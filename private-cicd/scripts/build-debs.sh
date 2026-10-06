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
#   Build CloudStack Debian packages with dpkg-buildpackage -uc -us -b -d,
#   adding -nc when REUSE_MAVEN_BUILD=true and otherwise building with
#   ACS_BUILD_OPTS="-DskipTests". Copy .deb/.changes/.buildinfo and the
#   generated Marvin archive into RESULT_DIR, require cloudstack-common,
#   cloudstack-agent, and cloudstack-management, then write manifests and sums.
# Dependencies:
#   SOURCE_DIR — CloudStack repo root with debian/ (required).
#   RESULT_DIR — output dir (default: SOURCE_DIR/dist/deb-all).
#   Optional REUSE_MAVEN_BUILD=true skips debian/rules' mvn clean package and
#   reuses target/ from mvn-full.sh. Jenkins always sets this after unit tests.
#   Commands: dpkg-buildpackage, dpkg-deb, python3 with setuptools, sha256sum;
#   Maven/JDK as used by debian/rules unless Maven is reused.
#   Does not call other private-cicd scripts.

set -euo pipefail

SOURCE_DIR="${SOURCE_DIR:?SOURCE_DIR is required}"
SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd)"
RESULT_DIR="${RESULT_DIR:-$SOURCE_DIR/dist/deb-all}"

for command_name in dpkg-buildpackage dpkg-deb python3 sha256sum; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command is missing: $command_name" >&2
        exit 1
    fi
done
if ! python3 -c 'import setuptools' >/dev/null 2>&1; then
    echo "Python setuptools is required to build the Marvin archive" >&2
    echo "Install python3-setuptools before running the Debian package build" >&2
    exit 1
fi

mkdir -p "$RESULT_DIR"
RESULT_DIR="$(cd "$RESULT_DIR" && pwd)"
rm -rf "$RESULT_DIR"
mkdir -p "$RESULT_DIR"
temporary_log="$(mktemp)"
trap 'rm -f "$temporary_log"' EXIT

cd "$SOURCE_DIR"

REUSE_MAVEN_BUILD="${REUSE_MAVEN_BUILD:-false}"
if [[ "$REUSE_MAVEN_BUILD" == "true" ]]; then
    echo "==> Reusing Maven target/ from mvn-full.sh"
    missing=0
    if [[ ! -d "$SOURCE_DIR/client/target" ]]; then
        echo "client/target is missing; run mvn-full.sh before packaging" >&2
        missing=1
    fi
    if [[ ! -f "$SOURCE_DIR/tools/apidoc/target/commands.xml" ]]; then
        echo "tools/apidoc/target/commands.xml is missing; run mvn-full.sh before packaging" >&2
        missing=1
    fi
    shopt -s nullglob
    marvin_precheck=("$SOURCE_DIR"/tools/marvin/dist/Marvin-*.tar.gz)
    shopt -u nullglob
    if [[ "${#marvin_precheck[@]}" -eq 0 ]]; then
        echo "tools/marvin/dist/Marvin-*.tar.gz is missing; run mvn-full.sh before packaging" >&2
        missing=1
    fi
    if [[ "$missing" -ne 0 ]]; then
        exit 1
    fi

    python3 - "$SOURCE_DIR/debian/rules" <<'PY'
from pathlib import Path
import sys

rules = Path(sys.argv[1])
text = rules.read_text()
old = (
    "override_dh_auto_build:\n"
    "\tmvn clean package -Psystemvm,developer -Dsystemvm \\\n"
    "\t    -Dcs.replace.properties=replace.properties.tmp \\\n"
    "\t    -Dmaven.repo.local=$(HOME)/.m2/repository \\\n"
    "\t     ${ACS_BUILD_OPTS}\n"
)
new = (
    "override_dh_auto_build:\n"
    '\t@echo "Reusing Maven target/ from mvn-full.sh; skipping mvn clean package"\n'
)
if old not in text:
    raise SystemExit("debian/rules override_dh_auto_build did not match the expected recipe")
rules.write_text(text.replace(old, new, 1))
PY
fi

echo "==> Building CloudStack Debian packages"
if [[ "$REUSE_MAVEN_BUILD" == "true" ]]; then
    echo "==> Command: dpkg-buildpackage -uc -us -b -d -nc (Maven reused)"
    set +e
    dpkg-buildpackage -uc -us -b -d -nc \
        2>&1 | tee "$temporary_log"
    package_status="${PIPESTATUS[0]}"
    set -e
else
    echo "==> Command: ACS_BUILD_OPTS=\"-DskipTests\" dpkg-buildpackage -uc -us -b -d"
    set +e
    ACS_BUILD_OPTS="-DskipTests" dpkg-buildpackage -uc -us -b -d \
        2>&1 | tee "$temporary_log"
    package_status="${PIPESTATUS[0]}"
    set -e
fi

mkdir -p "$RESULT_DIR"
mv "$temporary_log" "$RESULT_DIR/deb-build.log"
trap - EXIT

if [[ "$package_status" -ne 0 ]]; then
    echo "Debian package build failed with status $package_status" >&2
    exit "$package_status"
fi

commands_xml="$SOURCE_DIR/tools/apidoc/target/commands.xml"
if [[ ! -f "$commands_xml" ]]; then
    echo "Marvin API metadata is missing after the Debian build: $commands_xml" >&2
    exit 1
fi

shopt -s nullglob
marvin_archives=("$SOURCE_DIR"/tools/marvin/dist/Marvin-*.tar.gz)
artifacts=(../*.deb ../*.changes ../*.buildinfo)
debs=(../*.deb)
shopt -u nullglob

if [[ "${#marvin_archives[@]}" -eq 0 ]]; then
    echo "No Marvin archive was produced under tools/marvin/dist" >&2
    echo "Verify that the build environment provides python3-setuptools, then inspect deb-build.log" >&2
    exit 1
fi
if [[ "${#marvin_archives[@]}" -ne 1 ]]; then
    echo "Expected one Marvin archive, found ${#marvin_archives[@]} under tools/marvin/dist" >&2
    printf '  %s\n' "${marvin_archives[@]}" >&2
    exit 1
fi

if [[ "${#debs[@]}" -eq 0 ]]; then
    echo "No Debian packages were produced" >&2
    exit 1
fi

cp "${artifacts[@]}" "$RESULT_DIR/"
mkdir -p "$RESULT_DIR/marvin"
cp "${marvin_archives[0]}" "$RESULT_DIR/marvin/"
cp "$commands_xml" "$RESULT_DIR/marvin/commands.xml"

required_packages=(
    cloudstack-common
    cloudstack-agent
    cloudstack-management
)

declare -A package_files
for package_name in "${required_packages[@]}"; do
    matches=("$RESULT_DIR/${package_name}_"*.deb)
    if [[ ! -e "${matches[0]}" ]]; then
        echo "Required Debian package is missing: $package_name" >&2
        exit 1
    fi

    actual_name="$(dpkg-deb -f "${matches[0]}" Package)"
    if [[ "$actual_name" != "$package_name" ]]; then
        echo "Expected package '$package_name', found '$actual_name'" >&2
        exit 1
    fi
    package_files["$package_name"]="${matches[0]}"
done

dpkg-deb -c "${package_files[cloudstack-common]}" > "$RESULT_DIR/cloudstack-common.contents"
dpkg-deb -c "${package_files[cloudstack-agent]}" > "$RESULT_DIR/cloudstack-agent.contents"
dpkg-deb -c "${package_files[cloudstack-management]}" > "$RESULT_DIR/cloudstack-management.contents"

grep -q '/usr/share/cloudstack-common/' "$RESULT_DIR/cloudstack-common.contents"
grep -q '/lib/systemd/system/cloudstack-agent.service' "$RESULT_DIR/cloudstack-agent.contents"
grep -q '/lib/systemd/system/cloudstack-management.service' "$RESULT_DIR/cloudstack-management.contents"

(
    cd "$RESULT_DIR"
    sha256sum ./*.deb > SHA256SUMS
    (
        cd marvin
        sha256sum "$(basename "${marvin_archives[0]}")" > SHA256SUMS
    )
    ls -lh
)

{
    printf 'PACKAGE\tVERSION\tARCHITECTURE\tFILE\n'
    for package_name in "${required_packages[@]}"; do
        package_file="${package_files[$package_name]}"
        printf '%s\t%s\t%s\t%s\n' \
            "$(dpkg-deb -f "$package_file" Package)" \
            "$(dpkg-deb -f "$package_file" Version)" \
            "$(dpkg-deb -f "$package_file" Architecture)" \
            "$(basename "$package_file")"
    done
} > "$RESULT_DIR/package-manifest.tsv"

{
    printf 'ARTIFACT\tFILE\tSHA256\n'
    printf 'marvin\t%s\t%s\n' \
        "$(basename "${marvin_archives[0]}")" \
        "$(sha256sum "${marvin_archives[0]}" | awk '{print $1}')"
    printf 'commands_xml\tcommands.xml\t%s\n' \
        "$(sha256sum "$commands_xml" | awk '{print $1}')"
} > "$RESULT_DIR/marvin/manifest.tsv"
