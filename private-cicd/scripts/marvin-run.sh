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
#   Run ONTAP Marvin on the VM: install the staged archive with setup_env, then
#   run run_tests.sh for setup_zone, iscsi, and nfs3. Copies ONTAP results into
#   PHASE2_RESULT_DIR.
# Dependencies:
#   CLOUDSTACK_DIR — source tree with test/integration/plugins/ontap (required),
#   MARVIN_ARCHIVE — Marvin-*.tar.gz produced by build-debs.sh (required).
#   PHASE2_RESULT_DIR — log/result output (required).
#   ontap.cfg must already exist under test/integration/plugins/ontap/
#   (copied from render-phase2-config.py). Calls setup_env.sh and run_tests.sh.
#   Management API must be healthy (check-phase2-health.sh). Called by run-phase2-vm.sh.

set -euo pipefail

CLOUDSTACK_DIR="${CLOUDSTACK_DIR:?Set CLOUDSTACK_DIR}"
PHASE2_RESULT_DIR="${PHASE2_RESULT_DIR:?Set PHASE2_RESULT_DIR}"
MARVIN_ARCHIVE="${MARVIN_ARCHIVE:?Set MARVIN_ARCHIVE}"
if [[ ! -f "$MARVIN_ARCHIVE" ]]; then
    echo "Marvin archive does not exist: $MARVIN_ARCHIVE" >&2
    exit 1
fi

cd "$CLOUDSTACK_DIR"
mkdir -p "$PHASE2_RESULT_DIR"

echo "==> Preparing Marvin environment"
MARVIN_ARCHIVE="$MARVIN_ARCHIVE" \
    bash test/integration/plugins/ontap/setup_env.sh \
    2>&1 | tee "$PHASE2_RESULT_DIR/setup-env.log"

run_suite() {
    local filter="$1"
    local log_file="$PHASE2_RESULT_DIR/${filter}.log"

    set +e
    bash test/integration/plugins/ontap/run_tests.sh "$filter" \
        2>&1 | tee "$log_file"
    local status="${PIPESTATUS[0]}"
    set -e
    return "$status"
}

echo "==> Creating and validating the CloudStack zone"
if ! run_suite setup_zone; then
    echo "Zone setup failed; protocol suites cannot run" >&2
    exit 1
fi

suite_status=0
echo "==> Running ONTAP iSCSI suites"
run_suite iscsi || suite_status=1

echo "==> Running ONTAP NFS3 suites"
run_suite nfs3 || suite_status=1

ontap_results=test/integration/plugins/ontap/results
if [[ -d "$ontap_results" ]]; then
    cp -a "$ontap_results" "$PHASE2_RESULT_DIR/ontap-results"
fi

exit "$suite_status"
