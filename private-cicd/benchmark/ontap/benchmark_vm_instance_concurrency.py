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
Concurrency VM-instance benchmark for the NetApp ONTAP CloudStack plugin.

Drives deployVirtualMachine / destroyVirtualMachine in parallel
(ThreadPoolExecutor) over the CloudStack HTTP/REST API against pre-
provisioned bench_vm_nfs3 / bench_vm_iscsi storage pools (see README.md
"VM instance benchmark prerequisites") and reproduces Section 6.2 - Parallel/
Concurrency Matrix:

  6.2.1 Parallel VM creation
  6.2.2 Parallel VM deletion

Each deployed VM gets a root disk (from the service offering) AND one data
disk (from the disk offering) landing on the SAME tagged storage pool.

See benchmark_vm_instance_sequential.py for the sequential scale matrix, or
benchmark_vm_instance_combined.py to run both in one invocation (kept as
separate scripts/entry points on purpose - concurrency runs are the ones most
likely to hit mgmt-server/plugin job-queue saturation or pool capacity limits,
so they're easy to run, review, and re-run independently of the sequential
matrix).

Every individual API call is logged to results/raw_ops_vm_<run_id>.csv, and a
per-checkpoint roll-up is appended to results/summary_vm_<run_id>.csv. Use
render_report.py afterwards to turn the summary CSV into paste-ready markdown.
Using the same --run-id here and in benchmark_vm_instance_sequential.py
combines both into a single summary_vm_<run_id>.csv / report.

Protocols and concurrency levels come from config.yaml. Prefer run.py.

Usage:
    python3 run.py --test vm-instance-concurrency --config config.yaml
    python3 benchmark_vm_instance_concurrency.py --config config.yaml --dry-run
    python3 benchmark_vm_instance_concurrency.py --config config.yaml --cleanup-only
"""

import argparse
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from benchmark_support import (
    assert_vm_name_prefix,
    configure_logging,
    format_vm_name,
    get_logger,
    mean_seconds,
    normalize_run_id,
)
from vm_instance_common import (
    append_summary_csv,
    cleanup_by_filter,
    deploy_vm,
    destroy_vm,
    fake_create,
    fake_delete,
    load_config,
    make_cloudstack_client,
    RawLogger,
    resolve_protocols,
)

log = get_logger()


def run_concurrency(client, cfg, protocol_key, run_id, raw_logger, summary_rows, dry_run, delay, levels):
    infra = cfg["infrastructure"]
    vm_cfg = cfg["vm_bench"]
    proto_cfg = vm_cfg["protocols"][protocol_key]
    prefix = vm_cfg["vm_name_prefix"]

    for level in levels:
        log.info("[6.2.1] Parallel CREATE protocol=%s C=%s", protocol_key, level)
        names = [format_vm_name(prefix, protocol_key, run_id, i, concurrency_level=level)
                 for i in range(1, level + 1)]

        t0 = time.perf_counter()
        results = []
        if dry_run:
            results = [fake_create(n) for n in names]
        else:
            with ThreadPoolExecutor(max_workers=level) as ex:
                futures = {ex.submit(deploy_vm, client, n, infra, vm_cfg, proto_cfg): n for n in names}
                for fut in as_completed(futures):
                    results.append(fut.result())
        wall = time.perf_counter() - t0

        for idx, r in enumerate(results, start=1):
            raw_logger.log(run_id, "concurrent_create", "6.2.1", protocol_key, level, idx, r)

        succ = [r for r in results if r.success]
        fail = [r for r in results if not r.success]
        avg = mean_seconds([r.duration_sec for r in succ])
        notes = "Watch mgmt-server job-queue/thread-pool + single-host KVM saturation as a confound" if level >= 20 else ""
        summary_rows.append({
            "run_id": run_id, "phase": "concurrent_create", "test_id": "6.2.1",
            "protocol": protocol_key, "checkpoint": level,
            "total_time_sec": round(wall, 3), "avg_time_sec": round(avg, 3),
            "success_count": len(succ), "failure_count": len(fail), "notes": notes,
        })
        log.info(
            "checkpoint C=%s protocol=%s total=%.3fs avg=%.3fs/op success=%s failure=%s",
            level, protocol_key, wall, avg, len(succ), len(fail),
        )
        for r in fail:
            log.error("%s: %s", r.vm_name, r.error)

        log.info("[6.2.2] Parallel DELETE protocol=%s C=%s", protocol_key, len(succ))
        t0 = time.perf_counter()
        del_results = []
        if dry_run:
            del_results = [fake_delete(r.vm_id, r.vm_name) for r in succ]
        elif succ:
            with ThreadPoolExecutor(max_workers=len(succ)) as ex:
                futures = {ex.submit(destroy_vm, client, r.vm_id, r.vm_name): r for r in succ}
                for fut in as_completed(futures):
                    del_results.append(fut.result())
        wall_del = time.perf_counter() - t0

        for idx, r in enumerate(del_results, start=1):
            raw_logger.log(run_id, "concurrent_delete", "6.2.2", protocol_key, level, idx, r)

        succ_d = [r for r in del_results if r.success]
        fail_d = [r for r in del_results if not r.success]
        avg_d = mean_seconds([r.duration_sec for r in succ_d])
        summary_rows.append({
            "run_id": run_id, "phase": "concurrent_delete", "test_id": "6.2.2",
            "protocol": protocol_key, "checkpoint": level,
            "total_time_sec": round(wall_del, 3), "avg_time_sec": round(avg_d, 3),
            "success_count": len(succ_d), "failure_count": len(fail_d), "notes": "",
        })
        log.info(
            "checkpoint C=%s protocol=%s total=%.3fs avg=%.3fs/op success=%s failure=%s",
            level, protocol_key, wall_del, avg_d, len(succ_d), len(fail_d),
        )
        for r in fail_d:
            log.error("%s: %s", r.vm_name, r.error)
        if delay:
            time.sleep(delay)


def execute(cfg, dry_run=False, skip_cleanup=False, cleanup_only=None, run_id=None):
    configure_logging()
    assert_vm_name_prefix(cfg["vm_bench"]["vm_name_prefix"])
    output_dir = cfg["vm_bench"].get("output_dir", "results")
    os.makedirs(output_dir, exist_ok=True)

    if cleanup_only is not None:
        client = make_cloudstack_client(cfg)
        name_filter = cfg["vm_bench"]["vm_name_prefix"] if cleanup_only == "__PREFIX__" else cleanup_only
        cleanup_by_filter(client, name_filter, cfg)
        return

    run_id = normalize_run_id(run_id)
    protocols = resolve_protocols(cfg, "both")
    delay = cfg["vm_bench"].get("inter_op_delay_sec", 0)
    levels = sorted(cfg["vm_bench"]["concurrency_levels"])

    log.info("Run ID: %s", run_id)
    log.info("Protocols: %s", protocols)
    log.info("Mode: concurrency")
    log.info("Concurrency levels: %s", levels)
    log.info("Dry run: %s", dry_run)

    client = None if dry_run else make_cloudstack_client(cfg)

    raw_logger = RawLogger(os.path.join(output_dir, f"raw_ops_vm_{run_id}.csv"))
    summary_rows = []

    try:
        for protocol_key in protocols:
            run_concurrency(client, cfg, protocol_key, run_id, raw_logger, summary_rows, dry_run, delay, levels)
    finally:
        raw_logger.close()

    summary_path = os.path.join(output_dir, f"summary_vm_{run_id}.csv")
    append_summary_csv(summary_path, summary_rows)

    log.info("Raw per-operation log: %s", os.path.join(output_dir, f"raw_ops_vm_{run_id}.csv"))
    log.info("Checkpoint summary: %s", summary_path)
    log.info(
        "Next: python3 render_report.py --run-id %s --output-dir %s --raw-prefix raw_ops_vm --summary-prefix summary_vm --report-suffix _vm",
        run_id, output_dir,
    )

    if not dry_run and not skip_cleanup:
        log.info("Verifying no orphaned VMs remain for run %s", run_id)
        cleanup_by_filter(client, run_id, cfg)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default="config.yaml", help="Path to config YAML (default: config.yaml)")
    parser.add_argument("--run-id", default=None, help="Override auto-generated run id")
    parser.add_argument("--dry-run", action="store_true", help="Simulate timings, no real API calls")
    parser.add_argument("--skip-cleanup", action="store_true", help="Leave any leftover VMs from this run in place")
    parser.add_argument(
        "--cleanup-only", nargs="?", const="__PREFIX__", default=None, metavar="FILTER",
        help="Destroy benchmark VMs whose name contains FILTER (default: config vm_name_prefix) and exit",
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
