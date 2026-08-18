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

Creates a zone-scoped pool (scope=ZONE, no clusterid/podid).  CloudStack calls
OntapPrimaryDatastoreLifecycle.attachZone(), which connects all eligible KVM
hosts in the zone and creates igroups for each host's IQN.

Workflow:
  01  Create zone-scoped iSCSI pool — pool.state Up; ONTAP FlexVol online;
                                      igroup present for each cluster host IQN
  02  Disable zone-scoped pool — pool.state Disabled; FlexVol unchanged
  03  Enable zone-scoped pool — pool.state Up; FlexVol unchanged
  04  Delete zone-scoped pool — pool gone; FlexVol deleted; igroups deleted

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM hosts with iSCSI registered in the zone
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/pool/test_zone_scoped_pool.py -v

Note: Tests 01-04 share class-level state (sequential).  Always run the full
suite.
"""

import base64
import logging
import random
import re
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    createStoragePool as createStoragePoolAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config

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

    def _assert_igroups_for_hosts(self, expect_present):
        """Assert igroups are present (or absent) for each cluster host IQN."""
        for host in self.cluster_hosts:
            iqn = (getattr(host, "storageurl", None)
                   or getattr(host, "StorageUrl", None))
            if not iqn or not iqn.startswith("iqn."):
                continue
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            if expect_present:
                self.assertIsNotNone(
                    igroup,
                    "ONTAP igroup '%s' not found for host '%s' after pool creation"
                    % (igroup_name, host.name)
                )
                initiator_names = [
                    i.get("name", "") for i in igroup.get("initiators", [])
                ]
                self.assertIn(
                    iqn, initiator_names,
                    "Host IQN '%s' not in igroup '%s' initiators: %s"
                    % (iqn, igroup_name, initiator_names)
                )
            else:
                self.assertIsNone(
                    igroup,
                    "ONTAP igroup '%s' still exists after pool deletion" % igroup_name
                )

    # ------------------------------------------------------------------
    # Step 01 — Create zone-scoped iSCSI pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_zone_pool"], required_hardware=True)
    def test_01_create_zone_scoped_pool(self):
        """
        Create a zone-scoped iSCSI primary storage pool (no clusterid/podid).
        CloudStack calls attachZone(), which connects all eligible KVM hosts
        in the zone and creates igroups for each host's IQN.
        Verifies:
          - pool.state is Up, type is OntapSAN
          - ONTAP: FlexVol is online
          - ONTAP: igroup exists for each cluster host with the correct IQN
        """
        pool = self._create_zone_pool()
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

        # ONTAP: igroups must exist for each cluster host with IQN
        self._assert_igroups_for_hosts(expect_present=True)

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

        # igroups must still be present after a simple disable
        self._assert_igroups_for_hosts(expect_present=True)

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

        # igroups must still be present after re-enable
        self._assert_igroups_for_hosts(expect_present=True)

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
          - ONTAP: igroups deleted for all cluster hosts
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name

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
        self._assert_igroups_for_hosts(expect_present=False)
