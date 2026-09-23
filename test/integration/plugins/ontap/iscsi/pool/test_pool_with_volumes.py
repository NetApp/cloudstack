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
iSCSI pool lifecycle tests with a CloudStack data volume present throughout.

Covers the TDS (section 10) scenarios that require a data volume to already
exist on the pool during pool state transitions — the iSCSI variants of those
scenarios:

  TDS Approach-1 SN 11  — Disable iSCSI pool WITH volumes
  TDS Approach-1 SN 15  — Enable iSCSI pool WITH volumes
  TDS Approach-1 SN 19  — Enter maintenance WITH volumes
  TDS Approach-1 SN 23  — Cancel maintenance WITH volumes
  TDS Negative   SN 5   — Delete iSCSI pool that has volumes; forced=False rejected
  TDS Approach-1 SN 7   — Force-delete iSCSI pool (volume deleted first from
                           Maintenance — allowed on iSCSI unlike NFS3)

Key iSCSI difference from NFS3: cancelStorageMaintenance works on iSCSI because
the KVM agent can unmount/remount iSCSI LUNs correctly.  This allows the full
maintenance-cancel-maintenance lifecycle and proper volume cleanup while pool
is in Maintenance state.

Tests are numbered test_01 ... test_07 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create iSCSI pool and allocate a CloudStack data volume (LUN on ONTAP)
  02  Disable pool — volume survives; ONTAP LUN still exists        (SN 11)
  03  Re-enable pool — volume intact; ONTAP LUN accessible          (SN 15)
  04  Enter maintenance with volume — pool Maintenance; LUN exists  (SN 19)
  05  Cancel maintenance with volume — pool Up; LUN accessible      (SN 23)
  06  Re-enter maintenance; forced=False delete rejected            (Neg SN 5)
  07  Delete volume from Maintenance, then force-delete pool        (SN 7)

Isolated tests (test_08 onwards) run after that workflow and share no state
with it.  Each one builds its own pool plus CloudStack volume, breaks a single
ONTAP object behind CloudStack's back, and force-deletes the pool:

  08  FlexVol pre-deleted on ONTAP, then force-delete pool with CS volume
  09  Host igroups pre-deleted on ONTAP, then force-delete pool with CS volume
  10  Enter maintenance with CS volume after LUN maps are pre-deleted
  11  Cancel maintenance once the CS volume has been deleted

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has iSCSI initiator configured
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
    test/integration/plugins/ontap/iscsi/pool/test_pool_with_volumes.py -v
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
    destroyVolume as destroyVolumeAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.cloudstackException import CloudstackAPIException
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config

logger = logging.getLogger("TestOntapISCSIPoolWithVolumes")

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
                "email": "ontap-iscsi-wv@test.com",
                "firstname": "ONTAP",
                "lastname": "iSCSI-WV",
                "username": "ontap_iscsi_wv_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapISCSIWV_%d" % random.randint(0, 9999),
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
# Test class
# ---------------------------------------------------------------------------

class TestOntapISCSIPoolWithVolumes(OntapTestBase):
    """
    iSCSI pool lifecycle tests with a CloudStack data volume present throughout.
    Tests 01-07 are sequential; tests 08-10 use isolated throwaway resources.
    """

    _vol_name_prefix = "OntapISCSIWV"

    @classmethod
    def setUpClass(cls):
        super(TestOntapISCSIPoolWithVolumes, cls).setUpClass()
        testclient = super(
            TestOntapISCSIPoolWithVolumes, cls
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
        cls._capture_igroup_baseline()

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapISCSIWV_%d" % random.randint(0, 99999)

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = pool_name
        cmd.url = "iscsi://%s/ontap" % storage_ip
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

    def _volume_exists_in_cs(self, vol_id):
        """Return True if the volume is still listed by CloudStack."""
        from marvin.cloudstackAPI import listVolumes as listVolumesAPI
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.id = vol_id
        cmd.listall = True
        vols = self.apiClient.listVolumes(cmd) or []
        return len(vols) > 0

    def _assert_lun_exists(self, pool_name, msg_context=""):
        """Assert that at least one LUN exists in the pool's ONTAP FlexVol."""
        luns = self.ontap.list_luns_in_volume(self.svm_name, pool_name)
        self.assertTrue(
            len(luns) > 0,
            "Expected ≥1 LUN in ONTAP FlexVol '%s'%s, found 0"
            % (pool_name, " (%s)" % msg_context if msg_context else "")
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
    # Step 01 — Create pool and allocate a data volume
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_01_create_pool_and_volume(self):
        """
        Create an iSCSI primary storage pool and allocate a CloudStack data
        volume on it.
        Verifies:
          - Pool state is Up; pool type is OntapiSCSI
          - ONTAP: FlexVol is online
          - ONTAP: at least one igroup exists (one per cluster host with IQN)
          - ONTAP: after createVolume, a LUN exists in the FlexVol
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

        # ONTAP: the plugin creates an igroup only when a host is first
        # granted access to a LUN, so the empty pool must not change the
        # suite-start igroup baseline.
        self._assert_igroup_baseline_unchanged(
            "after creating an empty pool"
        )

        # Allocate a CloudStack data volume on this pool
        vol = self._create_volume(pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        # ONTAP: a LUN must exist in the FlexVol after volume creation
        self._assert_lun_exists(pool.name, "after volume creation")
        self._assert_igroup_baseline_unchanged(
            "after creating an unattached volume"
        )

        # Capacity reporting: LUN allocated but FlexVol size unchanged
        self._assert_pool_capacity(pool, "volume-allocated")

    # ------------------------------------------------------------------
    # Step 02 — Disable pool with volume present  (TDS SN 11)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_02_disable_pool_volume_survives(self):
        """
        Disable the pool while a CloudStack data volume exists on it.
        Covers TDS Approach-1 SN 11 (iSCSI):
          - Pool transitions to Disabled
          - Existing CS volume still listed
          - ONTAP: FlexVol remains online; LUN still exists
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = self.__class__.pool.id
        cmd.enabled = False
        self.apiClient.updateStoragePool(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Disabled", timeout=60)
        self.assertEqual(
            result.state, "Disabled",
            "Pool should be 'Disabled', got '%s'" % result.state
        )

        # CS volume must still exist
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after pool disable"
        )

        # ONTAP: FlexVol still online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after pool disable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' after pool disable"
        )

        # ONTAP: LUN still exists
        self._assert_lun_exists(self.__class__.pool.name, "after pool disable")

    # ------------------------------------------------------------------
    # Step 03 — Re-enable pool with volume present  (TDS SN 15)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_03_enable_pool_volume_intact(self):
        """
        Re-enable the pool while a CloudStack data volume exists on it.
        Covers TDS Approach-1 SN 15 (iSCSI):
          - Pool transitions back to Up
          - CS volume still listed
          - ONTAP: FlexVol online; LUN still exists
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = self.__class__.pool.id
        cmd.enabled = True
        self.apiClient.updateStoragePool(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Up", timeout=60)
        self.assertEqual(
            result.state, "Up",
            "Pool should be 'Up' after re-enable, got '%s'" % result.state
        )

        # CS volume must still exist
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after pool re-enable"
        )

        # ONTAP: FlexVol online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after pool re-enable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after pool re-enable"
        )

        # ONTAP: LUN still exists
        self._assert_lun_exists(self.__class__.pool.name, "after pool re-enable")

    # ------------------------------------------------------------------
    # Step 04 — Enter maintenance with volume present  (TDS SN 19)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_04_enter_maintenance_volume_present(self):
        """
        Enter maintenance mode while a CloudStack data volume exists on the pool.
        Covers TDS Approach-1 SN 19 (iSCSI):
          - Pool transitions to Maintenance
          - CS volume still listed (not destroyed)
          - ONTAP: FlexVol remains online (maintenance is a CloudStack state)
          - ONTAP: LUN still exists in the FlexVol

        Note: the TDS additionally expects VMs using this pool to stop and their
        LUN maps to be removed.  This suite uses a standalone data volume (not
        attached to any VM), so the VM stop behaviour is not exercised here — it
        is covered by the VM lifecycle test suite.
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.enableStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Maintenance", timeout=120)
        self.assertEqual(
            result.state, "Maintenance",
            "Pool should be 'Maintenance', got '%s'" % result.state
        )

        # CS volume must still exist
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after pool entered Maintenance"
        )

        # ONTAP: FlexVol still online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol disappeared after entering Maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' in Maintenance"
        )

        # ONTAP: LUN still exists
        self._assert_lun_exists(self.__class__.pool.name, "after entering Maintenance")

    # ------------------------------------------------------------------
    # Step 05 — Cancel maintenance with volume present  (TDS SN 23)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_05_cancel_maintenance_volume_present(self):
        """
        Cancel maintenance mode while a CloudStack data volume exists on the pool.
        Covers TDS Approach-1 SN 23 (iSCSI):
          - cancelStorageMaintenance works on iSCSI (unlike the NFS3 variant)
          - Pool transitions back to Up
          - CS volume still listed
          - ONTAP: FlexVol online; LUN still present in FlexVol

        Note: when VMs are attached to volumes on this pool, ONTAP would
        re-create the LUN-maps (igroup bindings) at cancel-maintenance time.
        This suite has no VMs attached, so LUN-map re-creation is not verified
        here; it is covered by the VM lifecycle test suite.
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.cancelStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Up", timeout=120)
        self.assertEqual(
            result.state, "Up",
            "Pool should be 'Up' after cancel maintenance, got '%s'" % result.state
        )

        # CS volume must still exist
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after cancel maintenance"
        )

        # ONTAP: FlexVol online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol disappeared after cancel maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after cancel maintenance"
        )

        # ONTAP: LUN still exists
        self._assert_lun_exists(self.__class__.pool.name, "after cancel maintenance")

    # ------------------------------------------------------------------
    # Step 06 — forced=False delete rejected (negative)  (TDS Neg SN 5)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_06_forced_false_delete_rejected(self):
        """
        Enter maintenance then attempt deleteStoragePool(forced=False) while
        a CloudStack volume exists on the pool.  The operation must be rejected.
        Covers TDS Negative Scenario SN 5 (iSCSI):
          - CloudstackAPIException is raised
          - Pool remains in Maintenance state
          - CS volume still exists
          - ONTAP: FlexVol and LUN unchanged
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        # Re-enter Maintenance (pool is Up from test_05)
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = self.__class__.pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(self.__class__.pool.id, "Maintenance", timeout=120)

        # Attempt forced=False delete — must raise
        with self.assertRaises(Exception,
                               msg="deleteStoragePool(forced=False) with a live "
                                   "volume should raise an exception"):
            self._delete_pool(self.__class__.pool.id, forced=False)

        # Pool must still be listed (in Maintenance)
        try:
            remaining = list_storage_pools(self.apiClient, id=self.__class__.pool.id)
        except Exception:
            remaining = None
        self.assertTrue(
            remaining,
            "Pool was deleted even though forced=False delete should have failed"
        )

        # CS volume must still exist
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume was deleted after rejected pool deletion"
        )

        # ONTAP: FlexVol still online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol should still exist after rejected pool deletion"
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' after rejected deletion"
        )

    # ------------------------------------------------------------------
    # Step 07 — Delete volume from Maintenance, then force-delete pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_07_delete_volume_and_force_delete_pool(self):
        """
        Delete the CloudStack volume (while pool is in Maintenance) then
        force-delete the pool.
        Covers TDS Approach-1 SN 7 (iSCSI):
          - On iSCSI, deleteVolume succeeds even when pool is in Maintenance
            (unlike NFS3 where the KVM agent raises NPE)
          - After volume deletion, the LUN is removed from the ONTAP FlexVol
          - force-delete pool removes pool, FlexVol, and all igroups
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name
        vol = self.__class__.volume

        # Delete the volume while pool is in Maintenance
        # (this works on iSCSI — no KVM NPE unlike NFS3)
        del_cmd = deleteVolumeAPI.deleteVolumeCmd()
        del_cmd.id = vol.id
        self.apiClient.deleteVolume(del_cmd)
        self.__class__.volume = None

        # ONTAP: LUN must be gone from the FlexVol after volume deletion
        luns_after = self.ontap.list_luns_in_volume(self.svm_name, pool_name)
        self.assertEqual(
            len(luns_after), 0,
            "Expected 0 LUNs in ONTAP FlexVol '%s' after volume deletion, "
            "found %d: %s" % (pool_name, len(luns_after), luns_after)
        )

        # ONTAP: FlexVol must still be online (pool deletion removes the FlexVol)
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' should still exist after CS volume deletion"
            % pool_name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' after CS volume deletion"
        )

        # Capacity reporting: capacity fields stable after LUN removal
        self._assert_pool_capacity(pool, "volume-deleted")

        # Force-delete the pool (no live volumes remain; pool is in Maintenance)
        self._delete_pool(pool.id, forced=True)
        self.__class__.pool = None

        # CloudStack: pool must be gone
        try:
            remaining = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            remaining = None
        self.assertFalse(
            remaining,
            "Pool '%s' still listed in CloudStack after force deletion" % pool_name
        )

        # ONTAP: FlexVol must be deleted
        ontap_vol_after = self.ontap.get_volume(pool_name)
        self.assertIsNone(
            ontap_vol_after,
            "ONTAP FlexVol '%s' still exists after pool force deletion" % pool_name
        )

        self._assert_no_lun_maps_for_volume(
            pool_name, "after pool force deletion"
        )
        self._assert_igroup_baseline_unchanged("after pool force deletion")

    # ==================================================================
    # Isolated tests — appended after the sequential workflow above.
    #
    # Each one creates its own pool and CloudStack volume in the pool2 /
    # volume2 slots (which OntapTestBase.tearDownClass also sweeps), runs a
    # single scenario, and cleans up in a finally block.  They never reuse a
    # pool destroyed by another test.
    # ==================================================================

    def _host_igroup_names(self):
        """igroup names the plugin creates, one per cluster host with an IQN.

        Built from the host UUID so the names match the plugin.
        """
        return [name for name, _ in self._host_igroup_specs()]

    def _host_igroup_specs(self):
        """(igroup name, initiator IQN) per cluster host that reports an IQN."""
        return self._iscsi_host_specs()

    def _enter_maintenance(self, pool):
        """Put the pool into Maintenance and wait for the state to settle."""
        cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(cmd)
        return self._poll_pool_state(pool.id, "Maintenance", timeout=120)

    def _create_isolated_pool_with_volume(self, label):
        """Build a fresh pool plus CS volume for one isolated scenario.

        Returns ``(pool, volume)``.  Any pool a previous isolated test could
        not clean up is swept first so its ONTAP objects are never orphaned by
        the overwrite of the pool2 slot.
        """
        if self.__class__.pool2 is not None:
            self._cleanup_isolated_pool(
                self.__class__.pool2, "leftover-from-previous-isolated-test"
            )

        pool = self._create_pool()
        self.__class__.pool2 = pool
        logger.info("[%s] created isolated pool '%s'", label, pool.name)

        self.assertEqual(
            pool.state, "Up",
            "[%s] new pool state should be 'Up', got '%s'" % (label, pool.state)
        )
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "[%s] ONTAP FlexVol not found for new pool '%s'" % (label, pool.name)
        )

        vol = self._create_volume(pool.id)
        self.__class__.volume2 = vol
        self.assertIsNotNone(vol, "[%s] createVolume returned None" % label)
        self._assert_lun_exists(pool.name, "%s: after volume creation" % label)
        return pool, vol

    def _assert_pool_absent(self, pool, label, delete_error=None):
        """Assert CloudStack no longer lists the pool."""
        try:
            remaining = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            remaining = None
        self.assertFalse(
            remaining,
            "[%s] pool '%s' is still listed after deleteStoragePool(forced=True)%s"
            % (label, pool.name,
               "; the API raised: %s" % delete_error if delete_error else "")
        )

    def _purge_cs_volume_record(self, vol, label):
        """Remove a CS volume record the forced pool delete may have left.

        The backing LUN is already gone at this point, so a failure here only
        affects tidiness — the volume stays in the volume2 slot for
        tearDownClass to retry and no exception is raised.
        """
        if vol is None:
            return
        if self._volume_exists_in_cs(vol.id):
            try:
                cmd = deleteVolumeAPI.deleteVolumeCmd()
                cmd.id = vol.id
                self.apiClient.deleteVolume(cmd)
                logger.info("[%s] deleted leftover CS volume record %s",
                            label, vol.id)
            except Exception as exc:
                logger.warning("[%s] could not delete leftover CS volume %s: %s",
                               label, vol.id, exc)
        else:
            logger.info("[%s] CS volume %s was removed along with the pool",
                        label, vol.id)
        if not self._volume_exists_in_cs(vol.id):
            self.__class__.volume2 = None

    def _exit_maintenance(self, pool, label):
        """Bring a pool out of Maintenance so its volumes can be deleted."""
        try:
            listed = list_storage_pools(self.apiClient, id=pool.id)
        except CloudstackAPIException:
            return False
        if not listed:
            return False
        if listed[0].state != "Maintenance":
            return True
        try:
            cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
            cmd.id = pool.id
            self.apiClient.cancelStorageMaintenance(cmd)
            self._poll_pool_state(pool.id, "Up", timeout=120)
            return True
        except Exception as exc:
            logger.warning("[%s] could not cancel maintenance on '%s': %s",
                           label, pool.name, exc)
            return False

    def _enter_maintenance_quietly(self, pool, label):
        """Enter Maintenance, tolerating a pool whose backend is already gone."""
        try:
            self._enter_maintenance(pool)
        except Exception as exc:
            logger.warning("[%s] could not enter maintenance on '%s': %s",
                           label, pool.name, exc)

    DESTROYED_VOLUME_STATES = ("destroy", "destroyed", "expunging", "expunged")

    def _cs_volume_state(self, vol_id):
        """Return the CloudStack volume state, or None when it is not listed."""
        vol = self._get_cs_volume(vol_id)
        return getattr(vol, "state", None) if vol is not None else None

    def _volume_cleared_for_pool_delete(self, vol_id):
        """True once the volume no longer blocks deleteStoragePool(forced)."""
        state = self._cs_volume_state(vol_id)
        return state is None or state.lower() in self.DESTROYED_VOLUME_STATES

    def _remove_cs_volume(self, pool, vol, label):
        """Clear the CloudStack volume so the pool can be force-deleted.

        deleteStoragePool(forced=True) refuses while any volume on the pool is
        in a state other than Destroy.  deleteVolume is tried first because it
        also reclaims the backing storage, but it expunges through libvirt and
        fails when the FlexVol is already gone.  destroyVolume(expunge=False)
        is the fallback: it only moves the record to Destroy, which is all the
        forced pool delete requires - it expunges the leftovers itself.
        """
        if vol is None or not self._volume_exists_in_cs(vol.id):
            self.__class__.volume2 = None
            return True
        self._exit_maintenance(pool, label)
        try:
            cmd = deleteVolumeAPI.deleteVolumeCmd()
            cmd.id = vol.id
            self.apiClient.deleteVolume(cmd)
        except Exception as exc:
            logger.warning("[%s] deleteVolume failed for %s (%s); falling back "
                           "to destroyVolume without expunge",
                           label, vol.id, exc)
            try:
                cmd = destroyVolumeAPI.destroyVolumeCmd()
                cmd.id = vol.id
                cmd.expunge = False
                self.apiClient.destroyVolume(cmd)
            except Exception as destroy_exc:
                logger.warning("[%s] destroyVolume also failed for %s: %s",
                               label, vol.id, destroy_exc)
        if not self._volume_cleared_for_pool_delete(vol.id):
            return False
        if not self._volume_exists_in_cs(vol.id):
            self.__class__.volume2 = None
        return True

    def _cleanup_isolated_pool(self, pool, label):
        """Best-effort teardown of one isolated pool and its ONTAP FlexVol.

        Igroups are intentionally left alone: their names carry no pool
        identity, so the pool delete owns their removal.
        """
        if pool is None:
            return
        try:
            listed = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            listed = None
        if listed:
            self._remove_cs_volume(pool, self.__class__.volume2, label)
            try:
                listed = list_storage_pools(self.apiClient, id=pool.id) or listed
                if listed[0].state != "Maintenance":
                    self._enter_maintenance(pool)
                self._delete_pool(pool.id, forced=True)
            except Exception as exc:
                logger.warning("[%s] could not force-delete pool '%s': %s",
                               label, pool.name, exc)
        try:
            self.ontap.offline_and_delete_volume(pool.name)
        except Exception as exc:
            logger.warning("[%s] ONTAP FlexVol cleanup for '%s' failed: %s",
                           label, pool.name, exc)
        try:
            listed = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            listed = None
        if not listed:
            self.__class__.pool2 = None

    # ------------------------------------------------------------------
    # Step 08 — FlexVol deleted on ONTAP before the pool delete (negative)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_08_delete_pool_with_volume_flexvol_missing(self):
        """
        Force-delete a pool that still owns a CloudStack volume after its
        ONTAP FlexVol — and with it the volume's LUN — has been removed behind
        CloudStack's back.

        Uses its own pool and volume, so the pool is known to be healthy up to
        the point the FlexVol is destroyed.  Verifies:
          - deleteStoragePool is rejected while the CS volume still exists
          - deleteStoragePool(forced=True) tolerates the missing FlexVol
          - the CloudStack pool record is removed
          - the leftover CS volume record can still be cleaned up
        """
        label = "flexvol-missing"
        pool, vol = self._create_isolated_pool_with_volume(label)
        try:
            self._enter_maintenance(pool)

            self.ontap.offline_and_delete_volume(pool.name)
            self.assertIsNone(
                self.ontap.get_volume(pool.name),
                "[%s] ONTAP FlexVol '%s' should be gone before the pool delete"
                % (label, pool.name)
            )
            self.assertEqual(
                len(self.ontap.list_luns_in_volume(self.svm_name, pool.name)), 0,
                "[%s] LUNs should have gone with the FlexVol '%s'"
                % (label, pool.name)
            )

            # CloudStack rejects deleteStoragePool while the pool still owns
            # a volume, even with forced=True, so the volume goes first.
            with self.assertRaises(CloudstackAPIException):
                self._delete_pool(pool.id, forced=True)
            self.assertTrue(
                self._remove_cs_volume(pool, vol, label),
                "[%s] CloudStack volume could not be deleted before the pool "
                "delete" % label
            )
            self._enter_maintenance_quietly(pool, label)

            delete_error = None
            try:
                self._delete_pool(pool.id, forced=True)
            except CloudstackAPIException as exc:
                delete_error = exc

            self._assert_pool_absent(pool, label, delete_error)
            self.assertIsNone(
                delete_error,
                "[%s] deleteStoragePool(forced=True) should tolerate a missing "
                "FlexVol, but raised: %s" % (label, delete_error)
            )

            self._assert_igroup_baseline_unchanged(
                "[%s] after pool delete with missing FlexVol" % label
            )

            self._purge_cs_volume_record(vol, label)
        finally:
            self._cleanup_isolated_pool(pool, label)

    # ------------------------------------------------------------------
    # Step 09 — Host igroups deleted on ONTAP before the delete (negative)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_09_delete_pool_with_volume_igroups_missing(self):
        """
        Force-delete a pool that still owns a CloudStack volume after the host
        igroups have been removed behind CloudStack's back.

        Uses its own pool and volume.  The volume is not attached to any VM, so
        no LUN maps reference the igroups and they delete cleanly.  Unlike
        test_08 the FlexVol is still present, so the plugin is expected to
        remove it as part of the delete.  Verifies:
          - deleteStoragePool is rejected while the CS volume still exists
          - deleteStoragePool(forced=True) tolerates the missing igroups
          - the CloudStack pool record is removed
          - the ONTAP FlexVol is deleted and no igroup is left behind
        """
        other_pools = self._other_ontap_pools_on_svm(None)
        if other_pools:
            self.skipTest(
                "Pre-deleting SVM-wide igroups requires exclusive SVM use; "
                "found other ONTAP pool(s): %s"
                % ", ".join(str(getattr(p, "name", p)) for p in other_pools)
            )
        label = "igroups-missing"
        pool, vol = self._create_isolated_pool_with_volume(label)
        seeded = []
        try:
            igroup_specs = self._host_igroup_specs()
            self.assertTrue(
                igroup_specs,
                "[%s] no cluster host reports an IQN, so there is no igroup "
                "to remove" % label
            )
            igroup_names = [name for name, _ in igroup_specs]

            # The volume is not attached to a VM, so the plugin has never
            # granted a host access and created no igroups.  Seed them under
            # the plugin's own names so the pre-deletion is a real one.
            for name, iqn in igroup_specs:
                if self.ontap.get_igroup(self.svm_name, name) is None:
                    logger.info("[%s] seeding igroup '%s' with initiator '%s'",
                                label, name, iqn)
                    self.ontap.create_igroup(self.svm_name, name, iqn)
                    seeded.append(name)

            self._enter_maintenance(pool)

            deleted = []
            for name in igroup_names:
                if self.ontap.get_igroup(self.svm_name, name) is None:
                    continue
                self.ontap.delete_igroup(self.svm_name, name)
                deleted.append(name)
            logger.info("[%s] deleted %d of %d host igroup(s): %s",
                        label, len(deleted), len(igroup_names), deleted)

            for name in igroup_names:
                self.assertIsNone(
                    self.ontap.get_igroup(self.svm_name, name),
                    "[%s] igroup '%s' should be gone before the pool delete"
                    % (label, name)
                )

            # CloudStack rejects deleteStoragePool while the pool still owns
            # a volume, even with forced=True, so the volume goes first.
            with self.assertRaises(CloudstackAPIException):
                self._delete_pool(pool.id, forced=True)
            self.assertTrue(
                self._remove_cs_volume(pool, vol, label),
                "[%s] CloudStack volume could not be deleted before the pool "
                "delete" % label
            )
            self._enter_maintenance_quietly(pool, label)

            delete_error = None
            try:
                self._delete_pool(pool.id, forced=True)
            except CloudstackAPIException as exc:
                delete_error = exc

            self._assert_pool_absent(pool, label, delete_error)
            self.assertIsNone(
                delete_error,
                "[%s] deleteStoragePool(forced=True) should tolerate missing "
                "igroups, but raised: %s" % (label, delete_error)
            )

            self.assertIsNone(
                self.ontap.get_volume(pool.name),
                "[%s] ONTAP FlexVol '%s' should have been deleted with the pool"
                % (label, pool.name)
            )
            for name in igroup_names:
                self.assertIsNone(
                    self.ontap.get_igroup(self.svm_name, name),
                    "[%s] igroup '%s' reappeared during the pool delete"
                    % (label, name)
                )

            self._purge_cs_volume_record(vol, label)
        finally:
            for name in seeded:
                try:
                    self.ontap.delete_igroup(self.svm_name, name)
                except Exception as exc:
                    logger.warning(
                        "[%s] cleanup: could not delete seeded igroup '%s': %s",
                        label, name, exc)
            self._cleanup_isolated_pool(pool, label)

    # ------------------------------------------------------------------
    # Step 10 — Enter maintenance after LUN maps are pre-deleted
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_10_enter_maintenance_lun_maps_predeleted(self):
        """
        Enter maintenance with a CloudStack volume after its ONTAP LUN maps
        have been deleted behind CloudStack's back. Verifies:
          - enableStorageMaintenance tolerates already-absent LUN maps
          - the pool reaches Maintenance and the CS volume remains present
          - the LUN remains online and its maps remain absent
        """
        label = "maintenance-lun-maps-missing"
        pool, vol = self._create_isolated_pool_with_volume(label)
        seeded_igroup = None
        try:
            luns = self.ontap.list_luns_in_volume(self.svm_name, pool.name)
            self.assertTrue(
                luns,
                "[%s] no LUN found in FlexVol '%s'" % (label, pool.name)
            )
            lun_path = luns[0].get("name")

            igroup_specs = self._host_igroup_specs()
            self.assertTrue(
                igroup_specs,
                "[%s] no cluster host reports an IQN" % label
            )
            igroup_name, initiator_iqn = igroup_specs[0]
            if self.ontap.get_igroup(self.svm_name, igroup_name) is None:
                self.ontap.create_igroup(
                    self.svm_name, igroup_name, initiator_iqn
                )
                seeded_igroup = igroup_name

            self.ontap.create_lun_map(
                self.svm_name, lun_path, igroup_name
            )
            maps = self.ontap.list_lun_maps_for_volume(
                self.svm_name, pool.name
            )
            self.assertTrue(
                maps,
                "[%s] failed to seed a LUN map for '%s'" % (label, lun_path)
            )

            for lun_map in maps:
                self.ontap.delete_lun_map(lun_map)
            self.assertEqual(
                self.ontap.list_lun_maps_for_volume(
                    self.svm_name, pool.name
                ),
                [],
                "[%s] LUN maps should be absent before maintenance" % label
            )

            self._enter_maintenance(pool)
            self.assertTrue(
                self._volume_exists_in_cs(vol.id),
                "[%s] CS volume disappeared after entering maintenance" % label
            )
            self._assert_lun_exists(
                pool.name, "after entering Maintenance with maps pre-deleted"
            )
            self.assertEqual(
                self.ontap.list_lun_maps_for_volume(
                    self.svm_name, pool.name
                ),
                [],
                "[%s] LUN maps unexpectedly reappeared during maintenance"
                % label
            )
        finally:
            self._cleanup_isolated_pool(pool, label)
            if seeded_igroup:
                try:
                    self.ontap.delete_igroup(
                        self.svm_name, seeded_igroup
                    )
                except Exception as exc:
                    logger.warning(
                        "[%s] cleanup: could not delete seeded igroup '%s': %s",
                        label, seeded_igroup, exc
                    )

    # ------------------------------------------------------------------
    # Step 11 — Cancel maintenance once the CS volume has been deleted
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_with_volumes"], required_hardware=True)
    def test_11_cancel_maintenance_after_volume_deleted(self):
        """
        Cancel maintenance on a pool whose CloudStack volume has been deleted.

        Complements test_05, which cancels maintenance with the volume still
        present.  On iSCSI the volume can be deleted while the pool sits in
        Maintenance, so that is the order used here.  Verifies:
          - the LUN is removed when the volume is deleted
          - the pool returns to Up
          - the ONTAP FlexVol is still online
        """
        label = "cancel-maintenance-no-volume"
        pool, vol = self._create_isolated_pool_with_volume(label)
        try:
            self._enter_maintenance(pool)

            del_cmd = deleteVolumeAPI.deleteVolumeCmd()
            del_cmd.id = vol.id
            self.apiClient.deleteVolume(del_cmd)
            self.assertFalse(
                self._volume_exists_in_cs(vol.id),
                "[%s] CS volume %s should be gone before cancel maintenance"
                % (label, vol.id)
            )
            self.__class__.volume2 = None
            vol = None

            luns_after = self.ontap.list_luns_in_volume(self.svm_name, pool.name)
            self.assertEqual(
                len(luns_after), 0,
                "[%s] expected 0 LUNs in FlexVol '%s' after volume deletion, "
                "found %d: %s" % (label, pool.name, len(luns_after), luns_after)
            )

            cancel_cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
            cancel_cmd.id = pool.id
            self.apiClient.cancelStorageMaintenance(cancel_cmd)

            result = self._poll_pool_state(pool.id, "Up", timeout=120)
            self.assertEqual(
                result.state, "Up",
                "[%s] pool should be 'Up' after cancel maintenance, got '%s'"
                % (label, result.state)
            )

            ontap_vol = self.ontap.get_volume(pool.name)
            self.assertIsNotNone(
                ontap_vol,
                "[%s] ONTAP FlexVol '%s' disappeared after cancel maintenance"
                % (label, pool.name)
            )
            self.assertEqual(
                ontap_vol.get("state"), "online",
                "[%s] ONTAP FlexVol should be 'online' after cancel "
                "maintenance, got '%s'" % (label, ontap_vol.get("state"))
            )
        finally:
            self._purge_cs_volume_record(vol, label)
            self._cleanup_isolated_pool(pool, label)
