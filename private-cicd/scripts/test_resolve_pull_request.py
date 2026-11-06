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

"""Unit tests for resolve-pull-request.py."""

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("resolve-pull-request.py")
SPEC = importlib.util.spec_from_file_location("resolve_pull_request", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SHA = "a" * 40


def pull(number="98", draft=False, base="main", repo="NetApp/cloudstack", sha=SHA):
    return {
        "number": int(number),
        "title": "Test",
        "html_url": f"https://github.com/NetApp/cloudstack/pull/{number}",
        "draft": draft,
        "base": {"ref": base},
        "head": {
            "ref": "feature/test",
            "sha": sha,
            "repo": {"full_name": repo} if repo is not None else None,
        },
        "user": {"login": "tester", "email": None},
    }


class AssessPullRequestTest(unittest.TestCase):
    def test_accepts_matching_origin_pr(self):
        decision = MODULE.assess(pull(), "98", SHA, "main", "NetApp/cloudstack")

        self.assertTrue(decision["eligible"])
        self.assertEqual("", decision["reason"])
        self.assertEqual("tester", decision["pr_author_login"])
        self.assertEqual("", decision["pr_author_email"])

    def test_rejects_draft_fork_base_and_sha_mismatch(self):
        cases = [
            pull(draft=True),
            pull(repo="other/cloudstack"),
            pull(repo=None),
            pull(base="release"),
            pull(sha="b" * 40),
        ]

        for candidate in cases:
            decision = MODULE.assess(
                candidate, "98", SHA, "main", "NetApp/cloudstack")
            self.assertFalse(decision["eligible"], candidate)

    def test_accepts_github_head_when_jenkins_sha_is_missing(self):
        decision = MODULE.assess(pull(), "98", "", "main", "NetApp/cloudstack")

        self.assertTrue(decision["eligible"])
        self.assertEqual(SHA, decision["pr_head_sha"])

    def test_rejects_change_target_other_than_main(self):
        decision = MODULE.assess(
            pull(), "98", SHA, "feature/other", "NetApp/cloudstack")

        self.assertFalse(decision["eligible"])
        self.assertIn("change target", decision["reason"])


if __name__ == "__main__":
    unittest.main()
