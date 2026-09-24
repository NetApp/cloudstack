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

Complete reference for all 87 test cases across 10 test suites.
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
**Total:** 12 tests | **Scope:** cluster-scoped NFS3 pool, no-volume workflow through test 08 and isolated negative tests 01–02 and 11–12

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_reject_create_when_flexvol_name_exists` | Reject pool creation when ONTAP already has a FlexVol with the requested name | isolated | `CloudstackAPIException`; no CS pool created | Pre-existing FlexVol remains until test cleanup | negative |
| 02 | `test_02_reject_create_when_no_aggregate_space` | Reject pool creation when requested capacity exceeds every assigned online aggregate's available space | isolated | `CloudstackAPIException` containing `No suitable aggregates`; no CS pool created | No FlexVol created | negative |
| 03 | `test_03_create_primary_storage_pool` | Create a cluster-scoped NFS3 primary storage pool | setUpClass (zone, cluster, account) | `pool.state == "Up"`, `pool.type == "NetworkFilesystem"`, `nfsmountopts` contains `vers=3` | FlexVol exists and `state == "online"`, export policy exists with each cluster host IP as a rule, at least one NFS data LIF present on SVM | positive |
| 04 | `test_04_disable_storage_pool` | Disable the pool (admin operation) | test_06 | `pool.state == "Disabled"` | FlexVol still `online`; export policy still present | positive |
| 05 | `test_05_enable_storage_pool` | Re-enable the pool | test_08 | `pool.state == "Up"` | FlexVol still `online`; export policy still present | positive |
| 06 | `test_06_enter_maintenance_mode` | Put pool into maintenance (drains new volume allocations) | test_05 | `pool.state == "Maintenance"` | FlexVol still `online`; export policy still present (maintenance is CS-only state) | positive |
| 07 | `test_07_cancel_maintenance_mode` | Cancel maintenance, return pool to service | test_11 | `pool.state == "Up"` | FlexVol still `online`; export policy still present | positive |
| 08 | `test_08_delete_pool_from_maintenance` | Enter maintenance then permanently delete the original pool | test_07 | Pool no longer returned by `listStoragePools` (CS 431 error expected on ID lookup) | FlexVol and export policy deleted | positive |
| 09 | `test_09_create_volume_on_pool` | Create a fresh pool and allocate a CloudStack data volume | test_08 | New pool is `Up`; `createVolume` returns a volume | FlexVol `online`; export policy present | positive |
| 10 | `test_10_delete_volume_and_pool` | Detach and destroy the VM, delete the volume, then force-delete the pool | test_17 | VM destroyed; volume and pool no longer listed | FlexVol and export policy deleted | cleanup |
| 11 | `test_11_delete_pool_with_flexvol_predeleted` | Delete an empty pool after its FlexVol was removed directly from ONTAP | isolated | Pool removed successfully | FlexVol remains absent; export policy cleaned up | negative |
| 12 | `test_12_delete_pool_with_export_policy_predeleted` | Delete an empty pool after its export policy was removed directly from ONTAP | isolated | Pool removed successfully | FlexVol deleted; export policy remains absent | negative |

---

## Suite 2 — NFS3 Pool with Volumes

**File:** `nfs3/pool/test_pool_with_volumes.py`
**Class:** `TestOntapNFS3PoolWithVolumes`
**Tag:** `nfs3_with_volumes`
**Total:** 10 tests | **Scope:** cluster-scoped NFS3 pool with a live CloudStack volume, plus isolated negative workflows

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_pool_and_volume` | Create NFS3 pool and immediately allocate a data volume | setUpClass | `pool.state == "Up"`, volume object non-None | FlexVol `online`; export policy present | positive |
| 02 | `test_02_disable_pool_volume_survives` | Disable pool while a volume exists — volume must survive | test_01 (`pool`, `volume`) | `pool.state == "Disabled"`; volume still listed in `listVolumes` | FlexVol still `online` | positive |
| 03 | `test_03_enable_pool_volume_intact` | Re-enable pool with volume present | test_02 | `pool.state == "Up"`; volume still listed | FlexVol still `online` | positive |
| 04 | `test_04_enter_maintenance_volume_present` | Enter maintenance while volume present | test_03 | `pool.state == "Maintenance"`; volume still listed | FlexVol still `online` | positive |
| 05 | `test_05_cancel_maintenance_with_volume` | Cancel maintenance with volume — verifies the NFS3 cancel-maintenance fix | test_04 | `pool.state == "Up"`; volume still listed | FlexVol still `online` | positive |
| 06 | `test_06_forced_false_delete_rejected` | Attempt to delete pool (forced=False) with volume present — must be rejected | test_05 | `deleteStoragePool(forced=False)` raises `CloudstackAPIException`; pool still listed in `Maintenance` state | FlexVol still `online`; no ONTAP objects removed | negative |
| 07 | `test_07_force_delete_pool_and_cleanup` | Cancel maintenance, delete volume, then force-delete pool | test_06 | Pool no longer listed; volume no longer listed | FlexVol deleted; export policy deleted | cleanup |
| 08 | `test_08_delete_pool_with_volume_flexvol_missing` | Force-delete a pool with a CS volume after its FlexVol was removed directly from ONTAP | isolated | Pool removed; leftover volume record cleaned | FlexVol remains absent | negative |
| 09 | `test_09_delete_pool_with_volume_export_policy_missing` | Force-delete a pool with a CS volume after its export policy was removed directly from ONTAP | isolated | Pool removed; leftover volume record cleaned | FlexVol deleted; export policy remains absent | negative |
| 10 | `test_10_cancel_maintenance_after_volume_deleted` | Cancel maintenance after the pool's CS volume has been deleted | isolated | Pool returns to `Up`; volume absent | FlexVol online; export policy present | negative |

---

## Suite 3 — NFS3 Zone-Scoped Pool

**File:** `nfs3/pool/test_zone_scoped_pool.py`
**Class:** `TestOntapZoneScopedPool`
**Tag:** `zone_pool`
**Total:** 8 tests | **Scope:** zone-scoped NFS3 pool (scope=ZONE, all hosts in zone connected)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_zone_pool_rejected_when_flexvol_exists` | Reject zone-scoped pool creation when a same-name FlexVol exists | isolated | `CloudstackAPIException`; no CS pool created | Pre-existing FlexVol preserved until cleanup | negative |
| 02 | `test_02_create_zone_pool_rejected_when_no_aggregate_space` | Reject creation when requested capacity exceeds every online aggregate's free space | isolated | `CloudstackAPIException` containing `No suitable aggregates`; no pool created | No FlexVol created | negative |
| 03 | `test_03_create_zone_scoped_pool` | Create a zone-scoped NFS3 pool; CloudStack calls `attachZone()` to connect all eligible KVM hosts | setUpClass | `pool.state == "Up"` | FlexVol `online`; export policy exists and contains **every** cluster host IP; at least one NFS data LIF present | positive |
| 04 | `test_04_disable_zone_scoped_pool` | Disable the zone-scoped pool | test_05 | `pool.state == "Disabled"` | FlexVol unchanged; export policy unchanged | positive |
| 05 | `test_05_enable_zone_scoped_pool` | Re-enable the zone-scoped pool | test_04 | `pool.state == "Up"` | FlexVol unchanged; export policy unchanged | positive |
| 06 | `test_06_delete_zone_scoped_pool` | Enter maintenance and force-delete the zone-scoped pool | test_05 | Pool no longer listed | FlexVol deleted; export policy deleted | positive |
| 07 | `test_07_delete_zone_pool_with_flexvol_predeleted` | Delete an empty zone pool after its FlexVol was removed directly | isolated | Pool removed | FlexVol remains absent; export policy cleaned up | negative |
| 08 | `test_08_delete_zone_pool_with_export_policy_predeleted` | Delete an empty zone pool after its export policy was removed directly | isolated | Pool removed | FlexVol deleted; export policy remains absent | negative |

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
**Total:** 8 tests | **Scope:** end-to-end — NFS3 pool, data volume, running VM, attach/detach lifecycle

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_nfs3_pool` | Create NFS3 ONTAP primary storage pool | setUpClass (zone, cluster, template) | `pool.state == "Up"` | FlexVol `online`; export policy present | positive |
| 02 | `test_02_create_ontap_data_volume` | Allocate a CloudStack data volume on the ONTAP pool | test_01 (`pool`) | Volume non-None and listed in `listVolumes` | FlexVol still `online` | positive |
| 03 | `test_03_deploy_vm` | Deploy a VM using the first available ready KVM template | test_02 (`pool`, `volume`) | `vm.state == "Running"`; template auto-selected from `listTemplates` | n/a | positive |
| 04 | `test_04_attach_volume_to_vm` | Attach the ONTAP data volume to the running VM (hot-plug) | test_03 (`vm`, `volume`) | `volume.virtualmachineid == vm.id`; `attachVolume` job succeeds | FlexVol `online`; after attach, a data file matching volume UUID present in FlexVol (`list_files_in_volume`) | positive |
| 05 | `test_05_stop_vm_export_retained` | Stop the running VM with volume attached | test_04 | `vm.state == "Stopped"` | FlexVol still `online`; NFS export policy still present | positive |
| 06 | `test_06_start_vm_volume_accessible` | Start the stopped VM | test_05 | `vm.state == "Running"` | FlexVol still `online` | positive |
| 07 | `test_07_detach_volume_from_vm` | Hot-detach the ONTAP volume from the running VM (TDS Detach NFS3) | test_06 (`vm`, `volume`) | `volume.virtualmachineid` cleared; `volume.state == "Ready"` | FlexVol still `online`; data file **still present** (NFS3: file persists until `deleteVolume`, not on detach) | positive |
| 08 | `test_08_destroy_vm_and_cleanup` | Destroy VM (expunge), delete volume, enter maintenance, delete pool | test_07 | VM no longer listed; volume no longer listed; pool no longer listed | FlexVol deleted; export policy deleted | cleanup |

---

## Suite 6 — iSCSI Pool Lifecycle

**File:** `iscsi/pool/test_pool_lifecycle.py`
**Class:** `TestOntapISCSIPoolLifecycle`
**Tag:** `iscsi_workflow`
**Total:** 12 tests | **Scope:** cluster-scoped iSCSI pool, no-volume workflow through test 08 and isolated negative tests 01–02 and 11–12

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_reject_create_when_flexvol_name_exists` | Reject pool creation when ONTAP already has a FlexVol with the requested name | isolated | `CloudstackAPIException`; no CS pool created | Pre-existing FlexVol remains until cleanup | negative |
| 02 | `test_02_reject_create_when_no_aggregate_space` | Reject pool creation when requested capacity exceeds every assigned online aggregate's available space | isolated | `CloudstackAPIException` containing `No suitable aggregates`; no CS pool created | No FlexVol created | negative |
| 03 | `test_03_create_primary_storage_pool` | Create a cluster-scoped iSCSI primary storage pool | setUpClass | `pool.state == "Up"`, `pool.type == "Iscsi"` | FlexVol `online`; shared `cs_{hostUuid}_{svmName}` igroups unchanged from suite-start baseline | positive |
| 04 | `test_04_disable_storage_pool` | Disable the pool | test_06 | `pool.state == "Disabled"` | FlexVol still `online` | positive |
| 05 | `test_05_enable_storage_pool` | Re-enable the pool | test_08 | `pool.state == "Up"` | FlexVol still `online` | positive |
| 06 | `test_06_enter_maintenance_mode` | Put pool into maintenance | test_05 | `pool.state == "Maintenance"` | FlexVol still `online`; igroups unchanged | positive |
| 07 | `test_07_cancel_maintenance_mode` | Cancel maintenance | test_11 | `pool.state == "Up"` | FlexVol still `online` | positive |
| 08 | `test_08_enter_maintenance_and_delete_pool` | Enter maintenance then delete the original pool | test_07 | Pool no longer listed | FlexVol and test-pool LUN maps deleted; shared igroup baseline restored | positive |
| 09 | `test_09_create_volume_on_pool` | Create a fresh pool and allocate a CloudStack volume | test_08 | New pool is `Up`; `createVolume` returns a volume | FlexVol `online`; at least one LUN present | positive |
| 10 | `test_10_delete_volume_and_pool` | Detach and destroy the VM, delete the volume, then force-delete the pool | test_17 | VM destroyed; volume and pool no longer listed | LUN, FlexVol, and test-pool maps deleted; shared igroup baseline restored | cleanup |
| 11 | `test_11_delete_pool_with_flexvol_predeleted` | Delete an empty pool after its FlexVol was removed directly from ONTAP | isolated | Pool removed successfully | FlexVol remains absent; igroups cleaned up | negative |
| 12 | `test_12_delete_pool_with_igroups_predeleted` | Delete an empty pool after host igroups were removed directly from ONTAP | isolated | Pool removed successfully | FlexVol deleted; igroups remain absent | negative |

---

## Suite 7 — iSCSI Pool with Volumes

**File:** `iscsi/pool/test_pool_with_volumes.py`
**Class:** `TestOntapISCSIPoolWithVolumes`
**Tag:** `iscsi_workflow`
**Total:** 11 tests | **Scope:** cluster-scoped iSCSI pool with a live CloudStack volume (LUN), plus isolated negative workflows

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_pool_and_volume` | Create iSCSI pool and allocate a data volume (creates LUN) | setUpClass | `pool.state == "Up"`; volume non-None | FlexVol `online`; ≥1 LUN in FlexVol | positive |
| 02 | `test_02_disable_pool_volume_survives` | Disable pool with volume present | test_01 (`pool`, `volume`) | `pool.state == "Disabled"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 03 | `test_03_enable_pool_volume_intact` | Re-enable pool with volume | test_02 | `pool.state == "Up"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 04 | `test_04_enter_maintenance_volume_present` | Enter maintenance with volume | test_03 | `pool.state == "Maintenance"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 05 | `test_05_cancel_maintenance_volume_present` | Cancel maintenance with volume | test_04 | `pool.state == "Up"`; volume still listed | FlexVol still `online`; LUN still present | positive |
| 06 | `test_06_forced_false_delete_rejected` | Attempt `deleteStoragePool(forced=False)` with LUN-backed volume present — must be rejected | test_05 | `CloudstackAPIException` raised; pool still in `Maintenance` | No ONTAP objects removed | negative |
| 07 | `test_07_delete_volume_and_force_delete_pool` | Delete volume (LUN removed) then force-delete pool | test_06 (`pool`, `volume`) | Volume gone; pool gone | LUN and FlexVol deleted; shared igroup baseline restored | cleanup |
| 08 | `test_08_delete_pool_with_volume_flexvol_missing` | Force-delete a pool with a CS volume after its FlexVol and LUN were removed directly | isolated | Pool removed; leftover volume record cleaned | FlexVol and LUN remain absent | negative |
| 09 | `test_09_delete_pool_with_volume_igroups_missing` | Force-delete a pool with a CS volume after host igroups were removed directly | isolated | Pool removed; leftover volume record cleaned | FlexVol deleted; igroups remain absent | negative |
| 10 | `test_10_enter_maintenance_lun_maps_predeleted` | Enter maintenance after LUN maps were removed directly on ONTAP | isolated | Pool reaches Maintenance | LUN maps remain absent | negative |
| 11 | `test_11_cancel_maintenance_after_volume_deleted` | Cancel maintenance after the pool's CS volume has been deleted | isolated | Pool returns to `Up`; volume absent | LUN absent; FlexVol online | negative |

---

## Suite 8 — iSCSI Zone-Scoped Pool

**File:** `iscsi/pool/test_zone_scoped_pool.py`
**Class:** `TestOntapISCSIZoneScopedPool`
**Tag:** `iscsi_zone_pool`
**Total:** 8 tests | **Scope:** zone-scoped iSCSI pool (scope=ZONE)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_zone_pool_rejected_when_flexvol_exists` | Reject zone-scoped pool creation when a same-name FlexVol exists | isolated | `CloudstackAPIException`; no CS pool created | Pre-existing FlexVol preserved until cleanup | negative |
| 02 | `test_02_create_zone_pool_rejected_when_no_aggregate_space` | Reject creation when requested capacity exceeds every online aggregate's free space | isolated | `CloudstackAPIException` containing `No suitable aggregates`; no pool created | No FlexVol created | negative |
| 03 | `test_03_create_zone_scoped_pool` | Create a zone-scoped iSCSI pool | setUpClass | `pool.state == "Up"` | FlexVol `online`; shared host igroups unchanged from suite-start baseline | positive |
| 04 | `test_04_disable_zone_scoped_pool` | Disable pool | test_05 | `pool.state == "Disabled"` | FlexVol unchanged; igroups unchanged | positive |
| 05 | `test_05_enable_zone_scoped_pool` | Re-enable pool | test_04 | `pool.state == "Up"` | FlexVol unchanged; igroups unchanged | positive |
| 06 | `test_06_delete_zone_scoped_pool` | Enter maintenance then delete pool | test_05 | Pool no longer listed | FlexVol and test-pool maps deleted; shared igroup baseline restored | positive |
| 07 | `test_07_delete_zone_pool_with_flexvol_predeleted` | Delete an empty zone pool after its FlexVol was removed directly | isolated | Pool removed | FlexVol remains absent; igroups cleaned up | negative |
| 08 | `test_08_delete_zone_pool_with_igroups_predeleted` | Delete an empty zone pool after host igroups were removed directly | isolated | Pool removed | FlexVol deleted; igroups remain absent | negative |

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
| 05 | `test_05_delete_volume_and_force_delete_pool` | Delete volume (LUN removed) then force-delete pool | test_04 | Volume gone; pool gone | LUN and FlexVol deleted; shared igroup baseline restored | positive |

---

## Suite 10 — iSCSI VM + Volume Attach

**File:** `iscsi/instance/test_vm_volume_attach.py`
**Class:** `TestOntapVMVolumeAttachISCSI`
**Tag:** `iscsi_vm_workflow`
**Total:** 8 tests | **Scope:** end-to-end — iSCSI pool, data volume (LUN), running VM, attach/stop/start/detach lifecycle

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
|---|-------------|------|------------|-----------------------------|------------------------|------|
| 01 | `test_01_create_iscsi_pool` | Create iSCSI ONTAP primary storage pool | setUpClass | `pool.state == "Up"`, `pool.type == "OntapiSCSI"` | FlexVol `online`; igroup per cluster host with host IQN | positive |
| 02 | `test_02_create_ontap_data_volume` | Allocate a CloudStack data volume (creates a LUN in the FlexVol) | test_01 (`pool`) | Volume non-None | ≥1 LUN in FlexVol | positive |
| 03 | `test_03_deploy_vm` | Deploy VM using first ready KVM template; verify 0 LUN-maps exist before attach | test_02 (`volume`) | `vm.state == "Running"`; 0 LUN-maps on ONTAP | 0 LUN-maps (`list_lun_maps_for_volume` returns empty) | positive |
| 04 | `test_04_attach_volume_to_vm` | Hot-attach the ONTAP iSCSI volume to the running VM — a LUN-map is created (TDS SN 27) | test_03 (`vm`, `volume`) | `volume.virtualmachineid == vm.id` | ≥1 LUN-map linking the LUN to the host's igroup | positive |
| 05 | `test_05_stop_vm_lun_unmapped` | Stop VM — LUN-maps must be removed (TDS VM Stop iSCSI) | test_04 | `vm.state == "Stopped"` | 0 LUN-maps; LUN itself **still present** in FlexVol | positive |
| 06 | `test_06_start_vm_lun_remapped` | Start VM — LUN-maps must be re-created (TDS VM Start iSCSI) | test_05 | `vm.state == "Running"` | ≥1 LUN-map re-created | positive |
| 07 | `test_07_detach_volume_from_vm` | Hot-detach the iSCSI volume from the running VM (TDS Detach iSCSI) | test_06 (`vm`, `volume`) | `volume.virtualmachineid` cleared | 0 LUN-maps; LUN still in FlexVol | positive ⚠️ |
| 08 | `test_08_destroy_vm_and_cleanup` | Destroy VM (expunge), delete volume, enter maintenance, delete pool | test_07 | VM gone; volume gone; pool gone | FlexVol deleted; all LUNs and igroups deleted | cleanup |

> ⚠️ **test_07 known status:** iSCSI hot-detach from a running VM relies on the KVM guest acknowledging the SCSI device removal. On this environment the guest does not acknowledge in time, causing CloudStack error 530. This is a KVM-host-level or guest-template limitation, not a test code defect.

---

## Cross-suite summary

| Suite | Protocol | Scope | Tests | Status |
|-------|---------|-------|-------|--------|
| NFS3 Pool Lifecycle | NFS3 | Cluster | 12 | ✅ |
| NFS3 Pool with Volumes | NFS3 | Cluster | 10 | ✅ |
| NFS3 Zone-Scoped Pool | NFS3 | Zone | 8 | ✅ |
| NFS3 Volume Lifecycle | NFS3 | Cluster | 5 | ✅ |
| NFS3 VM + Volume Attach | NFS3 | Cluster | 8 | ✅ |
| iSCSI Pool Lifecycle | iSCSI | Cluster | 12 | ✅ |
| iSCSI Pool with Volumes | iSCSI | Cluster | 11 | ✅ |
| iSCSI Zone-Scoped Pool | iSCSI | Zone | 8 | ✅ |
| iSCSI Volume Lifecycle | iSCSI | Cluster | 5 | ✅ |
| iSCSI VM + Volume Attach | iSCSI | Cluster | 8 | ⚠️ 7/8 |
| **Total** | | | **87** | **86 passing** |
