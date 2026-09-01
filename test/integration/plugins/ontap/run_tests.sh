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

# Run ONTAP Marvin integration tests by tag or protocol batch.
# Each test file runs individually so sequential test state is preserved.
#
# Usage (from cloudstack repo root):
#   bash test/integration/plugins/ontap/run_tests.sh              # setup + iscsi + nfs3
#   bash test/integration/plugins/ontap/run_tests.sh iscsi      # all iSCSI suites
#   bash test/integration/plugins/ontap/run_tests.sh nfs3       # all NFS3 suites
#   bash test/integration/plugins/ontap/run_tests.sh both         # iscsi then nfs3
#   bash test/integration/plugins/ontap/run_tests.sh nfs3_workflow  # single suite

ONTAP_DIR=test/integration/plugins/ontap
CFG=${ONTAP_DIR}/ontap.cfg
RESULTS_BASE=${ONTAP_DIR}/results
AGGREGATE=${ONTAP_DIR}/aggregate_results.py
NOSE_RUNNER=${ONTAP_DIR}/nose_compat.py
ONTAP_PREREQS=${ONTAP_DIR}/check_ontap_prereqs.py
export PYTHONPATH=${ONTAP_DIR}:${PYTHONPATH:-}
export PYTHONUNBUFFERED=1
FILTER="${1:-all}"

if [[ -x ${ONTAP_DIR}/.venv/bin/python ]]; then
    PYTHON=${ONTAP_DIR}/.venv/bin/python
else
    PYTHON=python3
fi

PASS=0
FAIL=0
SKIP=0
SUMMARY_FILE=$(mktemp)
BATCH_SUMMARY=""
BATCH_FAIL=0
GLOBAL_BATCH_FAIL=0
RUN_DIR=""
BATCH_PROTOCOL=""
BATCH_START=""
SUITE_RC_FILE=$(mktemp)

trap 'rm -f "$SUMMARY_FILE" "$SUITE_RC_FILE"; [[ -n "$BATCH_SUMMARY" && -f "$BATCH_SUMMARY" ]] && rm -f "$BATCH_SUMMARY"' EXIT

# ---------------------------------------------------------------------------
# Protocol suite definitions (label|tag|file)
# Order: pool lifecycle → with volumes → volume lifecycle → zone → VM last
# ---------------------------------------------------------------------------

ISCSI_SUITES=(
    "iSCSI pool lifecycle|iscsi_workflow|${ONTAP_DIR}/iscsi/pool/test_pool_lifecycle.py"
    "iSCSI pool with volumes|iscsi_with_volumes|${ONTAP_DIR}/iscsi/pool/test_pool_with_volumes.py"
    "iSCSI volume lifecycle|iscsi_volume|${ONTAP_DIR}/iscsi/volume/test_volume_lifecycle.py"
    "iSCSI zone-scoped pool|iscsi_zone_pool|${ONTAP_DIR}/iscsi/pool/test_zone_scoped_pool.py"
    "iSCSI VM volume workflow|iscsi_vm_workflow|${ONTAP_DIR}/iscsi/instance/test_vm_volume_attach.py"
)

NFS3_SUITES=(
    "NFS3 pool lifecycle|nfs3_workflow|${ONTAP_DIR}/nfs3/pool/test_pool_lifecycle.py"
    "NFS3 pool with volumes|nfs3_with_volumes|${ONTAP_DIR}/nfs3/pool/test_pool_with_volumes.py"
    "NFS3 volume lifecycle|nfs3_volume|${ONTAP_DIR}/nfs3/volume/test_volume_lifecycle.py"
    "NFS3 zone-scoped pool|zone_pool|${ONTAP_DIR}/nfs3/pool/test_zone_scoped_pool.py"
    "NFS3 VM volume attach|vm_volume_workflow|${ONTAP_DIR}/nfs3/instance/test_vm_volume_attach.py"
)

record_results() {
    local tag="$1"
    local label="$2"
    local results_file="$3"
    local dest="${4:-$SUMMARY_FILE}"
    $PYTHON -c "
import re, sys
tag, label, path = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, encoding='utf-8') as fh:
    for line in fh:
        line = line.rstrip('\n')
        m = re.search(r'TestName: (\S+) \| Status : (\S+)', line)
        if m:
            print('%s\t%s\t%s\t%s\t' % (tag, label, m.group(1), m.group(2)))
            continue
        m = re.match(r'(.+?) \.\.\. SKIP: (.+)$', line)
        if m:
            name = m.group(1).strip()
            if len(name) > 72:
                name = name[:69] + '...'
            detail = m.group(2).strip()
            if len(detail) > 120:
                detail = detail[:117] + '...'
            print('%s\t%s\t%s\tSKIP\t%s' % (tag, label, name, detail))
" "$tag" "$label" "$results_file" >> "$dest"
}

print_final_summary() {
    echo ""
    echo "================================================================"
    echo "  TEST SUMMARY"
    echo "================================================================"
    $PYTHON "$AGGREGATE" --out-dir "$(mktemp -d)" --summary-tsv "$SUMMARY_FILE" --print 2>/dev/null \
        | sed -n '/TEST SUMMARY/,$p' | tail -n +2
}

init_batch() {
    local protocol="$1"
    local parent_dir="${2:-}"

    BATCH_PROTOCOL="$protocol"
    BATCH_START=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    BATCH_FAIL=0
    local stamp
    stamp=$(date +"%Y%m%d-%H%M%S")

    if [[ -n "$parent_dir" ]]; then
        RUN_DIR="${parent_dir}/${protocol}"
    else
        RUN_DIR="${RESULTS_BASE}/${stamp}-${protocol}"
    fi

    mkdir -p "${RUN_DIR}/suites"
    : > "$SUITE_RC_FILE"
    BATCH_SUMMARY=$(mktemp)

    if [[ -z "$parent_dir" ]]; then
        ln -sfn "$(basename "$RUN_DIR")" "${RESULTS_BASE}/latest-${protocol}"
    fi

    echo ""
    echo "################################################################"
    echo "  Protocol batch: $(echo "$protocol" | tr '[:lower:]' '[:upper:]')"
    echo "  Results: ${RUN_DIR}"
    echo "################################################################"
}

finalize_batch() {
    local batch_meta batch_summary
    batch_summary=$(mktemp)
    $PYTHON -c "
import json, sys
from datetime import datetime, timezone
run_dir, protocol, start, rc_file = sys.argv[1:5]
suites = []
try:
    with open(rc_file) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            tag, label, rc = line.split('\t', 2)
            suites.append({'tag': tag, 'label': label, 'exitCode': int(rc)})
except (IOError, OSError):
    pass
meta = {
    'protocol': protocol,
    'startedAt': start,
    'finishedAt': datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ'),
    'resultsDir': run_dir,
    'suites': suites,
}
with open(run_dir + '/run.meta.json', 'w') as fh:
    json.dump(meta, fh, indent=2)
    fh.write('\n')
print(json.dumps(meta))
" "$RUN_DIR" "$BATCH_PROTOCOL" "$BATCH_START" "$SUITE_RC_FILE" > "$batch_summary"

    $PYTHON "$AGGREGATE" \
        --out-dir "$RUN_DIR" \
        --summary-tsv "$BATCH_SUMMARY" \
        --meta-json "$batch_summary" \
        --print

    cat "$BATCH_SUMMARY" >> "$SUMMARY_FILE"

    if [[ "$BATCH_FAIL" -ne 0 ]]; then
        echo "  Batch ${BATCH_PROTOCOL}: FAILED (see ${RUN_DIR}/summary.txt)"
    else
        echo "  Batch ${BATCH_PROTOCOL}: all suites passed"
    fi
    echo "  Artifacts: ${RUN_DIR}/summary.json"

    rm -f "$batch_summary" "$BATCH_SUMMARY"
    BATCH_SUMMARY=""
}

copy_suite_logs() {
    local tag="$1"
    local log_folder="$2"
    local stdout_file="$3"

    local dest="${RUN_DIR}/suites/${tag}"
    mkdir -p "$dest"

    if [[ -f "$stdout_file" ]]; then
        cp "$stdout_file" "${dest}/stdout.log"
    fi
    if [[ -n "$log_folder" && -d "$log_folder" ]]; then
        [[ -f "${log_folder}/results.txt" ]] && cp "${log_folder}/results.txt" "${dest}/"
        [[ -f "${log_folder}/runinfo.txt" ]] && cp "${log_folder}/runinfo.txt" "${dest}/"
    fi
}

should_run_tag() {
    local tag="$1"

    case "$FILTER" in
        all)
            [[ "$tag" != "cleanup_zone" ]]
            ;;
        both)
            [[ "$tag" != "setup_zone" && "$tag" != "cleanup_zone" ]]
            ;;
        iscsi)
            [[ "$tag" == iscsi_* ]]
            ;;
        nfs3)
            [[ "$tag" == nfs3_* || "$tag" == "zone_pool" || "$tag" == "vm_volume_workflow" ]]
            ;;
        *)
            [[ "$FILTER" == "$tag" ]]
            ;;
    esac
}

run_group() {
    local label="$1"
    local tag="$2"
    local file="$3"

    if ! should_run_tag "$tag"; then
        return 0
    fi

    echo ""
    echo "================================================================"
    echo "  ${label}  (tag: ${tag})"
    echo "================================================================"

    local out tmpout rc log_folder record_dest
    tmpout=$(mktemp)
    if [[ -n "$BATCH_SUMMARY" ]]; then
        record_dest="$BATCH_SUMMARY"
    else
        record_dest="$SUMMARY_FILE"
    fi

    set +e
    $PYTHON "$NOSE_RUNNER" --with-marvin --marvin-config="$CFG" "$file" -a "tags=${tag}" -v -s 2>&1 | tee "$tmpout"
    rc=${PIPESTATUS[0]}
    set -e
    out=$(cat "$tmpout")

    log_folder=$(echo "$out" | grep "Final results are now copied to" | sed 's/.*copied to: //; s/ ===.*//' | tr -d '[:space:]')
    log_folder=$($PYTHON -c "import os; print(os.path.realpath('$log_folder'))" 2>/dev/null || echo "")

    if [[ -n "$RUN_DIR" ]]; then
        copy_suite_logs "$tag" "$log_folder" "$tmpout"
        printf '%s\t%s\t%d\n' "$tag" "$label" "$rc" >> "$SUITE_RC_FILE"
    fi

    if [[ -n "$log_folder" && -f "${log_folder}/results.txt" ]]; then
        local suite_pass suite_fail suite_skip
        record_results "$tag" "$label" "${log_folder}/results.txt" "$record_dest"
        while IFS= read -r line; do
            echo "  $line"
        done < <(grep "TestName.*Status" "${log_folder}/results.txt" | grep -v "^===")
        suite_pass=$(grep -c "Status : SUCCESS" "${log_folder}/results.txt" 2>/dev/null | tr -d '[:space:]' || echo 0)
        suite_fail=$(grep -E "Status : FAIL|Status : EXCEPTION" "${log_folder}/results.txt" 2>/dev/null | wc -l | tr -d '[:space:]' || echo 0)
        suite_skip=$(grep -c "\.\.\. SKIP:" "${log_folder}/results.txt" 2>/dev/null | tr -d '[:space:]' || echo 0)
        PASS=$((PASS + suite_pass))
        FAIL=$((FAIL + suite_fail))
        SKIP=$((SKIP + suite_skip))
        echo "  -> ${suite_pass} passed, ${suite_fail} failed, ${suite_skip} skipped"
    else
        echo "$out" | grep -E "ERROR|Exception|failed" | head -5
        echo "  [could not read results — log folder: ${log_folder:-not found}]"
        printf '%s\t%s\t%s\tFAIL\t%s\n' "$tag" "$label" "(suite)" "results not found" >> "$record_dest"
        FAIL=$((FAIL + 1))
    fi

    rm -f "$tmpout"

    if [[ "$rc" -ne 0 ]]; then
        BATCH_FAIL=$((BATCH_FAIL + 1))
        GLOBAL_BATCH_FAIL=$((GLOBAL_BATCH_FAIL + 1))
    fi
    return 0
}

run_iscsi_suites() {
    local entry label tag file
    for entry in "${ISCSI_SUITES[@]}"; do
        IFS='|' read -r label tag file <<< "$entry"
        run_group "$label" "$tag" "$file"
    done
}

run_nfs3_suites() {
    local entry label tag file
    for entry in "${NFS3_SUITES[@]}"; do
        IFS='|' read -r label tag file <<< "$entry"
        run_group "$label" "$tag" "$file"
    done
}

check_ontap_prereqs() {
    local protocol="$1"
    echo "==> Checking ONTAP ${protocol} prerequisites"
    $PYTHON "$ONTAP_PREREQS" "$CFG" "$protocol"
}

run_protocol_batch() {
    local protocol="$1"
    local parent_dir="${2:-}"

    check_ontap_prereqs "$protocol"
    init_batch "$protocol" "$parent_dir"

    case "$protocol" in
        iscsi) run_iscsi_suites ;;
        nfs3)  run_nfs3_suites ;;
        *)
            echo "Unknown protocol: $protocol" >&2
            return 1
            ;;
    esac

    finalize_batch
}

run_single_suite_by_tag() {
    local want_tag="$1"
    local entry label tag file
    for entry in "${ISCSI_SUITES[@]}" "${NFS3_SUITES[@]}"; do
        IFS='|' read -r label tag file <<< "$entry"
        if [[ "$tag" == "$want_tag" ]]; then
            if [[ "$want_tag" == iscsi_* ]]; then
                check_ontap_prereqs iscsi
            else
                check_ontap_prereqs nfs3
            fi
            run_group "$label" "$tag" "$file"
            return 0
        fi
    done
    return 1
}

write_combined_both_summary() {
    local both_dir="$1"
    $PYTHON "$AGGREGATE" \
        --out-dir "$both_dir" \
        --summary-tsv "$SUMMARY_FILE" \
        --meta-json "{\"filter\":\"both\",\"resultsDir\":\"${both_dir}\"}" \
        --print
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

mkdir -p "$RESULTS_BASE"

case "$FILTER" in
    all)
        run_group "Advanced zone setup" "setup_zone" \
            "${ONTAP_DIR}/zone_setup/test_setup_zone.py"

        BOTH_DIR="${RESULTS_BASE}/$(date +"%Y%m%d-%H%M%S")-both"
        mkdir -p "$BOTH_DIR"
        ln -sfn "$(basename "$BOTH_DIR")" "${RESULTS_BASE}/latest-both"

        run_protocol_batch iscsi "$BOTH_DIR"
        run_protocol_batch nfs3 "$BOTH_DIR"
        write_combined_both_summary "$BOTH_DIR"
        ;;
    both)
        BOTH_DIR="${RESULTS_BASE}/$(date +"%Y%m%d-%H%M%S")-both"
        mkdir -p "$BOTH_DIR"
        ln -sfn "$(basename "$BOTH_DIR")" "${RESULTS_BASE}/latest-both"

        run_protocol_batch iscsi "$BOTH_DIR"
        run_protocol_batch nfs3 "$BOTH_DIR"
        write_combined_both_summary "$BOTH_DIR"
        ;;
    iscsi)
        run_protocol_batch iscsi
        ;;
    nfs3)
        run_protocol_batch nfs3
        ;;
    setup_zone)
        run_group "Advanced zone setup" "setup_zone" \
            "${ONTAP_DIR}/zone_setup/test_setup_zone.py"
        print_final_summary
        ;;
    cleanup_zone)
        run_group "Advanced zone cleanup" "cleanup_zone" \
            "${ONTAP_DIR}/zone_setup/test_cleanup_zone.py"
        print_final_summary
        ;;
    *)
        if run_single_suite_by_tag "$FILTER"; then
            print_final_summary
        else
            echo "Unknown filter: $FILTER" >&2
            echo "Use: all | both | iscsi | nfs3 | setup_zone | cleanup_zone | <suite_tag>" >&2
            exit 1
        fi
        ;;
esac

echo ""
echo "================================================================"
echo "  GRAND TOTAL: ${PASS} passed, ${FAIL} failed, ${SKIP} skipped"
echo "================================================================"

[[ "$FAIL" -eq 0 && "$GLOBAL_BATCH_FAIL" -eq 0 ]]
