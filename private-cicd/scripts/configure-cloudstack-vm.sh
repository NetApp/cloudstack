#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Purpose:
#   Follow the Apache Quick Installation Guide order: OS and NFS, MySQL with
#   QIG tunables, local apt repo, cloudstack-management, setup-databases with
#   --force-recreate so leftover snapshot schemas are not reused,
#   cloudstack-setup-management --no-start (4.23 seeds the KVM system-VM
#   template without starting the JVM), then cloudstack-agent. Set integration
#   API port 8096 before the first management start. Management starts once at
#   the end so the 4.0.0-to-4.23 schema upgrade is not interrupted.
#   Package installs go through an apt wrapper: apt-daily and unattended-upgrades
#   are masked up front, each apt call waits on the dpkg lock, and if the lock is
#   still held only apt-family holders are killed before dpkg is repaired and the
#   call retried once.
# Dependencies:
#   Must run as root on the target VM. python3 (stdlib json).
#   DEB_DIR — cloudstack-{common,agent,management}_*.deb from build-debs.sh.
#   SECRETS_FILE — JSON from render-phase2-config.py (MySQL/cloud DB users).
#   ONTAP_CONFIG — JSON from render-phase2-config.py (management IP).
#   BRIDGE — KVM bridge name (default cloudbr0). Nested virt (/dev/kvm) expected.
#   Apt packages: mysql-server, nfs-kernel-server, open-iscsi, qemu-kvm,
#   libvirt-daemon-system, nginx, dpkg-dev, uuid-runtime, etc.
#   The local repository under /var/www/html/repos/cloudstack is the only
#   CloudStack package source; third-party CloudStack apt lists are removed.
#   Called by run-phase2-vm.sh. Does not call other Phase 2 scripts.

set -euo pipefail

DEB_DIR="${DEB_DIR:?DEB_DIR is required}"
SECRETS_FILE="${SECRETS_FILE:?SECRETS_FILE is required}"
ONTAP_CONFIG="${ONTAP_CONFIG:?ONTAP_CONFIG is required}"
BRIDGE="${BRIDGE:-cloudbr0}"

if [[ "$(id -u)" -ne 0 ]]; then
    echo "CloudStack package deployment must run as root" >&2
    exit 1
fi

json_value() {
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' \
        "$SECRETS_FILE" "$1"
}

single_package() {
    local package_name="$1"
    local matches=("$DEB_DIR/${package_name}_"*.deb)
    if [[ "${#matches[@]}" -ne 1 || ! -f "${matches[0]}" ]]; then
        echo "Expected exactly one package for $package_name in $DEB_DIR" >&2
        exit 1
    fi
    printf '%s\n' "${matches[0]}"
}

APT_LOCK_TIMEOUT_SECONDS=300
# Matches the apt family only, so an unrelated process is never signalled.
apt_process_pattern='unattended-upgrade|apt\.systemd\.daily|apt-get|dpkg|(^| |/)apt( |$)'

# fuser and lsof are not guaranteed on the VM, so read the lock owners from /proc.
apt_lock_holders() {
    local pid_dir pid fd_link target
    for pid_dir in /proc/[0-9]*; do
        pid="${pid_dir##*/}"
        for fd_link in "$pid_dir"/fd/*; do
            target="$(readlink -f "$fd_link" 2>/dev/null || true)"
            case "$target" in
                /var/lib/dpkg/lock-frontend|/var/lib/dpkg/lock|/var/lib/apt/lists/lock|/var/cache/apt/archives/lock)
                    printf '%s\n' "$pid"
                    ;;
            esac
        done
    done | sort -u
}

# Returns 0 when at least one process was signalled.
signal_apt_lock_holders() {
    local signal="$1"
    local pid cmdline signalled=1
    for pid in $(apt_lock_holders); do
        if [[ "$pid" == "$$" ]]; then
            continue
        fi
        cmdline="$(tr '\0' ' ' <"/proc/${pid}/cmdline" 2>/dev/null || true)"
        if [[ -z "$cmdline" ]]; then
            continue
        fi
        if [[ ! "$cmdline" =~ $apt_process_pattern ]]; then
            echo "PID ${pid} holds an apt lock but is not an apt process, leaving it: ${cmdline}" >&2
            continue
        fi
        echo "Sending SIG${signal} to apt lock holder PID ${pid}: ${cmdline}" >&2
        kill "-${signal}" "$pid" 2>/dev/null || true
        signalled=0
    done
    return "$signalled"
}

free_apt_lock() {
    if signal_apt_lock_holders TERM; then
        sleep 10
        signal_apt_lock_holders KILL || true
        sleep 2
    fi
}

apt_get() {
    local attempt status=0
    for attempt in 1 2; do
        set +e
        apt-get -o "DPkg::Lock::Timeout=${APT_LOCK_TIMEOUT_SECONDS}" "$@"
        status=$?
        set -e
        if [[ "$status" -eq 0 ]]; then
            return 0
        fi
        if [[ "$attempt" -eq 2 ]]; then
            break
        fi
        echo "apt-get exited with ${status}; looking for processes holding the dpkg lock" >&2
        free_apt_lock
        echo "Repairing dpkg state before retrying apt-get"
        dpkg --configure -a || true
        apt-get -o "DPkg::Lock::Timeout=${APT_LOCK_TIMEOUT_SECONDS}" -f install -y || true
    done
    return "$status"
}

# Schema-upgrade tracing. Each call records the cloud database version, table
# counts, and management-server state as one NDJSON line, so an interrupted
# 4.0.0-to-4.23 upgrade can be reconstructed from the stage log or the VM file.
DB_TRACE_FILE=/var/log/cloudstack-phase2-debug.ndjson
db_trace_log() {
    local line
    line="$(printf '{"runId":"phase2","checkpoint":"%s","location":"configure-cloudstack-vm.sh","data":%s,"timestamp":%s000}' \
        "$1" "$2" "$(date +%s)")"
    echo "PHASE2_DB_TRACE ${line}"
    echo "$line" >>"$DB_TRACE_FILE" 2>/dev/null || true
}
db_trace_state() {
    local ver rel tables mgmt_active mgmt_pid mgmt_since upgrade_errors dup_errors
    ver="$("${mysql_root[@]}" --batch --skip-column-names cloud \
        -e "SELECT IFNULL(GROUP_CONCAT(CONCAT(version,'/',step)),'none') FROM version" 2>/dev/null || echo NA)"
    rel="$("${mysql_root[@]}" --batch --skip-column-names \
        -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='async_job' AND COLUMN_NAME='related'" 2>/dev/null || echo NA)"
    tables="$("${mysql_root[@]}" --batch --skip-column-names \
        -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='cloud'" 2>/dev/null || echo NA)"
    mgmt_active="$(systemctl is-active cloudstack-management 2>/dev/null || true)"
    mgmt_pid="$(systemctl show -p MainPID --value cloudstack-management 2>/dev/null || true)"
    mgmt_since="$(systemctl show -p ActiveEnterTimestamp --value cloudstack-management 2>/dev/null || true)"
    upgrade_errors="$(grep -c 'Unable to upgrade the database' /var/log/cloudstack/management/management-server.log 2>/dev/null || echo 0)"
    dup_errors="$(grep -c 'Duplicate column name\|Duplicate key name' /var/log/cloudstack/management/management-server.log 2>/dev/null || echo 0)"
    db_trace_log "$1" "$(printf '{"version":"%s","async_job_related":"%s","cloud_tables":"%s","mgmt_active":"%s","mgmt_pid":"%s","mgmt_since":"%s","upgrade_errors":"%s","duplicate_errors":"%s"}' \
        "$ver" "$rel" "$tables" "$mgmt_active" "$mgmt_pid" "$mgmt_since" "$upgrade_errors" "$dup_errors")"
}

cloud_db_user="$(json_value cloudDbUser)"
cloud_db_password="$(json_value cloudDbPassword)"
mysql_root_user="$(json_value mysqlRootUser)"
mysql_root_password="$(json_value mysqlRootPassword)"

single_package cloudstack-common >/dev/null
single_package cloudstack-agent >/dev/null
management_deb="$(single_package cloudstack-management)"

package_version="$(dpkg-deb -f "$management_deb" Version)"
repo_version="$(cut -d. -f1,2 <<<"$package_version")"
repo_dir="/var/www/html/repos/cloudstack/${repo_version}"
management_ip="$(
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["mgtSvr"][0]["mgtSvrIp"])' \
        "$ONTAP_CONFIG"
)"

export DEBIAN_FRONTEND=noninteractive

# Ubuntu runs apt-daily and unattended-upgrades from timers shortly after boot,
# which takes the dpkg lock while Phase 2 is installing. Minimal images may not
# ship every unit, so failures here are not fatal.
for apt_unit in apt-daily.timer apt-daily-upgrade.timer \
        apt-daily.service apt-daily-upgrade.service unattended-upgrades.service; do
    echo "Disabling ${apt_unit} for the duration of this deployment"
    systemctl disable --now "$apt_unit" >/dev/null 2>&1 || true
    systemctl mask "$apt_unit" >/dev/null 2>&1 || true
done

shopt -s nullglob
for apt_list in /etc/apt/sources.list.d/*cloudstack*.list \
        /etc/apt/sources.list.d/*cloudstack*.list.disabled; do
    echo "Removing third-party CloudStack apt source: $apt_list"
    rm -f "$apt_list"
done
shopt -u nullglob
apt_get update
apt_get install -y \
    curl \
    dpkg-dev \
    libvirt-daemon-system \
    mysql-server \
    nfs-common \
    nfs-kernel-server \
    nginx \
    open-iscsi \
    python3-pip \
    python3-venv \
    qemu-kvm \
    quota \
    rpcbind \
    uuid-runtime

mysqld_config=/etc/mysql/mysql.conf.d/mysqld.cnf
set_mysqld_property() {
    local key="$1"
    local value="$2"
    if grep -Eq "^#?${key}[[:space:]]*=" "$mysqld_config"; then
        sed -i -E "s|^#?${key}[[:space:]]*=.*|${key}=${value}|" "$mysqld_config"
    else
        printf '%s=%s\n' "$key" "$value" >> "$mysqld_config"
    fi
}
set_mysqld_property innodb_rollback_on_timeout 1
set_mysqld_property innodb_lock_wait_timeout 600
set_mysqld_property max_connections 350
set_mysqld_property log_bin mysql-bin
set_mysqld_property binlog_format ROW

systemctl enable mysql
systemctl restart mysql

mysql_root=(mysql "--user=$mysql_root_user")
if [[ -n "$mysql_root_password" ]]; then
    mysql_root+=("--password=$mysql_root_password")
fi

mysql_cloud=(mysql "--user=$cloud_db_user")
if [[ -n "$cloud_db_password" ]]; then
    mysql_cloud+=("--password=$cloud_db_password")
fi

# A freshly installed mysql-server leaves the administrator on socket
# authentication with no password, so apply the configured one before anything
# connects with a password. Skipped when the account already accepts it.
if [[ -n "$mysql_root_password" ]] && ! "${mysql_root[@]}" -e 'SELECT 1' >/dev/null 2>&1; then
    if ! mysql "--user=$mysql_root_user" -e 'SELECT 1' >/dev/null 2>&1; then
        echo "Cannot reach MySQL as $mysql_root_user over the local socket to set its password" >&2
        exit 1
    fi
    # mysql_native_password is absent from MySQL 8.4 and newer.
    mysql_auth_plugin=caching_sha2_password
    mysql_native_available="$(
        mysql "--user=$mysql_root_user" --batch --skip-column-names -e \
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.PLUGINS
             WHERE PLUGIN_NAME='mysql_native_password' AND PLUGIN_STATUS='ACTIVE'"
    )"
    if [[ "$mysql_native_available" == 1 ]]; then
        mysql_auth_plugin=mysql_native_password
    fi
    echo "Setting the MySQL $mysql_root_user password for unattended lab setup"
    mysql "--user=$mysql_root_user" -e \
        "ALTER USER '${mysql_root_user}'@'localhost'
         IDENTIFIED WITH ${mysql_auth_plugin} BY '${mysql_root_password}'; FLUSH PRIVILEGES;"
    "${mysql_root[@]}" -e 'SELECT 1' >/dev/null
fi

systemctl enable --now iscsid
systemctl enable --now rpcbind
systemctl enable --now nfs-kernel-server

mkdir -p /export/primary /export/secondary
if ! grep -Fqx '/export/primary *(rw,async,no_root_squash,no_subtree_check)' /etc/exports; then
    echo '/export/primary *(rw,async,no_root_squash,no_subtree_check)' >> /etc/exports
fi
if ! grep -Fqx '/export/secondary *(rw,async,no_root_squash,no_subtree_check)' /etc/exports; then
    echo '/export/secondary *(rw,async,no_root_squash,no_subtree_check)' >> /etc/exports
fi
exportfs -ra
systemctl restart nfs-kernel-server

if systemctl is-active --quiet docker 2>/dev/null; then
    iptables -P FORWARD ACCEPT
    systemctl disable --now docker
fi

if command -v ufw >/dev/null 2>&1; then
    ufw disable
fi

rm -rf "$repo_dir"
mkdir -p "$repo_dir"
cp "$DEB_DIR"/*.deb "$repo_dir/"
(
    cd "$repo_dir"
    dpkg-scanpackages . /dev/null > Packages
    gzip -9c Packages > Packages.gz
)
chown -R www-data:www-data /var/www/html/repos
chmod -R 755 /var/www/html/repos
systemctl enable --now nginx

repo_url="http://${management_ip}/repos/cloudstack/${repo_version}"
if ! curl -fsI "${repo_url}/Packages.gz" >/dev/null; then
    echo "The local CloudStack apt repository is not reachable at ${repo_url}" >&2
    exit 1
fi

printf 'deb [trusted=yes] %s ./\n' "$repo_url" \
    > /etc/apt/sources.list.d/cloudstack.list
apt_get update
apt_get install -y \
    "cloudstack-common=${package_version}" \
    "cloudstack-management=${package_version}"
apt-cache policy cloudstack-management

# Leftover cloud/cloud_usage from a previous CloudStack on the snapshot has
# vm_template but a version table that does not match the indexes, so 4.23
# then fails mid-upgrade (Duplicate key name uc_hypervisor) and never binds
# 8096. Always recreate against the packages just installed. Marvin rebuilds
# the zone afterward.
db_trace_state "before setup-databases"
systemctl stop cloudstack-management || true
echo "Creating CloudStack databases (force-recreate if they already exist)"
cloudstack-setup-databases \
    "${cloud_db_user}:${cloud_db_password}@localhost" \
    "--deploy-as=${mysql_root_user}:${mysql_root_password}" \
    --force-recreate
db_trace_state "after setup-databases"
# The 4.0.0 schema has no integration.api.port row, so an UPDATE here matches
# nothing and management boots with the ConfigKey default of 0, which disables
# the listener. The key is not dynamic, so the row has to exist before start.
"${mysql_cloud[@]}" cloud -e \
    "INSERT INTO configuration (category, instance, component, name, value)
     VALUES ('Advanced', 'DEFAULT', 'management-server', 'integration.api.port', '8096')
     ON DUPLICATE KEY UPDATE value = '8096';"
integration_port="$("${mysql_cloud[@]}" --batch --skip-column-names cloud -e \
    "SELECT value FROM configuration WHERE name = 'integration.api.port';")"
if [[ "$integration_port" != "8096" ]]; then
    echo "integration.api.port is '${integration_port}', not 8096; the integration API would never bind" >&2
    exit 1
fi

setup_management=(cloudstack-setup-management --no-start)
if cloudstack-setup-management --help 2>&1 | grep -q -- '--systemvm-templates'; then
    setup_management+=(--systemvm-templates kvm-x86_64)
else
    echo "cloudstack-setup-management has no --systemvm-templates; installing the KVM template before management starts" >&2
    systemctl stop cloudstack-management || true
    /usr/share/cloudstack-common/scripts/storage/secondary/cloud-install-sys-tmplt \
        -m /export/secondary \
        -u http://download.cloudstack.org/systemvm/4.22/systemvmtemplate-4.22.0-x86_64-kvm.qcow2.bz2 \
        -h kvm \
        -F
fi
"${setup_management[@]}"
db_trace_state "after cloudstack-setup-management --no-start"

apt_get install -y "cloudstack-agent=${package_version}"
apt-cache policy cloudstack-agent

sed -i -E 's|^#?vnc_listen.*|vnc_listen = "0.0.0.0"|' /etc/libvirt/qemu.conf
if ! grep -q '^LIBVIRTD_ARGS=' /etc/default/libvirtd; then
    echo 'LIBVIRTD_ARGS="--listen"' >> /etc/default/libvirtd
else
    sed -i 's|^LIBVIRTD_ARGS=.*|LIBVIRTD_ARGS="--listen"|' /etc/default/libvirtd
fi
set_libvirt_property() {
    local key="$1"
    local value="$2"
    if grep -Eq "^#?${key}[[:space:]]*=" /etc/libvirt/libvirtd.conf; then
        sed -i -E "s|^#?${key}[[:space:]]*=.*|${key} = ${value}|" /etc/libvirt/libvirtd.conf
    else
        printf '%s = %s\n' "$key" "$value" >> /etc/libvirt/libvirtd.conf
    fi
}
set_libvirt_property listen_tls 0
set_libvirt_property listen_tcp 1
set_libvirt_property tcp_port '"16509"'
set_libvirt_property mdns_adv 0
set_libvirt_property auth_tcp '"none"'
systemctl mask \
    libvirtd.socket \
    libvirtd-ro.socket \
    libvirtd-admin.socket \
    libvirtd-tls.socket \
    libvirtd-tcp.socket
systemctl enable --now libvirtd

agent_properties=/etc/cloudstack/agent/agent.properties
touch "$agent_properties"
set_agent_property() {
    local key="$1"
    local value="$2"
    if grep -q "^${key}=" "$agent_properties"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$agent_properties"
    else
        printf '%s=%s\n' "$key" "$value" >> "$agent_properties"
    fi
}

if ! grep -q '^guid=.\+' "$agent_properties"; then
    set_agent_property guid "$(uuidgen)"
fi
set_agent_property public.network.device "$BRIDGE"
set_agent_property private.network.device "$BRIDGE"

systemctl daemon-reload
systemctl enable cloudstack-management cloudstack-agent
systemctl restart libvirtd
libvirtd_wait=0
until systemctl is-active --quiet libvirtd; do
    if (( libvirtd_wait >= 30 )); then
        echo "libvirtd did not become active after restart" >&2
        systemctl status libvirtd --no-pager || true
        exit 1
    fi
    sleep 1
    libvirtd_wait=$((libvirtd_wait + 1))
done
db_trace_state "before first start of cloudstack-management"
systemctl start cloudstack-management
systemctl restart cloudstack-agent
db_trace_state "after first start of cloudstack-management"
