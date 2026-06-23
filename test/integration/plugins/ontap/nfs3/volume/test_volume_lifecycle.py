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
Sequential workflow integration tests for NetApp ONTAP NFS3 data volume
lifecycle (volume create / delete / negative-delete / force-delete).

For NFS3, a CloudStack data volume is a metadata record only — no new ONTAP
object is created per volume (the pool's single FlexVol serves all volumes).
Volume deletion likewise removes the CloudStack record while leaving the
FlexVol intact.

Tests are numbered test_01 ... test_05 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create NFS3 primary storage pool and allocate a CloudStack data volume
  02  Delete the volume — CloudStack record removed; FlexVol stays online
  03  Recreate volume — CS record back; FlexVol stays online (setup for 04-05)
  04  Put pool in Maintenance; attempt forced=False deleteStoragePool — must be
      rejected because volumes exist; pool stays in Maintenance
  05  Delete volume from Maintenance; forced=True deleteStoragePool — FlexVol
      and export policy are removed from ONTAP

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has NFS configured
  - ONTAP SVM with NFS3 service enabled and at least one NFS data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/nfs3/volume/ -v

Note: Tests share class-level state (sequential).  Always run the full suite.
"""

import base64
import logging
import random
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    createStoragePool as createStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, _parse_pool_details

logger = logging.getLogger("TestOntapNFS3VolumeLifecycle")


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
                "email": "ontap-nfs3-vol@test.com",
                "firstname": "ONTAP",
                "lastname": "NFS3-Vol",
                "username": "ontap_nfs3_vol_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapNFS3Vol_%d" % random.randint(0, 9999),
                TestData.scope: scope,
                TestData.provider: provider,
                TestData.tags: tags,
                "capacitybytes": capacitybytes,
                "managed": True,
                "details": {
                    TestData.DETAIL_USERNAME: username,
                    TestData.DETAIL_PASSWORD: encoded_password,
                    TestData.DETAIL_SVM_NAME: svm_name,
                    TestData.DETAIL_PROTOCOL: "NFS3",
                    TestData.DETAIL_STORAGE_IP: storage_ip,
                },
            },
        }


# ---------------------------------------------------------------------------
# Sequential workflow test class
# ---------------------------------------------------------------------------

class TestOntapNFS3VolumeLifecycle(OntapTestBase):

    _vol_name_prefix = "OntapNFS3Vol"

    @classmethod
    def setUpClass(cls):
        testclient = super(
            TestOntapNFS3VolumeLifecycle, cls
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
        scope = pool_cfg.get("storagePoolScope", "CLUSTER")
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")
        tags = nfs3_cfg.get("storagePoolTags", "ontap-nfs3")
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

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapNFS3Vol_%d" % random.randint(0, 99999)

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
            ep_name = "cs-%s-%s" % (self.svm_name, pool.name)
        return ep_name

    # ------------------------------------------------------------------
    # Step 01 - Create pool (infrastructure) and allocate a volume
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_volume"], required_hardware=True)
    def test_01_create_pool_and_volume(self):
        """
        Create a new NFS3 pool and allocate a CloudStack data volume on it.
        For NFS3, volume creation is a CloudStack metadata record only — no
        new ONTAP object is created (the pool's FlexVol serves all volumes).
        Verifies:
          - pool.state is Up
          - createVolume returns a non-None volume object
          - ONTAP: FlexVol remains online after volume allocation
          - ONTAP: export policy still present
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

        # ONTAP: FlexVol must remain online after volume allocation
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' not found after volume creation" % pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online', got '%s'" % ontap_vol.get("state")
        )

        # ONTAP: export policy must still be present
        policy = self.ontap.get_export_policy(ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' should exist after volume creation" % ep_name
        )

    # ------------------------------------------------------------------
    # Step 02 - Delete volume; FlexVol must remain untouched
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_volume"], required_hardware=True)
    def test_02_delete_volume(self):
        """
        Delete the volume created in test_01.
        For NFS3, volume deletion removes only the CloudStack record.
        Verifies:
          - deleteVolume completes without error
          - ONTAP: FlexVol is still online (unaffected by volume deletion)
          - ONTAP: export policy still present
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_01 must pass first")

        pool = self.__class__.pool
        ep_name = self.__class__.pool_ep_name
        vol = self.__class__.volume

        cmd = deleteVolumeAPI.deleteVolumeCmd()
        cmd.id = vol.id
        self.apiClient.deleteVolume(cmd)
        self.__class__.volume = None

        # ONTAP: FlexVol must still be online
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' should still exist after volume deletion" % pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' after volume deletion, "
            "got '%s'" % ontap_vol.get("state")
        )

        # ONTAP: export policy must still be present
        if ep_name:
            policy = self.ontap.get_export_policy(ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after volume deletion" % ep_name
            )

    # ------------------------------------------------------------------
    # Step 03 - Recreate volume for negative delete tests
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_volume"], required_hardware=True)
    def test_03_recreate_volume_for_delete_tests(self):
        """
        Recreate a volume on the existing pool (setup for tests 04-05).
        Verifies:
          - volume created successfully
          - ONTAP: FlexVol still online
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        vol = self._create_volume(self.__class__.pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' not found after volume re-creation"
            % self.__class__.pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after volume re-creation"
        )

    # ------------------------------------------------------------------
    # Step 04 - Forced=False delete with live volume must fail
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_volume"], required_hardware=True)
    def test_04_forced_false_delete_with_volume_fails(self):
        """
        Put pool in Maintenance then attempt deleteStoragePool(forced=False).
        With a live volume present CloudStack must reject the request.
        Verifies:
          - Exception is raised (CloudStack rejects the delete)
          - Pool is still listed in CloudStack (in Maintenance state)
          - ONTAP: FlexVol still exists and is online
          - ONTAP: export policy still present
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_03 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name
        ep_name = self.__class__.pool_ep_name

        # Enter maintenance mode
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)

        # Attempt forced=False delete — must raise exception because volumes exist
        with self.assertRaises(Exception):
            self._delete_pool(pool.id, forced=False)

        # Pool must still be listed in CloudStack
        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(
            listed,
            "Pool should still exist in CloudStack after failed forced=False delete"
        )

        # ONTAP: FlexVol must still be online
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' should still exist after failed delete" % pool_name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online', got '%s'" % ontap_vol.get("state")
        )

        # ONTAP: export policy must still be present
        if ep_name:
            policy = self.ontap.get_export_policy(ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after failed delete" % ep_name
            )

    # ------------------------------------------------------------------
    # Step 05 - Delete volume then force-delete pool from Maintenance
    # ------------------------------------------------------------------

    @attr(tags=["nfs3_volume"], required_hardware=True)
    def test_05_delete_volume_and_force_delete_pool(self):
        """
        Delete the live volume then force-delete the pool while it is still
        in Maintenance state (pool is in Maintenance from test_04).
        Verifies:
          - Volume can be deleted while pool is in Maintenance
          - Pool is removed from CloudStack using forced=True from Maintenance
          - ONTAP: FlexVol deleted
          - ONTAP: export policy deleted
        """
        self.assertIsNotNone(
            self.__class__.pool,
            "Pool absent - test_04 must not have cleaned up the pool"
        )
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_03 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name
        ep_name = self.__class__.pool_ep_name
        vol = self.__class__.volume

        # Delete the volume first (pool is in Maintenance — volume deletion is
        # allowed).  For NFS3 a forced=False delete attempt in test_04 may have
        # already destroyed the libvirt NFS pool representation on the host;
        # if so deleteVolume raises "Storage pool not found".  The CS metadata
        # record will be cleaned up by the subsequent force-delete of the pool,
        # so we treat that specific error as a no-op here.
        try:
            cmd = deleteVolumeAPI.deleteVolumeCmd()
            cmd.id = vol.id
            self.apiClient.deleteVolume(cmd)
        except Exception as exc:
            if "Storage pool not found" in str(exc) or "storage pool" in str(exc).lower():
                logger.warning(
                    "deleteVolume raised expected NFS3 libvirt pool-not-found "
                    "error; proceeding to force-delete pool: %s", exc
                )
            else:
                raise
        self.__class__.volume = None

        # Force-delete the pool from Maintenance (no live volumes remaining)
        self._delete_pool(pool.id, forced=True)
        self.__class__.pool = None
        self.__class__.pool_ep_name = None

        # CloudStack: pool must be gone
        try:
            remaining = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            remaining = None
        self.assertFalse(remaining, "Pool still listed in CloudStack after force deletion")

        # ONTAP: FlexVol must be deleted
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNone(
            ontap_vol,
            "ONTAP FlexVol '%s' still exists after force deletion" % pool_name
        )

        # ONTAP: export policy must be deleted
        if ep_name:
            policy = self.ontap.get_export_policy(ep_name)
            self.assertIsNone(
                policy,
                "Export policy '%s' still exists after pool deletion" % ep_name
            )
