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
HTTP/REST API and reproduces Section 5.1 - Sequential Scale Matrix
(N = 1,5,10,20,30):

  5.1.1 Sequential create (1 pool at a time, cumulative to N)
  5.1.2 Sequential delete (1 pool at a time, from N remaining)

See benchmark_storage_pool_concurrency.py for the parallel/concurrency matrix
(kept as a separate script/entry point on purpose, so a sequential run can be
kicked off and reviewed independently before committing to a concurrency run).

Every individual API call is logged to results/raw_ops_<run_id>.csv, and a
per-checkpoint roll-up is appended to results/summary_<run_id>.csv. Use
render_report.py afterwards to turn the summary CSV into paste-ready markdown.
Using the same --run-id here and in benchmark_storage_pool_concurrency.py
combines both into a single summary_<run_id>.csv / report.

Protocols and checkpoints come from config.yaml. Prefer run.py.

Usage:
    python3 run.py --test storage-pool-sequential --config config.yaml
    python3 benchmark_storage_pool_sequential.py --config config.yaml --dry-run
    python3 benchmark_storage_pool_sequential.py --config config.yaml --cleanup-only
"""

import argparse
import os
import time

from benchmark_support import (
    assert_pool_name_prefix,
    configure_logging,
    format_pool_name,
    get_logger,
    mean_seconds,
    normalize_run_id,
)
from storage_pool_common import (
    append_summary_csv,
    cleanup_by_filter,
    create_pool,
    delete_pool,
    fake_create,
    fake_delete,
    load_config,
    make_cloudstack_client,
    RawLogger,
    resolve_protocols,
)

log = get_logger()


def run_sequential(client, cfg, protocol_key, run_id, raw_logger, summary_rows, dry_run, delay):
    infra = cfg["infrastructure"]
    ontap_cfg = cfg["ontap"][protocol_key]
    checkpoints = sorted(cfg["benchmark"]["sequential_checkpoints"])
    max_n = max(checkpoints)
    prefix = cfg["benchmark"]["pool_name_prefix"]

    created = []
    create_durations = []
    log.info("[5.1.1] Sequential CREATE protocol=%s up to N=%s", protocol_key, max_n)
    for i in range(1, max_n + 1):
        name = format_pool_name(prefix, protocol_key, run_id, i)
        result = fake_create(name) if dry_run else create_pool(client, name, infra, ontap_cfg)
        raw_logger.log(run_id, "sequential_create", "5.1.1", protocol_key, max_n, i, result)
        status = "OK" if result.success else "FAIL"
        log.info("[%s/%s] create %s -> %s (%.3fs)", i, max_n, name, status, result.duration_sec)
        if result.success:
            created.append((name, result.pool_id))
            create_durations.append(result.duration_sec)
        else:
            log.error("%s", result.error)
        if i in checkpoints:
            total = sum(create_durations)
            avg = mean_seconds(create_durations)
            summary_rows.append({
                "run_id": run_id, "phase": "sequential_create", "test_id": "5.1.1",
                "protocol": protocol_key, "checkpoint": i,
                "total_time_sec": round(total, 3), "avg_time_sec": round(avg, 3),
                "success_count": len(created), "failure_count": i - len(created), "notes": "",
            })
            log.info(
                "checkpoint N=%s protocol=%s total=%.3fs avg=%.3fs/op success=%s failure=%s",
                i, protocol_key, total, avg, len(created), i - len(created),
            )
        if delay:
            time.sleep(delay)

    total_created = len(created)
    log.info("[5.1.2] Sequential DELETE protocol=%s from N=%s remaining", protocol_key, total_created)
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
        log.info(
            "[%s/%s] delete %s -> %s (%.3fs) remaining=%s",
            idx, total_created, name, status, result.duration_sec, remaining,
        )
        if result.success:
            delete_durations.append(result.duration_sec)
            deleted_ok += 1
        else:
            log.error("%s", result.error)
        if remaining in checkpoints:
            total = sum(delete_durations)
            avg = mean_seconds(delete_durations)
            summary_rows.append({
                "run_id": run_id, "phase": "sequential_delete", "test_id": "5.1.2",
                "protocol": protocol_key, "checkpoint": remaining,
                "total_time_sec": round(total, 3), "avg_time_sec": round(avg, 3),
                "success_count": deleted_ok, "failure_count": idx - deleted_ok, "notes": "",
            })
            log.info(
                "checkpoint remaining=%s protocol=%s total=%.3fs avg=%.3fs/op success=%s failure=%s",
                remaining, protocol_key, total, avg, deleted_ok, idx - deleted_ok,
            )
        if delay:
            time.sleep(delay)


def execute(cfg, dry_run=False, skip_cleanup=False, cleanup_only=None, run_id=None):
    configure_logging()
    assert_pool_name_prefix(cfg["benchmark"]["pool_name_prefix"])
    output_dir = cfg["benchmark"].get("output_dir", "results")
    os.makedirs(output_dir, exist_ok=True)

    if cleanup_only is not None:
        client = make_cloudstack_client(cfg)
        name_filter = cfg["benchmark"]["pool_name_prefix"] if cleanup_only == "__PREFIX__" else cleanup_only
        cleanup_by_filter(client, name_filter, cfg)
        return

    run_id = normalize_run_id(run_id)
    protocols = resolve_protocols(cfg, "both")
    delay = cfg["benchmark"].get("inter_op_delay_sec", 0)

    log.info("Run ID: %s", run_id)
    log.info("Protocols: %s", protocols)
    log.info("Mode: sequential")
    log.info("Dry run: %s", dry_run)

    client = None if dry_run else make_cloudstack_client(cfg)

    raw_logger = RawLogger(os.path.join(output_dir, f"raw_ops_{run_id}.csv"))
    summary_rows = []

    try:
        for protocol_key in protocols:
            run_sequential(client, cfg, protocol_key, run_id, raw_logger, summary_rows, dry_run, delay)
    finally:
        raw_logger.close()

    summary_path = os.path.join(output_dir, f"summary_{run_id}.csv")
    append_summary_csv(summary_path, summary_rows)

    log.info("Raw per-operation log: %s", os.path.join(output_dir, f"raw_ops_{run_id}.csv"))
    log.info("Checkpoint summary: %s", summary_path)
    log.info("Next: python3 render_report.py --run-id %s --output-dir %s", run_id, output_dir)

    if not dry_run and not skip_cleanup:
        log.info("Verifying no orphaned pools remain for run %s", run_id)
        cleanup_by_filter(client, run_id, cfg)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default="config.yaml", help="Path to config YAML (default: config.yaml)")
    parser.add_argument("--run-id", default=None, help="Override auto-generated run id")
    parser.add_argument("--dry-run", action="store_true", help="Simulate timings, no real API calls")
    parser.add_argument("--skip-cleanup", action="store_true", help="Leave any leftover pools from this run in place")
    parser.add_argument(
        "--cleanup-only", nargs="?", const="__PREFIX__", default=None, metavar="FILTER",
        help="Delete benchmark pools whose name contains FILTER (default: config pool_name_prefix) and exit",
    )
    args = parser.parse_args()
    execute(
        load_config(args.config),
        dry_run=args.dry_run,
        skip_cleanup=args.skip_cleanup,
        cleanup_only=args.cleanup_only,
        run_id=args.run_id,
    )


if __name__ == "__main__":
    main()
