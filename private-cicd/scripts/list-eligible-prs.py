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

"""List open, non-draft pull requests that the OpenLab poller may queue."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime


API_ROOT = "https://api.github.com"


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--base-branch", default="main")
    parser.add_argument(
        "--updated-after",
        required=True,
        help="Return PRs updated strictly after this ISO-8601 UTC timestamp.",
    )
    return parser.parse_args()


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def github_get(url, token):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "cloudstack-ontap-presubmit-discovery",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read().decode("utf-8")
            link = response.headers.get("Link", "")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(
            f"GitHub API {error.code} for {url}: {detail}") from error
    next_url = ""
    for part in link.split(","):
        if 'rel="next"' in part:
            next_url = part.split(";")[0].strip().strip("<>")
    return json.loads(body), next_url


def list_pulls(repository, base_branch, token):
    query = urllib.parse.urlencode({
        "state": "open",
        "base": base_branch,
        "sort": "updated",
        "direction": "desc",
        "per_page": "100",
    })
    url = f"{API_ROOT}/repos/{repository}/pulls?{query}"
    pulls = []
    while url:
        page, url = github_get(url, token)
        if not isinstance(page, list):
            raise SystemExit(f"Unexpected GitHub pulls payload: {page}")
        pulls.extend(page)
    return pulls


def eligible_pulls(pulls, base_branch, updated_after):
    eligible = []
    for pull in pulls:
        if pull.get("draft"):
            continue
        if pull.get("base", {}).get("ref") != base_branch:
            continue
        updated = parse_time(pull.get("updated_at", "1970-01-01T00:00:00Z"))
        if updated <= updated_after:
            continue
        user = pull.get("user") or {}
        eligible.append({
            "pr_id": str(pull.get("number", "")),
            "pr_title": pull.get("title") or "",
            "pr_url": pull.get("html_url") or "",
            "pr_head_branch": (pull.get("head") or {}).get("ref") or "",
            "pr_head_sha": (pull.get("head") or {}).get("sha") or "",
            "pr_author_login": user.get("login") or "",
            "pr_author_email": user.get("email") or "",
            "updated_at": pull.get("updated_at") or "",
        })
    eligible.sort(key=lambda item: parse_time(
        item["updated_at"] or "1970-01-01T00:00:00Z"))
    return eligible


def main():
    args = parse_args()
    token = os.environ.get("GITHUB_ACCESS_TOKEN", "").strip()
    if not token:
        raise SystemExit("GITHUB_ACCESS_TOKEN is empty.")
    try:
        updated_after = parse_time(args.updated_after)
    except ValueError as error:
        raise SystemExit(
            f"--updated-after is not a valid ISO-8601 timestamp: {error}"
        ) from error
    eligible = eligible_pulls(
        list_pulls(args.repository, args.base_branch, token),
        args.base_branch,
        updated_after,
    )
    json.dump(eligible, sys.stdout)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
