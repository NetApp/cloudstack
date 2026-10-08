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

"""Ordered stopped-VM storage migration tests.

Every case runs migrateVirtualMachine with storageid, which moves all of a
stopped VM's volumes to one pool. One VM per protocol, holding a root and an
attached data volume, is carried through the same six-step pool chain:
DefaultPrimary (DP), cluster-1 pool A (C1a), cluster-1 pool B (C1b),
cluster-2 pool (C2), zone pool 1 (Z1), zone pool 2 (Z2), back to C1a. Hosts
stay split across two clusters; only the volumes move.

Workflow:
  01 NFS3 DP to C1a
  02 NFS3 C1a to C1b, both cluster-scoped in cluster 1
  03 NFS3 C1b to C2, cluster-scoped across clusters
  04 NFS3 C2 to Z1, cluster-scoped to zone-scoped
  05 NFS3 Z1 to Z2, zone-scoped to zone-scoped
  06 NFS3 Z2 to C1a, zone-scoped back to cluster-scoped
  07 iSCSI DP to C1a
  08 iSCSI C1a to C1b, both cluster-scoped in cluster 1
  09 iSCSI C1b to C2, cluster-scoped across clusters
  10 iSCSI C2 to Z1, cluster-scoped to zone-scoped
  11 iSCSI Z1 to Z2, zone-scoped to zone-scoped
  12 iSCSI Z2 to C1a, zone-scoped back to cluster-scoped
  13 reject NFS3-to-iSCSI storage migration
  14 reject iSCSI-to-NFS3 storage migration
  15 reject the stopped-VM API form once the VM is running

Migration out of ONTAP into non-ONTAP storage is never requested; the
DefaultPrimary pool is only ever a source.
"""

import unittest

from nose.plugins.attrib import attr

from migration.migration_test_base import OntapMigrationTestBase

CROSS_PROTOCOL_REJECTION = "managed storage can only be 'migrated'"


class TestOntapStoppedVmStorageMigration(OntapMigrationTestBase):
    """Verify stopped-VM storage motion across every ONTAP pool scope."""

    chain_vms = None
    chain_volumes = None
    chain_pools = None

    @classmethod
    def setUpClass(cls):
        super(TestOntapStoppedVmStorageMigration, cls).setUpClass()
        cls.chain_vms = {}
        cls.chain_volumes = {}
        cls.chain_pools = {}

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
        scopes = [
            ("CLUSTER", cls.cluster.id, 2),
            ("CLUSTER", secondary_cluster_id, 1),
            ("ZONE", None, 2),
        ]
        return {"NFS3": list(scopes), "ISCSI": list(scopes)}

    def _pools_for(self, protocol):
        secondary_cluster_id = self._require_secondary_cluster()
        return {
            "C1A": self._migration_pool(protocol),
            "C1B": self._migration_pool(protocol, 1),
            "C2": self._migration_pool(
                protocol, cluster_id=secondary_cluster_id
            ),
            "Z1": self._migration_pool(protocol, scope="ZONE"),
            "Z2": self._migration_pool(protocol, 1, scope="ZONE"),
        }

    def _start_chain(self, protocol):
        """Deploy a DefaultPrimary VM with a data disk and stop it."""
        cls = self.__class__
        cls.chain_pools[protocol] = self._pools_for(protocol)
        vm = self._deploy_vm(
            self._default_storage_tag(),
            self._host_for_cluster(cls.cluster.id).id,
        )
        self._attach_volume(
            vm, self._create_data_volume(cls.default_pool)
        )
        self._stop_vm(vm)
        volumes = self._vm_volumes(vm.id)
        self.assertTrue(
            len(volumes) >= 2,
            "The %s chain VM has no attached data volume" % protocol,
        )
        for volume in volumes:
            self.assertEqual(
                volume.storageid,
                cls.default_pool.id,
                "VM volume was not allocated on DefaultPrimary",
            )
        cls.chain_vms[protocol] = vm
        cls.chain_volumes[protocol] = volumes

    def _chain(self, protocol):
        cls = self.__class__
        if protocol not in cls.chain_vms:
            raise unittest.SkipTest(
                "The %s chain VM was not prepared by the first case"
                % protocol
            )
        return (
            cls.chain_vms[protocol],
            cls.chain_volumes[protocol],
            cls.chain_pools[protocol],
        )

    def _migrate_step(self, protocol, source_key, destination_key):
        """Move every volume of the chain VM to the next pool in the chain."""
        vm, volumes, pools = self._chain(protocol)
        destination = pools[destination_key]
        source_pool = pools[source_key] if source_key else None
        self._migrate_stopped_vm_storage(vm, destination)
        for volume in volumes:
            self._poll_volume(
                volume.id, "storageid", destination.id, timeout=900
            )
        migrated = self._assert_migration_success(
            volumes,
            destination,
            protocol,
            vm=vm,
            expected_vm_state="Stopped",
            expected_vm_id=vm.id,
            source_pool=source_pool,
        )
        self.__class__.chain_volumes[protocol] = migrated
        self._assert_offline_host_state(
            protocol, destination, vm=vm, volumes=migrated
        )
        return destination

    def _destination_objects(self, protocol, pool, volumes):
        return {
            volume.id: self._backend_object_exists(
                protocol, pool, self._backend_name(protocol, pool, volume)
            )
            for volume in volumes
        }

    def _assert_rejected(self, protocol, source_key, destination_pool,
                         destination_protocol, pattern=None):
        """Assert CloudStack and ONTAP are untouched by a refused request."""
        vm, volumes, pools = self._chain(protocol)
        source_pool = pools[source_key]
        snapshot = self._snapshot_state(
            vm=vm,
            volumes=volumes,
            source_pool=source_pool,
            protocol=protocol,
        )
        host_snapshot = self.__class__._host_vm_snapshot(vm)
        dest_ids = self._volume_ids_on_pool(destination_pool)
        destination_before = self._destination_objects(
            destination_protocol, destination_pool, volumes
        )
        if pattern:
            with self.assertRaisesRegex(Exception, pattern):
                self._migrate_stopped_vm_storage(vm, destination_pool)
        else:
            with self.assertRaises(Exception):
                self._migrate_stopped_vm_storage(vm, destination_pool)
        self._finish_rejected_migration(
            volumes, destination_pool, dest_ids
        )
        self._assert_state_unchanged(
            snapshot,
            vm=vm,
            source_pool=source_pool,
            protocol=protocol,
        )
        self.assertEqual(
            self._destination_objects(
                destination_protocol, destination_pool, volumes
            ),
            destination_before,
            "Refused migration changed destination pool %s"
            % destination_pool.name,
        )
        self._assert_host_snapshot_unchanged(host_snapshot, vm)

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_01_nfs3_default_primary_to_cluster(self):
        """Move a stopped VM's volumes from DefaultPrimary to NFS3 C1a."""
        self._start_chain("NFS3")
        self._migrate_step("NFS3", None, "C1A")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_02_nfs3_cluster_to_cluster_same_cluster(self):
        """Move NFS3 volumes between cluster pools in the same cluster."""
        self._migrate_step("NFS3", "C1A", "C1B")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_03_nfs3_cluster_to_cluster_across_clusters(self):
        """Move NFS3 volumes to a cluster pool in the second cluster."""
        destination = self._migrate_step("NFS3", "C1B", "C2")
        clients = self._export_policy_clients(destination)
        for host_ip in self._host_ips(self.__class__.cluster.id):
            self.assertFalse(
                any(host_ip in client for client in clients),
                "Source cluster host %s remained in destination export %s"
                % (host_ip, clients),
            )
        for host_ip in self._host_ips(self._require_secondary_cluster()):
            self.assertTrue(
                any(host_ip in client for client in clients),
                "Destination cluster host %s missing from export %s"
                % (host_ip, clients),
            )

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_04_nfs3_cluster_to_zone(self):
        """Move NFS3 volumes from a cluster pool to a zone-wide pool."""
        self._migrate_step("NFS3", "C2", "Z1")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_05_nfs3_zone_to_zone(self):
        """Move NFS3 volumes between two zone-wide pools."""
        self._migrate_step("NFS3", "Z1", "Z2")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_06_nfs3_zone_to_cluster(self):
        """Move NFS3 volumes from a zone-wide pool back to cluster C1a."""
        self._migrate_step("NFS3", "Z2", "C1A")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_07_iscsi_default_primary_to_cluster(self):
        """Move a stopped VM's volumes from DefaultPrimary to iSCSI C1a."""
        self._start_chain("ISCSI")
        self._migrate_step("ISCSI", None, "C1A")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_08_iscsi_cluster_to_cluster_same_cluster(self):
        """Move iSCSI volumes between cluster pools in the same cluster."""
        self._migrate_step("ISCSI", "C1A", "C1B")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_09_iscsi_cluster_to_cluster_across_clusters(self):
        """Move iSCSI volumes to a cluster pool in the second cluster."""
        self._migrate_step("ISCSI", "C1B", "C2")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_10_iscsi_cluster_to_zone(self):
        """Move iSCSI volumes from a cluster pool to a zone-wide pool."""
        self._migrate_step("ISCSI", "C2", "Z1")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_11_iscsi_zone_to_zone(self):
        """Move iSCSI volumes between two zone-wide pools."""
        self._migrate_step("ISCSI", "Z1", "Z2")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_12_iscsi_zone_to_cluster(self):
        """Move iSCSI volumes from a zone-wide pool back to cluster C1a."""
        self._migrate_step("ISCSI", "Z2", "C1A")

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_13_reject_nfs3_to_iscsi(self):
        """Reject a stopped-VM migration from NFS3 storage to iSCSI."""
        _, _, iscsi_pools = self._chain("ISCSI")
        self._assert_rejected(
            "NFS3", "C1A", iscsi_pools["C1B"], "ISCSI",
            CROSS_PROTOCOL_REJECTION,
        )

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_14_reject_iscsi_to_nfs3(self):
        """Reject a stopped-VM migration from iSCSI storage to NFS3."""
        _, _, nfs_pools = self._chain("NFS3")
        self._assert_rejected(
            "ISCSI", "C1A", nfs_pools["C1B"], "NFS3",
            CROSS_PROTOCOL_REJECTION,
        )

    @attr(tags=["ontap_migration", "stopped_vm"], required_hardware=True)
    def test_15_reject_running_vm(self):
        """Reject the stopped-VM API form once the chain VM is running."""
        vm, _, pools = self._chain("ISCSI")
        self._start_vm(vm)
        self._assert_rejected(
            "ISCSI", "C1A", pools["C1B"], "ISCSI",
            "VM is not Stopped",
        )
        self.assertEqual(self._get_vm(vm.id).state, "Running")
