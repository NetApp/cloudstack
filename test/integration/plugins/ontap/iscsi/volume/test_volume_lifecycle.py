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
Sequential workflow integration tests for NetApp ONTAP iSCSI data volume
lifecycle (LUN create / delete / negative-delete / force-delete).

Tests are numbered test_01 ... test_05 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create iSCSI primary storage pool (infrastructure) and allocate a
      CloudStack data volume — LUN is created inside the pool's ONTAP FlexVol
  02  Delete the volume — LUN is removed from the FlexVol
  03  Recreate volume — LUN is present again (setup for negative delete tests)
  04  Put pool in Maintenance; attempt forced=False deleteStoragePool — must be
      rejected because volumes exist; pool stays in Maintenance
  05  Delete volume from Maintenance; forced=True deleteStoragePool — FlexVol,
      igroups, and all LUNs are removed from ONTAP

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has iSCSI configured (storageUrl starts with iqn.)
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/volume/ -v

Note: Tests share class-level state (sequential).  Always run the full suite.
The pool is cleaned up in test_05 on the happy path; OntapTestBase tearDownClass
provides a safety net for mid-run failures.
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

from ontap_test_base import OntapRestClient, OntapTestBase

logger = logging.getLogger("TestOntapISCSIVolumeLifecycle")


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
                "email": "ontap-iscsi-vol@test.com",
                "firstname": "ONTAP",
                "lastname": "iSCSI-Vol",
                "username": "ontap_iscsi_vol_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapISCSIVol_%d" % random.randint(0, 9999),
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

class TestOntapISCSIVolumeLifecycle(OntapTestBase):

    _vol_name_prefix = "OntapISCSIVol"

    @classmethod
    def setUpClass(cls):
        testclient = super(
            TestOntapISCSIVolumeLifecycle, cls
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

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapISCSIVol_%d" % random.randint(0, 99999)

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

    # ------------------------------------------------------------------
    # Step 01 - Create pool (infrastructure) and allocate a volume
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_volume"], required_hardware=True)
    def test_01_create_pool_and_volume(self):
        """
        Create a new iSCSI pool and allocate a CloudStack data volume on it.
        Verifies:
          - pool.state is Up
          - createVolume returns a non-None volume object
          - ONTAP: at least one LUN exists in the pool's FlexVol
        """
        pool = self._create_pool()
        self.__class__.pool = pool

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
        )

        vol = self._create_volume(pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        # ONTAP: at least one LUN must be present in the pool FlexVol
        luns = self.ontap.list_luns_in_volume(self.svm_name, pool.name)
        self.assertTrue(
            len(luns) > 0,
            "No LUNs found in ONTAP FlexVol '%s' after volume creation" % pool.name
        )

    # ------------------------------------------------------------------
    # Step 02 - Delete volume; LUN must be removed from ONTAP
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_volume"], required_hardware=True)
    def test_02_delete_volume(self):
        """
        Delete the volume created in test_01.
        Verifies:
          - deleteVolume completes without error
          - ONTAP: LUN is removed from the pool's FlexVol
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_01 must pass first")

        pool = self.__class__.pool
        vol = self.__class__.volume

        cmd = deleteVolumeAPI.deleteVolumeCmd()
        cmd.id = vol.id
        self.apiClient.deleteVolume(cmd)
        self.__class__.volume = None

        # ONTAP: LUN must be gone from the FlexVol
        luns = self.ontap.list_luns_in_volume(self.svm_name, pool.name)
        self.assertEqual(
            len(luns), 0,
            "Expected 0 LUNs in FlexVol '%s' after volume deletion, found %d: %s"
            % (pool.name, len(luns), luns)
        )

    # ------------------------------------------------------------------
    # Step 03 - Recreate volume for negative delete tests
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_volume"], required_hardware=True)
    def test_03_recreate_volume_for_delete_tests(self):
        """
        Recreate a volume on the existing pool (setup for tests 04-05).
        Verifies:
          - volume created successfully
          - ONTAP: LUN present in pool FlexVol
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        vol = self._create_volume(self.__class__.pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        luns = self.ontap.list_luns_in_volume(self.svm_name, self.__class__.pool.name)
        self.assertTrue(
            len(luns) > 0,
            "No LUNs found in ONTAP FlexVol '%s' after volume re-creation"
            % self.__class__.pool.name
        )

    # ------------------------------------------------------------------
    # Step 04 - Forced=False delete with live volume must fail
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_volume"], required_hardware=True)
    def test_04_forced_false_delete_with_volume_fails(self):
        """
        Put pool in Maintenance then attempt deleteStoragePool(forced=False).
        With a live volume present CloudStack must reject the request.
        Verifies:
          - CloudstackAPIException is raised
          - Pool is still listed in CloudStack (in Maintenance state)
          - ONTAP: FlexVol still exists and is online
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_03 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name

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

    # ------------------------------------------------------------------
    # Step 05 - Delete volume then force-delete pool from Maintenance
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_volume"], required_hardware=True)
    def test_05_delete_volume_and_force_delete_pool(self):
        """
        Delete the live volume then force-delete the pool while it is still
        in Maintenance state (pool is in Maintenance from test_04).
        Verifies:
          - Volume can be deleted while pool is in Maintenance
          - Pool is removed from CloudStack using forced=True from Maintenance
          - ONTAP: FlexVol deleted
          - ONTAP: igroups deleted for all cluster hosts
        """
        self.assertIsNotNone(
            self.__class__.pool,
            "Pool absent - test_04 must not have cleaned up the pool"
        )
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_03 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name
        vol = self.__class__.volume

        # Delete the volume first (pool is in Maintenance — volume deletion is allowed)
        cmd = deleteVolumeAPI.deleteVolumeCmd()
        cmd.id = vol.id
        self.apiClient.deleteVolume(cmd)
        self.__class__.volume = None

        # Force-delete the pool from Maintenance (no live volumes remaining)
        self._delete_pool(pool.id, forced=True)
        self.__class__.pool = None

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

        # ONTAP: igroups for each cluster host must be deleted
        for host in self.cluster_hosts:
            iqn = getattr(host, "storageurl", None) or getattr(host, "StorageUrl", None)
            if not iqn or not iqn.startswith("iqn."):
                continue
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            self.assertIsNone(
                igroup,
                "ONTAP igroup '%s' still exists after force deletion" % igroup_name
            )
