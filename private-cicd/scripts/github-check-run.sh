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
#   that run with a conclusion, commit-email prints the head commit author
#   address used for presubmit mail, and diagnose probes the App's token type,
#   repository access and Checks permissions to explain a refused check run.
# Dependencies:
#   Commands: curl, python3 (stdlib), mktemp.
#   GITHUB_ACCESS_TOKEN, GITHUB_REPOSITORY — required by every subcommand; the
#   token is passed to curl through a 0600 config file so it stays out of the
#   process list.
#   GITHUB_HEAD_SHA — create, commit-email and diagnose. BUILD_URL — create and
#   complete. GITHUB_CHECK_RUN_ID, GITHUB_CHECK_CONCLUSION — complete.
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

# Prints the HTTP status code and writes the response body to $3. Unlike
# github_api this never fails, so a caller can branch on the status code.
github_probe() {
    local method="$1"
    local endpoint="$2"
    local body_file="$3"
    local payload_file="${4:-}"
    local auth_config
    auth_config="$(mktemp)"
    chmod 600 "$auth_config"
    printf 'header = "Authorization: Bearer %s"\n' \
        "$GITHUB_ACCESS_TOKEN" > "$auth_config"
    local args=(
        --silent
        --show-error
        --request "$method"
        --config "$auth_config"
        --header 'Accept: application/vnd.github+json'
        --header 'X-GitHub-Api-Version: 2022-11-28'
        --output "$body_file"
        --write-out '%{http_code}'
    )
    if [[ -n "$payload_file" ]]; then
        args+=(--header 'Content-Type: application/json' --data-binary "@${payload_file}")
    fi
    curl "${args[@]}" "${API_ROOT}${endpoint}" || true
    rm -f "$auth_config"
}

# GitHub explains every refusal in the response body. Printing it is the
# difference between "HTTP 403" and knowing which permission is missing.
print_github_error() {
    local body_file="$1"
    [[ -s "$body_file" ]] || return 0
    python3 - "$body_file" >&2 <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    try:
        payload = json.load(handle)
    except json.JSONDecodeError:
        handle.seek(0)
        print(f"    body:    {handle.read().strip()}")
    else:
        print(f"    message: {payload.get('message', '(none)')}")
        for item in payload.get("errors") or []:
            print(f"    error:   {item}")
        if payload.get("documentation_url"):
            print(f"    docs:    {payload['documentation_url']}")
PY
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

    github_api POST "/repos/${GITHUB_REPOSITORY}/check-runs" "$payload" > "$response" || {
        local status=$?
        echo "GitHub refused to create the check run (curl exit ${status}):" >&2
        print_github_error "$response"
        echo "    Re-run this job with SOURCE_MODE=diagnose to identify the cause." >&2
        return "$status"
    }
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
        echo "GitHub refused to complete the check run:" >&2
        print_github_error "$response"
        return 1
    fi
}

# Read-only probes plus one write probe, ordered so that the first failure
# names the cause. Reads succeed for almost any token, so a create-only 403
# cannot be diagnosed from the worker log alone.
diagnose_app() {
    require_env GITHUB_ACCESS_TOKEN GITHUB_REPOSITORY GITHUB_HEAD_SHA
    local body payload status verdict archived
    body="$(mktemp)"
    payload="$(mktemp)"
    trap "rm -f '$body' '$payload'" EXIT
    verdict=''

    echo "Repository : ${GITHUB_REPOSITORY}"
    echo "Head SHA   : ${GITHUB_HEAD_SHA}"
    if [[ "${GITHUB_APP:-}" =~ ^[0-9]+$ ]]; then
        echo 'Credential : username is numeric, consistent with a GitHub App ID'
    else
        echo 'Credential : username is not numeric; a Jenkins GitHub App'
        echo '             credential supplies the numeric App ID as username'
    fi
    echo

    status="$(github_probe GET '/installation/repositories?per_page=1' "$body")"
    echo "[1] GET /installation/repositories -> HTTP ${status}"
    case "$status" in
        200)
            echo '    Token is a GitHub App installation token.'
            ;;
        401)
            print_github_error "$body"
            verdict='GitHub rejected the token itself. Re-check the App ID and private key in the Jenkins credential; the key must be unencrypted PKCS#8.'
            ;;
        403)
            print_github_error "$body"
            echo '    Only GitHub App installation tokens may create check runs.'
            verdict='The credential is not issuing a GitHub App installation token. Set its Kind to GitHub App.'
            ;;
        *)
            print_github_error "$body"
            ;;
    esac

    status="$(github_probe GET "/repos/${GITHUB_REPOSITORY}" "$body")"
    echo "[2] GET /repos/${GITHUB_REPOSITORY} -> HTTP ${status}"
    if [[ "$status" == '200' ]]; then
        archived="$(python3 -c \
            'import json,sys; print(json.load(open(sys.argv[1])).get("archived"))' \
            "$body")"
        echo "    archived=${archived}"
        if [[ "$archived" == 'True' ]]; then
            verdict="${verdict:-The repository is archived, so GitHub rejects every write including check runs.}"
        fi
    else
        print_github_error "$body"
    fi

    status="$(github_probe GET \
        "/repos/${GITHUB_REPOSITORY}/commits/${GITHUB_HEAD_SHA}/check-runs?per_page=1" \
        "$body")"
    echo "[3] GET commits/<sha>/check-runs (needs Checks: read) -> HTTP ${status}"
    case "$status" in
        200)
            echo '    Checks: read is granted to this installation.'
            ;;
        403)
            print_github_error "$body"
            verdict="${verdict:-The installation holds no Checks permission. Approve the updated permission request on the ${GITHUB_REPOSITORY} installation.}"
            ;;
        404)
            print_github_error "$body"
            verdict="${verdict:-The App is not installed on ${GITHUB_REPOSITORY}, or the repository name is wrong.}"
            ;;
        *)
            print_github_error "$body"
            ;;
    esac

    python3 - "$payload" <<'PY'
import json
import os
import sys

payload = {
    "name": "cloudstack-ontap-presubmit-diagnostic",
    "head_sha": os.environ["GITHUB_HEAD_SHA"],
    "status": "completed",
    "conclusion": "neutral",
    "output": {
        "title": "GitHub App Checks write probe",
        "summary": (
            "Created by SOURCE_MODE=diagnose to verify Checks write access. "
            "This check is informational and never gates a merge."
        ),
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(payload, output)
PY

    status="$(github_probe POST "/repos/${GITHUB_REPOSITORY}/check-runs" \
        "$body" "$payload")"
    echo "[4] POST /repos/${GITHUB_REPOSITORY}/check-runs -> HTTP ${status}"
    case "$status" in
        200|201)
            echo '    Checks: write works.'
            echo "    A neutral cloudstack-ontap-presubmit-diagnostic check now"
            echo "    exists on ${GITHUB_HEAD_SHA:0:12}."
            verdict="${verdict:-Checks write succeeded, so the presubmit check will publish on the next worker run.}"
            ;;
        403)
            print_github_error "$body"
            verdict="${verdict:-Checks write is denied while reads succeed. Approve the updated Checks permission on the installation.}"
            ;;
        422)
            print_github_error "$body"
            verdict="${verdict:-GitHub rejected the head SHA because it is absent from ${GITHUB_REPOSITORY}, which happens for pull requests opened from a fork.}"
            ;;
        *)
            print_github_error "$body"
            ;;
    esac

    echo
    echo "Verdict: ${verdict:-No single cause identified; read the probe output above.}"
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
    diagnose)
        diagnose_app
        ;;
    *)
        echo "Usage: $0 {create|complete|commit-email|diagnose}" >&2
        exit 2
        ;;
esac
