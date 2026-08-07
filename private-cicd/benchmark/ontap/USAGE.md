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
# Usage Reference

Quick reference for **what each file does** and **how to run it**. For the
"why" behind the design (what each benchmark measures, how it maps to the
Confluence test matrix, known caveats) see [README.md](README.md) instead -
this file is deliberately just a lookup table / cheat sheet.

## 1. Prerequisites

| # | Requirement | Notes |
|---|---|---|
| 1 | Python 3.9+ | No other version-specific features used; anything 3.9-3.12 should work. |
| 2 | Network access to a CloudStack management server's API (`/client/api`) | Admin credentials with permission to create/delete storage pools, service/disk offerings, and deploy/destroy VMs. |
| 3 | Network access (HTTPS/443) from **your machine** to the ONTAP cluster's management LIF | Only needed if you run the storage-pool scripts' `--cleanup-only` helper functions that talk to ONTAP directly for orphan checks; the benchmark scripts themselves only ever call the CloudStack API, never ONTAP directly. |
| 4 | A CloudStack zone/pod/cluster with at least one KVM host, and the ONTAP plugin's SVM(s) reachable from the CloudStack management server | See README.md's "Test Environment" section on the Confluence page for the exact lab topology this was built/tested against. |
| 5 | For the VM-instance scripts only: a pre-created `bench_vm_<protocol>` storage pool + matching service/disk offerings | One-time setup, see [Prerequisites (VM instance benchmark)](#4-vm-instance-benchmark-scripts) below. |

### One-time environment setup

```bash
cd private-cicd/benchmark/ontap
python3 -m venv .venv
source .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp config.example.yaml config.yaml
```

Then edit `config.yaml` with your lab's real API URL, credentials, zone/pod/
cluster IDs, and ONTAP connection details (see inline comments in
`config.example.yaml` for what each field means and why).

> **About `.venv/`:** this is a completely standard, disposable Python
> virtual environment (`python3 -m venv .venv` + `pip install -r
> requirements.txt`, which just pulls in `requests` and `PyYAML` plus their
> small set of transitive dependencies). It is **never committed** (it's in
> `.gitignore`) because it is:
> - **Machine/OS-specific** - it stores an absolute path back to the Python
>   interpreter that created it (`.venv/pyvenv.cfg`) and can contain
>   platform-compiled binary wheels (e.g. a `.so`/`.pyd` file bundled with
>   one of the dependencies) that won't load on a different OS/architecture.
> - **Python-version-specific** - the `lib/python3.9/...` directory layout
>   is tied to whatever interpreter version created it.
> - **Fully reproducible** - anyone can regenerate an equivalent one in a
>   few seconds with the two commands above; there is nothing hand-tuned or
>   irreplaceable in it.
>
> If you ever see `.venv/` show up in `git status` as untracked, it means
> `.gitignore` isn't being picked up (e.g. it was created before the
> `.gitignore` entry was added) - just confirm `.venv/` is listed in
> `.gitignore` and it will stop appearing.

## 2. Shared library modules (not run directly)

| File | Purpose |
|---|---|
| `cloudstack_client.py` | Minimal CloudStack HTTP/REST client (session-key login, generic `call()`, automatic `queryAsyncJobResult` polling). Used by every benchmark script. |
| `storage_pool_common.py` | Shared helpers for the storage-pool scripts: `createStoragePool`/`deleteStoragePool` request building, CSV logging (`RawLogger`, `append_summary_csv`), cleanup-by-filter, config loading. |
| `vm_instance_common.py` | Shared helpers for the VM-instance scripts: `deployVirtualMachine`/`destroyVirtualMachine` request building (incl. force-purging data disks and `Destroy`-state volumes so ONTAP space is reclaimed immediately instead of waiting for CloudStack's 24h cleanup delay), CSV logging, cleanup-by-filter, config loading. |

## 3. Storage-pool benchmark scripts

Reproduces Confluence sections 5.1 (sequential) and 6.1 (concurrency) -
`createStoragePool` / `deleteStoragePool` timing at scale.

| Script | What it does | Typical command |
|---|---|---|
| `benchmark_storage_pool_sequential.py` | Creates storage pools one at a time (per protocol) up to N=30, then deletes them one at a time, logging every call and reporting checkpoint totals at N = 1, 5, 10, 20, 30. | `python3 benchmark_storage_pool_sequential.py --config config.yaml` |
| `benchmark_storage_pool_concurrency.py` | For each concurrency level C (default 2, 5, 10, 20, 30), creates C pools in parallel via a thread pool, records wall-clock time for the whole batch, then deletes the same C pools in parallel. | `python3 benchmark_storage_pool_concurrency.py --config config.yaml` |

Common flags (both scripts):

| Flag | Meaning |
|---|---|
| `--config PATH` | Config YAML to use (default `config.yaml`). |
| `--protocol {nfs3,iscsi,both}` | Restrict the run to one protocol (default `both`). |
| `--run-id ID` | Reuse a specific run id (auto-generated otherwise) - use the **same id** across the sequential and concurrency scripts to merge both into one `summary_<run_id>.csv`/report. |
| `--dry-run` | Simulate timings with no real API calls - use this first to sanity-check your config. |
| `--skip-cleanup` | Skip the automatic post-run sweep for leftover pools from this run id. |
| `--cleanup-only [FILTER]` | Don't run the benchmark - just delete every pool whose name contains `FILTER` (default: config's `pool_name_prefix`) and exit. Use this to recover after a crashed run. |
| `--levels 2,5,10` | *(concurrency script only)* Override the concurrency levels to run instead of the config's `concurrency_levels`. |

## 4. VM-instance benchmark scripts

Reproduces Confluence sections 5.2 (sequential) and 6.2 (concurrency) -
`deployVirtualMachine` / `destroyVirtualMachine` timing at scale, with each
VM getting both a root disk and a data disk on the same tagged pool.

### Prerequisites (one-time, per protocol - not created/destroyed by the scripts)

1. A persistent storage pool (e.g. `bench_vm_nfs3_pool`) with a distinct
   storage tag (e.g. `bench_vm_nfs3`), sized comfortably below your
   aggregate's capacity.
2. A service offering (root disk) and a disk offering (data disk), both
   tagged with that same storage tag, so root + data land on the same pool.
   Set the service offering's `rootdisksize` (GB) to comfortably exceed the
   template's actual `qemu-img` **virtual** size - too small silently
   "succeeds" on NFS but hard-fails on iSCSI with `qemu-img: Cannot grow
   device files`.
3. Fill in `vm_bench.templateid` / `networkid` / per-protocol
   `serviceofferingid` / `diskofferingid` in `config.yaml`.

### Scripts

| Script | What it does | Typical command |
|---|---|---|
| `benchmark_vm_instance_sequential.py` | Deploys VMs one at a time (per protocol) up to N=30, then destroys them one at a time, reporting checkpoint totals at N = 1, 2, 5, 10, 20, 30. | `python3 benchmark_vm_instance_sequential.py --config config.yaml` |
| `benchmark_vm_instance_concurrency.py` | For each concurrency level C, deploys C VMs in parallel, records wall-clock time, then destroys the same C VMs in parallel. | `python3 benchmark_vm_instance_concurrency.py --config config.yaml` |
| `benchmark_vm_instance_combined.py` | Runs the sequential matrix immediately followed by the concurrency matrix, under one shared run id, in a single invocation. Use once you already trust your config/environment. | `python3 benchmark_vm_instance_combined.py --config config.yaml` |

Common flags: same as the storage-pool scripts above (`--config`,
`--protocol`, `--run-id`, `--dry-run`, `--skip-cleanup`, `--cleanup-only
[FILTER]`; `--levels` on the concurrency/combined scripts). Failed
`deployVirtualMachine` calls are intentionally **left in place** (not
auto-destroyed) so you can inspect what got left behind - clean them up
with `--cleanup-only` once done.

## 5. Report rendering

| Script | What it does | Typical command |
|---|---|---|
| `render_report.py` | Turns a run's `raw_ops_<run_id>.csv` + `summary_<run_id>.csv` into Confluence-ready markdown tables (matches the page's section layout, plus Min/Avg/P95/Max rows for the Results Log). Writes to stdout and to `results/report_<run_id>.md`. | `python3 render_report.py --run-id RUN-0001 --cloudstack-build 4.23.0.0-SNAPSHOT --ontap-version 9.17.1` |

For VM-instance runs (which write `raw_ops_vm_<run_id>.csv` /
`summary_vm_<run_id>.csv` instead of the storage-pool scripts' `raw_ops_`/
`summary_` prefix), pass the matching prefixes:

```bash
python3 render_report.py --run-id RUN-0001 \
    --raw-prefix raw_ops_vm --summary-prefix summary_vm --report-suffix _vm
```

| Flag | Meaning |
|---|---|
| `--run-id ID` | **Required.** The run id used by the benchmark script(s). |
| `--output-dir DIR` | Where the CSVs live / the report gets written (default `results`). |
| `--cloudstack-build STR` | Label only, stamped into the Results Log rows. |
| `--ontap-version STR` | Label only, stamped into the Results Log rows. |
| `--run-date YYYY-MM-DD` | Defaults to today. |
| `--raw-prefix STR` | CSV filename prefix for raw per-op data (default `raw_ops`; use `raw_ops_vm` for VM-instance runs). |
| `--summary-prefix STR` | CSV filename prefix for checkpoint summaries (default `summary`; use `summary_vm` for VM-instance runs). |
| `--report-suffix STR` | Suffix appended to the output report filename (e.g. `_vm`) to avoid clobbering a storage-pool report with the same run id. |

## 6. Config files at a glance

| File | Committed? | Purpose |
|---|---|---|
| `config.example.yaml` | Yes | Sanitized template - copy to `config.yaml` and fill in your lab's real values. |
| `config.yaml` | **No** (gitignored) | Your real, lab-specific config (real IPs/credentials) - never commit this. |
| `config_sanity.yaml` | **No** (gitignored) | Optional scratch config some contributors keep locally for quick one-off dry-runs; not part of the checked-in tooling. |

## 7. Output files at a glance

All written under `results/` (gitignored - these are run artifacts, not
source):

| Pattern | Produced by | Contents |
|---|---|---|
| `raw_ops_<run_id>.csv` | storage-pool scripts | One row per individual API call (timestamps, duration, success/failure, error). |
| `raw_ops_vm_<run_id>.csv` | VM-instance scripts | Same, for VM deploy/destroy calls. |
| `summary_<run_id>.csv` / `summary_vm_<run_id>.csv` | both | One row per checkpoint/concurrency level (totals, averages, success/failure counts). |
| `report_<run_id>.md` / `report_vm_<run_id>.md` | `render_report.py` | Confluence-ready markdown tables generated from the two CSVs above. |
