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
Zone-scoped primary storage lifecycle tests for NetApp ONTAP (NFS3).

Creates a zone-scoped pool (scope=ZONE, no clusterid/podid).  CloudStack calls
OntapPrimaryDatastoreLifecycle.attachZone(), which connects all eligible KVM
hosts in the zone to the pool and creates an NFS export policy covering their
IPs.

Workflow:
  01  Create zone-scoped NFS3 pool — pool.state Up; ONTAP FlexVol online;
                                     export policy has all cluster host IPs
  02  Disable zone-scoped pool — pool.state Disabled; FlexVol unchanged
  03  Enable zone-scoped pool — pool.state Up; FlexVol unchanged
  04  Delete zone-scoped pool — pool gone; FlexVol deleted; export policy deleted

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM hosts registered in the zone
  - ONTAP SVM with NFS3 service enabled and at least one NFS data LIF
  - ontap.cfg populated with real values (protocol=NFS3)

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
    test/integration/plugins/ontap/nfs3/pool/test_zone_scoped_pool.py -v

Note: Tests 01-04 share class-level state (sequential).  Running a single test
with -m "test_NN" will invoke setUpClass but the guard assertion will fail
immediately if earlier steps have not yet run.  Always run the full suite.
"""

import base64
import logging
import random
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    createStoragePool as createStoragePoolAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, _parse_pool_details

logger = logging.getLogger("TestOntapZoneScopedPool")


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
                 protocol="NFS3", provider="NetApp ONTAP",
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
                "email": "ontap-zone@test.com",
                "firstname": "ONTAP",
                "lastname": "Zone",
                "username": "ontap_zone_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapZoneNFS3_%d" % random.randint(0, 9999),
                TestData.scope: "ZONE",
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

class TestOntapZoneScopedPool(OntapTestBase):

    # ---- zone-pool-specific shared state --------------------------------
    pool_ep_name = None
    cluster_host_ips = None

    _vol_name_prefix = "OntapZoneVol"

    @classmethod
    def setUpClass(cls):
        testclient = super(
            TestOntapZoneScopedPool, cls
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
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")
        tags = nfs3_cfg.get("storagePoolTags", "ontap-nfs3")
        capacitybytes = pool_cfg.get("capacitybytes", None)

        cls.testdata = TestData(
            storage_ip, svm_name, username, password,
            protocol=protocol, provider=provider,
            tags=tags, capacitybytes=capacitybytes,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name

        cls._setup_cloudstack_resources(config, cls.testdata[TestData.account])

        # Collect host IPs for export policy assertions
        cls.cluster_host_ips = [
            h.ipaddress for h in cls.cluster_hosts
            if getattr(h, "ipaddress", None)
        ]

    # No per-test tearDown — state intentionally persists between steps.

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_zone_pool(self):
        """Create a zone-scoped NFS3 pool (no clusterid / podid)."""
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapZoneNFS3_%d" % random.randint(0, 99999)

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = pool_name
        cmd.url = "nfs://%s/ontap" % storage_ip
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

    def _get_export_policy_name(self, pool):
        """Extract the export policy name from pool creation response details."""
        details = _parse_pool_details(pool)
        ep_name = details.get("exportPolicyName")
        if not ep_name:
            ep_name = "cs-%s-%s" % (self.svm_name, pool.name)
        return ep_name

    def _assert_export_policy_has_host_ips(self, ep_name):
        """Assert export policy exists and contains each cluster host IP."""
        policy = self.ontap.get_export_policy(ep_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' not found on ONTAP" % ep_name
        )
        if not self.cluster_host_ips:
            return
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

    # ------------------------------------------------------------------
    # Step 01 — Create zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_01_create_zone_scoped_pool(self):
        """
        Create a zone-scoped NFS3 primary storage pool (no clusterid/podid).
        CloudStack calls attachZone(), which connects all eligible KVM hosts
        in the zone and creates an NFS export policy.
        Verifies:
          - pool.state is Up
          - ONTAP: FlexVol is online
          - ONTAP: export policy exists and contains cluster host IPs
          - ONTAP: at least one NFS data LIF is present on the SVM
        """
        pool = self._create_zone_pool()
        self.__class__.pool = pool

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
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

        # ONTAP: export policy must exist with cluster host IPs
        ep_name = self._get_export_policy_name(pool)
        self.__class__.pool_ep_name = ep_name
        self._assert_export_policy_has_host_ips(ep_name)

        # ONTAP: at least one NFS data LIF must be present
        lifs = self.ontap.get_data_lifs(self.svm_name)
        self.assertTrue(
            len(lifs) > 0,
            "No NFS data LIFs found on SVM '%s'" % self.svm_name
        )

    # ------------------------------------------------------------------
    # Step 02 — Disable zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_02_disable_zone_scoped_pool(self):
        """
        Disable the zone-scoped pool.
        Verifies:
          - pool.state is Disabled
          - ONTAP: FlexVol still online; export policy unchanged
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

        if self.__class__.pool_ep_name:
            policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after disable"
                % self.__class__.pool_ep_name
            )

    # ------------------------------------------------------------------
    # Step 03 — Enable zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_03_enable_zone_scoped_pool(self):
        """
        Re-enable the zone-scoped pool.
        Verifies:
          - pool.state is Up
          - ONTAP: FlexVol still online; export policy unchanged
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

        if self.__class__.pool_ep_name:
            policy = self.ontap.get_export_policy(self.__class__.pool_ep_name)
            self.assertIsNotNone(
                policy,
                "Export policy '%s' should still exist after enable"
                % self.__class__.pool_ep_name
            )

    # ------------------------------------------------------------------
    # Step 04 — Delete zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_04_delete_zone_scoped_pool(self):
        """
        Enter maintenance then delete the zone-scoped pool.
        Verifies:
          - Pool is removed from CloudStack
          - ONTAP: FlexVol deleted
          - ONTAP: export policy deleted
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        pool = self.__class__.pool
        pool_name = pool.name
        ep_name = self.__class__.pool_ep_name

        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)

        # Unmount the NFS on each KVM host BEFORE deleteStoragePool removes
        # the ONTAP export.  Without this, the mount becomes stale and
        # KVMHAMonitor will fail its heartbeat 5 times then reboot the host
        # via `echo b > /proc/sysrq-trigger`.
        self._cleanup_kvm_storage_pool_mounts(pool.id)

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

    # ------------------------------------------------------------------
    # Class-level teardown
    # ------------------------------------------------------------------

    @classmethod
    def tearDownClass(cls):
        """
        Clean up any lingering zone-scoped pool NFS mounts on KVM hosts
        before the base-class teardown deletes the ONTAP FlexVol.  Without
        this, a failed test_04 leaves a stale NFS mount that will cause
        KVMHAMonitor to reboot the host.
        """
        for pool in [p for p in (cls.pool2, cls.pool) if p is not None]:
            try:
                cls._cleanup_kvm_storage_pool_mounts(pool.id)
            except Exception as e:
                logger.warning(
                    "tearDownClass: KVM NFS cleanup failed for pool %s: %s"
                    % (pool.id, e)
                )
        super(TestOntapZoneScopedPool, cls).tearDownClass()
