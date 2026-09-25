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
pool lifecycle (no volumes).

Tests are numbered test_01 ... test_16 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create primary storage pool
  02  Increase storage pool capacity to the maximum supported size
  03  Reject a grow beyond the maximum supported size
  04  Safely shrink storage pool capacity
  05  Disable storage pool
  06  Grow and shrink the pool while it is disabled
  07  Enable storage pool
  08  Enter maintenance mode
  09  Resize storage pool while in maintenance mode
  10  Cancel maintenance mode
  11  Enter maintenance mode and delete the storage pool
  12  Create a new pool and allocate a CloudStack data volume (LUN created)
  13  Deploy a VM and attach the ONTAP volume (LUN) to it
  14  Reject shrink below the ONTAP used capacity of the attached LUN
  15  Grow and shrink the pool while the VM holds the volume (LUN)
  16  Detach/destroy the VM, delete the volume (LUN removed), enter
      maintenance, force-delete pool

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has iSCSI configured (storageUrl starts with iqn.)
  - At least one ready user KVM template in the zone (test_13 skips without one)
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/pool/ -v

Note: Tests share class-level state (sequential).  Always run the full suite.
"""

import base64
import logging
import random
import re
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    attachVolume as attachVolumeAPI,
    cancelStorageMaintenance,
    createStoragePool as createStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    deployVirtualMachine as deployVirtualMachineAPI,
    enableStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import (
    OntapRestClient, OntapTestBase, _wait_for_vm_state, get_datacenter_config,
    log_progress,
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

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapISCSI_%d" % random.randint(0, 99999)

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
    # Step 01 - Create primary storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_01_create_primary_storage_pool(self):
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
    # Step 02 - Increase storage pool capacity
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_02_grow_storage_pool(self):
        """Grow the original pool to 300 TiB and verify CloudStack and ONTAP converge."""
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_01 must pass first"
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
    # Step 03 - Reject a grow beyond the ONTAP maximum FlexVol size
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_03_reject_grow_above_max_size(self):
        """
        Request a capacity above the 300 TiB ONTAP FlexVol maximum and verify
        it is refused, leaving the pool at the size test_02 grew it to.
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
            self.__class__.pool, "Pool absent - test_02 must pass first"
        )
        pool = self.__class__.pool

        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before over-max resize")
        self.assertEqual(
            int(getattr(listed[0], "capacitybytes", 0) or 0),
            TestData.ONTAP_MAX_VOLUME_SIZE,
            "Pool should sit at the 300 TiB maximum - test_02 must pass first"
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
    # Step 04 - Safely shrink storage pool capacity
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_04_shrink_storage_pool(self):
        """Shrink the original pool back to its initial safe capacity."""
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_02 must pass first"
        )
        target_size = self.__class__.resize_original_size
        self.assertIsNotNone(
            target_size, "Original size absent - test_02 must pass first"
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
    # Step 05 - Disable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_05_disable_storage_pool(self):
        """
        Disable the pool and verify:
          - CloudStack reports Disabled
          - ONTAP: FlexVol is still online (disable is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

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
    # Step 06 - Resize storage pool while disabled
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_06_resize_storage_pool_while_disabled(self):
        """
        Grow and shrink the disabled pool and verify:
          - Both capacity updates are accepted
          - CloudStack and ONTAP reach each requested size
          - The pool remains Disabled throughout
          - The FlexVol remains online and per-host igroups are unchanged
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_01 must pass first"
        )
        pool = self.__class__.pool
        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before disabled resize")
        self.assertEqual(
            listed[0].state, "Disabled",
            "Pool must be Disabled - test_05 must pass first, got '%s'"
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
    # Step 07 - Enable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_07_enable_storage_pool(self):
        """
        Re-enable the pool and verify:
          - CloudStack reports Up
          - ONTAP: FlexVol is still online (enable is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

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
    # Step 08 - Enter maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_08_enter_maintenance_mode(self):
        """
        Put the pool into maintenance mode and verify:
          - CloudStack reports Maintenance
          - ONTAP: FlexVol is still online (maintenance is a CS-only state change)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.enableStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Maintenance", timeout=120)
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
    # Step 09 - Resize storage pool while in maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_09_resize_storage_pool_in_maintenance(self):
        """
        Grow the pool while it is in maintenance mode and verify:
          - updateStoragePool is accepted while the pool is in Maintenance
          - CloudStack reports the new capacity
          - ONTAP: FlexVol is resized and still online
          - The pool stays in Maintenance throughout (resize must not
            implicitly return it to Up)
          - ONTAP: per-host igroups are unchanged
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_01 must pass first"
        )
        pool = self.__class__.pool

        listed = list_storage_pools(self.apiClient, id=pool.id)
        self.assertTrue(listed, "Pool missing before maintenance resize")
        self.assertEqual(
            listed[0].state, "Maintenance",
            "Pool must be in Maintenance - test_08 must pass first, got '%s'"
            % listed[0].state
        )

        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol missing before maintenance resize"
        )
        current_size = max(
            int(getattr(listed[0], "capacitybytes", 0) or 0),
            int(ontap_vol.get("space", {}).get("size", 0) or 0),
        )
        requested_size = current_size + TestData.ONTAP_MIN_VOLUME_SIZE

        log_progress(
            logger, "info",
            "Resizing iSCSI pool '%s' while in Maintenance: current=%d B, "
            "requested=%d B",
            pool.name, current_size, requested_size,
        )

        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = pool.id
        cmd.capacitybytes = requested_size
        self.apiClient.updateStoragePool(cmd)

        resized_pool = self._poll_pool_capacity(
            pool.id, requested_size, timeout=120
        )
        self.assertEqual(
            resized_pool.state, "Maintenance",
            "Pool should remain in Maintenance after resize, got '%s'"
            % resized_pool.state
        )

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
                    "ONTAP igroup '%s' disappeared after maintenance resize"
                    % igroup_name,
                )

    # ------------------------------------------------------------------
    # Step 10 - Cancel maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_10_cancel_maintenance_mode(self):
        """
        Cancel maintenance and verify:
          - CloudStack reports Up
          - ONTAP: FlexVol is still online
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.cancelStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Up", timeout=120)
        self.assertEqual(result.state, "Up")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after cancel maintenance")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after cancel maintenance, got '%s'"
            % ontap_vol.get("state")
        )

    # ------------------------------------------------------------------
    # Step 11 - Enter maintenance mode and delete the storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_11_enter_maintenance_and_delete_pool(self):
        """
        Enter maintenance mode then delete the pool.
        Verifies the pool is removed from CloudStack and the backing ONTAP
        FlexVol is deleted.
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")
        pool = self.__class__.pool
        pool_name = pool.name

        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)

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
    # Step 12 - Create fresh pool and allocate a CloudStack volume (LUN)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_12_create_volume_on_pool(self):
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
            "test_12: created storage pool name='%s' id=%s state=%s type=%s",
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
            "test_12: created CloudStack volume name='%s' id=%s state=%s "
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
    # Step 13 - Deploy a VM and attach the ONTAP volume (LUN) to it
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_13_create_vm_and_attach_volume(self):
        """
        Deploy a VM and attach the ONTAP data volume from test_12 to it.
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
            self.__class__.pool, "Pool absent - test_12 must pass first"
        )
        self.assertIsNotNone(
            self.__class__.volume, "Volume absent - test_12 must pass first"
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
    # Step 14 - Reject shrink below ONTAP used capacity
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_14_reject_shrink_below_used_capacity(self):
        """
        Attempt to shrink the pool below ONTAP used space and verify the
        request is rejected while the volume and ONTAP LUNs remain unchanged.

        Runs after test_13, so the LUN is mapped to a running VM.  The LUN is
        thin-provisioned, though, and in practice leaves used space under the
        20 MiB FlexVol floor, so incompressible data is written through the
        ONTAP files API to get above it and removed before this test returns.
        The fill is skipped whenever used space is already high enough.
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_13 must pass first"
        )
        self.assertIsNotNone(
            self.__class__.volume, "Volume absent - test_13 must pass first"
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
    # Step 15 - Resize the pool while the VM holds the volume
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_15_resize_pool_with_vm_attached(self):
        """
        Grow the pool and shrink it straight back while the volume is
        attached to the running VM from test_13, so resize is exercised
        against a pool that is genuinely in use rather than an idle one.
        Verifies:
          - grow: CloudStack and ONTAP both reach the requested size
          - shrink back: both return to the starting size
          - the VM stays 'Running' and keeps the volume across both resizes
          - ONTAP: FlexVol stays online
          - ONTAP: the LUN backing the volume is still present
        """
        self.assertIsNotNone(
            self.__class__.pool, "Pool absent - test_12 must pass first"
        )
        self.assertIsNotNone(
            self.__class__.volume, "Volume absent - test_12 must pass first"
        )
        if self.__class__.vm is None:
            self.skipTest(
                "No VM was deployed (test_13 skipped) - nothing to resize "
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
    # Step 16 - Detach/destroy the VM, delete volume (LUN), delete the pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_16_delete_volume_and_pool(self):
        """
        Detach the volume and destroy the VM from test_13, then delete the
        volume, enter maintenance, and force-delete the pool.
        Verifies:
          - deleteVolume removes the LUN from ONTAP
          - Pool transitions to Maintenance
          - Pool is removed from CloudStack after force deletion
          - ONTAP: FlexVol deleted
          - ONTAP: igroups for all cluster hosts deleted
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_12 must pass first")
        self.assertIsNotNone(self.__class__.volume, "Volume absent - test_12 must pass first")

        # A volume still attached to a VM cannot be deleted, so unwind test_13
        # first.  Both steps are no-ops when test_13 skipped.
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
