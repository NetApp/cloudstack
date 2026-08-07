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
Concurrency storage-pool benchmark for the NetApp ONTAP CloudStack plugin.

Drives createStoragePool / deleteStoragePool in parallel (ThreadPoolExecutor)
over the CloudStack HTTP/REST API and reproduces Section 6.1 - Parallel/
Concurrency Matrix (C = 2,5,10,20,30 workers) from the
"ONTAP Plugin - CloudStack Operations, Scale & Parallel Test Matrix" page:

  6.1.1 Parallel pool creation
  6.1.2 Parallel pool deletion

See benchmark_storage_pool_sequential.py for the sequential scale matrix
(kept as a separate script/entry point on purpose - concurrency runs are the
ones most likely to hit mgmt-server/plugin job-queue saturation, so they're
easy to run, review, and re-run independently of the sequential matrix).

Every individual API call is logged to results/raw_ops_<run_id>.csv, and a
per-checkpoint roll-up (matching the columns of the Confluence tables) is
appended to results/summary_<run_id>.csv. Use render_report.py afterwards to
turn the summary CSV into paste-ready markdown for the Confluence page. Using
the same --run-id here and in benchmark_storage_pool_sequential.py combines
both into a single summary_<run_id>.csv / report.

Usage:
    python3 benchmark_storage_pool_concurrency.py --config config.yaml --protocol both
    python3 benchmark_storage_pool_concurrency.py --config config.yaml --levels 2,5,10
    python3 benchmark_storage_pool_concurrency.py --config config.yaml --dry-run
    python3 benchmark_storage_pool_concurrency.py --config config.yaml --cleanup-only
"""

import argparse
import os
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

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


def run_concurrency(client, cfg, protocol_key, run_id, raw_logger, summary_rows, dry_run, delay, levels):
    infra = cfg["infrastructure"]
    ontap_cfg = cfg["ontap"][protocol_key]
    prefix = cfg["benchmark"]["pool_name_prefix"]

    for level in levels:
        print(f"\n=== [6.1.1] Parallel CREATE - protocol={protocol_key} C={level} ===")
        names = [f"{prefix}_c{level}_{protocol_key}_{run_id}_{i:03d}" for i in range(1, level + 1)]

        t0 = time.perf_counter()
        results = []
        if dry_run:
            results = [fake_create(n) for n in names]
        else:
            with ThreadPoolExecutor(max_workers=level) as ex:
                futures = {ex.submit(create_pool, client, n, infra, ontap_cfg): n for n in names}
                for fut in as_completed(futures):
                    results.append(fut.result())
        wall = time.perf_counter() - t0

        for idx, r in enumerate(results, start=1):
            raw_logger.log(run_id, "concurrent_create", "6.1.1", protocol_key, level, idx, r)

        succ = [r for r in results if r.success]
        fail = [r for r in results if not r.success]
        avg = statistics.mean([r.duration_sec for r in succ]) if succ else 0
        notes = "Watch mgmt-server job-queue/thread-pool saturation as a confound" if level >= 30 else ""
        summary_rows.append({
            "run_id": run_id, "phase": "concurrent_create", "test_id": "6.1.1",
            "protocol": protocol_key, "checkpoint": level,
            "total_time_sec": round(wall, 3), "avg_time_sec": round(avg, 3),
            "success_count": len(succ), "failure_count": len(fail), "notes": notes,
        })
        print(f"  wall-clock={wall:.3f}s success={len(succ)} failure={len(fail)} avg/op={avg:.3f}s")
        for r in fail:
            print(f"    !! {r.pool_name}: {r.error}")

        print(f"\n=== [6.1.2] Parallel DELETE - protocol={protocol_key} C={len(succ)} ===")
        t0 = time.perf_counter()
        del_results = []
        if dry_run:
            del_results = [fake_delete(r.pool_id, r.pool_name) for r in succ]
        elif succ:
            with ThreadPoolExecutor(max_workers=len(succ)) as ex:
                futures = {ex.submit(delete_pool, client, r.pool_id, r.pool_name): r for r in succ}
                for fut in as_completed(futures):
                    del_results.append(fut.result())
        wall_del = time.perf_counter() - t0

        for idx, r in enumerate(del_results, start=1):
            raw_logger.log(run_id, "concurrent_delete", "6.1.2", protocol_key, level, idx, r)

        succ_d = [r for r in del_results if r.success]
        fail_d = [r for r in del_results if not r.success]
        avg_d = statistics.mean([r.duration_sec for r in succ_d]) if succ_d else 0
        summary_rows.append({
            "run_id": run_id, "phase": "concurrent_delete", "test_id": "6.1.2",
            "protocol": protocol_key, "checkpoint": level,
            "total_time_sec": round(wall_del, 3), "avg_time_sec": round(avg_d, 3),
            "success_count": len(succ_d), "failure_count": len(fail_d), "notes": "",
        })
        print(f"  wall-clock={wall_del:.3f}s success={len(succ_d)} failure={len(fail_d)} avg/op={avg_d:.3f}s")
        for r in fail_d:
            print(f"    !! {r.pool_name}: {r.error}")
        if delay:
            time.sleep(delay)


def parse_levels(raw, default_levels):
    if not raw:
        return sorted(default_levels)
    return sorted(int(x.strip()) for x in raw.split(",") if x.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default="config.yaml", help="Path to config YAML (default: config.yaml)")
    parser.add_argument("--protocol", default="both", help="nfs3 | iscsi | both (default: both)")
    parser.add_argument("--run-id", default=None, help="Override auto-generated run id")
    parser.add_argument(
        "--levels", default=None,
        help="Comma-separated concurrency levels to run, e.g. '2,5,10' "
             "(default: config.benchmark.concurrency_levels, typically up to 30)",
    )
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
    levels = parse_levels(args.levels, cfg["benchmark"]["concurrency_levels"])

    print(f"Run ID: {run_id}")
    print(f"Protocols: {protocols}")
    print("Mode: concurrency")
    print(f"Concurrency levels: {levels}")
    print(f"Dry run: {args.dry_run}")

    client = None if args.dry_run else make_cloudstack_client(cfg)

    raw_logger = RawLogger(os.path.join(output_dir, f"raw_ops_{run_id}.csv"))
    summary_rows = []

    try:
        for protocol_key in protocols:
            run_concurrency(client, cfg, protocol_key, run_id, raw_logger, summary_rows, args.dry_run, delay, levels)
    finally:
        raw_logger.close()

    summary_path = os.path.join(output_dir, f"summary_{run_id}.csv")
    append_summary_csv(summary_path, summary_rows)

    print(f"\nRaw per-operation log: {os.path.join(output_dir, f'raw_ops_{run_id}.csv')}")
    print(f"Checkpoint summary:    {summary_path}")
    print("Next: python3 render_report.py --run-id " + run_id + f" --output-dir {output_dir}")

    if not args.dry_run and not args.skip_cleanup:
        print(f"\nVerifying no orphaned pools remain for run {run_id}...")
        cleanup_by_filter(client, run_id)


if __name__ == "__main__":
    main()
