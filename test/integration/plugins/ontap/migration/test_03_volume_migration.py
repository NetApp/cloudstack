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

"""Ordered ONTAP volume-only migration tests.

Workflow:
  01-06 carry one detached NFS3 volume through
        DefaultPrimary -> C1a -> C1b -> C2 -> Z1 -> Z2 -> C1a
  07-12 carry one detached iSCSI volume through the same pool scopes
  13 migrate an NFS3 data volume attached to a stopped VM from C1a to Z1
  14 migrate an iSCSI data volume attached to a stopped VM from C1a to Z1
  15-16 migrate stopped-attached DefaultPrimary data to ONTAP NFS3/iSCSI
  17-20 reject detached and stopped-attached cross-protocol migration
  21 reject running-attached migration without livemigrate
  22 reject running-attached migration with livemigrate
  23 reject migration to the volume's current pool

Every successful migration verifies CloudStack identity and attachment state,
the destination ONTAP object and access state, and source cleanup when the
source is ONTAP. Every rejection verifies that CloudStack and ONTAP state
remain unchanged.
"""

import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import migrateVolume as migrateVolumeAPI

from migration.migration_test_base import OntapMigrationTestBase


class TestOntapVolumeMigration(OntapMigrationTestBase):
    """Verify detached, stopped-attached, and rejected volume migrations."""

    nfs_volume = None
    iscsi_volume = None
    nfs_c1a = None
    nfs_c1b = None
    nfs_c2 = None
    nfs_z1 = None
    nfs_z2 = None
    iscsi_c1a = None
    iscsi_c1b = None
    iscsi_c2 = None
    iscsi_z1 = None
    iscsi_z2 = None
    nfs_vm = None
    nfs_attached_volume = None
    iscsi_vm = None
    iscsi_attached_volume = None

    @classmethod
    def setUpClass(cls):
        super(TestOntapVolumeMigration, cls).setUpClass()
        cls._initialize_pools()

    @classmethod
    def _host_requirements(cls):
        return {
            "minimum_hosts": 2,
            "same_primary_cluster": False,
            "multiple_clusters": True,
        }

    @classmethod
    def _pool_requirements(cls):
        secondary_cluster_id = cls._require_secondary_cluster()
        requirements = [
            ("CLUSTER", cls.cluster.id, 2),
            ("CLUSTER", secondary_cluster_id, 1),
            ("ZONE", None, 2),
        ]
        return {
            "NFS3": list(requirements),
            "ISCSI": list(requirements),
        }

    @classmethod
    def _initialize_pools(cls):
        secondary_cluster_id = cls._require_secondary_cluster()
        cls.nfs_c1a = cls._migration_pool("NFS3")
        cls.nfs_c1b = cls._migration_pool("NFS3", 1)
        cls.nfs_c2 = cls._migration_pool(
            "NFS3", cluster_id=secondary_cluster_id
        )
        cls.nfs_z1 = cls._migration_pool("NFS3", scope="ZONE")
        cls.nfs_z2 = cls._migration_pool("NFS3", 1, scope="ZONE")
        cls.iscsi_c1a = cls._migration_pool("ISCSI")
        cls.iscsi_c1b = cls._migration_pool("ISCSI", 1)
        cls.iscsi_c2 = cls._migration_pool(
            "ISCSI", cluster_id=secondary_cluster_id
        )
        cls.iscsi_z1 = cls._migration_pool("ISCSI", scope="ZONE")
        cls.iscsi_z2 = cls._migration_pool("ISCSI", 1, scope="ZONE")

    @classmethod
    def _require_class_state(cls, *attributes):
        missing = [
            attribute for attribute in attributes
            if getattr(cls, attribute, None) is None
        ]
        if missing:
            raise unittest.SkipTest(
                "Required earlier test state is unavailable: %s"
                % ", ".join(missing)
            )

    def _migrate_detached(self, protocol, source_pool, destination_pool):
        cls = self.__class__
        attribute = "nfs_volume" if protocol == "NFS3" else "iscsi_volume"
        cls._require_class_state(attribute)
        volume = self._get_volume(getattr(cls, attribute).id)
        source_path = volume.path
        migrated = self._migrate_volume_offline(volume, destination_pool)
        current = self._assert_migration_success(
            [volume],
            destination_pool,
            protocol,
            expected_vm_id=None,
            source_pool=source_pool,
        )[0]
        if protocol != "ISCSI":
            self.assertNotEqual(current.path, source_path)
        setattr(cls, attribute, migrated)
        self._assert_offline_host_state(
            protocol, destination_pool, volumes=[current]
        )

    def _root_volume(self, vm):
        roots = [
            volume for volume in self._vm_volumes(vm.id)
            if str(getattr(volume, "type", "")).upper() == "ROOT"
        ]
        self.assertEqual(len(roots), 1, "VM %s must have one root volume" % vm.id)
        return roots[0]

    def _prepare_stopped_attached(self, protocol, source_pool):
        vm = self._deploy_vm(
            self._default_storage_tag(),
            self._host_for_cluster(self.__class__.cluster.id).id,
        )
        root = self._root_volume(vm)
        root_pool_id = root.storageid
        volume = self._create_data_volume(source_pool)
        attached = self._attach_volume(vm, volume)
        self._stop_vm(vm)
        return vm, attached, root.id, root_pool_id

    def _migrate_stopped_attached(
            self, protocol, source_pool, destination_pool):
        vm, volume, root_id, root_pool_id = (
            self._prepare_stopped_attached(protocol, source_pool)
        )
        source_path = volume.path
        self._migrate_volume_offline(volume, destination_pool)
        current = self._assert_migration_success(
            [volume],
            destination_pool,
            protocol,
            vm=vm,
            expected_vm_state="Stopped",
            expected_vm_id=vm.id,
            source_pool=(
                None if source_pool.id == self.__class__.default_pool.id
                else source_pool
            ),
        )[0]
        if protocol != "ISCSI":
            self.assertNotEqual(current.path, source_path)
        root = self._get_volume(root_id)
        self.assertEqual(root.storageid, root_pool_id)
        self.assertEqual(
            getattr(root, "virtualmachineid", None),
            vm.id,
        )
        self._assert_offline_host_state(
            protocol, destination_pool, vm=vm, volumes=[current]
        )
        return self._get_vm(vm.id), current

    def _request_volume_migration(self, volume, pool, live_value=None):
        cmd = migrateVolumeAPI.migrateVolumeCmd()
        cmd.volumeid = volume.id
        cmd.storageid = pool.id
        if live_value is not None:
            cmd.livemigrate = live_value
        return self.apiClient.migrateVolume(cmd)

    def _assert_rejected(
            self, vm, volume, source_pool, destination_pool, protocol,
            live_value, pattern=None, destination_protocol=None):
        snapshot = self._snapshot_state(
            vm=vm,
            volumes=[volume],
            source_pool=source_pool,
            protocol=protocol,
            destination_pool=destination_pool,
            destination_protocol=destination_protocol,
        )
        host_snapshot = (
            self.__class__._host_vm_snapshot(vm)
            if vm is not None
            else self.__class__._host_volume_snapshot(volume)
        )
        dest_ids = self._volume_ids_on_pool(destination_pool)
        with self.assertRaises(Exception) as context:
            self._request_volume_migration(
                volume, destination_pool, live_value
            )
        if pattern is not None:
            message = str(context.exception).lower().replace(" ", "")
            self.assertIn(pattern.lower().replace(" ", ""), message)
        self._finish_rejected_migration(
            [volume], destination_pool, dest_ids
        )
        self._assert_state_unchanged(
            snapshot,
            vm=vm,
            source_pool=source_pool,
            protocol=protocol,
            destination_pool=destination_pool,
        )
        if vm is not None:
            self._assert_host_snapshot_unchanged(host_snapshot, vm)
        else:
            self.assertEqual(
                self.__class__._host_volume_snapshot(volume),
                host_snapshot,
                "Rejected migration changed KVM volume references",
            )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_01_nfs3_detached_default_primary_to_cluster(self):
        """Migrate a detached NFS3 volume from DefaultPrimary to C1a."""
        self.__class__.nfs_volume = self._create_data_volume(
            self.__class__.default_pool
        )
        self._migrate_detached(
            "NFS3", self.__class__.default_pool, self.__class__.nfs_c1a
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_02_nfs3_detached_cluster_to_cluster_same_cluster(self):
        """Migrate the detached NFS3 volume from C1a to C1b."""
        self._migrate_detached(
            "NFS3", self.__class__.nfs_c1a, self.__class__.nfs_c1b
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_03_nfs3_detached_cluster_to_cluster_across_clusters(self):
        """Migrate the detached NFS3 volume from C1b to C2."""
        self._migrate_detached(
            "NFS3", self.__class__.nfs_c1b, self.__class__.nfs_c2
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_04_nfs3_detached_cluster_to_zone(self):
        """Migrate the detached NFS3 volume from C2 to Z1."""
        self._migrate_detached(
            "NFS3", self.__class__.nfs_c2, self.__class__.nfs_z1
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_05_nfs3_detached_zone_to_zone(self):
        """Migrate the detached NFS3 volume from Z1 to Z2."""
        self._migrate_detached(
            "NFS3", self.__class__.nfs_z1, self.__class__.nfs_z2
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_06_nfs3_detached_zone_to_cluster(self):
        """Migrate the detached NFS3 volume from Z2 back to C1a."""
        self._migrate_detached(
            "NFS3", self.__class__.nfs_z2, self.__class__.nfs_c1a
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_07_iscsi_detached_default_primary_to_cluster(self):
        """Migrate a detached iSCSI volume from DefaultPrimary to C1a."""
        self.__class__.iscsi_volume = self._create_data_volume(
            self.__class__.default_pool
        )
        self._migrate_detached(
            "ISCSI", self.__class__.default_pool, self.__class__.iscsi_c1a
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_08_iscsi_detached_cluster_to_cluster_same_cluster(self):
        """Migrate the detached iSCSI volume from C1a to C1b."""
        self._migrate_detached(
            "ISCSI", self.__class__.iscsi_c1a, self.__class__.iscsi_c1b
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_09_iscsi_detached_cluster_to_cluster_across_clusters(self):
        """Migrate the detached iSCSI volume from C1b to C2."""
        self._migrate_detached(
            "ISCSI", self.__class__.iscsi_c1b, self.__class__.iscsi_c2
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_10_iscsi_detached_cluster_to_zone(self):
        """Migrate the detached iSCSI volume from C2 to Z1."""
        self._migrate_detached(
            "ISCSI", self.__class__.iscsi_c2, self.__class__.iscsi_z1
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_11_iscsi_detached_zone_to_zone(self):
        """Migrate the detached iSCSI volume from Z1 to Z2."""
        self._migrate_detached(
            "ISCSI", self.__class__.iscsi_z1, self.__class__.iscsi_z2
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_12_iscsi_detached_zone_to_cluster(self):
        """Migrate the detached iSCSI volume from Z2 back to C1a."""
        self._migrate_detached(
            "ISCSI", self.__class__.iscsi_z2, self.__class__.iscsi_c1a
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_13_nfs3_attached_stopped_vm_cluster_to_zone(self):
        """Move stopped-attached NFS3 data C1a to Z1, preserving its root."""
        (
            self.__class__.nfs_vm,
            self.__class__.nfs_attached_volume,
        ) = self._migrate_stopped_attached(
            "NFS3", self.__class__.nfs_c1a, self.__class__.nfs_z1
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_14_iscsi_attached_stopped_vm_cluster_to_zone(self):
        """Move stopped-attached iSCSI data C1a to Z1, preserving its root."""
        (
            self.__class__.iscsi_vm,
            self.__class__.iscsi_attached_volume,
        ) = self._migrate_stopped_attached(
            "ISCSI", self.__class__.iscsi_c1a, self.__class__.iscsi_z1
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_15_nfs3_attached_stopped_vm_default_to_ontap(self):
        """Move stopped-attached non-ONTAP data to ONTAP NFS3."""
        self._migrate_stopped_attached(
            "NFS3", self.__class__.default_pool, self.__class__.nfs_c1a
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_16_iscsi_attached_stopped_vm_default_to_ontap(self):
        """Move stopped-attached non-ONTAP data to ONTAP iSCSI."""
        self._migrate_stopped_attached(
            "ISCSI", self.__class__.default_pool, self.__class__.iscsi_c1a
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_17_reject_detached_nfs3_to_iscsi(self):
        """Reject detached NFS3-to-iSCSI migration."""
        self.__class__._require_class_state("nfs_volume")
        self._assert_rejected(
            None, self.__class__.nfs_volume, self.__class__.nfs_c1a,
            self.__class__.iscsi_c1b, "NFS3", False, "cross-protocol",
            destination_protocol="ISCSI",
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_18_reject_detached_iscsi_to_nfs3(self):
        """Reject detached iSCSI-to-NFS3 migration."""
        self.__class__._require_class_state("iscsi_volume")
        self._assert_rejected(
            None, self.__class__.iscsi_volume, self.__class__.iscsi_c1a,
            self.__class__.nfs_c1b, "ISCSI", False, "cross-protocol",
            destination_protocol="NFS3",
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_19_reject_stopped_attached_nfs3_to_iscsi(self):
        """Reject stopped-attached NFS3-to-iSCSI migration."""
        self.__class__._require_class_state(
            "nfs_vm", "nfs_attached_volume"
        )
        self._assert_rejected(
            self.__class__.nfs_vm, self.__class__.nfs_attached_volume,
            self.__class__.nfs_z1, self.__class__.iscsi_z2,
            "NFS3", False, "cross-protocol",
            destination_protocol="ISCSI",
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_20_reject_stopped_attached_iscsi_to_nfs3(self):
        """Reject stopped-attached iSCSI-to-NFS3 migration."""
        self.__class__._require_class_state(
            "iscsi_vm", "iscsi_attached_volume"
        )
        self._assert_rejected(
            self.__class__.iscsi_vm, self.__class__.iscsi_attached_volume,
            self.__class__.iscsi_z1, self.__class__.nfs_z2,
            "ISCSI", False, "cross-protocol",
            destination_protocol="NFS3",
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_21_reject_attached_running_vm_without_livemigrate(self):
        """Reject running-attached NFS3 migration without livemigrate."""
        self.__class__._require_class_state(
            "nfs_vm", "nfs_attached_volume"
        )
        self.__class__.nfs_vm = self._start_vm(self.__class__.nfs_vm)
        self.__class__.nfs_attached_volume = self._get_volume(
            self.__class__.nfs_attached_volume.id
        )
        self._assert_rejected(
            self.__class__.nfs_vm,
            self.__class__.nfs_attached_volume,
            self.__class__.nfs_z1,
            self.__class__.nfs_c1a,
            "NFS3",
            None,
            "migrateVirtualMachineWithVolume",
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_22_reject_attached_running_vm_with_livemigrate(self):
        """Reject live volume-only migration in favor of VM-with-volume."""
        self.__class__._require_class_state(
            "nfs_vm", "nfs_attached_volume"
        )
        self._assert_rejected(
            self.__class__.nfs_vm,
            self.__class__.nfs_attached_volume,
            self.__class__.nfs_z1,
            self.__class__.nfs_c1a,
            "NFS3",
            True,
            "migrateVirtualMachineWithVolume",
        )

    @attr(tags=["ontap_migration", "volume_migration"],
          required_hardware=True)
    def test_23_reject_same_pool(self):
        """Reject migration to the stopped-attached volume's current pool."""
        self.__class__._require_class_state(
            "iscsi_vm", "iscsi_attached_volume"
        )
        self._assert_rejected(
            self.__class__.iscsi_vm,
            self.__class__.iscsi_attached_volume,
            self.__class__.iscsi_z1,
            self.__class__.iscsi_z1,
            "ISCSI",
            False,
            "already on the destination storage pool",
        )
