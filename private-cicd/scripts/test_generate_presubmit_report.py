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

import importlib.util
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "generate_presubmit_report",
    Path(__file__).with_name("generate-presubmit-report.py"))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)

SEP = "=" * 70
DASH = "-" * 70
RESULTS = f"""Start the VM. ... === TestName: test_06_start | Status : EXCEPTION ===
ERROR
Check the pool. ... === TestName: test_07_pool | Status : FAIL ===
FAIL
Detach it. ... === TestName: test_08_detach | Status : SUCCESS ===
ok

{SEP}
ERROR: Start the VM.
{DASH}
Traceback (most recent call last):
  File "test.py", line 750, in test_06_start
    self.apiClient.startVirtualMachine(cmd)
Exception: Job failed: {{errortext : 'Unable to orchestrate the start of VM'}}
-------------------- >> begin captured logging << --------------------
captured line that must not be shown
--------------------- >> end captured logging << ---------------------

{SEP}
FAIL: Check the pool.
{DASH}
Traceback (most recent call last):
  File "test.py", line 90, in test_07_pool
    self.assertEqual(a, b)
AssertionError: 1 != 2
{DASH}
Ran 3 tests in 1.0s

FAILED (errors=1, failures=1)
"""
SETUP_ERROR = f"""{SEP}
ERROR: setUpClass (suite.Class)
{DASH}
Traceback (most recent call last):
RuntimeError: pool create failed
{DASH}
Ran 0 tests in 0.1s
"""


class ParseSuiteFailuresTest(unittest.TestCase):
    def parse(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.txt"
            path.write_text(text, encoding="utf-8")
            return report.parse_suite_failures(path)

    def test_error_and_fail_blocks_are_mapped_to_test_names(self):
        failures = self.parse(RESULTS)
        self.assertEqual([f["kind"] for f in failures], ["ERROR", "FAIL"])
        self.assertEqual(failures[0]["names"], ("test_06_start",))
        self.assertEqual(failures[1]["names"], ("test_07_pool",))

    def test_error_is_the_exception_line_without_captured_logging(self):
        failures = self.parse(RESULTS)
        self.assertTrue(failures[0]["error"].startswith("Exception: Job failed"))
        self.assertIn("Unable to orchestrate", failures[0]["error"])
        self.assertEqual(failures[1]["error"], "AssertionError: 1 != 2")
        self.assertNotIn(
            "must not be shown", "\n".join(failures[0]["lines"]))

    def test_cloudstack_job_failure_leads_with_its_message(self):
        line = ("Exception: Job failed: {accountid : 'a', account : 'admin', "
                "jobresult : {errorcode : 530, errortext : 'Unable to start "
                "the VM'}}")
        text = f"{SEP}\nERROR: d\n{DASH}\n{line}\n"
        self.assertEqual(
            self.parse(text)[0]["error"],
            "Job failed (code 530): Unable to start the VM")

    def test_unmapped_block_is_kept_without_names(self):
        failures = self.parse(SETUP_ERROR)
        self.assertEqual(len(failures), 1)
        self.assertEqual(failures[0]["names"], ())
        self.assertEqual(failures[0]["error"], "RuntimeError: pool create failed")

    def test_missing_file_or_clean_run_gives_no_failures(self):
        self.assertEqual(
            report.parse_suite_failures(Path("/nonexistent/results.txt")), ())
        self.assertEqual(self.parse("Detach. ... === TestName: t | Status : SUCCESS ===\nok\n"), ())

    def test_long_error_is_truncated(self):
        long_line = "Exception: " + "x" * 2000
        text = f"{SEP}\nERROR: d\n{DASH}\n{long_line}\n"
        error = self.parse(text)[0]["error"]
        self.assertEqual(len(error), report.MAX_ERROR_CHARS + 1)


class FailurePageTest(unittest.TestCase):
    def test_suite_page_shows_failure_before_the_log(self):
        with tempfile.TemporaryDirectory() as directory:
            suite = (Path(directory) / "phase2" / "marvin" / "ontap-results"
                     / "run-iscsi" / "suites" / "vm")
            suite.mkdir(parents=True)
            (suite / "results.txt").write_text(RESULTS, encoding="utf-8")
            log = suite / "stdout.log"
            log.write_text(
                "=== TestName: test_06_start | Status : EXCEPTION ===\n"
                "=== TestName: test_08_detach | Status : SUCCESS ===\n",
                encoding="utf-8")
            management = (Path(directory) / "phase2" / "runtime-logs"
                          / "var-log-cloudstack-management")
            management.mkdir(parents=True)
            (management / "management-server.log").write_text("log\n")
            report.generate_log_artifacts(Path(directory), None)
            page = (suite / "stdout.log.html").read_text(encoding="utf-8")
        self.assertIn('id="fail-test_06_start"', page)
        self.assertIn("Unable to orchestrate", page)
        self.assertIn('id="test-test_08_detach"', page)
        self.assertIn("Management server log", page)
        self.assertIn(
            "../../../../../runtime-logs/var-log-cloudstack-management/"
            "management-server.log", page)
        self.assertNotIn("Agent log", page)
        self.assertLess(page.index("Failure details"), page.index("Suite log"))
        self.assertLess(page.index("Suite log"), page.index("TestName"))

    def test_clean_suite_page_has_no_failure_section(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "stdout.log"
            log.write_text("=== TestName: t | Status : SUCCESS ===\n",
                           encoding="utf-8")
            destination = Path(directory) / "stdout.log.html"
            report.log_to_html(log, destination)
            self.assertNotIn(
                "Failure details", destination.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
