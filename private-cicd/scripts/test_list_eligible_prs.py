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

"""Unit tests for list-eligible-prs.py."""

import importlib.util
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("list-eligible-prs.py")
SPEC = importlib.util.spec_from_file_location("list_eligible_prs", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def pull(number, updated_at, base="main", draft=False):
    return {
        "number": number,
        "title": f"PR {number}",
        "html_url": f"https://github.com/NetApp/cloudstack/pull/{number}",
        "updated_at": updated_at,
        "draft": draft,
        "base": {"ref": base},
        "head": {"ref": f"feature/{number}", "sha": f"{number:040x}"},
        "user": {"login": f"user-{number}", "email": None},
    }


class EligiblePullsTest(unittest.TestCase):
    def test_filters_draft_base_and_watermark(self):
        watermark = MODULE.parse_time("2026-09-02T08:00:00Z")
        pulls = [
            pull(1, "2026-09-02T08:00:01Z"),
            pull(2, "2026-09-02T08:00:02Z", draft=True),
            pull(3, "2026-09-02T08:00:03Z", base="release"),
            pull(4, "2026-09-02T08:00:00Z"),
            pull(5, "2026-09-02T07:59:59Z"),
        ]

        result = MODULE.eligible_pulls(pulls, "main", watermark)

        self.assertEqual(["1"], [item["pr_id"] for item in result])
        self.assertEqual(f"{1:040x}", result[0]["pr_head_sha"])

    def test_returns_oldest_updated_first(self):
        watermark = MODULE.parse_time("2026-09-02T08:00:00Z")
        pulls = [
            pull(3, "2026-09-02T08:00:03Z"),
            pull(1, "2026-09-02T08:00:01Z"),
            pull(2, "2026-09-02T08:00:02Z"),
        ]

        result = MODULE.eligible_pulls(pulls, "main", watermark)

        self.assertEqual(["1", "2", "3"], [item["pr_id"] for item in result])

    def test_list_pulls_follows_pagination(self):
        with mock.patch.object(
                MODULE,
                "github_get",
                side_effect=[([pull(1, "2026-09-02T08:00:01Z")],
                              "https://api.github.com/page-2"),
                             ([pull(2, "2026-09-02T08:00:02Z")], "")]) as get:
            result = MODULE.list_pulls("NetApp/cloudstack", "main", "token")

        self.assertEqual([1, 2], [item["number"] for item in result])
        self.assertEqual(
            "https://api.github.com/page-2",
            get.call_args_list[1].args[0],
        )


if __name__ == "__main__":
    unittest.main()
