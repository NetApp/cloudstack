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
Sequential workflow integration tests for NetApp ONTAP data volume lifecycle
with a running virtual machine.

Tests are numbered test_01 ... test_08 and must run in that order.  Each step
builds on the shared state established by the previous step.

Workflow:
  01  Create NFS3 primary storage pool on ONTAP
  02  Create a CloudStack data volume on the ONTAP pool
  03  Deploy a VM (template and service offering discovered at setup time)
  04  Attach the ONTAP data volume to the running VM
  05  Stop the VM — export policy stays; volume remains attached in CS
  06  Start the VM — VM Running; volume still attached; FlexVol online
  07  Detach the ONTAP data volume from the VM
  08  Destroy VM; delete ONTAP volume; enter maintenance; delete pool

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster registered in CloudStack with at least one executable template
  - ONTAP SVM with NFS3 service enabled and at least one NFS data LIF
  - ontap.cfg populated with real values

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/test_ontap_vm_volume_attach.py -v

Note: Tests share class-level state (sequential).  Always run the full suite.
"""

import base64
import logging
import random
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
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.lib.base import StoragePool
from marvin.lib.common import list_storage_pools

from ontap_test_base import OntapRestClient, OntapTestBase, _parse_pool_details

logger = logging.getLogger("TestOntapVMVolumeAttach")


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
                 protocol="NFS3", scope="CLUSTER", provider="NetApp ONTAP",
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
                "email": "ontap-vm-vol@test.com",
                "firstname": "ONTAP",
                "lastname": "VMVol",
                "username": "ontap_vm_vol_%d" % random.randint(0, 9999),
                "password": "password",
            },
            TestData.primaryStorage: {
                "name": "OntapVMVol_%d" % random.randint(0, 9999),
                TestData.scope: scope,
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

class TestOntapVMVolumeAttach(OntapTestBase):
    """
    Tests ONTAP data volume lifecycle with a running CloudStack VM.

    All tests are sequential — state is carried on class attributes.
    """

    # ---- extra shared state beyond OntapTestBase -----------------------
    vm = None              # running VirtualMachine
    template_id = None     # KVM template ID discovered at setup
    service_offering_id = None
    network_id = None      # None for Basic zones
    _created_network_id = None  # network created by this suite for Advanced zones

    _vol_name_prefix = "OntapVMVol"

    # ---- setup ---------------------------------------------------------

    @classmethod
    def setUpClass(cls):
        testclient = super(
            TestOntapVMVolumeAttach, cls
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
        scope = pool_cfg.get("storagePoolScope", "CLUSTER")
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")
        tags = nfs3_cfg.get("storagePoolTags", "ontap-nfs3")
        capacitybytes = pool_cfg.get("capacitybytes", None)

        cls.testdata = TestData(
            storage_ip, svm_name, username, password,
            protocol=protocol, scope=scope, provider=provider,
            tags=tags, capacitybytes=capacitybytes,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name

        cls._setup_cloudstack_resources(config, cls.testdata[TestData.account])

        # Discover a suitable user KVM template in the zone (must be fully
        # downloaded; system-type templates are excluded as they cannot be
        # deployed as user VMs).
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
        if kvm_ready:
            cls.template_id = kvm_ready[0].id
        else:
            logger.warning(
                "No ready user KVM template found in zone '%s'. "
                "Tests that deploy VMs will be skipped until a template "
                "finishes downloading." % cls.zone.name
            )
            cls.template_id = None

        # Discover the smallest service offering
        so_cmd = listServiceOfferingsAPI.listServiceOfferingsCmd()
        offerings = cls.apiClient.listServiceOfferings(so_cmd) or []
        assert offerings, "No service offerings available in CloudStack"
        offerings.sort(key=lambda s: getattr(s, "memory", 9999))
        cls.service_offering_id = offerings[0].id

        # Detect zone type; resolve network ID for Advanced zones
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
                no_offerings = cls.apiClient.listNetworkOfferings(no_cmd) or []
                snat_offering = next(
                    (o for o in no_offerings
                     if "SourceNat" in o.name and "Vpc" not in o.name
                     and "NSX" not in o.name and "Netris" not in o.name),
                    no_offerings[0] if no_offerings else None
                )
                if snat_offering:
                    cn_cmd = createNetworkAPI.createNetworkCmd()
                    cn_cmd.zoneid = cls.zone.id
                    cn_cmd.networkofferingid = snat_offering.id
                    cn_cmd.name = "ontap-nfs3-vm-net-%d" % random.randint(
                        0, 9999)
                    cn_cmd.displaytext = "ONTAP NFS3 VM test network"
                    cn_cmd.account = cls.account.name
                    cn_cmd.domainid = cls.domain.id
                    net = cls.apiClient.createNetwork(cn_cmd)
                    cls.network_id = net.id
                    cls._created_network_id = net.id

    @classmethod
    def tearDownClass(cls):
        """Destroy the VM first, then delegate pool/volume cleanup to super."""
        if cls.vm is not None:
            try:
                # Ensure VM is stopped before destroying
                vms = cls.apiClient.listVirtualMachines(
                    _list_vms_cmd(cls.vm.id))
                current_state = vms[0].state if vms else "unknown"
                if current_state.lower() not in ("stopped", "destroyed",
                                                  "expunging", "error"):
                    stop_cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
                    stop_cmd.id = cls.vm.id
                    stop_cmd.forced = True
                    cls.apiClient.stopVirtualMachine(stop_cmd)
                    _wait_for_vm_state(cls.apiClient, cls.vm.id, "Stopped",
                                       timeout=120)
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

        super(TestOntapVMVolumeAttach, cls).tearDownClass()

    # ---- pool creation helper -----------------------------------------

    def _create_pool(self):
        ps = self.testdata[TestData.primaryStorage]
        storage_ip = self.testdata[TestData.ontap][TestData.DETAIL_STORAGE_IP]
        pool_name = "OntapVMVol_%d" % random.randint(0, 99999)

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

    # ---- VM state helpers ----------------------------------------------

    def _poll_vm_state(self, vm_id, target_state, timeout=300, interval=10):
        """Poll listVirtualMachines until the VM reaches target_state."""
        deadline = time.time() + timeout
        current_state = "unknown"
        while time.time() < deadline:
            vms = self.apiClient.listVirtualMachines(
                _list_vms_cmd(vm_id))
            if vms:
                current_state = vms[0].state
                if current_state.lower() == target_state.lower():
                    return vms[0]
            time.sleep(interval)
        self.fail(
            "VM %s did not reach state '%s' within %ds (last: '%s')"
            % (vm_id, target_state, timeout, current_state)
        )

    def _volume_state(self, vol_id):
        """Return the current CloudStack state string for a volume."""
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.id = vol_id
        vols = self.apiClient.listVolumes(cmd)
        return vols[0].state if vols else "unknown"

    # ==================================================================
    # Test steps
    # ==================================================================

    # ------------------------------------------------------------------
    # Step 01 - Create NFS3 ONTAP pool
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_01_create_nfs3_pool(self):
        """
        Create an NFS3 ONTAP primary storage pool.
        Verifies:
          - Pool reaches 'Up' state in CloudStack
          - ONTAP: FlexVol is created and online
        """
        pool = self._create_pool()
        self.__class__.pool = pool

        self.assertEqual(
            pool.state, "Up",
            "Pool state should be 'Up', got '%s'" % pool.state
        )

        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol not created for pool '%s'" % pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online', got '%s'"
            % ontap_vol.get("state")
        )

    # ------------------------------------------------------------------
    # Step 02 - Create CloudStack data volume on ONTAP pool
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_02_create_ontap_data_volume(self):
        """
        Allocate a CloudStack data volume on the ONTAP NFS3 pool.
        Verifies:
          - Volume is created and in 'Allocated' or 'Ready' state
          - ONTAP: FlexVol remains online
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")

        pool = self.__class__.pool
        vol = self._create_volume(pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        vol_state = self._volume_state(vol.id)
        self.assertIn(
            vol_state.lower(), ("allocated", "ready"),
            "Volume should be 'Allocated' or 'Ready', got '%s'" % vol_state
        )

        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol disappeared after data volume creation"
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should still be 'online' after volume creation"
        )

    # ------------------------------------------------------------------
    # Step 03 - Deploy a VM
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_03_deploy_vm(self):
        """
        Deploy a VM using the first available KVM template and smallest
        service offering discovered at setup time.
        Verifies:
          - VM reaches 'Running' state in CloudStack
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")
        if self.__class__.template_id is None:
            self.skipTest(
                "No ready user KVM template available — "
                "waiting for template download to complete"
            )
        self.assertIsNotNone(self.__class__.service_offering_id,
                             "No service offering available — check setup")

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

        vm_obj = self._poll_vm_state(vm.id, "Running", timeout=600)
        self.assertEqual(
            vm_obj.state, "Running",
            "VM should be 'Running', got '%s'" % vm_obj.state
        )

    # ------------------------------------------------------------------
    # Step 04 - Attach ONTAP data volume to the running VM
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_04_attach_volume_to_vm(self):
        """
        Attach the ONTAP data volume to the running VM.
        Verifies:
          - Volume virtualmachineid is set to the VM's ID in CloudStack
            (Note: on ONTAP/NFS shared storage the volume state remains 'Ready';
            attachment is signalled by virtualmachineid being populated)
          - ONTAP: FlexVol remains online
          - ONTAP: NFS3 volume data file created in FlexVol after attach (lazy creation)
          - VM remains 'Running'
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped (no ready template)")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")

        vm = self.__class__.vm
        vol = self.__class__.volume

        cmd = attachVolumeAPI.attachVolumeCmd()
        cmd.id = vol.id
        cmd.virtualmachineid = vm.id
        attached = self.apiClient.attachVolume(cmd)
        self.assertIsNotNone(attached, "attachVolume returned None")

        # On ONTAP/NFS shared storage CloudStack does not transition the volume
        # state to 'In Use' — attachment is indicated by virtualmachineid being
        # set on the volume record.  Poll on that field instead of state.
        deadline = time.time() + 120
        vol_vmid = None
        while time.time() < deadline:
            vols = self.apiClient.listVolumes(_list_vols_cmd(vol.id))
            vol_vmid = getattr(vols[0], "virtualmachineid", None) if vols else None
            if vol_vmid:
                break
            time.sleep(5)

        self.assertEqual(
            vol_vmid, vm.id,
            "Volume should be attached to VM %s after attach, "
            "got virtualmachineid=%s" % (vm.id, vol_vmid)
        )

        # VM must still be Running
        vm_obj = self._poll_vm_state(vm.id, "Running", timeout=30)
        self.assertEqual(vm_obj.state, "Running",
                         "VM should still be 'Running' after volume attach")

        # ONTAP FlexVol must remain online
        pool = self.__class__.pool
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(ontap_vol, "ONTAP FlexVol not found after attach")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after attach"
        )

        # ONTAP: NFS3 uses lazy file creation — the volume data file is
        # materialised on the FlexVol only when CloudStack calls createAsync
        # during attachVolume.  Verify that the file now exists.
        files = self.ontap.list_files_in_volume(pool.name)
        vol_file = next((f for f in files if vol.id in f), None)
        self.assertIsNotNone(
            vol_file,
            "No data file matching volume UUID '%s' found in FlexVol '%s' "
            "after attach; files present: %s" % (vol.id, pool.name, files)
        )

    # ------------------------------------------------------------------
    # Step 05 - Stop VM — export policy must be retained
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_05_stop_vm_export_retained(self):
        """
        Stop the running VM while the NFS3 data volume is still attached.
        Unlike iSCSI (where LUN-maps are removed on VM stop), NFS3 export
        policies are not torn down when a VM stops — the FlexVol stays
        accessible on the same mount.
        Verifies:
          - VM reaches Stopped state
          - ONTAP: FlexVol still online
          - CloudStack: volume virtualmachineid still set (volume stays attached)
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped (no ready template)")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")

        vm = self.__class__.vm
        vol = self.__class__.volume

        cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
        cmd.id = vm.id
        self.apiClient.stopVirtualMachine(cmd)

        result = self._poll_vm_state(vm.id, "Stopped", timeout=300)
        self.assertEqual(
            result.state, "Stopped",
            "VM should be 'Stopped', got '%s'" % result.state
        )

        # ONTAP: FlexVol must remain online — NFS export is not torn down on VM stop
        pool = self.__class__.pool
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' disappeared after VM stop" % pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should remain 'online' after VM stop, "
            "got '%s'" % ontap_vol.get("state")
        )

        # CloudStack: volume must still be attached (virtualmachineid set)
        cmd_list = listVolumesAPI.listVolumesCmd()
        cmd_list.id = vol.id
        vols = self.apiClient.listVolumes(cmd_list)
        vol_vmid = getattr(vols[0], "virtualmachineid", None) if vols else None
        self.assertEqual(
            vol_vmid, vm.id,
            "Volume should still be attached to VM %s after stop, "
            "got virtualmachineid=%s" % (vm.id, vol_vmid)
        )

    # ------------------------------------------------------------------
    # Step 06 - Start VM — volume accessible; FlexVol online
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_06_start_vm_volume_accessible(self):
        """
        Start the stopped VM.
        Verifies:
          - VM reaches Running state
          - ONTAP: FlexVol still online
          - CloudStack: volume virtualmachineid still set (volume remains attached)
          - VM remains 'Running' after start
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped (no ready template)")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")

        vm = self.__class__.vm
        vol = self.__class__.volume

        cmd = startVirtualMachineAPI.startVirtualMachineCmd()
        cmd.id = vm.id
        self.apiClient.startVirtualMachine(cmd)

        result = self._poll_vm_state(vm.id, "Running", timeout=300)
        self.assertEqual(
            result.state, "Running",
            "VM should be 'Running' after start, got '%s'" % result.state
        )

        # ONTAP: FlexVol must remain online after VM start
        pool = self.__class__.pool
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol,
            "ONTAP FlexVol '%s' not found after VM start" % pool.name
        )
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after VM start, "
            "got '%s'" % ontap_vol.get("state")
        )

        # CloudStack: volume must still be attached to the VM
        cmd_list = listVolumesAPI.listVolumesCmd()
        cmd_list.id = vol.id
        vols = self.apiClient.listVolumes(cmd_list)
        vol_vmid = getattr(vols[0], "virtualmachineid", None) if vols else None
        self.assertEqual(
            vol_vmid, vm.id,
            "Volume should still be attached to VM %s after start, "
            "got virtualmachineid=%s" % (vm.id, vol_vmid)
        )

    # ------------------------------------------------------------------
    # Step 07 - Detach ONTAP data volume from the VM
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_07_detach_volume_from_vm(self):
        """
        Detach the ONTAP data volume from the running VM.
        Verifies:
          - Volume state returns to 'Ready' in CloudStack
          - Volume no longer lists the VM's ID
          - VM remains 'Running'
          - ONTAP: FlexVol remains online
          - ONTAP: NFS3 volume data file persists in FlexVol after detach
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped (no ready template)")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")

        vm = self.__class__.vm
        vol = self.__class__.volume

        cmd = detachVolumeAPI.detachVolumeCmd()
        cmd.id = vol.id
        # The hypervisor may briefly mark the device as busy; retry up to 3×.
        last_exc = None
        for attempt in range(3):
            try:
                self.apiClient.detachVolume(cmd)
                last_exc = None
                break
            except Exception as exc:
                last_exc = exc
                if attempt < 2:
                    time.sleep(30)
        if last_exc is not None:
            raise last_exc

        # On ONTAP/NFS shared storage the volume state stays 'Ready' throughout.
        # Poll until virtualmachineid is cleared instead.
        deadline = time.time() + 120
        vol_vmid = "pending"
        while time.time() < deadline:
            vols = self.apiClient.listVolumes(_list_vols_cmd(vol.id))
            vol_vmid = getattr(vols[0], "virtualmachineid", None) if vols else None
            if not vol_vmid:
                break
            time.sleep(5)

        self.assertIsNone(
            vol_vmid,
            "Volume should have no virtualmachineid after detach, got '%s'"
            % vol_vmid
        )

        # VM must still be Running
        vm_obj = self._poll_vm_state(vm.id, "Running", timeout=30)
        self.assertEqual(vm_obj.state, "Running",
                         "VM should still be 'Running' after volume detach")

        # ONTAP FlexVol must remain online
        pool = self.__class__.pool
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol not found after detach")
        self.assertEqual(
            ontap_vol.get("state"), "online",
            "ONTAP FlexVol should be 'online' after detach"
        )

        # ONTAP: NFS3 volume data file must still exist after detach — the file
        # is only removed when deleteVolume is called, not on detach.
        files = self.ontap.list_files_in_volume(pool.name)
        vol_file = next((f for f in files if vol.id in f), None)
        self.assertIsNotNone(
            vol_file,
            "Volume data file for '%s' should persist in FlexVol '%s' after "
            "detach; files present: %s" % (vol.id, pool.name, files)
        )

    # ------------------------------------------------------------------
    # Step 08 - Destroy VM, delete volume, delete pool
    # ------------------------------------------------------------------

    @attr(tags=["vm_volume_workflow"], required_hardware=True)
    def test_08_destroy_vm_and_cleanup(self):
        """
        Destroy the VM, delete the ONTAP data volume, enter maintenance,
        then delete the pool.
        Verifies:
          - VM is destroyed/expunged from CloudStack
          - Volume is deleted from CloudStack
          - ONTAP: NFS3 volume data file removed from FlexVol after deleteVolume
          - Pool is removed from CloudStack
          - ONTAP: FlexVol is deleted after pool removal
          - ONTAP: Export policy is removed after pool removal
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")

        vm = self.__class__.vm
        vol = self.__class__.volume
        pool = self.__class__.pool
        pool_name = pool.name

        # Stop VM if still running
        if vm is not None:
            vms = self.apiClient.listVirtualMachines(_list_vms_cmd(vm.id))
            current_state = vms[0].state.lower() if vms else "unknown"
            if current_state not in ("stopped", "destroyed",
                                     "expunging", "error"):
                stop_cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
                stop_cmd.id = vm.id
                self.apiClient.stopVirtualMachine(stop_cmd)
                self._poll_vm_state(vm.id, "Stopped", timeout=300)

            dest_cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
            dest_cmd.id = vm.id
            dest_cmd.expunge = True
            self.apiClient.destroyVirtualMachine(dest_cmd)
            self.__class__.vm = None

        # Delete the ONTAP data volume
        if vol is not None:
            vol_id = vol.id
            cmd = deleteVolumeAPI.deleteVolumeCmd()
            cmd.id = vol_id
            self.apiClient.deleteVolume(cmd)
            self.__class__.volume = None

            # ONTAP: NFS3 volume data file must be removed from the FlexVol
            # after deleteVolume (CloudStack/libvirt deletes the file from the
            # NFS mount as part of the destroy workflow).
            files = self.ontap.list_files_in_volume(pool_name)
            vol_file = next((f for f in files if vol_id in f), None)
            self.assertIsNone(
                vol_file,
                "Volume data file for '%s' should be gone from FlexVol '%s' "
                "after deleteVolume; files still present: %s"
                % (vol_id, pool_name, files)
            )

        # Enter maintenance then delete the pool
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
        self.assertFalse(remaining,
                         "Pool still listed in CloudStack after deletion")

        # ONTAP: FlexVol must be deleted
        ontap_vol = self.ontap.get_volume(pool_name)
        self.assertIsNone(
            ontap_vol,
            "ONTAP FlexVol '%s' still exists after pool deletion" % pool_name
        )

        # ONTAP: Export policy must be removed
        ep_name = "cs-%s-%s" % (self.svm_name, pool_name)
        ep = self.ontap.get_export_policy(ep_name)
        self.assertIsNone(
            ep,
            "ONTAP export policy '%s' still exists after pool deletion"
            % ep_name
        )


# ---------------------------------------------------------------------------
# Module-level helpers (used in tearDownClass and test helpers)
# ---------------------------------------------------------------------------

def _list_vms_cmd(vm_id):
    cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
    cmd.id = vm_id
    return cmd


def _list_vols_cmd(vol_id):
    cmd = listVolumesAPI.listVolumesCmd()
    cmd.id = vol_id
    return cmd


def _wait_for_vm_state(api_client, vm_id, target_state, timeout=120,
                       interval=5):
    """Blocking wait for a VM to reach target_state (used in tearDownClass)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        vms = api_client.listVirtualMachines(_list_vms_cmd(vm_id))
        if vms and vms[0].state.lower() == target_state.lower():
            return
        time.sleep(interval)
