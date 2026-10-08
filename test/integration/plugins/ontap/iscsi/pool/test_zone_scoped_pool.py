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
Zone-scoped primary storage lifecycle tests for NetApp ONTAP (iSCSI).

Creates a zone-scoped pool (scope=ZONE, no clusterid/podid). Host igroups are
shared by host and SVM and are created only when a LUN is granted to a host,
not when an empty pool is created.

Test order — sequential workflow that must run in order:
  01  Create zone-scoped iSCSI pool — pool.state Up; ONTAP FlexVol online;
      pre-existing shared igroups unchanged
  02  Disable zone-scoped pool — pool.state Disabled; FlexVol unchanged
  03  Enable zone-scoped pool — pool.state Up; FlexVol unchanged
  04  Delete zone-scoped pool — pool gone; FlexVol deleted; baseline restored

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM hosts with iSCSI registered in the zone
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/pool/test_zone_scoped_pool.py -v

Note: Tests share class-level state (sequential).  Always run the full suite.
"""

import base64
import logging
import random
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    createStoragePool as createStoragePoolAPI,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import (
    OntapRestClient,
    OntapTestBase,
    get_datacenter_config,
)

logger = logging.getLogger("TestOntapISCSIZoneScopedPool")


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
                 provider="NetApp ONTAP", tags="ontap-iscsi", capacitybytes=None):
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
                "email": "ontap-iscsi-zone@test.com",
                "firstname": "ONTAP",
                "lastname": "iSCSI-Zone",
                "username": "ontap_iscsi_zone_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapZoneISCSI_%d" % random.randint(0, 9999),
                TestData.scope: "ZONE",
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
# Sequential workflow test class
# ---------------------------------------------------------------------------

class TestOntapISCSIZoneScopedPool(OntapTestBase):

    _vol_name_prefix = "OntapISCSIZoneVol"

    @classmethod
    def setUpClass(cls):
        super(TestOntapISCSIZoneScopedPool, cls).setUpClass()
        testclient = super(
            TestOntapISCSIZoneScopedPool, cls
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
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")
        tags = iscsi_cfg.get("storagePoolTags", "ontap-iscsi")
        capacitybytes = pool_cfg.get("capacitybytes", None)

        cls.testdata = TestData(
            storage_ip, svm_name, username, password,
            provider=provider, tags=tags, capacitybytes=capacitybytes,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name

        cls._setup_cloudstack_resources(config, cls.testdata[TestData.account])
        cls._capture_igroup_baseline()

    # No per-test tearDown — state intentionally persists between steps.

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_zone_pool(self):
        """Create a zone-scoped iSCSI pool (no clusterid / podid)."""
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapZoneISCSI_%d" % random.randint(0, 99999)

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = pool_name
        cmd.url = "iscsi://%s/ontap" % storage_ip
        cmd.zoneid = self.zone.id
        # Intentionally omit clusterid and podid — zone-scoped pool
        cmd.scope = "ZONE"
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
    # Step 01 — Create zone-scoped iSCSI pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_zone_pool"], required_hardware=True)
    def test_01_create_zone_scoped_pool(self):
        """
        Create a zone-scoped iSCSI primary storage pool (no clusterid/podid).
        Verifies:
          - pool.state is Up, type is OntapiSCSI
          - ONTAP: FlexVol is online
          - ONTAP: pre-existing shared igroups are unchanged
        """
        pool = self._create_zone_pool()
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
        # granted access to a LUN, so creating an empty pool must not make
        # new ones.
        self._assert_igroup_baseline_unchanged(
            "after creating an empty zone-scoped pool"
        )

    # ------------------------------------------------------------------
    # Step 02 — Disable zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_zone_pool"], required_hardware=True)
    def test_02_disable_zone_scoped_pool(self):
        """
        Disable the zone-scoped iSCSI pool.
        Verifies:
          - pool.state is Disabled
          - ONTAP: FlexVol still online; igroups unchanged
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

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
            "ONTAP FlexVol should still be 'online' after disable"
        )

        # The pool has no volumes, so shared igroups must remain unchanged.
        self._assert_igroup_baseline_unchanged(
            "after disabling an empty zone-scoped pool"
        )

    # ------------------------------------------------------------------
    # Step 03 — Enable zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_zone_pool"], required_hardware=True)
    def test_03_enable_zone_scoped_pool(self):
        """
        Re-enable the zone-scoped iSCSI pool.
        Verifies:
          - pool.state is Up
          - ONTAP: FlexVol still online; igroups unchanged
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

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
            "ONTAP FlexVol should be 'online' after enable"
        )

        # The pool has no volumes, so shared igroups must remain unchanged.
        self._assert_igroup_baseline_unchanged(
            "after re-enabling an empty zone-scoped pool"
        )

    # ------------------------------------------------------------------
    # Step 04 — Delete zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_zone_pool"], required_hardware=True)
    def test_04_delete_zone_scoped_pool(self):
        """
        Enter maintenance then delete the zone-scoped iSCSI pool.
        Verifies:
          - Pool is removed from CloudStack
          - ONTAP: FlexVol deleted
          - ONTAP: this pool's maps are gone and shared igroups are restored
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name

        self._enter_maintenance(pool.id)

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

        self._assert_no_lun_maps_for_volume(
            pool_name, "after zone-scoped pool deletion"
        )
        self._assert_igroup_baseline_unchanged(
            "after zone-scoped pool deletion"
        )
