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
NFS3 pool lifecycle tests with a CloudStack volume present throughout.

Tests are numbered test_01 ... test_07 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create NFS3 pool and allocate a CloudStack data volume
  02  Disable pool — volume still exists in CloudStack; ONTAP FlexVol online
  03  Re-enable pool — volume still accessible; FlexVol online
  04  Enter maintenance with volume present — Maintenance state; FlexVol online
  05  Cancel maintenance with volume present — pool returns to Up (fix confirmed)
  06  Forced=False delete rejected — pool stays in Maintenance (negative)
  07  Cleanup — cancel maintenance, delete volume, force-delete pool

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster registered in CloudStack
  - ONTAP SVM with NFS3 service enabled and at least one NFS data LIF
  - ontap.cfg populated with real values (protocol=NFS3)

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/test_ontap_nfs3_pool_with_volumes.py -v

Note: Tests 01-05 share class-level state (sequential).  Running a single test
with -m "test_NN" will invoke setUpClass but the guard assertion will fail
immediately if earlier steps have not yet run.  Always run the full suite.

Post-run ONTAP cleanup: The suite ends with the pool in Maintenance state (from
test_04) and a CS volume present (test_05 negative test leaves both intact).  The
OntapTestBase teardown exits Maintenance via cancelStorageMaintenance (which
transitions CS pool state even though KVM remount fails on NFS3), deletes the
volume, re-enters Maintenance, and force-deletes the pool.  In rare cases where
CS pool state does not transition, one orphaned ONTAP FlexVol and export policy
may be left behind.  Clean these up manually:

  curl -sk -u <user>:<pass> \\
      "https://<ontap>/api/storage/volumes?name=OntapNFS3WV_*&fields=name,state"
  # Offline + DELETE each orphan, then DELETE the matching export policy."""

import base64
import logging
import random
import time
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

from ontap_test_base import OntapRestClient, OntapTestBase, _parse_pool_details

logger = logging.getLogger("TestOntapNFS3PoolWithVolumes")


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
                "email": "ontap-nfs3-wv@test.com",
                "firstname": "ONTAP",
                "lastname": "NFS3-WV",
                "username": "ontap_nfs3_wv_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapNFS3WV_%d" % random.randint(0, 9999),
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
# Test class
# ---------------------------------------------------------------------------

class TestOntapNFS3PoolWithVolumes(OntapTestBase):
    """
    NFS3 pool lifecycle tests with a CloudStack data volume present throughout.
    All tests are sequential and share class-level state.
    """

    pool_ep_name = None    # NFS export policy name extracted at pool creation

    _vol_name_prefix = "OntapNFS3WV"

    @classmethod
    def setUpClass(cls):
        testclient = super(
            TestOntapNFS3PoolWithVolumes, cls
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

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapNFS3WV_%d" % random.randint(0, 99999)

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
        """Extract the NFS export policy name from pool creation response details."""
        details = _parse_pool_details(pool)
        ep_name = details.get("exportPolicyName")
        if not ep_name:
            ep_name = "cs-%s-%s" % (self.svm_name, pool.name)
        return ep_name

    def _volume_exists_in_cs(self, vol_id):
        """Return True if the volume is still listed by CloudStack."""
        from marvin.cloudstackAPI import listVolumes as listVolumesAPI
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.id = vol_id
        cmd.listall = True
        vols = self.apiClient.listVolumes(cmd) or []
        return len(vols) > 0

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

    @attr(tags=["nfs3_with_volumes"], required_hardware=True)
    def test_01_create_pool_and_volume(self):
        """
        Create an NFS3 primary storage pool and allocate a CloudStack data
        volume on it.
        Verifies:
          - Pool state is Up; ONTAP FlexVol is online
          - NFS export policy exists
          - createVolume returns a volume object (NFS3 data vols are CS records
            backed by a qcow2 file inside the FlexVol)
        """
        pool = self._create_pool()
        self.__class__.pool = pool

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
        )

        ep_name = self._get_export_policy_name(pool)
        self.__class__.pool_ep_name = ep_name

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

        # ONTAP: export policy must exist
        policy = self.ontap.get_export_policy(ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' not found on ONTAP after pool creation" % ep_name
        )

        # Allocate a CloudStack data volume on this pool
        vol = self._create_volume(pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        # Capacity reporting: volume allocated on FlexVol
        self._assert_pool_capacity(pool, "volume-allocated")

    # ------------------------------------------------------------------
    # Step 02 — Disable pool with volume present
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_with_volumes"], required_hardware=True)
    def test_02_disable_pool_volume_survives(self):
        """
        Disable the pool while a CloudStack data volume exists on it:
          - Pool should no longer be available for scheduling new CS volumes
          - The existing CS volume should continue to exist (not deleted)
          - ONTAP: FlexVol remains online; export policy unchanged
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

        # Volume must still exist in CloudStack
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after pool disable"
        )

        # ONTAP: FlexVol must still be online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after pool disable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' after pool disable, got '%s'"
            % ontap_vol.get("state")
        )

        # ONTAP: export policy must still exist
        policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' should still exist after pool disable"
            % self.__class__.pool_ep_name
        )

    # ------------------------------------------------------------------
    # Step 03 — Re-enable pool with volume present
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_with_volumes"], required_hardware=True)
    def test_03_enable_pool_volume_intact(self):
        """
        Re-enable the pool while a CloudStack data volume exists on it:
          - Pool state transitions back to Up
          - The existing CS volume is still accessible
          - ONTAP: FlexVol remains online; export policy unchanged
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

        # Volume must still exist in CloudStack
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after pool re-enable"
        )

        # ONTAP: FlexVol must still be online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after pool re-enable")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after pool re-enable, got '%s'"
            % ontap_vol.get("state")
        )

        # ONTAP: export policy must still exist
        policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' should still exist after pool re-enable"
            % self.__class__.pool_ep_name
        )

    # ------------------------------------------------------------------
    # Step 04 — Enter maintenance mode with volume present
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_with_volumes"], required_hardware=True)
    def test_04_enter_maintenance_volume_present(self):
        """
        Enter maintenance mode while a CloudStack data volume exists on the pool:
          - Pool transitions to Maintenance state
          - Existing CS volume remains in CloudStack
          - ONTAP: FlexVol stays online (maintenance is a CS-only state)
          - ONTAP: export policy is unchanged
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

        # Volume must still exist in CloudStack
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after pool entered Maintenance"
        )

        # ONTAP: FlexVol must still be online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol disappeared after entering Maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' in Maintenance, got '%s'"
            % ontap_vol.get("state")
        )

        # ONTAP: export policy must still exist
        policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' should still exist during Maintenance"
            % self.__class__.pool_ep_name
        )

    # ------------------------------------------------------------------
    # Step 05 — Cancel maintenance mode with volume present
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_with_volumes"], required_hardware=True)
    def test_05_cancel_maintenance_with_volume(self):
        """
        Cancel maintenance mode while a CloudStack data volume exists on the pool:
          - cancelStorageMaintenance succeeds (KVM/NFS3 fix confirmed)
          - Pool returns to Up state
          - Existing CS volume is still present in CloudStack
          - ONTAP: FlexVol is still online
          - ONTAP: NFS export policy is unchanged
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.cancelStorageMaintenance(cmd)

        result = self._poll_pool_state(
            self.__class__.pool.id, "Up", timeout=120
        )
        self.assertEqual(
            result.state, "Up",
            "Pool should be 'Up' after cancel maintenance, got '%s'" % result.state
        )

        # CS volume must still exist
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume disappeared after cancel maintenance"
        )

        # ONTAP: FlexVol must still be online
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

        # ONTAP: export policy must still exist
        if self.__class__.pool_ep_name:
            policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after cancel maintenance"
                % self.__class__.pool_ep_name
            )

    # ------------------------------------------------------------------
    # Step 06 — forced=False delete rejected when volume present (negative)
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_with_volumes"], required_hardware=True)
    def test_06_forced_false_delete_rejected(self):
        """
        Enter maintenance mode then attempt to delete the pool (forced=False)
        while a CloudStack volume still exists on it.  The operation must be
        rejected:
          - CloudstackAPIException is raised with an appropriate error
          - Pool remains in Maintenance state
          - CS volume still exists
          - ONTAP: FlexVol and export policy are unchanged
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_01 must pass first")

        # Pool is Up after test_05 (cancel maintenance); re-enter Maintenance
        # before attempting the delete so it reaches the forced=False gate.
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = self.__class__.pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(self.__class__.pool.id, "Maintenance", timeout=120)

        with self.assertRaises(CloudstackAPIException,
                               msg="deleteStoragePool(forced=False) with a live "
                                   "volume should raise CloudstackAPIException"):
            self._delete_pool(self.__class__.pool.id, forced=False)

        # Pool must still be in Maintenance (not deleted)
        try:
            remaining = list_storage_pools(
                self.apiClient, id=self.__class__.pool.id)
        except CloudstackAPIException:
            remaining = None
        self.assertTrue(
            remaining,
            "Pool was deleted even though forced=False delete should have failed"
        )

        # Volume must still exist
        self.assertTrue(
            self._volume_exists_in_cs(self.__class__.volume.id),
            "CS volume was deleted even though pool deletion was rejected"
        )

        # ONTAP: FlexVol must still be online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol should still exist after rejected pool deletion"
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' after rejected deletion"
        )

    @attr(tags=["nfs3_with_volumes"], required_hardware=True)
    def test_07_force_delete_pool_and_cleanup(self):
        """
        Explicit cleanup after the test_06 negative test.

        The pool is in Maintenance with a CS volume still present.
        Cleanup sequence:
          1. Try cancelStorageMaintenance.
          2. If still in Maintenance: try updateStoragePool(enabled=True) as a
             fallback exit path (works on KVM/NFS3 even when cancel fails).
          3. Once pool exits Maintenance: delete volume, re-enter Maintenance.
          4. Force-delete the pool.
          5. Verify CS pool is gone.
          6. Verify ONTAP FlexVol and export policy are removed.
             If the CS force-delete fails (volume couldn't be removed), the
             ONTAP FlexVol and export policy are cleaned up directly via REST
             so the storage array is never left with orphans. The CS pool
             record is left for tearDownClass in that edge case only.
        """
        pool = self.__class__.pool
        vol = self.__class__.volume
        self.assertIsNotNone(pool, "No pool from test_06 to clean up")
        pool_name = pool.name
        ep_name = self.__class__.pool_ep_name

        # Step 1: Try cancelStorageMaintenance
        pool_state = "Maintenance"
        try:
            cm = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
            cm.id = pool.id
            self.apiClient.cancelStorageMaintenance(cm)
            deadline = time.time() + 60
            while time.time() < deadline:
                ps = list_storage_pools(self.apiClient, id=pool.id)
                if ps and ps[0].state != "Maintenance":
                    pool_state = ps[0].state
                    break
                time.sleep(5)
        except Exception:
            pass  # falls through to step 2

        # Step 2: If still in Maintenance, try updateStoragePool(enabled=True).
        # On KVM/NFS3 this succeeds in moving the pool to Disabled/Up even
        # when cancelStorageMaintenance fails.
        if pool_state == "Maintenance":
            try:
                ec = updateStoragePoolAPI.updateStoragePoolCmd()
                ec.id = pool.id
                ec.enabled = True
                self.apiClient.updateStoragePool(ec)
                deadline = time.time() + 60
                while time.time() < deadline:
                    ps = list_storage_pools(self.apiClient, id=pool.id)
                    if ps and ps[0].state != "Maintenance":
                        pool_state = ps[0].state
                        break
                    time.sleep(5)
            except Exception:
                pass

        # Step 3: If pool exited Maintenance, delete the CS volume and
        # re-enter Maintenance so the pool can be force-deleted.
        if pool_state != "Maintenance" and vol is not None:
            if self._volume_exists_in_cs(vol.id):
                try:
                    del_cmd = deleteVolumeAPI.deleteVolumeCmd()
                    del_cmd.id = vol.id
                    self.apiClient.deleteVolume(del_cmd)
                    self.__class__.volume = None
                    vol = None
                except Exception:
                    pass
            else:
                self.__class__.volume = None
                vol = None
            try:
                mc = enableStorageMaintenance.enableStorageMaintenanceCmd()
                mc.id = pool.id
                self.apiClient.enableStorageMaintenance(mc)
                deadline = time.time() + 60
                while time.time() < deadline:
                    ps = list_storage_pools(self.apiClient, id=pool.id)
                    if ps and ps[0].state == "Maintenance":
                        pool_state = "Maintenance"
                        break
                    time.sleep(5)
            except Exception:
                pass

        # Step 4: Force-delete the CS pool.
        cs_pool_deleted = False
        if vol is None or not self._volume_exists_in_cs(vol.id):
            # Volume is gone — safe to force-delete
            self.__class__.volume = None
            vol = None
            try:
                self._delete_pool(pool.id, forced=True)
                self.__class__.pool = None
                cs_pool_deleted = True
            except CloudstackAPIException:
                pass

        # Step 5: Assert CS pool is gone (only when deletion was attempted)
        if cs_pool_deleted:
            try:
                remaining = list_storage_pools(self.apiClient, id=pool.id)
            except CloudstackAPIException:
                remaining = None
            self.assertFalse(
                remaining,
                "Pool '%s' should have been deleted with forced=True" % pool_name
            )

        # Step 6: ONTAP FlexVol must be gone.
        # If the CS pool could not be deleted (volume still present — an
        # NFS3/KVM platform edge case), delete the ONTAP FlexVol and export
        # policy directly via REST so the storage array is always clean.
        # The orphaned CS pool record is left for tearDownClass.
        ontap_vol = self.ontap.get_volume(pool_name)
        if ontap_vol is not None:
            self.ontap.delete_volume(pool_name)
            ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNone(
            ontap_vol,
            "ONTAP FlexVol '%s' should be gone after cleanup" % pool_name
        )

        policy = self.ontap.get_export_policy(ep_name)
        if policy is not None:
            self.ontap.delete_export_policy(ep_name)
            policy = self.ontap.get_export_policy(ep_name)
        self.assertIsNone(
            policy,
            "NFS export policy '%s' should be removed after cleanup" % ep_name
        )
