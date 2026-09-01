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
#   GitHub reporting for one presubmit run: create prints the new
#   cloudstack-ontap-presubmit check-run ID for the head SHA, complete PATCHes
#   that run with a conclusion, and commit-email prints the head commit author
#   address used for presubmit mail.
# Dependencies:
#   Commands: curl, python3 (stdlib), mktemp.
#   GITHUB_ACCESS_TOKEN, GITHUB_REPOSITORY — required by every subcommand; the
#   token is passed to curl through a 0600 config file so it stays out of the
#   process list.
#   GITHUB_HEAD_SHA — create and commit-email. BUILD_URL — create and complete.
#   GITHUB_CHECK_RUN_ID, GITHUB_CHECK_CONCLUSION — complete.
#   Optional: PR_ID and BUILD_TAG label the check run.
#   Called by the Jenkinsfile. Does not call other private-cicd scripts.

set -euo pipefail

readonly CHECK_NAME='cloudstack-ontap-presubmit'
readonly API_ROOT='https://api.github.com'

require_env() {
    local name
    for name in "$@"; do
        if [[ -z "${!name:-}" ]]; then
            echo "Required environment variable ${name} is empty." >&2
            exit 2
        fi
    done
}

github_api() {
    local method="$1"
    local endpoint="$2"
    local payload_file="${3:-}"
    local auth_config status
    auth_config="$(mktemp)"
    chmod 600 "$auth_config"
    printf 'header = "Authorization: Bearer %s"\n' \
        "$GITHUB_ACCESS_TOKEN" > "$auth_config"
    local args=(
        --fail-with-body
        --silent
        --show-error
        --request "$method"
        --config "$auth_config"
        --header 'Accept: application/vnd.github+json'
        --header 'X-GitHub-Api-Version: 2022-11-28'
    )
    if [[ -n "$payload_file" ]]; then
        args+=(--header 'Content-Type: application/json' --data-binary "@${payload_file}")
    fi
    set +e
    curl "${args[@]}" "${API_ROOT}${endpoint}"
    status=$?
    set -e
    rm -f "$auth_config"
    return "$status"
}

create_check() {
    require_env GITHUB_ACCESS_TOKEN GITHUB_REPOSITORY GITHUB_HEAD_SHA BUILD_URL
    local payload response
    payload="$(mktemp)"
    response="$(mktemp)"
    trap "rm -f '$payload' '$response'" EXIT

    python3 - "$payload" <<'PY'
import json
import os
import sys

payload = {
    "name": "cloudstack-ontap-presubmit",
    "head_sha": os.environ["GITHUB_HEAD_SHA"],
    "status": "in_progress",
    "details_url": os.environ["BUILD_URL"],
    "external_id": os.environ.get("BUILD_TAG", ""),
    "output": {
        "title": f"CloudStack ONTAP presubmit for PR-{os.environ.get('PR_ID', 'unknown')}",
        "summary": (
            f"Jenkins build started for {os.environ['GITHUB_HEAD_SHA'][:12]}. "
            f"Details: {os.environ['BUILD_URL']}"
        ),
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(payload, output)
PY

    github_api POST "/repos/${GITHUB_REPOSITORY}/check-runs" "$payload" > "$response"
    python3 - "$response" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    print(json.load(response)["id"])
PY
}

complete_check() {
    require_env GITHUB_ACCESS_TOKEN GITHUB_REPOSITORY GITHUB_CHECK_RUN_ID \
        GITHUB_CHECK_CONCLUSION BUILD_URL
    local payload response
    payload="$(mktemp)"
    response="$(mktemp)"
    trap "rm -f '$payload' '$response'" EXIT

    python3 - "$payload" <<'PY'
import json
import os
import sys

conclusion = os.environ["GITHUB_CHECK_CONCLUSION"]
payload = {
    "name": "cloudstack-ontap-presubmit",
    "status": "completed",
    "conclusion": conclusion,
    "details_url": os.environ["BUILD_URL"],
    "output": {
        "title": f"CloudStack ONTAP presubmit: {conclusion}",
        "summary": (
            f"Jenkins build completed with conclusion {conclusion}. "
            f"Details and logs: {os.environ['BUILD_URL']}"
        ),
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(payload, output)
PY

    if ! github_api PATCH \
            "/repos/${GITHUB_REPOSITORY}/check-runs/${GITHUB_CHECK_RUN_ID}" \
            "$payload" > "$response"; then
        echo "GitHub Check completion response:" >&2
        python3 - "$response" >&2 <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    try:
        payload = json.load(response)
    except json.JSONDecodeError:
        response.seek(0)
        print(response.read())
    else:
        print(payload.get("message", "No GitHub error message was returned."))
PY
        return 1
    fi
}

commit_email() {
    require_env GITHUB_ACCESS_TOKEN GITHUB_REPOSITORY GITHUB_HEAD_SHA
    local response
    response="$(mktemp)"
    trap "rm -f '$response'" EXIT
    github_api GET \
        "/repos/${GITHUB_REPOSITORY}/commits/${GITHUB_HEAD_SHA}" > "$response"
    python3 - "$response" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    payload = json.load(response)
print(payload.get("commit", {}).get("author", {}).get("email") or "")
PY
}

case "${1:-}" in
    create)
        create_check
        ;;
    complete)
        complete_check
        ;;
    commit-email)
        commit_email
        ;;
    *)
        echo "Usage: $0 {create|complete|commit-email}" >&2
        exit 2
        ;;
esac
