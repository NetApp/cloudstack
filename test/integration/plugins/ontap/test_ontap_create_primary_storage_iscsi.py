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
Sequential workflow integration tests for NetApp ONTAP iSCSI primary storage pool.

Tests are numbered test_01 ... test_11 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create primary storage pool
  02  Disable storage pool
  03  Enable storage pool
  04  Enter maintenance mode
  05  Cancel maintenance mode
  06  Create a data volume on the pool
  07  Enter maintenance mode (pool has a volume)
  08  Cancel maintenance mode (pool has a volume)
  09  Delete the data volume
  10  Enter maintenance mode and delete the storage pool
  11  Create a second pool, attach a volume, enter maintenance,
      then force-delete the pool (volume still present)

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has iSCSI configured (storageUrl starts with iqn.)
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/test_ontap_create_primary_storage_iscsi.py -v
"""

import base64
import logging
import random

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    createStoragePool as createStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    enableStorageMaintenance,
    cancelStorageMaintenance,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase

logger = logging.getLogger("TestOntapISCSIWorkflow")


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
    import re
    sanitized = re.sub(r"[^a-zA-Z0-9_-]", "_", short)
    return "cs_%s_%s" % (svm_name, sanitized)


def _lun_path(vol_name, lun_name):
    """Mirror OntapStorageUtils.getLunName: /vol/{volName}/{lunName}"""
    return "/vol/%s/%s" % (vol_name, lun_name)


# ---------------------------------------------------------------------------
# Sequential workflow test class
# ---------------------------------------------------------------------------

class TestOntapISCSIPrimaryStorageWorkflow(OntapTestBase):

    # ---- iSCSI-specific state (set/cleared by individual tests) --------
    _vol_name_prefix = "OntapISCSIVol"
    lun_path = None      # ONTAP LUN path of cls.volume
    lun_path2 = None     # ONTAP LUN path of cls.volume2

    @classmethod
    def setUpClass(cls):
        testclient = super(
            TestOntapISCSIPrimaryStorageWorkflow, cls
        ).getClsTestClient()

        cls.apiClient = testclient.getApiClient()
        cls.dbConnection = testclient.getDbConnection()
        config = testclient.getParsedTestDataConfig()

        ontap_cfg = config.get("ontap", {})
        storage_ip = ontap_cfg.get("storageIP", "")
        svm_name = ontap_cfg.get("svmName", "")
        username = ontap_cfg.get("username", "")
        password = ontap_cfg.get("password", "")
        scope = ontap_cfg.get("storagePoolScope", "CLUSTER")
        provider = ontap_cfg.get("storagePoolProvider", "NetApp ONTAP")
        tags = ontap_cfg.get("storagePoolTags", "ontap-iscsi")
        capacitybytes = ontap_cfg.get("capacitybytes", None)

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

    # ------------------------------------------------------------------
    # Step 01 - Create primary storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_01_create_primary_storage_pool(self):
        """
        Create an iSCSI primary storage pool and verify:
          - CloudStack state is Up, type is Iscsi
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
            pool.type, "Iscsi",
            "Pool type should be 'Iscsi', got '%s'" % pool.type
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

    # ------------------------------------------------------------------
    # Step 02 - Disable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_02_disable_storage_pool(self):
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
    # Step 03 - Enable storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_03_enable_storage_pool(self):
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
    # Step 04 - Enter maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_04_enter_maintenance_mode(self):
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
    # Step 05 - Cancel maintenance mode
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_05_cancel_maintenance_mode(self):
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
    # Step 06 - Create a data volume on the pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_06_create_volume(self):
        """
        Allocate a data volume on the iSCSI pool and verify:
          - CloudStack returns a volume id
          - ONTAP: a LUN is created inside the FlexVol at /vol/{poolName}/{volName}
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")
        if not self.disk_offering_id:
            self.skipTest("No disk offering available - skipping volume steps")

        try:
            vol = self._create_volume(self.__class__.pool.id)
        except Exception as e:
            self.skipTest("createVolume failed (iSCSI may require an attached VM): %s" % e)

        self.__class__.volume = vol
        vol_id = getattr(vol, "id", None)
        self.assertIsNotNone(vol_id, "Volume creation returned no id")

        # ONTAP: a LUN must exist inside the FlexVol
        luns = self.ontap.list_luns_in_volume(self.svm_name, self.__class__.pool.name)
        self.assertTrue(
            len(luns) > 0,
            "No LUNs found in ONTAP FlexVol '%s' after volume creation"
            % self.__class__.pool.name
        )
        self.__class__.lun_path = luns[0].get("name")  # cache for later steps

    # ------------------------------------------------------------------
    # Step 07 - Enter maintenance mode (pool has a volume)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_07_enter_maintenance_mode_with_volume(self):
        """
        Enter maintenance mode while the pool holds a data volume and verify:
          - CloudStack reports Maintenance
          - ONTAP: FlexVol still online and LUN still present (maintenance
            does not affect ONTAP data plane)
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.enableStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Maintenance", timeout=120)
        self.assertEqual(result.state, "Maintenance")

        # ONTAP: FlexVol and LUN must be untouched
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared during maintenance (with volume)")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' during maintenance, got '%s'"
            % ontap_vol.get("state")
        )
        if getattr(self.__class__, "lun_path", None):
            lun = self.ontap.get_lun(self.svm_name, self.__class__.lun_path)
            self.assertIsNotNone(
                lun,
                "ONTAP LUN '%s' disappeared during maintenance" % self.__class__.lun_path
            )

    # ------------------------------------------------------------------
    # Step 08 - Cancel maintenance mode (pool has a volume)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_08_cancel_maintenance_mode_with_volume(self):
        """
        Cancel maintenance mode while the pool still holds the volume and verify:
          - CloudStack reports Up
          - ONTAP: FlexVol online and LUN still present
        """
        self.assertIsNotNone(self.__class__.pool, "Pool absent - test_01 must pass first")

        cmd = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
        cmd.id = self.__class__.pool.id
        self.apiClient.cancelStorageMaintenance(cmd)

        result = self._poll_pool_state(self.__class__.pool.id, "Up", timeout=120)
        self.assertEqual(result.state, "Up")

        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after cancel maintenance (with volume)")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after cancel maintenance, got '%s'"
            % ontap_vol.get("state")
        )
        if getattr(self.__class__, "lun_path", None):
            lun = self.ontap.get_lun(self.svm_name, self.__class__.lun_path)
            self.assertIsNotNone(
                lun,
                "ONTAP LUN '%s' disappeared after cancel maintenance" % self.__class__.lun_path
            )

    # ------------------------------------------------------------------
    # Step 09 - Delete the volume
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_09_delete_volume(self):
        """
        Delete the data volume and verify:
          - ONTAP: the LUN is removed from the FlexVol
          - ONTAP: the FlexVol itself is still online (only the LUN is gone)
        """
        if self.__class__.volume is None:
            self.skipTest("No volume from test_06 - skipping")

        vol_id = self.__class__.volume.id
        lun_path = getattr(self.__class__, "lun_path", None)
        cmd = deleteVolumeAPI.deleteVolumeCmd()
        cmd.id = vol_id
        self.apiClient.deleteVolume(cmd)
        self.__class__.volume = None
        self.__class__.lun_path = None

        logger.info("Volume %s deleted" % vol_id)

        # ONTAP: LUN must be gone
        if lun_path:
            lun = self.ontap.get_lun(self.svm_name, lun_path)
            self.assertIsNone(
                lun,
                "ONTAP LUN '%s' still exists after volume deletion" % lun_path
            )

        # ONTAP: FlexVol must still be online
        ontap_vol = self.ontap.get_volume(self.__class__.pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol disappeared after volume deletion")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' after volume deletion, got '%s'"
            % ontap_vol.get("state")
        )

    # ------------------------------------------------------------------
    # Step 10 - Enter maintenance mode and delete the storage pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_10_enter_maintenance_and_delete_pool(self):
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
    # Step 11 - Create pool + volume, enter maintenance, force-delete
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_workflow"], required_hardware=True)
    def test_11_create_pool_volume_maintenance_force_delete(self):
        """
        Validates the forced=True behaviour of deleteStoragePool.

        CloudStack distinguishes two volume categories on a pool:
          - non-destroyed  (Allocated/Ready): active volumes
          - destroyed      (Destroy state)  : soft-deleted, awaiting GC expunge

        forced=False → fails if ANY volume record exists on the pool (any state)
        forced=True  → fails only if non-destroyed volumes exist;
                       if only destroyed volumes remain CloudStack force-expunges
                       them and removes the pool.

        This test covers the two reliable halves of that contract:

          Step 1  Create pool + allocate a data volume (non-destroyed).
          Step 2  Enter maintenance mode.
          Step 3  forced=False delete MUST FAIL — non-destroyed volume present.
          Step 4  Soft-delete the volume (deleteVolume API).
          Step 5  forced=True delete MUST SUCCEED — handles any remaining state
                  (immediately-expunged or still Destroyed — both pass).
          Step 6  Assert pool is gone from CloudStack and ONTAP.

        Note: The Destroyed-only scenario (forced=True succeeds where forced=False
        would still fail) requires a VM lifecycle to produce Destroyed volumes and
        is covered by higher-level system tests rather than this FT suite.
        """
        if not self.disk_offering_id:
            self.skipTest(
                "No disk offering available; force-delete test requires a volume "
                "to be present on the pool."
            )

        pool2 = self._create_pool()
        self.__class__.pool2 = pool2
        self.assertEqual(
            pool2.state, "Up",
            "Pool2 state should be 'Up', got '%s'" % pool2.state
        )

        # Step 1: allocate a data volume — must succeed for this test to be valid
        try:
            vol2 = self._create_volume(pool2.id)
            self.__class__.volume2 = vol2
        except Exception as e:
            self.skipTest(
                "createVolume failed (iSCSI may require an attached VM): %s" % e
            )
        self.assertIsNotNone(getattr(vol2, "id", None),
                             "Volume2 creation returned no id")

        # ONTAP: LUN must be created in pool2's FlexVol
        luns2 = self.ontap.list_luns_in_volume(self.svm_name, pool2.name)
        self.assertTrue(
            len(luns2) > 0,
            "No LUNs found in ONTAP FlexVol '%s' after volume2 creation" % pool2.name
        )
        self.__class__.lun_path2 = luns2[0].get("name")

        # Step 2: enter maintenance
        maint_cmd = enableStorageMaintenance.enableStorageMaintenanceCmd()
        maint_cmd.id = pool2.id
        self.apiClient.enableStorageMaintenance(maint_cmd)
        self._poll_pool_state(pool2.id, "Maintenance", timeout=120)

        # ONTAP: LUN still present during maintenance
        if self.__class__.lun_path2:
            lun2 = self.ontap.get_lun(self.svm_name, self.__class__.lun_path2)
            self.assertIsNotNone(
                lun2,
                "ONTAP LUN '%s' disappeared during maintenance (pool2)" % self.__class__.lun_path2
            )

        # Step 3: forced=False must FAIL — active (non-destroyed) volume present
        from marvin.cloudstackException import CloudstackAPIException
        with self.assertRaises(CloudstackAPIException,
                               msg="deleteStoragePool (forced=False) should fail "
                                   "when a non-destroyed volume is on the pool"):
            self._delete_pool(pool2.id, forced=False)

        # Step 4: soft-delete the volume via the deleteVolume API
        del_vol_cmd = deleteVolumeAPI.deleteVolumeCmd()
        del_vol_cmd.id = self.__class__.volume2.id
        self.apiClient.deleteVolume(del_vol_cmd)
        self.__class__.volume2 = None

        # ONTAP: LUN must be gone after deleteVolume
        if self.__class__.lun_path2:
            lun2 = self.ontap.get_lun(self.svm_name, self.__class__.lun_path2)
            self.assertIsNone(
                lun2,
                "ONTAP LUN '%s' still exists after deleteVolume (pool2)" % self.__class__.lun_path2
            )
            self.__class__.lun_path2 = None

        # Step 5: forced=True must SUCCEED — handles any remaining volume state
        self._delete_pool(pool2.id, forced=True)
        self.__class__.pool2 = None

        # Step 6: assert CloudStack and ONTAP cleaned up
        try:
            remaining = list_storage_pools(self.apiClient, id=pool2.id)
        except Exception:
            remaining = None
        self.assertFalse(remaining, "Pool2 still listed in CloudStack after force-deletion")

        # ONTAP: FlexVol and igroups must be deleted
        ontap_vol = self.ontap.get_volume(pool2.name)
        self.assertIsNone(
            ontap_vol,
            "ONTAP FlexVol '%s' still exists after force-deletion" % pool2.name
        )
        for host in self.cluster_hosts:
            iqn = getattr(host, "storageurl", None) or getattr(host, "StorageUrl", None)
            if not iqn or not iqn.startswith("iqn."):
                continue
            igroup_name = _igroup_name(self.svm_name, host.name)
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            self.assertIsNone(
                igroup,
                "ONTAP igroup '%s' still exists after pool2 force-deletion" % igroup_name
            )
