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
#   Verify the VM is ready for Marvin: systemd units, dpkg versions vs manifest,
#   /dev/kvm, bridge, NFS exports, ONTAP plugin, cloud DB, integration API
#   on 8096, ONTAP HTTPS :443, and SVM protocol/LIF prerequisites.
# Dependencies:
#   Run after configure-cloudstack-vm.sh. python3, curl, mysql, virsh, dpkg-query.
#   MANIFEST_FILE — package-manifest.tsv from build-debs.sh.
#   ONTAP_CONFIG — JSON from render-phase2-config.py.
#   BRIDGE (default cloudbr0), HEALTH_TIMEOUT_SECONDS (default 900).
#   check_ontap_prereqs.py — prefer the copy next to this script (trusted CI
#   checkout); fall back to the file beside ontap.cfg.
#   Called by run-phase2-vm.sh.

set -euo pipefail

MANIFEST_FILE="${MANIFEST_FILE:?MANIFEST_FILE is required}"
ONTAP_CONFIG="${ONTAP_CONFIG:?ONTAP_CONFIG is required}"
BRIDGE="${BRIDGE:-cloudbr0}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-900}"

required_capabilities=(mysql libvirtd iscsid nfs-kernel-server cloudstack-agent cloudstack-management)
for service_name in "${required_capabilities[@]}"; do
    if ! systemctl is-active --quiet "$service_name"; then
        systemctl status "$service_name" --no-pager || true
        echo "Required service is not active: $service_name" >&2
        exit 1
    fi
done

while IFS=$'\t' read -r package_name expected_version architecture file_name; do
    if [[ "$package_name" == "PACKAGE" ]]; then
        continue
    fi
    actual_version="$(dpkg-query -W -f='${Version}' "$package_name")"
    if [[ "$actual_version" != "$expected_version" ]]; then
        echo "$package_name version mismatch: expected $expected_version, found $actual_version" >&2
        exit 1
    fi
    echo "$package_name $actual_version $architecture $file_name"
done < "$MANIFEST_FILE"

test -c /dev/kvm
ip link show "$BRIDGE" >/dev/null
management_ip="$(
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["mgtSvr"][0]["mgtSvrIp"])' \
        "$ONTAP_CONFIG"
)"
ip -4 address show | grep -Fq "$management_ip/"
virsh -c qemu:///system list --all >/dev/null
grep -q '^InitiatorName=iqn\.' /etc/iscsi/initiatorname.iscsi
exportfs -v | grep -q '/export/primary'
exportfs -v | grep -q '/export/secondary'

# The plugin is shaded into the management uber-jar, not shipped standalone.
if ! python3 - <<'PY'
import glob
import sys
import zipfile

resource = "META-INF/cloudstack/storage-volume-ontap/module.properties"
for jar in glob.glob("/usr/share/cloudstack-management/lib/cloudstack-*.jar"):
    with zipfile.ZipFile(jar) as archive:
        if resource in archive.namelist():
            print("ONTAP plugin found in", jar)
            sys.exit(0)
sys.exit(1)
PY
then
    echo "The NetApp ONTAP plugin is missing from cloudstack-management" >&2
    exit 1
fi

mapfile -t db_credentials < <(
    python3 - "$ONTAP_CONFIG" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as stream:
    config = json.load(stream)["dbSvr"]
print(config["user"])
print(config["passwd"])
PY
)
mysql "--user=${db_credentials[0]}" "--password=${db_credentials[1]}" \
    --batch --skip-column-names cloud -e 'SELECT 1' | grep -Fxq 1

deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
until curl --fail --silent --show-error \
        'http://127.0.0.1:8096/client/api?command=listCapabilities&response=json' \
        >/dev/null; do
    if (( SECONDS >= deadline )); then
        echo "CloudStack integration API did not become ready on port 8096" >&2
        exit 1
    fi
    sleep 15
done

python3 - "$ONTAP_CONFIG" <<'PY'
import json
import socket
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    host = json.load(stream)["ontap"]["storageIP"]
with socket.create_connection((host, 443), timeout=10):
    pass
print("ONTAP HTTPS reachable:", host)
PY

prereq_script="$(dirname "${BASH_SOURCE[0]}")/check_ontap_prereqs.py"
if [[ ! -f "$prereq_script" ]]; then
    prereq_script="$(dirname "$ONTAP_CONFIG")/check_ontap_prereqs.py"
fi
if [[ ! -f "$prereq_script" ]]; then
    echo "check_ontap_prereqs.py was not found next to this script or ontap.cfg" >&2
    exit 1
fi
python3 "$prereq_script" "$ONTAP_CONFIG" both
