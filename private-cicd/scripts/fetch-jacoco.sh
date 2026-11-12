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
#   Copies the JaCoCo agent and command-line jars into a directory as
#   jacocoagent.jar and jacococli.jar. The agent is attached to the management
#   server on the lab VM, and the command line dumps and reports coverage.
#   Jars already present in the directory are kept, so a second call is free.
# Dependencies:
#   Usage: fetch-jacoco.sh DEST_DIR
#   Optional: JACOCO_VERSION (default 0.8.11, the version pinned in the root pom).
#   Commands: mvn with access to Maven Central or a mirror.
#   Called by: run-phase2-remote.sh, coverage-report.sh.

set -euo pipefail

DEST_DIR="${1:?Usage: fetch-jacoco.sh DEST_DIR}"
JACOCO_VERSION="${JACOCO_VERSION:-0.8.11}"
DEPENDENCY_PLUGIN="org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy"

mkdir -p "$DEST_DIR"
DEST_DIR="$(cd "$DEST_DIR" && pwd)"
if [[ -f "$DEST_DIR/jacocoagent.jar" && -f "$DEST_DIR/jacococli.jar" ]]; then
    exit 0
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# The agent is the "runtime" classifier of org.jacoco.agent; the command line is
# the self-contained "nodeps" jar of org.jacoco.cli.
mvn -B -q "$DEPENDENCY_PLUGIN" \
    "-Dartifact=org.jacoco:org.jacoco.agent:${JACOCO_VERSION}:jar:runtime" \
    "-DoutputDirectory=${work_dir}/agent" -Dmdep.stripVersion=true
mvn -B -q "$DEPENDENCY_PLUGIN" \
    "-Dartifact=org.jacoco:org.jacoco.cli:${JACOCO_VERSION}:jar:nodeps" \
    "-DoutputDirectory=${work_dir}/cli" -Dmdep.stripVersion=true

agent_jar="$(find "$work_dir/agent" -name '*.jar' -print -quit)"
cli_jar="$(find "$work_dir/cli" -name '*.jar' -print -quit)"
if [[ -z "$agent_jar" || -z "$cli_jar" ]]; then
    echo "Could not fetch the JaCoCo ${JACOCO_VERSION} jars" >&2
    exit 1
fi
cp "$agent_jar" "$DEST_DIR/jacocoagent.jar"
cp "$cli_jar" "$DEST_DIR/jacococli.jar"
echo "JaCoCo ${JACOCO_VERSION} jars are in $DEST_DIR"
