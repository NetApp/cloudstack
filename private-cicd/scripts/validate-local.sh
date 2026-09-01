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
#   Syntax-check private-cicd shell/Python scripts and parse YAML under config/.
#   Optional --with-docker builds docker/Dockerfile.driver. No CloudStack source required.
# Dependencies:
#   bash; python3 (optional, for .py compile and YAML if PyYAML is present).
#   YAML: ruby+psych, python3+yaml, or yq. docker only with --with-docker.
#   CICD_ROOT — scripts/, config/, docker/ (default: parent of this file).
#   Does not call Maven, packaging, or Phase 2 scripts.

set -euo pipefail

usage() {
  cat << 'EOF'
Usage: validate-local.sh [--with-docker]

  Validates shell scripts (bash -n) and YAML under this repo's private-cicd
  (or standalone CI repo) root — independent of CloudStack merge scope.

  --with-docker   Build the CloudStack package-builder image and run smoke checks.

Environment:
  CICD_ROOT       Root containing scripts/, config/, and docker/ (default: inferred).

Exit status: 0 if all checks pass, non-zero otherwise.
EOF
}

WITH_DOCKER=false
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --with-docker) WITH_DOCKER=true ;;
    *) echo "Unknown option: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${CICD_ROOT:-}" ]]; then
  CICD_ROOT="$(cd "$CICD_ROOT" && pwd)"
else
  CICD_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi

echo "==> CICD_ROOT=$CICD_ROOT"

failures=0

run_check() {
  local name="$1"
  shift
  echo "==> $name"
  if "$@"; then
    echo "    OK"
  else
    echo "    FAILED" >&2
    failures=$((failures + 1))
  fi
}

# --- Shell: bash -n on all scripts/*.sh (no CloudStack tree required)
while IFS= read -r -d '' f; do
  run_check "bash -n: ${f#$CICD_ROOT/}" bash -n "$f"
done < <(find "$CICD_ROOT/scripts" -maxdepth 1 -type f -name '*.sh' -print0 2>/dev/null || true)

if [[ ! -d "$CICD_ROOT/scripts" ]]; then
  echo "No scripts directory at $CICD_ROOT/scripts" >&2
  failures=$((failures + 1))
fi

# --- Python: parse without importing optional runtime dependencies
if command -v python3 >/dev/null 2>&1; then
  while IFS= read -r -d '' f; do
    run_check "Python syntax: ${f#$CICD_ROOT/}" \
      python3 -c 'import pathlib,sys; compile(pathlib.Path(sys.argv[1]).read_text(), sys.argv[1], "exec")' "$f"
  done < <(find "$CICD_ROOT/scripts" -maxdepth 1 -type f -name '*.py' -print0 2>/dev/null || true)
else
  echo "==> Python syntax: skipped (python3 not installed)" >&2
fi

# --- YAML under config/
yaml_ok=false
if command -v ruby >/dev/null 2>&1 && ruby -ryaml -e 'true' >/dev/null 2>&1; then
  yaml_check() { ruby -ryaml -e "YAML.load_file(ARGV[0])" "$1"; }
  yaml_ok=true
elif command -v python3 >/dev/null 2>&1; then
  if python3 -c 'import yaml' >/dev/null 2>&1; then
    yaml_check() { python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" "$1"; }
    yaml_ok=true
  fi
elif command -v yq >/dev/null 2>&1; then
  yaml_check() { yq e '.' "$1" >/dev/null; }
  yaml_ok=true
fi

if [[ -d "$CICD_ROOT/config" ]]; then
  shopt -s nullglob
  yfiles=(
    "$CICD_ROOT"/config/*.yaml
    "$CICD_ROOT"/config/*.yml
    "$CICD_ROOT"/config/*.yaml.example
    "$CICD_ROOT"/config/*.yml.example
  )
  shopt -u nullglob
  if [[ ${#yfiles[@]} -eq 0 ]]; then
    echo "==> YAML: no *.yaml in $CICD_ROOT/config (skipped)"
  elif [[ "$yaml_ok" == true ]]; then
    for f in "${yfiles[@]}"; do
      run_check "YAML: ${f#$CICD_ROOT/}" yaml_check "$f"
    done
  else
    echo "==> YAML: skipped (install Ruby+psych, PyYAML, or yq to validate)" >&2
  fi
else
  echo "==> YAML: no config directory (skipped)"
fi

# --- CloudStack Jenkins builder image (optional)
if [[ "$WITH_DOCKER" == true ]]; then
  builder_dockerfile="$CICD_ROOT/docker/Dockerfile.driver"
  if ! command -v docker >/dev/null 2>&1; then
    echo "==> Docker builder image: docker is not installed" >&2
    failures=$((failures + 1))
  elif [[ ! -f "$builder_dockerfile" ]]; then
    echo "==> Docker builder image: missing $builder_dockerfile" >&2
    failures=$((failures + 1))
  else
    run_check "Docker builder image" \
      docker build \
        -f "$builder_dockerfile" \
        -t cloudstack-presubmit-driver:validate \
        "$CICD_ROOT"
  fi
fi

if [[ "$failures" -ne 0 ]]; then
  echo "==> Done with $failures failure(s)." >&2
  exit 1
fi

echo "==> All checks passed."
