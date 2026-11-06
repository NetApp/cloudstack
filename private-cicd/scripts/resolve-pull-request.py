#!/usr/bin/env python3
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

"""Decide whether a multibranch build may test one origin pull request."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


API_ROOT = "https://api.github.com"


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--pr-id", required=True)
    parser.add_argument("--indexed-sha", required=True)
    parser.add_argument("--change-target", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def github_get(url, token):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "cloudstack-ontap-presubmit",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"GitHub API {error.code} for {url}: {detail}") from error


def is_main(ref):
    return ref in ("main", "refs/heads/main")


def assess(pull, pr_id, indexed_sha, change_target, expected_repository):
    head = (pull or {}).get("head") or {}
    base = (pull or {}).get("base") or {}
    user = (pull or {}).get("user") or {}
    repository = head.get("repo") or {}
    decision = {
        "eligible": False,
        "reason": "",
        "pr_id": str((pull or {}).get("number") or ""),
        "pr_head_sha": head.get("sha") or "",
        "pr_head_branch": head.get("ref") or "",
        "pr_base_branch": base.get("ref") or "",
        "pr_draft": bool((pull or {}).get("draft")),
        "pr_title": (pull or {}).get("title") or "",
        "pr_url": (pull or {}).get("html_url") or "",
        "pr_author_login": user.get("login") or "",
        "pr_author_email": user.get("email") or "",
        "head_repository": repository.get("full_name") or "",
    }
    if not isinstance(pull, dict):
        decision["reason"] = "GitHub pull request payload is missing."
    elif decision["pr_id"] != str(pr_id):
        decision["reason"] = "GitHub pull request number does not match the build."
    elif decision["head_repository"] != expected_repository:
        decision["reason"] = (
            f"head repository '{decision['head_repository']}' is not "
            f"'{expected_repository}'."
        )
    elif not is_main(decision["pr_base_branch"]) or not is_main(change_target):
        decision["reason"] = (
            f"base branch is '{decision['pr_base_branch']}' and change target "
            f"is '{change_target}'."
        )
    elif decision["pr_draft"]:
        decision["reason"] = "pull request is a draft."
    elif not decision["pr_head_sha"] or len(decision["pr_head_sha"]) != 40:
        decision["reason"] = "GitHub pull request head SHA is missing."
    elif (indexed_sha or "").strip() and decision["pr_head_sha"].lower() != indexed_sha.strip().lower():
        decision["reason"] = "indexed revision does not match the pull request head SHA."
    else:
        decision["eligible"] = True
    return decision


def main():
    args = parse_args()
    token = os.environ.get("GITHUB_ACCESS_TOKEN", "").strip()
    if not token:
        raise SystemExit("GITHUB_ACCESS_TOKEN is empty.")
    if not args.pr_id.isdigit():
        raise SystemExit("PR id must be numeric.")
    pull = github_get(
        f"{API_ROOT}/repos/{args.repository}/pulls/{args.pr_id}",
        token,
    )
    decision = assess(
        pull,
        args.pr_id,
        args.indexed_sha,
        args.change_target,
        args.repository,
    )
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(decision, handle)
        handle.write("\n")
    if not decision["eligible"]:
        print(decision["reason"], file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
