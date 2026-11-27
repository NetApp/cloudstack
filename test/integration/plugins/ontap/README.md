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
- Pipeline: `private-cicd/Jenkinsfile` (downstream only)
- Inventory schema: `private-cicd/config/vm-inventory.yaml.example`
- Runtime config: generated from a populated Jenkins Secret file and separate
  Jenkins credentials; populated inventory and real credentials are never
  committed
- Test order: `setup_zone`, `iscsi`, then `nfs3`
- Archived results: `presubmit-results/phase2/marvin/`

The pipeline installs the version-matched Marvin archive produced by the
CloudStack package build, stages this directory on a reverted lab VM, and runs
the protocol batches sequentially. See
[`private-cicd/docs/PRIVATE-CICD-GUIDE.md`](../../../../private-cicd/docs/PRIVATE-CICD-GUIDE.md)
for the deployment, health-gate, and artifact contracts.

---

## Directory layout

```
test/integration/plugins/ontap/
├── ontap.cfg                     # Environment config (IPs, credentials, zone info)
├── ontap_test_base.py            # Shared base class and ONTAP REST client
├── guest_ssh.py                  # Guest SSH (paramiko) for the VM workflow suites
├── setup_env.sh                  # venv: Marvin plus requirements.txt
├── requirements.txt              # setuptools, pyvmomi, paramiko pins
├── TEST_CASES.md                 # Full test case reference table (211 tests)
├── README.md                     # This file
│
├── helpers/
│   ├── qos_offerings.py              # Shared QoS cases; both qos suites inherit them
│   └── ...                           # KVM host and template-cache helpers
│
├── nfs3/
│   ├── pool/
│   │   ├── test_pool_lifecycle.py        # Pool create/disable/enable/maintenance/delete
│   │   ├── test_pool_with_volumes.py     # Same lifecycle with a CS volume present
│   │   └── test_zone_scoped_pool.py      # Zone-scoped pool (attachZone) + capacity IOPS
│   ├── volume/
│   │   └── test_volume_lifecycle.py      # Volume create/delete/negative-delete
│   ├── qos/
│   │   └── test_qos_offerings.py         # QoS policy on NFS files: disk/compute offerings, resize
│   ├── instance/
│   │   └── test_vm_volume_attach.py      # Pool + volume + VM + resize, snapshots, attach/detach + template cache
│   └── template/
│       └── test_template_cache_negative.py  # Template-cache boundary cases
│
└── iscsi/
    ├── pool/
    │   ├── test_pool_lifecycle.py        # iSCSI pool lifecycle + igroup assertions
    │   ├── test_pool_with_volumes.py     # Same lifecycle with a LUN-backed volume
    │   └── test_zone_scoped_pool.py      # Zone-scoped iSCSI pool + capacity IOPS
    ├── volume/
    │   └── test_volume_lifecycle.py      # LUN create/delete/negative-delete
    ├── qos/
    │   └── test_qos_offerings.py         # QoS policy on LUNs: disk/compute offerings, resize
    ├── instance/
    │   └── test_vm_volume_attach.py      # Pool + LUN + VM + resize, snapshots, LUN-map lifecycle + template cache
    └── template/
        └── test_template_cache_negative.py  # Template-cache boundary cases
```

---

## What is being tested

The ONTAP plugin (`plugins/storage/volume/ontap/`) integrates CloudStack's primary storage API with the NetApp ONTAP REST API. Every test suite verifies **both sides** of an operation:

1. **CloudStack side** — the expected `listStoragePools` / `listVolumes` / `listVirtualMachines` state after each API call.
2. **ONTAP side** — the actual ONTAP object state (FlexVol, LUN, igroup, export policy, LUN-map) via direct REST API queries.

### NFS3 vs iSCSI — key differences

| Aspect | NFS3 | iSCSI |
| -------- | ------ | ------- |
| ONTAP object per pool | FlexVol + export policy | FlexVol; igroups are shared per KVM host and SVM |
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

The VM workflow suites also need a venv with pinned dependencies. From the repo root:

```bash
bash test/integration/plugins/ontap/setup_env.sh
```

The script is safe to re-run. It creates `test/integration/plugins/ontap/.venv` when that directory is missing, then installs into it:

- Marvin (from `MARVIN_ARCHIVE` when set, otherwise from `tools/marvin` after generating `commands.xml`)
- the pins in `requirements.txt`: `setuptools>=40.3.0,<81`, `pyvmomi==8.0.2.0.1`, and `paramiko>=3.4,<4`

`run_tests.sh` uses that venv automatically.

#### Paramiko (guest SSH)

`nfs3/instance/test_vm_volume_attach.py` and `iscsi/instance/test_vm_volume_attach.py` log into the guest through `guest_ssh.py`. The lab template is CentOS 5.5, and its sshd only offers SHA-1 key exchange (`diffie-hellman-group14-sha1`) and `ssh-rsa` host keys. Paramiko 4.x removed SHA-1, so those logins fail unless the venv has Paramiko 3.x.

`setup_env.sh` installs the pin from `requirements.txt`:

```bash
test/integration/plugins/ontap/.venv/bin/pip install -r test/integration/plugins/ontap/requirements.txt
```

`guest_ssh.check_paramiko()` checks for `diffie-hellman-group14-sha1` before the first guest connection and tells you to re-run `setup_env.sh` when the active interpreter has Paramiko 4. Guest login itself comes from `ontap.cfg` (`cloudstack.templateUsername` / `cloudstack.templatePassword`).

Confirm the venv before a VM workflow run:

```bash
test/integration/plugins/ontap/.venv/bin/python -c \
  "import paramiko; print(paramiko.__version__); assert 'diffie-hellman-group14-sha1' in paramiko.Transport._kex_info"
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
# iSCSI only — 7 suites
bash test/integration/plugins/ontap/run_tests.sh iscsi

# NFS3 only — 7 suites
bash test/integration/plugins/ontap/run_tests.sh nfs3

# Full plugin validation: iSCSI batch, then NFS3 batch
bash test/integration/plugins/ontap/run_tests.sh both

# Default (setup_zone + iscsi + nfs3; excludes cleanup_zone)
bash test/integration/plugins/ontap/run_tests.sh
bash test/integration/plugins/ontap/run_tests.sh all

# Template-cache negative / boundary suites only
# (the template-cache happy path runs inside the VM attach suites)
bash test/integration/plugins/ontap/run_tests.sh nfs3_template_cache_negative
bash test/integration/plugins/ontap/run_tests.sh iscsi_template_cache_negative

# QoS offering suites
bash test/integration/plugins/ontap/run_tests.sh nfs3_qos
bash test/integration/plugins/ontap/run_tests.sh iscsi_qos
```

Each protocol batch runs suites in this order: pool lifecycle → pool with volumes → volume lifecycle → QoS offerings → zone-scoped pool → VM attach (includes template cache seed / reuse / survive) → template cache negative (last).

| Command | What it runs |
| --------- | -------------- |
| `run_tests.sh iscsi` | All 7 iSCSI suites + unified iSCSI report |
| `run_tests.sh nfs3` | All 7 NFS3 suites + unified NFS3 report |
| `run_tests.sh both` | iSCSI batch, then NFS3 batch + combined report |
| `run_tests.sh all` | `setup_zone`, then `both` (iSCSI before NFS3) |
| `run_tests.sh nfs3_workflow` | Single suite by tag (unchanged) |
| `run_tests.sh nfs3_template_cache_negative` | NFS3 template-cache boundary/negative suite |
| `run_tests.sh iscsi_template_cache_negative` | iSCSI template-cache boundary/negative suite |
| `run_tests.sh nfs3_qos` | NFS3 QoS offerings suite |
| `run_tests.sh iscsi_qos` | iSCSI QoS offerings suite |
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
test/integration/plugins/ontap/.venv/bin/python \
    test/integration/plugins/ontap/nose_compat.py --with-marvin \
    --marvin-config=test/integration/plugins/ontap/ontap.cfg \
    test/integration/plugins/ontap/nfs3/pool/test_pool_lifecycle.py -v

# By tag
PYTHONPATH=test/integration/plugins/ontap \
test/integration/plugins/ontap/.venv/bin/python \
    test/integration/plugins/ontap/nose_compat.py --with-marvin \
    --marvin-config=test/integration/plugins/ontap/ontap.cfg \
    -a tags=iscsi_workflow \
    test/integration/plugins/ontap/iscsi/pool/test_pool_lifecycle.py -v
```

> **Important:** `PYTHONPATH=test/integration/plugins/ontap` is always required. Test files import `ontap_test_base` from the parent directory.

> **Python 3.10+:** Always invoke legacy nose through `nose_compat.py`. The wrapper restores the `collections.Callable` alias that nose test discovery requires.

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

**Guard assertion at the start of every test that depends on a previous one**

Every test in a sequential workflow starts with an assertion that the previous step's resource exists. This produces a clear, readable failure message instead of a confusing `AttributeError`:
```python
def test_05_enable_storage_pool(self):
    self.assertIsNotNone(self.__class__.pool, "Pool absent — test_03 must pass first")
```

Isolated negative tests own everything they create, so they carry no such guard. In the pool lifecycle suites the two create-rejection negatives are numbered `test_01` (no aggregate space) and `test_02` (invalid capacitybytes) so that a bad create fails within a minute rather than after the full workflow.

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

**Shared iSCSI igroups**

iSCSI igroups are named from the host UUID and SVM, not from the storage pool.
Each iSCSI suite snapshots existing host igroups during `setUpClass()` and
checks that pool-only operations preserve that baseline. Tests may therefore
run while another ONTAP iSCSI pool uses the same SVM. The negative tests that
deliberately delete igroups still require exclusive SVM use and skip when
another ONTAP pool is present.

---

## Shared base — `ontap_test_base.py`

`OntapTestBase` provides everything individual test classes inherit:

| What | Purpose |
| ------ | --------- |
| `_setup_cloudstack_resources()` | Creates a test account, domain, disk offering; resolves zone/cluster/hosts |
| `tearDownClass()` | Best-effort cleanup: deletes pool (forced=True), volume, disk offering, account |
| `_poll_pool_state(pool_id, state, timeout)` | Polls `listStoragePools` until pool reaches the target state |
| `_create_volume(pool_id)` | Creates a CloudStack data volume on the given pool |
| `_delete_pool(pool_id, forced)` | Enters Maintenance then calls `deleteStoragePool` |
| `_parse_pool_details(pool)` | Extracts key→value pairs from the pool's `details` list |
| `OntapRestClient` | Thin HTTPS client for ONTAP REST API calls |

### `OntapRestClient` methods at a glance

| Method | What it checks | Used in |
| -------- | --------------- | --------- |
| `get_volume(name)` | FlexVol existence and state | All suites |
| `get_export_policy(name)` | NFS export policy existence | NFS3 suites |
| `get_data_lifs(svm_name)` | NFS data LIF count | NFS3 pool lifecycle |
| `get_igroup(svm_name, name)` | iSCSI igroup existence and initiator list | iSCSI suites |
| `list_luns_in_volume(svm_name, vol_name)` | LUNs present in a FlexVol | iSCSI volume/instance suites |
| `list_lun_maps_for_volume(svm_name, vol_name)` | Active LUN-maps for a volume | iSCSI pool-with-volumes/instance suites |
| `list_files_in_volume(vol_name)` | Files inside a FlexVol | NFS3 instance and pool lifecycle suites |
| `write_file_in_volume(vol_name, filename, size_bytes)` | Write incompressible data into a FlexVol file (no VM) | NFS3/iSCSI pool lifecycle suites |
| `delete_file_in_volume(vol_name, filename)` | Remove a FlexVol file | NFS3/iSCSI pool lifecycle suites |
| `get_lun(svm_name, lun_path)` | One LUN record, including its `qos_policy` | iSCSI QoS suite |
| `get_nfs_file(vol_name, file_name)` | One FlexVol file record, including its `qos_policy` | NFS3 QoS suite |
| `get_qos_policy(svm_name, policy_name)` | SVM QoS policy: `uuid`, `object_count`, `fixed` min/max throughput | QoS suites |
| `delete_qos_policy(svm_name, policy_name)` | Remove one SVM QoS policy; no-op when already gone | QoS suites (cleanup) |
| `is_all_flash()` | True when every cluster node is all-flash optimized (AFF); false means FAS | QoS suites |

---

## Test suite quick reference

| Suite | File | Tests | What it covers |
| ------- | ------ | ------- | --------------- |
| NFS3 Pool Lifecycle | `nfs3/pool/test_pool_lifecycle.py` | 21 | Create rejects for aggregate space, invalid capacitybytes, and a duplicate FlexVol name; grow to 300 TiB, reject grow past 300 TiB, safe shrink, disable, resize while disabled, enable, maintenance, grow and shrink in maintenance, delete, then re-create with a volume, reject shrink below used capacity, deploy a VM and attach the volume, cleanup, plus empty-pool deletion with a pre-deleted FlexVol or export policy |
| NFS3 Pool with Volumes | `nfs3/pool/test_pool_with_volumes.py` | 12 | Lifecycle with a live volume, deletion with a pre-deleted FlexVol or export policy, and host cases for an inactive libvirt pool, a read-only NFS mount, and a deleted mount point |
| NFS3 Zone-Scoped Pool | `nfs3/pool/test_zone_scoped_pool.py` | 6 | Zone-scoped pool create, grow and shrink with CloudStack/ONTAP/KVM verification, disable, enable, and delete, plus a pool created with capacity IOPS |
| NFS3 Volume Lifecycle | `nfs3/volume/test_volume_lifecycle.py` | 5 | Volume is metadata-only; FlexVol unchanged on delete |
| NFS3 QoS Offerings | `nfs3/qos/test_qos_offerings.py` | 29 | QoS policy on NFS files from disk offerings (max only, min and max, custom size, custom IOPS, provisioning types, shared policy, resize), compute offerings (fixed, own IOPS, bound disk, strict, custom, constrained), root plus data disks, rejected IOPS values, and a rejected resize that keeps the original policy; min IOPS apply on AFF and are rejected on FAS |
| NFS3 VM + Volume Attach | `nfs3/instance/test_vm_volume_attach.py` | 29 | Full VM lifecycle with stopped-VM qcow2 grow, guest SSH, volume and VM snapshots; ROOT on tagged pool seeds/reuses template cache, which survives VM delete |
| NFS3 Template Cache Negative | `nfs3/template/test_template_cache_negative.py` | 3 | Tag mismatch; undersized pool; out-of-band cache delete |
| iSCSI Pool Lifecycle | `iscsi/pool/test_pool_lifecycle.py` | 21 | Create rejects for aggregate space, invalid capacitybytes, and a duplicate FlexVol name; grow to 300 TiB, reject grow past 300 TiB, safe shrink, disable, resize while disabled, enable, maintenance, grow and shrink in maintenance, delete + igroups, then re-create with a volume (LUN), reject shrink below used capacity, deploy a VM and attach the volume, cleanup, plus empty-pool deletion with a pre-deleted FlexVol or igroups |
| iSCSI Pool with Volumes | `iscsi/pool/test_pool_with_volumes.py` | 13 | Lifecycle with a live LUN, deletion with a pre-deleted FlexVol or igroups, maintenance with pre-deleted LUN maps, and host cases for a logged-out iSCSI session, an existing session during volume delete, and a replaced by-path symlink |
| iSCSI Zone-Scoped Pool | `iscsi/pool/test_zone_scoped_pool.py` | 6 | Zone-scoped pool create, grow and shrink with CloudStack/ONTAP verification, disable, enable, and delete, plus a pool created with capacity IOPS |
| iSCSI Volume Lifecycle | `iscsi/volume/test_volume_lifecycle.py` | 5 | LUN created per CS volume; LUN removed on delete |
| iSCSI QoS Offerings | `iscsi/qos/test_qos_offerings.py` | 29 | Same 29 cases as the NFS3 suite, with the QoS policy checked on the ONTAP LUN |
| iSCSI VM + Volume Attach | `iscsi/instance/test_vm_volume_attach.py` | 29 | Full VM lifecycle; stopped-VM LUN grow, LUN-map checks, guest SSH, volume and VM snapshots; ROOT on tagged pool seeds/reuses `cs_tmpl_*` LUN cache |
| iSCSI Template Cache Negative | `iscsi/template/test_template_cache_negative.py` | 3 | Tag mismatch; undersized pool; out-of-band cache delete |

For the goal, dependencies, and exact success criteria of every individual test, see [TEST_CASES.md](TEST_CASES.md).

---

## QoS offering suites

`nfs3/qos/test_qos_offerings.py` and `iscsi/qos/test_qos_offerings.py` check that the IOPS on a CloudStack disk or compute offering end up as an ONTAP QoS policy on the right object: the NFS file or the iSCSI LUN. Each test creates its own offerings, creates a volume or VM, and asserts both sides: the volume's `miniops` / `maxiops` in CloudStack, and the policy name and throughput on the ONTAP object.

The 29 cases live once in `helpers/qos_offerings.py`. Each protocol file is a small subclass that sets the protocol and pool URL scheme, so a change to a case applies to both suites. See Suites 13 and 14 in [TEST_CASES.md](TEST_CASES.md) for every case.

| Area | Cases |
| ------ | ------- |
| Disk offerings | no IOPS, max only, min and max, min only, custom size, custom IOPS, thin/sparse/fat, shared policy, resize to a higher max, resize to no IOPS |
| Rejected input | min above max on the offering, min of 0 on the offering, min above max on the volume, min above the pool IOPS capacity, and a resize the driver rejects (the volume keeps its original policy and IOPS, and no replacement policy is left) |
| Compute offerings | fixed with an override disk, own IOPS, bound to a disk, strict offering rejecting an override, custom, custom root disk, custom IOPS at deploy, constrained custom |
| Root and data disks | plain root with a QoS data disk, different policies on root and data, hypervisor rate limits creating no policy |

### AFF and FAS

The suite asks ONTAP which platform it is running on (`OntapRestClient.is_all_flash()`):

- **AFF:** a minimum IOPS above 0 is applied. The test checks the policy `cs_<min>_to_<max>_iops_<svm>` on the object.
- **FAS:** ONTAP supports only a maximum limit, so the plugin rejects a minimum above 0 before creating a policy. The same tests then expect `createVolume` to fail and expect no policy to be left behind.
- The API error for that failure only says the volume create failed. The test finds the real reason in `vmops.log` on the management server.

### Extra requirements

- **Management server SSH (FAS only):** the log check connects to `mgtSvr[0].mgtSvrIp` from `ontap.cfg`, using the username and password of the first host under `zones[0].pods[0].clusters[0].hosts[0]`. It needs `paramiko` from the venv. If it is missing or the login fails, the FAS cases fail.
- **MySQL access:** the API rejects a disk offering with a minimum of 0, so for max-only offerings the test sets `min_iops` / `max_iops` in the `disk_offering` table afterwards. This uses the `dbSvr` entry in `ontap.cfg`.
- **VM cases:** need a ready KVM template and a routing host in the cluster, plus a guest network in an advanced zone. Without them those cases skip.
- **Pool IOPS capacity cases:** `test_16_reject_pool_iops_capacity` and `test_29_rejected_resize_keeps_original_qos` each create and delete a second pool with `capacityiops=100`. Both skip on FAS.
- **Disabled protocol:** the suite skips when its protocol has `enabled` set to false under `storagePool.protocols` in `ontap.cfg`.

### Cleanup

Each test removes its VMs and volumes, then the ONTAP LUN or file, then any `cs_` policy with no objects left. `tearDownClass` force-deletes the pool and its FlexVol. The NFS3 suite also unmounts the pool on the KVM hosts first and deletes the export policy.

```bash
bash test/integration/plugins/ontap/run_tests.sh nfs3_qos
bash test/integration/plugins/ontap/run_tests.sh iscsi_qos
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
| --------- | ------------- | ----- |
| `paramiko … has no SHA-1 key exchange` / guest SSH fails on CentOS 5.5 | Paramiko 4.x is installed; the guest sshd only offers SHA-1 | Re-run `bash test/integration/plugins/ontap/setup_env.sh` and run tests with `test/integration/plugins/ontap/.venv` (`paramiko>=3.4,<4`) |
| `ModuleNotFoundError: No module named 'ontap_test_base'` | Missing `PYTHONPATH` prefix | Prefix every run with `PYTHONPATH=test/integration/plugins/ontap` |
| `Marvin Init Failed` | CloudStack API unreachable | Check `mgtSvrIp:8096` is reachable; restart `cloudstack-management` |
| `Lost connection to MySQL` | MySQL not accepting remote connections | Enable remote MySQL access (see Prerequisites §3) |
| `sh: python: command not found` (repeated) | Marvin internal call — harmless on macOS | Ignore; Marvin Init still succeeds |
| Pool state never reaches `Maintenance` | KVM agent not responding | Check `cloudstack-agent` on KVM host; verify host is connected in CloudStack UI |
| iSCSI `test_24` error 530 | KVM guest does not ACK SCSI hot-unplug | Detach of the data LUN from a running VM; see TEST_CASES.md Suite 10 `test_24_detach_volume_from_vm` |
| ONTAP REST `401 Unauthorized` | Wrong credentials in `ontap.cfg` | Verify `username`/`password` under `ontap` section |
| QoS suite on FAS: `createVolume` fails when a minimum IOPS is set | Expected: FAS supports only a maximum IOPS limit | None; the QoS cases assert this rejection on FAS and expect no policy left behind |
| QoS FAS case fails with `NO_JAVA`, `NO_LOG`, or `ontap.cfg has no management server SSH login` | The test cannot read `vmops.log` on the management server | Check `mgtSvr[0].mgtSvrIp` and the first host's `username`/`password` in `ontap.cfg`, and that the venv has `paramiko` |
| QoS VM cases skip with `No ready KVM template` / `Advanced zone has no guest network` | No ready template, routing host, or guest network for the QoS account | Run `setup_zone`, or wait for the template to download |
| `ISCSI protocol is not enabled on SVM` / many pool tests EXCEPTION then FAIL | SVM iSCSI service off or no `data_iscsi` LIF | Enable iSCSI on the SVM and assign `default-data-iscsi` (or `default-data-blocks`) to at least one data LIF; `run_tests.sh` now fails this check before Marvin starts |
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
