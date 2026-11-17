#!/usr/bin/env python3
#
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

"""Unit tests for ontap-coverage.py."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("ontap-coverage.py")
SPEC = importlib.util.spec_from_file_location("ontap_coverage", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

DOCTYPE = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">')


def report(files, line_missed, line_covered):
    """A JaCoCo report. files maps a name to {line number: covered?}."""
    sources = ""
    for name, lines in files.items():
        package, _, source = name.rpartition("/")
        entries = "".join(
            f'<line nr="{nr}" mi="{0 if hit else 1}" ci="{1 if hit else 0}" mb="0" cb="0"/>'
            for nr, hit in lines.items())
        sources += (f'<package name="{package}"><sourcefile name="{source}">'
                    f"{entries}</sourcefile></package>")
    return (f'{DOCTYPE}<report name="t">{sources}'
            f'<counter type="LINE" missed="{line_missed}" covered="{line_covered}"/>'
            '<counter type="BRANCH" missed="3" covered="1"/>'
            '<counter type="METHOD" missed="2" covered="2"/>'
            '<counter type="CLASS" missed="0" covered="1"/></report>')


A = "org/apache/cloudstack/storage/service/A.java"
B = "org/apache/cloudstack/storage/driver/B.java"


def write(directory, name, text):
    path = Path(directory) / name
    path.write_text(text, encoding="utf-8")
    return path


class CoverageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def reports(self):
        unit = write(self.directory.name, "unit.xml",
                     report({A: {1: True, 2: True, 3: False, 4: False}, B: {1: False}}, 3, 2))
        functional = write(self.directory.name, "functional.xml",
                           report({A: {1: False, 2: False, 3: True, 4: False}, B: {1: True}}, 3, 2))
        return unit, functional

    def test_parse_reads_totals_and_lines(self):
        unit, _ = self.reports()
        parsed = MODULE.parse_report(unit)

        self.assertEqual((3, 2), parsed["totals"]["LINE"])
        self.assertEqual((3, 1), parsed["totals"]["BRANCH"])
        self.assertEqual((0, 1), parsed["files"][A]["lines"][1])
        self.assertEqual((1, 0), parsed["files"][A]["lines"][3])

    def test_combined_coverage_is_the_union_of_covered_lines(self):
        unit, functional = self.reports()
        summary = MODULE.build_summary(
            MODULE.parse_report(unit), MODULE.parse_report(functional), 10, [])

        # A: lines 1,2 (unit) + 3 (functional) of 4; B: 1 (functional) of 1.
        self.assertEqual(4, summary["combined_line"]["covered"])
        self.assertEqual(1, summary["combined_line"]["missed"])
        self.assertEqual(80.0, summary["combined_line"]["percent"])
        self.assertEqual(40.0, summary["unit"]["line"]["percent"])
        rows = {row["file"]: row for row in summary["least_covered"]}
        self.assertEqual(75.0, rows[A]["combined"])
        self.assertEqual(50.0, rows[A]["unit"])
        self.assertEqual(25.0, rows[A]["functional"])
        self.assertNotIn(B, rows)  # fully covered files are not listed

    def test_one_missing_report_still_summarises_the_other(self):
        unit, _ = self.reports()
        summary = MODULE.build_summary(MODULE.parse_report(unit), None, 10, [])

        self.assertIsNone(summary["functional"])
        self.assertEqual(40.0, summary["combined_line"]["percent"])

    def test_missing_or_broken_report_is_not_a_report(self):
        broken = write(self.directory.name, "broken.xml", "<report><oops>")
        other = write(self.directory.name, "other.xml", "<html/>")

        self.assertIsNone(MODULE.parse_report(Path(self.directory.name) / "none.xml"))
        self.assertIsNone(MODULE.parse_report(broken))
        self.assertIsNone(MODULE.parse_report(other))

    def test_main_writes_json_markdown_and_html(self):
        unit, functional = self.reports()
        out = Path(self.directory.name) / "out"
        code = MODULE.main(["--unit", str(unit), "--functional", str(functional),
                            "--output-dir", str(out), "--unit-link", "https://j/u"])

        self.assertEqual(0, code)
        summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(80.0, summary["combined_line"]["percent"])
        markdown = (out / "summary.md").read_text(encoding="utf-8")
        self.assertTrue(markdown.startswith(MODULE.COMMENT_MARKER))
        self.assertIn("| **Combined** | **80.0%** |", markdown)
        self.assertIn("[Unit coverage report](https://j/u)", markdown)
        page = (out / "summary.html").read_text(encoding="utf-8")
        self.assertIn("Combined", page)
        self.assertIn('href="https://j/u"', page)

    def test_main_with_no_reports_explains_why(self):
        out = Path(self.directory.name) / "out"
        MODULE.main(["--unit", "/nonexistent.xml", "--output-dir", str(out)])

        summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertIsNone(summary["combined_line"])
        self.assertEqual(2, len(summary["notes"]))
        self.assertIn("not available", (out / "summary.md").read_text(encoding="utf-8"))

    def test_file_names_are_escaped_in_html(self):
        unit = write(self.directory.name, "unit.xml",
                     report({"p/&lt;b&gt;.java": {1: False}}, 1, 0))  # XML-escaped name
        out = Path(self.directory.name) / "out"
        MODULE.main(["--unit", str(unit), "--output-dir", str(out)])

        page = (out / "summary.html").read_text(encoding="utf-8")
        self.assertNotIn("<b>.java", page)
        self.assertIn("&lt;b&gt;.java", page)


if __name__ == "__main__":
    unittest.main()
