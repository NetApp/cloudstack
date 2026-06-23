#!/usr/bin/env bash
# Run the full ONTAP Marvin integration test suite by tag.
# Each test file is run individually so sequential test state is preserved.
#
# Usage (from cloudstack root):
#   bash test/integration/plugins/ontap/run_tests.sh
#
# Optional: limit to a specific group by passing the tag as an argument:
#   bash test/integration/plugins/ontap/run_tests.sh nfs3_workflow

CFG=test/integration/plugins/ontap/ontap.cfg
export PYTHONPATH=test/integration/plugins/ontap:${PYTHONPATH:-}
FILTER="${1:-all}"

PASS=0
FAIL=0

run_group() {
    local label="$1"
    local tag="$2"
    local file="$3"

    if [[ "$FILTER" != "all" && "$FILTER" != "$tag" ]]; then
        return
    fi

    echo ""
    echo "================================================================"
    echo "  ${label}  (tag: ${tag})"
    echo "================================================================"

    local out
    out=$(python3 -m nose --with-marvin --marvin-config="$CFG" "$file" -a "tags=${tag}" -v 2>&1)

    # Resolve the log folder (handle /tmp -> /private/tmp symlink on macOS)
    local log_folder
    log_folder=$(echo "$out" | grep "Final results are now copied to" | sed 's/.*copied to: //; s/ ===.*//' | tr -d '[:space:]')
    log_folder=$(python3 -c "import os; print(os.path.realpath('$log_folder'))" 2>/dev/null || echo "")

    if [[ -n "$log_folder" && -f "${log_folder}/results.txt" ]]; then
        local suite_pass suite_fail
        while IFS= read -r line; do
            echo "  $line"
        done < <(grep "TestName.*Status" "${log_folder}/results.txt" | grep -v "^===")
        suite_pass=$(grep -c "Status : SUCCESS" "${log_folder}/results.txt" 2>/dev/null | tr -d '[:space:]' || echo 0)
        suite_fail=$(grep "Status : FAIL\|Status : EXCEPTION" "${log_folder}/results.txt" 2>/dev/null | wc -l | tr -d '[:space:]' || echo 0)
        PASS=$((PASS + suite_pass))
        FAIL=$((FAIL + suite_fail))
        echo "  -> ${suite_pass} passed, ${suite_fail} failed"
    else
        echo "$out" | grep -E "ERROR|Exception|failed" | head -5
        echo "  [could not read results — log folder: ${log_folder:-not found}]"
        FAIL=$((FAIL + 1))
    fi
}

run_group "NFS3 pool lifecycle"          nfs3_workflow       test/integration/plugins/ontap/nfs3/pool/test_pool_lifecycle.py
run_group "NFS3 pool with volumes"       nfs3_with_volumes   test/integration/plugins/ontap/nfs3/pool/test_pool_with_volumes.py
run_group "NFS3 zone-scoped pool"        zone_pool           test/integration/plugins/ontap/nfs3/pool/test_zone_scoped_pool.py
run_group "NFS3 volume lifecycle"        nfs3_volume         test/integration/plugins/ontap/nfs3/volume/test_volume_lifecycle.py
run_group "NFS3 VM volume attach"        vm_volume_workflow  test/integration/plugins/ontap/nfs3/instance/test_vm_volume_attach.py
run_group "iSCSI pool lifecycle"         iscsi_workflow      test/integration/plugins/ontap/iscsi/pool/test_pool_lifecycle.py
run_group "iSCSI pool with volumes"      iscsi_with_volumes  test/integration/plugins/ontap/iscsi/pool/test_pool_with_volumes.py
run_group "iSCSI zone-scoped pool"       iscsi_zone_pool     test/integration/plugins/ontap/iscsi/pool/test_zone_scoped_pool.py
run_group "iSCSI volume lifecycle"       iscsi_volume        test/integration/plugins/ontap/iscsi/volume/test_volume_lifecycle.py
run_group "iSCSI VM volume workflow"     iscsi_vm_workflow   test/integration/plugins/ontap/iscsi/instance/test_vm_volume_attach.py

echo ""
echo "================================================================"
echo "  TOTAL: ${PASS} passed, ${FAIL} failed"
echo "================================================================"

[[ "$FAIL" -eq 0 ]]
