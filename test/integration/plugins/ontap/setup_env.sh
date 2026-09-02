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
# Creates a local venv and installs Marvin + dependencies. CI sets
# MARVIN_ARCHIVE to the version-matched archive produced during packaging.
# A source-tree fallback remains available for standalone development.
#
# Usage (from repo root):
#   bash test/integration/plugins/ontap/setup_env.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
ONTAP_DIR="${REPO_ROOT}/test/integration/plugins/ontap"
VENV="${ONTAP_DIR}/.venv"
COMMANDS_XML="${REPO_ROOT}/tools/apidoc/target/commands.xml"
MARVIN_API="${REPO_ROOT}/tools/marvin/marvin/cloudstackAPI"
MARVIN_ARCHIVE="${MARVIN_ARCHIVE:-}"
NOSE_RUNNER="${ONTAP_DIR}/nose_compat.py"

echo "==> Repo root: ${REPO_ROOT}"

if [[ ! -d "${VENV}" ]]; then
    echo "==> Creating Python venv at ${VENV}"
    if ! python3 -m venv "${VENV}"; then
        echo "Unable to create the Marvin venv; install python3-venv on this host" >&2
        exit 1
    fi
fi

PIP="${VENV}/bin/pip"
PYTHON="${VENV}/bin/python"

echo "==> Upgrading pip / setuptools / wheel"
if ! "${PIP}" install --upgrade pip wheel; then
    echo "Unable to install Python build tools; verify access to the configured Python package index" >&2
    exit 1
fi

# nose discovers Marvin via setuptools entry points (needs pkg_resources).
if ! "${PIP}" install "setuptools>=40.3.0,<81"; then
    echo "Unable to install a Marvin-compatible setuptools version" >&2
    exit 1
fi

if [[ -n "${MARVIN_ARCHIVE}" ]]; then
    if [[ ! -f "${MARVIN_ARCHIVE}" ]]; then
        echo "Marvin archive does not exist: ${MARVIN_ARCHIVE}" >&2
        exit 1
    fi
    echo "==> Installing Marvin archive ${MARVIN_ARCHIVE}"
    if ! "${PIP}" install --no-compile "${MARVIN_ARCHIVE}"; then
        echo "Unable to install Marvin; verify the archive and Python package-index access" >&2
        exit 1
    fi
else
    if [[ ! -f "${COMMANDS_XML}" ]]; then
        echo "==> Building apidoc (generates commands.xml) — first run may take ~2 min"
        (cd "${REPO_ROOT}/tools" && mvn -pl apidoc -am package -DskipTests -q)
    fi

    if [[ ! -d "${MARVIN_API}" ]]; then
        echo "==> Generating Marvin cloudstackAPI from commands.xml"
        (cd "${REPO_ROOT}/tools/marvin/marvin" && \
            "${PYTHON}" codegenerator.py -s "${COMMANDS_XML}")
    fi

    echo "==> Installing Marvin from the source tree"
    if ! "${PIP}" install --no-compile "${REPO_ROOT}/tools/marvin"; then
        echo "Unable to install Marvin; verify the source tree and Python package-index access" >&2
        exit 1
    fi
fi

echo "==> Pinning pyvmomi for Python 3.9 compatibility (pyvmomi 9.x requires 3.10+)"
if ! "${PIP}" install "pyvmomi==8.0.2.0.1"; then
    echo "Unable to install pyvmomi; verify access to the configured Python package index" >&2
    exit 1
fi

echo ""
echo "==> Verifying installation"
if ! "${PYTHON}" -c "import marvin; print('Marvin OK')"; then
    echo "Marvin import verification failed" >&2
    exit 1
fi
if ! nose_plugins="$("${PYTHON}" "${NOSE_RUNNER}" -p 2>&1)"; then
    printf '%s\n' "${nose_plugins}" >&2
    echo "Unable to query installed nose plugins" >&2
    exit 1
fi
if ! grep -q "Plugin marvin" <<< "${nose_plugins}"; then
    printf '%s\n' "${nose_plugins}" >&2
    echo "Marvin nose plugin verification failed" >&2
    exit 1
fi
echo "Marvin nose plugin OK"
if ! discovery_output="$(
    PYTHONPATH="${ONTAP_DIR}:${PYTHONPATH:-}" \
        "${PYTHON}" "${NOSE_RUNNER}" --collect-only \
        "${ONTAP_DIR}/zone_setup/test_setup_zone.py" \
        -a "tags=setup_zone" 2>&1
)"; then
    printf '%s\n' "${discovery_output}" >&2
    echo "Nose test discovery verification failed" >&2
    exit 1
fi
echo "Nose test discovery compatibility OK"

echo ""
echo "Done. Activate the venv with:"
echo "  source test/integration/plugins/ontap/.venv/bin/activate"
echo ""
echo "Run zone setup tests:"
echo "  bash test/integration/plugins/ontap/run_tests.sh setup_zone"
echo ""
echo "Or run manually:"
echo "  test/integration/plugins/ontap/.venv/bin/python \\"
echo "      test/integration/plugins/ontap/nose_compat.py --with-marvin \\"
echo "      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\"
echo "      test/integration/plugins/ontap/zone_setup/test_setup_zone.py \\"
echo "      -a \"tags=setup_zone\" -v"
