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
pool lifecycle.

Tests are numbered test_01 ... test_21 and must run in that order.  test_01
and test_02 are isolated create rejections.  test_04 reuses the FlexVol created
by test_03.  test_20 and test_21 are isolated delete cases.

Workflow:
  01  Reject create when no online assigned aggregate has enough free space
  02  Reject create when capacitybytes has invalid values
  03  Create primary storage pool
  04  Reject create when a FlexVol of that name already exists on ONTAP
  05  Increase storage pool capacity to the maximum supported size
  06  Reject a grow beyond the maximum supported size
  07  Safely shrink storage pool capacity
  08  Disable storage pool
  09  Grow and shrink the pool while it is disabled
  10  Enable storage pool
  11  Enter maintenance mode
  12  Grow and shrink the pool while it is in maintenance mode
  13  Cancel maintenance mode
  14  Enter maintenance mode and delete the storage pool
  15  Create a new pool and allocate a CloudStack data volume (LUN created)
  16  Deploy a VM and attach the ONTAP volume (LUN) to it
  17  Reject shrink below the ONTAP used capacity of the attached LUN
  18  Grow and shrink the pool while the VM holds the volume (LUN)
  19  Detach/destroy the VM, delete the volume (LUN removed), enter
      maintenance, force-delete pool
  20  Delete an empty pool whose FlexVol was deleted directly on ONTAP
  21  Delete an empty pool whose igroups were deleted on ONTAP

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has iSCSI configured (storageUrl starts with iqn.)
  - At least one ready user KVM template in the zone (test_16 skips without one)
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/pool/test_pool_lifecycle.py -v

Note: Tests 03-19 share class-level state (sequential).  test_04 reuses the
FlexVol created by test_03.  Running a single test with -m "test_NN" will
invoke setUpClass but the guard assertion will fail immediately if earlier
steps have not yet run.  Always run the full suite.
"""

import base64
import logging
import random
import re
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    attachVolume as attachVolumeAPI,
    createStoragePool as createStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    deployVirtualMachine as deployVirtualMachineAPI,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.cloudstackException import CloudstackAPIException
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import (
    OntapRestClient, OntapTestBase, _wait_for_vm_state, get_datacenter_config,
    log_progress, run_invalid_capacitybytes_rejections,
)

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

    # Maximum FlexVol size on AFF/FAS, raised from 100 TB in ONTAP 9.12.1P2.
    # ONTAP writes this as "300 TB" but means binary, hence 1024**4.  The
    # value has to be hardcoded: no REST field reports the platform ceiling.
    # Against an ONTAP older than 9.12.1P2 the real limit is 100 TB and the
    # grow step will fail.  CloudStack enforces no maximum of its own (the
    # plugin validates only a 20 MB floor), so it is ONTAP that refuses
    # anything above this.
    ONTAP_MAX_VOLUME_SIZE = 300 * 1024 ** 4
    ONTAP_GROW_TARGET_SIZE = ONTAP_MAX_VOLUME_SIZE

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
    resize_original_size = None

    # VM state (vm/template_id/network_id) lives in OntapTestBase.
    _vm_network_name_prefix = "ontap-iscsi-lifecycle-net"

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

        cls._discover_vm_deploy_resources()

    # No per-test tearDown — state intentionally persists between steps.

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_pool(self, pool_name=None, capacitybytes=None):
        """Create a pool; name and capacity default to the suite values."""
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
                        self._enter_maintenance(pool.id)
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

    def _assert_only_original_pool(self, pool):
        """Assert the rejected create did not add a second pool of this name."""
        try:
            listed = list_storage_pools(self.apiClient, name=pool.name) or []
        except CloudstackAPIException:
            listed = []
        same_name = [
            item for item in listed if getattr(item, "name", None) == pool.name
        ]
        self.assertEqual(
            [item.id for item in same_name],
            [pool.id],
            "CloudStack should still list only pool '%s' after the rejected "
            "create, found: %s" % (pool.name, same_name),
        )
        current = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(
            current,
            "Original pool '%s' disappeared after the rejected create"
            % pool.name,
        )
        self.assertEqual(
            current[0].state, "Up",
            "Original pool '%s' should still be Up, got '%s'"
            % (pool.name, current[0].state),
        )

    def _warn_if_duplicate_pool(self, duplicate, pool):
        """Leave a stray duplicate in place.

        Deleting it would remove the FlexVol that the rest of the suite
        still uses.
        """
        if duplicate is None or getattr(duplicate, "id", None) == pool.id:
            return
        logger.warning(
            "Duplicate pool '%s' (id=%s) was created against the shared "
            "FlexVol; leaving it in place so cleanup does not delete the "
            "pool the rest of the suite uses",
            pool.name, duplicate.id,
        )

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

    # ------------------------------------------------------------------
    # Step 01 - reject create when no aggregate space
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_01_reject_create_when_no_aggregate_space(self):
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

    # ------------------------------------------------------------------
    # Step 02 - reject create with invalid capacitybytes values
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_02_reject_create_with_invalid_capacitybytes_values(self):
        """
        Negative case: reject pool creation when capacitybytes has invalid
        values.

        Verifies, for each invalid value:
          - createStoragePool raises CloudstackAPIException
          - zero and negative report that capacity must be at least the
            ONTAP minimum
          - special characters and a decimal are rejected as an invalid
            capacitybytes value
          - no pool is left in CloudStack and no FlexVol on ONTAP
        """
        def cleanup(pool, pool_name):
            self._cleanup_throwaway_pool(pool, flexvol_name=pool_name)

        run_invalid_capacitybytes_rejections(
            self,
            lambda name, capacity: self._create_pool(
                pool_name=name, capacitybytes=capacity
            ),
            cleanup,
            logger,
        )

    # ------------------------------------------------------------------
    # Step 03 - create primary storage pool
    # ------------------------------------------------------------------

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
    # Step 04 - reject create when flexvol name exists
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_04_reject_create_when_flexvol_name_exists(self):
        """
        The pool from test_03 already has a FlexVol of that name on ONTAP.
        A second createStoragePool with the same name must be rejected, and
        the existing pool and FlexVol must be left untouched.

        Verifies:
          - createStoragePool raises CloudstackAPIException
          - CloudStack still lists only the pool from test_03
          - the existing ONTAP FlexVol is still online
        """
        pool = self.__class__.pool
        self.assertIsNotNone(pool, "Pool absent - test_03 must pass first")
        pool_name = pool.name
        self.assertIsNotNone(
            self.ontap.get_volume(pool_name),
            "ONTAP FlexVol '%s' from test_03 is missing" % pool_name,
        )
        log_progress(
            logger, "info",
            "Pool '%s' already owns a FlexVol; requesting another pool of "
            "the same name (expect reject)", pool_name,
        )
        duplicate = None
        try:
            with self.assertRaises(CloudstackAPIException) as caught:
                duplicate = self._create_pool(pool_name=pool_name)
            log_progress(
                logger, "info",
                "Rejected duplicate-name create for '%s': %s",
                pool_name, caught.exception,
            )
            self._assert_only_original_pool(pool)
            ontap_vol = self.ontap.get_volume(pool_name)
            self.assertIsNotNone(
                ontap_vol,
                "Existing ONTAP FlexVol '%s' was removed by the failed "
                "pool create" % pool_name,
            )
            self.assertEqual(
                ontap_vol.get("state"), "online",
                "Existing ONTAP FlexVol '%s' should still be online, got '%s'"
                % (pool_name, ontap_vol.get("state")),
            )
        finally:
            self._warn_if_duplicate_pool(duplicate, pool)

    # ------------------------------------------------------------------
    # Step 05 - grow storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_05_grow_storage_pool(self):
        """Grow the original pool to 300 TiB and verify CloudStack and ONTAP converge."""
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_03 must pass first"
        )
        pool = self.__class__.pool
        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before capacity increase")
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol missing before increase")

        original_size = max(
            int(getattr(listed[0], "capacitybytes", 0) or 0),
            int(ontap_vol.get("space", {}).get("size", 0) or 0),
            int(self.testdata[TestData.primaryStorage]["capacitybytes"]),
        )
        requested_size = TestData.ONTAP_GROW_TARGET_SIZE
        self.assertGreater(
            requested_size, original_size,
            "Grow target %d B must exceed the current pool size %d B"
            % (requested_size, original_size)
        )
        self.__class__.resize_original_size = original_size

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = pool.id
        cmd.capacitybytes = requested_size
        self.apiClient.updateStoragePool(cmd)

        resized_pool = self._poll_pool_capacity(
            pool.id, requested_size, timeout=120
        )
        self.assertEqual(resized_pool.state, "Up")
        resized_ontap_vol = self._poll_ontap_volume_size(
            pool.name, requested_size, timeout=120
        )
        self.assertEqual(resized_ontap_vol.get("state"), "online")

        for host in self.cluster_hosts:
            iqn = (
                getattr(host, "storageurl", None)
                or getattr(host, "StorageUrl", None)
            )
            if iqn and iqn.startswith("iqn."):
                igroup_name = _igroup_name(self.svm_name, host.name)
                self.assertIsNotNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' disappeared after increase" % igroup_name,
                )


    # ------------------------------------------------------------------
    # Step 06 - reject grow above max size
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_06_reject_grow_above_max_size(self):
        """
        Request a capacity above the 300 TiB ONTAP FlexVol maximum and verify
        it is refused, leaving the pool at the size test_05 grew it to.
        ONTAP reports this as an autosize-maximum violation rather than an
        oversized-volume error.
        Verifies:
          - updateStoragePool raises CloudstackAPIException
          - the error text is about the size limit, not about some other
            failure such as the aggregate running out of space
          - CloudStack capacity and ONTAP FlexVol size are both unchanged
          - Pool stays 'Up'
          - ONTAP: per-host igroups are untouched
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_05 must pass first"
        )
        pool = self.__class__.pool

        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before over-max resize")
        self.assertEqual(
            int(getattr(listed[0], "capacitybytes", 0) or 0),
            TestData.ONTAP_MAX_VOLUME_SIZE,
            "Pool should sit at the 300 TiB maximum - test_05 must pass first"
        )

        # Overshoot by only 1 GiB: at 0.0003% of the limit this probes the
        # 300 TiB boundary itself rather than asking for something absurd.
        above_max = self._align_flexvol_bytes(
            TestData.ONTAP_MAX_VOLUME_SIZE + 1024 ** 3
        )
        log_progress(
            logger, "info",
            "Requesting %d B for pool '%s' (ONTAP maximum is %d B, "
            "expect reject)",
            above_max, pool.name, TestData.ONTAP_MAX_VOLUME_SIZE,
        )

        # ONTAP enforces the platform cap through autosize rather than
        # rejecting the size outright, reporting "Volume maximum autosize
        # must be greater than or equal to the current volume size 300.0TB".
        # Matching on that keeps the test from passing when the resize fails
        # for an unrelated reason, such as the aggregate running out of space.
        self._assert_capacity_update_rejected(
            pool.id, above_max, "grow-above-max-flexvol",
            pool.id, pool.name,
            expected_error=(
                "maximum autosize",
                "maximum size",
                "exceeds",
            ),
        )

        after = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(after, "Pool disappeared after rejected over-max grow")
        self.assertEqual(
            after[0].state, "Up",
            "Pool should stay 'Up' after a rejected resize, got '%s'"
            % after[0].state
        )
        for host in self.cluster_hosts:
            iqn = (
                getattr(host, "storageurl", None)
                or getattr(host, "StorageUrl", None)
            )
            if iqn and iqn.startswith("iqn."):
                igroup_name = _igroup_name(self.svm_name, host.name)
                self.assertIsNotNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' disappeared after a rejected "
                    "over-max grow" % igroup_name,
                )


    # ------------------------------------------------------------------
    # Step 07 - shrink storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_07_shrink_storage_pool(self):
        """Shrink the original pool back to its initial safe capacity."""
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_05 must pass first"
        )
        target_size = self.__class__.resize_original_size
        self.assertIsNotNone(
            target_size, "Original size absent - test_05 must pass first"
        )
        pool = self.__class__.pool

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = pool.id
        cmd.capacitybytes = target_size
        self.apiClient.updateStoragePool(cmd)

        shrunk_pool = self._poll_pool_capacity(
            pool.id, target_size, timeout=120
        )
        self.assertEqual(shrunk_pool.state, "Up")
        shrunk_ontap_vol = self._poll_ontap_volume_size(
            pool.name, target_size, timeout=120
        )
        self.assertEqual(shrunk_ontap_vol.get("state"), "online")

        for host in self.cluster_hosts:
            iqn = (
                getattr(host, "storageurl", None)
                or getattr(host, "StorageUrl", None)
            )
            if iqn and iqn.startswith("iqn."):
                igroup_name = _igroup_name(self.svm_name, host.name)
                self.assertIsNotNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' disappeared after safe shrink"
                    % igroup_name,
                )


    # ------------------------------------------------------------------
    # Step 08 - disable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_08_disable_storage_pool(self):
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
    # Step 09 - grow and shrink storage pool while disabled
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_09_grow_and_shrink_storage_pool_while_disabled(self):
        """
        Grow and shrink the disabled pool and verify:
          - Both capacity updates are accepted
          - CloudStack and ONTAP reach each requested size
          - The pool remains Disabled throughout
          - The FlexVol remains online and per-host igroups are unchanged
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_03 must pass first"
        )
        pool = self.__class__.pool
        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before disabled resize")
        self.assertEqual(
            listed[0].state, "Disabled",
            "Pool must be Disabled - test_08 must pass first, got '%s'"
            % listed[0].state,
        )
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol missing before disabled resize"
        )
        original_size = max(
            int(getattr(listed[0], "capacitybytes", 0) or 0),
            int(ontap_vol.get("space", {}).get("size", 0) or 0),
        )
        grow_target = original_size + TestData.ONTAP_MIN_VOLUME_SIZE

        for target, operation in (
                (grow_target, "grow"),
                (original_size, "shrink")):
            log_progress(
                logger, "info",
                "%s disabled iSCSI pool '%s': target=%d B",
                operation.capitalize(), pool.name, target,
            )
            cmd = updateStoragePoolAPI.updateStoragePoolCmd()
            cmd.id = pool.id
            cmd.capacitybytes = target
            self.apiClient.updateStoragePool(cmd)

            resized = self._poll_pool_capacity(pool.id, target, timeout=120)
            self.assertEqual(
                resized.state, "Disabled",
                "Pool should remain Disabled after %s, got '%s'"
                % (operation, resized.state),
            )
            resized_ontap = self._poll_ontap_volume_size(
                pool.name, target, timeout=120
            )
            self.assertEqual(resized_ontap.get("state"), "online")

        for host in self.cluster_hosts:
            iqn = (
                getattr(host, "storageurl", None)
                or getattr(host, "StorageUrl", None)
            )
            if iqn and iqn.startswith("iqn."):
                igroup_name = _igroup_name(self.svm_name, host.name)
                self.assertIsNotNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' disappeared during disabled resize"
                    % igroup_name,
                )


    # ------------------------------------------------------------------
    # Step 10 - enable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_10_enable_storage_pool(self):
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
    # Step 11 - enter maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_11_enter_maintenance_mode(self):
        """
        Put the pool into maintenance mode and verify:
          - CloudStack reports Maintenance
          - ONTAP: FlexVol is still online (maintenance is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

        result = self._enter_maintenance(self.__class__.pool.id)
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
    # Step 12 - grow and shrink storage pool in maintenance
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_12_grow_and_shrink_storage_pool_in_maintenance(self):
        """
        Grow and shrink the pool while it is in maintenance mode and verify:
          - Both capacity updates are accepted
          - CloudStack and ONTAP reach each requested size
          - The pool remains in Maintenance throughout
          - The FlexVol remains online and per-host igroups are unchanged
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_03 must pass first"
        )
        pool = self.__class__.pool

        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before maintenance resize")
        self.assertEqual(
            listed[0].state, "Maintenance",
            "Pool must be in Maintenance - test_11 must pass first, got '%s'"
            % listed[0].state,
        )

        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol missing before maintenance resize"
        )
        original_size = max(
            int(getattr(listed[0], "capacitybytes", 0) or 0),
            int(ontap_vol.get("space", {}).get("size", 0) or 0),
        )
        grow_target = original_size + TestData.ONTAP_MIN_VOLUME_SIZE

        for target, operation in (
                (grow_target, "grow"),
                (original_size, "shrink")):
            log_progress(
                logger, "info",
                "%s iSCSI pool '%s' while in Maintenance: target=%d B",
                operation.capitalize(), pool.name, target,
            )
            cmd = updateStoragePoolAPI.updateStoragePoolCmd()
            cmd.id = pool.id
            cmd.capacitybytes = target
            self.apiClient.updateStoragePool(cmd)

            resized = self._poll_pool_capacity(pool.id, target, timeout=120)
            self.assertEqual(
                resized.state, "Maintenance",
                "Pool should remain in Maintenance after %s, got '%s'"
                % (operation, resized.state),
            )
            resized_ontap = self._poll_ontap_volume_size(
                pool.name, target, timeout=120
            )
            self.assertEqual(resized_ontap.get("state"), "online")

        for host in self.cluster_hosts:
            iqn = (
                getattr(host, "storageurl", None)
                or getattr(host, "StorageUrl", None)
            )
            if iqn and iqn.startswith("iqn."):
                igroup_name = _igroup_name(self.svm_name, host.name)
                self.assertIsNotNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' disappeared during maintenance resize"
                    % igroup_name,
                )


    # ------------------------------------------------------------------
    # Step 13 - cancel maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_13_cancel_maintenance_mode(self):
        """
        Cancel maintenance and verify:
          - CloudStack reports Up
          - ONTAP: FlexVol is still online
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")

        result = self._exit_maintenance(self.__class__.pool.id)
        self.assertEqual(result.state, "Up")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after cancel maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after cancel maintenance, got '%s'"
            % ontap_vol.get("state")
        )


    # ------------------------------------------------------------------
    # Step 14 - enter maintenance and delete pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_14_enter_maintenance_and_delete_pool(self):
        """
        Enter maintenance mode then delete the pool.
        Verifies the pool is removed from CloudStack and the backing ONTAP
        FlexVol is deleted.
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_03 must pass first")
        pool = self.__class__.pool
        pool_name = pool.name

        self._enter_maintenance(pool.id)

        self._delete_pool(pool.id)
        self.__class__.pool = None
        self.__class__.resize_original_size = None

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
    # Step 15 - create volume on pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_15_create_volume_on_pool(self):
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
            "test_15: created storage pool name='%s' id=%s state=%s type=%s",
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
            "test_15: created CloudStack volume name='%s' id=%s state=%s "
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
    # Step 16 - create vm and attach volume
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_16_create_vm_and_attach_volume(self):
        """
        Deploy a VM and attach the ONTAP data volume from test_15 to it.
        Verifies:
          - VM reaches 'Running'
          - attachVolume sets the volume's virtualmachineid (on ONTAP managed
            storage CloudStack may leave the volume state at 'Ready', so
            virtualmachineid is the reliable attach signal)
          - VM is still 'Running' after the attach
          - ONTAP: FlexVol stays online and the LUN is still present
          - ONTAP: per-host igroups survive the attach
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_15 must pass first"
        )
        self.assertIsNotNone(
            self.__class__.volume, "Volume absent - test_15 must pass first"
        )
        if self.__class__.template_id is None:
            self.skipTest(
                "No ready user KVM template in the zone - cannot deploy a VM"
            )
        self.assertIsNotNone(
            self.__class__.service_offering_id,
            "No service offering available - check the CloudStack setup"
        )

        pool = self.__class__.pool
        vol = self.__class__.volume

        log_progress(
            logger, "info",
            "Deploying VM for iSCSI pool '%s' (template=%s, offering=%s)",
            pool.name, self.__class__.template_id,
            self.__class__.service_offering_id,
        )

        cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
        cmd.zoneid = self.zone.id
        cmd.templateid = self.__class__.template_id
        cmd.serviceofferingid = self.__class__.service_offering_id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        if self.__class__.network_id:
            cmd.networkids = self.__class__.network_id

        vm = self.apiClient.deployVirtualMachine(cmd)
        self.assertIsNotNone(vm, "deployVirtualMachine returned None")
        self.__class__.vm = vm

        vm_obj = _wait_for_vm_state(self.apiClient, vm.id, "Running",
                                    timeout=600)
        self.assertIsNotNone(
            vm_obj, "VM %s never appeared in listVirtualMachines" % vm.id
        )
        self.assertEqual(
            vm_obj.state, "Running",
            "VM should be 'Running' before attach, got '%s'" % vm_obj.state
        )

        log_progress(
            logger, "info",
            "Attaching volume '%s' to VM '%s'", vol.id, vm.id,
        )
        attach_cmd = attachVolumeAPI.attachVolumeCmd()
        attach_cmd.id = vol.id
        attach_cmd.virtualmachineid = vm.id
        self.assertIsNotNone(
            self.apiClient.attachVolume(attach_cmd),
            "attachVolume returned None"
        )

        vol_vmid = self._poll_volume_attached(vol.id, timeout=180)
        self.assertEqual(
            vol_vmid, vm.id,
            "Volume %s should report virtualmachineid=%s after attach, got %s"
            % (vol.id, vm.id, vol_vmid)
        )

        vm_after = _wait_for_vm_state(self.apiClient, vm.id, "Running",
                                      timeout=60)
        self.assertEqual(
            vm_after.state, "Running",
            "VM should still be 'Running' after attach, got '%s'"
            % vm_after.state
        )

        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol missing after volume attach"
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after attach, got '%s'"
            % ontap_vol.get("state")
        )

        self.assertTrue(
            self.ontap.list_luns_in_volume(self.svm_name, pool.name),
            "No LUN present in FlexVol '%s' after attach" % pool.name
        )

        for host in self.cluster_hosts:
            iqn = (
                getattr(host, "storageurl", None)
                or getattr(host, "StorageUrl", None)
            )
            if iqn and iqn.startswith("iqn."):
                igroup_name = _igroup_name(self.svm_name, host.name)
                self.assertIsNotNone(
                    self.ontap.get_igroup(self.svm_name, igroup_name),
                    "ONTAP igroup '%s' disappeared after volume attach"
                    % igroup_name,
                )


    # ------------------------------------------------------------------
    # Step 17 - reject shrink below used capacity
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_17_reject_shrink_below_used_capacity(self):
        """
        Attempt to shrink the pool below ONTAP used space and verify the
        request is rejected while the volume and ONTAP LUNs remain unchanged.

        Runs after test_16, so the LUN is mapped to a running VM.  The LUN is
        thin-provisioned, though, and in practice leaves used space under the
        20 MiB FlexVol floor, so incompressible data is written through the
        ONTAP files API to get above it and removed before this test returns.
        The fill is skipped whenever used space is already high enough.
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_16 must pass first"
        )
        self.assertIsNotNone(
            self.__class__.volume, "Volume absent - test_16 must pass first"
        )
        pool = self.__class__.pool
        self.assertIsNotNone(
            self.ontap.get_volume(pool.name),
            "ONTAP FlexVol not found for pool '%s'" % pool.name,
        )

        vol_before = self._cs_volume_snapshot(self.__class__.volume.id)
        luns = self.ontap.list_luns_in_volume(self.svm_name, pool.name)
        self.assertTrue(
            len(luns) > 0,
            "No LUNs in FlexVol '%s' before rejected shrink" % pool.name,
        )
        luns_before_used = sorted(
            (lun.get("uuid") or "", lun.get("name") or "")
            for lun in luns
        )

        # The attached volume is thin on both protocols, so it usually does
        # not put 20 MiB on disk by itself.  This writes incompressible data
        # only when used space is still under the FlexVol floor, and removes
        # it before returning.
        filler_name, used_bytes = self._fill_flexvol_above_minimum(pool.name)
        self.__class__.filler_filename = filler_name
        self.__class__.filler_flexvol = pool.name if filler_name else None
        try:
            below_used = self._shrink_target_below_used(used_bytes)
            log_progress(
                logger, "info",
                "Shrinking iSCSI pool '%s' below ONTAP used capacity: "
                "used=%d B, requested=%d B "
                "(FlexVol minimum=%d B, expect reject)",
                pool.name, used_bytes, below_used, self.ONTAP_MIN_FLEXVOL_SIZE,
            )
            self._assert_capacity_update_rejected(
                pool.id, below_used, "shrink-below-used",
                pool.id, pool.name,
                # ONTAP's wording: "Selected volume size is too small to
                # hold the current volume data.  New volume size must be at
                # least 34.3MB ...".  Matching the specific phrase stops an
                # unrelated resize failure from counting as a pass.
                expected_error=(
                    "too small to hold the current volume data",
                    "too small to hold",
                    "cannot reduce",
                ),
            )
            self._assert_cs_volume_untouched(vol_before, "shrink-below-used")
            self.assertGreater(
                self._flexvol_used_bytes(pool.name),
                self.ONTAP_MIN_FLEXVOL_SIZE,
                "ONTAP used space dropped below the FlexVol minimum after "
                "the rejected shrink",
            )
            luns_after_used = sorted(
                (lun.get("uuid") or "", lun.get("name") or "")
                for lun in self.ontap.list_luns_in_volume(
                    self.svm_name, pool.name
                )
            )
            self.assertEqual(
                luns_after_used, luns_before_used,
                "LUN list in FlexVol '%s' changed after rejected "
                "used-capacity shrink" % pool.name,
            )
            self.assertTrue(
                len(luns_after_used) > 0,
                "LUN disappeared from FlexVol '%s' after rejected shrink"
                % pool.name,
            )
        finally:
            self._delete_filler_file(pool.name, filler_name)


    # ------------------------------------------------------------------
    # Step 18 - grow and shrink pool with vm attached
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_18_grow_and_shrink_pool_with_vm_attached(self):
        """
        Grow the pool and shrink it straight back while the volume is
        attached to the running VM from test_16, so resize is exercised
        against a pool that is genuinely in use rather than an idle one.
        Verifies:
          - grow: CloudStack and ONTAP both reach the requested size
          - shrink back: both return to the starting size
          - the VM stays 'Running' and keeps the volume across both resizes
          - ONTAP: FlexVol stays online
          - ONTAP: the LUN backing the volume is still present
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_15 must pass first"
        )
        self.assertIsNotNone(
            self.__class__.volume, "Volume absent - test_15 must pass first"
        )
        if self.__class__.vm is None:
            self.skipTest(
                "No VM was deployed (test_16 skipped) - nothing to resize "
                "a pool underneath"
            )

        pool = self.__class__.pool
        vm = self.__class__.vm
        vol = self.__class__.volume

        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before the in-use resize")
        original_size = int(getattr(listed[0], "capacitybytes", 0) or 0)
        grow_target = original_size + TestData.ONTAP_MIN_VOLUME_SIZE

        log_progress(
            logger, "info",
            "Growing in-use pool '%s' from %d B to %d B (VM %s holds the "
            "volume)", pool.name, original_size, grow_target, vm.id,
        )
        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = pool.id
        cmd.capacitybytes = grow_target
        self.apiClient.updateStoragePool(cmd)

        grown = self._poll_pool_capacity(pool.id, grow_target, timeout=120)
        self.assertEqual(
            grown.state, "Up",
            "Pool should stay 'Up' after growing under a VM, got '%s'"
            % grown.state
        )
        grown_vol = self._poll_ontap_volume_size(
            pool.name, grow_target, timeout=120
        )
        self.assertEqual(grown_vol.get("state"), "online")
        self._assert_vm_running_with_volume(vm.id, vol.id, "grow-with-vm")
        self.assertTrue(
            self.ontap.list_luns_in_volume(self.svm_name, pool.name),
            "LUN disappeared from FlexVol '%s' during the in-use resize"
            % pool.name,
        )

        log_progress(
            logger, "info",
            "Shrinking in-use pool '%s' back from %d B to %d B",
            pool.name, grow_target, original_size,
        )
        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = pool.id
        cmd.capacitybytes = original_size
        self.apiClient.updateStoragePool(cmd)

        shrunk = self._poll_pool_capacity(pool.id, original_size, timeout=120)
        self.assertEqual(
            shrunk.state, "Up",
            "Pool should stay 'Up' after shrinking under a VM, got '%s'"
            % shrunk.state
        )
        shrunk_vol = self._poll_ontap_volume_size(
            pool.name, original_size, timeout=120
        )
        self.assertEqual(shrunk_vol.get("state"), "online")
        self._assert_vm_running_with_volume(vm.id, vol.id, "shrink-with-vm")
        self.assertTrue(
            self.ontap.list_luns_in_volume(self.svm_name, pool.name),
            "LUN disappeared from FlexVol '%s' during the in-use resize"
            % pool.name,
        )


    # ------------------------------------------------------------------
    # Step 19 - delete volume and pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_19_delete_volume_and_pool(self):
        """
        Detach the volume and destroy the VM from test_16, then delete the
        volume, enter maintenance, and force-delete the pool.
        Verifies:
          - deleteVolume removes the LUN from ONTAP
          - Pool transitions to Maintenance
          - Pool is removed from CloudStack after force deletion
          - ONTAP: FlexVol deleted
          - ONTAP: igroups for all cluster hosts deleted
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_15 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_15 must pass first")

        # A volume still attached to a VM cannot be deleted, so unwind test_16
        # first.  Both steps are no-ops when test_16 skipped.
        self._detach_volume_if_attached(self.__class__.volume.id)
        self.__class__._destroy_vm_if_present()

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
    # Step 20 - delete pool with flexvol predeleted
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_20_delete_pool_with_flexvol_predeleted(self):
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
            self._enter_maintenance(pool.id)
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

    # ------------------------------------------------------------------
    # Step 21 - delete pool with igroups predeleted
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_21_delete_pool_with_igroups_predeleted(self):
        """
        Delete the per-host igroups directly on ONTAP, then delete the empty
        pool through CloudStack.  Deletion must tolerate the missing igroups
        and still remove the FlexVol.

        The igroup name is keyed off the host UUID and the SVM, not the pool,
        so the igroups are shared by every ONTAP pool on that SVM.  This test
        therefore assumes no other pool is in use on the SVM, which holds
        here because test_11 removed the workflow pools.

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

            self._enter_maintenance(pool.id)
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


    # ------------------------------------------------------------------
