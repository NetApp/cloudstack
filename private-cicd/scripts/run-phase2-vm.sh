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
#   On-VM Phase 2 driver: configure CloudStack, health-check, run Marvin, then on
#   EXIT collect logs, redact secrets, write phase2.properties, and delete
#   secrets.json / ontap.cfg.
# Dependencies:
#   PHASE2_ROOT — scripts/, source/, debs/, results/, secrets.json (required).
#   BRIDGE (default cloudbr0). Must run as root on the target VM.
#   Calls: configure-cloudstack-vm.sh, check-phase2-health.sh, marvin-run.sh,
#   collect-phase2-logs.sh, redact-phase2-results.py.
#   debs/ must contain packages and package-manifest.tsv from build-debs.sh.
#   source/ must include ONTAP tests, marvin/Marvin-*.tar.gz, and ontap.cfg.
#   Invoked by run-phase2-remote.sh over SSH (or by hand on a throwaway VM).

set -euo pipefail

PHASE2_ROOT="${PHASE2_ROOT:?PHASE2_ROOT is required}"
BRIDGE="${BRIDGE:-cloudbr0}"

scripts_dir="$PHASE2_ROOT/scripts"
source_dir="$PHASE2_ROOT/source"
deb_dir="$PHASE2_ROOT/debs"
result_dir="$PHASE2_ROOT/results"
secrets_file="$PHASE2_ROOT/secrets.json"
ontap_config="$source_dir/test/integration/plugins/ontap/ontap.cfg"
manifest_file="$deb_dir/package-manifest.tsv"

mkdir -p "$result_dir"

shopt -s nullglob
marvin_archives=("$source_dir"/marvin/Marvin-*.tar.gz)
shopt -u nullglob
if [[ "${#marvin_archives[@]}" -ne 1 ]]; then
    echo "Expected one Marvin archive under $source_dir/marvin; found ${#marvin_archives[@]}" >&2
    exit 1
fi
marvin_archive="${marvin_archives[0]}"

on_exit() {
    status=$?
    trap - EXIT
    OUTPUT_DIR="$result_dir" "$scripts_dir/collect-phase2-logs.sh" || true
    python3 "$scripts_dir/redact-phase2-results.py" \
        --results "$result_dir" \
        --secrets "$secrets_file" \
        --ontap-config "$ontap_config" || true
    printf 'PHASE2_RESULT=%s\n' "$([[ "$status" -eq 0 ]] && echo SUCCESS || echo FAILURE)" \
        > "$result_dir/phase2.properties"
    rm -f "$secrets_file" "$ontap_config"
    exit "$status"
}
trap on_exit EXIT

DEB_DIR="$deb_dir" \
SECRETS_FILE="$secrets_file" \
ONTAP_CONFIG="$ontap_config" \
BRIDGE="$BRIDGE" \
    "$scripts_dir/configure-cloudstack-vm.sh" \
    2>&1 | tee "$result_dir/configure-cloudstack.log"

MANIFEST_FILE="$manifest_file" \
ONTAP_CONFIG="$ontap_config" \
BRIDGE="$BRIDGE" \
    "$scripts_dir/check-phase2-health.sh" \
    2>&1 | tee "$result_dir/health-check.log"

CLOUDSTACK_DIR="$source_dir" \
PHASE2_RESULT_DIR="$result_dir/marvin" \
MARVIN_ARCHIVE="$marvin_archive" \
    "$scripts_dir/marvin-run.sh"
