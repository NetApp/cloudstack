<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->
# ONTAP plugin - storage pool benchmark (Step 1)

> Looking for a quick "what does script X do / how do I run it" cheat
> sheet instead of the full design write-up below? See [USAGE.md](USAGE.md).

Downstream-only benchmarking tool (not for Apache upstream) that drives
`createStoragePool` / `deleteStoragePool` over the CloudStack HTTP/REST API
to reproduce the **Storage Pool** rows of the Sequential Scale Matrix (5.1)
and Parallel/Concurrency Matrix (6.1) from the Confluence page
["ONTAP Plugin - CloudStack Operations, Scale & Parallel Test Matrix"](https://netapp.atlassian.net/wiki/spaces/OSSG/pages/608854350).

This is step 1 of the automation follow-up (storage pool only). VM instance
benchmarks are now also covered (see below); volume/snapshot benchmarks will
be added as separate scripts later, reusing `cloudstack_client.py`.

The storage-pool benchmark is split into **two independent scripts** (on
purpose — a sequential run is cheap/safe and worth reviewing before deciding
to launch a concurrency run, which is the one most likely to stress the
mgmt-server/plugin job queue):

- `benchmark_storage_pool_sequential.py` — Section 5.1 (5.1.1 / 5.1.2)
- `benchmark_storage_pool_concurrency.py` — Section 6.1 (6.1.1 / 6.1.2)

Both share the same createStoragePool/deleteStoragePool call shape, CSV
formats, and cleanup logic via `storage_pool_common.py`. Run them with the
**same `--run-id`** to accumulate both into a single
`results/summary_<run_id>.csv` (and therefore a single `render_report.py`
output covering 5.1.x and 6.1.x together).

The VM-instance benchmark is split into **three scripts** sharing
`vm_instance_common.py` (same call shape/CSV formats/cleanup logic):

- `benchmark_vm_instance_sequential.py` — Section 5.2 (5.2.1 / 5.2.2)
- `benchmark_vm_instance_concurrency.py` — Section 6.2 (6.2.1 / 6.2.2)
- `benchmark_vm_instance_combined.py` — runs both of the above back to back
  under one shared `--run-id`, for when you already trust the config/
  environment and just want the full 5.2.x + 6.2.x matrix in one invocation

See "VM instance benchmark" below for prerequisites and usage.

## What it does

1. **Sequential scale test (5.1.1 / 5.1.2)** — for each protocol (NFS3,
   iSCSI), creates storage pools one at a time up to N=30, logging every
   single create call, and reports cumulative-total/avg-per-op timing at
   checkpoints N = 1, 5, 10, 20, 30. Then deletes them one at a time and
   reports the same at "N remaining" = 30, 20, 10, 5, 1.
2. **Concurrency test (6.1.1 / 6.1.2)** — for each concurrency level
   C = 2, 5, 10, 20, 30 and each protocol, creates C pools in parallel
   (`ThreadPoolExecutor`), records wall-clock time for the whole batch plus
   success/failure/avg-per-op, then deletes the same C pools in parallel.
3. Writes **every** individual API call (start/end timestamp, duration,
   success/failure, pool id, error) to `results/raw_ops_<run_id>.csv`, and a
   checkpoint-level roll-up to `results/summary_<run_id>.csv`.
4. `render_report.py` turns those two CSVs into markdown tables that match
   the Confluence page's table layout (including min/avg/p95/max stats for
   the "Results Log" section), ready to paste back into the page.

## Setup

```bash
cd private-cicd/benchmark/ontap
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp config.example.yaml config.yaml
```

Edit `config.yaml`:

- `cloudstack.*` — API URL, admin username/password of your lab's
  management server.
- `infrastructure.*` — zone/pod/cluster UUIDs to create pools under.
- `ontap.nfs3` / `ontap.iscsi` — connection details of the ONTAP SVM(s) you
  want to benchmark against for each protocol. Delete a block if you only
  have one protocol available.
- `benchmark.*` — checkpoints, concurrency levels, pool name prefix, output
  directory. Defaults match the Confluence page (N/C = 1,5,10,20,30 /
  2,5,10,20,30).

`config.yaml` and everything under `results/` are gitignored — never commit
real lab IPs/credentials or run output.

## Running

```bash
# Sanity-check either script/config without hitting a real management server:
python3 benchmark_storage_pool_sequential.py --config config.yaml --dry-run
python3 benchmark_storage_pool_concurrency.py --config config.yaml --dry-run

# Sequential scale matrix, both protocols, up to config's sequential_checkpoints:
python3 benchmark_storage_pool_sequential.py --config config.yaml

# Sequential scale matrix, NFS3 only:
python3 benchmark_storage_pool_sequential.py --config config.yaml --protocol nfs3

# Concurrency matrix, both protocols, up to config's concurrency_levels (e.g. 30):
python3 benchmark_storage_pool_concurrency.py --config config.yaml

# Concurrency matrix, iSCSI only, overriding the levels to run:
python3 benchmark_storage_pool_concurrency.py --config config.yaml --protocol iscsi --levels 2,5,10,20,30

# Use the SAME --run-id across both scripts to combine 5.1.x + 6.1.x into one
# summary_<run_id>.csv / report (run sequential first, review it, then decide
# whether to proceed with concurrency under the same run id):
python3 benchmark_storage_pool_sequential.py --config config.yaml --run-id RUN-0001
python3 benchmark_storage_pool_concurrency.py --config config.yaml --run-id RUN-0001
```

At the end of a real run each script automatically checks for and deletes any
storage pool whose name still contains the run id (safety net for pools that
were created but never got torn down because of a mid-run crash). Pass
`--skip-cleanup` to disable that and inspect the pools yourself.

If a run crashes hard (script killed, network partition, etc.) and left
pools behind, recover with either script's `--cleanup-only` (they share the
same cleanup logic):

```bash
# Deletes every pool whose name contains "bench_ontap" (the default prefix)
python3 benchmark_storage_pool_sequential.py --config config.yaml --cleanup-only

# Or scope it to one specific run:
python3 benchmark_storage_pool_sequential.py --config config.yaml --cleanup-only RUN_20260722_101500
```

## Rendering the Confluence-ready report

```bash
python3 render_report.py --run-id RUN-20260722-101500 \
    --cloudstack-build 4.23.0.0-SNAPSHOT --ontap-version 9.15.1
```

This prints markdown tables for 5.1.1, 5.1.2, 6.1.1, 6.1.2, and a set of
pre-filled rows for the page's Section 9 "Results Log" table (with Min/Avg/
P95/Max computed from the raw per-op log), and also saves them to
`results/report_<run_id>.md`.

## VM instance benchmark

Drives `deployVirtualMachine` / `destroyVirtualMachine` to reproduce the
**VM Instance** rows of the same Confluence page's Sequential Scale Matrix
(5.2) and Parallel/Concurrency Matrix (6.2). Every VM gets a root disk (from
the service offering) AND a data disk (from the disk offering), both landing
on the same tagged storage pool.

### Prerequisites (one-time setup, per protocol)

Unlike the storage-pool benchmark's transient pools, the VM-instance
benchmark needs a **persistent, pre-created** `bench_vm_<protocol>` pool plus
matching offerings - it does not create/destroy the pool itself:

1. A storage pool (e.g. `bench_vm_nfs3_pool`) with a distinct storage tag
   (e.g. `bench_vm_nfs3`), sized comfortably below your aggregate's capacity.
2. A service offering (root disk) and a disk offering (data disk), both
   tagged with that same storage tag, so root + data land on the same pool.
   Set the service offering's `rootdisksize` (GB) to comfortably exceed the
   template's actual `qemu-img` **virtual** size (not its sparse/download
   file size) - too small silently "succeeds" on NFS but hard-fails on iSCSI
   with `qemu-img: Cannot grow device files` (see Confluence Issue #2).
3. Fill in `vm_bench.templateid` / `networkid` / per-protocol
   `serviceofferingid` / `diskofferingid` in `config.yaml` with the above.

### Running

```bash
# Sanity-check without hitting a real management server:
python3 benchmark_vm_instance_sequential.py --config config.yaml --dry-run
python3 benchmark_vm_instance_concurrency.py --config config.yaml --dry-run

# Sequential scale matrix (5.2.1/5.2.2), both protocols:
python3 benchmark_vm_instance_sequential.py --config config.yaml

# Concurrency matrix (6.2.1/6.2.2), iSCSI only, custom levels:
python3 benchmark_vm_instance_concurrency.py --config config.yaml --protocol iscsi --levels 1,2,5,10

# Both matrices in one invocation, combined into a single summary/report:
python3 benchmark_vm_instance_combined.py --config config.yaml

# Or run the two standalone scripts under the SAME --run-id to combine them
# instead (lets you review the sequential results before committing to
# concurrency):
python3 benchmark_vm_instance_sequential.py --config config.yaml --run-id RUN-0001
python3 benchmark_vm_instance_concurrency.py --config config.yaml --run-id RUN-0001

python3 render_report.py --run-id RUN-0001 --raw-prefix raw_ops_vm --summary-prefix summary_vm --report-suffix _vm
```

All three scripts share the same `--skip-cleanup` / `--cleanup-only` safety
net as the storage-pool scripts (see above), via `vm_instance_common.py`.
Failed `deployVirtualMachine` calls are logged but their VM/disk are
intentionally left in place (not auto-destroyed) so you can inspect exactly
what got left behind - use `--cleanup-only` once you're done.

## Notes / caveats

- Auth is session-key based (`login` -> `sessionkey` + `JSESSIONID` cookie),
  matching the approach documented on the Confluence API page. API
  key/secret signed requests are not implemented (add to
  `cloudstack_client.py` if your lab requires it).
- Timing is measured end-to-end from the caller's perspective, including any
  async job polling (`queryAsyncJobResult`) — this is the number an operator
  or automation would actually observe, not raw server-side processing time.
- The single `CloudStackClient`/`requests.Session` is shared across threads
  during concurrency tests. We never mutate session state after login, so
  this is safe; if you prefer full isolation, instantiate one
  `CloudStackClient` per thread instead.
- `createStoragePool`/`deleteStoragePool`/`updateStoragePool` are
  synchronous in this CloudStack version (no `jobid` in the response);
  `enableStorageMaintenance`/`cancelStorageMaintenance` are async — the
  client polls `queryAsyncJobResult` automatically for any response that
  does contain a `jobid`, so the script works either way.
- This script intentionally covers **create/delete only** (per the current
  ask). Maintenance mode and enable/disable pool are documented in
  `cloudstack_client.py`'s API surface but not yet wired into a benchmark
  phase — flag if you want those added to the matrix too.
