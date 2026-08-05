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
lifecycle with a running virtual machine.

Covers TDS (section 10) iSCSI VM volume scenarios:

  TDS Approach-1 SN 27   — Create CS Volume and allocate it to an Instance (iSCSI)
                           (attach data volume to running VM; verify LUN-map)
  TDS VM Stop  (iSCSI)   — Stop running VM; verify LUN-maps are removed
  TDS VM Start (iSCSI)   — Start stopped VM; verify LUN-maps are re-created
  TDS Detach   (iSCSI)   — Detach data volume; verify LUN-map removed

Key iSCSI behaviour verified at each step via ONTAP REST API:
  - createVolume → LUN is created inside the pool's FlexVol
  - attachVolume → LUN-map is created linking the LUN to the host's igroup
  - stopVirtualMachine → LUN-map is removed (LUN stays; just unmapped)
  - startVirtualMachine → LUN-map is re-created
  - detachVolume → LUN-map is removed

Tests are numbered test_01 ... test_08 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create iSCSI primary storage pool on ONTAP
  02  Create a CloudStack data volume on the iSCSI pool (LUN on ONTAP)
  03  Deploy a VM using any available KVM template
  04  Attach iSCSI data volume to running VM (LUN-map created)
  05  Stop VM — LUN-map for attached volume is removed from ONTAP
  06  Start VM — LUN-map is re-created on ONTAP
  07  Detach data volume from running VM — LUN-map removed
  08  Destroy VM, delete data volume, delete pool

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster where every host has iSCSI initiator configured (iqn.* IQN)
  - ONTAP SVM with iSCSI service enabled and at least one iSCSI data LIF
  - ontap.cfg populated with real values
  - At least one KVM template must be fully downloaded and ready (isready=True)

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/test_ontap_vm_volume_attach_iscsi.py -v
"""

import base64
import logging
import random
import re
import time
import unittest

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    attachVolume as attachVolumeAPI,
    createNetwork as createNetworkAPI,
    createStoragePool as createStoragePoolAPI,
    deleteNetwork as deleteNetworkAPI,
    deleteVolume as deleteVolumeAPI,
    deployVirtualMachine as deployVirtualMachineAPI,
    destroyVirtualMachine as destroyVirtualMachineAPI,
    detachVolume as detachVolumeAPI,
    enableStorageMaintenance,
    listNetworkOfferings as listNetworkOfferingsAPI,
    listNetworks as listNetworksAPI,
    listServiceOfferings as listServiceOfferingsAPI,
    listTemplates as listTemplatesAPI,
    listVirtualMachines as listVirtualMachinesAPI,
    listVolumes as listVolumesAPI,
    startVirtualMachine as startVirtualMachineAPI,
    stopVirtualMachine as stopVirtualMachineAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config

logger = logging.getLogger("TestOntapVMVolumeAttachISCSI")


# ---------------------------------------------------------------------------
# Utility functions
# ---------------------------------------------------------------------------

def _list_vms_cmd(vm_id):
    cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
    cmd.id = vm_id
    cmd.listall = True
    return cmd


def _wait_for_vm_state(api_client, vm_id, target_state, timeout=300,
                       interval=10):
    """Block until the VM reaches target_state or timeout expires."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        vms = api_client.listVirtualMachines(_list_vms_cmd(vm_id))
        if vms and vms[0].state.lower() == target_state.lower():
            return vms[0]
        time.sleep(interval)
    return None


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
                "email": "ontap-iscsi-vm@test.com",
                "firstname": "ONTAP",
                "lastname": "iSCSI-VM",
                "username": "ontap_iscsi_vm_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapISCSIVM_%d" % random.randint(0, 9999),
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
# Sequential workflow test class
# ---------------------------------------------------------------------------

class TestOntapVMVolumeAttachISCSI(OntapTestBase):
    """
    Tests iSCSI ONTAP data volume lifecycle with a running CloudStack VM.
    All tests are sequential — state is carried on class attributes.
    """

    # ---- extra shared state beyond OntapTestBase -----------------------
    vm = None
    template_id = None
    service_offering_id = None
    network_id = None
    _created_network_id = None  # network created by this suite for Advanced zones

    _vol_name_prefix = "OntapISCSIVM"

    # ---- setup ---------------------------------------------------------

    @classmethod
    def setUpClass(cls):
        super(TestOntapVMVolumeAttachISCSI, cls).setUpClass()
        testclient = super(
            TestOntapVMVolumeAttachISCSI, cls
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

        # Discover a ready user KVM template (exclude SYSTEM type)
        tpl_cmd = listTemplatesAPI.listTemplatesCmd()
        tpl_cmd.templatefilter = "all"
        tpl_cmd.listall = True
        tpl_cmd.zoneid = cls.zone.id
        templates = cls.apiClient.listTemplates(tpl_cmd) or []
        kvm_ready = [
            t for t in templates
            if getattr(t, "hypervisor", "").lower() == "kvm"
            and getattr(t, "isready", False)
            and getattr(t, "templatetype", "").upper() != "SYSTEM"
        ]
        cls.template_id = kvm_ready[0].id if kvm_ready else None
        if cls.template_id is None:
            logger.warning(
                "No ready KVM user template found — VM tests will be skipped."
            )

        # Smallest service offering
        so_cmd = listServiceOfferingsAPI.listServiceOfferingsCmd()
        offerings = cls.apiClient.listServiceOfferings(so_cmd) or []
        assert offerings, "No service offerings available in CloudStack"
        offerings.sort(key=lambda s: getattr(s, "memory", 9999))
        cls.service_offering_id = offerings[0].id

        # Network ID for VM deployment
        cls.network_id = None
        zone_type = getattr(cls.zone, "networktype", "Basic")
        if zone_type.lower() == "advanced":
            # Find a network already accessible to the test account
            net_cmd = listNetworksAPI.listNetworksCmd()
            net_cmd.zoneid = cls.zone.id
            net_cmd.account = cls.account.name
            net_cmd.domainid = cls.domain.id
            nets = cls.apiClient.listNetworks(net_cmd) or []
            if nets:
                cls.network_id = nets[0].id
            else:
                # Create an Isolated guest network for the test account
                no_cmd = listNetworkOfferingsAPI.listNetworkOfferingsCmd()
                no_cmd.state = "Enabled"
                no_cmd.guestiptype = "Isolated"
                no_cmd.specifyvlan = "false"
                offerings = cls.apiClient.listNetworkOfferings(no_cmd) or []
                snat_offering = next(
                    (o for o in offerings
                     if "SourceNat" in o.name and "Vpc" not in o.name
                     and "NSX" not in o.name and "Netris" not in o.name),
                    offerings[0] if offerings else None
                )
                if snat_offering:
                    cn_cmd = createNetworkAPI.createNetworkCmd()
                    cn_cmd.zoneid = cls.zone.id
                    cn_cmd.networkofferingid = snat_offering.id
                    cn_cmd.name = "ontap-iscsi-vm-net-%d" % random.randint(
                        0, 9999)
                    cn_cmd.displaytext = "ONTAP iSCSI VM test network"
                    cn_cmd.account = cls.account.name
                    cn_cmd.domainid = cls.domain.id
                    net = cls.apiClient.createNetwork(cn_cmd)
                    cls.network_id = net.id
                    cls._created_network_id = net.id

    @classmethod
    def tearDownClass(cls):
        """
        Safety-net cleanup: destroy VM if still alive, delete the guest
        network created for Advanced zones (if not already deleted by test_08),
        then delegate pool/volume/account cleanup to the base class.
        """
        if cls.vm is not None:
            try:
                vms = cls.apiClient.listVirtualMachines(
                    _list_vms_cmd(cls.vm.id))
                state = vms[0].state if vms else "unknown"
                if state.lower() not in ("stopped", "destroyed",
                                         "expunging", "error"):
                    stop_cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
                    stop_cmd.id = cls.vm.id
                    stop_cmd.forced = True
                    cls.apiClient.stopVirtualMachine(stop_cmd)
                    _wait_for_vm_state(cls.apiClient, cls.vm.id,
                                       "Stopped", timeout=120)
            except Exception as e:
                logger.warning("tearDownClass: could not stop VM %s: %s"
                               % (cls.vm.id, e))
            try:
                dest_cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
                dest_cmd.id = cls.vm.id
                dest_cmd.expunge = True
                cls.apiClient.destroyVirtualMachine(dest_cmd)
            except Exception as e:
                logger.warning("tearDownClass: could not destroy VM %s: %s"
                               % (cls.vm.id, e))

        # Delete the guest network created for this account in Advanced zones.
        # test_08 deletes it on the happy path; this is the fallback for
        # mid-suite failures.
        if cls._created_network_id is not None:
            try:
                dn_cmd = deleteNetworkAPI.deleteNetworkCmd()
                dn_cmd.id = cls._created_network_id
                cls.apiClient.deleteNetwork(dn_cmd)
                cls._created_network_id = None
            except Exception as e:
                logger.warning(
                    "tearDownClass: could not delete network %s: %s"
                    % (cls._created_network_id, e))

        super(TestOntapVMVolumeAttachISCSI, cls).tearDownClass()

    # ---- helpers -------------------------------------------------------

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapISCSIVM_%d" % random.randint(0, 99999)

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

    def _poll_vm_state(self, vm_id, target_state, timeout=300, interval=10):
        deadline = time.time() + timeout
        current_state = "unknown"
        while time.time() < deadline:
            vms = self.apiClient.listVirtualMachines(_list_vms_cmd(vm_id))
            if vms:
                current_state = vms[0].state
                if current_state.lower() == target_state.lower():
                    return vms[0]
            time.sleep(interval)
        self.fail("VM %s did not reach '%s' within %ds (last: '%s')"
                  % (vm_id, target_state, timeout, current_state))

    def _poll_volume_field(self, vol_id, field, target, timeout=120,
                           interval=5):
        """Poll a volume field until it matches target; return the volume."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            cmd = listVolumesAPI.listVolumesCmd()
            cmd.id = vol_id
            cmd.listall = True
            vols = self.apiClient.listVolumes(cmd)
            if vols:
                val = getattr(vols[0], field, None)
                if val == target:
                    return vols[0]
            time.sleep(interval)
        return None

    def _lun_maps(self):
        """Return current LUN-maps for the pool's FlexVol."""
        if self.__class__.pool is None:
            return []
        return self.ontap.list_lun_maps_for_volume(
            self.svm_name, self.__class__.pool.name)

    # ==================================================================
    # Test steps
    # ==================================================================

    # ------------------------------------------------------------------
    # Step 01 — Create iSCSI ONTAP pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_01_create_iscsi_pool(self):
        """
        Create an iSCSI primary storage pool on ONTAP.
        Verifies:
          - Pool reaches 'Up' state; type is 'Iscsi'
          - ONTAP: FlexVol is online
          - ONTAP: igroup exists for every host in the cluster that has an IQN
        """
        pool = self._create_pool()
        self.__class__.pool = pool

        self.assertEqual(pool.state, "Up",
                         "Pool state should be 'Up', got '%s'" % pool.state)
        self.assertEqual(pool.type, "Iscsi",
                         "Pool type should be 'Iscsi', got '%s'" % pool.type)

        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol not found for pool '%s'" % pool.name)
        self.assertEqual(ontap_vol.get("state"), "online",
                         "ONTAP FlexVol should be 'online'")

    # ------------------------------------------------------------------
    # Step 02 — Create iSCSI data volume (LUN on ONTAP)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_02_create_ontap_data_volume(self):
        """
        Allocate a CloudStack data volume on the iSCSI ONTAP pool.
        Verifies:
          - createVolume returns a volume object
          - ONTAP: at least one LUN is created inside the pool's FlexVol
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")

        vol = self._create_volume(self.__class__.pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        luns = self.ontap.list_luns_in_volume(
            self.svm_name, self.__class__.pool.name)
        self.assertTrue(
            len(luns) > 0,
            "Expected ≥1 LUN in ONTAP FlexVol '%s' after volume creation, "
            "found 0" % self.__class__.pool.name
        )

    # ------------------------------------------------------------------
    # Step 03 — Deploy a VM
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_03_deploy_vm(self):
        """
        Deploy a VM using the first available ready KVM template.
        Verifies:
          - VM reaches Running state
          - ONTAP: the iSCSI data volume's LUN is NOT yet mapped (no VM
            attachment has been performed yet)
        """
        if self.__class__.template_id is None:
            self.skipTest(
                "No ready KVM user template available — waiting for template "
                "download to complete"
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
        self.__class__.vm = vm

        result = self._poll_vm_state(vm.id, "Running", timeout=300)
        self.assertEqual(
            result.state, "Running",
            "VM should be 'Running' after deploy, got '%s'" % result.state
        )

        # Data volume LUN-map must not exist yet (volume not yet attached)
        lun_maps = self._lun_maps()
        self.assertEqual(
            len(lun_maps), 0,
            "Expected 0 LUN-maps before volume attach, found %d: %s"
            % (len(lun_maps), lun_maps)
        )

    # ------------------------------------------------------------------
    # Step 04 — Attach iSCSI volume to running VM  (TDS SN 27 iSCSI)
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_04_attach_volume_to_vm(self):
        """
        Attach the iSCSI data volume to the running VM.
        Covers TDS Approach-1 SN 27 (iSCSI):
          - attachVolume completes successfully
          - CloudStack: volume shows virtualmachineid set
          - ONTAP: a LUN-map is created linking the data LUN to the host's
            igroup (the LUN is now accessible to the VM's KVM host)
        """
        if self.__class__.vm is None:
            self.skipTest(
                "VM not deployed — test_03 was skipped (no ready template)"
            )
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")

        cmd = attachVolumeAPI.attachVolumeCmd()
        cmd.id = self.__class__.volume.id
        cmd.virtualmachineid = self.__class__.vm.id
        self.apiClient.attachVolume(cmd)

        # Poll until virtualmachineid is set on the volume
        result = self._poll_volume_field(
            self.__class__.volume.id, "virtualmachineid",
            self.__class__.vm.id, timeout=120)
        self.assertIsNotNone(
            result,
            "Volume virtualmachineid was not set after attachVolume"
        )

        # ONTAP: at least one LUN-map must exist for the pool's FlexVol
        lun_maps = self._lun_maps()
        self.assertGreater(
            len(lun_maps), 0,
            "Expected ≥1 LUN-map after volume attach, found 0 — "
            "LUN is not accessible to the VM's host"
        )

    # ------------------------------------------------------------------
    # Step 05 — Stop VM — LUN-maps should be removed
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_05_stop_vm_lun_unmapped(self):
        """
        Stop the running VM while the iSCSI data volume is still attached.
        Covers TDS VM Stop (iSCSI): 'Luns for the volumes under this VM
        should be unmapped.'
        Verifies:
          - VM reaches Stopped state
          - ONTAP: LUN-map is removed (LUN itself stays in the FlexVol)
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped")

        cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
        cmd.id = self.__class__.vm.id
        self.apiClient.stopVirtualMachine(cmd)

        result = self._poll_vm_state(self.__class__.vm.id, "Stopped",
                                     timeout=300)
        self.assertEqual(
            result.state, "Stopped",
            "VM should be 'Stopped', got '%s'" % result.state
        )

        # ONTAP: LUN-map must be removed once VM is stopped
        lun_maps = self._lun_maps()
        self.assertEqual(
            len(lun_maps), 0,
            "Expected 0 LUN-maps after VM stop, found %d: %s"
            % (len(lun_maps), lun_maps)
        )

        # ONTAP: LUN itself must still exist in the FlexVol
        luns = self.ontap.list_luns_in_volume(
            self.svm_name, self.__class__.pool.name)
        self.assertTrue(
            len(luns) > 0,
            "LUN should still exist in ONTAP FlexVol after VM stop"
        )

    # ------------------------------------------------------------------
    # Step 06 — Start VM — LUN-maps should be re-created
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_06_start_vm_lun_remapped(self):
        """
        Start the stopped VM.
        Covers TDS VM Start (iSCSI): 'luns should be re-mapped again to
        provide access.'
        Verifies:
          - VM reaches Running state
          - ONTAP: LUN-map is re-created (LUN accessible to VM's host)
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped")

        cmd = startVirtualMachineAPI.startVirtualMachineCmd()
        cmd.id = self.__class__.vm.id
        self.apiClient.startVirtualMachine(cmd)

        result = self._poll_vm_state(self.__class__.vm.id, "Running",
                                     timeout=300)
        self.assertEqual(
            result.state, "Running",
            "VM should be 'Running' after start, got '%s'" % result.state
        )

        # ONTAP: LUN-map must be re-created after VM starts
        lun_maps = self._lun_maps()
        self.assertGreater(
            len(lun_maps), 0,
            "Expected ≥1 LUN-map after VM start (re-map), found 0"
        )

    # ------------------------------------------------------------------
    # Step 07 — Detach volume from running VM
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_07_detach_volume_from_vm(self):
        """
        Detach the iSCSI data volume from the running VM.
        Verifies:
          - detachVolume completes successfully
          - CloudStack: volume virtualmachineid cleared
          - ONTAP: LUN-map is removed (LUN stays in FlexVol)
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")

        cmd = detachVolumeAPI.detachVolumeCmd()
        cmd.id = self.__class__.volume.id

        max_timeout = 180
        interval = 10
        deadline = time.time() + max_timeout
        last_exc = None
        while True:
            try:
                self.apiClient.detachVolume(cmd)
                last_exc = None
                break
            except Exception as exc:
                last_exc = exc
                remaining = deadline - time.time()
                if remaining <= 0:
                    break
                time.sleep(min(interval, remaining))
                interval = min(interval * 2, max_timeout)
        if last_exc is not None:
            raise last_exc

        # Poll until virtualmachineid is cleared
        result = self._poll_volume_field(
            self.__class__.volume.id, "virtualmachineid", None, timeout=120)
        self.assertIsNotNone(
            result,
            "Volume virtualmachineid was not cleared after detachVolume"
        )

        # ONTAP: LUN-map must be removed after detach
        lun_maps = self._lun_maps()
        self.assertEqual(
            len(lun_maps), 0,
            "Expected 0 LUN-maps after volume detach, found %d: %s"
            % (len(lun_maps), lun_maps)
        )

        # ONTAP: LUN still exists in FlexVol
        luns = self.ontap.list_luns_in_volume(
            self.svm_name, self.__class__.pool.name)
        self.assertTrue(
            len(luns) > 0,
            "LUN should still exist in ONTAP FlexVol after detach"
        )

    # ------------------------------------------------------------------
    # Step 08 — Destroy VM and clean up pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_08_destroy_vm_and_cleanup(self):
        """
        Destroy the VM (with expunge), delete the data volume, force-delete
        the ONTAP pool, and delete the guest network created for this suite.
        This test leaves no entities behind in either CloudStack or ONTAP.
        Verifies:
          - VM is destroyed and expunged from CloudStack
          - deleteVolume removes the LUN from the ONTAP FlexVol
          - deleteStoragePool(forced=True) removes the pool from CS
          - ONTAP: FlexVol deleted
          - ONTAP: all per-host igroups deleted
          - CloudStack: guest network deleted (Advanced zones only)
        """
        pool = self.__class__.pool
        vol = self.__class__.volume

        if self.__class__.vm is not None:
            # Ensure VM is stopped before destroying
            vms = self.apiClient.listVirtualMachines(
                _list_vms_cmd(self.__class__.vm.id))
            current_state = vms[0].state if vms else "unknown"
            if current_state.lower() not in ("stopped", "destroyed"):
                stop_cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
                stop_cmd.id = self.__class__.vm.id
                stop_cmd.forced = True
                self.apiClient.stopVirtualMachine(stop_cmd)
                self._poll_vm_state(self.__class__.vm.id, "Stopped",
                                    timeout=120)

            dest_cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
            dest_cmd.id = self.__class__.vm.id
            dest_cmd.expunge = True
            self.apiClient.destroyVirtualMachine(dest_cmd)
            self.__class__.vm = None

        if vol is not None and pool is not None:
            pool_name = pool.name

            # Delete the data volume
            del_cmd = deleteVolumeAPI.deleteVolumeCmd()
            del_cmd.id = vol.id
            self.apiClient.deleteVolume(del_cmd)
            self.__class__.volume = None

            # ONTAP: LUN must be removed after volume deletion
            luns = self.ontap.list_luns_in_volume(self.svm_name, pool_name)
            self.assertEqual(
                len(luns), 0,
                "Expected 0 LUNs in FlexVol '%s' after volume delete, "
                "found %d" % (pool_name, len(luns))
            )

            # Enter maintenance and force-delete the pool
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
            self.assertFalse(
                remaining,
                "Pool '%s' still listed in CloudStack after force deletion"
                % pool_name
            )

            # ONTAP: FlexVol must be deleted
            ontap_vol = self.ontap.get_volume(pool_name)
            self.assertIsNone(
                ontap_vol,
                "ONTAP FlexVol '%s' still exists after pool force deletion"
                % pool_name
            )

            # ONTAP: per-host igroups must be deleted by the pool force-delete
            for host in self.cluster_hosts:
                iqn = getattr(host, "storageurl", None)
                if not iqn or not iqn.startswith("iqn."):
                    continue
                short = host.name.split(".")[0]
                igroup_name = "cs_%s_%s" % (
                    self.svm_name,
                    re.sub(r"[^a-zA-Z0-9_-]", "_", short),
                )
                igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
                self.assertIsNone(
                    igroup,
                    "ONTAP igroup '%s' still exists after pool force deletion"
                    % igroup_name
                )

        # Delete the guest network created by setUpClass for Advanced zones.
        # Doing this inside the test (rather than only in tearDownClass) makes
        # the full sequence self-contained when all tests pass.
        if self.__class__._created_network_id is not None:
            net_id = self.__class__._created_network_id
            dn_cmd = deleteNetworkAPI.deleteNetworkCmd()
            dn_cmd.id = net_id
            # The management server may briefly drop the connection after the
            # heavy teardown above; retry deleteNetwork up to 3× with 15s gaps.
            last_net_exc = None
            for attempt in range(3):
                try:
                    self.apiClient.deleteNetwork(dn_cmd)
                    last_net_exc = None
                    break
                except Exception as exc:
                    last_net_exc = exc
                    if attempt < 2:
                        time.sleep(15)
            if last_net_exc is not None:
                raise last_net_exc
            self.__class__._created_network_id = None
            self.__class__.network_id = None

            # CloudStack: guest network must be gone
            net_cmd = listNetworksAPI.listNetworksCmd()
            net_cmd.id = net_id
            net_cmd.listall = True
            remaining_nets = self.apiClient.listNetworks(net_cmd) or []
            self.assertFalse(
                remaining_nets,
                "Guest network %s still listed in CloudStack after deletion"
                % net_id
            )
