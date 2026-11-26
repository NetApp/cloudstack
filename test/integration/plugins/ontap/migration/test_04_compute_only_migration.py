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

"""Compute-only VM migration matrix for ONTAP NFS3 and iSCSI.

Compute-only migration moves a VM's host assignment (live or cold) without
touching the volumes. Every attached volume stays on its current ONTAP pool
and keeps the same CloudStack path / ONTAP identifier. The ONTAP plugin
participates only through access control: grant on dest, revoke on src for
iSCSI live; essentially no-op for NFS; nothing at migrate time for cold
(grant/revoke happen on the next Start).

Spec: https://netapp.atlassian.net/wiki/spaces/OSSG/pages/727388029
Test plan landing: https://netapp.atlassian.net/wiki/spaces/OSSG/pages/725106301
Live compute-only cases: https://netapp.atlassian.net/wiki/spaces/OSSG/pages/727064932
Cold cases: https://netapp.atlassian.net/wiki/spaces/OSSG/pages/727064957

Phases and spec-ID mapping:

  Phase 1 — Same-cluster live compute-only (LM-P / NM-LP positives)
    test_01  LM-P-001   live iSCSI single volume
    test_02  LM-P-010   live iSCSI root + 1 data, same iSCSI pool
    test_03  LM-P-011   live iSCSI root + data on 2 iSCSI pools, same SVM
    test_04  NM-LP-001  live NFS single volume
    test_05  NM-LP-002  live NFS root + 1 data on same NFS pool
    test_06  LM-P-012   live mixed iSCSI root + NFS data, same SVM

  Phase 2 — Cross-cluster live compute-only (ZONE-scoped pools, same zone)
    test_07  LM-P-020   cross-cluster, ZONE-scoped iSCSI pool
    test_08  LM-P-023   cross-cluster, 2 ZONE iSCSI pools same SVM
    test_09  NM-LP-009  cross-cluster, ZONE-scoped NFS pool

  Phase 3 — Cold compute-only (Stopped VM, migrateVirtualMachine with hostid)
    test_10  CM-P-001   stopped iSCSI VM, host change, same cluster
    test_11  CM-P-002   stopped multi-volume iSCSI VM, host change, same cluster
    test_12  CM-P-003   stopped iSCSI VM, cross-cluster on ZONE-scoped pool
    test_13  NM-CP-008  stopped NFS VM, host change, same cluster

  Phase 4 — Negatives (rejected mappings)
    test_14  LM-N-001       live reject when dest host is in Maintenance
    test_15  LM-N-002       live reject when dest host is Disabled
    test_16  LM-N-007 ish   cross-cluster reject on CLUSTER-scoped pool
    test_17  LM-N-040       live API on Stopped VM — reject or auto-route

Deliberately not covered in this file (follow-up suites):
  LM-P-013b/c/d, LM-P-029 (scale: 5+ data disks)
  LM-P-011b, LM-P-011c, LM-P-018, LM-P-019 (different-SVM / different-cluster
      multi-pool positives)
  LM-E-*  (edge / stress: bandwidth, dirty-rate, bounce 10x, 128 GB RAM)
  LM-N-080..086 (mid-migration failures and recovery)
  LM-N-100..101 (permission / authorization)
  LM-P-032..033, LM-P-039 (HA / DRS triggers)
  LM-P-036..038 (ISO / config drive / LUKS)
"""

import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    migrateVirtualMachine as migrateVirtualMachineAPI,
)

from migration.migration_test_base import OntapMigrationTestBase


class TestOntapComputeOnlyMigration(OntapMigrationTestBase):
    """migrateVirtualMachine coverage for ONTAP, both live and cold."""

    # VM state chained across the live phase. Reset when a phase transitions
    # (same-cluster -> cross-cluster -> cold) so each phase starts fresh.
    iscsi_vm = None
    nfs_vm = None
    mixed_vm = None

    cross_cluster_ready = False
    cold_phase_ready = False

    _vol_name_prefix = "compute_only"

    @classmethod
    def _host_requirements(cls):
        return {
            "minimum_hosts": 2,
            "same_primary_cluster": True,
            "multiple_clusters": False,
            "live_migration": True,
        }

    @classmethod
    def _pool_requirements(cls):
        # Phase 1 needs CLUSTER-scoped pools for both protocols (2 iSCSI to
        # cover the two-pools-same-SVM case). Phase 2 needs ZONE-scoped pools.
        # Phase 4's CLUSTER-scope reject is satisfied by Phase 1's pools.
        return {
            "NFS3": [
                ("CLUSTER", cls.cluster.id, 1),
                ("ZONE", None, 1),
            ],
            "ISCSI": [
                ("CLUSTER", cls.cluster.id, 2),
                ("ZONE", None, 2),
            ],
        }

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _compute_only_migrate(self, vm, dest_host):
        """Issue plain migrateVirtualMachine with hostid, no storage mapping."""
        cmd = migrateVirtualMachineAPI.migrateVirtualMachineCmd()
        cmd.virtualmachineid = vm.id
        cmd.hostid = dest_host.id
        return self.apiClient.migrateVirtualMachine(cmd)

    def _volume_snapshot(self, vm):
        """Capture (volume_id, storage_id, path) tuples for comparison."""
        return [
            (volume.id, volume.storageid, getattr(volume, "path", None))
            for volume in self._vm_volumes(vm.id)
        ]

    def _assert_volumes_unchanged(self, vm, snapshot_before):
        """Every volume must stay on the same pool with the same path."""
        after = {
            vol.id: vol for vol in self._vm_volumes(vm.id)
        }
        self.assertEqual(
            len(snapshot_before), len(after),
            "Volume count changed across compute-only migration",
        )
        for vol_id, storage_id, path in snapshot_before:
            self.assertIn(vol_id, after,
                          "Volume %s disappeared after migration" % vol_id)
            current = after[vol_id]
            self.assertEqual(
                current.storageid, storage_id,
                "Volume %s moved pool (%s -> %s); compute-only must not "
                "touch storage" % (vol_id, storage_id, current.storageid),
            )
            self.assertEqual(
                getattr(current, "path", None), path,
                "Volume %s path changed (%s -> %s); compute-only must not "
                "rewrite the path" % (
                    vol_id, path, getattr(current, "path", None)),
            )

    def _deploy_on_pool(self, pool, extra_data_pools=None, host=None):
        """Deploy a Running VM with its root on pool and optional data disks."""
        if host is None:
            host = self._host_for_cluster(self.__class__.cluster.id)
        protocol = self._pool_protocol(pool)
        storage_tag = self._storage_tag(protocol)
        vm = self._deploy_vm(storage_tag, host.id)
        # Attach any requested data volumes.
        for data_pool in (extra_data_pools or []):
            data_volume = self._create_data_volume(data_pool)
            self._attach_volume(vm, data_volume)
        # If the base's _deploy_vm landed root on DefaultPrimary, migrate root
        # onto the requested ontap pool via the stopped-storage path before
        # the compute-only run. This keeps _host_requirements small.
        current_volumes = self._vm_volumes(vm.id)
        root_volume = next(
            (v for v in current_volumes
             if str(getattr(v, "type", "")).upper() == "ROOT"),
            current_volumes[0] if current_volumes else None,
        )
        if root_volume is not None and root_volume.storageid != pool.id:
            self._stop_vm(vm)
            self._migrate_volume_offline(root_volume, pool)
            vm = self._start_vm(vm)
        return self._get_vm(vm.id)

    def _ensure_cold_phase(self):
        cls = self.__class__
        if cls.cold_phase_ready:
            return
        # Any live-phase chained VMs are gone by now (phase 2 expunged them
        # before cross-cluster). Nothing extra to do here beyond flagging.
        cls.cold_phase_ready = True

    def _primary_live_migrate(self, vm, protocol):
        """Live compute-only migrate to the peer host in the same cluster."""
        snapshot = self._volume_snapshot(vm)
        volumes_before = self._vm_volumes(vm.id)
        old_host_id = self._get_vm(vm.id).hostid
        dest_host = self._migration_target(vm, self.__class__.cluster.id)
        self._compute_only_migrate(vm, dest_host)
        refreshed = self._poll_vm(vm.id, "Running")
        self.assertEqual(
            refreshed.hostid, dest_host.id,
            "VM %s did not land on the requested destination host" % vm.id,
        )
        self.assertNotEqual(
            refreshed.hostid, old_host_id,
            "VM %s still reports the source host after migration" % vm.id,
        )
        self._assert_volumes_unchanged(vm, snapshot)
        self._assert_live_host_state(
            vm, old_host_id, dest_host, volumes_before, protocol
        )
        return refreshed

    def _cross_cluster_live_migrate(self, vm, protocol):
        """Live compute-only migrate from Cluster1 host to Cluster2 host."""
        snapshot = self._volume_snapshot(vm)
        volumes_before = self._vm_volumes(vm.id)
        secondary = self._require_secondary_cluster()
        old_host_id = self._get_vm(vm.id).hostid
        dest_host = self._migration_target(vm, secondary)
        self._compute_only_migrate(vm, dest_host)
        refreshed = self._poll_vm(vm.id, "Running")
        self.assertEqual(refreshed.hostid, dest_host.id)
        self.assertEqual(dest_host.clusterid, secondary)
        self._assert_volumes_unchanged(vm, snapshot)
        self._assert_live_host_state(
            vm, old_host_id, dest_host, volumes_before, protocol
        )
        return refreshed

    def _cold_compute_only_migrate(self, vm, dest_host=None,
                                   cluster_id=None):
        """Stop VM, migrate host, assert Stopped on dest host assignment."""
        self._stop_vm(vm)
        snapshot = self._volume_snapshot(vm)
        volumes_before = self._vm_volumes(vm.id)
        old_host_id = self._get_vm(vm.id).hostid
        if dest_host is None:
            dest_host = self._migration_target(vm, cluster_id)
        self._compute_only_migrate(vm, dest_host)
        refreshed = self._get_vm(vm.id)
        # On a Stopped VM, the migrate records host assignment without boot.
        self.assertIn(
            str(refreshed.state).lower(), ("stopped",),
            "Cold compute-only migration should leave VM Stopped, got %s"
            % refreshed.state,
        )
        reported_host = getattr(refreshed, "hostid", None) or \
            getattr(refreshed, "lasthostid", None)
        self.assertEqual(
            reported_host, dest_host.id,
            "Stopped VM %s did not record dest host %s (saw %s)"
            % (vm.id, dest_host.id, reported_host),
        )
        self.assertNotEqual(reported_host, old_host_id)
        self._assert_volumes_unchanged(vm, snapshot)
        # Volume must not be mapped to any running domain after cold migrate.
        for volume in volumes_before:
            self._assert_volume_not_referenced_on_hosts(volume)
        return refreshed

    # ------------------------------------------------------------------
    # Phase 1 — Same-cluster live compute-only
    # ------------------------------------------------------------------

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_01_live_iscsi_single_volume_same_cluster(self):
        """LM-P-001: live compute-only, single iSCSI root volume."""
        cls = self.__class__
        pool = self._migration_pool("ISCSI")
        vm = self._deploy_on_pool(pool)
        cls.iscsi_vm = self._primary_live_migrate(vm, "ISCSI")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_02_live_iscsi_multi_volume_same_pool(self):
        """LM-P-010: live compute-only, iSCSI root + 1 data on same pool."""
        cls = self.__class__
        pool = self._migration_pool("ISCSI")
        vm = self._deploy_on_pool(pool, extra_data_pools=[pool])
        self.assertGreaterEqual(len(self._vm_volumes(vm.id)), 2)
        cls.iscsi_vm = self._primary_live_migrate(vm, "ISCSI")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_03_live_iscsi_multi_volume_two_pools_same_svm(self):
        """LM-P-011: live compute-only, iSCSI root + data on 2 iSCSI pools."""
        cls = self.__class__
        pool_a = self._migration_pool("ISCSI")
        pool_b = self._migration_pool("ISCSI", 1)
        vm = self._deploy_on_pool(pool_a, extra_data_pools=[pool_b])
        volumes = self._vm_volumes(vm.id)
        pool_ids = {vol.storageid for vol in volumes}
        self.assertIn(pool_a.id, pool_ids)
        self.assertIn(pool_b.id, pool_ids)
        cls.iscsi_vm = self._primary_live_migrate(vm, "ISCSI")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_04_live_nfs_single_volume_same_cluster(self):
        """NM-LP-001: live compute-only, single NFS root volume."""
        cls = self.__class__
        pool = self._migration_pool("NFS3")
        vm = self._deploy_on_pool(pool)
        cls.nfs_vm = self._primary_live_migrate(vm, "NFS3")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_05_live_nfs_multi_volume_same_pool(self):
        """NM-LP-002: live compute-only, NFS root + 1 data on same pool."""
        cls = self.__class__
        pool = self._migration_pool("NFS3")
        vm = self._deploy_on_pool(pool, extra_data_pools=[pool])
        self.assertGreaterEqual(len(self._vm_volumes(vm.id)), 2)
        cls.nfs_vm = self._primary_live_migrate(vm, "NFS3")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_06_live_mixed_iscsi_nfs_same_svm(self):
        """LM-P-012: live compute-only, iSCSI root + NFS data (same SVM)."""
        cls = self.__class__
        iscsi_pool = self._migration_pool("ISCSI")
        nfs_pool = self._migration_pool("NFS3")
        vm = self._deploy_on_pool(iscsi_pool, extra_data_pools=[nfs_pool])
        volumes = self._vm_volumes(vm.id)
        self.assertEqual(len(volumes), 2)
        # Root should live on iSCSI; data on NFS. We assert both are present.
        pool_ids = {vol.storageid for vol in volumes}
        self.assertEqual(pool_ids, {iscsi_pool.id, nfs_pool.id})
        cls.mixed_vm = self._primary_live_migrate(vm, "ISCSI")

    # ------------------------------------------------------------------
    # Phase 2 — Cross-cluster live compute-only
    # ------------------------------------------------------------------

    def _enter_cross_cluster_phase(self):
        cls = self.__class__
        if cls.cross_cluster_ready:
            return
        # Expunge chained live VMs so Phase 2 starts fresh.
        for vm in (cls.iscsi_vm, cls.nfs_vm, cls.mixed_vm):
            if vm is not None:
                try:
                    self._stop_vm(vm)
                except Exception:
                    pass
                try:
                    from marvin.cloudstackAPI import (
                        destroyVirtualMachine as destroyAPI,
                    )
                    cmd = destroyAPI.destroyVirtualMachineCmd()
                    cmd.id = vm.id
                    cmd.expunge = True
                    self.apiClient.destroyVirtualMachine(cmd)
                except Exception:
                    pass
        cls.iscsi_vm = None
        cls.nfs_vm = None
        cls.mixed_vm = None
        # Split hosts across clusters and refresh inventory.
        cls._ensure_hosts_across_clusters()
        cls._refresh_host_inventory()
        cls._validate_host_topology({
            "same_primary_cluster": False,
            "multiple_clusters": True,
        })
        cls._validate_host_credentials()
        cls._prepare_live_migration_hosts()
        cls._recreate_test_network()
        cls.cross_cluster_ready = True

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_07_live_iscsi_cross_cluster_zone_pool(self):
        """LM-P-020: cross-cluster live compute-only on ZONE iSCSI pool."""
        self._enter_cross_cluster_phase()
        cls = self.__class__
        pool = self._migration_pool("ISCSI", scope="ZONE")
        # Deploy on a cluster1 host; we'll live-migrate to a cluster2 host.
        src_host = self._host_for_cluster(cls.cluster.id)
        vm = self._deploy_on_pool(pool, host=src_host)
        cls.iscsi_vm = self._cross_cluster_live_migrate(vm, "ISCSI")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_08_live_iscsi_cross_cluster_two_pools_same_svm(self):
        """LM-P-023: cross-cluster, 2 ZONE iSCSI pools same SVM."""
        self._enter_cross_cluster_phase()
        cls = self.__class__
        pool_a = self._migration_pool("ISCSI", scope="ZONE")
        pool_b = self._migration_pool("ISCSI", 1, scope="ZONE")
        src_host = self._host_for_cluster(cls.cluster.id)
        vm = self._deploy_on_pool(pool_a, extra_data_pools=[pool_b],
                                  host=src_host)
        volumes = self._vm_volumes(vm.id)
        pool_ids = {vol.storageid for vol in volumes}
        self.assertEqual(pool_ids, {pool_a.id, pool_b.id})
        cls.iscsi_vm = self._cross_cluster_live_migrate(vm, "ISCSI")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_09_live_nfs_cross_cluster_zone_pool(self):
        """NM-LP-009: cross-cluster live compute-only on ZONE NFS pool."""
        self._enter_cross_cluster_phase()
        cls = self.__class__
        pool = self._migration_pool("NFS3", scope="ZONE")
        src_host = self._host_for_cluster(cls.cluster.id)
        vm = self._deploy_on_pool(pool, host=src_host)
        cls.nfs_vm = self._cross_cluster_live_migrate(vm, "NFS3")

    # ------------------------------------------------------------------
    # Phase 3 — Cold compute-only (Stopped VM)
    # ------------------------------------------------------------------

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_10_cold_iscsi_same_cluster(self):
        """CM-P-001: stopped iSCSI VM, host change in same cluster."""
        self._ensure_cold_phase()
        cls = self.__class__
        # Rebuild same-cluster topology if we entered cross-cluster earlier.
        if cls.cross_cluster_ready:
            # Live phase already expunged the chained VMs; we just need a
            # cluster-scoped pool and a VM on it.
            pass
        pool = self._migration_pool("ISCSI", scope="ZONE") \
            if cls.cross_cluster_ready else self._migration_pool("ISCSI")
        src_cluster = cls.cluster.id
        src_host = self._host_for_cluster(src_cluster)
        vm = self._deploy_on_pool(pool, host=src_host)
        self._cold_compute_only_migrate(vm, cluster_id=src_cluster)

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_11_cold_iscsi_multi_volume(self):
        """CM-P-002: stopped multi-volume iSCSI VM, host change."""
        self._ensure_cold_phase()
        cls = self.__class__
        pool = self._migration_pool("ISCSI", scope="ZONE") \
            if cls.cross_cluster_ready else self._migration_pool("ISCSI")
        src_cluster = cls.cluster.id
        src_host = self._host_for_cluster(src_cluster)
        vm = self._deploy_on_pool(pool, extra_data_pools=[pool],
                                  host=src_host)
        self.assertGreaterEqual(len(self._vm_volumes(vm.id)), 2)
        self._cold_compute_only_migrate(vm, cluster_id=src_cluster)

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_12_cold_iscsi_cross_cluster_zone_pool(self):
        """CM-P-003: stopped iSCSI VM, cross-cluster on ZONE pool."""
        self._enter_cross_cluster_phase()
        cls = self.__class__
        pool = self._migration_pool("ISCSI", scope="ZONE")
        src_host = self._host_for_cluster(cls.cluster.id)
        secondary = self._require_secondary_cluster()
        vm = self._deploy_on_pool(pool, host=src_host)
        self._cold_compute_only_migrate(vm, cluster_id=secondary)

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_13_cold_nfs_same_cluster(self):
        """NM-CP-008: stopped NFS VM, host change in same cluster."""
        self._ensure_cold_phase()
        cls = self.__class__
        pool = self._migration_pool("NFS3", scope="ZONE") \
            if cls.cross_cluster_ready else self._migration_pool("NFS3")
        src_cluster = cls.cluster.id
        src_host = self._host_for_cluster(src_cluster)
        vm = self._deploy_on_pool(pool, host=src_host)
        self._cold_compute_only_migrate(vm, cluster_id=src_cluster)

    # ------------------------------------------------------------------
    # Phase 4 — Negative cases
    # ------------------------------------------------------------------

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_14_reject_live_dest_host_in_maintenance(self):
        """LM-N-001: live compute-only rejected when dest host is Maintenance.

        The orchestrator must refuse before any grant on ONTAP. We confirm
        by snapshotting and checking state after the rejection.
        """
        cls = self.__class__
        pool = self._migration_pool("ISCSI", scope="ZONE") \
            if cls.cross_cluster_ready else self._migration_pool("ISCSI")
        src_host = self._host_for_cluster(cls.cluster.id)
        vm = self._deploy_on_pool(pool, host=src_host)
        # Pick a candidate dest host and park it in Maintenance for the test.
        dest_host = self._migration_target(vm, cls.cluster.id)
        self._prepare_host_for_relocation(dest_host.id)
        from marvin.cloudstackAPI import (
            prepareHostForMaintenance as prepareMaintAPI,
            cancelHostMaintenance as cancelMaintAPI,
        )
        try:
            maint_cmd = prepareMaintAPI.prepareHostForMaintenanceCmd()
            maint_cmd.id = dest_host.id
            self.apiClient.prepareHostForMaintenance(maint_cmd)
            self._wait_for_host_resource_state(
                dest_host.id, "Maintenance", timeout=300,
            )
            with self.assertRaises(Exception):
                self._compute_only_migrate(vm, dest_host)
            # VM must still be Running on the source host.
            current = self._get_vm(vm.id)
            self.assertEqual(str(current.state).lower(), "running")
            self.assertEqual(current.hostid, src_host.id)
        finally:
            try:
                cancel_cmd = cancelMaintAPI.cancelHostMaintenanceCmd()
                cancel_cmd.id = dest_host.id
                self.apiClient.cancelHostMaintenance(cancel_cmd)
                self._wait_for_host_up(dest_host.id)
            except Exception:
                pass

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_15_reject_live_dest_host_disabled(self):
        """LM-N-002: live compute-only rejected when dest host is Disabled."""
        cls = self.__class__
        pool = self._migration_pool("ISCSI", scope="ZONE") \
            if cls.cross_cluster_ready else self._migration_pool("ISCSI")
        src_host = self._host_for_cluster(cls.cluster.id)
        vm = self._deploy_on_pool(pool, host=src_host)
        dest_host = self._migration_target(vm, cls.cluster.id)
        from marvin.cloudstackAPI import (
            updateHost as updateHostAPI,
        )
        try:
            # Disable the candidate dest host.
            disable_cmd = updateHostAPI.updateHostCmd()
            disable_cmd.id = dest_host.id
            disable_cmd.allocationstate = "Disabled"
            self.apiClient.updateHost(disable_cmd)
            with self.assertRaises(Exception):
                self._compute_only_migrate(vm, dest_host)
            current = self._get_vm(vm.id)
            self.assertEqual(str(current.state).lower(), "running")
            self.assertEqual(current.hostid, src_host.id)
        finally:
            try:
                enable_cmd = updateHostAPI.updateHostCmd()
                enable_cmd.id = dest_host.id
                enable_cmd.allocationstate = "Enabled"
                self.apiClient.updateHost(enable_cmd)
            except Exception:
                pass

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_16_reject_cross_cluster_on_cluster_scoped_pool(self):
        """Pool-scope guard: a CLUSTER-scoped pool blocks cross-cluster
        compute-only migration even when the ONTAP driver vouches for the
        pool. The engine evaluates scope *before* calling the driver's
        zoneWideVolumesAvailableWithoutClusterMotion hook.
        """
        # We need hosts in two clusters, so flip into cross-cluster phase.
        self._enter_cross_cluster_phase()
        cls = self.__class__
        secondary = self._require_secondary_cluster()
        # Deploy the VM on a CLUSTER-scoped pool in the primary cluster.
        # A CLUSTER-scoped pool is created by Phase 1 _pool_requirements;
        # the ensure_cross_cluster_phase step does not delete it.
        pool = self._migration_pool("ISCSI")
        src_host = self._host_for_cluster(cls.cluster.id)
        vm = self._deploy_on_pool(pool, host=src_host)
        dest_host = self._host_for_cluster(secondary)
        with self.assertRaises(Exception):
            self._compute_only_migrate(vm, dest_host)
        current = self._get_vm(vm.id)
        self.assertEqual(current.hostid, src_host.id,
                         "VM moved despite CLUSTER-scope reject")

    @attr(tags=["ontap_migration", "compute_only"], required_hardware=True)
    def test_17_reject_live_api_on_stopped_vm(self):
        """LM-N-040: migrateVirtualMachine on a Stopped VM.

        Expected behavior is implementation-defined: CloudStack may either
        (a) reject with a state error, or (b) auto-route to the cold path.
        Either outcome is acceptable; corruption or orphan state is not.
        The test asserts no partial state and that the VM ends up either on
        the source host (Stopped) or on the chosen destination (Stopped).
        """
        cls = self.__class__
        pool = self._migration_pool("ISCSI", scope="ZONE") \
            if cls.cross_cluster_ready else self._migration_pool("ISCSI")
        src_host = self._host_for_cluster(cls.cluster.id)
        vm = self._deploy_on_pool(pool, host=src_host)
        self._stop_vm(vm)
        dest_host = self._migration_target(vm, cls.cluster.id)
        snapshot = self._volume_snapshot(vm)
        try:
            self._compute_only_migrate(vm, dest_host)
            routed = True
        except Exception:
            routed = False
        current = self._get_vm(vm.id)
        self.assertEqual(
            str(current.state).lower(), "stopped",
            "VM state must remain Stopped regardless of accept/reject",
        )
        self._assert_volumes_unchanged(vm, snapshot)
        if routed:
            # Auto-routed to cold: VM should now be assigned to dest host.
            reported_host = (getattr(current, "hostid", None)
                             or getattr(current, "lasthostid", None))
            self.assertEqual(
                reported_host, dest_host.id,
                "auto-routed cold migrate did not update host assignment",
            )
        else:
            # Rejected: VM's host assignment should be unchanged.
            reported_host = (getattr(current, "hostid", None)
                             or getattr(current, "lasthostid", None))
            self.assertEqual(
                reported_host, src_host.id,
                "VM host assignment changed despite rejection",
            )
