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

"""Live VM-with-storage migration matrix for ONTAP NFS3 and iSCSI.

Workflow:
  01-05 same-cluster NFS3 DP/C1/Z chain with root and data volumes
  06-10 same-cluster iSCSI DP/C1/Z chain with root and data volumes
  11-13 same-cluster rejections (cross-protocol and volume-only)
  14 relocate one host once, then NFS3 cross-cluster C1/C2/Z cases
  18-21 iSCSI cross-cluster C1/C2/Z cases
  22 reject a cluster-1 pool mapped to a cluster-2 destination host
"""

import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    destroyVirtualMachine as destroyVirtualMachineAPI,
    migrateVolume as migrateVolumeAPI,
    startVirtualMachine as startVirtualMachineAPI,
)
from marvin.lib.common import list_storage_pools

from migration.migration_test_base import OntapMigrationTestBase


class TestOntapLiveVmWithStorageMigration(OntapMigrationTestBase):
    """Running-VM migrateVirtualMachineWithVolume coverage for ONTAP."""

    nfs_vm = None
    nfs_pool = None
    iscsi_vm = None
    iscsi_pool = None
    cross_cluster_ready = False
    source_host = None
    target_host = None

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
        return {
            "NFS3": [
                ("CLUSTER", cls.cluster.id, 2),
                ("ZONE", None, 2),
            ],
            "ISCSI": [
                ("CLUSTER", cls.cluster.id, 2),
                ("ZONE", None, 2),
            ],
        }

    def _c1a(self, protocol):
        return self._migration_pool(protocol)

    def _c1b(self, protocol):
        return self._migration_pool(protocol, 1)

    def _z1(self, protocol):
        return self._migration_pool(protocol, scope="ZONE")

    def _z2(self, protocol):
        return self._migration_pool(protocol, 1, scope="ZONE")

    def _c2(self, protocol):
        return self._migration_pool(
            protocol,
            cluster_id=self._require_secondary_cluster(),
        )

    def _prepare_default_vm(self):
        host = self._host_for_cluster(self.__class__.cluster.id)
        vm = self._deploy_vm(self._default_storage_tag(), host.id)
        data_volume = self._create_data_volume(self.__class__.default_pool)
        self._attach_volume(vm, data_volume)
        for volume in self._vm_volumes(vm.id):
            self.assertEqual(
                volume.storageid,
                self.__class__.default_pool.id,
                "VM volume was not allocated on DefaultPrimary",
            )
        return vm

    def _prepare_vm_on_pool(self, pool):
        host = self.__class__.source_host or self._host_for_cluster(
            self.__class__.cluster.id
        )
        vm = self._deploy_vm(self._default_storage_tag(), host.id)
        data_volume = self._create_data_volume(
            self.__class__.default_pool
        )
        self._attach_volume(vm, data_volume)
        self._stop_vm(vm)
        self._migrate_stopped_vm_storage(vm, pool)
        vm = self._start_on_host(vm, host)
        for volume in self._vm_volumes(vm.id):
            self.assertEqual(volume.storageid, pool.id)
        return self._get_vm(vm.id)

    def _start_on_host(self, vm, host):
        cmd = startVirtualMachineAPI.startVirtualMachineCmd()
        cmd.id = vm.id
        cmd.hostid = host.id
        self.apiClient.startVirtualMachine(cmd)
        return self._poll_vm(vm.id, "Running")

    def _live_migrate(self, vm, dest_pool, protocol, dest_host=None,
                      dest_cluster_id=None):
        if vm is None:
            raise unittest.SkipTest(
                "Prerequisite VM was not created by an earlier test"
            )
        volumes = self._vm_volumes(vm.id)
        self.assertTrue(len(volumes) >= 2)
        source_pool = self._pool_by_id(volumes[0].storageid)
        old_host_id = self._get_vm(vm.id).hostid
        if dest_cluster_id is None:
            dest_cluster_id = self.__class__.cluster.id
        if dest_host is None:
            host = self._migration_target(vm, dest_cluster_id)
        else:
            host = dest_host
        self._migrate_vm_volumes(
            vm,
            host,
            [(volume, dest_pool) for volume in volumes],
        )
        self._assert_migration_success(
            volumes,
            dest_pool,
            protocol,
            vm=vm,
            expected_vm_state="Running",
            expected_host=host,
            expected_vm_id=vm.id,
            source_pool=source_pool,
        )
        self._assert_live_host_state(
            vm, old_host_id, host, volumes, protocol
        )
        self.assertEqual(host.clusterid, dest_cluster_id)
        self.assertNotEqual(host.id, old_host_id)
        return self._get_vm(vm.id), dest_pool

    def _reject_live_mapping(self, vm, dest_pool, protocol, pattern=None):
        if vm is None:
            raise unittest.SkipTest(
                "Prerequisite VM was not created by an earlier test"
            )
        volumes = self._vm_volumes(vm.id)
        source_pool = self._pool_by_id(volumes[0].storageid)
        snapshot = self._snapshot_state(
            vm=vm,
            volumes=volumes,
            source_pool=source_pool,
            protocol=protocol,
            destination_pool=dest_pool,
            destination_protocol=self._pool_protocol(dest_pool),
        )
        host_snapshot = self.__class__._host_vm_snapshot(vm)
        host = self._migration_target(vm, self.__class__.cluster.id)
        if pattern is None:
            with self.assertRaises(Exception):
                self._migrate_vm_volumes(
                    vm,
                    host,
                    [(volume, dest_pool) for volume in volumes],
                )
        else:
            with self.assertRaisesRegex(Exception, pattern):
                self._migrate_vm_volumes(
                    vm,
                    host,
                    [(volume, dest_pool) for volume in volumes],
                )
        self._assert_state_unchanged(
            snapshot,
            vm=vm,
            source_pool=source_pool,
            protocol=protocol,
            destination_pool=dest_pool,
        )
        self._assert_host_snapshot_unchanged(host_snapshot, vm)

    def _expunge_vm(self, vm):
        if vm is None:
            return
        try:
            current = self._get_vm(vm.id)
            if str(current.state).lower() not in ("stopped", "destroyed"):
                self._stop_vm(vm)
        except Exception:
            pass
        try:
            cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
            cmd.id = vm.id
            cmd.expunge = True
            self.apiClient.destroyVirtualMachine(cmd)
        except Exception:
            pass

    def _expunge_phase_vms(self):
        cls = self.__class__
        self._expunge_vm(cls.nfs_vm)
        self._expunge_vm(cls.iscsi_vm)
        cls.nfs_vm = None
        cls.iscsi_vm = None
        cls.nfs_pool = None
        cls.iscsi_pool = None

    def _ensure_cross_cluster_phase(self):
        cls = self.__class__
        if cls.cross_cluster_ready:
            return
        self._expunge_phase_vms()
        cls._ensure_hosts_across_clusters()
        cls._refresh_host_inventory()
        cls._validate_host_topology({
            "same_primary_cluster": False,
            "multiple_clusters": True,
        })
        cls._validate_host_credentials()
        cls._prepare_live_migration_hosts()
        secondary = cls._require_secondary_cluster()
        cls._recreate_test_network()
        pools = list_storage_pools(cls.apiClient, zoneid=cls.zone.id) or []
        for protocol in ("NFS3", "ISCSI"):
            try:
                cls._protocol_config(protocol)
            except Exception:
                continue
            key = cls._pool_key("CLUSTER", secondary)
            bucket = cls.ontap_pools.setdefault(
                protocol, {}
            ).setdefault(key, [])
            if bucket:
                continue
            found = [
                pool for pool in pools
                if cls._is_compatible_ontap_pool(
                    pool, protocol, "CLUSTER", secondary
                )
            ][:1]
            if found:
                bucket.extend(found)
            else:
                bucket.append(
                    cls._create_ontap_pool(
                        protocol, "CLUSTER", secondary
                    )
                )
            cls._validate_migration_pool(
                bucket[0], protocol, "CLUSTER", secondary
            )
        cls.source_host = cls._host_for_cluster(cls.cluster.id)
        cls.target_host = cls._host_for_cluster(secondary)
        cls.cross_cluster_ready = True

    def _live_cross(self, vm, dest_pool, protocol):
        if vm is None:
            raise unittest.SkipTest(
                "Prerequisite VM was not created by an earlier test"
            )
        secondary = self._require_secondary_cluster()
        current = self._get_vm(vm.id)
        if current.hostid == self.__class__.target_host.id:
            self._stop_vm(vm)
            vm = self._start_on_host(vm, self.__class__.source_host)
        return self._live_migrate(
            vm,
            dest_pool,
            protocol,
            dest_host=self.__class__.target_host,
            dest_cluster_id=secondary,
        )

    def _data_volume(self, vm):
        if vm is None:
            raise unittest.SkipTest(
                "Prerequisite VM was not created by an earlier test"
            )
        for volume in self._vm_volumes(vm.id):
            if str(getattr(volume, "type", "")).upper() != "ROOT":
                return volume
        self.fail("No data volume attached to VM %s" % vm.id)

    def _reset_running_on_pool(self, vm, pool):
        if vm is None:
            raise unittest.SkipTest(
                "Prerequisite VM was not created by an earlier test"
            )
        self._stop_vm(vm)
        self._migrate_stopped_vm_storage(vm, pool)
        host = self.__class__.source_host or self._host_for_cluster(
            self.__class__.cluster.id
        )
        return self._start_on_host(vm, host)

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_01_nfs3_default_primary_to_cluster(self):
        """Live-migrate DefaultPrimary root/data volumes to NFS3 C1a."""
        vm = self._prepare_default_vm()
        pool = self._c1a("NFS3")
        vm, pool = self._live_migrate(vm, pool, "NFS3")
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_02_nfs3_cluster_to_cluster(self):
        """Live-migrate the NFS3 VM from C1a to C1b."""
        pool = self._c1b("NFS3")
        vm, pool = self._live_migrate(
            self.__class__.nfs_vm, pool, "NFS3"
        )
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_03_nfs3_cluster_to_zone(self):
        """Live-migrate the NFS3 VM from C1b to Z1."""
        pool = self._z1("NFS3")
        vm, pool = self._live_migrate(
            self.__class__.nfs_vm, pool, "NFS3"
        )
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_04_nfs3_zone_to_zone(self):
        """Live-migrate the NFS3 VM from Z1 to Z2."""
        pool = self._z2("NFS3")
        vm, pool = self._live_migrate(
            self.__class__.nfs_vm, pool, "NFS3"
        )
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_05_nfs3_zone_to_cluster(self):
        """Live-migrate the NFS3 VM from Z2 back to C1a."""
        pool = self._c1a("NFS3")
        vm, pool = self._live_migrate(
            self.__class__.nfs_vm, pool, "NFS3"
        )
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_06_iscsi_default_primary_to_cluster(self):
        """Live-migrate DefaultPrimary root/data volumes to iSCSI C1a."""
        self._release_idle_pools("NFS3", self.__class__.nfs_pool)
        vm = self._prepare_default_vm()
        pool = self._c1a("ISCSI")
        vm, pool = self._live_migrate(vm, pool, "ISCSI")
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_07_iscsi_cluster_to_cluster(self):
        """Live-migrate the iSCSI VM from C1a to C1b."""
        pool = self._c1b("ISCSI")
        vm, pool = self._live_migrate(
            self.__class__.iscsi_vm, pool, "ISCSI"
        )
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_08_iscsi_cluster_to_zone(self):
        """Live-migrate the iSCSI VM from C1b to Z1."""
        pool = self._z1("ISCSI")
        vm, pool = self._live_migrate(
            self.__class__.iscsi_vm, pool, "ISCSI"
        )
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_09_iscsi_zone_to_zone(self):
        """Live-migrate the iSCSI VM from Z1 to Z2."""
        pool = self._z2("ISCSI")
        vm, pool = self._live_migrate(
            self.__class__.iscsi_vm, pool, "ISCSI"
        )
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_10_iscsi_zone_to_cluster(self):
        """Live-migrate the iSCSI VM from Z2 back to C1a."""
        self._release_idle_pools("ISCSI", self.__class__.iscsi_pool)
        pool = self._c1a("ISCSI")
        vm, pool = self._live_migrate(
            self.__class__.iscsi_vm, pool, "ISCSI"
        )
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_11_reject_nfs3_to_iscsi(self):
        """Reject a live NFS3-to-iSCSI volume mapping."""
        self._reject_live_mapping(
            self.__class__.nfs_vm,
            self._c1a("ISCSI"),
            "NFS3",
            "managed storage can only be 'migrated' to itself",
        )

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_12_reject_iscsi_to_nfs3(self):
        """Reject a live iSCSI-to-NFS3 volume mapping."""
        self._reject_live_mapping(
            self.__class__.iscsi_vm,
            self._c1a("NFS3"),
            "ISCSI",
            "managed storage can only be 'migrated' to itself",
        )

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_13_reject_live_volume_only(self):
        """Reject migrateVolume livemigrate=true on a running attached volume."""
        vm = self.__class__.nfs_vm
        volume = self._data_volume(vm)
        dest = self._c1b("NFS3")
        source_pool = self.__class__.nfs_pool
        volumes = self._vm_volumes(vm.id)
        snapshot = self._snapshot_state(
            vm=vm,
            volumes=volumes,
            source_pool=source_pool,
            protocol="NFS3",
            destination_pool=dest,
        )
        host_snapshot = self.__class__._host_vm_snapshot(vm)
        cmd = migrateVolumeAPI.migrateVolumeCmd()
        cmd.volumeid = volume.id
        cmd.storageid = dest.id
        cmd.livemigrate = True
        with self.assertRaises(Exception) as context:
            self.apiClient.migrateVolume(cmd)
        self.assertIn(
            "migratevirtualmachinewithvolume",
            str(context.exception).lower().replace(" ", ""),
        )
        self._assert_state_unchanged(
            snapshot,
            vm=vm,
            source_pool=source_pool,
            protocol="NFS3",
            destination_pool=dest,
        )
        self._assert_host_snapshot_unchanged(host_snapshot, vm)

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_14_nfs3_cluster_to_cluster(self):
        """After host split, live-migrate NFS3 storage from C1a to C2."""
        self._ensure_cross_cluster_phase()
        vm = self._prepare_vm_on_pool(self._c1a("NFS3"))
        vm, pool = self._live_cross(vm, self._c2("NFS3"), "NFS3")
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_15_nfs3_cluster_to_zone(self):
        """Live-migrate NFS3 storage from C1a to Z1 onto a C2 host."""
        self._ensure_cross_cluster_phase()
        self._expunge_vm(self.__class__.nfs_vm)
        vm = self._prepare_vm_on_pool(self._c1a("NFS3"))
        vm, pool = self._live_cross(vm, self._z1("NFS3"), "NFS3")
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_16_nfs3_zone_to_cluster(self):
        """Live-migrate the NFS3 VM from Z1 to C2."""
        self._ensure_cross_cluster_phase()
        vm, pool = self._live_cross(
            self.__class__.nfs_vm, self._c2("NFS3"), "NFS3"
        )
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_17_nfs3_zone_to_zone(self):
        """Live-migrate NFS3 storage from Z1 to Z2 onto a C2 host."""
        self._ensure_cross_cluster_phase()
        vm = self._reset_running_on_pool(
            self.__class__.nfs_vm, self._z1("NFS3")
        )
        vm, pool = self._live_cross(vm, self._z2("NFS3"), "NFS3")
        self.__class__.nfs_vm = vm
        self.__class__.nfs_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_18_iscsi_cluster_to_cluster(self):
        """Live-migrate iSCSI storage from C1a to C2 onto a C2 host."""
        self._ensure_cross_cluster_phase()
        self._expunge_vm(self.__class__.nfs_vm)
        self.__class__.nfs_vm = None
        vm = self._prepare_vm_on_pool(self._c1a("ISCSI"))
        vm, pool = self._live_cross(vm, self._c2("ISCSI"), "ISCSI")
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_19_iscsi_cluster_to_zone(self):
        """Live-migrate iSCSI storage from C1a to Z1 onto a C2 host."""
        self._ensure_cross_cluster_phase()
        self._expunge_vm(self.__class__.iscsi_vm)
        vm = self._prepare_vm_on_pool(self._c1a("ISCSI"))
        vm, pool = self._live_cross(vm, self._z1("ISCSI"), "ISCSI")
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_20_iscsi_zone_to_cluster(self):
        """Live-migrate the iSCSI VM from Z1 to C2."""
        self._ensure_cross_cluster_phase()
        vm, pool = self._live_cross(
            self.__class__.iscsi_vm, self._c2("ISCSI"), "ISCSI"
        )
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_21_iscsi_zone_to_zone(self):
        """Live-migrate iSCSI storage from Z1 to Z2 onto a C2 host."""
        self._ensure_cross_cluster_phase()
        self._expunge_vm(self.__class__.iscsi_vm)
        vm = self._prepare_vm_on_pool(self._z1("ISCSI"))
        vm, pool = self._live_cross(vm, self._z2("ISCSI"), "ISCSI")
        self.__class__.iscsi_vm = vm
        self.__class__.iscsi_pool = pool

    @attr(tags=["ontap_migration", "live_storage"], required_hardware=True)
    def test_22_reject_pool_not_reachable_from_destination_host(self):
        """Reject mapping a cluster-1 pool to a host that lives in cluster 2."""
        self._ensure_cross_cluster_phase()
        vm = self.__class__.nfs_vm
        if vm is None:
            vm = self._prepare_vm_on_pool(self._z1("NFS3"))
        elif self._get_vm(vm.id).hostid != self.__class__.source_host.id:
            self._stop_vm(vm)
            vm = self._start_on_host(vm, self.__class__.source_host)
        self.__class__.nfs_vm = vm
        volumes = self._vm_volumes(vm.id)
        source_pool = self._pool_by_id(volumes[0].storageid)
        dest = self._c1a("NFS3")
        snapshot = self._snapshot_state(
            vm=vm,
            volumes=volumes,
            source_pool=source_pool,
            protocol="NFS3",
            destination_pool=dest,
        )
        host_snapshot = self.__class__._host_vm_snapshot(vm)
        host = self.__class__.target_host
        with self.assertRaises(Exception):
            self._migrate_vm_volumes(
                vm,
                host,
                [(volume, dest) for volume in volumes],
            )
        self._assert_state_unchanged(
            snapshot,
            vm=vm,
            source_pool=source_pool,
            protocol="NFS3",
            destination_pool=dest,
        )
        self._assert_host_snapshot_unchanged(host_snapshot, vm)
