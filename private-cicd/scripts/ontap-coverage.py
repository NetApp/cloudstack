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

# Purpose:
#   Summarise ONTAP storage plugin coverage for one presubmit run. Reads the
#   JaCoCo XML reports of the unit tests (produced by the Maven build) and of
#   the functional Marvin tests (produced from the management server's JaCoCo
#   dump), and writes summary.json, summary.md and summary.html to --output-dir.
#   The combined figure is computed per source line, so it does not depend on
#   the two reports agreeing on class IDs: a line counts as covered when either
#   run executed it.
# Dependencies:
#   Python 3 standard library only.
#   Called by: coverage-report.sh. Read by generate-presubmit-report.py and the
#   Jenkinsfile, which publish the summary in the mail, the report, the GitHub
#   Check and a pull-request comment.

import argparse
import html
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Marks the pull-request comment so a later run updates it in place.
COMMENT_MARKER = "<!-- ontap-coverage-bot -->"
TITLE = "ONTAP storage plugin test coverage"
COUNTERS = ("LINE", "BRANCH", "METHOD", "CLASS")
DOCTYPE = re.compile(r"<!DOCTYPE[^>]*>")


def parse_report(path):
    """Return {"totals": {...}, "files": {...}} for one JaCoCo XML report.

    files maps "package/File.java" to {"lines": {nr: (missed, covered)}}.
    Returns None when the file is missing or is not a JaCoCo report.
    """
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
        root = ET.fromstring(DOCTYPE.sub("", text, count=1))
    except (OSError, ET.ParseError):
        return None
    if root.tag != "report":
        return None
    totals = {}
    for counter in root.findall("counter"):
        kind = counter.get("type")
        if kind in COUNTERS:
            totals[kind] = (int(counter.get("missed", 0)), int(counter.get("covered", 0)))
    files = {}
    for package in root.findall("package"):
        package_name = package.get("name", "")
        for source in package.findall("sourcefile"):
            lines = {}
            for line in source.findall("line"):
                lines[int(line.get("nr"))] = (
                    int(line.get("mi", 0)), int(line.get("ci", 0)))
            files[f"{package_name}/{source.get('name')}"] = {"lines": lines}
    return {"totals": totals, "files": files}


def percent(covered, missed):
    total = covered + missed
    return round(100.0 * covered / total, 1) if total else None


def counter_summary(totals, kind):
    missed, covered = totals.get(kind, (0, 0))
    return {"covered": covered, "missed": missed, "percent": percent(covered, missed)}


def covered_line_numbers(file_entry):
    return {nr for nr, (_, covered) in file_entry["lines"].items() if covered > 0}


def combine(unit, functional):
    """Line coverage of the union of both runs, plus a row for every file."""
    names = set()
    for report in (unit, functional):
        if report:
            names.update(report["files"])
    rows = []
    total_lines = total_covered = 0
    for name in sorted(names):
        line_sets = []
        all_lines = set()
        for report in (unit, functional):
            entry = report["files"].get(name) if report else None
            if entry:
                all_lines.update(entry["lines"])
                line_sets.append(covered_line_numbers(entry))
            else:
                line_sets.append(set())
        if not all_lines:
            continue
        unit_covered, functional_covered = line_sets
        combined_covered = unit_covered | functional_covered
        rows.append({
            "file": name,
            "lines": len(all_lines),
            "unit": percent(len(unit_covered), len(all_lines) - len(unit_covered))
            if unit else None,
            "functional": percent(len(functional_covered),
                                  len(all_lines) - len(functional_covered))
            if functional else None,
            "combined": percent(len(combined_covered),
                                len(all_lines) - len(combined_covered)),
            "missed": len(all_lines) - len(combined_covered),
        })
        total_lines += len(all_lines)
        total_covered += len(combined_covered)
    return {
        "covered": total_covered,
        "missed": total_lines - total_covered,
        "percent": percent(total_covered, total_lines - total_covered),
    }, rows


def build_summary(unit, functional, top, notes):
    combined, rows = combine(unit, functional)
    worst = sorted(rows, key=lambda row: (-row["missed"], row["file"]))
    return {
        "title": TITLE,
        "unit": ({kind.lower(): counter_summary(unit["totals"], kind) for kind in COUNTERS}
                 if unit else None),
        "functional": ({kind.lower(): counter_summary(functional["totals"], kind)
                        for kind in COUNTERS} if functional else None),
        "combined_line": combined if (unit or functional) else None,
        "least_covered": [row for row in worst if row["missed"] > 0][:top],
        "notes": notes,
    }


def fmt(value):
    return "n/a" if value is None else f"{value:.1f}%"


def cell(section, kind):
    return fmt(section[kind]["percent"]) if section else "not available"


def render_markdown(summary, links):
    unit, functional = summary["unit"], summary["functional"]
    combined = summary["combined_line"]
    lines = [COMMENT_MARKER, f"### {summary['title']}", ""]
    lines += [
        "| | Line | Branch | Method |",
        "| --- | --- | --- | --- |",
        f"| Unit tests | {cell(unit, 'line')} | {cell(unit, 'branch')} | {cell(unit, 'method')} |",
        f"| Functional (Marvin) tests | {cell(functional, 'line')} | "
        f"{cell(functional, 'branch')} | {cell(functional, 'method')} |",
        f"| **Combined** | **{fmt(combined['percent']) if combined else 'not available'}** | | |",
        "",
    ]
    if summary["least_covered"]:
        lines += ["Least covered files (by lines not covered by either run):", "",
                  "| File | Lines | Unit | Functional | Combined |",
                  "| --- | --- | --- | --- | --- |"]
        for row in summary["least_covered"]:
            name = row["file"].replace("org/apache/cloudstack/storage/", "")
            lines.append(f"| `{name}` | {row['lines']} | {fmt(row['unit'])} | "
                         f"{fmt(row['functional'])} | {fmt(row['combined'])} |")
        lines.append("")
    for note in summary["notes"]:
        lines += [f"> {note}", ""]
    for label, url in links:
        if url:
            lines.append(f"[{label}]({url})  ")
    return "\n".join(lines).rstrip() + "\n"


def render_html(summary, links):
    unit, functional = summary["unit"], summary["functional"]
    combined = summary["combined_line"]
    th = "text-align:left;background:#f2f4f7;border:1px solid #d0d5dd;padding:6px 10px"
    td = "border:1px solid #d0d5dd;padding:6px 10px"

    def row(label, section, bold=False):
        cells = [cell(section, kind) for kind in ("line", "branch", "method")]
        style = td + (";font-weight:bold" if bold else "")
        return (f'<tr><td style="{style}">{html.escape(label)}</td>' +
                "".join(f'<td style="{style}">{html.escape(value)}</td>' for value in cells) +
                "</tr>")

    parts = [f'<h3 style="margin:16px 0 8px">{html.escape(summary["title"])}</h3>',
             '<table style="border-collapse:collapse;font-size:13px">',
             f'<tr><th style="{th}"></th><th style="{th}">Line</th>'
             f'<th style="{th}">Branch</th><th style="{th}">Method</th></tr>',
             row("Unit tests", unit), row("Functional (Marvin) tests", functional),
             f'<tr><td style="{td};font-weight:bold">Combined</td>'
             f'<td style="{td};font-weight:bold">'
             f'{html.escape(fmt(combined["percent"]) if combined else "not available")}</td>'
             f'<td style="{td}"></td><td style="{td}"></td></tr>',
             "</table>"]
    if summary["least_covered"]:
        parts += ['<p style="margin:12px 0 4px">Least covered files '
                  '(lines not covered by either run):</p>',
                  '<table style="border-collapse:collapse;font-size:13px">',
                  f'<tr><th style="{th}">File</th><th style="{th}">Lines</th>'
                  f'<th style="{th}">Unit</th><th style="{th}">Functional</th>'
                  f'<th style="{th}">Combined</th></tr>']
        for item in summary["least_covered"]:
            name = item["file"].replace("org/apache/cloudstack/storage/", "")
            parts.append(
                f'<tr><td style="{td}"><code>{html.escape(name)}</code></td>'
                f'<td style="{td}">{item["lines"]}</td>'
                f'<td style="{td}">{html.escape(fmt(item["unit"]))}</td>'
                f'<td style="{td}">{html.escape(fmt(item["functional"]))}</td>'
                f'<td style="{td}">{html.escape(fmt(item["combined"]))}</td></tr>')
        parts.append("</table>")
    for note in summary["notes"]:
        parts.append(f'<p style="color:#475467;margin:8px 0">{html.escape(note)}</p>')
    link_html = " | ".join(
        f'<a href="{html.escape(url)}">{html.escape(label)}</a>'
        for label, url in links if url)
    if link_html:
        parts.append(f'<p style="margin:8px 0">{link_html}</p>')
    return "\n".join(parts) + "\n"


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Summarise ONTAP plugin unit and functional test coverage.")
    parser.add_argument("--unit", help="JaCoCo XML report of the unit tests")
    parser.add_argument("--functional", help="JaCoCo XML report of the functional tests")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--top", type=int, default=10,
                        help="number of least covered files to list")
    parser.add_argument("--unit-link", default="", help="URL of the unit coverage report")
    parser.add_argument("--functional-link", default="",
                        help="URL of the functional coverage report")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    notes = []
    unit = parse_report(args.unit) if args.unit else None
    functional = parse_report(args.functional) if args.functional else None
    if unit is None:
        notes.append("Unit-test coverage was not produced by this build.")
    if functional is None:
        notes.append("Functional-test coverage was not produced by this run.")
    summary = build_summary(unit, functional, args.top, notes)
    links = [("Unit coverage report", args.unit_link),
             ("Functional coverage report", args.functional_link)]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "summary.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8")
    (args.output_dir / "summary.md").write_text(
        render_markdown(summary, links), encoding="utf-8")
    (args.output_dir / "summary.html").write_text(
        render_html(summary, links), encoding="utf-8")
    combined = summary["combined_line"]
    print("ONTAP coverage: unit line %s, functional line %s, combined line %s" % (
        cell(summary["unit"], "line"), cell(summary["functional"], "line"),
        fmt(combined["percent"]) if combined else "not available"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
