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
# NetApp ONTAP Integration Tests — README

This folder contains end-to-end integration tests for the NetApp ONTAP primary storage plugin in Apache CloudStack. The tests use the **Marvin** framework to drive real CloudStack API calls against a live management server and verify outcomes on a real ONTAP storage system.

CI wiring:
- Bundles: `private-cicd/marvin/bundles.txt`
- Zone config: `private-cicd/marvin/zones/` (downstream only)

---

## Directory layout

```
test/integration/plugins/ontap/
├── ontap.cfg                     # Environment config (IPs, credentials, zone info)
├── ontap_test_base.py            # Shared base class and ONTAP REST client
├── TEST_CASES.md                 # Full test case reference table (62 tests)
├── README.md                     # This file
│
├── nfs3/
│   ├── pool/
│   │   ├── test_pool_lifecycle.py        # Pool create/disable/enable/maintenance/delete
│   │   ├── test_pool_with_volumes.py     # Same lifecycle with a CS volume present
│   │   └── test_zone_scoped_pool.py      # Zone-scoped pool (attachZone)
│   ├── volume/
│   │   └── test_volume_lifecycle.py      # Volume create/delete/negative-delete
│   └── instance/
│       └── test_vm_volume_attach.py      # Pool + volume + VM + attach/detach
│
└── iscsi/
    ├── pool/
    │   ├── test_pool_lifecycle.py        # iSCSI pool lifecycle + igroup assertions
    │   ├── test_pool_with_volumes.py     # Same lifecycle with a LUN-backed volume
    │   └── test_zone_scoped_pool.py      # Zone-scoped iSCSI pool
    ├── volume/
    │   └── test_volume_lifecycle.py      # LUN create/delete/negative-delete
    └── instance/
        └── test_vm_volume_attach.py      # Pool + LUN + VM + attach/LUN-map lifecycle
```

---

## What is being tested

The ONTAP plugin (`plugins/storage/volume/ontap/`) integrates CloudStack's primary storage API with the NetApp ONTAP REST API. Every test suite verifies **both sides** of an operation:

1. **CloudStack side** — the expected `listStoragePools` / `listVolumes` / `listVirtualMachines` state after each API call.
2. **ONTAP side** — the actual ONTAP object state (FlexVol, LUN, igroup, export policy, LUN-map) via direct REST API queries.

### NFS3 vs iSCSI — key differences

| Aspect | NFS3 | iSCSI |
|--------|------|-------|
| ONTAP object per pool | FlexVol + export policy | FlexVol + igroup per KVM host |
| ONTAP object per CS volume | None (FlexVol is shared) | One LUN inside the FlexVol |
| Host connectivity | NFS mount | iSCSI login (IQN-based) |
| Volume detach from running VM | Works via virtio hot-unplug | Requires KVM guest to support SCSI hot-unplug |

---

## Prerequisites

Before running any test:

1. **CloudStack management server** running with the ONTAP plugin deployed (jar in `/usr/share/cloudstack-management/lib/`).
2. **Integration API port 8096 enabled** — run on the management server:
   ```sql
   UPDATE configuration SET value='8096' WHERE name='integration.api.port';
   ```
   Then restart: `systemctl restart cloudstack-management`
3. **MySQL accessible remotely** from your laptop (port 3306). If not:
   ```bash
   sudo sed -i 's/^bind-address.*/bind-address = 0.0.0.0/' /etc/mysql/mysql.conf.d/mysqld.cnf
   sudo iptables -I INPUT -p tcp --dport 3306 -j ACCEPT
   sudo systemctl restart mysql
   ```
4. **ONTAP SVM** with NFS3 service and/or iSCSI service enabled, and at least one data LIF per protocol.
5. **KVM cluster** registered in CloudStack. For iSCSI tests, every KVM host must have iSCSI configured (its `storageUrl` starts with `iqn.`).
6. **`ontap.cfg` populated** — see the [Configuration](#configuration--ontapcfg) section.

### Python / Marvin setup

```bash
# Install Marvin from the repo's bundled tarball
python3 -m pip install --user \
    "$(ls tools/marvin/dist/Marvin-*.tar.gz | tail -1)"

# Verify
python3 -c "import marvin; print('Marvin OK')"
```

---

## Configuration — `ontap.cfg`

`ontap.cfg` is a JSON file that tells Marvin where CloudStack and ONTAP are. **Never commit real credentials.**

Key sections:

```json
{
  "mgtSvr": [{ "mgtSvrIp": "<CS_IP>", "port": 8096, "user": "admin", "passwd": "password" }],
  "dbSvr":  { "dbSvr": "<CS_IP>", "port": 3306, "user": "cloud", "passwd": "cloud" },
  "ontap":  { "storageIP": "<ONTAP_IP>", "svmName": "<SVM>", "username": "admin", "password": "<pw>" }
}
```

The test classes read `storageIP`, `svmName`, `username`, and `password` from the `ontap` section at runtime. **No credentials appear in test code.**

---

## Running the tests

**Always run from the repo root.** The recommended entry point is [`run_tests.sh`](run_tests.sh), which runs suites sequentially (required for shared test state) and writes unified reports under `results/`.

### Protocol batch commands (recommended)

Run all suites for one protocol in a single batch, then inspect consolidated results:

```bash
# iSCSI only — 6 suites, ~40–60 min
bash test/integration/plugins/ontap/run_tests.sh iscsi

# NFS3 only — 6 suites, ~40–60 min
bash test/integration/plugins/ontap/run_tests.sh nfs3

# Full plugin validation: iSCSI batch, then NFS3 batch
bash test/integration/plugins/ontap/run_tests.sh both

# Default (setup_zone + iscsi + nfs3; excludes cleanup_zone)
bash test/integration/plugins/ontap/run_tests.sh
bash test/integration/plugins/ontap/run_tests.sh all

# Template-cache suites only
bash test/integration/plugins/ontap/run_tests.sh nfs3_template_cache
bash test/integration/plugins/ontap/run_tests.sh iscsi_template_cache
```

Each protocol batch runs suites in this order: pool lifecycle → pool with volumes → volume lifecycle → zone-scoped pool → VM attach → template cache (last).

| Command | What it runs |
|---------|--------------|
| `run_tests.sh iscsi` | All 6 iSCSI suites + unified iSCSI report |
| `run_tests.sh nfs3` | All 6 NFS3 suites + unified NFS3 report |
| `run_tests.sh both` | iSCSI batch, then NFS3 batch + combined report |
| `run_tests.sh all` | `setup_zone`, then `both` (iSCSI before NFS3) |
| `run_tests.sh nfs3_workflow` | Single suite by tag (unchanged) |
| `run_tests.sh nfs3_template_cache` | NFS3 template-cache suite only |
| `run_tests.sh iscsi_template_cache` | iSCSI template-cache suite only |
| `run_tests.sh setup_zone` | Zone setup only |
| `run_tests.sh cleanup_zone` | Zone teardown (manual; destructive) |

### Results layout

After a protocol batch, artifacts are under `test/integration/plugins/ontap/results/`:

```
results/<YYYYMMDD-HHMMSS>-iscsi/
  run.meta.json       # protocol, timestamps, per-suite exit codes
  summary.tsv         # all tests (tab-separated)
  summary.json        # machine-readable aggregate (CI-friendly)
  summary.txt         # human-readable TEST SUMMARY
  suites/
    iscsi_workflow/
      stdout.log
      results.txt     # copy of Marvin results
      runinfo.txt
    ...
```

Symlinks: `results/latest-iscsi`, `results/latest-nfs3`, `results/latest-both`.

For `both` / `all`, the parent folder `results/<timestamp>-both/` contains `iscsi/` and `nfs3/` sub-batches plus a combined `summary.txt` at the top level.

Marvin also writes raw logs to `/tmp/MarvinLogs/<run>/` during execution.

### Manual nose commands

```bash
# Single suite (e.g. NFS3 pool lifecycle)
PYTHONPATH=test/integration/plugins/ontap \
test/integration/plugins/ontap/.venv/bin/python -m nose --with-marvin \
    --marvin-config=test/integration/plugins/ontap/ontap.cfg \
    test/integration/plugins/ontap/nfs3/pool/test_pool_lifecycle.py -v

# By tag
PYTHONPATH=test/integration/plugins/ontap \
test/integration/plugins/ontap/.venv/bin/python -m nose --with-marvin \
    --marvin-config=test/integration/plugins/ontap/ontap.cfg \
    -a tags=iscsi_workflow \
    test/integration/plugins/ontap/iscsi/pool/test_pool_lifecycle.py -v
```

> **Important:** `PYTHONPATH=test/integration/plugins/ontap` is always required. Test files import `ontap_test_base` from the parent directory.

> **Single-host lab:** Suites within a batch run **sequentially** (not in parallel). iSCSI completes before NFS3 starts in `both`/`all` so the one KVM host is not shared across protocol operations simultaneously.

---

## Code structure — how a test file is organised

Every test file follows the same layout:

```
1. Apache 2.0 license header
2. Module docstring  ← workflow summary, prerequisites, run command
3. Imports
4. TestData class    ← holds all config values read from ontap.cfg; builds the
                       createStoragePool command parameters
5. Test class (extends OntapTestBase)
   ├── Class-level state attributes (pool, volume, vm, etc.) initialised to None
   ├── setUpClass()     ← connects to CloudStack; creates a test account and
   │                      disk offering; resolves zone/cluster/hosts
   ├── tearDownClass()  ← best-effort cleanup: deletes pool (forced=True),
   │                      volume, account, disk offering
   ├── Helper methods   ← _create_pool(), _create_volume(), _poll_pool_state(),
   │                      _lun_maps() (iSCSI only), etc.
   └── test_01 … test_N  ← sequential, numbered test methods
```

### Key patterns to know

**Sequential state sharing — always use `self.__class__.<attr>`**

Tests share state via class attributes, never instance attributes:
```python
# Correct
self.__class__.pool = pool
pool = self.__class__.pool

# Wrong — state is lost between test method invocations
self.pool = pool
```

**Guard assertion at the start of every test (except test_01)**

Every test after the first starts with an assertion that the previous step's resource exists. This produces a clear, readable failure message instead of a confusing `AttributeError`:
```python
def test_03_enable_storage_pool(self):
    self.assertIsNotNone(self.__class__.pool, "Pool absent — test_01 must pass first")
```

**Creating a storage pool — always use indexed `details[N].key` syntax**

The CloudStack API for `createStoragePool` requires plugin details to be passed as indexed parameters. **Never call `StoragePool.create()` directly** — it does not support this syntax:
```python
count = 1
for key, value in ps["details"].items():
    setattr(cmd, "details[{}].{}".format(count, key), value)
    count += 1
```

**Polling for async state changes**

CloudStack operations are asynchronous. Use `_poll_pool_state()` rather than reading state immediately after an API call:
```python
result = self._poll_pool_state(pool.id, "Maintenance", timeout=120)
self.assertEqual(result.state, "Maintenance")
```

---

## Shared base — `ontap_test_base.py`

`OntapTestBase` provides everything individual test classes inherit:

| What | Purpose |
|------|---------|
| `_setup_cloudstack_resources()` | Creates a test account, domain, disk offering; resolves zone/cluster/hosts |
| `tearDownClass()` | Best-effort cleanup: deletes pool (forced=True), volume, disk offering, account |
| `_poll_pool_state(pool_id, state, timeout)` | Polls `listStoragePools` until pool reaches the target state |
| `_create_volume(pool_id)` | Creates a CloudStack data volume on the given pool |
| `_delete_pool(pool_id, forced)` | Enters Maintenance then calls `deleteStoragePool` |
| `_parse_pool_details(pool)` | Extracts key→value pairs from the pool's `details` list |
| `OntapRestClient` | Thin HTTPS client for ONTAP REST API calls |

### `OntapRestClient` methods at a glance

| Method | What it checks | Used in |
|--------|---------------|---------|
| `get_volume(name)` | FlexVol existence and state | All suites |
| `get_export_policy(name)` | NFS export policy existence | NFS3 suites |
| `get_data_lifs(svm_name)` | NFS data LIF count | NFS3 pool lifecycle |
| `get_igroup(svm_name, name)` | iSCSI igroup existence and initiator list | iSCSI suites |
| `list_luns_in_volume(svm_name, vol_name)` | LUNs present in a FlexVol | iSCSI volume/instance suites |
| `list_lun_maps_for_volume(svm_name, vol_name)` | Active LUN-maps for a volume | iSCSI instance suite |
| `list_files_in_volume(svm_name, vol_name)` | Files inside a FlexVol | NFS3 instance suite |

---

## Test suite quick reference

| Suite | File | Tests | What it covers |
|-------|------|-------|---------------|
| NFS3 Pool Lifecycle | `nfs3/pool/test_pool_lifecycle.py` | 8 | Create, disable, enable, maintenance, delete |
| NFS3 Pool with Volumes | `nfs3/pool/test_pool_with_volumes.py` | 7 | Same + live volume present; negative delete guard |
| NFS3 Zone-Scoped Pool | `nfs3/pool/test_zone_scoped_pool.py` | 4 | Zone scope — all hosts connected via `attachZone` |
| NFS3 Volume Lifecycle | `nfs3/volume/test_volume_lifecycle.py` | 5 | Volume is metadata-only; FlexVol unchanged on delete |
| NFS3 VM + Volume Attach | `nfs3/instance/test_vm_volume_attach.py` | 8 | Full VM lifecycle with hot-plug/detach |
| NFS3 Template Cache | `nfs3/template/test_template_cache.py` | 6 | ROOT on tagged pool; seed/reuse cache; survive VM delete |
| iSCSI Pool Lifecycle | `iscsi/pool/test_pool_lifecycle.py` | 8 | Create, disable, enable, maintenance, delete + igroups |
| iSCSI Pool with Volumes | `iscsi/pool/test_pool_with_volumes.py` | 7 | Same + live LUN present; negative delete guard |
| iSCSI Zone-Scoped Pool | `iscsi/pool/test_zone_scoped_pool.py` | 4 | Zone scope |
| iSCSI Volume Lifecycle | `iscsi/volume/test_volume_lifecycle.py` | 5 | LUN created per CS volume; LUN removed on delete |
| iSCSI VM + Volume Attach | `iscsi/instance/test_vm_volume_attach.py` | 8 | Full VM lifecycle; LUN-maps on VM start/stop/detach |
| iSCSI Template Cache | `iscsi/template/test_template_cache.py` | 6 | ROOT on tagged pool; `cs_tmpl_*` LUN cache seed/reuse |

For the goal, dependencies, and exact success criteria of every individual test, see [TEST_CASES.md](TEST_CASES.md).

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `ModuleNotFoundError: No module named 'ontap_test_base'` | Missing `PYTHONPATH` prefix | Prefix every run with `PYTHONPATH=test/integration/plugins/ontap` |
| `Marvin Init Failed` | CloudStack API unreachable | Check `mgtSvrIp:8096` is reachable; restart `cloudstack-management` |
| `Lost connection to MySQL` | MySQL not accepting remote connections | Enable remote MySQL access (see Prerequisites §3) |
| `sh: python: command not found` (repeated) | Marvin internal call — harmless on macOS | Ignore; Marvin Init still succeeds |
| Pool state never reaches `Maintenance` | KVM agent not responding | Check `cloudstack-agent` on KVM host; verify host is connected in CloudStack UI |
| iSCSI `test_07` error 530 | KVM guest does not ACK SCSI hot-unplug | Known environment limitation — see TEST_CASES.md Suite 10 note |
| ONTAP REST `401 Unauthorized` | Wrong credentials in `ontap.cfg` | Verify `username`/`password` under `ontap` section |
| `No ready KVM user template available` | Template still downloading | Re-run `setup_zone` (step 12 waits for template readiness); or wait in CloudStack UI |
| `setup_zone` steps 11–12 slow on first run | System VMs and template download after zone enable | Normal — first run may take up to ~60 min; re-runs pass quickly when already ready |
| `cleanup_zone` pool delete fails | Pool stuck in Maintenance or KVM NFS mount stale | Re-run cleanup; check host connectivity; manually `umount /mnt/<pool-uuid>` on KVM if needed |
| `deleteZone failed` after cleanup | VMs, pools, or hosts still present in zone | Re-run `cleanup_zone`; check CloudStack UI for remaining resources |

---

## Adding new test cases

1. Pick the existing file closest to what you need and copy its structure.
2. Read `ontap_test_base.py` for the exact method signatures you can reuse.
3. Copy the `_create_pool()` helper from an existing file that matches your protocol — **never** call `StoragePool.create()`.
4. Number your methods `test_01`, `test_02`, … and add `@attr(tags=["<your_tag>"], required_hardware=True)` to each.
5. Use `self.__class__.<attr>` for all state shared between test methods.
6. Syntax-check before the first full run: `python3 -m py_compile <your_file>.py`
7. Add your test cases to [TEST_CASES.md](TEST_CASES.md).
