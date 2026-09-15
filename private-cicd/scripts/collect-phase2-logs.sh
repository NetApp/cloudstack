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
#   Capture dpkg, systemd, journal, virsh, network, NFS, disk, and CloudStack /
#   Marvin log trees into OUTPUT_DIR/runtime-logs. Best-effort (does not fail
#   the job if a capture command fails).
# Dependencies:
#   OUTPUT_DIR (required). Commands: dpkg-query, systemctl, journalctl, virsh,
#   ip, iptables-save, exportfs, df, free. Run on the target VM.
#   Called from run-phase2-vm.sh EXIT trap. Pair with redact-phase2-results.py.

set -u

OUTPUT_DIR="${OUTPUT_DIR:?OUTPUT_DIR is required}"
logs_dir="$OUTPUT_DIR/runtime-logs"
mkdir -p "$logs_dir"

capture() {
    local output="$1"
    shift
    "$@" > "$logs_dir/$output" 2>&1 || true
}

capture dpkg-cloudstack.txt dpkg-query -W 'cloudstack-*'
capture systemctl-failed.txt systemctl --failed --no-pager
capture systemctl-cloudstack-management.txt systemctl status cloudstack-management --no-pager
capture systemctl-cloudstack-agent.txt systemctl status cloudstack-agent --no-pager
capture systemctl-libvirtd.txt systemctl status libvirtd --no-pager
capture systemctl-mysql.txt systemctl status mysql --no-pager
capture journal-cloudstack-management.txt journalctl -u cloudstack-management --no-pager
capture journal-cloudstack-agent.txt journalctl -u cloudstack-agent --no-pager
capture journal-libvirtd.txt journalctl -u libvirtd --no-pager
capture journal-mysql.txt journalctl -u mysql --no-pager
capture virsh.txt virsh -c qemu:///system list --all
capture ip-address.txt ip address show
capture ip-route.txt ip route show
capture iptables.txt iptables-save
capture exports.txt exportfs -v
capture disk-usage.txt df -h
capture memory.txt free -h

for source_dir in \
    /var/log/cloudstack/management \
    /var/log/cloudstack/agent \
    /var/log/mysql \
    /tmp/MarvinLogs; do
    if [[ -d "$source_dir" ]]; then
        target_name="$(echo "$source_dir" | sed 's|^/||; s|/|-|g')"
        cp -a "$source_dir" "$logs_dir/$target_name" 2>/dev/null || true
    fi
done

find "$OUTPUT_DIR" -type f -exec chmod 0644 {} +
