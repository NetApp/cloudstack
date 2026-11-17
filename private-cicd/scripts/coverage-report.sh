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
#   Builds the ONTAP storage plugin coverage summary of one presubmit run. The
#   unit-test JaCoCo report already exists, written by the Maven build. The
#   functional report is made here from the management server dumps that
#   marvin-run.sh collected on the lab VM, using the plugin jar from the
#   management package that was actually deployed. ontap-coverage.py then
#   writes summary.json, summary.md and summary.html.
#   This script never fails the presubmit: every problem is reported and the
#   summary says which part of the coverage is missing.
# Dependencies:
#   RESULTS_DIR — the presubmit results directory (required). Reads
#   unit-tests/coverage/unit/jacoco.xml and phase2/coverage/*.exec, writes
#   coverage/.
#   SOURCE_DIR — CloudStack source checkout (required): ONTAP sources.
#   Optional: DEB_DIR (default $SOURCE_DIR/dist/deb-all) for the deployed jar,
#   BUILD_URL to link the reports.
#   Commands: java, python3, dpkg-deb; fetch-jacoco.sh for
#   the JaCoCo command line.
#   Calls: fetch-jacoco.sh, ontap-coverage.py.

set -uo pipefail

RESULTS_DIR="${RESULTS_DIR:?Set RESULTS_DIR}"
SOURCE_DIR="${SOURCE_DIR:?Set SOURCE_DIR}"
DEB_DIR="${DEB_DIR:-$SOURCE_DIR/dist/deb-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ontap_module="plugins/storage/volume/ontap"
out_dir="$RESULTS_DIR/coverage"
unit_xml="$RESULTS_DIR/unit-tests/coverage/unit/jacoco.xml"
exec_dir="$RESULTS_DIR/phase2/coverage"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir -p "$out_dir"

functional_xml=""
shopt -s nullglob
exec_files=("$exec_dir"/*.exec)
shopt -u nullglob

if [[ "${#exec_files[@]}" -eq 0 ]]; then
    echo "No functional coverage dumps under $exec_dir."
elif ! "$SCRIPT_DIR/fetch-jacoco.sh" "$work_dir/jacoco" >/dev/null 2>&1; then
    echo "Could not fetch the JaCoCo command line; functional coverage is skipped." >&2
else
    cli="$work_dir/jacoco/jacococli.jar"
    classfiles=""
    # The jar inside the deployed management package is the code that ran.
    management_deb="$(find "$DEB_DIR" -maxdepth 1 -name 'cloudstack-management_*.deb' -print -quit 2>/dev/null)"
    if [[ -n "$management_deb" ]] && dpkg-deb -x "$management_deb" "$work_dir/deployed" 2>/dev/null; then
        classfiles="$(find "$work_dir/deployed" -name 'cloud-plugin-storage-volume-ontap-*.jar' \
            ! -name '*-sources.jar' ! -name '*-tests.jar' -print -quit)"
    fi
    if [[ -z "$classfiles" && -d "$SOURCE_DIR/$ontap_module/target/classes" ]]; then
        classfiles="$SOURCE_DIR/$ontap_module/target/classes"
        echo "Deployed plugin jar not found; using $classfiles."
    fi
    if [[ -z "$classfiles" ]]; then
        echo "No ONTAP plugin classes found; functional coverage is skipped." >&2
    elif mkdir -p "$out_dir/functional" \
            && java -jar "$cli" merge "${exec_files[@]}" --destfile "$work_dir/functional.exec" >/dev/null \
            && java -jar "$cli" report "$work_dir/functional.exec" \
                --classfiles "$classfiles" \
                --sourcefiles "$SOURCE_DIR/$ontap_module/src/main/java" \
                --name "ONTAP functional tests" \
                --xml "$out_dir/functional/jacoco.xml" \
                --csv "$out_dir/functional/jacoco.csv" \
                --html "$out_dir/functional/html" >/dev/null; then
        functional_xml="$out_dir/functional/jacoco.xml"
        echo "Functional coverage report: $functional_xml"
    else
        echo "Could not build the functional coverage report." >&2
    fi
fi

summary_args=(--output-dir "$out_dir")
[[ -f "$unit_xml" ]] && summary_args+=(--unit "$unit_xml")
[[ -n "$functional_xml" ]] && summary_args+=(--functional "$functional_xml")
if [[ -n "${BUILD_URL:-}" ]]; then
    [[ -f "$unit_xml" ]] && summary_args+=(
        --unit-link "${BUILD_URL}artifact/presubmit-results/unit-tests/coverage/unit/index.html")
    [[ -n "$functional_xml" ]] && summary_args+=(
        --functional-link "${BUILD_URL}artifact/presubmit-results/coverage/functional/html/index.html")
fi
python3 "$SCRIPT_DIR/ontap-coverage.py" "${summary_args[@]}" \
    || echo "Could not write the coverage summary." >&2
exit 0
