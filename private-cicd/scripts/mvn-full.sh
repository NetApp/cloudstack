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
#   Full CloudStack Maven clean install (developer,systemvm; optional simulator).
#   When RESULT_DIR is set, copies Surefire XML out of the source tree, writes
#   maven.log and stage1-handoff.properties, and exits with Maven's status.
# Dependencies:
#   SOURCE_DIR or CLOUDSTACK_DIR — CloudStack repo root (required).
#   RESULT_DIR — if set, SOURCE_ID and SOURCE_SHA are required (Jenkins handoff);
#   PR_ID and PR_HEAD_SHA are accepted as fallbacks for pull-request builds.
#   Optional: SKIP_TESTS, ENABLE_NOREDIST, ENABLE_SIMULATOR (default true),
#   MAVEN_THREADS (Jenkins uses 1C; empty disables -T; unset uses nproc).
#   Commands: mvn, java. Does not call other private-cicd scripts.
#   When RESULT_DIR is set, apply debian/rules packaging flags so build-debs.sh
#   can reuse target/ without a second mvn clean.

set -euo pipefail

SOURCE_DIR="${SOURCE_DIR:-${CLOUDSTACK_DIR:-}}"
if [[ -z "$SOURCE_DIR" ]]; then
  echo "Set SOURCE_DIR to the CloudStack repo root" >&2
  exit 1
fi
SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd)"

SKIP_TESTS="${SKIP_TESTS:-false}"
ENABLE_NOREDIST="${ENABLE_NOREDIST:-false}"
ENABLE_SIMULATOR="${ENABLE_SIMULATOR:-true}"
THREADS="${MAVEN_THREADS-$(nproc)}"
RESULT_DIR="${RESULT_DIR:-}"

mvn_args=(-B -P developer,systemvm)
if [[ "$ENABLE_SIMULATOR" == "true" ]]; then
  mvn_args+=(-Dsimulator)
fi
if [[ "$ENABLE_NOREDIST" == "true" ]]; then
  mvn_args+=(-Dnoredist)
fi
mvn_args+=(clean install)
if [[ "$SKIP_TESTS" == "true" ]]; then
  mvn_args+=(-DskipTests=true)
fi
if [[ -n "$THREADS" ]]; then
  mvn_args+=(-T"$THREADS")
fi

echo "==> Full build in $SOURCE_DIR"

if [[ -z "$RESULT_DIR" ]]; then
  cd "$SOURCE_DIR"
  exec mvn "${mvn_args[@]}"
fi

SOURCE_ID="${SOURCE_ID:-${PR_ID:-}}"
SOURCE_SHA="${SOURCE_SHA:-${PR_HEAD_SHA:-}}"
: "${SOURCE_ID:?SOURCE_ID (or PR_ID) is required when RESULT_DIR is set}"
: "${SOURCE_SHA:?SOURCE_SHA (or PR_HEAD_SHA) is required when RESULT_DIR is set}"

mkdir -p "$RESULT_DIR"
RESULT_DIR="$(cd "$RESULT_DIR" && pwd)"
rm -rf "$RESULT_DIR"
mkdir -p "$RESULT_DIR/surefire"

cd "$SOURCE_DIR"
# server/pom.xml reads ${project.basedir}/../${cs.replace.properties}, so the
# path must stay relative to the source root like it is in debian/rules.
replace_properties="replace.properties.tmp"
cp "$SOURCE_DIR/packaging/debian/replace.properties" "$replace_properties"
# debian/rules takes the second <version> in pom.xml. A grep|head pipeline
# would SIGPIPE under pipefail, so read the file in one pass.
version="$(awk -F'[<>]' '/<version>/ { matches++; if (matches == 2) { print $3; exit } }' pom.xml)"
if [[ -z "$version" ]]; then
  echo "Could not read the project version from pom.xml" >&2
  exit 1
fi
echo "VERSION=${version}" >> "$replace_properties"
mvn_args+=(-Dsystemvm "-Dcs.replace.properties=${replace_properties}")

echo "==> Command: mvn ${mvn_args[*]}"

set +e
mvn "${mvn_args[@]}" 2>&1 | tee "$RESULT_DIR/maven.log"
maven_status="${PIPESTATUS[0]}"
set -e

report_count="$(
  find . -type f -path '*/target/surefire-reports/*.xml' -print | wc -l | tr -d '[:space:]'
)"
if [[ "$report_count" -gt 0 ]]; then
  while IFS= read -r -d '' report; do
    report_dir="$RESULT_DIR/surefire/$(dirname "$report")"
    mkdir -p "$report_dir"
    cp "$report" "$report_dir/"
  done < <(find . -type f -path '*/target/surefire-reports/*.xml' -print0)
  find . -type f -path '*/target/surefire-reports/*.xml' -print0 \
    | tar --null -czf "$RESULT_DIR/surefire-reports.tar.gz" --files-from -
fi

if [[ "$maven_status" -eq 0 ]]; then
  result="SUCCESS"
else
  result="FAILURE"
fi

cat > "$RESULT_DIR/stage1-handoff.properties" <<EOF
SOURCE_ID=$SOURCE_ID
SOURCE_SHA=$SOURCE_SHA
PR_ID=$SOURCE_ID
PR_HEAD_SHA=$SOURCE_SHA
MAVEN_RESULT=$result
SUREFIRE_REPORT_COUNT=$report_count
EOF

echo "Maven result: $result; Surefire XML reports: $report_count"
exit "$maven_status"
