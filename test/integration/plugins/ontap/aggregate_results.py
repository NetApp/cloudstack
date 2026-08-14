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

"""Merge Marvin suite results into summary.tsv, summary.json, and summary.txt."""

from __future__ import print_function

import argparse
import json
import re
import sys
from datetime import datetime, timezone


def parse_results_txt(path):
    """Return list of (tag, label, test_id, status, detail) from Marvin results.txt."""
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            m = re.search(r"TestName: (\S+) \| Status : (\S+)", line)
            if m:
                rows.append(("", "", m.group(1), m.group(2), ""))
                continue
            m = re.match(r"(.+?) \.\.\. SKIP: (.+)$", line)
            if m:
                name = m.group(1).strip()
                detail = m.group(2).strip()
                rows.append(("", "", name, "SKIP", detail))
    return rows


def parse_tsv_line(line):
    parts = line.rstrip("\n").split("\t", 4)
    while len(parts) < 5:
        parts.append("")
    return tuple(parts)


def normalize_status(status):
    st = (status or "").upper()
    if st == "SUCCESS":
        return "PASS", "pass"
    if st == "SKIP":
        return "SKIP", "skip"
    return "FAIL", "fail"


def format_summary_text(rows):
    lines = [
        "================================================================",
        "  TEST SUMMARY",
        "================================================================",
    ]
    pass_n = fail_n = skip_n = 0
    current_label = None
    for tag, label, test_id, status, detail in rows:
        group = "[%s] %s" % (tag, label) if tag else label
        if group != current_label:
            if current_label is not None:
                lines.append("")
            lines.append("  %s" % group)
            current_label = group
        mark, bucket = normalize_status(status)
        if bucket == "pass":
            pass_n += 1
        elif bucket == "skip":
            skip_n += 1
        else:
            fail_n += 1
        suffix = ""
        st = (status or "").upper()
        if st == "SKIP" and detail:
            suffix = " — %s" % detail
        elif st not in ("SUCCESS", "SKIP"):
            suffix = " — %s" % status
        lines.append("    %-4s  %s%s" % (mark, test_id, suffix))

    total = pass_n + fail_n + skip_n
    lines.extend([
        "",
        "================================================================",
        "  TOTAL: %d passed, %d failed, %d skipped (%d tests)" % (
            pass_n, fail_n, skip_n, total),
        "================================================================",
    ])
    return "\n".join(lines) + "\n", pass_n, fail_n, skip_n


def rows_to_json(rows, meta=None):
    suites = {}
    tests = []
    pass_n = fail_n = skip_n = 0
    for tag, label, test_id, status, detail in rows:
        mark, bucket = normalize_status(status)
        if bucket == "pass":
            pass_n += 1
        elif bucket == "skip":
            skip_n += 1
        else:
            fail_n += 1
        suite_key = tag or label
        if suite_key not in suites:
            suites[suite_key] = {
                "tag": tag,
                "label": label,
                "passed": 0,
                "failed": 0,
                "skipped": 0,
                "tests": [],
            }
        suites[suite_key]["tests"].append({
            "name": test_id,
            "status": mark,
            "detail": detail or None,
        })
        if bucket == "pass":
            suites[suite_key]["passed"] += 1
        elif bucket == "skip":
            suites[suite_key]["skipped"] += 1
        else:
            suites[suite_key]["failed"] += 1
        tests.append({
            "tag": tag,
            "label": label,
            "name": test_id,
            "status": mark,
            "detail": detail or None,
        })

    payload = {
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "totals": {
            "passed": pass_n,
            "failed": fail_n,
            "skipped": skip_n,
            "total": pass_n + fail_n + skip_n,
        },
        "suites": list(suites.values()),
        "tests": tests,
    }
    if meta:
        payload["run"] = meta
    return payload


def load_rows_from_tsv(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                rows.append(parse_tsv_line(line))
    return rows


def load_rows_from_suite_specs(specs):
    rows = []
    for spec in specs:
        parts = spec.split(":", 2)
        if len(parts) != 3:
            print("Invalid --suite spec (want tag:label:path): %s" % spec,
                  file=sys.stderr)
            sys.exit(1)
        tag, label, path = parts
        for _tag, _label, test_id, status, detail in parse_results_txt(path):
            rows.append((tag, label, test_id, status, detail))
    return rows


def write_outputs(out_dir, rows, meta=None):
    tsv_path = out_dir + "/summary.tsv"
    json_path = out_dir + "/summary.json"
    txt_path = out_dir + "/summary.txt"

    with open(tsv_path, "w", encoding="utf-8") as fh:
        for row in rows:
            fh.write("\t".join(row) + "\n")

    summary_text, pass_n, fail_n, skip_n = format_summary_text(rows)
    with open(txt_path, "w", encoding="utf-8") as fh:
        fh.write(summary_text)

    payload = rows_to_json(rows, meta=meta)
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)
        fh.write("\n")

    return pass_n, fail_n, skip_n, summary_text


def main():
    parser = argparse.ArgumentParser(
        description="Aggregate Marvin test results into summary files.")
    parser.add_argument(
        "--out-dir", required=True,
        help="Directory for summary.tsv, summary.json, summary.txt")
    parser.add_argument(
        "--summary-tsv",
        help="Read existing tab-separated summary (from run_tests.sh)")
    parser.add_argument(
        "--suite", action="append", default=[],
        help="Suite spec tag:label:path/to/results.txt (repeatable)")
    parser.add_argument(
        "--meta-json",
        help="JSON string or path to run metadata merged into summary.json")
    parser.add_argument(
        "--print", dest="print_summary", action="store_true",
        help="Print human-readable summary to stdout")
    args = parser.parse_args()

    if args.summary_tsv:
        rows = load_rows_from_tsv(args.summary_tsv)
    elif args.suite:
        rows = load_rows_from_suite_specs(args.suite)
    else:
        print("Provide --summary-tsv or at least one --suite", file=sys.stderr)
        sys.exit(1)

    meta = None
    if args.meta_json:
        if args.meta_json.startswith("{"):
            meta = json.loads(args.meta_json)
        else:
            with open(args.meta_json, encoding="utf-8") as fh:
                meta = json.load(fh)

    pass_n, fail_n, skip_n, summary_text = write_outputs(
        args.out_dir.rstrip("/"), rows, meta=meta)

    if args.print_summary:
        print(summary_text, end="")

    return 0 if fail_n == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
