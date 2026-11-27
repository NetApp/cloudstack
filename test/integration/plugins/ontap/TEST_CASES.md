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

Complete reference for all 211 test cases across 14 test suites.
Each suite is sequential — tests must run in numbered order; each step builds on state created by the previous step.

---

## How to read the tables

| Column | Meaning |
| -------- | --------- |
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
**Total:** 21 tests | **Scope:** cluster-scoped NFS3 pool. tests 01, 02, 20, and 21 are isolated; test 04 reuses the FlexVol from test 03

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_reject_create_when_no_aggregate_space` | Reject pool creation when requested capacity exceeds every assigned online aggregate's available space | isolated | `CloudstackAPIException` containing `No suitable aggregates`; no CS pool created | No FlexVol created | negative |
| 02 | `test_02_reject_create_with_invalid_capacitybytes_values` | Reject pool creation when `capacitybytes` has invalid values | isolated | `CloudstackAPIException` for each value containing `invalid value` for `capacitybytes`, or `must be at least` when the plugin validates the size; no CS pool created | No FlexVol created | negative |
| 03 | `test_03_create_primary_storage_pool` | Create a cluster-scoped NFS3 primary storage pool | setUpClass (zone, cluster, account) | `pool.state == "Up"`, `pool.type == "NetworkFilesystem"`, `nfsmountopts` contains `vers=3` | FlexVol exists and `state == "online"`, export policy exists with each cluster host IP as a rule, at least one NFS data LIF present on SVM | positive |
| 04 | `test_04_reject_create_when_flexvol_name_exists` | Reject a second pool create when the FlexVol from test 03 already exists on ONTAP | test_03 | `CloudstackAPIException`; only the test_03 pool remains, still `Up` | Existing FlexVol stays `online`; export policy still present | negative |
| 05 | `test_05_grow_storage_pool` | Grow the original empty pool to 300 TiB | test_03 | `capacitybytes` reaches 300 TiB; pool stays `Up` | FlexVol `space.size` reaches 300 TiB; remains `online`; export policy unchanged | positive |
| 06 | `test_06_reject_grow_above_max_size` | Reject a grow past the 300 TiB ONTAP FlexVol maximum (overshoots by 1 GiB; asserts ONTAP's autosize-maximum error, not a generic failure such as aggregate space) | test_05 | `CloudstackAPIException`; `capacitybytes` stays at 300 TiB; pool stays `Up` | FlexVol `space.size` unchanged; export policy unchanged | negative |
| 07 | `test_07_shrink_storage_pool` | Safely shrink the original empty pool to its initial capacity | test_05 | `capacitybytes` reaches the safe target; pool stays `Up` | FlexVol `space.size` reaches the safe target; remains `online`; export policy unchanged | positive |
| 08 | `test_08_disable_storage_pool` | Disable the pool (admin operation) | test_07 | `pool.state == "Disabled"` | FlexVol still `online`; export policy still present | positive |
| 09 | `test_09_grow_and_shrink_storage_pool_while_disabled` | Grow then shrink the pool back while it is Disabled | test_08 | Both `updateStoragePool` calls accepted; `capacitybytes` reaches each target; KVM hosts report each resized NFS capacity; `pool.state` stays `"Disabled"` throughout | FlexVol `space.size` reaches each target and stays `online`; export policy still present and still covers every host IP | positive |
| 10 | `test_10_enable_storage_pool` | Re-enable the pool | test_09 | `pool.state == "Up"` | FlexVol still `online`; export policy still present | positive |
| 11 | `test_11_enter_maintenance_mode` | Put pool into maintenance (drains new volume allocations) | test_10 | `pool.state == "Maintenance"` | FlexVol still `online`; export policy still present (maintenance is CS-only state) | positive |
| 12 | `test_12_grow_and_shrink_storage_pool_in_maintenance` | Grow then shrink the pool back while it is in Maintenance | test_11 | Both `updateStoragePool` calls accepted; `capacitybytes` reaches each target; `pool.state` stays `"Maintenance"` throughout | FlexVol `space.size` reaches each target and stays `online`; export policy still present | positive |
| 13 | `test_13_cancel_maintenance_mode` | Cancel maintenance, return pool to service | test_12 | `pool.state == "Up"` | FlexVol still `online`; export policy still present | positive |
| 14 | `test_14_delete_pool_from_maintenance` | Enter maintenance then permanently delete the original pool | test_13 | Pool no longer returned by `listStoragePools` (CS 431 error expected on ID lookup) | FlexVol and export policy deleted | positive |
| 15 | `test_15_create_volume_on_pool` | Create a fresh pool and allocate a CloudStack data volume | test_14 | New pool is `Up`; `createVolume` returns a volume | FlexVol `online`; export policy present | positive |
| 16 | `test_16_create_vm_and_attach_volume` | Deploy a VM and attach the ONTAP data volume to it | test_15 | VM reaches `Running`; volume reports `virtualmachineid` equal to the VM id; VM still `Running` after attach; pool capacity unchanged | FlexVol still `online`; volume data file materialised in the FlexVol (NFS3 creates it lazily at attach) | positive |
| 17 | `test_17_reject_shrink_below_used_capacity` | Reject shrink below the used space of the VM-attached volume, with the target held above the ONTAP FlexVol minimum. Writes incompressible data through the ONTAP files API when the VM-attached thin volume leaves used space under the minimum, and removes it before returning | test_16 (`pool`, `volume`, `vm`) | `CloudstackAPIException`; volume remains listed with unchanged `id`/`state`/`size`/`poolid`; pool capacity unchanged | FlexVol size and export policy unchanged; used space stays above the FlexVol minimum | negative |
| 18 | `test_18_grow_and_shrink_pool_with_vm_attached` | Grow then shrink the pool back while the volume is attached to the running VM | test_16 (`vm`, `volume`) | Both resizes reach the requested capacity; pool stays `Up`; VM stays `Running` and keeps `virtualmachineid` on the volume | FlexVol reaches each size and stays `online`; export policy still covers every host IP | positive |
| 19 | `test_19_delete_volume_and_pool` | Detach and destroy the VM, delete the volume, then force-delete the pool | test_18 | VM destroyed; volume and pool no longer listed | FlexVol and export policy deleted | cleanup |
| 20 | `test_20_delete_pool_with_flexvol_predeleted` | Delete an empty pool after its FlexVol was removed directly from ONTAP | isolated | Pool removed successfully | FlexVol remains absent; export policy cleaned up | negative |
| 21 | `test_21_delete_pool_with_export_policy_predeleted` | Delete an empty pool after its export policy was removed directly from ONTAP | isolated | Pool removed successfully | FlexVol deleted; export policy remains absent | negative |

---

## Suite 2 — NFS3 Pool with Volumes

**File:** `nfs3/pool/test_pool_with_volumes.py`
**Class:** `TestOntapNFS3PoolWithVolumes`
**Tag:** `nfs3_with_volumes`
**Total:** 12 tests | **Scope:** cluster-scoped NFS3 pool with a live CloudStack volume, plus isolated negative workflows

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_create_pool_and_volume` | Create NFS3 pool and immediately allocate a data volume | setUpClass | `pool.state == "Up"`, volume object non-None | FlexVol `online`; export policy present | positive |
| 02 | `test_02_disable_pool_volume_survives` | Disable pool while a volume exists — volume must survive | test_01 (`pool`, `volume`) | `pool.state == "Disabled"`; volume still listed in `listVolumes` | FlexVol still `online` | positive |
| 03 | `test_03_enable_pool_volume_intact` | Re-enable pool with volume present | test_02 | `pool.state == "Up"`; volume still listed | FlexVol still `online` | positive |
| 04 | `test_04_enter_maintenance_volume_present` | Enter maintenance while volume present | test_03 | `pool.state == "Maintenance"`; volume still listed | FlexVol still `online` | positive |
| 05 | `test_05_cancel_maintenance_with_volume` | Cancel maintenance with volume — verifies the NFS3 cancel-maintenance fix | test_04 | `pool.state == "Up"`; volume still listed | FlexVol still `online` | positive |
| 06 | `test_06_forced_false_delete_rejected` | Attempt to delete pool (forced=False) with volume present — must be rejected | test_05 | `deleteStoragePool(forced=False)` raises `CloudstackAPIException`; pool still listed in `Maintenance` state | FlexVol still `online`; no ONTAP objects removed | negative |
| 07 | `test_07_force_delete_pool_and_cleanup` | Cancel maintenance, delete volume, then force-delete pool | test_06 | Pool no longer listed; volume no longer listed | FlexVol deleted; export policy deleted | cleanup |
| 08 | `test_08_delete_pool_with_volume_flexvol_missing` | Force-delete a pool with a CS volume after its FlexVol was removed directly from ONTAP | isolated | Pool removed; leftover volume record cleaned | FlexVol remains absent | negative |
| 09 | `test_09_delete_pool_with_volume_export_policy_missing` | Force-delete a pool with a CS volume after its export policy was removed directly from ONTAP | isolated | Pool removed; leftover volume record cleaned | FlexVol deleted; export policy remains absent | negative |
| 10 | `test_10_libvirt_pool_inactive` | Libvirt pool for this storage pool is inactive on the KVM host | isolated | Pool reaches Maintenance; volume remains | FlexVol stays online; export policy remains | negative |
| 11 | `test_11_nfs_mount_read_only` | NFS mount for this pool is read-only on the KVM host | isolated | Pool reaches Maintenance; writes to the mount fail | FlexVol stays online; export policy remains | negative |
| 12 | `test_12_nfs_mount_point_deleted` | NFS mount point for this pool is missing on the KVM host | isolated | Pool reaches Maintenance with its mount point absent | FlexVol stays online; export policy remains | negative |

---

## Suite 3 — NFS3 Zone-Scoped Pool

**File:** `nfs3/pool/test_zone_scoped_pool.py`
**Class:** `TestOntapZoneScopedPool`
**Tag:** `zone_pool`
**Total:** 6 tests | **Scope:** zone-scoped NFS3 pool (scope=ZONE, all hosts in zone connected). Test 06 is isolated: it creates and deletes its own pool after test 05 has deleted the first one

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_create_zone_scoped_pool` | Create a zone-scoped NFS3 pool; CloudStack calls `attachZone()` to connect all eligible KVM hosts | setUpClass | `pool.state == "Up"` | FlexVol `online`; export policy exists and contains **every** cluster host IP; at least one NFS data LIF present | positive |
| 02 | `test_02_grow_and_shrink_zone_scoped_pool` | Grow the zone-scoped pool, then shrink it to its original size | test_01 | Both capacity updates reach the requested size; pool remains `Up`; KVM hosts report each resized NFS capacity | FlexVol reaches each requested size and remains `online`; export policy still covers every host IP | positive |
| 03 | `test_03_disable_zone_scoped_pool` | Disable the zone-scoped pool | test_02 | `pool.state == "Disabled"` | FlexVol unchanged; export policy unchanged | positive |
| 04 | `test_04_enable_zone_scoped_pool` | Re-enable the zone-scoped pool | test_03 | `pool.state == "Up"` | FlexVol unchanged; export policy unchanged | positive |
| 05 | `test_05_delete_zone_scoped_pool` | Enter maintenance and force-delete the zone-scoped pool | test_04 | Pool no longer listed | FlexVol deleted; export policy deleted | positive |
| 06 | `test_06_zone_scoped_pool_accepts_capacity_iops` | Create a zone-scoped pool with `capacityiops` 5000, then enter maintenance, unmount it on the KVM hosts, and force-delete it | isolated | `pool.state == "Up"`; `listStoragePools` returns `capacityiops` 5000; pool no longer listed after delete | FlexVol `online` after create; FlexVol and export policy deleted after delete | positive |

---

## Suite 4 — NFS3 Volume Lifecycle

**File:** `nfs3/volume/test_volume_lifecycle.py`
**Class:** `TestOntapNFS3VolumeLifecycle`
**Tag:** `nfs3_volume`
**Total:** 5 tests | **Scope:** NFS3 CloudStack volume create/delete semantics (NFS3 volumes are metadata-only in CS)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
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
**Total:** 29 tests | **Scope:** end-to-end — NFS3 pool, data volume resize, running VM (ROOT on the ONTAP pool via a tagged compute offering), primary template cache seed / reuse / survive VM delete, attach/detach, guest SSH, volume snapshots, and VM snapshots. Guest login uses `guest_ssh.py` (paramiko) against the CentOS 5.5 template.

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_create_nfs3_pool` | Create NFS3 ONTAP primary storage pool tagged `<storagePoolTags>-tmpl-cache` | setUpClass (zone, cluster, template, tagged SO) | `pool.state == "Up"` | FlexVol `online`; export policy present | positive |
| 02 | `test_02_create_ontap_data_volume` | Allocate a CloudStack data volume on the ONTAP pool | test_01 (`pool`) | Volume non-None and listed in `listVolumes` | FlexVol still `online` | positive |
| 03 | `test_03_deploy_vm` | Deploy a VM with the tagged SO — ROOT on ONTAP; seeds template cache | test_02 (`pool`, `volume`) | `vm.state == "Running"`; ROOT `storageid` = pool; `template_spool_ref` Ready/DOWNLOADED | Cache file present at spool `install_path` | positive |
| 03a | `test_03a_deploy_second_vm_reuses_template_cache` | Deploy VM-2 — reuses cache | test_03 | VM-2 Running; ROOT on pool; still exactly one `template_spool_ref` | Same cache file (no second cache) | positive |
| 03b | `test_03b_expunge_second_vm_template_cache_survives` | Expunge VM-2 — cache must remain (lazy GC) | test_03a | spool_ref still Ready | Cache file still present | positive |
| 04 | `test_04_attach_volume_to_vm` | Attach the ONTAP data volume to the running VM (hot-plug) | test_03b (`vm`, `volume`) | `volume.virtualmachineid == vm.id`; `attachVolume` job succeeds | FlexVol `online`; after attach, a data file matching volume UUID present in FlexVol (`list_files_in_volume`) | positive |
| 05 | `test_05_stop_vm_export_retained` | Stop the running VM with volume attached | test_04 | `vm.state == "Stopped"` | FlexVol still `online`; NFS export policy still present | positive |
| 06 | `test_06_grow_stopped_vm_volume` | Grow the attached volume while the VM is stopped | test_05 | Volume is `Ready` at the requested byte size | `qemu-img info` reports the requested virtual size; ONTAP file remains present | positive |
| 07 | `test_07_shrink_rejected` | Attempt to shrink the grown qcow2 volume with `shrinkok=true` | test_06 | Resize is rejected; grown size remains unchanged | qcow2 virtual size remains unchanged | negative |
| 08 | `test_08_start_vm_volume_accessible` | Start the stopped VM | test_07 | `vm.state == "Running"`; KVM data-disk capacity equals target | FlexVol still `online` | positive |
| 09 | `test_09_open_ssh_and_write_root_data_files` | Port-forward SSH to the guest and write ROOT and DATA marker files | test_08 (`vm`, `volume`, guest network) | ROOT and DATA both on the ONTAP pool; DATA still attached; SSH login succeeds; both markers read back | FlexVol still `online` | positive |
| 10 | `test_10_root_volume_snapshot_create` | VS-01: snapshot the ROOT volume of the running VM | test_09 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot | positive |
| 11 | `test_11_data_volume_snapshot_create` | VS-03: snapshot the attached DATA volume (kept for test_14) | test_10 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot, kept until test_14 | positive |
| 12 | `test_12_volume_revert_running_vm_rejected` | VS-19: revert a volume snapshot while the VM is Running | test_11 | Revert is rejected | FlexVol unchanged | negative |
| 13 | `test_13_root_volume_revert` | VS-11: write A, snapshot ROOT, write B, stop, revert, start | test_12 | After revert and start, ROOT marker is A | New FlexVol snapshot for the snapshot; revert snapshot removed in tearDown | positive |
| 14 | `test_14_data_volume_revert` | Snapshot DATA again, revert to the test_11 snapshot then to this one, then delete both while the VM is running | test_11, test_13 | Older revert restores the test_11 DATA marker and the later snapshot stays listed; later revert restores its marker; both snapshots are then gone and the VM stays `Running` | Both FlexVol snapshots remain through the older revert, then both are removed | positive |
| 15 | `test_15_vm_snapshot_create_running` | VMS-01: VM snapshot of the running VM (kept for test_17) | test_14 | VM snapshot `Ready`; VM stays `Running` | One new FlexVol snapshot | positive |
| 16 | `test_16_vm_snapshot_create_stopped` | VMS-03: stop, VM snapshot, start (kept for test_17) | test_15 | VM snapshot `Ready` | One new FlexVol snapshot | positive |
| 17 | `test_17_vm_snapshot_s1_running_s2_stopped_revert` | VMS-09: revert to the test_15 snapshot, then to the test_16 snapshot, then delete both while the VM is running | test_15, test_16 | Each revert finishes; guest file matches that snapshot; both VM snapshots are then gone and the VM stays `Running` | FlexVol snapshots survive the reverts, then both are removed | positive |
| 18 | `test_18_vm_snapshot_duplicate_requests` | VMS-53: two VM snapshot requests for the same VM at once | test_17 | Both snapshots `Ready` (a rejected request is retried) | Two new FlexVol snapshots | positive |
| 19 | `test_19_deploy_second_vm` | Deploy VM-B whose ROOT disk is on the same ONTAP pool | test_18 | VM-B `Running`; ROOT `storageid` = pool | ROOT file present on the shared FlexVol | positive |
| 20 | `test_20_volume_revert_leaves_other_vm_untouched` | VS-28: revert VM-A's ROOT | test_19 | VM-A's ROOT marker is restored; VM-B's file is unchanged | FlexVol stays `online` | positive |
| 21 | `test_21_concurrent_vm_snapshots_two_vms` | VMS-54: VM snapshots of both VMs at the same time | test_20 | Both VM snapshots `Ready` | Two new FlexVol snapshots | positive |
| 22 | `test_22_concurrent_volume_snapshot_and_revert` | Volume snapshot of VM-A's ROOT while VM-B's ROOT reverts | test_21 | Snapshot `BackedUp` and revert both finish (retried on error 530) | New FlexVol snapshot for the volume snapshot | positive |
| 23 | `test_23_concurrent_vm_snapshot_and_revert` | VM snapshot of VM-A while VM-B reverts to its VM snapshot | test_22 | Both operations finish; VM-A snapshot `Ready` | New FlexVol snapshot for VM-A | positive |
| 24 | `test_24_detach_volume_from_vm` | Hot-detach the ONTAP volume from the running VM (TDS Detach NFS3) | test_23 (`vm`, `volume`) | `volume.virtualmachineid` cleared; `volume.state == "Ready"` | FlexVol still `online`; data file **still present** (NFS3: file persists until `deleteVolume`, not on detach) | positive |
| 25 | `test_25_grow_detached_volume` | Grow the detached, materialized data volume | test_24 | API and DB size equal target | qcow2 virtual size equals target | positive |
| 26 | `test_26_destroy_vm_with_vm_snapshot` | VMS-38: expunge VM-B while it still has a VM snapshot | test_19 (VM-B) | VM-B no longer listed | VM-B's FlexVol snapshot removed | positive |
| 27 | `test_27_destroy_vm_and_cleanup` | Destroy VM (expunge), delete volume, enter maintenance, force-delete pool | test_25, test_26 | VM no longer listed; spool_ref still Ready after VM expunge; volume no longer listed; pool no longer listed | Cache file present after VM expunge; FlexVol deleted; export policy deleted | cleanup |

---

## Suite 6 — iSCSI Pool Lifecycle

**File:** `iscsi/pool/test_pool_lifecycle.py`
**Class:** `TestOntapISCSIPoolLifecycle`
**Tag:** `iscsi_workflow`
**Total:** 21 tests | **Scope:** cluster-scoped iSCSI pool. tests 01, 02, 20, and 21 are isolated; test 04 reuses the FlexVol from test 03

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_reject_create_when_no_aggregate_space` | Reject pool creation when requested capacity exceeds every assigned online aggregate's available space | isolated | `CloudstackAPIException` containing `No suitable aggregates`; no CS pool created | No FlexVol created | negative |
| 02 | `test_02_reject_create_with_invalid_capacitybytes_values` | Reject pool creation when `capacitybytes` has invalid values | isolated | `CloudstackAPIException` for each value containing `invalid value` for `capacitybytes`, or `must be at least` when the plugin validates the size; no CS pool created | No FlexVol created | negative |
| 03 | `test_03_create_primary_storage_pool` | Create a cluster-scoped iSCSI primary storage pool | setUpClass | `pool.state == "Up"`, `pool.type == "Iscsi"` | FlexVol `online`; one igroup per cluster host (named `cs_{svmName}_{hostShortName}`) with host IQN as initiator | positive |
| 04 | `test_04_reject_create_when_flexvol_name_exists` | Reject a second pool create when the FlexVol from test 03 already exists on ONTAP | test_03 | `CloudstackAPIException`; only the test_03 pool remains, still `Up` | Existing FlexVol stays `online` | negative |
| 05 | `test_05_grow_storage_pool` | Grow the original empty pool to 300 TiB | test_03 | `capacitybytes` reaches 300 TiB; pool stays `Up` | FlexVol reaches 300 TiB and remains `online`; igroups remain present | positive |
| 06 | `test_06_reject_grow_above_max_size` | Reject a grow past the 300 TiB ONTAP FlexVol maximum (overshoots by 1 GiB; asserts ONTAP's autosize-maximum error, not a generic failure such as aggregate space) | test_05 | `CloudstackAPIException`; `capacitybytes` stays at 300 TiB; pool stays `Up` | FlexVol `space.size` unchanged; igroups unchanged | negative |
| 07 | `test_07_shrink_storage_pool` | Safely shrink the original empty pool to its initial capacity | test_05 | `capacitybytes` reaches the safe target; pool stays `Up` | FlexVol reaches the safe target and remains `online`; igroups remain present | positive |
| 08 | `test_08_disable_storage_pool` | Disable the pool | test_07 | `pool.state == "Disabled"` | FlexVol still `online` | positive |
| 09 | `test_09_grow_and_shrink_storage_pool_while_disabled` | Grow then shrink the pool back while it is Disabled | test_08 | Both `updateStoragePool` calls accepted; `capacitybytes` reaches each target; `pool.state` stays `"Disabled"` throughout | FlexVol `space.size` reaches each target and stays `online`; per-host igroups still present | positive |
| 10 | `test_10_enable_storage_pool` | Re-enable the pool | test_09 | `pool.state == "Up"` | FlexVol still `online` | positive |
| 11 | `test_11_enter_maintenance_mode` | Put pool into maintenance | test_10 | `pool.state == "Maintenance"` | FlexVol still `online`; igroups unchanged | positive |
| 12 | `test_12_grow_and_shrink_storage_pool_in_maintenance` | Grow then shrink the pool back while it is in Maintenance | test_11 | Both `updateStoragePool` calls accepted; `capacitybytes` reaches each target; `pool.state` stays `"Maintenance"` throughout | FlexVol `space.size` reaches each target and stays `online`; per-host igroups still present | positive |
| 13 | `test_13_cancel_maintenance_mode` | Cancel maintenance | test_12 | `pool.state == "Up"` | FlexVol still `online` | positive |
| 14 | `test_14_enter_maintenance_and_delete_pool` | Enter maintenance then delete the original pool | test_13 | Pool no longer listed | FlexVol and all host igroups deleted | positive |
| 15 | `test_15_create_volume_on_pool` | Create a fresh pool and allocate a CloudStack volume | test_14 | New pool is `Up`; `createVolume` returns a volume | FlexVol `online`; at least one LUN present | positive |
| 16 | `test_16_create_vm_and_attach_volume` | Deploy a VM and attach the ONTAP data volume (LUN) to it | test_15 | VM reaches `Running`; volume reports `virtualmachineid` equal to the VM id; VM still `Running` after attach | FlexVol still `online`; LUN still present; per-host igroups unchanged | positive |
| 17 | `test_17_reject_shrink_below_used_capacity` | Reject shrink below the used space of the VM-attached LUN, with the target held above the ONTAP FlexVol minimum. Writes incompressible data through the ONTAP files API when the VM-attached thin volume leaves used space under the minimum, and removes it before returning | test_16 (`pool`, `volume`, `vm`) | `CloudstackAPIException`; volume remains listed with unchanged `id`/`state`/`size`/`poolid`; pool capacity unchanged | FlexVol size and LUN uuid/name list unchanged; used space stays above the FlexVol minimum | negative |
| 18 | `test_18_grow_and_shrink_pool_with_vm_attached` | Grow then shrink the pool back while the volume (LUN) is attached to the running VM | test_16 (`vm`, `volume`) | Both resizes reach the requested capacity; pool stays `Up`; VM stays `Running` and keeps `virtualmachineid` on the volume | FlexVol reaches each size and stays `online`; LUN still present | positive |
| 19 | `test_19_delete_volume_and_pool` | Detach and destroy the VM, delete the volume, then force-delete the pool | test_18 | VM destroyed; volume and pool no longer listed | LUN, FlexVol, and igroups deleted | cleanup |
| 20 | `test_20_delete_pool_with_flexvol_predeleted` | Delete an empty pool after its FlexVol was removed directly from ONTAP | isolated | Pool removed successfully | FlexVol remains absent; igroups cleaned up | negative |
| 21 | `test_21_delete_pool_with_igroups_predeleted` | Delete an empty pool after host igroups were removed directly from ONTAP | isolated | Pool removed successfully | FlexVol deleted; igroups remain absent | negative |

---

## Suite 7 — iSCSI Pool with Volumes

**File:** `iscsi/pool/test_pool_with_volumes.py`
**Class:** `TestOntapISCSIPoolWithVolumes`
**Tag:** `iscsi_workflow`
**Total:** 13 tests | **Scope:** cluster-scoped iSCSI pool with a live CloudStack volume (LUN), plus isolated negative workflows

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
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
| 11 | `test_11_iscsi_session_logged_out` | Enter maintenance after only the test iSCSI session is logged out | isolated | Pool reaches Maintenance; volume remains | Test LUN remains | negative |
| 12 | `test_12_delete_volume_with_existing_iscsi_session` | Delete the test volume while its iSCSI session is already logged in | isolated | Volume is removed; no extra session is created | Test LUN is removed | negative |
| 13 | `test_13_corrupt_iscsi_by_path` | Delete the test volume after its by-path symlink is replaced with a regular file | isolated | Volume is removed; the planted file remains | Test LUN is removed | negative |

---

## Suite 8 — iSCSI Zone-Scoped Pool

**File:** `iscsi/pool/test_zone_scoped_pool.py`
**Class:** `TestOntapISCSIZoneScopedPool`
**Tag:** `iscsi_zone_pool`
**Total:** 6 tests | **Scope:** zone-scoped iSCSI pool (scope=ZONE). Test 06 is isolated: it creates and deletes its own pool after test 05 has deleted the first one

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_create_zone_scoped_pool` | Create a zone-scoped iSCSI pool | setUpClass | `pool.state == "Up"` | FlexVol `online`; shared host igroups unchanged from suite-start baseline | positive |
| 02 | `test_02_grow_and_shrink_zone_scoped_pool` | Grow the zone-scoped pool, then shrink it to its original size | test_01 | Both capacity updates reach the requested size; pool remains `Up` | FlexVol reaches each requested size and remains `online`; shared igroups remain unchanged | positive |
| 03 | `test_03_disable_zone_scoped_pool` | Disable pool | test_02 | `pool.state == "Disabled"` | FlexVol unchanged; igroups unchanged | positive |
| 04 | `test_04_enable_zone_scoped_pool` | Re-enable pool | test_03 | `pool.state == "Up"` | FlexVol unchanged; igroups unchanged | positive |
| 05 | `test_05_delete_zone_scoped_pool` | Enter maintenance then delete pool | test_04 | Pool no longer listed | FlexVol and test-pool maps deleted; shared igroup baseline restored | positive |
| 06 | `test_06_zone_scoped_pool_accepts_capacity_iops` | Create a zone-scoped pool with `capacityiops` 5000, then enter maintenance and force-delete it | isolated | `pool.state == "Up"`; `listStoragePools` returns `capacityiops` 5000; pool no longer listed after delete | FlexVol `online` after create; FlexVol deleted after delete | positive |

---

## Suite 9 — iSCSI Volume Lifecycle

**File:** `iscsi/volume/test_volume_lifecycle.py`
**Class:** `TestOntapISCSIVolumeLifecycle`
**Tag:** `iscsi_volume`
**Total:** 5 tests | **Scope:** iSCSI CloudStack volume create/delete semantics (each CS volume maps to an ONTAP LUN)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
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
**Total:** 29 tests | **Scope:** end-to-end — iSCSI pool, data volume resize (LUN), running VM (ROOT on the ONTAP pool via a tagged compute offering), `cs_tmpl_<templateId>` LUN cache seed / reuse / survive VM delete, attach/stop/start/detach, guest SSH, volume snapshots, and VM snapshots. LUN-map checks are limited to the data volume's LUN. Guest login uses `guest_ssh.py` (paramiko) against the CentOS 5.5 template.

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_create_iscsi_pool` | Create iSCSI ONTAP primary storage pool tagged `<storagePoolTags>-tmpl-cache` | setUpClass (tagged SO) | `pool.state == "Up"`, `pool.type == "OntapiSCSI"` | FlexVol `online`; igroup per cluster host with host IQN | positive |
| 02 | `test_02_create_ontap_data_volume` | Allocate and grow a detached data volume | test_01 (`pool`) | API and DB size equal target | LUN exists and `space.size` equals target | positive |
| 03 | `test_03_deploy_vm` | Deploy VM with the tagged SO — ROOT on ONTAP; seeds `cs_tmpl_*`; verify 0 data-volume LUN-maps before attach | test_02 (`volume`) | `vm.state == "Running"`; ROOT on pool; spool_ref Ready (`local_path` = LUN uuid) | Exactly one `/vol/<flex>/cs_tmpl_<id>` LUN; 0 data-volume LUN-maps | positive |
| 03a | `test_03a_deploy_second_vm_reuses_template_cache` | Deploy VM-2 — reuse cache | test_03 | VM-2 Running; ROOT on pool; still one spool_ref | Still one `cs_tmpl_*`; non-cache LUN count +1 | positive |
| 03b | `test_03b_expunge_second_vm_template_cache_survives` | Expunge VM-2 — cache LUN remains | test_03a | spool_ref still Ready | VM-2 ROOT LUN gone (non-cache count back to baseline); `cs_tmpl_*` still present | positive |
| 04 | `test_04_attach_volume_to_vm` | Hot-attach the ONTAP iSCSI volume to the running VM — a LUN-map is created (TDS SN 27) | test_03b (`vm`, `volume`) | `volume.virtualmachineid == vm.id` | ≥1 LUN-map linking the LUN to the host's igroup | positive |
| 05 | `test_05_stop_vm_lun_unmapped` | Stop VM — LUN-maps must be removed (TDS VM Stop iSCSI) | test_04 | `vm.state == "Stopped"` | 0 LUN-maps; LUN itself **still present** in FlexVol | positive |
| 06 | `test_06_grow_stopped_vm_volume` | Grow the attached volume while the VM is stopped | test_05 | Volume is `Ready` at the requested byte size | LUN `space.size` equals the requested size; LUN remains unmapped | positive |
| 07 | `test_07_shrink_rejected` | Attempt to shrink the grown LUN with `shrinkok=true` | test_06 | Resize is rejected; grown size remains unchanged | LUN `space.size` remains unchanged | negative |
| 08 | `test_08_start_vm_lun_remapped` | Start VM — LUN-maps must be re-created (TDS VM Start iSCSI) | test_07 | `vm.state == "Running"` | ≥1 LUN-map re-created | positive |
| 09 | `test_09_open_ssh_and_write_root_data_files` | Port-forward SSH to the guest and write ROOT and DATA marker files | test_08 (`vm`, `volume`, guest network) | ROOT and DATA both on the ONTAP pool; DATA still attached; SSH login succeeds; both markers read back | Data LUN still mapped | positive |
| 10 | `test_10_root_volume_snapshot_create` | VS-01: snapshot the ROOT volume of the running VM | test_09 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot | positive |
| 11 | `test_11_data_volume_snapshot_create` | VS-03: snapshot the attached DATA volume (kept for test_14) | test_10 | Snapshot `BackedUp`; VM stays `Running` | One new FlexVol snapshot, kept until test_14 | positive |
| 12 | `test_12_volume_revert_running_vm_rejected` | VS-20: revert a volume snapshot while the VM is Running | test_11 | Revert is rejected | FlexVol unchanged | negative |
| 13 | `test_13_root_volume_revert` | VS-12: write A, snapshot ROOT, write B, stop, revert, start | test_12 | After revert and start, ROOT marker is A | New FlexVol snapshot for the snapshot; revert snapshot removed in tearDown | positive |
| 14 | `test_14_data_volume_revert` | Snapshot DATA again, revert to the test_11 snapshot then to this one, then delete both while the VM is running | test_11, test_13 | Older revert restores the test_11 DATA marker and the later snapshot stays listed; later revert restores its marker; both snapshots are then gone and the VM stays `Running` | Both FlexVol snapshots remain through the older revert, then both are removed | positive |
| 15 | `test_15_vm_snapshot_create_running` | VM snapshot of the running VM, ROOT + DATA LUNs (kept for test_17) | test_14 | VM snapshot `Ready`; VM stays `Running` | One new FlexVol snapshot | positive |
| 16 | `test_16_vm_snapshot_create_stopped` | Stop, VM snapshot, start (kept for test_17) | test_15 | VM snapshot `Ready` | One new FlexVol snapshot | positive |
| 17 | `test_17_vm_snapshot_s1_running_s2_stopped_revert` | VMS-09: revert to the test_15 snapshot, then to the test_16 snapshot, then delete both while the VM is running | test_15, test_16 | Each revert finishes; guest file matches that snapshot; both VM snapshots are then gone and the VM stays `Running` | FlexVol snapshots survive the reverts, then both are removed | positive |
| 18 | `test_18_vm_snapshot_duplicate_requests` | VMS-53: two VM snapshot requests for the same VM at once | test_17 | Both snapshots `Ready` (a rejected request is retried) | Two new FlexVol snapshots | positive |
| 19 | `test_19_deploy_second_vm` | Deploy VM-B whose ROOT LUN is on the same ONTAP pool | test_18 | VM-B `Running`; ROOT on pool | One more non-cache LUN; `cs_tmpl_*` still one | positive |
| 20 | `test_20_volume_revert_leaves_other_vm_untouched` | VS-28: revert VM-A's ROOT | test_19 | VM-A's ROOT marker is restored; VM-B's file is unchanged | FlexVol stays `online`; both ROOT LUNs remain | positive |
| 21 | `test_21_concurrent_vm_snapshots_two_vms` | VMS-55: VM snapshots of both VMs at the same time | test_20 | Both VM snapshots `Ready` | Two new FlexVol snapshots | positive |
| 22 | `test_22_concurrent_volume_snapshot_and_revert` | Volume snapshot of VM-A's ROOT while VM-B's ROOT reverts | test_21 | Snapshot `BackedUp` and revert both finish (retried on error 530) | New FlexVol snapshot for the volume snapshot | positive |
| 23 | `test_23_concurrent_vm_snapshot_and_revert` | VM snapshot of VM-A while VM-B reverts to its VM snapshot | test_22 | Both operations finish; VM-A snapshot `Ready` | New FlexVol snapshot for VM-A | positive |
| 24 | `test_24_detach_volume_from_vm` | Hot-detach the iSCSI volume from the running VM (TDS Detach iSCSI) | test_23 (`vm`, `volume`) | `volume.virtualmachineid` cleared | 0 LUN-maps for the data LUN; LUN still in FlexVol | positive ⚠️ |
| 25 | `test_25_grow_detached_volume` | Grow the detached, materialized data volume | test_24 | API and DB size equal target | LUN `space.size` equals target | positive |
| 26 | `test_26_destroy_vm_with_vm_snapshot` | Expunge VM-B while it still has a VM snapshot | test_19 (VM-B) | VM-B no longer listed | VM-B ROOT LUN gone; `cs_tmpl_*` still present | positive |
| 27 | `test_27_destroy_vm_and_cleanup` | Destroy VM (expunge), delete volume, enter maintenance, delete pool | test_25, test_26 | VM gone; spool_ref still Ready after VM expunge; volume gone; pool gone | `cs_tmpl_*` present after VM expunge; FlexVol deleted; all LUNs and igroups deleted | cleanup |

> ⚠️ **test_24 known status:** iSCSI hot-detach from a running VM relies on the KVM guest acknowledging the SCSI device removal. On this environment the guest does not acknowledge in time, causing CloudStack error 530. This is a KVM-host-level or guest-template limitation, not a test code defect.

---

## Suite 11 — NFS3 Template Cache Negative / Boundary

**File:** `nfs3/template/test_template_cache_negative.py`
**Class:** `TestOntapNfs3TemplateCacheNegative`
**Tag:** `nfs3_template_cache_negative`
**Total:** 3 tests | **Scope:** Boundary conditions for NFS3 primary template cache (isolated from happy path)

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
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
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_tag_mismatch_does_not_seed_cache` | SO tags ≠ pool tags | setUpClass | ROOT not on ONTAP pool; no `template_spool_ref` | No `cs_tmpl_*` LUN | negative |
| 02 | `test_02_undersized_pool_deploy_fails` | Matching tags but undersized capacity | setUpClass | Deploy fails; spool_ref not Ready/DOWNLOADED | No `cs_tmpl_*` LUN | negative |
| 03 | `test_03_deleted_cache_blocks_reuse` | Seed cache, delete LUN out-of-band, redeploy | setUpClass | spool_ref still Ready; second deploy fails | `cs_tmpl_*` absent after delete | negative |

---

## Suite 13 — NFS3 QoS Offerings

**File:** `nfs3/qos/test_qos_offerings.py` (shared cases in `helpers/qos_offerings.py`)
**Class:** `TestOntapNFS3QosOfferings`
**Tag:** `nfs3_qos`
**Total:** 29 tests | **Scope:** QoS policy placement on NFS3 files for disk offerings, compute offerings, root and data disks, and resize. One cluster-scoped pool is created in `setUpClass`

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_fixed_disk_without_iops` | Fixed disk offering with no IOPS | setUpClass (`pool`) | Volume reaches `Ready`; `miniops` and `maxiops` are 0 | The file has no QoS policy | positive |
| 02 | `test_02_fixed_disk_max_iops_only` | Fixed disk offering with a maximum only. The offering's min IOPS is set to 0 in `disk_offering` after create, because the API rejects a minimum of 0 | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 410 | The file carries `cs_0_to_410_iops_<svm>`; the policy's max throughput is 410 | positive |
| 03 | `test_03_fixed_disk_min_and_max_iops` | Fixed disk offering with a minimum of 120 and a maximum of 460 | setUpClass (`pool`) | AFF: volume `Ready` with `miniops` 120 and `maxiops` 460. FAS: `createVolume` fails and the management log holds the FAS minimum-IOPS message for that volume | AFF: the file carries `cs_120_to_460_iops_<svm>` with min 120 and max 460. FAS: no `cs_120_to_460_iops_<svm>` policy exists afterwards | positive on AFF, negative on FAS |
| 04 | `test_04_fixed_disk_min_iops_only` | Fixed disk offering with a minimum of 180 and no maximum | setUpClass (`pool`) | AFF: volume `Ready` with `miniops` 180 and `maxiops` 0. FAS: `createVolume` fails and the management log holds the FAS minimum-IOPS message | AFF: the file carries `cs_180_to_0_iops_<svm>`. FAS: no `cs_180_to_0_iops_<svm>` policy exists afterwards | positive on AFF, negative on FAS |
| 05 | `test_05_custom_size_disk_with_fixed_iops` | Custom-size disk offering whose IOPS are fixed on the offering (0 and 470); the volume is created at 1 GB | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 470 | The file carries `cs_0_to_470_iops_<svm>` | positive |
| 06 | `test_06_custom_iops_disk_max_only` | Custom-IOPS disk offering; `createVolume` passes `miniops` 0 and `maxiops` 480 | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 480 | The file carries `cs_0_to_480_iops_<svm>` | positive |
| 07 | `test_07_custom_iops_disk_min_and_max` | Custom-IOPS disk offering; `createVolume` passes `miniops` 130 and `maxiops` 490 | setUpClass (`pool`) | AFF: volume `Ready` with `miniops` 130 and `maxiops` 490. FAS: `createVolume` fails and the management log holds the FAS minimum-IOPS message | AFF: the file carries `cs_130_to_490_iops_<svm>`. FAS: no `cs_130_to_490_iops_<svm>` policy exists afterwards | positive on AFF, negative on FAS |
| 08 | `test_08_custom_size_and_custom_iops` | Disk offering with both custom size and custom IOPS; `createVolume` passes size 1 GB, `miniops` 0 and `maxiops` 500 | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 500 | The file carries `cs_0_to_500_iops_<svm>` | positive |
| 09 | `test_09_provisioning_types_keep_iops` | Thin, sparse, and fat disk offerings each keep a max-only policy (511, 512, 513). Each volume is deleted before the next one is created | setUpClass (`pool`) | Each volume reaches `Ready` with its `maxiops` and `miniops` 0 | Each file carries `cs_0_to_<max>_iops_<svm>`; after its volume is deleted that policy is removed | positive |
| 10 | `test_10_shared_policy_survives_until_last_volume` | Two volumes from the same offering (0 and 610) share one policy until the last volume is deleted | setUpClass (`pool`) | Both volumes reach `Ready` with `maxiops` 610; the second volume is still `Ready` after the first is deleted | Both files carry `cs_0_to_610_iops_<svm>` and `object_count` is at least 2 when ONTAP reports it; the policy survives the first delete and is removed after the second | positive |
| 11 | `test_11_resize_replaces_qos_policy` | `resizeVolume` onto a disk offering with a higher maximum (415 to 825) | setUpClass (`pool`) | Volume is `Ready` again with `maxiops` 825 | The file switches to `cs_0_to_825_iops_<svm>`; `cs_0_to_415_iops_<svm>` is removed | positive |
| 12 | `test_12_resize_clears_qos_policy` | `resizeVolume` onto a disk offering that has no IOPS (from 430) | setUpClass (`pool`) | Volume is `Ready` again with `miniops` and `maxiops` 0 | The file has no QoS policy; `cs_0_to_430_iops_<svm>` is removed | positive |
| 13 | `test_13_reject_disk_offering_min_above_max` | `createDiskOffering` with `miniops` 500 and `maxiops` 100 | setUpClass (`pool`) | `createDiskOffering` fails with `min IOPS must be less than or equal to the max IOPS` | No ONTAP object is involved | negative |
| 14 | `test_14_reject_disk_offering_min_iops_zero` | `createDiskOffering` with `miniops` 0 and `maxiops` 100 | setUpClass (`pool`) | `createDiskOffering` fails with `min IOPS must be greater than 0` | No ONTAP object is involved | negative |
| 15 | `test_15_reject_custom_iops_min_above_max` | `createVolume` on a custom-IOPS offering with `miniops` 400 and `maxiops` 100 | setUpClass (`pool`) | `createVolume` fails with `min IOPS must be less than or equal to the max IOPS`; no volume of that name remains | No file is created | negative |
| 16 | `test_16_reject_pool_iops_capacity` | A minimum above the pool's IOPS capacity is rejected. Creates a second pool with `capacityiops` 100 and requests `miniops` 200. Skipped on FAS, which rejects a minimum earlier | setUpClass (creates and deletes a second pool) | `createVolume` fails with `Insufficient IOPS capacity`; no volume of that name remains; the second pool is deleted afterwards | No `cs_200_to_200_iops_<svm>` policy exists afterwards; the second pool's FlexVol is deleted | negative (skipped on FAS) |
| 17 | `test_17_fixed_compute_override_qos_disk` | Fixed compute offering plus an override root disk offering with a maximum of 440 (8 GB) | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT file carries `cs_0_to_440_iops_<svm>` | positive |
| 18 | `test_18_compute_offering_with_its_own_iops` | Compute offering that carries its own maximum of 450 on its implicit disk offering | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT file carries `cs_0_to_450_iops_<svm>` | positive |
| 19 | `test_19_compute_bound_to_qos_disk` | Compute offering bound to a disk offering with a maximum of 455 (8 GB) | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT file carries `cs_0_to_455_iops_<svm>` | positive |
| 20 | `test_20_strict_compute_rejects_disk_override` | Strict compute offering bound to one disk offering; deploy with a different override disk offering | setUpClass (`pool`) | `deployVirtualMachine` fails with `errorCode: 431` and `strictly mapped to its disk offering` | No ONTAP object is created for the rejected deploy | negative |
| 21 | `test_21_custom_compute_with_qos_disk` | Custom compute offering bound to a disk offering with a maximum of 485; deploy details give 1 CPU, 500 MHz, 512 MB | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT file carries `cs_0_to_485_iops_<svm>` | positive |
| 22 | `test_22_custom_compute_custom_root_disk` | Custom compute offering with a custom-size, custom-IOPS root disk; `rootdisksize` 8 and deploy details `minIops` 0 and `maxIops` 540 | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool with `maxiops` 540 | The ROOT file carries `cs_0_to_540_iops_<svm>` | positive |
| 23 | `test_23_custom_iops_on_compute_offering` | Fixed compute offering with custom IOPS; deploy details give `minIops` 0 and `maxIops` 530 | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool with `maxiops` 530 | The ROOT file carries `cs_0_to_530_iops_<svm>` | positive |
| 24 | `test_24_root_without_iops_and_qos_data_disk` | Root disk with no IOPS and an attached data disk with a maximum of 545 | setUpClass (`pool`) | VM reaches `Running`; data volume attaches and is `Ready` with `maxiops` 545 | The ROOT file has no QoS policy; the data file carries `cs_0_to_545_iops_<svm>` | positive |
| 25 | `test_25_root_and_data_use_different_policies` | Root disk with a maximum of 550 and an attached data disk with a maximum of 560 | setUpClass (`pool`) | VM reaches `Running`; both volumes are `Ready` with their own `maxiops` | The ROOT file carries `cs_0_to_550_iops_<svm>` and the data file carries `cs_0_to_560_iops_<svm>`; the ROOT policy is unchanged after the attach | positive |
| 26 | `test_26_hypervisor_rate_limits_do_not_create_ontap_policy` | Disk offering with hypervisor read-rate limits (`bytesreadrate` and `iopsreadrate`) and no min/max IOPS | setUpClass (`pool`) | Volume reaches `Ready`; `miniops` and `maxiops` are 0 | The file has no QoS policy | positive |
| 27 | `test_27_constrained_compute_fixed_iops` | Constrained custom compute offering (1 to 2 CPUs, 512 to 1024 MB, 500 MHz) bound to a disk offering with a maximum of 570; deploy with 2 CPUs and 1024 MB | setUpClass (`pool`) | Offering reports customized, 500 MHz, and the four CPU and memory limits; VM reports 2 CPUs, 500 MHz, 1024 MB; ROOT volume is `Ready` on the pool | The ROOT file carries `cs_0_to_570_iops_<svm>` | positive |
| 28 | `test_28_constrained_compute_custom_iops` | Constrained custom compute offering bound to a custom-IOPS root disk; deploy with 1 CPU, 768 MB, `minIops` 0 and `maxIops` 580 | setUpClass (`pool`) | Offering reports the same limits; VM reports 1 CPU, 500 MHz, 768 MB; ROOT volume is `Ready` on the pool with `maxiops` 580 | The ROOT file carries `cs_0_to_580_iops_<svm>` | positive |
| 29 | `test_29_rejected_resize_keeps_original_qos` | A resize the driver rejects keeps the original QoS. Creates a second pool with `capacityiops` 100 and a volume with min 50 and max 500, then resizes it onto a disk offering with min 200. Skipped on FAS, where a minimum IOPS is rejected earlier | setUpClass (creates and deletes a second pool) | `resizeVolume` fails with `Insufficient IOPS capacity`; the volume is `Ready` with `miniops` 50 and `maxiops` 500 | The file still carries `cs_50_to_500_iops_<svm>` with min 50 and max 500; no `cs_200_to_500_iops_<svm>` policy exists; the second pool's FlexVol is deleted afterwards | negative (skipped on FAS) |

> **AFF and FAS:** the suite reads the platform from ONTAP (`OntapRestClient.is_all_flash()`). A minimum IOPS above 0 is applied only on AFF. On FAS the plugin rejects it before ONTAP creates a policy; the API error only says the volume create failed, so the test reads the FAS message from `vmops.log` on the management server over SSH. Policy names follow `cs_<min>_to_<max>_iops_<svm>`, with dots in the SVM name replaced by underscores.
>
> **Isolation and cleanup:** each test is independent. `tearDown` destroys the test's VMs and volumes, then removes the ONTAP file and any `cs_` policy with no remaining objects. `tearDownClass` unmounts the pool on the KVM hosts, then force-deletes the pool, the FlexVol, and the export policy.

---

## Suite 14 — iSCSI QoS Offerings

**File:** `iscsi/qos/test_qos_offerings.py` (shared cases in `helpers/qos_offerings.py`)
**Class:** `TestOntapISCSIQosOfferings`
**Tag:** `iscsi_qos`
**Total:** 29 tests | **Scope:** QoS policy placement on iSCSI LUNs for disk offerings, compute offerings, root and data disks, and resize. One cluster-scoped pool is created in `setUpClass`

| # | Test method | Goal | Depends on | CloudStack success criteria | ONTAP success criteria | Type |
| --- | ------------- | ------ | ------------ | ----------------------------- | ------------------------ | ------ |
| 01 | `test_01_fixed_disk_without_iops` | Fixed disk offering with no IOPS | setUpClass (`pool`) | Volume reaches `Ready`; `miniops` and `maxiops` are 0 | The LUN has no QoS policy | positive |
| 02 | `test_02_fixed_disk_max_iops_only` | Fixed disk offering with a maximum only. The offering's min IOPS is set to 0 in `disk_offering` after create, because the API rejects a minimum of 0 | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 410 | The LUN carries `cs_0_to_410_iops_<svm>`; the policy's max throughput is 410 | positive |
| 03 | `test_03_fixed_disk_min_and_max_iops` | Fixed disk offering with a minimum of 120 and a maximum of 460 | setUpClass (`pool`) | AFF: volume `Ready` with `miniops` 120 and `maxiops` 460. FAS: `createVolume` fails and the management log holds the FAS minimum-IOPS message for that volume | AFF: the LUN carries `cs_120_to_460_iops_<svm>` with min 120 and max 460. FAS: no `cs_120_to_460_iops_<svm>` policy exists afterwards | positive on AFF, negative on FAS |
| 04 | `test_04_fixed_disk_min_iops_only` | Fixed disk offering with a minimum of 180 and no maximum | setUpClass (`pool`) | AFF: volume `Ready` with `miniops` 180 and `maxiops` 0. FAS: `createVolume` fails and the management log holds the FAS minimum-IOPS message | AFF: the LUN carries `cs_180_to_0_iops_<svm>`. FAS: no `cs_180_to_0_iops_<svm>` policy exists afterwards | positive on AFF, negative on FAS |
| 05 | `test_05_custom_size_disk_with_fixed_iops` | Custom-size disk offering whose IOPS are fixed on the offering (0 and 470); the volume is created at 1 GB | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 470 | The LUN carries `cs_0_to_470_iops_<svm>` | positive |
| 06 | `test_06_custom_iops_disk_max_only` | Custom-IOPS disk offering; `createVolume` passes `miniops` 0 and `maxiops` 480 | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 480 | The LUN carries `cs_0_to_480_iops_<svm>` | positive |
| 07 | `test_07_custom_iops_disk_min_and_max` | Custom-IOPS disk offering; `createVolume` passes `miniops` 130 and `maxiops` 490 | setUpClass (`pool`) | AFF: volume `Ready` with `miniops` 130 and `maxiops` 490. FAS: `createVolume` fails and the management log holds the FAS minimum-IOPS message | AFF: the LUN carries `cs_130_to_490_iops_<svm>`. FAS: no `cs_130_to_490_iops_<svm>` policy exists afterwards | positive on AFF, negative on FAS |
| 08 | `test_08_custom_size_and_custom_iops` | Disk offering with both custom size and custom IOPS; `createVolume` passes size 1 GB, `miniops` 0 and `maxiops` 500 | setUpClass (`pool`) | Volume reaches `Ready` with `miniops` 0 and `maxiops` 500 | The LUN carries `cs_0_to_500_iops_<svm>` | positive |
| 09 | `test_09_provisioning_types_keep_iops` | Thin, sparse, and fat disk offerings each keep a max-only policy (511, 512, 513). Each volume is deleted before the next one is created | setUpClass (`pool`) | Each volume reaches `Ready` with its `maxiops` and `miniops` 0 | Each LUN carries `cs_0_to_<max>_iops_<svm>`; after its volume is deleted that policy is removed | positive |
| 10 | `test_10_shared_policy_survives_until_last_volume` | Two volumes from the same offering (0 and 610) share one policy until the last volume is deleted | setUpClass (`pool`) | Both volumes reach `Ready` with `maxiops` 610; the second volume is still `Ready` after the first is deleted | Both LUNs carry `cs_0_to_610_iops_<svm>` and `object_count` is at least 2 when ONTAP reports it; the policy survives the first delete and is removed after the second | positive |
| 11 | `test_11_resize_replaces_qos_policy` | `resizeVolume` onto a disk offering with a higher maximum (415 to 825) | setUpClass (`pool`) | Volume is `Ready` again with `maxiops` 825 | The LUN switches to `cs_0_to_825_iops_<svm>`; `cs_0_to_415_iops_<svm>` is removed | positive |
| 12 | `test_12_resize_clears_qos_policy` | `resizeVolume` onto a disk offering that has no IOPS (from 430) | setUpClass (`pool`) | Volume is `Ready` again with `miniops` and `maxiops` 0 | The LUN has no QoS policy; `cs_0_to_430_iops_<svm>` is removed | positive |
| 13 | `test_13_reject_disk_offering_min_above_max` | `createDiskOffering` with `miniops` 500 and `maxiops` 100 | setUpClass (`pool`) | `createDiskOffering` fails with `min IOPS must be less than or equal to the max IOPS` | No ONTAP object is involved | negative |
| 14 | `test_14_reject_disk_offering_min_iops_zero` | `createDiskOffering` with `miniops` 0 and `maxiops` 100 | setUpClass (`pool`) | `createDiskOffering` fails with `min IOPS must be greater than 0` | No ONTAP object is involved | negative |
| 15 | `test_15_reject_custom_iops_min_above_max` | `createVolume` on a custom-IOPS offering with `miniops` 400 and `maxiops` 100 | setUpClass (`pool`) | `createVolume` fails with `min IOPS must be less than or equal to the max IOPS`; no volume of that name remains | No LUN is created | negative |
| 16 | `test_16_reject_pool_iops_capacity` | A minimum above the pool's IOPS capacity is rejected. Creates a second pool with `capacityiops` 100 and requests `miniops` 200. Skipped on FAS, which rejects a minimum earlier | setUpClass (creates and deletes a second pool) | `createVolume` fails with `Insufficient IOPS capacity`; no volume of that name remains; the second pool is deleted afterwards | No `cs_200_to_200_iops_<svm>` policy exists afterwards; the second pool's FlexVol is deleted | negative (skipped on FAS) |
| 17 | `test_17_fixed_compute_override_qos_disk` | Fixed compute offering plus an override root disk offering with a maximum of 440 (8 GB) | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT LUN carries `cs_0_to_440_iops_<svm>` | positive |
| 18 | `test_18_compute_offering_with_its_own_iops` | Compute offering that carries its own maximum of 450 on its implicit disk offering | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT LUN carries `cs_0_to_450_iops_<svm>` | positive |
| 19 | `test_19_compute_bound_to_qos_disk` | Compute offering bound to a disk offering with a maximum of 455 (8 GB) | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT LUN carries `cs_0_to_455_iops_<svm>` | positive |
| 20 | `test_20_strict_compute_rejects_disk_override` | Strict compute offering bound to one disk offering; deploy with a different override disk offering | setUpClass (`pool`) | `deployVirtualMachine` fails with `errorCode: 431` and `strictly mapped to its disk offering` | No ONTAP object is created for the rejected deploy | negative |
| 21 | `test_21_custom_compute_with_qos_disk` | Custom compute offering bound to a disk offering with a maximum of 485; deploy details give 1 CPU, 500 MHz, 512 MB | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool | The ROOT LUN carries `cs_0_to_485_iops_<svm>` | positive |
| 22 | `test_22_custom_compute_custom_root_disk` | Custom compute offering with a custom-size, custom-IOPS root disk; `rootdisksize` 8 and deploy details `minIops` 0 and `maxIops` 540 | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool with `maxiops` 540 | The ROOT LUN carries `cs_0_to_540_iops_<svm>` | positive |
| 23 | `test_23_custom_iops_on_compute_offering` | Fixed compute offering with custom IOPS; deploy details give `minIops` 0 and `maxIops` 530 | setUpClass (`pool`) | VM reaches `Running`; ROOT volume is `Ready` on the pool with `maxiops` 530 | The ROOT LUN carries `cs_0_to_530_iops_<svm>` | positive |
| 24 | `test_24_root_without_iops_and_qos_data_disk` | Root disk with no IOPS and an attached data disk with a maximum of 545 | setUpClass (`pool`) | VM reaches `Running`; data volume attaches and is `Ready` with `maxiops` 545 | The ROOT LUN has no QoS policy; the data LUN carries `cs_0_to_545_iops_<svm>` | positive |
| 25 | `test_25_root_and_data_use_different_policies` | Root disk with a maximum of 550 and an attached data disk with a maximum of 560 | setUpClass (`pool`) | VM reaches `Running`; both volumes are `Ready` with their own `maxiops` | The ROOT LUN carries `cs_0_to_550_iops_<svm>` and the data LUN carries `cs_0_to_560_iops_<svm>`; the ROOT policy is unchanged after the attach | positive |
| 26 | `test_26_hypervisor_rate_limits_do_not_create_ontap_policy` | Disk offering with hypervisor read-rate limits (`bytesreadrate` and `iopsreadrate`) and no min/max IOPS | setUpClass (`pool`) | Volume reaches `Ready`; `miniops` and `maxiops` are 0 | The LUN has no QoS policy | positive |
| 27 | `test_27_constrained_compute_fixed_iops` | Constrained custom compute offering (1 to 2 CPUs, 512 to 1024 MB, 500 MHz) bound to a disk offering with a maximum of 570; deploy with 2 CPUs and 1024 MB | setUpClass (`pool`) | Offering reports customized, 500 MHz, and the four CPU and memory limits; VM reports 2 CPUs, 500 MHz, 1024 MB; ROOT volume is `Ready` on the pool | The ROOT LUN carries `cs_0_to_570_iops_<svm>` | positive |
| 28 | `test_28_constrained_compute_custom_iops` | Constrained custom compute offering bound to a custom-IOPS root disk; deploy with 1 CPU, 768 MB, `minIops` 0 and `maxIops` 580 | setUpClass (`pool`) | Offering reports the same limits; VM reports 1 CPU, 500 MHz, 768 MB; ROOT volume is `Ready` on the pool with `maxiops` 580 | The ROOT LUN carries `cs_0_to_580_iops_<svm>` | positive |
| 29 | `test_29_rejected_resize_keeps_original_qos` | A resize the driver rejects keeps the original QoS. Creates a second pool with `capacityiops` 100 and a volume with min 50 and max 500, then resizes it onto a disk offering with min 200. Skipped on FAS, where a minimum IOPS is rejected earlier | setUpClass (creates and deletes a second pool) | `resizeVolume` fails with `Insufficient IOPS capacity`; the volume is `Ready` with `miniops` 50 and `maxiops` 500 | The LUN still carries `cs_50_to_500_iops_<svm>` with min 50 and max 500; no `cs_200_to_500_iops_<svm>` policy exists; the second pool's FlexVol is deleted afterwards | negative (skipped on FAS) |

> **AFF and FAS:** the suite reads the platform from ONTAP (`OntapRestClient.is_all_flash()`). A minimum IOPS above 0 is applied only on AFF. On FAS the plugin rejects it before ONTAP creates a policy; the API error only says the volume create failed, so the test reads the FAS message from `vmops.log` on the management server over SSH. Policy names follow `cs_<min>_to_<max>_iops_<svm>`, with dots in the SVM name replaced by underscores.
>
> **Isolation and cleanup:** each test is independent. `tearDown` destroys the test's VMs and volumes, then removes the ONTAP LUN and any `cs_` policy with no remaining objects. `tearDownClass` force-deletes the pool and the FlexVol.

---

## Cross-suite summary

| Suite | Protocol | Scope | Tests | Status |
| ------- | --------- | ------- | ------- | -------- |
| NFS3 Pool Lifecycle | NFS3 | Cluster | 21 | ✅ |
| NFS3 Pool with Volumes | NFS3 | Cluster | 12 | ✅ |
| NFS3 Zone-Scoped Pool | NFS3 | Zone | 6 | ✅ |
| NFS3 Volume Lifecycle | NFS3 | Cluster | 5 | ✅ |
| NFS3 QoS Offerings | NFS3 | Cluster | 29 | 🆕 |
| NFS3 VM + Volume Attach | NFS3 | Cluster | 29 | ⏳ resize and snapshot cases pending lab validation |
| NFS3 Template Cache Negative | NFS3 | Cluster | 3 | 🆕 |
| iSCSI Pool Lifecycle | iSCSI | Cluster | 21 | ✅ |
| iSCSI Pool with Volumes | iSCSI | Cluster | 13 | ✅ |
| iSCSI Zone-Scoped Pool | iSCSI | Zone | 6 | ✅ |
| iSCSI Volume Lifecycle | iSCSI | Cluster | 5 | ✅ |
| iSCSI QoS Offerings | iSCSI | Cluster | 29 | 🆕 |
| iSCSI VM + Volume Attach | iSCSI | Cluster | 29 | ⏳ resize and snapshot cases pending lab validation; detach limitation |
| iSCSI Template Cache Negative | iSCSI | Cluster | 3 | 🆕 |
| **Total** | | | **211** | **Resize and snapshot cases pending lab validation** |
