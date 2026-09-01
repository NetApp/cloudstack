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
#   Jenkins-driver Phase 2: render ontap.cfg/secrets, wait for SSH after snapshot
#   revert, copy debs, ONTAP tests, and the Marvin archive to the VM, run
#   run-phase2-vm.sh remotely, copy results back, delete the remote work dir.
# Dependencies:
#   python3, sha256sum, ssh, scp, ssh-keyscan, ssh-keygen, sshpass, tar. Results
#   come back over ssh|tar because scp rejects "$remote:dir/.". Calls render-phase2-config.py then
#   copies configure-cloudstack-vm.sh, check-phase2-health.sh, collect-phase2-logs.sh,
#   marvin-run.sh, redact-phase2-results.py, run-phase2-vm.sh, and the trusted
#   check_ontap_prereqs.py. Debian packages are transferred as one tar archive.
#   SOURCE_DIR (ONTAP tests), DEB_DIR (debs, manifest, and marvin/Marvin-*.tar.gz),
#   RESULT_DIR, INVENTORY_FILE, VM_ID, VM_HOST, VM_SSH_USER, VM_SSH_PASSWORD,
#   SOURCE_ID, SOURCE_SHA (PR_ID and PR_HEAD_SHA accepted as fallbacks); these
#   name the remote work directory. Optional BRIDGE, SSH_WAIT_SECONDS.
#   Generates a one-run SSH key, installs the public key with sshpass, then
#   uses the private key for remaining ssh/scp. Credential env vars required by
#   render-phase2-config.py.
#   Does not revert the VM; Jenkins calls vcenter-revert.py first.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SOURCE_DIR:?SOURCE_DIR is required}"
DEB_DIR="${DEB_DIR:?DEB_DIR is required}"
RESULT_DIR="${RESULT_DIR:?RESULT_DIR is required}"
INVENTORY_FILE="${INVENTORY_FILE:?INVENTORY_FILE is required}"
VM_ID="${VM_ID:?VM_ID is required}"
VM_HOST="${VM_HOST:?VM_HOST is required}"
VM_SSH_USER="${VM_SSH_USER:?VM_SSH_USER is required}"
VM_SSH_PASSWORD="${VM_SSH_PASSWORD:?VM_SSH_PASSWORD is required}"
BRIDGE="${BRIDGE:-cloudbr0}"
SOURCE_ID="${SOURCE_ID:-${PR_ID:-}}"
SOURCE_SHA="${SOURCE_SHA:-${PR_HEAD_SHA:-}}"
: "${SOURCE_ID:?SOURCE_ID (or PR_ID) is required}"
: "${SOURCE_SHA:?SOURCE_SHA (or PR_HEAD_SHA) is required}"
SSH_WAIT_SECONDS="${SSH_WAIT_SECONDS:-900}"

mkdir -p "$RESULT_DIR"
RESULT_DIR="$(cd "$RESULT_DIR" && pwd)"
rm -rf "$RESULT_DIR"
mkdir -p "$RESULT_DIR"

work_dir="$(mktemp -d)"
ssh_key="$work_dir/id"
known_hosts="$work_dir/known_hosts"
assets_archive="$work_dir/phase2-assets.tar.gz"
debs_archive="$work_dir/phase2-debs.tar.gz"
assets_root="$work_dir/assets"
ontap_config="$work_dir/ontap.cfg"
secrets_file="$work_dir/secrets.json"
trap 'unset SSHPASS; rm -rf "$work_dir"' EXIT

repo_root="$(cd "$SCRIPT_DIR/../.." && pwd)"
trusted_prereqs="$repo_root/test/integration/plugins/ontap/check_ontap_prereqs.py"
if [[ ! -f "$trusted_prereqs" ]]; then
    echo "Trusted ONTAP prerequisite checker is missing: $trusted_prereqs" >&2
    exit 1
fi

ssh-keygen -t ed25519 -N '' -f "$ssh_key" -C "cloudstack-presubmit-$(date -u +%Y%m%dT%H%M%SZ)" >/dev/null
chmod 600 "$ssh_key"

python3 "$SCRIPT_DIR/render-phase2-config.py" \
    --inventory "$INVENTORY_FILE" \
    --vm-id "$VM_ID" \
    --ontap-config "$ontap_config" \
    --secrets "$secrets_file"

test -d "$SOURCE_DIR/test/integration/plugins/ontap"
test -f "$DEB_DIR/package-manifest.tsv"
test -f "$DEB_DIR/marvin/SHA256SUMS"

shopt -s nullglob
marvin_archives=("$DEB_DIR"/marvin/Marvin-*.tar.gz)
shopt -u nullglob
if [[ "${#marvin_archives[@]}" -ne 1 ]]; then
    echo "Expected one staged Marvin archive under $DEB_DIR/marvin; found ${#marvin_archives[@]}" >&2
    exit 1
fi
(cd "$DEB_DIR/marvin" && sha256sum --check SHA256SUMS)

(
    cd "$DEB_DIR"
    tar -czf "$debs_archive" ./*.deb package-manifest.tsv
)

mkdir -p "$assets_root/test/integration/plugins" "$assets_root/marvin"
cp -a "$SOURCE_DIR/test/integration/plugins/ontap" "$assets_root/test/integration/plugins/"
cp "${marvin_archives[0]}" "$assets_root/marvin/"
tar -czf "$assets_archive" -C "$assets_root" .

remote="${VM_SSH_USER}@${VM_HOST}"
remote_root="/tmp/cloudstack-phase2-${SOURCE_ID}-${SOURCE_SHA:0:12}"
remote_scripts="$remote_root/scripts"
remote_source="$remote_root/source"
remote_debs="$remote_root/debs"
remote_results="$remote_root/results"

ssh_options=(
    -i "$ssh_key"
    -o BatchMode=yes
    -o ConnectTimeout=15
    -o IdentitiesOnly=yes
    -o ServerAliveInterval=60
    -o ServerAliveCountMax=120
    -o StrictHostKeyChecking=yes
    -o "UserKnownHostsFile=$known_hosts"
)
password_ssh_options=(
    -o PreferredAuthentications=password
    -o PubkeyAuthentication=no
    -o NumberOfPasswordPrompts=1
    -o ConnectTimeout=15
    -o StrictHostKeyChecking=yes
    -o "UserKnownHostsFile=$known_hosts"
)

echo "Waiting up to ${SSH_WAIT_SECONDS}s for password SSH to $remote"
export SSHPASS="$VM_SSH_PASSWORD"
deadline=$((SECONDS + SSH_WAIT_SECONDS))
while true; do
    if ssh-keyscan -T 10 "$VM_HOST" > "$known_hosts.tmp" 2>/dev/null; then
        mv "$known_hosts.tmp" "$known_hosts"
        if sshpass -e ssh "${password_ssh_options[@]}" "$remote" true >/dev/null 2>&1; then
            break
        fi
    fi
    if (( SECONDS >= deadline )); then
        echo "Timed out waiting for password SSH to $remote" >&2
        exit 1
    fi
    sleep 10
done

echo "Installing a one-run SSH public key on $remote"
pubkey="$(cat "$ssh_key.pub")"
sshpass -e ssh "${password_ssh_options[@]}" "$remote" \
    "umask 077; mkdir -p .ssh; touch .ssh/authorized_keys; chmod 700 .ssh; chmod 600 .ssh/authorized_keys"
printf '%s\n' "$pubkey" | sshpass -e ssh "${password_ssh_options[@]}" "$remote" \
    'cat >> .ssh/authorized_keys'
unset SSHPASS
unset VM_SSH_PASSWORD

if ! ssh "${ssh_options[@]}" "$remote" true >/dev/null 2>&1; then
    echo "Password bootstrap succeeded but key SSH to $remote failed" >&2
    exit 1
fi

ssh "${ssh_options[@]}" "$remote" \
    "rm -rf '$remote_root' && mkdir -p '$remote_scripts' '$remote_source' '$remote_debs' '$remote_results'"

scp "${ssh_options[@]}" \
    "$SCRIPT_DIR/configure-cloudstack-vm.sh" \
    "$SCRIPT_DIR/check-phase2-health.sh" \
    "$SCRIPT_DIR/collect-phase2-logs.sh" \
    "$SCRIPT_DIR/marvin-run.sh" \
    "$SCRIPT_DIR/redact-phase2-results.py" \
    "$SCRIPT_DIR/run-phase2-vm.sh" \
    "$trusted_prereqs" \
    "$remote:$remote_scripts/"
scp "${ssh_options[@]}" "$debs_archive" "$remote:$remote_root/"
scp "${ssh_options[@]}" "$assets_archive" "$remote:$remote_root/"
scp "${ssh_options[@]}" "$ontap_config" "$remote:$remote_root/ontap.cfg"
scp "${ssh_options[@]}" "$secrets_file" "$remote:$remote_root/secrets.json"

ssh "${ssh_options[@]}" "$remote" \
    "tar -xzf '$remote_root/phase2-debs.tar.gz' -C '$remote_debs' && \
     tar -xzf '$remote_root/phase2-assets.tar.gz' -C '$remote_source' && \
     cp '$remote_root/ontap.cfg' '$remote_source/test/integration/plugins/ontap/ontap.cfg' && \
     chmod 600 '$remote_root/secrets.json' '$remote_source/test/integration/plugins/ontap/ontap.cfg' && \
     chmod +x '$remote_scripts/'*.sh"

printf -v remote_command \
    'PHASE2_ROOT=%q BRIDGE=%q %q/run-phase2-vm.sh' \
    "$remote_root" "$BRIDGE" "$remote_scripts"

set +e
ssh "${ssh_options[@]}" "$remote" "$remote_command"
phase2_status=$?
set -e

# Transferred as a stream because scp rejects the '.' entry name that
# "$remote:dir/." produces. pipefail is still in effect under set +e, so $?
# reports a failure on either side of the pipe.
set +e
ssh "${ssh_options[@]}" "$remote" "tar -czf - -C '$remote_results' ." \
    | tar -xzf - -C "$RESULT_DIR"
result_copy_status=$?
set -e
ssh "${ssh_options[@]}" "$remote" "rm -rf '$remote_root'" || true

if [[ "$phase2_status" -eq 0 && "$result_copy_status" -ne 0 ]]; then
    echo "Phase 2 passed but result retrieval failed" >&2
    exit "$result_copy_status"
fi
exit "$phase2_status"
