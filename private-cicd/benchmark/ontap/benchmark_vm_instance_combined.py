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
Combined (sequential + concurrency) VM-instance benchmark for the NetApp
ONTAP CloudStack plugin.

Convenience wrapper that runs benchmark_vm_instance_sequential.py's Section
5.2 scale matrix immediately followed by benchmark_vm_instance_concurrency.py's
Section 6.2 parallel matrix, for each protocol, under a single shared
--run-id - equivalent to running both scripts back to back, but in one
invocation and one combined results/summary_vm_<run_id>.csv.

Prefer the two standalone scripts (benchmark_vm_instance_sequential.py /
benchmark_vm_instance_concurrency.py) when you want to review the sequential
results before deciding whether to proceed with a concurrency run - this
combined script is for when you already trust the config/environment and just
want the full 5.2.x + 6.2.x matrix in one go.

Usage:
    python3 benchmark_vm_instance_combined.py --config config.yaml --protocol both
    python3 benchmark_vm_instance_combined.py --config config.yaml --dry-run
    python3 benchmark_vm_instance_combined.py --config config.yaml --cleanup-only
"""

import argparse
import os

from benchmark_vm_instance_concurrency import parse_levels, run_concurrency
from benchmark_vm_instance_sequential import run_sequential
from vm_instance_common import (
    append_summary_csv,
    cleanup_by_filter,
    load_config,
    make_cloudstack_client,
    new_run_id,
    RawLogger,
    resolve_protocols,
)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default="config.yaml", help="Path to config YAML (default: config.yaml)")
    parser.add_argument("--protocol", default="both", help="nfs3 | iscsi | both (default: both)")
    parser.add_argument("--run-id", default=None, help="Override auto-generated run id")
    parser.add_argument(
        "--levels", default=None,
        help="Comma-separated concurrency levels to run, e.g. '2,5,10' "
             "(default: config.vm_bench.concurrency_levels, typically up to 30)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Simulate timings, no real API calls")
    parser.add_argument("--skip-cleanup", action="store_true", help="Leave any leftover VMs from this run in place")
    parser.add_argument(
        "--cleanup-only", nargs="?", const="__PREFIX__", default=None, metavar="FILTER",
        help="Destroy all VMs whose name contains FILTER (default: config vm_name_prefix) and exit",
    )
    args = parser.parse_args()

    cfg = load_config(args.config)
    os.makedirs(cfg["vm_bench"].get("output_dir", "results"), exist_ok=True)

    if args.cleanup_only is not None:
        client = make_cloudstack_client(cfg)
        name_filter = args.cleanup_only
        if name_filter == "__PREFIX__":
            name_filter = cfg["vm_bench"]["vm_name_prefix"]
        cleanup_by_filter(client, name_filter)
        return

    run_id = args.run_id or new_run_id()
    protocols = resolve_protocols(cfg, args.protocol)
    delay = cfg["vm_bench"].get("inter_op_delay_sec", 0)
    output_dir = cfg["vm_bench"].get("output_dir", "results")
    levels = parse_levels(args.levels, cfg["vm_bench"]["concurrency_levels"])

    print(f"Run ID: {run_id}")
    print(f"Protocols: {protocols}")
    print("Mode: sequential + concurrency (combined)")
    print(f"Concurrency levels: {levels}")
    print(f"Dry run: {args.dry_run}")

    client = None if args.dry_run else make_cloudstack_client(cfg)

    raw_logger = RawLogger(os.path.join(output_dir, f"raw_ops_vm_{run_id}.csv"))
    summary_rows = []

    try:
        for protocol_key in protocols:
            run_sequential(client, cfg, protocol_key, run_id, raw_logger, summary_rows, args.dry_run, delay)
            run_concurrency(client, cfg, protocol_key, run_id, raw_logger, summary_rows, args.dry_run, delay, levels)
    finally:
        raw_logger.close()

    summary_path = os.path.join(output_dir, f"summary_vm_{run_id}.csv")
    append_summary_csv(summary_path, summary_rows)

    print(f"\nRaw per-operation log: {os.path.join(output_dir, f'raw_ops_vm_{run_id}.csv')}")
    print(f"Checkpoint summary:    {summary_path}")
    print("Next: python3 render_report.py --run-id " + run_id +
          f" --output-dir {output_dir} --raw-prefix raw_ops_vm --summary-prefix summary_vm --report-suffix _vm")

    if not args.dry_run and not args.skip_cleanup:
        print(f"\nVerifying no orphaned VMs remain for run {run_id}...")
        cleanup_by_filter(client, run_id)


if __name__ == "__main__":
    main()
