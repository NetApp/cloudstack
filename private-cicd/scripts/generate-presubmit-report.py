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

"""Generate browsable HTML artifacts and the final presubmit mail body."""

import argparse
import html
import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote


STAGES = [
    "Validate source request",
    "Check eligibility",
    "Abort superseded run",
    "Checkout CI scripts",
    "Start PR reporting",
    "Validate builder",
    "Checkout source",
    "Build and unit tests",
    "Build Debian packages",
    "Deploy and run ONTAP integration",
]

TERMINAL_STATUSES = {"SUCCESS", "FAILURE", "ABORTED", "SKIPPED"}
TEXT_SUFFIXES = {".log", ".txt", ".tsv", ".properties"}
STATUS_ORDER = {"FAIL": 0, "EXCEPTION": 0, "FAILED": 0, "SKIP": 1, "PASS": 2}
ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--stage-events", required=True, type=Path)
    parser.add_argument("--build-url", required=True)
    parser.add_argument("--result", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--source-url", default="")
    parser.add_argument("--title", default="")
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--vm", default="")
    parser.add_argument("--started-at", required=True)
    parser.add_argument("--finished-at", required=True)
    parser.add_argument("--debian-log", type=Path)
    return parser.parse_args()


def parse_time(value):
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def format_duration(start, finish):
    if not start or not finish:
        return "—"
    seconds = max(0, int((finish - start).total_seconds()))
    hours, seconds = divmod(seconds, 3600)
    minutes, seconds = divmod(seconds, 60)
    if hours:
        return f"{hours}h {minutes}m {seconds}s"
    if minutes:
        return f"{minutes}m {seconds}s"
    return f"{seconds}s"


def slug(value):
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-") or "test"


def artifact_url(build_url, relative_path, fragment=""):
    encoded = "/".join(quote(part) for part in Path(relative_path).parts)
    url = f"{build_url.rstrip('/')}/artifact/{encoded}"
    return f"{url}#{quote(fragment)}" if fragment else url


def status_style(status):
    status = status.upper()
    if status in {"FAILURE", "FAIL", "FAILED", "EXCEPTION", "ABORTED"}:
        return "color:#b42318;font-weight:bold"
    if status in {"SUCCESS", "PASS"}:
        return "color:#067647;font-weight:bold"
    if status in {"SKIP", "SKIPPED", "NOT RUN"}:
        return "color:#475467;font-weight:bold"
    return "font-weight:bold"


def display_test_status(status):
    status = str(status or "FAIL").upper()
    return "PASS" if status == "SUCCESS" else status


def html_document(title, body):
    return f"""<!doctype html>
<html>
<head><meta charset="utf-8"><title>{html.escape(title)}</title></head>
<body style="font-family:Arial,sans-serif;color:#101828">
{body}
</body>
</html>
"""


def log_to_html(source, destination):
    lines = source.read_text(encoding="utf-8", errors="replace").splitlines()
    rendered = []
    test_pattern = re.compile(r"TestName:\s+(\S+)\s+\|\s+Status\s+:\s+(\S+)")
    anchor_positions = {}
    seen_anchors = set()
    for index, original_line in enumerate(lines):
        line = ANSI_ESCAPE.sub("", original_line)
        match = test_pattern.search(line)
        if not match:
            continue
        anchor_name = f"test-{slug(match.group(1))}"
        if anchor_name in seen_anchors:
            continue
        seen_anchors.add(anchor_name)
        anchor_positions.setdefault(max(0, index - 40), []).append(anchor_name)

    for index, original_line in enumerate(lines):
        line = ANSI_ESCAPE.sub("", original_line)
        anchor = "".join(
            f'<a id="{anchor_name}"></a>'
            for anchor_name in anchor_positions.get(index, []))
        escaped = html.escape(line)
        error_pattern = r"\b(FAIL(?:ED|URE)?|EXCEPTION|ERROR|Traceback)\b"
        if re.search(error_pattern, line, re.I):
            escaped = (
                f'<span style="color:#b42318;font-weight:bold">'
                f"{escaped}</span>")
        elif re.search(r"\b(SUCCESS|PASS)\b", line):
            escaped = f'<span style="color:#067647">{escaped}</span>'
        rendered.append(f"{anchor}{escaped}")
    body = (
        f"<h2>{html.escape(source.name)}</h2>"
        f'<pre style="white-space:pre-wrap;word-break:break-word;'
        f'background:#f9fafb;border:1px solid #d0d5dd;padding:16px">'
        + "\n".join(rendered)
        + "</pre>"
    )
    destination.write_text(
        html_document(source.name, body), encoding="utf-8")


def generate_log_artifacts(results, debian_log):
    if debian_log and debian_log.is_file():
        copied_log = results / "logs" / "deb-build.log"
        copied_log.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(debian_log, copied_log)

    candidates = [
        results / "unit-tests" / "maven.log",
        results / "logs" / "deb-build.log",
        results / "phase2" / "configure-cloudstack.log",
        results / "phase2" / "health-check.log",
    ]
    marvin = results / "phase2" / "marvin"
    candidates.extend(marvin.glob("*.log"))
    candidates.extend(
        path for path in (marvin / "ontap-results").rglob("*")
        if path.suffix.lower() in TEXT_SUFFIXES)
    for source in sorted(set(candidates)):
        if not source.is_file():
            continue
        log_to_html(source, Path(f"{source}.html"))


def parse_stage_events(stage_events, results):
    destination = results / "stage-events.tsv"
    if stage_events.is_file():
        shutil.copyfile(stage_events, destination)
    events = {stage: [] for stage in STAGES}
    if not destination.is_file():
        return events
    for line in destination.read_text(encoding="utf-8").splitlines():
        fields = line.split("\t")
        if len(fields) != 3 or fields[1] not in events:
            continue
        try:
            timestamp = parse_time(fields[0])
        except ValueError:
            continue
        events[fields[1]].append((timestamp, fields[2].upper()))
    return events


def stage_log(stage, results):
    mapping = {
        "Build and unit tests": results / "unit-tests" / "maven.log.html",
        "Build Debian packages": results / "logs" / "deb-build.log.html",
    }
    if stage == "Deploy and run ONTAP integration":
        marvin = results / "phase2" / "marvin"
        for summary_path in sorted(marvin.rglob("summary.json")):
            try:
                payload = json.loads(summary_path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if payload.get("totals", {}).get("failed", 0):
                protocol = payload.get("run", {}).get(
                    "protocol", summary_path.parent.name)
                failed_log = marvin / f"{protocol}.log.html"
                if failed_log.is_file():
                    return failed_log
        for protocol in ("iscsi", "nfs3", "setup_zone"):
            protocol_log = marvin / f"{protocol}.log.html"
            if protocol_log.is_file():
                return protocol_log
    path = mapping.get(stage)
    return path if path and path.is_file() else None


def stage_rows(events, results, build_url):
    rows = []
    failed_stage = ""
    for stage in STAGES:
        stage_events = events[stage]
        started = next(
            (time for time, status in stage_events if status == "STARTED"),
            None)
        waiting = next(
            (time for time, status in stage_events
             if status == "WAITING_FOR_VM"),
            None)
        terminal = next(
            ((time, status) for time, status in reversed(stage_events)
             if status in TERMINAL_STATUSES),
            None)
        finished, status = terminal if terminal else (None, "NOT RUN")
        if status == "FAILURE" and not failed_stage:
            failed_stage = stage
        queue_duration = format_duration(waiting, started) if waiting else "—"
        relative_log = stage_log(stage, results)
        if relative_log:
            relative_log = relative_log.relative_to(results.parent)
            log_link = (
                f'<a href="'
                f'{html.escape(artifact_url(build_url, relative_log))}">'
                "HTML log</a>")
        else:
            console_url = build_url.rstrip("/") + "/console"
            log_link = (
                f'<a href="{html.escape(console_url)}">'
                "Console</a>")
        started_text = (
            started.strftime("%Y-%m-%d %H:%M:%S UTC") if started else "—")
        rows.append(
            "<tr>"
            f"<td>{html.escape(stage)}</td>"
            f'<td style="{status_style(status)}">{html.escape(status)}</td>'
            f"<td>{html.escape(started_text)}</td>"
            f"<td>{html.escape(format_duration(started, finished))}</td>"
            f"<td>{html.escape(queue_duration)}</td>"
            f"<td>{log_link}</td>"
            "</tr>")
    return "\n".join(rows), failed_stage


def test_log_for_summary(summary_path, test):
    suite_dir = summary_path.parent / "suites" / test.get("tag", "")
    for name in ("stdout.log.html", "results.txt.html"):
        suite_log = suite_dir / name
        if suite_log.is_file():
            return suite_log
    protocol = summary_path.parent.name
    run = test.get("_run", {})
    protocol = run.get("protocol", protocol)
    fallback = summary_path.parents[2] / f"{protocol}.log.html"
    return fallback if fallback.is_file() else None


def load_tests(results):
    tests = []
    summaries = sorted(
        (results / "phase2" / "marvin").rglob("summary.json"))
    for summary_path in summaries:
        try:
            payload = json.loads(summary_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(payload.get("tests"), list):
            continue
        run = payload.get("run", {})
        protocol = run.get("protocol", summary_path.parent.name)
        summary_tsv = summary_path.with_name("summary.tsv")
        source_rows = []
        if summary_tsv.is_file():
            for line in summary_tsv.read_text(encoding="utf-8").splitlines():
                fields = line.split("\t", 4)
                if len(fields) >= 4:
                    source_rows.append({
                        "tag": fields[0],
                        "label": fields[1],
                        "name": fields[2],
                        "status": fields[3],
                        "detail": fields[4] if len(fields) == 5 else "",
                    })
        else:
            source_rows = payload["tests"]
        for test in source_rows:
            row = dict(test)
            row["_run"] = run
            row["_protocol"] = protocol
            row["_summary"] = summary_path
            tests.append(row)
    return sorted(
        tests,
        key=lambda item: (
            STATUS_ORDER.get(display_test_status(item.get("status")), 0),
            item.get("_protocol", ""),
            item.get("tag", ""),
            item.get("name", ""),
        ))


def test_rows(tests, results, build_url):
    rows = []
    for test in tests:
        status = display_test_status(test.get("status"))
        log_path = test_log_for_summary(test["_summary"], test)
        if log_path:
            relative = log_path.relative_to(results.parent)
            link = artifact_url(
                build_url, relative, f"test-{slug(test.get('name', 'test'))}")
            log_cell = f'<a href="{html.escape(link)}">HTML log</a>'
        else:
            log_cell = "—"
        suite = str(test.get("label") or test.get("tag") or "")
        rows.append(
            "<tr>"
            f"<td>{html.escape(str(test.get('_protocol', '')))}</td>"
            f"<td>{html.escape(suite)}</td>"
            f"<td><code>{html.escape(str(test.get('name', '')))}</code></td>"
            f'<td style="{status_style(status)}">{html.escape(status)}</td>'
            f"<td>{log_cell}</td>"
            "</tr>")
    return "\n".join(rows)


def table(headers, rows):
    cells = "".join(
        f'<th style="text-align:left;background:#f2f4f7;'
        f'border:1px solid #d0d5dd;padding:8px">{html.escape(header)}</th>'
        for header in headers)
    styled_rows = rows.replace(
        "<td>", '<td style="border:1px solid #d0d5dd;padding:8px">')
    styled_rows = re.sub(
        r'<td style="([^"]+)">',
        r'<td style="border:1px solid #d0d5dd;padding:8px;\1">',
        styled_rows)
    return (
        '<table style="border-collapse:collapse;width:100%;margin:12px 0">'
        f"<thead><tr>{cells}</tr></thead><tbody>{styled_rows}</tbody></table>")


def main():
    args = parse_args()
    args.results.mkdir(parents=True, exist_ok=True)
    generate_log_artifacts(args.results, args.debian_log)
    events = parse_stage_events(args.stage_events, args.results)
    stages_html, failed_stage = stage_rows(
        events, args.results, args.build_url)
    tests = load_tests(args.results)
    tests_html = test_rows(tests, args.results, args.build_url)

    started = parse_time(args.started_at)
    finished = parse_time(args.finished_at)
    report_url = artifact_url(
        args.build_url,
        args.results.relative_to(args.results.parent) / "report.html")
    result_style = status_style(args.result)
    source = html.escape(args.source)
    if args.source_url.startswith("https://github.com/"):
        source = (
            f'<a href="{html.escape(args.source_url)}">{source}</a>')
    title_row = (
        f"<tr><td><strong>Title</strong></td>"
        f"<td>{html.escape(args.title)}</td></tr>"
        if args.title else "")
    header = f"""
<h1 style="margin-bottom:4px">CloudStack ONTAP presubmit</h1>
<p style="font-size:18px;{result_style}">{html.escape(args.result)}</p>
<table style="border-collapse:collapse">
<tr><td><strong>Source</strong></td>
<td>{source}</td></tr>
{title_row}
<tr><td><strong>Commit</strong></td>
<td><code>{html.escape(args.source_sha)}</code></td></tr>
<tr><td><strong>VM</strong></td>
<td>{html.escape(args.vm or 'not assigned')}</td></tr>
<tr><td><strong>Started</strong></td>
<td>{html.escape(args.started_at)}</td></tr>
<tr><td><strong>Finished</strong></td>
<td>{html.escape(args.finished_at)}</td></tr>
<tr><td><strong>Total duration</strong></td>
<td>{html.escape(format_duration(started, finished))}</td></tr>
</table>
<p><a href="{html.escape(args.build_url)}">Jenkins build</a> ·
<a href="{html.escape(args.build_url.rstrip('/') + '/console')}">Console</a> ·
<a href="{html.escape(report_url)}">HTML report</a></p>
"""
    failure = (
        f'<p style="padding:12px;background:#fef3f2;'
        f'border-left:4px solid #d92d20">'
        f'<strong>Failed stage:</strong> {html.escape(failed_stage)}</p>'
        if failed_stage else "")
    stage_table = table(
        ["Stage", "Status", "Started", "Duration", "VM wait", "Details"],
        stages_html)
    test_section = (
        "<h2>ONTAP tests</h2>"
        "<p>Failures are listed first. Select a log link to jump to the "
        "test.</p>"
        + table(["Protocol", "Suite", "Test", "Status", "Details"], tests_html)
        if tests else
        "<h2>ONTAP tests</h2><p>No Marvin test summary was available.</p>")
    body = (
        header + failure + "<h2>Pipeline stages</h2>" +
        stage_table + test_section)
    document = html_document("CloudStack ONTAP presubmit report", body)
    (args.results / "report.html").write_text(document, encoding="utf-8")
    (args.results / "email-body.html").write_text(body, encoding="utf-8")
    (args.results / "failed-stage.txt").write_text(
        failed_stage, encoding="utf-8")


if __name__ == "__main__":
    main()
