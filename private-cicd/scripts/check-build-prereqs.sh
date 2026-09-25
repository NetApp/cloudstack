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
#   Fail fast if the builder environment cannot run Maven, Debian packaging,
#   Git/SSH, inventory YAML rendering, or vCenter revert.
#   Checks required commands and major versions of Java, Node, and npm.
# Dependencies:
#   Commands: git, java, mvn, node, npm, python3, dpkg-buildpackage, dpkg-deb,
#   sha256sum, ssh, ssh-keygen, sshpass, scp, ssh-keyscan. python3 must import setuptools, yaml, and
#   pyVim (pyvmomi). EXPECTED_JAVA_MAJOR (default 17), EXPECTED_NODE_MAJOR (16),
#   EXPECTED_NPM_MAJOR (8).
#   No CloudStack source tree and no other private-cicd scripts.

set -euo pipefail

EXPECTED_JAVA_MAJOR="${EXPECTED_JAVA_MAJOR:-17}"
EXPECTED_NODE_MAJOR="${EXPECTED_NODE_MAJOR:-16}"
EXPECTED_NPM_MAJOR="${EXPECTED_NPM_MAJOR:-8}"

required_commands=(
    dpkg-buildpackage
    dpkg-deb
    git
    java
    mvn
    node
    npm
    python3
    scp
    sha256sum
    ssh
    ssh-keygen
    ssh-keyscan
    sshpass
)

for command_name in "${required_commands[@]}"; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required build command is missing: $command_name" >&2
        exit 1
    fi
done

if ! python3 -c 'import setuptools' >/dev/null 2>&1; then
    echo "Python setuptools is required to build the Marvin archive" >&2
    echo "Install python3-setuptools before running the Debian package build" >&2
    exit 1
fi
if ! python3 -c 'import yaml' >/dev/null 2>&1; then
    echo "PyYAML is required to render Phase 2 inventory configuration" >&2
    exit 1
fi
if ! python3 -c 'import pyVim' >/dev/null 2>&1; then
    echo "pyvmomi (pyVim) is required for vCenter snapshot revert" >&2
    exit 1
fi

java_version="$(java -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')"
node_version="$(node --version | sed 's/^v//')"
npm_version="$(npm --version)"

if [[ "${java_version%%.*}" != "$EXPECTED_JAVA_MAJOR" ]]; then
    echo "Java $EXPECTED_JAVA_MAJOR is required; found Java $java_version" >&2
    exit 1
fi
if [[ "${node_version%%.*}" != "$EXPECTED_NODE_MAJOR" ]]; then
    echo "Node $EXPECTED_NODE_MAJOR.x is required; found Node $node_version" >&2
    exit 1
fi
if [[ "${npm_version%%.*}" != "$EXPECTED_NPM_MAJOR" ]]; then
    echo "npm $EXPECTED_NPM_MAJOR.x is required; found npm $npm_version" >&2
    exit 1
fi

echo "Java: $(java -version 2>&1 | awk 'NR == 1 {print}')"
echo "Maven: $(mvn --version | awk 'NR == 1 {print}')"
echo "Node: $node_version"
echo "npm: $npm_version"
echo "dpkg-buildpackage: $(dpkg-buildpackage --version | awk 'NR == 1 {print}')"
echo "Python setuptools: $(python3 -c 'import setuptools; print(setuptools.__version__)')"
echo "PyYAML: $(python3 -c 'import yaml; print(getattr(yaml, "__version__", "present"))')"
echo "pyvmomi: $(python3 -c 'import pyVim; print("present")')"
