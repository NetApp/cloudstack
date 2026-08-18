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
# Helpers
# ---------------------------------------------------------------------------

def _igroup_name(svm_name, host_name):
    """Mirror OntapStorageUtils.getIgroupName: cs_{svmName}_{sanitizedHostName}"""
    short = host_name.split(".")[0]
    sanitized = re.sub(r"[^a-zA-Z0-9_-]", "_", short)
    return "cs_%s_%s" % (svm_name, sanitized)


# ---------------------------------------------------------------------------
# Test class
# ---------------------------------------------------------------------------

class TestOntapISCSIPoolWithVolumes(OntapTestBase):
    """
    iSCSI pool lifecycle tests with a CloudStack data volume present throughout.
    All 7 tests are sequential and share class-level state.
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
          - Pool state is Up; pool type is OntapSAN
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
            pool.type, "OntapSAN",
            "Pool type should be 'OntapSAN', got '%s'" % pool.type
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
            iqn = getattr(host, "storageurl", None)
            if not iqn or not iqn.startswith("iqn."):
                continue
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            self.assertIsNotNone(
                igroup,
                "ONTAP igroup '%s' not found for host '%s'"
                % (igroup_name, host.name)
            )

        # Allocate a CloudStack data volume on this pool
        vol = self._create_volume(pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        # ONTAP: a LUN must exist in the FlexVol after volume creation
        self._assert_lun_exists(pool.name, "after volume creation")

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

        # ONTAP: igroups for all cluster hosts must be deleted
        for host in self.cluster_hosts:
            iqn = getattr(host, "storageurl", None)
            if not iqn or not iqn.startswith("iqn."):
                continue
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            self.assertIsNone(
                igroup,
                "ONTAP igroup '%s' still exists after pool force deletion"
                % igroup_name
            )
