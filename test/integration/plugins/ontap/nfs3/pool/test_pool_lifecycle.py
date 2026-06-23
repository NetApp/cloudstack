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

"""
Sequential workflow integration tests for NetApp ONTAP NFS3 primary storage pool.

Tests are numbered test_01 ... test_08 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create primary storage pool
  02  Disable storage pool
  03  Enable storage pool
  04  Enter maintenance mode
  05  Cancel maintenance mode
  06  Delete the storage pool (enters Maintenance first, then deletes)
  07  Create fresh pool and allocate a CloudStack volume
  08  Delete volume then force-delete the pool

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster registered in CloudStack
  - ONTAP SVM with NFS3 service enabled and at least one NFS data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/test_ontap_create_primary_storage_nfs3.py -v

Note: Tests 01-06 share class-level state (sequential).  Running a single test
with -m "test_NN" will invoke setUpClass but the guard assertion will fail
immediately if earlier steps have not yet run.  Always run the full suite.
"""

import base64
import logging
import random
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    cancelStorageMaintenance,
    createStoragePool as createStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, _parse_pool_details

logger = logging.getLogger("TestOntapNFS3Workflow")


# ---------------------------------------------------------------------------
# Test data
# ---------------------------------------------------------------------------

class TestData:
    account = "account"
    ontap = "ontap"
    primaryStorage = "primaryStorage"
    provider = "provider"
    scope = "scope"
    tags = "tags"

    DETAIL_USERNAME = "username"
    DETAIL_PASSWORD = "password"
    DETAIL_SVM_NAME = "svmName"
    DETAIL_PROTOCOL = "protocol"
    DETAIL_STORAGE_IP = "storageIP"
    DETAIL_VOLUME_UUID = "volumeUUID"
    DETAIL_VOLUME_NAME = "volumeName"
    DETAIL_DATA_LIF = "dataLIF"
    DETAIL_NFS_MOUNT_OPTS = "nfsmountopts"

    ONTAP_MIN_VOLUME_SIZE = 1677721600

    def __init__(self, storage_ip, svm_name, username, password,
                 protocol="NFS3", scope="CLUSTER", provider="NetApp ONTAP",
                 tags="ontap-nfs3", capacitybytes=None):
        if capacitybytes is None:
            capacitybytes = TestData.ONTAP_MIN_VOLUME_SIZE * 2
        encoded_password = base64.b64encode(password.encode()).decode()
        self.testdata = {
            TestData.ontap: {
                TestData.DETAIL_STORAGE_IP: storage_ip,
                TestData.DETAIL_SVM_NAME: svm_name,
                TestData.DETAIL_USERNAME: username,
                TestData.DETAIL_PASSWORD: password,
            },
            TestData.account: {
                "email": "ontap-nfs3-wf@test.com",
                "firstname": "ONTAP",
                "lastname": "NFS3-WF",
                "username": "ontap_nfs3_wf_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapNFS3_%d" % random.randint(0, 9999),
                TestData.scope: scope,
                TestData.provider: provider,
                TestData.tags: tags,
                "capacitybytes": capacitybytes,
                "managed": True,
                "details": {
                    TestData.DETAIL_USERNAME: username,
                    TestData.DETAIL_PASSWORD: encoded_password,
                    TestData.DETAIL_SVM_NAME: svm_name,
                    TestData.DETAIL_PROTOCOL: protocol,
                    TestData.DETAIL_STORAGE_IP: storage_ip,
                },
            },
        }


# ---------------------------------------------------------------------------
# Sequential workflow test class
# ---------------------------------------------------------------------------

class TestOntapNFS3PrimaryStorageWorkflow(OntapTestBase):

    # ---- NFS3-specific shared state ------------------------------------
    pool_ep_name = None    # NFS export policy name for pool
    cluster_host_ips = None

    _vol_name_prefix = "OntapNFS3Vol"

    @classmethod
    def setUpClass(cls):
        testclient = super(
            TestOntapNFS3PrimaryStorageWorkflow, cls
        ).getClsTestClient()

        cls.apiClient = testclient.getApiClient()
        cls.dbConnection = testclient.getDbConnection()
        config = testclient.getParsedTestDataConfig()

        ontap_cfg = config.get("ontap", {})
        pool_cfg = config.get("storagePool", {})
        storage_ip = ontap_cfg.get("storageIP", "")
        svm_name = ontap_cfg.get("svmName", "")
        username = ontap_cfg.get("username", "")
        password = ontap_cfg.get("password", "")
        nfs3_cfg = pool_cfg.get("protocols", {}).get("nfs3", {})
        if not nfs3_cfg.get("enabled", True):
            raise unittest.SkipTest(
                "NFS3 tests disabled in ontap.cfg "
                "(set protocols.nfs3.enabled=true to enable)"
            )
        protocol = "NFS3"
        scope = pool_cfg.get("storagePoolScope", "CLUSTER")
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")
        tags = nfs3_cfg.get("storagePoolTags", "ontap-nfs3")
        capacitybytes = pool_cfg.get("capacitybytes", None)

        cls.testdata = TestData(
            storage_ip, svm_name, username, password,
            protocol=protocol, scope=scope, provider=provider,
            tags=tags, capacitybytes=capacitybytes,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name

        cls._setup_cloudstack_resources(config, cls.testdata[TestData.account])

        # Resolve cluster host IPs for export policy rule assertions
        cls.cluster_host_ips = [
            h.ipaddress for h in cls.cluster_hosts
            if getattr(h, "ipaddress", None)
        ]

    # No per-test tearDown — state intentionally persists between steps.

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapNFS3_%d" % random.randint(0, 99999)

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = pool_name
        cmd.url = "nfs://%s/ontap" % storage_ip
        cmd.zoneid = self.zone.id
        cmd.clusterid = self.cluster.id
        cmd.podid = self.cluster.podid
        cmd.scope = ps[TestData.scope]
        cmd.provider = ps[TestData.provider]
        cmd.tags = ps[TestData.tags]
        cmd.capacitybytes = ps["capacitybytes"]
        cmd.hypervisor = "KVM"
        cmd.managed = True

        count = 1
        for key, value in ps["details"].items():
            setattr(cmd, "details[{}].{}".format(count, key), value)
            count += 1

        response = self.apiClient.createStoragePool(cmd)
        return StoragePool(response.__dict__)

    def _get_export_policy_name(self, pool):
        """Extract the export policy name from pool creation response details."""
        details = _parse_pool_details(pool)
        ep_name = details.get("exportPolicyName")
        if not ep_name:
            # Fallback: plugin typically uses cs-{svmName}-{poolName}
            ep_name = "cs-%s-%s" % (self.svm_name, pool.name)
        return ep_name

    def _assert_export_policy_has_host_ips(self, ep_name):
        """Assert that the export policy exists and its rules include each cluster host IP."""
        policy = self.ontap.get_export_policy(ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' not found on ONTAP" % ep_name
        )
        if not self.cluster_host_ips:
            return  # no host IPs registered; skip rule-level check
        all_clients = []
        for rule in policy.get("rules", []):
            for client in rule.get("clients", []):
                all_clients.append(client.get("match", ""))
        for ip in self.cluster_host_ips:
            self.assertTrue(
                any(ip in c for c in all_clients),
                "Host IP '%s' not found in export policy '%s' rules: %s"
                % (ip, ep_name, all_clients)
            )

    def _assert_pool_capacity(self, pool, label):
        """Assert CloudStack capacity fields and ONTAP FlexVol size are consistent.

        Logs configured bytes, reported capacity, used bytes, and ONTAP
        FlexVol space.size at each check point.  Asserts:
          - listStoragePools.capacitybytes >= 90% of configured value
          - listStoragePools.disksizeused >= 0 (ONTAP reports actual used bytes;
            even a fresh FlexVol has metadata overhead so a non-zero value is
            expected and is not an error)
          - ONTAP FlexVol space.size >= 90% of configured value
        """
        configured = self.testdata[TestData.primaryStorage]["capacitybytes"]
        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertIsNotNone(
            listed,
            "[capacity/%s] listStoragePools returned None for pool %s"
            % (label, pool.id)
        )
        lp = listed[0]
        reported = getattr(lp, "capacitybytes", 0) or 0
        used = getattr(lp, "disksizeused", 0) or 0
        min_expected = int(configured * 0.90)

        logger.info(
            "[capacity/%s] configured=%d B  reported=%d B  used=%d B",
            label, configured, reported, used
        )
        self.assertGreaterEqual(
            reported, min_expected,
            "[capacity/%s] capacitybytes %d is >10%% below configured %d"
            % (label, reported, configured)
        )
        self.assertGreaterEqual(
            used, 0,
            "[capacity/%s] disksizeused must not be negative, got %d"
            % (label, used)
        )

        ontap_vol = self.ontap.get_volume(pool.name)
        if ontap_vol:
            ontap_size = ontap_vol.get("space", {}).get("size", 0)
            logger.info(
                "[capacity/%s] ONTAP FlexVol space.size=%d B",
                label, ontap_size
            )
            self.assertGreaterEqual(
                ontap_size, min_expected,
                "[capacity/%s] ONTAP FlexVol space.size %d is >10%% below configured %d"
                % (label, ontap_size, configured)
            )

    # ------------------------------------------------------------------
    # Step 01 — Create primary storage pool
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_01_create_primary_storage_pool(self):
        """
        Create an NFS3 primary storage pool and verify:
          - CloudStack state is Up, type is NetworkFilesystem
          - nfsmountopts contains 'vers=3'
          - ONTAP: FlexVol exists and is online
          - ONTAP: NFS export policy exists with cluster host IP rules
          - ONTAP: at least one NFS data LIF is present on the SVM
        """
        pool = self._create_pool()
        self.__class__.pool = pool

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
        )
        self.assertEqual(
            pool.type, "NetworkFilesystem",
            "Pool type should be 'NetworkFilesystem', got '%s'" % pool.type
        )

        # Verify nfsmountopts via listStoragePools
        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertIsNotNone(listed, "listStoragePools returned None for pool %s" % pool.id)
        nfs_opts = getattr(listed[0], "nfsmountopts", "")
        self.assertIn(
            "vers=3", nfs_opts,
            "nfsmountopts should contain 'vers=3', got '%s'" % nfs_opts
        )

        # ONTAP: FlexVol must be online
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol not found for pool '%s'" % pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online', got '%s'" % ontap_vol.get("state")
        )

        # ONTAP: export policy must exist with host IP rules
        ep_name = self._get_export_policy_name(pool)
        self.__class__.pool_ep_name = ep_name
        self._assert_export_policy_has_host_ips(ep_name)

        # ONTAP: at least one NFS data LIF must be present
        lifs = self.ontap.get_data_lifs(self.svm_name)
        self.assertTrue(
            len(lifs) > 0,
            "No NFS data LIFs found on SVM '%s'" % self.svm_name
        )

        # Capacity reporting
        self._assert_pool_capacity(pool, "pool-created")

    # ------------------------------------------------------------------
    # Step 02 — Disable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_02_disable_storage_pool(self):
        """
        Disable the pool and verify:
          - CloudStack reports Disabled
          - ONTAP: FlexVol is still online and export policy unchanged
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent — test_01 must pass first")

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = self.__class__.pool.id
        cmd.enabled = False
        self.apiClient.updateStoragePool(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Disabled", timeout=60)
        self.assertEqual(result.state, "Disabled")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after disable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' after disable, got '%s'"
            % ontap_vol.get("state")
        )
        if self.__class__.pool_ep_name:
            policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after disable"
                % self.__class__.pool_ep_name
            )

    # ------------------------------------------------------------------
    # Step 03 — Enable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_03_enable_storage_pool(self):
        """
        Re-enable the pool and verify:
          - CloudStack reports Up
          - ONTAP: FlexVol is still online and export policy unchanged
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent — test_01 must pass first")

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = self.__class__.pool.id
        cmd.enabled = True
        self.apiClient.updateStoragePool(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Up", timeout=60)
        self.assertEqual(result.state, "Up")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after enable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after enable, got '%s'"
            % ontap_vol.get("state")
        )
        if self.__class__.pool_ep_name:
            policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after enable"
                % self.__class__.pool_ep_name
            )

    # ------------------------------------------------------------------
    # Step 04 — Enter maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_04_enter_maintenance_mode(self):
        """
        Put the pool into maintenance mode and verify:
          - CloudStack reports Maintenance
          - ONTAP: FlexVol is still online and export policy unchanged
            (maintenance is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent — test_01 must pass first")

        cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.enableStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Maintenance", timeout=120)
        self.assertEqual(result.state, "Maintenance")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after entering maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' in maintenance, got '%s'"
            % ontap_vol.get("state")
        )
        if self.__class__.pool_ep_name:
            policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist during maintenance"
                % self.__class__.pool_ep_name
            )

    # ------------------------------------------------------------------
    # Step 05 — Cancel maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_05_cancel_maintenance_mode(self):
        """
        Cancel maintenance mode and verify the pool returns to Up.

        cancelStorageMaintenance sends ModifyStoragePoolCommand(add=True) to the
        KVM agent, which calls createStoragePool() with details that include
        nfsMountOptions=vers=3.  The agent rebuilds the libvirt pool XML with the
        xmlns:fs namespace extension and mounts the NFS share with vers=3.

        Fix confirmed — LibvirtStorageAdaptor now correctly handles the case
        where a stale-active libvirt pool entry lingers at the mount point after
        sp.destroy() during enter-maintenance.  The fix:
          1. Detects a stale-active pool (isActive==1 but mountpoint -q fails)
             and destroys it before re-creating.
          2. Retries createNetfsStoragePool once after 5 s on LibvirtException.

        Verifies:
          - CloudStack reports pool state Up
          - ONTAP: FlexVol is still online
          - ONTAP: NFS export policy still present
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")

        cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.cancelStorageMaintenance(cmd)

        result = self._poll_pool_state(
            self.__class__.pool.id, "Up", timeout=120
        )
        self.assertEqual(
            result.state, "Up",
            "Pool should be 'Up' after cancel maintenance, got '%s'"
            % result.state
        )

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol disappeared after cancel maintenance"
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after cancel maintenance, got '%s'"
            % ontap_vol.get("state")
        )
        if self.__class__.pool_ep_name:
            policy = self.ontap.get_export_policy(
                self.__class__.pool_ep_name
            )
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after cancel maintenance"
                % self.__class__.pool_ep_name
            )

    # ------------------------------------------------------------------
    # Step 06 — Delete the storage pool (already in Maintenance)
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_06_delete_pool_from_maintenance(self):
        """
        Enter maintenance mode then delete the storage pool.

        Verifies:
          - Pool is removed from CloudStack
          - ONTAP: FlexVol is deleted
          - ONTAP: NFS export policy is deleted
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent — test_01 must pass first")
        pool = self.__class__.pool
        pool_name = pool.name
        ep_name = self.__class__.pool_ep_name

        # Pool is Up after test_05 succeeded; must enter Maintenance before deletion.
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)

        self._delete_pool(pool.id)
        self.__class__.pool = None
        self.__class__.pool_ep_name = None

        # CloudStack: pool must be gone
        try:
            remaining = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            remaining = None
        self.assertFalse(remaining, "Pool still listed in CloudStack after deletion")

        # ONTAP: FlexVol must be deleted
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNone(
            ontap_vol,
            "ONTAP FlexVol '%s' still exists after pool deletion" % pool_name
        )

        # ONTAP: export policy must be deleted
        if ep_name:
            policy = self.ontap.get_export_policy(ep_name)
            self.assertIsNone(
                policy,
                "Export policy '%s' still exists after pool deletion" % ep_name
            )

    # ------------------------------------------------------------------
    # Step 07 - Create fresh pool and allocate a CloudStack volume
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_07_create_volume_on_pool(self):
        """
        Create a new NFS3 pool and allocate a CloudStack data volume.
        For NFS3, createAsync is a no-op on ONTAP (volume is a CloudStack record
        only — no new ONTAP object is created).
        Verifies:
          - pool.state is Up
          - createVolume returns a non-None volume object
          - ONTAP: FlexVol is still online and export policy still present
        """
        pool = self._create_pool()
        self.__class__.pool = pool

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
        )

        ep_name = self._get_export_policy_name(pool)
        self.__class__.pool_ep_name = ep_name

        vol = self._create_volume(pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        # ONTAP: FlexVol must still be online after volume allocation
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' not found after volume creation" % pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online', got '%s'" % ontap_vol.get("state")
        )

        # ONTAP: export policy must still exist
        policy = self.ontap.get_export_policy(ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' should still exist after volume creation" % ep_name
        )

        # Capacity reporting: FlexVol size and reported capacity unchanged after volume allocation
        self._assert_pool_capacity(pool, "volume-allocated")

    # ------------------------------------------------------------------
    # Step 08 - Delete volume then force-delete the pool
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_workflow"], required_hardware=True)
    def test_08_delete_volume_and_pool(self):
        """
        Delete the volume from test_07, enter maintenance, then force-delete
        the pool.
        Verifies:
          - deleteVolume completes without error
          - Pool transitions to Maintenance
          - Pool is removed from CloudStack after force deletion
          - ONTAP: FlexVol deleted
          - ONTAP: export policy deleted
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_07 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_07 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name
        ep_name = self.__class__.pool_ep_name
        vol = self.__class__.volume

        # Delete the volume
        cmd = deleteVolumeAPI.deleteVolumeCmd()
        cmd.id = vol.id
        self.apiClient.deleteVolume(cmd)
        self.__class__.volume = None

        # ONTAP: FlexVol must still be online (volume deletion does not affect NFS FlexVol)
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' should still exist after volume deletion" % pool_name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' after volume deletion"
        )

        # Capacity reporting: capacity fields stable after volume deletion
        self._assert_pool_capacity(pool, "volume-deleted")

        # Enter maintenance then force-delete the pool
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)

        self._delete_pool(pool.id, forced=True)
        self.__class__.pool = None
        self.__class__.pool_ep_name = None

        # CloudStack: pool must be gone
        try:
            remaining = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            remaining = None
        self.assertFalse(remaining, "Pool still listed in CloudStack after deletion")

        # ONTAP: FlexVol must be deleted
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNone(
            ontap_vol,
            "ONTAP FlexVol '%s' still exists after pool deletion" % pool_name
        )

        # ONTAP: export policy must be deleted
        if ep_name:
            policy = self.ontap.get_export_policy(ep_name)
            self.assertIsNone(
                policy,
                "Export policy '%s' still exists after pool deletion" % ep_name
            )
