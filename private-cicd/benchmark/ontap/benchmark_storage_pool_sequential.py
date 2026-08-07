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
Sequential storage-pool benchmark for the NetApp ONTAP CloudStack plugin.

Drives createStoragePool / deleteStoragePool one at a time over the CloudStack
HTTP/REST API (see https://netapp.atlassian.net/wiki/spaces/OSSG/pages/608854350)
and reproduces Section 5.1 - Sequential Scale Matrix (N = 1,5,10,20,30) from the
"ONTAP Plugin - CloudStack Operations, Scale & Parallel Test Matrix" page:

  5.1.1 Sequential create (1 pool at a time, cumulative to N)
  5.1.2 Sequential delete (1 pool at a time, from N remaining)

See benchmark_storage_pool_concurrency.py for the parallel/concurrency matrix
(kept as a separate script/entry point on purpose, so a sequential run can be
kicked off and reviewed independently before committing to a concurrency run).

Every individual API call is logged to results/raw_ops_<run_id>.csv, and a
per-checkpoint roll-up (matching the columns of the Confluence tables) is
appended to results/summary_<run_id>.csv. Use render_report.py afterwards to
turn the summary CSV into paste-ready markdown for the Confluence page. Using
the same --run-id here and in benchmark_storage_pool_concurrency.py combines
both into a single summary_<run_id>.csv / report.

Usage:
    python3 benchmark_storage_pool_sequential.py --config config.yaml --protocol both
    python3 benchmark_storage_pool_sequential.py --config config.yaml --dry-run
    python3 benchmark_storage_pool_sequential.py --config config.yaml --cleanup-only
"""

import argparse
import os
import time

from storage_pool_common import (
    append_summary_csv,
    cleanup_by_filter,
    create_pool,
    delete_pool,
    fake_create,
    fake_delete,
    load_config,
    make_cloudstack_client,
    new_run_id,
    RawLogger,
    resolve_protocols,
)


def run_sequential(client, cfg, protocol_key, run_id, raw_logger, summary_rows, dry_run, delay):
    infra = cfg["infrastructure"]
    ontap_cfg = cfg["ontap"][protocol_key]
    checkpoints = sorted(cfg["benchmark"]["sequential_checkpoints"])
    max_n = max(checkpoints)
    prefix = cfg["benchmark"]["pool_name_prefix"]

    created = []
    create_durations = []
    print(f"\n=== [5.1.1] Sequential CREATE - protocol={protocol_key} up to N={max_n} ===")
    for i in range(1, max_n + 1):
        # ONTAP volume names only allow alphanumeric + underscore (no hyphens).
        name = f"{prefix}_seq_{protocol_key}_{run_id}_{i:03d}"
        result = fake_create(name) if dry_run else create_pool(client, name, infra, ontap_cfg)
        raw_logger.log(run_id, "sequential_create", "5.1.1", protocol_key, max_n, i, result)
        status = "OK" if result.success else "FAIL"
        print(f"  [{i:>3}/{max_n}] create {name} -> {status} ({result.duration_sec:.3f}s)")
        if result.success:
            created.append((name, result.pool_id))
            create_durations.append(result.duration_sec)
        else:
            print(f"    !! {result.error}")
        if i in checkpoints:
            total = sum(create_durations)
            avg = total / len(create_durations) if create_durations else 0
            summary_rows.append({
                "run_id": run_id, "phase": "sequential_create", "test_id": "5.1.1",
                "protocol": protocol_key, "checkpoint": i,
                "total_time_sec": round(total, 3), "avg_time_sec": round(avg, 3),
                "success_count": len(created), "failure_count": i - len(created), "notes": "",
            })
            print(f"    >> checkpoint N={i}: total={total:.3f}s avg={avg:.3f}s/op "
                  f"success={len(created)} failure={i - len(created)}")
        if delay:
            time.sleep(delay)

    total_created = len(created)
    print(f"\n=== [5.1.2] Sequential DELETE - protocol={protocol_key} from N={total_created} remaining ===")
    if total_created in checkpoints:
        summary_rows.append({
            "run_id": run_id, "phase": "sequential_delete", "test_id": "5.1.2",
            "protocol": protocol_key, "checkpoint": total_created,
            "total_time_sec": 0, "avg_time_sec": 0, "success_count": 0, "failure_count": 0,
            "notes": "baseline - no deletes issued yet",
        })
    delete_durations = []
    deleted_ok = 0
    for idx, (name, pool_id) in enumerate(created, start=1):
        result = fake_delete(pool_id, name) if dry_run else delete_pool(client, pool_id, name)
        remaining = total_created - idx
        raw_logger.log(run_id, "sequential_delete", "5.1.2", protocol_key, total_created, idx, result)
        status = "OK" if result.success else "FAIL"
        print(f"  [{idx:>3}/{total_created}] delete {name} -> {status} "
              f"({result.duration_sec:.3f}s) remaining={remaining}")
        if result.success:
            delete_durations.append(result.duration_sec)
            deleted_ok += 1
        else:
            print(f"    !! {result.error}")
        if remaining in checkpoints:
            total = sum(delete_durations)
            avg = total / len(delete_durations) if delete_durations else 0
            summary_rows.append({
                "run_id": run_id, "phase": "sequential_delete", "test_id": "5.1.2",
                "protocol": protocol_key, "checkpoint": remaining,
                "total_time_sec": round(total, 3), "avg_time_sec": round(avg, 3),
                "success_count": deleted_ok, "failure_count": idx - deleted_ok, "notes": "",
            })
            print(f"    >> checkpoint remaining={remaining}: total={total:.3f}s avg={avg:.3f}s/op "
                  f"success={deleted_ok} failure={idx - deleted_ok}")
        if delay:
            time.sleep(delay)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default="config.yaml", help="Path to config YAML (default: config.yaml)")
    parser.add_argument("--protocol", default="both", help="nfs3 | iscsi | both (default: both)")
    parser.add_argument("--run-id", default=None, help="Override auto-generated run id")
    parser.add_argument("--dry-run", action="store_true", help="Simulate timings, no real API calls")
    parser.add_argument("--skip-cleanup", action="store_true", help="Leave any leftover pools from this run in place")
    parser.add_argument(
        "--cleanup-only", nargs="?", const="__PREFIX__", default=None, metavar="FILTER",
        help="Delete all storage pools whose name contains FILTER (default: config pool_name_prefix) and exit",
    )
    args = parser.parse_args()

    cfg = load_config(args.config)
    os.makedirs(cfg["benchmark"].get("output_dir", "results"), exist_ok=True)

    if args.cleanup_only is not None:
        client = make_cloudstack_client(cfg)
        name_filter = args.cleanup_only
        if name_filter == "__PREFIX__":
            name_filter = cfg["benchmark"]["pool_name_prefix"]
        cleanup_by_filter(client, name_filter)
        return

    run_id = args.run_id or new_run_id()
    protocols = resolve_protocols(cfg, args.protocol)
    delay = cfg["benchmark"].get("inter_op_delay_sec", 0)
    output_dir = cfg["benchmark"].get("output_dir", "results")

    print(f"Run ID: {run_id}")
    print(f"Protocols: {protocols}")
    print("Mode: sequential")
    print(f"Dry run: {args.dry_run}")

    client = None if args.dry_run else make_cloudstack_client(cfg)

    raw_logger = RawLogger(os.path.join(output_dir, f"raw_ops_{run_id}.csv"))
    summary_rows = []

    try:
        for protocol_key in protocols:
            run_sequential(client, cfg, protocol_key, run_id, raw_logger, summary_rows, args.dry_run, delay)
    finally:
        raw_logger.close()

    summary_path = os.path.join(output_dir, f"summary_{run_id}.csv")
    append_summary_csv(summary_path, summary_rows)

    print(f"\nRaw per-operation log: {os.path.join(output_dir, f'raw_ops_{run_id}.csv')}")
    print(f"Checkpoint summary:    {summary_path}")
    print("Next: python3 render_report.py --run-id " + run_id + f" --output-dir {output_dir}")
    print(f"      (or run benchmark_storage_pool_concurrency.py --run-id {run_id} first to add 6.1.x to the same report)")

    if not args.dry_run and not args.skip_cleanup:
        print(f"\nVerifying no orphaned pools remain for run {run_id}...")
        cleanup_by_filter(client, run_id)


if __name__ == "__main__":
    main()
