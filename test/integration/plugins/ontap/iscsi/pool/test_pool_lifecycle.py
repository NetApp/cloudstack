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
Sequential workflow integration tests for NetApp ONTAP iSCSI primary storage
pool lifecycle (no volumes).

Tests are numbered test_01 ... test_12 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Reject create when a FlexVol of that name already exists on ONTAP
  02  Reject create when no online assigned aggregate has enough free space
  03  Create primary storage pool
  04  Disable storage pool
  05  Enable storage pool
  06  Enter maintenance mode
  07  Cancel maintenance mode
  08  Enter maintenance mode and delete the storage pool
  09  Create a new pool and allocate a CloudStack data volume (LUN created)
  10  Delete the volume (LUN removed), enter maintenance, force-delete pool
  11  Delete an empty pool whose FlexVol was deleted directly on ONTAP
  12  Delete an empty pool whose igroups were deleted on ONTAP

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has iSCSI configured (storageUrl starts with iqn.)
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/pool/ -v

Note: Tests share class-level state (sequential).  Always run the full suite.
"""

import base64
import logging
import random
import re
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    cancelStorageMaintenance,
    createStoragePool as createStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.cloudstackException import CloudstackAPIException
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config, log_progress

logger = logging.getLogger("TestOntapISCSIPoolLifecycle")


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

    ONTAP_MIN_VOLUME_SIZE = 1677721600
    ONTAP_MAX_VOLUME_SIZE = 300 * 1024 ** 4

    def __init__(self, storage_ip, svm_name, username, password,
                 scope="CLUSTER", provider="NetApp ONTAP",
                 tags="ontap-iscsi", capacitybytes=None):
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
                "email": "ontap-iscsi-wf@test.com",
                "firstname": "ONTAP",
                "lastname": "iSCSI-WF",
                "username": "ontap_iscsi_wf_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapISCSI_%d" % random.randint(0, 9999),
                TestData.scope: scope,
                TestData.provider: provider,
                TestData.tags: tags,
                "capacitybytes": capacitybytes,
                "managed": True,
                "details": {
                    TestData.DETAIL_USERNAME: username,
                    TestData.DETAIL_PASSWORD: encoded_password,
                    TestData.DETAIL_SVM_NAME: svm_name,
                    TestData.DETAIL_PROTOCOL: "ISCSI",
                    TestData.DETAIL_STORAGE_IP: storage_ip,
                },
            },
        }


# ---------------------------------------------------------------------------
# iSCSI path helpers
# ---------------------------------------------------------------------------

def _igroup_name(svm_name, host_name):
    """Mirror OntapStorageUtils.getIgroupName: cs_{svmName}_{sanitizedHostName}"""
    short = host_name.split(".")[0]
    sanitized = re.sub(r"[^a-zA-Z0-9_-]", "_", short)
    return "cs_%s_%s" % (svm_name, sanitized)


# ---------------------------------------------------------------------------
# Sequential workflow test class
# ---------------------------------------------------------------------------

class TestOntapISCSIPoolLifecycle(OntapTestBase):

    # ---- iSCSI-specific state (set/cleared by individual tests) --------
    _vol_name_prefix = "OntapISCSIVol"

    @classmethod
    def setUpClass(cls):
        super(TestOntapISCSIPoolLifecycle, cls).setUpClass()
        testclient = super(
            TestOntapISCSIPoolLifecycle, cls
        ).getClsTestClient()

        cls.apiClient = testclient.getApiClient()
        cls.dbConnection = testclient.getDbConnection()
        config = get_datacenter_config(testclient, cls)

        ontap_cfg = config.get("ontap", {})
        pool_cfg = config.get("storagePool", {})
        storage_ip = ontap_cfg.get("storageIP", "")
        svm_name = ontap_cfg.get("svmName", "")
        username = ontap_cfg.get("username", "")
        password = ontap_cfg.get("password", "")
        iscsi_cfg = pool_cfg.get("protocols", {}).get("iscsi", {})
        if not iscsi_cfg.get("enabled", True):
            raise unittest.SkipTest(
                "iSCSI tests disabled in ontap.cfg "
                "(set protocols.iscsi.enabled=true to enable)"
            )
        scope = pool_cfg.get("storagePoolScope", "CLUSTER")
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")
        tags = iscsi_cfg.get("storagePoolTags", "ontap-iscsi")
        capacitybytes = pool_cfg.get("capacitybytes", None)

        cls.testdata = TestData(
            storage_ip, svm_name, username, password,
            scope=scope, provider=provider, tags=tags,
            capacitybytes=capacitybytes,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name

        cls._setup_cloudstack_resources(config, cls.testdata[TestData.account])

    # No per-test tearDown — state intentionally persists between steps.

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_pool(self, pool_name=None, capacitybytes=None):
        """Create a pool; name and capacity default to the suite's values."""
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        if pool_name is None:
            pool_name = "OntapISCSI_%d" % random.randint(0, 99999)
        if capacitybytes is None:
            capacitybytes = ps["capacitybytes"]

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = pool_name
        cmd.url = "iscsi://%s/ontap" % storage_ip
        cmd.zoneid = self.zone.id
        cmd.clusterid = self.cluster.id
        cmd.podid = self.cluster.podid
        cmd.scope = ps[TestData.scope]
        cmd.provider = ps[TestData.provider]
        cmd.tags = ps[TestData.tags]
        cmd.capacitybytes = capacitybytes
        cmd.hypervisor = "KVM"
        cmd.managed = True

        count = 1
        for key, value in ps["details"].items():
            setattr(cmd, "details[{}].{}".format(count, key), value)
            count += 1

        response = self.apiClient.createStoragePool(cmd)
        return StoragePool(response.__dict__)

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
    # Step 01 - Create primary storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_01_reject_create_when_flexvol_name_exists(self):
        """
        Pre-create a FlexVol on ONTAP, then ask CloudStack for a pool of the
        same name.  ONTAP refuses the duplicate, so the create must fail.

        Verifies:
          - createStoragePool raises CloudstackAPIException
          - no pool of that name is left in CloudStack
          - the pre-existing FlexVol is untouched (the plugin must not
            adopt or delete a volume it did not create)
        """
        self._sweep_tracked_pool2()
        pool_name = self._throwaway_pool_name("Dup")
        try:
            self.ontap.create_flexvol(
                self.svm_name, pool_name, TestData.ONTAP_MIN_VOLUME_SIZE,
                nas_path=False,
            )
            self.assertIsNotNone(
                self.ontap.get_volume(pool_name),
                "Pre-created ONTAP FlexVol '%s' not found; cannot test the "
                "duplicate-name rejection" % pool_name,
            )
            log_progress(
                logger, "info",
                "Pre-created FlexVol '%s'; requesting a pool of the same name "
                "(expect reject)", pool_name,
            )
            with self.assertRaises(CloudstackAPIException) as caught:
                self.__class__.pool2 = self._create_pool(pool_name=pool_name)
            log_progress(
                logger, "info",
                "Rejected duplicate-name create for '%s': %s",
                pool_name, caught.exception,
            )
            self._assert_no_pool_named(pool_name)
            self.assertIsNotNone(
                self.ontap.get_volume(pool_name),
                "Pre-existing ONTAP FlexVol '%s' was removed by the failed "
                "pool create" % pool_name,
            )
        finally:
            self._cleanup_throwaway_pool(
                self.__class__.pool2, flexvol_name=pool_name
            )


    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_02_reject_create_when_no_aggregate_space(self):
        """
        Ask for 1 GiB more than the largest online aggregate assigned to the
        SVM can provide, so no aggregate qualifies and the plugin refuses
        before creating anything.

        Verifies:
          - createStoragePool raises CloudstackAPIException
          - the error names the aggregate shortage rather than some other
            failure ('No suitable aggregates')
          - no pool is left in CloudStack and no FlexVol on ONTAP
        """
        self._sweep_tracked_pool2()
        max_free = self.ontap.max_online_aggregate_available_bytes(
            self.svm_name
        )
        if not max_free:
            self.skipTest(
                "No online aggregate with reported free space is assigned to "
                "SVM '%s'; cannot build an unsatisfiable request"
                % self.svm_name
            )
        requested = int(max_free) + 1024 ** 3
        if requested > TestData.ONTAP_MAX_VOLUME_SIZE:
            self.skipTest(
                "Largest aggregate free space (%d B) + 1 GiB exceeds the "
                "ONTAP FlexVol maximum (%d B); the request would be refused "
                "for the size limit rather than the aggregate shortage"
                % (max_free, TestData.ONTAP_MAX_VOLUME_SIZE)
            )

        pool_name = self._throwaway_pool_name("NoSpace")
        log_progress(
            logger, "info",
            "Requesting pool '%s' of %d B; largest online aggregate on SVM "
            "'%s' has %d B free (expect reject)",
            pool_name, requested, self.svm_name, max_free,
        )
        try:
            with self.assertRaises(CloudstackAPIException) as caught:
                self.__class__.pool2 = self._create_pool(
                    pool_name=pool_name, capacitybytes=requested
                )
            error_text = str(caught.exception)
            log_progress(
                logger, "info",
                "Rejected no-space create for '%s': %s", pool_name, error_text,
            )
            self.assertIn(
                "No suitable aggregates", error_text,
                "Expected the rejection to report 'No suitable aggregates', "
                "got: %s" % error_text,
            )
            self._assert_no_pool_named(pool_name)
            self.assertIsNone(
                self.ontap.get_volume(pool_name),
                "ONTAP FlexVol '%s' was created despite the rejected pool "
                "create" % pool_name,
            )
        finally:
            self._cleanup_throwaway_pool(
                self.__class__.pool2, flexvol_name=pool_name
            )

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_03_create_primary_storage_pool(self):
        """
        Create an iSCSI primary storage pool and verify:
          - CloudStack state is Up, type is OntapiSCSI
          - ONTAP: FlexVol exists and is online
          - ONTAP: one igroup per cluster host exists with the correct IQN initiator
        """
        pool = self._create_pool()
        self.__class__.pool = pool

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
        )
        self.assertEqual(
            pool.type, "OntapiSCSI",
            "Pool type should be 'OntapiSCSI', got '%s'" % pool.type
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

        # ONTAP: igroup must exist for each cluster host that has an IQN
        for host in self.cluster_hosts:
            iqn = getattr(host, "storageurl", None) or getattr(host, "StorageUrl", None)
            if not iqn or not iqn.startswith("iqn."):
                continue  # host not iSCSI-enabled; skip igroup check for it
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            self.assertIsNotNone(
                igroup,
                "ONTAP igroup '%s' not found for host '%s'" % (igroup_name, host.name)
            )
            initiator_names = [
                i.get("name", "") for i in igroup.get("initiators", [])
            ]
            self.assertIn(
                iqn, initiator_names,
                "Host IQN '%s' not in igroup '%s' initiators: %s"
                % (iqn, igroup_name, initiator_names)
            )

        # Capacity reporting
        self._assert_pool_capacity(pool, "pool-created")

    # ------------------------------------------------------------------
    # Step 02 - Disable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_04_disable_storage_pool(self):
        """
        Disable the pool and verify:
          - CloudStack reports Disabled
          - ONTAP: FlexVol is still online (disable is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = self.__class__.pool.id
        cmd.enabled = False
        self.apiClient.updateStoragePool(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Disabled", timeout=60)
        self.assertEqual(result.state, "Disabled")

        # ONTAP: disable must not touch the FlexVol
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after disable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' after disable, got '%s'"
            % ontap_vol.get("state")
        )

    # ------------------------------------------------------------------
    # Step 03 - Enable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_05_enable_storage_pool(self):
        """
        Re-enable the pool and verify:
          - CloudStack reports Up
          - ONTAP: FlexVol is still online (enable is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = self.__class__.pool.id
        cmd.enabled = True
        self.apiClient.updateStoragePool(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Up", timeout=60)
        self.assertEqual(result.state, "Up")

        # ONTAP: enable must not touch the FlexVol
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after enable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after enable, got '%s'"
            % ontap_vol.get("state")
        )

    # ------------------------------------------------------------------
    # Step 04 - Enter maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_06_enter_maintenance_mode(self):
        """
        Put the pool into maintenance mode and verify:
          - CloudStack reports Maintenance
          - ONTAP: FlexVol is still online (maintenance is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

        cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.enableStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Maintenance", timeout=120)
        self.assertEqual(result.state, "Maintenance")

        # ONTAP: maintenance must not touch the FlexVol
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after entering maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' in maintenance, got '%s'"
            % ontap_vol.get("state")
        )

    # ------------------------------------------------------------------
    # Step 05 - Cancel maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_07_cancel_maintenance_mode(self):
        """
        Cancel maintenance and verify:
          - CloudStack reports Up
          - ONTAP: FlexVol is still online
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

        cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.cancelStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Up", timeout=120)
        self.assertEqual(result.state, "Up")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after cancel maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after cancel maintenance, got '%s'"
            % ontap_vol.get("state")
        )

    # ------------------------------------------------------------------
    # Step 06 - Enter maintenance mode and delete the storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_08_enter_maintenance_and_delete_pool(self):
        """
        Enter maintenance mode then delete the pool.
        Verifies the pool is removed from CloudStack and the backing ONTAP
        FlexVol is deleted.
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")
        pool = self.__class__.pool
        pool_name = pool.name

        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)

        self._delete_pool(pool.id)
        self.__class__.pool = None

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

        # ONTAP: igroups for each cluster host must be deleted
        for host in self.cluster_hosts:
            iqn = getattr(host, "storageurl", None) or getattr(host, "StorageUrl", None)
            if not iqn or not iqn.startswith("iqn."):
                continue
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            self.assertIsNone(
                igroup,
                "ONTAP igroup '%s' still exists after pool deletion" % igroup_name
            )

    # ------------------------------------------------------------------
    # Step 07 - Create fresh pool and allocate a CloudStack volume (LUN)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_09_create_volume_on_pool(self):
        """
        Create a new iSCSI pool and allocate a CloudStack data volume.
        For iSCSI, createAsync creates a LUN inside the pool's ONTAP FlexVol.
        Verifies:
          - pool.state is Up, type is OntapiSCSI
          - createVolume returns a non-None volume object
          - ONTAP: FlexVol is still online
          - ONTAP: at least one LUN is present in the FlexVol
        """
        pool = self._create_pool()
        self.__class__.pool = pool
        log_progress(
            logger, "info",
            "test_07: created storage pool name='%s' id=%s state=%s type=%s",
            pool.name, pool.id, pool.state, pool.type,
        )

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
        )
        self.assertEqual(
            pool.type, "OntapiSCSI",
            "Pool type should be 'OntapiSCSI', got '%s'" % pool.type
        )

        vol = self._create_volume(pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")
        log_progress(
            logger, "info",
            "test_07: created CloudStack volume name='%s' id=%s state=%s "
            "on pool='%s' (id=%s) account='%s' domain='%s' — "
            "switch to this account in the UI to see the volume",
            getattr(vol, "name", "?"), getattr(vol, "id", "?"),
            getattr(vol, "state", "?"), pool.name, pool.id,
            self.account.name, self.domain.name,
        )

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

        # ONTAP: at least one LUN must be present in the FlexVol
        luns = self.ontap.list_luns_in_volume(self.svm_name, pool.name)
        self.assertTrue(
            len(luns) > 0,
            "No LUNs found in ONTAP FlexVol '%s' after volume creation" % pool.name
        )

        # Capacity reporting
        self._assert_pool_capacity(pool, "volume-allocated")

    # ------------------------------------------------------------------
    # Step 08 - Delete volume (LUN) then force-delete the pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_10_delete_volume_and_pool(self):
        """
        Delete the volume from test_07, enter maintenance, then force-delete
        the pool.
        Verifies:
          - deleteVolume removes the LUN from ONTAP
          - Pool transitions to Maintenance
          - Pool is removed from CloudStack after force deletion
          - ONTAP: FlexVol deleted
          - ONTAP: igroups for all cluster hosts deleted
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_09 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_09 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name
        vol = self.__class__.volume

        # Delete the volume — LUN is removed from ONTAP
        cmd = deleteVolumeAPI.deleteVolumeCmd()
        cmd.id = vol.id
        self.apiClient.deleteVolume(cmd)
        self.__class__.volume = None

        # ONTAP: LUN must be gone from the FlexVol
        luns = self.ontap.list_luns_in_volume(self.svm_name, pool_name)
        self.assertEqual(
            len(luns), 0,
            "Expected 0 LUNs in FlexVol '%s' after volume deletion, found %d"
            % (pool_name, len(luns))
        )

        # ONTAP: FlexVol must still be online (pool not yet deleted)
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' should still exist after volume deletion" % pool_name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' after volume deletion"
        )

        # Capacity reporting: capacity stable after volume deletion
        self._assert_pool_capacity(pool, "volume-deleted")

        # Enter maintenance then force-delete the pool
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)

        self._delete_pool(pool.id, forced=True)
        self.__class__.pool = None

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

        # ONTAP: igroups for each cluster host must be deleted
        for host in self.cluster_hosts:
            iqn = getattr(host, "storageurl", None) or getattr(host, "StorageUrl", None)
            if not iqn or not iqn.startswith("iqn."):
                continue
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            self.assertIsNone(
                igroup,
                "ONTAP igroup '%s' still exists after pool deletion" % igroup_name
            )

    def _throwaway_pool_name(self, suffix):
        return "OntapISCSI%s_%d" % (suffix, random.randint(0, 99999))


    def _cleanup_throwaway_pool(self, pool, flexvol_name=None):
        """Best-effort teardown for an isolated test: CloudStack, then ONTAP.

        Never raises, so a failed assertion in the test body is the error
        that surfaces.
        """
        if pool is not None:
            try:
                listed = list_storage_pools(self.apiClient, id=pool.id)
            except Exception:
                listed = None
            if listed:
                try:
                    if listed[0].state != "Maintenance":
                        maint_cmd = (
                            enableStorageMaintenance
                            .enableStorageMaintenanceCmd()
                        )
                        maint_cmd.id = pool.id
                        self.apiClient.enableStorageMaintenance(maint_cmd)
                        self._poll_pool_state(
                            pool.id, "Maintenance", timeout=120
                        )
                except Exception as exc:
                    logger.warning(
                        "cleanup: could not put pool '%s' into Maintenance: %s",
                        pool.name, exc,
                    )
                try:
                    self._delete_pool(pool.id, forced=True)
                except Exception as exc:
                    logger.warning(
                        "cleanup: could not delete pool '%s': %s",
                        pool.name, exc,
                    )
            if flexvol_name is None:
                flexvol_name = pool.name
        if flexvol_name:
            try:
                if self.ontap.get_volume(flexvol_name) is not None:
                    self.ontap.offline_and_delete_volume(flexvol_name)
            except Exception as exc:
                logger.warning(
                    "cleanup: could not delete ONTAP FlexVol '%s': %s",
                    flexvol_name, exc,
                )
        if pool is None:
            return
        try:
            remaining = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            remaining = None
        if not remaining:
            self.__class__.pool2 = None


    def _assert_no_pool_named(self, pool_name):
        """Assert CloudStack holds no storage pool with this name."""
        try:
            listed = list_storage_pools(self.apiClient, name=pool_name)
        except CloudstackAPIException:
            listed = None
        self.assertFalse(
            listed,
            "CloudStack should hold no pool named '%s' after a rejected "
            "create, found: %s" % (pool_name, listed),
        )


    def _assert_pool_gone_from_cs(self, pool_id, pool_name):
        try:
            remaining = list_storage_pools(self.apiClient, id=pool_id)
        except CloudstackAPIException:
            remaining = None
        self.assertFalse(
            remaining,
            "Pool '%s' still listed in CloudStack after deletion" % pool_name,
        )


    def _sweep_tracked_pool2(self):
        """Clean a prior leftover before reusing the shared pool2 slot."""
        if self.__class__.pool2 is None:
            return
        self._cleanup_throwaway_pool(self.__class__.pool2)
        if self.__class__.pool2 is not None:
            self.skipTest("A previously tracked pool could not be cleaned up")


    def _create_throwaway_pool(self, suffix):
        """Create an isolated pool, park it in pool2, and return it."""
        self._sweep_tracked_pool2()
        pool = self._create_pool(pool_name=self._throwaway_pool_name(suffix))
        self.__class__.pool2 = pool
        self.assertEqual(
            pool.state, "Up",
            "Throwaway pool '%s' should be 'Up', got '%s'"
            % (pool.name, pool.state),
        )
        self.assertIsNotNone(
            self.ontap.get_volume(pool.name),
            "ONTAP FlexVol missing for throwaway pool '%s'" % pool.name,
        )
        return pool


    def _enter_maintenance(self, pool):
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)


    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_11_delete_pool_with_flexvol_predeleted(self):
        """
        Delete the backing FlexVol directly on ONTAP, then delete the empty
        pool through CloudStack.  Deletion must tolerate the missing volume
        rather than leaving an undeletable pool behind.

        Verifies:
          - deleteStoragePool succeeds with the FlexVol already gone
          - the pool is removed from CloudStack
        """
        pool = self._create_throwaway_pool("PreDelVol")
        try:
            self._enter_maintenance(pool)
            log_progress(
                logger, "info",
                "Deleting ONTAP FlexVol '%s' behind CloudStack's back",
                pool.name,
            )
            self.ontap.offline_and_delete_volume(pool.name)
            self.assertIsNone(
                self.ontap.get_volume(pool.name),
                "ONTAP FlexVol '%s' still present after direct deletion"
                % pool.name,
            )

            self._delete_pool(pool.id)
            self._assert_pool_gone_from_cs(pool.id, pool.name)
            self.assertIsNone(
                self.ontap.get_volume(pool.name),
                "ONTAP FlexVol '%s' reappeared after pool deletion"
                % pool.name,
            )
            self.__class__.pool2 = None
        finally:
            self._cleanup_throwaway_pool(
                self.__class__.pool2, flexvol_name=pool.name
            )


    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_12_delete_pool_with_igroups_predeleted(self):
        """
        Delete the per-host igroups directly on ONTAP, then delete the empty
        pool through CloudStack.  Deletion must tolerate the missing igroups
        and still remove the FlexVol.

        The igroup name is keyed off the host UUID and the SVM, not the pool,
        so the igroups are shared by every ONTAP pool on that SVM.  This test
        therefore assumes no other pool is in use on the SVM, which holds
        here because test_10 removed the workflow pools.

        The plugin only creates an igroup when a host is first granted access
        to a LUN, so a freshly created empty pool has none.  The test seeds
        them on ONTAP under the exact names the plugin would use, which both
        proves the naming scheme still matches and makes the pre-deletion a
        real precondition rather than a no-op.

        Verifies:
          - deleteStoragePool succeeds with the igroups already gone
          - the pool is removed from CloudStack
          - ONTAP: the FlexVol is deleted
        """
        other_pools = self._other_ontap_pools_on_svm(None)
        if other_pools:
            self.skipTest(
                "Pre-deleting SVM-wide igroups requires exclusive SVM use; "
                "found other ONTAP pool(s): %s"
                % ", ".join(str(getattr(p, "name", p)) for p in other_pools)
            )
        specs = self._iscsi_host_specs()
        if not specs:
            self.skipTest(
                "No cluster host advertises an iSCSI IQN, so no igroup name "
                "can be derived"
            )
        pool = self._create_throwaway_pool("PreDelIgroup")
        seeded = []
        try:
            for igroup_name, iqn in specs:
                if self.ontap.get_igroup(self.svm_name, igroup_name) is None:
                    log_progress(
                        logger, "info",
                        "Seeding ONTAP igroup '%s' with initiator '%s'",
                        igroup_name, iqn,
                    )
                    self.ontap.create_igroup(self.svm_name, igroup_name, iqn)
                    seeded.append(igroup_name)
            present = [name for name, _ in specs]
            for igroup_name in present:
                self.assertIsNotNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' should exist before the pre-deletion"
                    % igroup_name,
                )

            self._enter_maintenance(pool)
            log_progress(
                logger, "info",
                "Deleting ONTAP igroups %s behind CloudStack's back", present,
            )
            for igroup_name in present:
                self.ontap.delete_igroup(self.svm_name, igroup_name)
                self.assertIsNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' still present after direct deletion"
                    % igroup_name,
                )

            self._delete_pool(pool.id)
            self._assert_pool_gone_from_cs(pool.id, pool.name)
            self.assertIsNone(
                self.ontap.get_volume(pool.name),
                "ONTAP FlexVol '%s' still exists after pool deletion"
                % pool.name,
            )
            self.__class__.pool2 = None
        finally:
            for igroup_name in seeded:
                try:
                    self.ontap.delete_igroup(self.svm_name, igroup_name)
                except Exception as exc:
                    logger.warning(
                        "cleanup: could not delete seeded igroup '%s': %s",
                        igroup_name, exc,
                    )
            self._cleanup_throwaway_pool(
                self.__class__.pool2, flexvol_name=pool.name
            )
