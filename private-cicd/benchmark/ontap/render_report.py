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
"""
Turn benchmark_storage_pool_sequential.py / benchmark_storage_pool_concurrency.py's
raw_ops_<run_id>.csv / summary_<run_id>.csv into markdown tables that match the
layout of the storage-pool sections
(5.1.1, 5.1.2, 6.1.1, 6.1.2) and the Results Log (section 9) of the
"ONTAP Plugin - CloudStack Operations, Scale & Parallel Test Matrix"
Confluence page, ready to paste back in.

Usage:
    python3 render_report.py --run-id RUN-20260722-120000 \
        --cloudstack-build 4.23.0.0-SNAPSHOT --ontap-version 9.15.1
"""

import argparse
import csv
import math
import os
from collections import defaultdict

PROTOCOL_ORDER = ["nfs3", "iscsi"]
PROTOCOL_LABEL = {"nfs3": "NFS3", "iscsi": "iSCSI"}

# test_id -> (section title, sequential N-column label, category for section 9)
SECTION_DEFS = {
    "5.1.1": ("5.1.1 Sequential create (1 pool at a time, cumulative to N)", "N (pools)", "Storage Pool"),
    "5.1.2": ("5.1.2 Sequential delete (1 pool at a time, from N remaining)", "N (pools remaining)", "Storage Pool"),
    "6.1.1": ("6.1.1 Parallel pool creation", None, "Storage Pool"),
    "6.1.2": ("6.1.2 Parallel pool deletion", None, "Storage Pool"),
    "5.2.1": ("5.2.1 Sequential create (1 VM at a time, cumulative to N)", "N (VMs)", "VM Instance"),
    "5.2.2": ("5.2.2 Sequential delete (1 VM at a time, from N remaining)", "N (VMs remaining)", "VM Instance"),
    "6.2.1": ("6.2.1 Parallel VM creation", None, "VM Instance"),
    "6.2.2": ("6.2.2 Parallel VM deletion", None, "VM Instance"),
}


def percentile(sorted_values, pct):
    if not sorted_values:
        return None
    if len(sorted_values) == 1:
        return sorted_values[0]
    k = (len(sorted_values) - 1) * (pct / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_values[int(k)]
    d0 = sorted_values[f] * (c - k)
    d1 = sorted_values[c] * (k - f)
    return d0 + d1


def load_csv(path):
    if not os.path.exists(path):
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def stats_for(durations):
    if not durations:
        return None, None, None, None
    durations = sorted(durations)
    return (
        round(durations[0], 3),
        round(sum(durations) / len(durations), 3),
        round(percentile(durations, 95), 3),
        round(durations[-1], 3),
    )


def raw_durations(raw_rows, phase, protocol, predicate):
    return [
        float(r["duration_sec"]) for r in raw_rows
        if r["phase"] == phase and r["protocol"] == protocol
        and r["success"] == "True" and predicate(r)
    ]


def render_sequential_table(summary_rows, raw_rows, phase, test_id, n_label):
    checkpoints = sorted({int(r["checkpoint"]) for r in summary_rows if r["test_id"] == test_id})
    by_key = {(r["protocol"], int(r["checkpoint"])): r for r in summary_rows if r["test_id"] == test_id}

    lines = [f"| {n_label} | NFS3 Total (s) | NFS3 Avg/op (s) | iSCSI Total (s) | iSCSI Avg/op (s) | Notes |",
             "|---|---|---|---|---|---|"]
    for cp in checkpoints:
        cells = [str(cp)]
        notes = []
        for proto in PROTOCOL_ORDER:
            row = by_key.get((proto, cp))
            if row:
                cells.append(row["total_time_sec"])
                cells.append(row["avg_time_sec"])
                if row.get("notes") and row["notes"] not in notes:
                    notes.append(row["notes"])
            else:
                cells.append("")
                cells.append("")
        cells.append("; ".join(notes))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


def render_concurrency_table(summary_rows, test_id):
    rows = [r for r in summary_rows if r["test_id"] == test_id]
    rows.sort(key=lambda r: (int(r["checkpoint"]), PROTOCOL_ORDER.index(r["protocol"])
                              if r["protocol"] in PROTOCOL_ORDER else 99))
    lines = ["| Concurrency (C) | Protocol | Total Wall-clock (s) | Success | Failure | Avg Time/pool (s) | Notes |",
             "|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(
            f"| {r['checkpoint']} | {PROTOCOL_LABEL.get(r['protocol'], r['protocol'])} | "
            f"{r['total_time_sec']} | {r['success_count']} | {r['failure_count']} | "
            f"{r['avg_time_sec']} | {r.get('notes', '')} |"
        )
    return "\n".join(lines)


def build_results_log_rows(summary_rows, raw_rows, run_id, cs_build, ontap_version, run_date):
    header = ("| Run Date | Run ID | CloudStack Build | ONTAP Version | Category | Test ID / Section | "
               "Scale (N) / Concurrency (C) | Protocol | Min (s) | Avg (s) | P95 (s) | Max (s) | "
               "Success Rate | Notes |")
    sep = "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    lines = [header, sep]

    delete_total_created = {}
    for r in raw_rows:
        if r["phase"] == "sequential_delete":
            delete_total_created[r["protocol"]] = int(r["scale_or_concurrency"])

    for row in summary_rows:
        phase, test_id, protocol, checkpoint = row["phase"], row["test_id"], row["protocol"], int(row["checkpoint"])
        if phase == "sequential_create":
            durations = raw_durations(raw_rows, phase, protocol, lambda r: int(r["index"]) <= checkpoint)
            scale_label = f"N={checkpoint}"
        elif phase == "sequential_delete":
            total_created = delete_total_created.get(protocol, checkpoint)
            deleted_so_far = total_created - checkpoint
            durations = raw_durations(raw_rows, phase, protocol, lambda r: int(r["index"]) <= deleted_so_far)
            scale_label = f"N={checkpoint}"
        else:  # concurrent_create / concurrent_delete
            durations = raw_durations(raw_rows, phase, protocol, lambda r: int(r["scale_or_concurrency"]) == checkpoint)
            scale_label = f"C={checkpoint}"

        mn, avg, p95, mx = stats_for(durations)
        total_ops = int(row["success_count"]) + int(row["failure_count"])
        success_rate = f"{(int(row['success_count']) / total_ops * 100):.0f}%" if total_ops else "-"
        category = SECTION_DEFS.get(test_id, (None, None, "Storage Pool"))[2]
        lines.append(
            f"| {run_date} | {run_id} | {cs_build} | {ontap_version} | {category} | {test_id} | "
            f"{scale_label} | {PROTOCOL_LABEL.get(protocol, protocol)} | "
            f"{mn if mn is not None else '-'} | {avg if avg is not None else '-'} | "
            f"{p95 if p95 is not None else '-'} | {mx if mx is not None else '-'} | "
            f"{success_rate} | {row.get('notes', '')} |"
        )
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output-dir", default="results")
    parser.add_argument("--cloudstack-build", default="unknown")
    parser.add_argument("--ontap-version", default="unknown")
    parser.add_argument("--run-date", default=None, help="Defaults to today's date (YYYY-MM-DD)")
    parser.add_argument("--raw-prefix", default="raw_ops",
                         help="Filename prefix for the raw ops CSV, e.g. raw_ops_vm for the benchmark_vm_instance_* scripts")
    parser.add_argument("--summary-prefix", default="summary",
                         help="Filename prefix for the summary CSV, e.g. summary_vm for the benchmark_vm_instance_* scripts")
    parser.add_argument("--report-suffix", default="",
                         help="Optional suffix to disambiguate the output report filename, e.g. _vm")
    args = parser.parse_args()

    import datetime
    run_date = args.run_date or datetime.date.today().isoformat()

    summary_path = os.path.join(args.output_dir, f"{args.summary_prefix}_{args.run_id}.csv")
    raw_path = os.path.join(args.output_dir, f"{args.raw_prefix}_{args.run_id}.csv")
    summary_rows = load_csv(summary_path)
    raw_rows = load_csv(raw_path)
    if not summary_rows:
        raise SystemExit(f"No summary rows found at {summary_path}")

    # Render whichever sections actually have data in this run, in a stable order.
    present_test_ids = sorted({r["test_id"] for r in summary_rows if r["test_id"] in SECTION_DEFS})
    sections = []
    for test_id in present_test_ids:
        title, n_label, _category = SECTION_DEFS[test_id]
        phase = {
            "5.1.1": "sequential_create", "5.1.2": "sequential_delete",
            "5.2.1": "sequential_create", "5.2.2": "sequential_delete",
        }.get(test_id)
        if n_label is not None:
            sections.append(f"### {title}\n\n" +
                             render_sequential_table(summary_rows, raw_rows, phase, test_id, n_label))
        else:
            sections.append(f"### {title}\n\n" + render_concurrency_table(summary_rows, test_id))
    sections.append("### 9. Results Log rows for this run\n\n" +
                     build_results_log_rows(summary_rows, raw_rows, args.run_id, args.cloudstack_build,
                                             args.ontap_version, run_date))

    report = f"# Benchmark report - {args.run_id}\n\n" + "\n\n".join(sections) + "\n"
    print(report)

    report_path = os.path.join(args.output_dir, f"report{args.report_suffix}_{args.run_id}.md")
    with open(report_path, "w") as f:
        f.write(report)
    print(f"\nSaved to {report_path}")


if __name__ == "__main__":
    main()
