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

Test order — 03-06 are a sequential workflow that must run in order; 01, 02,
07 and 08 are isolated negative/recovery cases, each owning the pool it
creates, so they can be run on their own:
  01  Create rejected when a FlexVol of the same name already exists
  02  Create rejected when no assigned online aggregate has enough free space
  03  Create zone-scoped NFS3 pool — pool.state Up; ONTAP FlexVol online;
      export policy has all cluster host IPs
  04  Disable zone-scoped pool — pool.state Disabled; FlexVol unchanged
  05  Enable zone-scoped pool — pool.state Up; FlexVol unchanged
  06  Delete zone-scoped pool — pool gone; FlexVol deleted; export policy deleted
  07  Delete an empty pool whose FlexVol was removed behind CloudStack's back
  08  Delete an empty pool whose NFS export policy was removed beforehand

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM hosts registered in the zone
  - ONTAP SVM with NFS3 service enabled and at least one NFS data LIF
  - ontap.cfg populated with real values (protocol=NFS3)

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
    test/integration/plugins/ontap/nfs3/pool/test_zone_scoped_pool.py -v

Note: Tests 03-06 share class-level state (sequential).  Running a single test
with -m "test_NN" will invoke setUpClass but the guard assertion will fail
immediately if earlier steps have not yet run.  Always run the full suite.
"""

import base64
import logging
import random
import time
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    createStoragePool as createStoragePoolAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.cloudstackException import CloudstackAPIException
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import (
    OntapRestClient,
    OntapTestBase,
    _parse_pool_details,
    get_datacenter_config,
    log_progress,
)

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

    ONE_GIB = 1024 ** 3
    # Above this much free aggregate space, asking for "max free + 1 GiB"
    # stops being a meaningful request, so the no-space test skips instead.
    MAX_AGGREGATE_FREE_FOR_NO_SPACE_TEST = 300 * 1024 ** 4

    @classmethod
    def setUpClass(cls):
        super(TestOntapZoneScopedPool, cls).setUpClass()
        testclient = super(
            TestOntapZoneScopedPool, cls
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

    def _create_zone_pool(self, name=None, capacitybytes=None):
        """Create a zone-scoped NFS3 pool (no clusterid / podid).

        ``name`` and ``capacitybytes`` let the isolated negative tests drive
        the pool name (to collide with a pre-created FlexVol) and the
        requested size (to exceed every aggregate) without touching the
        shared test data.
        """
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = name or "OntapZoneNFS3_%d" % random.randint(0, 99999)

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = pool_name
        cmd.url = "nfs://%s/ontap" % storage_ip
        cmd.zoneid = self.zone.id
        # Intentionally omit clusterid and podid — zone-scoped pool
        cmd.scope = "ZONE"
        cmd.provider = ps[TestData.provider]
        cmd.tags = ps[TestData.tags]
        cmd.capacitybytes = capacitybytes or ps["capacitybytes"]
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

    # ---- helpers for the isolated tests (01, 02, 09, 10) ---------------

    def _require_ontap_client(self, *method_names):
        """Skip when the shared OntapRestClient lacks a backend helper."""
        missing = [n for n in method_names if not hasattr(self.ontap, n)]
        if missing:
            raise unittest.SkipTest(
                "OntapRestClient does not provide %s; update "
                "ontap_test_base.py before running this test"
                % ", ".join(missing)
            )

    def _create_isolated_zone_pool(self, name):
        """Create a throwaway zone pool and register it for class teardown."""
        pool = self._create_zone_pool(name=name)
        self.__class__.pool2 = pool
        self.assertEqual(
            pool.state, "Up",
            "Throwaway pool '%s' should be 'Up', got '%s'"
            % (name, pool.state)
        )
        return pool

    def _wait_for_pool_state_quietly(self, pool_id, target_state,
                                     timeout=120, interval=5):
        """Poll for a pool state, returning False instead of failing the test.

        Used on the recovery paths where the backend has deliberately been
        broken, so entering Maintenance is allowed to fail.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            pools = list_storage_pools(self.apiClient, id=pool_id)
            if not pools or pools[0].state == target_state:
                return True
            time.sleep(interval)
        return False

    def _enter_maintenance_quietly(self, pool_id):
        """Request Maintenance without failing when the backend is broken."""
        try:
            maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
            maint_cmd.id = pool_id
            self.apiClient.enableStorageMaintenance(maint_cmd)
        except Exception as exc:
            logger.warning(
                "enableStorageMaintenance failed for pool %s: %s"
                % (pool_id, exc)
            )
            return False
        return self._wait_for_pool_state_quietly(pool_id, "Maintenance")

    def _cs_pool_exists(self, pool_id):
        try:
            return bool(list_storage_pools(self.apiClient, id=pool_id))
        except Exception:
            return False

    def _assert_no_cs_pool_named(self, pool_name):
        """Assert CloudStack holds no storage pool with the given name."""
        try:
            listed = list_storage_pools(self.apiClient, name=pool_name)
        except Exception:
            listed = None
        self.assertFalse(
            listed,
            "CloudStack should not have created pool '%s' after the "
            "rejected request" % pool_name
        )

    def _force_cleanup_zone_pool(self, pool):
        """Best-effort removal of a throwaway pool left behind by a failure.

        Unmounts on the KVM hosts first so a stale NFS mount can never
        outlive the ONTAP export and trip KVMHAMonitor.
        """
        if pool is None:
            return True
        if not self._cs_pool_exists(pool.id):
            self.__class__.pool2 = None
            return True
        try:
            self._enter_maintenance_quietly(pool.id)
            self._cleanup_kvm_storage_pool_mounts(pool.id)
            self._delete_pool(pool.id, forced=True)
        except Exception as exc:
            logger.warning(
                "could not force-delete throwaway pool %s: %s"
                % (pool.id, exc)
            )
            return False
        if self._cs_pool_exists(pool.id):
            logger.warning(
                "throwaway pool %s still exists after force-delete", pool.id
            )
            return False
        self.__class__.pool2 = None
        return True

    def _force_delete_flexvol(self, vol_name):
        """Best-effort ONTAP FlexVol removal for a throwaway volume."""
        try:
            self.ontap.offline_and_delete_volume(vol_name)
        except Exception as exc:
            logger.warning(
                "could not delete ONTAP FlexVol '%s': %s" % (vol_name, exc)
            )

    def _force_delete_export_policy(self, ep_name):
        """Best-effort ONTAP export policy removal for a throwaway pool."""
        if not ep_name:
            return
        try:
            self.ontap.delete_export_policy(ep_name)
        except Exception as exc:
            logger.warning(
                "could not delete export policy '%s': %s" % (ep_name, exc)
            )

    # ------------------------------------------------------------------
    # Step 01 — Create rejected when a FlexVol of the same name exists
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_01_create_zone_pool_rejected_when_flexvol_exists(self):
        """
        Pre-create a FlexVol on the SVM, then ask CloudStack for a
        zone-scoped pool with that exact name.
        Verifies:
          - createStoragePool raises CloudstackAPIException
          - CloudStack records no pool with that name
          - ONTAP: the pre-existing FlexVol is left in place
        This test owns everything it creates and leaves no pool behind.
        """
        self._require_ontap_client("create_flexvol", "offline_and_delete_volume")

        pool_name = "OntapZoneNFS3Dup_%d" % random.randint(0, 99999)
        size_bytes = self.testdata[TestData.primaryStorage]["capacitybytes"]
        created_pool = None
        try:
            self.ontap.create_flexvol(self.svm_name, pool_name, size_bytes)
            self.assertIsNotNone(
                self.ontap.get_volume(pool_name),
                "Pre-created ONTAP FlexVol '%s' not visible; cannot test the "
                "name collision" % pool_name
            )

            try:
                created_pool = self._create_zone_pool(name=pool_name)
            except CloudstackAPIException as exc:
                log_progress(
                    logger, "info",
                    "createStoragePool rejected for existing FlexVol '%s': %s",
                    pool_name, exc,
                )
            else:
                self.fail(
                    "createStoragePool should have been rejected: ONTAP "
                    "FlexVol '%s' already exists" % pool_name
                )

            self._assert_no_cs_pool_named(pool_name)
            self.assertIsNotNone(
                self.ontap.get_volume(pool_name),
                "Pre-existing ONTAP FlexVol '%s' was removed by the rejected "
                "create" % pool_name
            )
        finally:
            if self._force_cleanup_zone_pool(created_pool):
                self._force_delete_flexvol(pool_name)
                self._force_delete_export_policy(
                    "cs-%s-%s" % (self.svm_name, pool_name)
                )

    # ------------------------------------------------------------------
    # Step 02 — Create rejected when no aggregate has enough free space
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_02_create_zone_pool_rejected_when_no_aggregate_space(self):
        """
        Ask for a pool 1 GiB larger than the free space of the roomiest
        assigned online aggregate.
        Verifies:
          - createStoragePool raises CloudstackAPIException reporting
            'No suitable aggregates'
          - CloudStack records no pool with that name
          - ONTAP: no FlexVol of that name was left behind
        Skipped when the SVM has more than 300 TiB free on one aggregate,
        where the oversized request stops being meaningful.
        """
        self._require_ontap_client(
            "max_online_aggregate_available_bytes", "offline_and_delete_volume"
        )

        max_free = self.ontap.max_online_aggregate_available_bytes(self.svm_name)
        if not max_free:
            raise unittest.SkipTest(
                "No assigned online aggregate with free space reported for "
                "SVM '%s'; cannot build an over-capacity request"
                % self.svm_name
            )
        requested = int(max_free) + self.ONE_GIB
        if requested > self.MAX_AGGREGATE_FREE_FOR_NO_SPACE_TEST:
            raise unittest.SkipTest(
                "Largest assigned online aggregate on SVM '%s' has %d B free; "
                "the over-capacity request would exceed the %d B FlexVol limit"
                % (self.svm_name, max_free,
                   self.MAX_AGGREGATE_FREE_FOR_NO_SPACE_TEST)
            )

        pool_name = "OntapZoneNFS3NoSpace_%d" % random.randint(0, 99999)
        created_pool = None
        try:
            try:
                created_pool = self._create_zone_pool(
                    name=pool_name, capacitybytes=requested
                )
            except CloudstackAPIException as exc:
                error_text = str(exc)
                log_progress(
                    logger, "info",
                    "createStoragePool rejected for %d B (max aggregate free "
                    "%d B): %s", requested, max_free, error_text,
                )
                self.assertIn(
                    "No suitable aggregates", error_text,
                    "Expected the rejection to report 'No suitable "
                    "aggregates', got: %s" % error_text
                )
            else:
                self.fail(
                    "createStoragePool should have been rejected: requested "
                    "%d B but the roomiest aggregate has only %d B free"
                    % (requested, max_free)
                )

            self._assert_no_cs_pool_named(pool_name)
            self.assertIsNone(
                self.ontap.get_volume(pool_name),
                "ONTAP FlexVol '%s' was left behind by the rejected create"
                % pool_name
            )
        finally:
            if self._force_cleanup_zone_pool(created_pool):
                self._force_delete_flexvol(pool_name)

    # ------------------------------------------------------------------
    # Step 03 — Create zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_03_create_zone_scoped_pool(self):
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
    # Step 04 — Disable zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_04_disable_zone_scoped_pool(self):
        """
        Disable the zone-scoped pool.
        Verifies:
          - pool.state is Disabled
          - ONTAP: FlexVol still online; export policy unchanged
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

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
    # Step 05 — Enable zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_05_enable_zone_scoped_pool(self):
        """
        Re-enable the zone-scoped pool.
        Verifies:
          - pool.state is Up
          - ONTAP: FlexVol still online; export policy unchanged
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

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
    # Step 06 — Delete zone-scoped pool
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_06_delete_zone_scoped_pool(self):
        """
        Enter maintenance then delete the zone-scoped pool.
        Verifies:
          - Pool is removed from CloudStack
          - ONTAP: FlexVol deleted
          - ONTAP: export policy deleted
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

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
    # Step 07 — Delete a pool whose FlexVol was pre-deleted
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_07_delete_zone_pool_with_flexvol_predeleted(self):
        """
        Create an empty zone pool, remove its FlexVol directly on ONTAP, then
        delete the pool through CloudStack.
        Verifies:
          - deleteStoragePool succeeds and the pool leaves CloudStack
          - ONTAP: the FlexVol stays gone
        The KVM hosts are unmounted before the FlexVol is removed so the pool
        never becomes a stale NFS mount.
        """
        self._require_ontap_client("offline_and_delete_volume")

        pool_name = "OntapZoneNFS3NoFv_%d" % random.randint(0, 99999)
        pool = self._create_isolated_zone_pool(pool_name)
        ep_name = self._get_export_policy_name(pool)
        try:
            self.assertIsNotNone(
                self.ontap.get_volume(pool_name),
                "ONTAP FlexVol '%s' missing right after pool creation"
                % pool_name
            )

            self.assertTrue(
                self._enter_maintenance_quietly(pool.id),
                "Pool '%s' did not enter Maintenance before backend mutation"
                % pool_name
            )

            # Unmount while the export is still reachable, then delete the
            # FlexVol behind CloudStack's back.
            self._cleanup_kvm_storage_pool_mounts(pool.id)
            self.ontap.offline_and_delete_volume(pool_name)
            self.assertIsNone(
                self.ontap.get_volume(pool_name),
                "ONTAP FlexVol '%s' still present after the manual delete"
                % pool_name
            )

            self._delete_pool(pool.id, forced=True)
            self.__class__.pool2 = None

            self.assertFalse(
                self._cs_pool_exists(pool.id),
                "Pool '%s' still listed in CloudStack after deletion with a "
                "pre-deleted FlexVol" % pool_name
            )
            self.assertIsNone(
                self.ontap.get_volume(pool_name),
                "ONTAP FlexVol '%s' reappeared after pool deletion" % pool_name
            )
        finally:
            if self._force_cleanup_zone_pool(pool):
                self._force_delete_flexvol(pool_name)
                self._force_delete_export_policy(ep_name)

    # ------------------------------------------------------------------
    # Step 08 — Delete a pool whose export policy was pre-deleted
    # ------------------------------------------------------------------

    @attr(tags=["zone_pool"], required_hardware=True)
    def test_08_delete_zone_pool_with_export_policy_predeleted(self):
        """
        Create an empty zone pool, remove its NFS export policy directly on
        ONTAP, then delete the pool through CloudStack.
        Verifies:
          - deleteStoragePool succeeds and the pool leaves CloudStack
          - ONTAP: the FlexVol is deleted and the export policy stays gone
        The KVM hosts are unmounted before the export policy is removed so
        the pool never becomes a stale NFS mount.
        """
        self._require_ontap_client("offline_and_delete_volume")

        pool_name = "OntapZoneNFS3NoEp_%d" % random.randint(0, 99999)
        pool = self._create_isolated_zone_pool(pool_name)
        ep_name = self._get_export_policy_name(pool)
        try:
            self._assert_export_policy_has_host_ips(ep_name)
            self.assertTrue(
                self._enter_maintenance_quietly(pool.id),
                "Pool '%s' did not enter Maintenance before backend mutation"
                % pool_name
            )

            # Unmount before pulling the export policy out from under the
            # hosts, otherwise the mount goes stale and KVMHAMonitor reboots.
            self._cleanup_kvm_storage_pool_mounts(pool.id)
            self.ontap.reassign_volume_export_policy(pool.name)
            self.ontap.delete_export_policy(ep_name)
            self.assertIsNone(
                self.ontap.get_export_policy(ep_name),
                "Export policy '%s' still present after the manual delete"
                % ep_name
            )

            self._delete_pool(pool.id, forced=True)
            self.__class__.pool2 = None

            self.assertFalse(
                self._cs_pool_exists(pool.id),
                "Pool '%s' still listed in CloudStack after deletion with a "
                "pre-deleted export policy" % pool_name
            )
            self.assertIsNone(
                self.ontap.get_volume(pool_name),
                "ONTAP FlexVol '%s' still exists after pool deletion"
                % pool_name
            )
            self.assertIsNone(
                self.ontap.get_export_policy(ep_name),
                "Export policy '%s' reappeared after pool deletion" % ep_name
            )
        finally:
            if self._force_cleanup_zone_pool(pool):
                self._force_delete_flexvol(pool_name)
                self._force_delete_export_policy(ep_name)

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
