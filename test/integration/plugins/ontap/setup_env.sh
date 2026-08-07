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

# One-time (or repeat) setup for ONTAP Marvin integration tests.
#
# Creates a local venv, generates Marvin API bindings from apidoc,
# and installs Marvin + dependencies.
#
# Usage (from repo root):
#   bash test/integration/plugins/ontap/setup_env.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
VENV="${REPO_ROOT}/test/integration/plugins/ontap/.venv"
COMMANDS_XML="${REPO_ROOT}/tools/apidoc/target/commands.xml"
MARVIN_API="${REPO_ROOT}/tools/marvin/marvin/cloudstackAPI"

echo "==> Repo root: ${REPO_ROOT}"

if [[ ! -d "${VENV}" ]]; then
    echo "==> Creating Python venv at ${VENV}"
    python3 -m venv "${VENV}"
fi

PIP="${VENV}/bin/pip"
PYTHON="${VENV}/bin/python"

echo "==> Upgrading pip / setuptools / wheel"
"${PIP}" install --upgrade pip wheel

# nose discovers Marvin via setuptools entry points (needs pkg_resources).
"${PIP}" install "setuptools>=40.3.0,<81"

if [[ ! -f "${COMMANDS_XML}" ]]; then
    echo "==> Building apidoc (generates commands.xml) — first run may take ~2 min"
    (cd "${REPO_ROOT}/tools" && mvn -pl apidoc -am package -DskipTests -q)
fi

if [[ ! -d "${MARVIN_API}" ]]; then
    echo "==> Generating Marvin cloudstackAPI from commands.xml"
    (cd "${REPO_ROOT}/tools/marvin/marvin" && \
        "${PYTHON}" codegenerator.py -s "${COMMANDS_XML}")
fi

echo "==> Installing Marvin (--no-compile avoids broken retries package bytecode)"
"${PIP}" install --no-compile "${REPO_ROOT}/tools/marvin"

echo "==> Pinning pyvmomi for Python 3.9 compatibility (pyvmomi 9.x requires 3.10+)"
"${PIP}" install "pyvmomi==8.0.2.0.1"

echo ""
echo "==> Verifying installation"
"${PYTHON}" -c "import marvin; print('Marvin OK')"
"${PYTHON}" -m nose -p 2>&1 | grep -q "Plugin marvin" && echo "Marvin nose plugin OK"

echo ""
echo "Done. Activate the venv with:"
echo "  source test/integration/plugins/ontap/.venv/bin/activate"
echo ""
echo "Run zone setup tests:"
echo "  bash test/integration/plugins/ontap/run_tests.sh setup_zone"
echo ""
echo "Or run manually:"
echo "  test/integration/plugins/ontap/.venv/bin/python -m nose --with-marvin \\"
echo "      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\"
echo "      test/integration/plugins/ontap/zone_setup/test_setup_zone.py \\"
echo "      -a \"tags=setup_zone\" -v"
