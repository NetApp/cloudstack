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
Single entry point for the ONTAP scale benchmarks.

Protocols, checkpoints, and concurrency levels come from config.yaml. Drop a
protocol block to skip it, and edit sequential_checkpoints or concurrency_levels
to change the matrix. The selected test reads those fields as written.

Usage:
    python3 run.py --test storage-pool-sequential --config config.yaml
    python3 run.py --test storage-pool-concurrency --config config.yaml --dry-run
    python3 run.py --test vm-instance-combined --config config.yaml
    python3 run.py --test vm-instance-sequential --config config.yaml --cleanup-only
"""

import argparse

from benchmark_storage_pool_concurrency import execute as run_storage_pool_concurrency
from benchmark_storage_pool_sequential import execute as run_storage_pool_sequential
from benchmark_support import configure_logging
from benchmark_vm_instance_combined import execute as run_vm_instance_combined
from benchmark_vm_instance_concurrency import execute as run_vm_instance_concurrency
from benchmark_vm_instance_sequential import execute as run_vm_instance_sequential
from storage_pool_common import load_config

TESTS = {
    "storage-pool-sequential": run_storage_pool_sequential,
    "storage-pool-concurrency": run_storage_pool_concurrency,
    "vm-instance-sequential": run_vm_instance_sequential,
    "vm-instance-concurrency": run_vm_instance_concurrency,
    "vm-instance-combined": run_vm_instance_combined,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--test", required=True, choices=sorted(TESTS), help="Benchmark to run")
    parser.add_argument("--config", default="config.yaml", help="Path to config YAML (default: config.yaml)")
    parser.add_argument("--run-id", default=None, help="Reuse a run id (auto-generated timestamp + random suffix otherwise)")
    parser.add_argument("--dry-run", action="store_true", help="Simulate timings, no real API calls")
    parser.add_argument("--skip-cleanup", action="store_true", help="Leave leftovers from this run in place")
    parser.add_argument(
        "--cleanup-only", nargs="?", const="__PREFIX__", default=None, metavar="FILTER",
        help="Delete benchmark resources whose name contains FILTER (default: configured name prefix) and exit",
    )
    args = parser.parse_args()

    configure_logging()
    cfg = load_config(args.config)
    runner = TESTS[args.test]
    runner(
        cfg,
        dry_run=args.dry_run,
        skip_cleanup=args.skip_cleanup,
        cleanup_only=args.cleanup_only,
        run_id=args.run_id,
    )


if __name__ == "__main__":
    main()
