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

# ONTAP Integration Test Cases

Complete reference for all 106 test cases across 12 test suites.
Each suite is sequential — tests must run in numbered order; each step builds on state created by the previous step.

---

## How to read the tables

| Column | Meaning |
|--------|---------|
| **Test method** | Exact Python method name |
| **Goal** | What CloudStack workflow step is being exercised |
| **Depends on** | Which earlier tests must have passed (class state they consume) |
| **CloudStack success criteria** | What the CS API must return for the test to pass |
| **ONTAP success criteria** | What the ONTAP REST API must show for the test to pass |
| **Type** | `positive` = happy path, `negative` = tests a rejection/error condition, `cleanup` = teardown step |

---

## Suite 1 — NFS3 Pool Lifecycle

**File:** `nfs3/pool/test_pool_lifecycle.py`
**Class:** `TestOntapNFS3PrimaryStorageWorkflow`
**Tag:** `nfs3_workflow`
**Total:** 8 tests | **Scope:** cluster-scoped NFS3 pool, no volumes for tests 01–06

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_primary_storage_pool` | Create a cluster-scoped NFS3 primary storage pool | setUpClass (zone, cluster, account) | `pool.state == "Up"`, `pool.type == "NetworkFilesystem"`, `nfsmountopts` contains `vers=3` | FlexVol exists and `state == "online"`, export policy exists with each cluster host IP as a rule, at least one NFS data LIF present on SVM | positive |
| 02 | `test_02_disable_storage_pool` | Disable the pool (admin operation) | test_01 (`pool`) | `pool.state == "Disabled"` | FlexVol still `online`; export policy still present | positive |
| 03 | `test_03_enable_storage_pool` | Re-enable the pool | test_02 | `pool.state == "Up"` | FlexVol still `online`; export policy still present | positive |
| 04 | `test_04_enter_maintenance_mode` | Put pool into maintenance (drains new volume allocations) | test_03 | `pool.state == "Maintenance"` | FlexVol still `online`; export policy still present (maintenance is CS-only state) | positive |
| 05 | `test_05_cancel_maintenance_mode` | Cancel maintenance, return pool to service | test_04 | `pool.state == "Up"` | FlexVol still `online`; export policy still present | positive |
| 06 | `test_06_delete_pool_from_maintenance` | Enter maintenance then permanently delete the pool | test_05 | Pool no longer returned by `listStoragePools` (CS 431 error expected on ID lookup) | FlexVol deleted (not found by `GET /api/storage/volumes?name=<pool_name>`); export policy deleted | positive |
| 07 | `test_07_create_volume_on_pool` | Create a second fresh pool and allocate a CloudStack data volume on it | test_06 (pool deleted; creates new pool) | New `pool.state == "Up"`; `createVolume` returns non-None volume object | FlexVol `online` after volume allocation; export policy present | positive |
| 08 | `test_08_delete_volume_and_pool` | Delete the volume then force-delete the pool | test_07 (`pool`, `volume`) | Volume no longer listed; pool no longer listed | FlexVol deleted; export policy deleted | positive |

---

## Suite 2 — NFS3 Pool with Volumes

**File:** `nfs3/pool/test_pool_with_volumes.py`
**Class:** `TestOntapNFS3PoolWithVolumes`
**Tag:** `nfs3_with_volumes`
**Total:** 7 tests | **Scope:** cluster-scoped NFS3 pool with a live CloudStack volume throughout

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_pool_and_volume` | Create NFS3 pool and immediately allocate a data volume | setUpClass | `pool.state == "Up"`, volume object non-None | FlexVol `online`; export policy present | positive |
| 02 | `test_02_disable_pool_volume_survives` | Disable pool while a volume exists — volume must survive | test_01 (`pool`, `volume`) | `pool.state == "Disabled"`; volume still listed in `listVolumes` | FlexVol still `online` | positive |
| 03 | `test_03_enable_pool_volume_intact` | Re-enable pool with volume present | test_02 | `pool.state == "Up"`; volume still listed | FlexVol still `online` | positive |
| 04 | `test_04_enter_maintenance_volume_present` | Enter maintenance while volume present | test_03 | `pool.state == "Maintenance"`; volume still listed | FlexVol still `online` | positive |
| 05 | `test_05_cancel_maintenance_with_volume` | Cancel maintenance with volume — verifies the NFS3 cancel-maintenance fix | test_04 | `pool.state == "Up"`; volume still listed | FlexVol still `online` | positive |
| 06 | `test_06_forced_false_delete_rejected` | Attempt to delete pool (forced=False) with volume present — must be rejected | test_05 | `deleteStoragePool(forced=False)` raises `CloudstackAPIException`; pool still listed in `Maintenance` state | FlexVol still `online`; no ONTAP objects removed | negative |
| 07 | `test_07_force_delete_pool_and_cleanup` | Cancel maintenance, delete volume, then force-delete pool | test_06 | Pool no longer listed; volume no longer listed | FlexVol deleted; export policy deleted | cleanup |

---

## Suite 3 — NFS3 Zone-Scoped Pool

**File:** `nfs3/pool/test_zone_scoped_pool.py`
**Class:** `TestOntapZoneScopedPool`
**Tag:** `zone_pool`
**Total:** 4 tests | **Scope:** zone-scoped NFS3 pool (scope=ZONE, all hosts in zone connected)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_zone_scoped_pool` | Create a zone-scoped NFS3 pool; CloudStack calls `attachZone()` to connect all eligible KVM hosts | setUpClass | `pool.state == "Up"` | FlexVol `online`; export policy exists and contains **every** cluster host IP; at least one NFS data LIF present | positive |
| 02 | `test_02_disable_zone_scoped_pool` | Disable the zone-scoped pool | test_01 (`pool`) | `pool.state == "Disabled"` | FlexVol unchanged; export policy unchanged | positive |
| 03 | `test_03_enable_zone_scoped_pool` | Re-enable the zone-scoped pool | test_02 | `pool.state == "Up"` | FlexVol unchanged; export policy unchanged | positive |
| 04 | `test_04_delete_zone_scoped_pool` | Enter maintenance and force-delete the zone-scoped pool | test_03 | Pool no longer listed | FlexVol deleted; export policy deleted | positive |

---

## Suite 4 — NFS3 Volume Lifecycle

**File:** `nfs3/volume/test_volume_lifecycle.py`
**Class:** `TestOntapNFS3VolumeLifecycle`
**Tag:** `nfs3_volume`
**Total:** 5 tests | **Scope:** NFS3 CloudStack volume create/delete semantics (NFS3 volumes are metadata-only in CS)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_pool_and_volume` | Create NFS3 pool and allocate a CloudStack data volume | setUpClass | `pool.state == "Up"`; volume object non-None | FlexVol `online` after volume allocation; export policy present — **no new ONTAP object per volume** (FlexVol is shared) | positive |
| 02 | `test_02_delete_volume` | Delete the CS data volume — for NFS3 only the CS record is removed | test_01 (`pool`, `volume`) | Volume no longer listed in `listVolumes` | FlexVol still `online` and **unaffected**; export policy still present | positive |
| 03 | `test_03_recreate_volume_for_delete_tests` | Re-create a volume on the pool (setup for negative tests 04–05) | test_02 | New volume object non-None | FlexVol still `online` | positive |
| 04 | `test_04_forced_false_delete_with_volume_fails` | Enter maintenance then attempt `deleteStoragePool(forced=False)` while volume exists — must be rejected | test_03 (`pool`, `volume`) | `deleteStoragePool(forced=False)` raises `CloudstackAPIException`; pool still in `Maintenance` state | No ONTAP objects removed | negative |
| 05 | `test_05_delete_volume_and_force_delete_pool` | Delete volume from Maintenance, then force-delete pool | test_04 | Volume no longer listed; pool no longer listed | FlexVol deleted; export policy deleted | positive |

---

## Suite 5 — NFS3 VM + Volume Attach

**File:** `nfs3/instance/test_vm_volume_attach.py`
**Class:** `TestOntapVMVolumeAttach`
**Tag:** `vm_volume_workflow`
**Total:** 26 tests | **Scope:** end-to-end — NFS3 pool, data volume, running VM (ROOT on the ONTAP pool via a tagged compute offering), primary template cache seed / reuse / survive VM delete, attach/detach, guest SSH, volume snapshots, and VM snapshots. Guest login uses `guest_ssh.py` (paramiko) against the CentOS 5.5 template.

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_nfs3_pool` | Create NFS3 ONTAP primary storage pool tagged `<storagePoolTags>-tmpl-cache` | setUpClass (zone, cluster, template, tagged SO) | `pool.state == "Up"` | FlexVol `online`; export policy present | positive |
| 02 | `test_02_create_ontap_data_volume` | Allocate a CloudStack data volume on the ONTAP pool | test_01 (`pool`) | Volume non-None and listed in `listVolumes` | FlexVol still `online` | positive |
| 03 | `test_03_deploy_vm` | Deploy a VM with the tagged SO — ROOT on ONTAP; seeds template cache | test_02 (`pool`, `volume`) | `vm.state == "Running"`; ROOT `storageid` = pool; `template_spool_ref` Ready/DOWNLOADED | Cache file present at spool `install_path` | positive |
| 03a | `test_03a_deploy_second_vm_reuses_template_cache` | Deploy VM-2 — reuses cache | test_03 | VM-2 Running; ROOT on pool; still exactly one `template_spool_ref` | Same cache file (no second cache) | positive |
| 03b | `test_03b_expunge_second_vm_template_cache_survives` | Expunge VM-2 — cache must remain (lazy GC) | test_03a | spool_ref still Ready | Cache file still present | positive |
| 04 | `test_04_attach_volume_to_vm` | Attach the ONTAP data volume to the running VM (hot-plug) | test_03 (`vm`, `volume`) | `volume.virtualmachineid == vm.id`; `attachVolume` job succeeds | FlexVol `online`; after attach, a data file matching volume UUID present in FlexVol (`list_files_in_volume`) | positive |
| 05 | `test_05_stop_vm_export_retained` | Stop the running VM with volume attached | test_04 | `vm.state == "Stopped"` | FlexVol still `online`; NFS export policy still present | positive |
| 06 | `test_06_start_vm_volume_accessible` | Start the stopped VM | test_05 | `vm.state == "Running"` | FlexVol still `online` | positive |
| 07 | `test_07_open_ssh_and_write_root_data_files` | Port-forward SSH to the guest and write ROOT and DATA marker files | test_06 (`vm`, `volume`, guest network) | ROOT and DATA both on the ONTAP pool; DATA still attached; SSH login succeeds; both markers read back | FlexVol still `online` | positive |
| 08 | `test_08_root_volume_snapshot_create` | VS-01: snapshot the ROOT volume of the running VM | test_07 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot | positive |
| 09 | `test_09_data_volume_snapshot_create` | VS-03: snapshot the attached DATA volume | test_08 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot | positive |
| 10 | `test_10_volume_revert_running_vm_rejected` | VS-19: revert a volume snapshot while the VM is Running | test_09 | Revert is rejected | FlexVol unchanged | negative |
| 11 | `test_11_root_volume_revert` | VS-11: write A, snapshot ROOT, write B, stop, revert, start | test_10 | After revert and start, ROOT marker is A | New FlexVol snapshot for the snapshot; revert snapshot removed in tearDown | positive |
| 12 | `test_12_data_volume_revert` | Write A, snapshot DATA, write B, stop, revert, start | test_11 | After revert and start, DATA marker is A | Same as test_11 for the DATA volume | positive |
| 13 | `test_13_vm_snapshot_create_running` | VMS-01: VM snapshot of the running VM (kept for test_15) | test_12 | VM snapshot `Ready`; VM stays `Running` | One new FlexVol snapshot | positive |
| 14 | `test_14_vm_snapshot_create_stopped` | VMS-03: stop, VM snapshot, start (kept for test_15) | test_13 | VM snapshot `Ready` | One new FlexVol snapshot | positive |
| 15 | `test_15_vm_snapshot_s1_running_s2_stopped_revert` | VMS-09: revert to the test_13 snapshot, then to the test_14 snapshot | test_13, test_14 | Each revert finishes; guest file matches that snapshot | FlexVol snapshots for both VM snapshots until tearDown | positive |
| 16 | `test_16_vm_snapshot_duplicate_requests` | VMS-53: two VM snapshot requests for the same VM at once | test_15 | Both snapshots `Ready` (a rejected request is retried) | Two new FlexVol snapshots | positive |
| 17 | `test_17_deploy_second_vm` | Deploy VM-B whose ROOT disk is on the same ONTAP pool | test_16 | VM-B `Running`; ROOT `storageid` = pool | ROOT file present on the shared FlexVol | positive |
| 18 | `test_18_volume_revert_leaves_other_vm_untouched` | VS-28: revert VM-A's ROOT | test_17 | VM-A's ROOT marker is restored; VM-B's file is unchanged | FlexVol stays `online` | positive |
| 19 | `test_19_concurrent_vm_snapshots_two_vms` | VMS-54: VM snapshots of both VMs at the same time | test_18 | Both VM snapshots `Ready` | Two new FlexVol snapshots | positive |
| 20 | `test_20_concurrent_volume_snapshot_and_revert` | Volume snapshot of VM-A's ROOT while VM-B's ROOT reverts | test_19 | Snapshot `BackedUp` and revert both finish (retried on error 530) | New FlexVol snapshot for the volume snapshot | positive |
| 21 | `test_21_concurrent_vm_snapshot_and_revert` | VM snapshot of VM-A while VM-B reverts to its VM snapshot | test_20 | Both operations finish; VM-A snapshot `Ready` | New FlexVol snapshot for VM-A | positive |
| 22 | `test_22_detach_volume_from_vm` | Hot-detach the ONTAP volume from the running VM (TDS Detach NFS3) | test_21 (`vm`, `volume`) | `volume.virtualmachineid` cleared; `volume.state == "Ready"` | FlexVol still `online`; data file **still present** (NFS3: file persists until `deleteVolume`, not on detach) | positive |
| 23 | `test_23_destroy_vm_with_vm_snapshot` | VMS-38: expunge VM-B while it still has a VM snapshot | test_21 (VM-B snapshot) | VM-B no longer listed | VM-B's FlexVol snapshot removed | positive |
| 24 | `test_24_destroy_vm_and_cleanup` | Destroy VM (expunge), delete volume, enter maintenance, force-delete pool | test_22, test_23 | VM no longer listed; spool_ref still Ready after VM expunge; volume no longer listed; pool no longer listed | Cache file present after VM expunge; FlexVol deleted; export policy deleted | cleanup |

---

## Suite 6 — iSCSI Pool Lifecycle

**File:** `iscsi/pool/test_pool_lifecycle.py`
**Class:** `TestOntapISCSIPoolLifecycle`
**Tag:** `iscsi_workflow`
**Total:** 8 tests | **Scope:** cluster-scoped iSCSI pool, no volumes for tests 01–06

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_primary_storage_pool` | Create a cluster-scoped iSCSI primary storage pool | setUpClass | `pool.state == "Up"`, `pool.type == "OntapiSCSI"` | FlexVol `online`; one igroup per cluster host (named `cs_{svmName}_{hostShortName}`) with host IQN as initiator | positive |
| 02 | `test_02_disable_storage_pool` | Disable the pool | test_01 (`pool`) | `pool.state == "Disabled"` | FlexVol still `online` | positive |
| 03 | `test_03_enable_storage_pool` | Re-enable the pool | test_02 | `pool.state == "Up"` | FlexVol still `online` | positive |
| 04 | `test_04_enter_maintenance_mode` | Put pool into maintenance | test_03 | `pool.state == "Maintenance"` | FlexVol still `online`; igroups unchanged | positive |
| 05 | `test_05_cancel_maintenance_mode` | Cancel maintenance | test_04 | `pool.state == "Up"` | FlexVol still `online` | positive |
| 06 | `test_06_enter_maintenance_and_delete_pool` | Enter maintenance then force-delete the pool | test_05 | Pool no longer listed | FlexVol deleted; all igroups for cluster hosts deleted | positive |
| 07 | `test_07_create_volume_on_pool` | Create a second fresh pool and allocate a CloudStack data volume (creates a LUN) | test_06 (new pool) | New `pool.state == "Up"`; volume object non-None | FlexVol `online`; ≥1 LUN present inside FlexVol (`list_luns_in_volume`) | positive |
| 08 | `test_08_delete_volume_and_pool` | Delete the volume (removes LUN), enter maintenance, force-delete pool | test_07 (`pool`, `volume`) | Volume no longer listed; pool no longer listed | LUN no longer in FlexVol; FlexVol deleted; igroups deleted | positive |

---

## Suite 7 — iSCSI Pool with Volumes

**File:** `iscsi/pool/test_pool_with_volumes.py`
**Class:** `TestOntapISCSIPoolWithVolumes`
**Tag:** `iscsi_workflow`
**Total:** 7 tests | **Scope:** cluster-scoped iSCSI pool with a live CloudStack volume (LUN) throughout

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_pool_and_volume` | Create iSCSI pool and allocate a data volume (creates LUN) | setUpClass | `pool.state == "Up"`; volume non-None | FlexVol `online`; ≥1 LUN in FlexVol | positive |
| 02 | `test_02_disable_pool_volume_survives` | Disable pool with volume present | test_01 (`pool`, `volume`) | `pool.state == "Disabled"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 03 | `test_03_enable_pool_volume_intact` | Re-enable pool with volume | test_02 | `pool.state == "Up"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 04 | `test_04_enter_maintenance_volume_present` | Enter maintenance with volume | test_03 | `pool.state == "Maintenance"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 05 | `test_05_cancel_maintenance_volume_present` | Cancel maintenance with volume (TDS iSCSI cancel maintenance) | test_04 | `pool.state == "Up"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 06 | `test_06_forced_false_delete_rejected` | Attempt `deleteStoragePool(forced=False)` with LUN-backed volume present — must be rejected | test_05 | `CloudstackAPIException` raised; pool still in `Maintenance` | No ONTAP objects removed | negative |
| 07 | `test_07_delete_volume_and_force_delete_pool` | Delete volume (LUN removed) then force-delete pool | test_06 (`pool`, `volume`) | Volume gone; pool gone | LUN removed; FlexVol deleted; igroups deleted | cleanup |

---

## Suite 8 — iSCSI Zone-Scoped Pool

**File:** `iscsi/pool/test_zone_scoped_pool.py`
**Class:** `TestOntapISCSIZoneScopedPool`
**Tag:** `iscsi_zone_pool`
**Total:** 4 tests | **Scope:** zone-scoped iSCSI pool (scope=ZONE)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_zone_scoped_pool` | Create a zone-scoped iSCSI pool; CS calls `attachZone()` to connect all eligible KVM hosts | setUpClass | `pool.state == "Up"` | FlexVol `online`; igroup per cluster host, each with host IQN as initiator | positive |
| 02 | `test_02_disable_zone_scoped_pool` | Disable pool | test_01 (`pool`) | `pool.state == "Disabled"` | FlexVol unchanged; igroups unchanged | positive |
| 03 | `test_03_enable_zone_scoped_pool` | Re-enable pool | test_02 | `pool.state == "Up"` | FlexVol unchanged; igroups unchanged | positive |
| 04 | `test_04_delete_zone_scoped_pool` | Enter maintenance then delete pool | test_03 | Pool no longer listed | FlexVol deleted; all igroups deleted | positive |

---

## Suite 9 — iSCSI Volume Lifecycle

**File:** `iscsi/volume/test_volume_lifecycle.py`
**Class:** `TestOntapISCSIVolumeLifecycle`
**Tag:** `iscsi_volume`
**Total:** 5 tests | **Scope:** iSCSI CloudStack volume create/delete semantics (each CS volume maps to an ONTAP LUN)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_pool_and_volume` | Create iSCSI pool and allocate a data volume — a LUN is created inside the pool's FlexVol | setUpClass | `pool.state == "Up"`; volume non-None | FlexVol `online`; ≥1 LUN in FlexVol (`list_luns_in_volume`) | positive |
| 02 | `test_02_delete_volume` | Delete the volume — the LUN is removed from the FlexVol | test_01 (`pool`, `volume`) | Volume no longer listed | LUN no longer in FlexVol; FlexVol itself still `online` | positive |
| 03 | `test_03_recreate_volume_for_delete_tests` | Re-create a volume (LUN re-created) — setup for negative tests | test_02 | New volume non-None | LUN present in FlexVol again | positive |
| 04 | `test_04_forced_false_delete_with_volume_fails` | Enter maintenance then attempt `deleteStoragePool(forced=False)` with LUN present — must be rejected | test_03 (`pool`, `volume`) | `CloudstackAPIException` raised; pool still in `Maintenance` | No ONTAP objects removed | negative |
| 05 | `test_05_delete_volume_and_force_delete_pool` | Delete volume (LUN removed) then force-delete pool | test_04 | Volume gone; pool gone | LUN removed; FlexVol deleted; igroups deleted | positive |

---

## Suite 10 — iSCSI VM + Volume Attach

**File:** `iscsi/instance/test_vm_volume_attach.py`
**Class:** `TestOntapVMVolumeAttachISCSI`
**Tag:** `iscsi_vm_workflow`
**Total:** 26 tests | **Scope:** end-to-end — iSCSI pool, data volume (LUN), running VM (ROOT on the ONTAP pool via a tagged compute offering), `cs_tmpl_<templateId>` LUN cache seed / reuse / survive VM delete, attach/stop/start/detach, guest SSH, volume snapshots, and VM snapshots. LUN-map checks are limited to the data volume's LUN. Guest login uses `guest_ssh.py` (paramiko) against the CentOS 5.5 template.

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_iscsi_pool` | Create iSCSI ONTAP primary storage pool tagged `<storagePoolTags>-tmpl-cache` | setUpClass (tagged SO) | `pool.state == "Up"`, `pool.type == "OntapiSCSI"` | FlexVol `online`; igroup per cluster host with host IQN | positive |
| 02 | `test_02_create_ontap_data_volume` | Allocate a CloudStack data volume (creates a LUN in the FlexVol) | test_01 (`pool`) | Volume non-None | ≥1 LUN in FlexVol | positive |
| 03 | `test_03_deploy_vm` | Deploy VM with the tagged SO — ROOT on ONTAP; seeds `cs_tmpl_*`; verify 0 data-volume LUN-maps before attach | test_02 (`volume`) | `vm.state == "Running"`; ROOT on pool; spool_ref Ready (`local_path` = LUN uuid) | Exactly one `/vol/<flex>/cs_tmpl_<id>` LUN; 0 data-volume LUN-maps | positive |
| 03a | `test_03a_deploy_second_vm_reuses_template_cache` | Deploy VM-2 — reuse cache | test_03 | VM-2 Running; ROOT on pool; still one spool_ref | Still one `cs_tmpl_*`; non-cache LUN count +1 | positive |
| 03b | `test_03b_expunge_second_vm_template_cache_survives` | Expunge VM-2 — cache LUN remains | test_03a | spool_ref still Ready | VM-2 ROOT LUN gone (non-cache count back to baseline); `cs_tmpl_*` still present | positive |
| 04 | `test_04_attach_volume_to_vm` | Hot-attach the ONTAP iSCSI volume to the running VM — a LUN-map is created (TDS SN 27) | test_03 (`vm`, `volume`) | `volume.virtualmachineid == vm.id` | ≥1 LUN-map linking the LUN to the host's igroup | positive |
| 05 | `test_05_stop_vm_lun_unmapped` | Stop VM — LUN-maps must be removed (TDS VM Stop iSCSI) | test_04 | `vm.state == "Stopped"` | 0 LUN-maps for the data LUN; LUN itself **still present** in FlexVol | positive |
| 06 | `test_06_start_vm_lun_remapped` | Start VM — LUN-maps must be re-created (TDS VM Start iSCSI) | test_05 | `vm.state == "Running"` | ≥1 LUN-map re-created for the data LUN | positive |
| 07 | `test_07_open_ssh_and_write_root_data_files` | Port-forward SSH to the guest and write ROOT and DATA marker files | test_06 (`vm`, `volume`, guest network) | ROOT and DATA both on the ONTAP pool; DATA still attached; SSH login succeeds; both markers read back | Data LUN still mapped | positive |
| 08 | `test_08_root_volume_snapshot_create` | VS-01: snapshot the ROOT volume of the running VM | test_07 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot | positive |
| 09 | `test_09_data_volume_snapshot_create` | VS-03: snapshot the attached DATA volume | test_08 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot | positive |
| 10 | `test_10_volume_revert_running_vm_rejected` | VS-20: revert a volume snapshot while the VM is Running | test_09 | Revert is rejected | FlexVol unchanged | negative |
| 11 | `test_11_root_volume_revert` | VS-12: write A, snapshot ROOT, write B, stop, revert, start | test_10 | After revert and start, ROOT marker is A | New FlexVol snapshot for the snapshot; revert snapshot removed in tearDown | positive |
| 12 | `test_12_data_volume_revert` | Write A, snapshot DATA, write B, stop, revert, start | test_11 | After revert and start, DATA marker is A | Same as test_11 for the DATA LUN | positive |
| 13 | `test_13_vm_snapshot_create_running` | VM snapshot of the running VM, ROOT + DATA LUNs (kept for test_15) | test_12 | VM snapshot `Ready`; VM stays `Running` | One new FlexVol snapshot | positive |
| 14 | `test_14_vm_snapshot_create_stopped` | Stop, VM snapshot, start (kept for test_15) | test_13 | VM snapshot `Ready` | One new FlexVol snapshot | positive |
| 15 | `test_15_vm_snapshot_s1_running_s2_stopped_revert` | VMS-09: revert to the test_13 snapshot, then to the test_14 snapshot | test_13, test_14 | Each revert finishes; guest file matches that snapshot | FlexVol snapshots for both VM snapshots until tearDown | positive |
| 16 | `test_16_vm_snapshot_duplicate_requests` | VMS-53: two VM snapshot requests for the same VM at once | test_15 | Both snapshots `Ready` (a rejected request is retried) | Two new FlexVol snapshots | positive |
| 17 | `test_17_deploy_second_vm` | Deploy VM-B whose ROOT LUN is on the same ONTAP pool | test_16 | VM-B `Running`; ROOT on pool | One more non-cache LUN; `cs_tmpl_*` still one | positive |
| 18 | `test_18_volume_revert_leaves_other_vm_untouched` | VS-28: revert VM-A's ROOT | test_17 | VM-A's ROOT marker is restored; VM-B's file is unchanged | FlexVol stays `online`; both ROOT LUNs remain | positive |
| 19 | `test_19_concurrent_vm_snapshots_two_vms` | VMS-55: VM snapshots of both VMs at the same time | test_18 | Both VM snapshots `Ready` | Two new FlexVol snapshots | positive |
| 20 | `test_20_concurrent_volume_snapshot_and_revert` | Volume snapshot of VM-A's ROOT while VM-B's ROOT reverts | test_19 | Snapshot `BackedUp` and revert both finish (retried on error 530) | New FlexVol snapshot for the volume snapshot | positive |
| 21 | `test_21_concurrent_vm_snapshot_and_revert` | VM snapshot of VM-A while VM-B reverts to its VM snapshot | test_20 | Both operations finish; VM-A snapshot `Ready` | New FlexVol snapshot for VM-A | positive |
| 22 | `test_22_detach_volume_from_vm` | Hot-detach the iSCSI volume from the running VM (TDS Detach iSCSI) | test_21 (`vm`, `volume`) | `volume.virtualmachineid` cleared | 0 LUN-maps for the data LUN; LUN still in FlexVol | positive |
| 23 | `test_23_destroy_vm_with_vm_snapshot` | Expunge VM-B while it still has a VM snapshot | test_21 (VM-B snapshot) | VM-B no longer listed | VM-B ROOT LUN gone; `cs_tmpl_*` still present | positive |
| 24 | `test_24_destroy_vm_and_cleanup` | Destroy VM (expunge), delete volume, enter maintenance, delete pool | test_22, test_23 | VM gone; spool_ref still Ready after VM expunge; volume gone; pool gone | `cs_tmpl_*` present after VM expunge; FlexVol deleted; all LUNs and igroups deleted | cleanup |

---

## Suite 11 — NFS3 Template Cache Negative / Boundary

**File:** `nfs3/template/test_template_cache_negative.py`
**Class:** `TestOntapNfs3TemplateCacheNegative`
**Tag:** `nfs3_template_cache_negative`
**Total:** 3 tests | **Scope:** Boundary conditions for NFS3 primary template cache (isolated from happy path)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_tag_mismatch_does_not_seed_cache` | SO tags ≠ pool tags | setUpClass | Deploy may succeed elsewhere; ROOT not on ONTAP pool; no `template_spool_ref` for pool | No cache file for template on FlexVol | negative |
| 02 | `test_02_undersized_pool_deploy_fails` | Matching tags but `capacitybytes` ≪ template size | setUpClass | Deploy fails / never Running; spool_ref not Ready/DOWNLOADED | No cache file | negative |
| 03 | `test_03_deleted_cache_blocks_reuse` | Seed cache, delete file out-of-band, redeploy | setUpClass | spool_ref still Ready after ONTAP delete; second deploy fails | Cache file absent after delete | negative |

---

## Suite 12 — iSCSI Template Cache Negative / Boundary

**File:** `iscsi/template/test_template_cache_negative.py`
**Class:** `TestOntapIscsiTemplateCacheNegative`
**Tag:** `iscsi_template_cache_negative`
**Total:** 3 tests | **Scope:** Boundary conditions for iSCSI primary template cache (isolated from happy path)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_tag_mismatch_does_not_seed_cache` | SO tags ≠ pool tags | setUpClass | ROOT not on ONTAP pool; no `template_spool_ref` | No `cs_tmpl_*` LUN | negative |
| 02 | `test_02_undersized_pool_deploy_fails` | Matching tags but undersized capacity | setUpClass | Deploy fails; spool_ref not Ready/DOWNLOADED | No `cs_tmpl_*` LUN | negative |
| 03 | `test_03_deleted_cache_blocks_reuse` | Seed cache, delete LUN out-of-band, redeploy | setUpClass | spool_ref still Ready; second deploy fails | `cs_tmpl_*` absent after delete | negative |

---

## Cross-suite summary

| Suite | Protocol | Scope | Tests | Status |
|-------|---------|-------|-------|--------|
| NFS3 Pool Lifecycle | NFS3 | Cluster | 8 | ✅ |
| NFS3 Pool with Volumes | NFS3 | Cluster | 7 | ✅ |
| NFS3 Zone-Scoped Pool | NFS3 | Zone | 4 | ✅ |
| NFS3 Volume Lifecycle | NFS3 | Cluster | 5 | ✅ |
| NFS3 VM + Volume Attach | NFS3 | Cluster | 26 | ✅ includes guest SSH, volume snapshots, VM snapshots |
| NFS3 Template Cache Negative | NFS3 | Cluster | 3 | 🆕 |
| iSCSI Pool Lifecycle | iSCSI | Cluster | 8 | ✅ |
| iSCSI Pool with Volumes | iSCSI | Cluster | 7 | ✅ |
| iSCSI Zone-Scoped Pool | iSCSI | Zone | 4 | ✅ |
| iSCSI Volume Lifecycle | iSCSI | Cluster | 5 | ✅ |
| iSCSI VM + Volume Attach | iSCSI | Cluster | 26 | ✅ includes guest SSH, volume snapshots, VM snapshots |
| iSCSI Template Cache Negative | iSCSI | Cluster | 3 | 🆕 |
| **Total** | | | **106** | |
