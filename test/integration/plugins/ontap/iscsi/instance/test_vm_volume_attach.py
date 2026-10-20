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

Tests are numbered test_01 ... test_27, plus test_03a and test_03b, and must
run in that order.  Each step builds on the shared state established by the
previous step.

The VM is deployed with a compute offering whose storage tags match the pool,
so its ROOT volume lands on the ONTAP pool and exercises the primary
template cache (``cs_tmpl_<templateId>`` LUN: seed / reuse / survive VM delete)
alongside the data-volume resize and snapshot workflow.  LUN-map assertions are
filtered to the data volume's LUN, so the ROOT and cache LUNs in the same
FlexVol do not affect them.

Workflow:
  01  Create iSCSI primary storage pool on ONTAP (tagged <storagePoolTags>-tmpl-cache)
  02  Create and grow a detached CloudStack data volume (LUN on ONTAP)
  03  Deploy a VM — ROOT on ONTAP; seeds the cs_tmpl_* cache LUN
  03a Deploy a second VM — reuses the cache LUN (one more ROOT clone LUN only)
  03b Expunge the second VM — its ROOT LUN is removed; cache LUN survives
  04  Attach iSCSI data volume to running VM (LUN-map created)
  05  Stop VM — LUN-map for attached volume is removed from ONTAP
  06  Grow the attached volume and ONTAP LUN while the VM is stopped
  07  Verify shrinking the ONTAP LUN is rejected without changing its size
  08  Start VM — LUN-map is re-created on ONTAP
  09  Open an SSH port forward to the VM; write ROOT and DATA files
  10  Snapshot the ROOT volume of the running VM
  11  Snapshot the DATA volume of the running VM (kept for step 14)
  12  Revert a volume snapshot while the VM is Running — rejected
  13  ROOT volume revert: write before-snapshot, snapshot, write
      after-snapshot, revert, file is back to before-snapshot
  14  DATA: snapshot again, revert to the step-11 snapshot then to this
      one, then delete both while the VM is still running
  15  Write before-s1; VM snapshot of the running VM (kept for step 17)
  16  Write before-s2, stop, VM snapshot (kept for step 17)
  17  Revert to the step-15 snapshot, then to the step-16 snapshot, then
      delete both while the VM is still running
  18  Two VM snapshot requests for the same VM at once — both succeed
  19  Deploy a second VM whose ROOT LUN is on the same pool
  20  Revert VM-A's ROOT; VM-B's file is unchanged
  21  VM snapshots of both VMs at the same time
  22  Volume snapshot of VM-A while VM-B's ROOT reverts
  23  VM snapshot of VM-A while VM-B reverts to its VM snapshot
  24  Detach data volume from running VM — LUN-map removed
  25  Grow the detached data volume
  26  Expunge the second VM while it still has a VM snapshot
  27  Destroy VM (cache LUN still survives), delete data volume, delete pool

Snapshot steps only create and verify; tearDown deletes every snapshot the
step created and waits for its FlexVol snapshot to go.  Step 11 keeps its
DATA snapshot so step 14 can revert to it and then delete both DATA
snapshots while the VM is running.  Steps 15 and 16 keep their VM snapshots
so step 17 can revert to them and then delete both while the VM is running.

Guest login for the CentOS 5.5 template comes from ontap.cfg
(cloudstack.templateUsername / templatePassword); see guest_ssh.py.

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
import threading
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
    listVirtualMachines as listVirtualMachinesAPI,
    listVolumes as listVolumesAPI,
    startVirtualMachine as startVirtualMachineAPI,
    stopVirtualMachine as stopVirtualMachineAPI,
)
from marvin.lib.base import ServiceOffering, Snapshot, StoragePool, VmSnapshot, Volume
from marvin.lib.common import list_storage_pools

from guest_ssh import GuestSsh, guest_credentials, new_token, open_ssh_port
from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config
from helpers import template_cache_util as tcu
from helpers.template_cache_base import (
    find_ready_kvm_template,
    tagged_compute_offering_data,
    template_cache_capacity_bytes,
    template_cache_tags,
)

logger = logging.getLogger("TestOntapVMVolumeAttachISCSI")

TAG = "iscsi_vm_workflow"


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


def _run_concurrent(*callables):
    """Start all callables at the same moment; return [(result, exception)]."""
    results = [(None, None)] * len(callables)
    barrier = threading.Barrier(len(callables))

    def worker(index, fn):
        try:
            barrier.wait(timeout=60)
            results[index] = (fn(), None)
        except Exception as exc:
            results[index] = (None, exc)

    threads = [threading.Thread(target=worker, args=(i, fn))
               for i, fn in enumerate(callables)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def _is_rejected(exc):
    """True when CloudStack or ONTAP refused the call because another one was in progress."""
    text = str(exc).lower()
    return any(token in text for token in (
        "internal error",
        "failed to create snapshot",
        "failed to create instance snapshot",
        "failed to revert",
        "another operation",
        "other active",
        "try again later",
        "in progress",
        "busy",
    ))


def _snapshot_and_revert_with_retry(snapshot_fn, revert_fn, attempts=3, pause=15):
    """Run a snapshot and a revert together.

    A call that is rejected is retried on its own. A call that already
    succeeded is not repeated. Returns the snapshot from snapshot_fn.
    """
    snap = None
    reverted = False
    last_errors = []
    for attempt in range(1, attempts + 1):
        jobs = []
        if snap is None:
            jobs.append(("snapshot", snapshot_fn))
        if not reverted:
            jobs.append(("revert", revert_fn))
        results = _run_concurrent(*(fn for _, fn in jobs))
        last_errors = []
        retryable = True
        for (kind, _), (result, exc) in zip(jobs, results):
            if exc is None:
                if kind == "snapshot":
                    snap = result
                else:
                    reverted = True
                continue
            last_errors.append("%s: %s" % (kind, exc))
            if not _is_rejected(exc):
                retryable = False
        if snap is not None and reverted:
            return snap
        if not retryable or attempt == attempts:
            break
        logger.warning("snapshot/revert rejected, retry %d/%d: %s",
                       attempt, attempts, last_errors)
        time.sleep(pause)
    raise AssertionError(
        "Snapshot-while-revert failed after %d attempt(s): %s"
        % (attempt, last_errors))


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
    # Used when ontap.cfg has neither templateCacheCapacitybytes nor capacitybytes.
    DEFAULT_POOL_SIZE = 50 * 1024 ** 3

    def __init__(self, storage_ip, svm_name, username, password,
                 scope="CLUSTER", provider="NetApp ONTAP",
                 tags="ontap-iscsi", capacitybytes=None):
        if not capacitybytes:
            capacitybytes = TestData.DEFAULT_POOL_SIZE
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
    Tests iSCSI ONTAP data volume lifecycle with a running CloudStack VM whose
    ROOT volume is on the ONTAP pool (primary template cache).
    All tests are sequential — state is carried on class attributes.
    """

    PROTOCOL = "ISCSI"

    # ---- extra shared state beyond OntapTestBase -----------------------
    vm = None
    vm2 = None             # second VM used only to verify template-cache reuse
    vm2_baseline_luns = None  # non-cache LUN count before the second deploy
    template_id = None
    template_db_id = None  # numeric vm_template.id (template_spool_ref key)
    pool_db_id = None      # numeric storage_pool.id (template_spool_ref key)
    service_offering_id = None
    network_id = None
    _created_network_id = None  # network created by this suite for Advanced zones
    initial_volume_size = None
    target_volume_size = None
    vm_ssh = None               # (public_ip, public_port) forwarded to vm:22
    vm2_ssh = None
    vm_snap_running = None      # step 15 snapshot, reused by step 17
    vm_snap_stopped = None      # step 16 snapshot, reused by step 17
    token_s1 = None
    token_s2 = None
    kept_ontap_snaps = None
    data_snap_older = None      # step 11 DATA snapshot, reused by step 14
    data_token_older = None
    kept_data_ontap_snaps = None
    guest_user = "root"
    guest_password = "password"

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
        # Dedicated tag shared by the pool and the compute offering so the
        # VM's ROOT volume is placed on this ONTAP pool (template cache path).
        tags = template_cache_tags(iscsi_cfg, "iscsi")
        capacitybytes = template_cache_capacity_bytes(pool_cfg)

        cls.testdata = TestData(
            storage_ip, svm_name, username, password,
            scope=scope, provider=provider, tags=tags,
            capacitybytes=capacitybytes,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name

        cls._setup_cloudstack_resources(config, cls.testdata[TestData.account])

        cls.guest_user, cls.guest_password = guest_credentials(config)

        # Discover a ready user KVM template (exclude SYSTEM type)
        template = find_ready_kvm_template(cls.apiClient, cls.zone.id)
        cls.template_id = template.id if template is not None else None
        if cls.template_id is None:
            logger.warning(
                "No ready KVM user template found — VM tests will be skipped."
            )
        else:
            cls.template_db_id = tcu.get_db_id(
                cls.dbConnection, "vm_template", cls.template_id
            )

        service_offering = ServiceOffering.create(
            cls.apiClient, tagged_compute_offering_data(tags, "OntapISCSIVMSO")
        )
        cls._cleanup.append(service_offering)
        cls.service_offering_id = service_offering.id
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
        Safety-net cleanup: destroy VMs if still alive, delete the guest
        network created for Advanced zones (if not already deleted by test_27),
        then delegate pool/volume/account cleanup to the base class.
        """
        cls._delete_kept_data_snapshot()
        cls._delete_kept_vm_snapshots()
        for vm in (cls.vm2, cls.vm):
            if vm is None:
                continue
            try:
                cls._stop_and_expunge_vm(vm.id)
            except Exception as e:
                logger.warning("tearDownClass: could not stop/destroy VM %s: "
                               "%s" % (vm.id, e))
                try:
                    dest_cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
                    dest_cmd.id = vm.id
                    dest_cmd.expunge = True
                    cls.apiClient.destroyVirtualMachine(dest_cmd)
                except Exception as de:
                    logger.warning("tearDownClass: could not destroy VM %s: "
                                   "%s" % (vm.id, de))
        cls.vm2 = None
        cls.vm = None

        # Delete the guest network created for this account in Advanced zones.
        # test_27 deletes it on the happy path; this is the fallback for
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

    @classmethod
    def _stop_and_expunge_vm(cls, vm_id):
        """Force-stop the VM if needed, then destroy it with expunge."""
        vms = cls.apiClient.listVirtualMachines(_list_vms_cmd(vm_id))
        state = vms[0].state if vms else "unknown"
        if state.lower() not in ("stopped", "destroyed",
                                 "expunging", "error"):
            stop_cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
            stop_cmd.id = vm_id
            stop_cmd.forced = True
            cls.apiClient.stopVirtualMachine(stop_cmd)
            _wait_for_vm_state(cls.apiClient, vm_id, "Stopped", timeout=120)
        dest_cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
        dest_cmd.id = vm_id
        dest_cmd.expunge = True
        cls.apiClient.destroyVirtualMachine(dest_cmd)

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

    def _data_lun_name(self, volume=None):
        """
        Full ONTAP path of the LUN backing the CloudStack data volume.

        UnifiedSANStrategy names the LUN after the CloudStack volume with
        hyphens replaced by underscores, inside the pool's FlexVol.
        """
        volume = volume or self.__class__.volume
        if volume is None or self.__class__.pool is None:
            return None
        return "/vol/%s/%s" % (self.__class__.pool.name,
                               volume.name.replace("-", "_"))

    def _lun_maps(self, volume=None):
        """
        Return LUN-maps belonging to the data volume's LUN only.

        The same FlexVol also holds the VM's ROOT LUN whenever the allocator
        places the root disk on this pool, so the unfiltered map list is not a
        measure of the data volume's accessibility.
        """
        lun_name = self._data_lun_name(volume)
        if lun_name is None:
            return []
        return [m for m in self.ontap.list_lun_maps_for_volume(
                    self.svm_name, self.__class__.pool.name)
                if m.get("lun", {}).get("name") == lun_name]

    def _data_luns(self, volume=None):
        """Return LUN records for the data volume's LUN only."""
        lun_name = self._data_lun_name(volume)
        if lun_name is None:
            return []
        return [lun for lun in self.ontap.list_luns_in_volume(
                    self.svm_name, self.__class__.pool.name)
                if lun.get("name") == lun_name]


    def _data_lun_size(self):
        """Return the ONTAP data LUN size in bytes."""
        lun_name = self._data_lun_name()
        lun = self.ontap.get_lun(self.svm_name, lun_name)
        self.assertIsNotNone(
            lun, "ONTAP LUN '%s' was not found" % lun_name)
        size = int(lun.get("space", {}).get("size", 0))
        self.assertGreater(
            size, 0, "ONTAP LUN '%s' did not report space.size" % lun_name)
        return size

    # ---- snapshot step helpers (steps 09-23) ---------------------------

    def setUp(self):
        super(TestOntapVMVolumeAttachISCSI, self).setUp()
        self._volume_snapshot_ids = []
        self._vm_snapshot_ids = []
        self._extra_volume_ids = []
        self._created_ontap_snaps = set()

    def tearDown(self):
        """
        Delete the snapshots and extra volumes a step created, then check
        their FlexVol snapshots are gone from ONTAP.
        """
        for snap_id in self._vm_snapshot_ids:
            try:
                VmSnapshot.deleteVMSnapshot(self.apiClient, snap_id)
            except Exception as exc:
                logger.warning("tearDown: VM snapshot %s: %s" % (snap_id, exc))
        for snap_id in self._volume_snapshot_ids:
            try:
                Snapshot({"id": snap_id}).delete(self.apiClient)
            except Exception as exc:
                logger.warning("tearDown: volume snapshot %s: %s" % (snap_id, exc))
        for vol_id in self._extra_volume_ids:
            try:
                cmd = deleteVolumeAPI.deleteVolumeCmd()
                cmd.id = vol_id
                self.apiClient.deleteVolume(cmd)
            except Exception as exc:
                logger.warning("tearDown: volume %s: %s" % (vol_id, exc))
        if self._created_ontap_snaps and not self._wait_ontap_snaps_gone(
                self._created_ontap_snaps, fail=False):
            logger.warning("tearDown: FlexVol snapshots still present: %s"
                           % sorted(self._created_ontap_snaps))
        super(TestOntapVMVolumeAttachISCSI, self).tearDown()

    @classmethod
    def _destroy_second_vm(cls):
        if cls.vm2 is None:
            return
        try:
            cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
            cmd.id = cls.vm2.id
            cmd.expunge = True
            cls.apiClient.destroyVirtualMachine(cmd)
        except Exception as exc:
            logger.warning("Could not destroy second VM %s: %s" % (cls.vm2.id, exc))
        cls.vm2 = None
        cls.vm2_ssh = None

    @classmethod
    def _delete_kept_vm_snapshots(cls):
        """Delete the step 15/16 snapshots when step 17 did not take them over."""
        for snap in (cls.vm_snap_running, cls.vm_snap_stopped):
            if snap is None:
                continue
            try:
                VmSnapshot.deleteVMSnapshot(cls.apiClient, snap.id)
            except Exception as exc:
                logger.warning("tearDownClass: VM snapshot %s: %s" % (snap.id, exc))
        cls.vm_snap_running = None
        cls.vm_snap_stopped = None
        cls.token_s1 = None
        cls.token_s2 = None
        cls.kept_ontap_snaps = set()

    @classmethod
    def _delete_kept_data_snapshot(cls):
        """Delete the step 11 DATA snapshot when step 14 did not take it over."""
        snap = cls.data_snap_older
        if snap is None:
            return
        try:
            Snapshot({"id": snap.id}).delete(cls.apiClient)
        except Exception as exc:
            logger.warning("tearDownClass: volume snapshot %s: %s" % (snap.id, exc))
        cls.data_snap_older = None
        cls.data_token_older = None
        cls.kept_data_ontap_snaps = set()

    def _keep_data_snapshot(self, snap, ontap_names, token):
        """Leave the step 11 DATA snapshot in place for step 14."""
        if snap.id in self._volume_snapshot_ids:
            self._volume_snapshot_ids.remove(snap.id)
        names = set(ontap_names)
        self._created_ontap_snaps -= names
        self.__class__.data_snap_older = snap
        self.__class__.data_token_older = token
        self.__class__.kept_data_ontap_snaps = names

    def _adopt_kept_data_snapshot(self):
        """Hand the step 11 DATA snapshot to step 14."""
        snap = self.__class__.data_snap_older
        token = self.__class__.data_token_older
        if snap is None or not token:
            self.skipTest("DATA snapshot from test_11 is required")
        ontap_names = set(self.__class__.kept_data_ontap_snaps or [])
        self._volume_snapshot_ids.append(snap.id)
        self._created_ontap_snaps |= ontap_names
        self.__class__.data_snap_older = None
        self.__class__.data_token_older = None
        self.__class__.kept_data_ontap_snaps = set()
        return snap, token, ontap_names

    def _delete_volume_snapshot_while_running(self, vm, snap, ontap_names, token):
        """Delete a volume snapshot while its VM is still running."""
        Snapshot({"id": snap.id}).delete(self.apiClient)
        if snap.id in self._volume_snapshot_ids:
            self._volume_snapshot_ids.remove(snap.id)
        names = set(ontap_names)
        self._created_ontap_snaps -= names
        self._wait_until_gone(
            lambda: self._volume_snapshot_listed(snap.id),
            "Volume snapshot %s still listed after delete" % snap.id)
        self._wait_ontap_snaps_gone(names)
        self.assertEqual(self._vm_state(vm.id), "Running")
        self._assert_marker(token, disk="DATA", msg="after volume snapshot delete")

    def _delete_vm_snapshot_while_running(self, vm, snap, ontap_names, token):
        """Delete a VM snapshot while that VM is still running."""
        VmSnapshot.deleteVMSnapshot(self.apiClient, snap.id)
        if snap.id in self._vm_snapshot_ids:
            self._vm_snapshot_ids.remove(snap.id)
        names = set(ontap_names)
        self._created_ontap_snaps -= names
        self._wait_until_gone(
            lambda: any(item.id == snap.id for item in self._vm_snapshots_of(vm.id)),
            "VM snapshot %s still listed after delete" % snap.id)
        self._wait_ontap_snaps_gone(names)
        self.assertEqual(self._vm_state(vm.id), "Running")
        self._assert_marker(token, msg="after VM snapshot delete")

    def _ontap_names_for_snap(self, names, snap, kind):
        prefix = "cs" if kind == "volume" else "vm"
        table = "snapshots" if kind == "volume" else "vm_snapshots"
        db_id = tcu.get_db_id(self.dbConnection, table, snap.id)
        suffix = "_%s%s" % (prefix, db_id)
        return {name for name in names if str(name).endswith(suffix)}

    def _keep_vm_snapshot(self, snap, ontap_names, which):
        """Leave this VM snapshot in place for step 17."""
        if snap.id in self._vm_snapshot_ids:
            self._vm_snapshot_ids.remove(snap.id)
        names = set(ontap_names)
        self._created_ontap_snaps -= names
        kept = self.__class__.kept_ontap_snaps
        if kept is None:
            kept = set()
            self.__class__.kept_ontap_snaps = kept
        kept.update(names)
        setattr(self.__class__, which, snap)

    def _adopt_kept_vm_snapshots(self):
        """Hand the step 15 and 16 snapshots to this step's tearDown."""
        s1 = self.__class__.vm_snap_running
        s2 = self.__class__.vm_snap_stopped
        token_a = self.__class__.token_s1
        token_b = self.__class__.token_s2
        if s1 is None or s2 is None or not token_a or not token_b:
            self.skipTest("VM snapshots from test_15 and test_16 are required")
        ontap_names = set(self.__class__.kept_ontap_snaps or [])
        self._vm_snapshot_ids.extend([s1.id, s2.id])
        self._created_ontap_snaps |= ontap_names
        self.__class__.vm_snap_running = None
        self.__class__.vm_snap_stopped = None
        self.__class__.token_s1 = None
        self.__class__.token_s2 = None
        self.__class__.kept_ontap_snaps = set()
        return s1, s2, token_a, token_b, ontap_names

    def _require_snapshot_vm(self, ssh=True):
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped (no ready template)")
        if ssh and self.__class__.vm_ssh is None:
            self.skipTest("Guest SSH not set up — test_09 must pass first")
        self._start_vm(self.__class__.vm.id)
        return self.__class__.vm

    def _require_second_vm(self):
        self._require_snapshot_vm()
        if self.__class__.vm2 is None or self.__class__.vm2_ssh is None:
            self.skipTest("Second VM not deployed — test_19 must pass first")
        self._start_vm(self.__class__.vm2.id)
        return self.__class__.vm2

    def _vm_state(self, vm_id):
        vms = self.apiClient.listVirtualMachines(_list_vms_cmd(vm_id))
        return vms[0].state if vms else None

    def _stop_vm(self, vm_id):
        if (self._vm_state(vm_id) or "").lower() == "stopped":
            return
        cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
        cmd.id = vm_id
        cmd.forced = True
        self.apiClient.stopVirtualMachine(cmd)
        self._poll_vm_state(vm_id, "Stopped", timeout=600)

    def _start_vm(self, vm_id):
        if (self._vm_state(vm_id) or "").lower() == "running":
            return
        cmd = startVirtualMachineAPI.startVirtualMachineCmd()
        cmd.id = vm_id
        self.apiClient.startVirtualMachine(cmd)
        self._poll_vm_state(vm_id, "Running", timeout=900)

    def _root_volume(self, vm_id):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.virtualmachineid = vm_id
        cmd.type = "ROOT"
        cmd.listall = True
        vols = self.apiClient.listVolumes(cmd) or []
        self.assertTrue(vols, "No ROOT volume found for VM %s" % vm_id)
        return vols[0]

    def _guest(self, which="vm"):
        host, port = getattr(self.__class__, "%s_ssh" % which)
        return GuestSsh(host, port, self.guest_user, self.guest_password)

    def _write_marker(self, token, which="vm", disks=("ROOT",)):
        with self._guest(which) as guest:
            if "ROOT" in disks:
                guest.write_root_marker(token)
            if "DATA" in disks:
                guest.write_data_marker(token)

    def _assert_marker(self, token, which="vm", disk="ROOT", msg=""):
        with self._guest(which) as guest:
            found = guest.read_data_marker() if disk == "DATA" else guest.read_root_marker()
        self.assertEqual(found, token, "%s: %s marker on %s is %r, expected %r"
                         % (msg, disk, which, found, token))

    def _ontap_snap_names(self):
        return {s.get("name") for s in self.ontap.list_snapshots(self.__class__.pool.name)}

    def _assert_new_ontap_snaps(self, before, snaps, kind, msg):
        """Return the FlexVol snapshot created for each CloudStack snapshot.

        A volume snapshot is stored as ``..._cs<dbId>`` and a VM snapshot as
        ``..._vm<dbId>``. ONTAP snapshots from its own schedule, such as
        ``hourly.*`` or ``Anti_ransomware_*``, are ignored.
        """
        new = self._ontap_snap_names() - before
        prefix = "cs" if kind == "volume" else "vm"
        table = "snapshots" if kind == "volume" else "vm_snapshots"
        matched = set()
        missing = []
        for snap in snaps:
            db_id = tcu.get_db_id(self.dbConnection, table, snap.id)
            suffix = "_%s%s" % (prefix, db_id)
            hits = sorted(name for name in new if name.endswith(suffix))
            if len(hits) != 1:
                missing.append("%s (suffix %s, matches %s)" % (snap.id, suffix, hits))
            else:
                matched.add(hits[0])
        self.assertFalse(
            missing,
            "%s: FlexVol snapshot missing for %s; new FlexVol snapshots: %s"
            % (msg, missing, sorted(new)))
        ignored = new - matched
        if ignored:
            logger.info("%s: ignoring FlexVol snapshots not created by this step: %s",
                        msg, sorted(ignored))
        self._created_ontap_snaps |= matched
        return matched

    def _assert_ontap_snaps_present(self, names, msg):
        missing = set(names) - self._ontap_snap_names()
        self.assertFalse(missing, "%s: FlexVol snapshots missing: %s" % (msg, sorted(missing)))

    def _wait_ontap_snaps_gone(self, names, timeout=180, fail=True):
        names = set(names)
        deadline = time.time() + timeout
        left = names
        while time.time() < deadline:
            left = names & self._ontap_snap_names()
            if not left:
                return True
            time.sleep(5)
        if fail:
            self.fail("FlexVol snapshots %s still present after %ds" % (sorted(left), timeout))
        return False

    def _take_volume_snapshot(self, volume_id):
        snap = Snapshot.create(self.apiClient, volume_id,
                               account=self.account.name, domainid=self.domain.id)
        self._volume_snapshot_ids.append(snap.id)
        self.assertEqual(snap.state, "BackedUp",
                         "Volume snapshot %s is %s, expected BackedUp" % (snap.id, snap.state))
        return snap

    def _take_vm_snapshot(self, vm_id):
        snap = VmSnapshot.create(self.apiClient, vm_id, snapshotmemory="false",
                                 name="ontap_%d" % random.randint(0, 999999))
        self._vm_snapshot_ids.append(snap.id)
        self.assertEqual(snap.state, "Ready",
                         "VM snapshot %s is %s, expected Ready" % (snap.id, snap.state))
        return snap

    def _vm_snapshots_of(self, vm_id):
        return VmSnapshot.list(self.apiClient, virtualmachineid=vm_id, listall=True) or []

    def _volume_snapshot_listed(self, snap_id):
        listed = Snapshot.list(self.apiClient, id=snap_id, listall=True) or []
        return any(getattr(item, "id", None) == snap_id for item in listed)

    def _wait_until_gone(self, still_there, msg, timeout=180):
        deadline = time.time() + timeout
        while time.time() < deadline and still_there():
            time.sleep(5)
        self.assertFalse(still_there(), msg)

    def _volume_revert_flow(self, disk):
        """Write A, snapshot, write B, stop, revert, start; expect A."""
        vm = self._require_snapshot_vm()
        volume_id = (self.__class__.volume.id if disk == "DATA"
                     else self._root_volume(vm.id).id)
        token_a = new_token("before-volume-snapshot")
        token_b = new_token("after-volume-snapshot")
        self._write_marker(token_a, disks=(disk,))
        before = self._ontap_snap_names()
        snap = self._take_volume_snapshot(volume_id)
        new = self._assert_new_ontap_snaps(before, [snap], "volume", "%s volume snapshot" % disk)
        self._write_marker(token_b, disks=(disk,))

        self._stop_vm(vm.id)
        Volume.revertToSnapshot(self.apiClient, snap.id)
        self._assert_ontap_snaps_present(new, "after revert")
        self._start_vm(vm.id)
        self._assert_marker(token_a, disk=disk,
                            msg="after revert (write after snapshot was %s)" % token_b)

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
          - Pool reaches 'Up' state; type is 'OntapiSCSI'
          - ONTAP: FlexVol is online
          - ONTAP: igroup exists for every host in the cluster that has an IQN
        """
        pool = self._create_pool()
        self.__class__.pool = pool
        self.__class__.pool_db_id = tcu.get_db_id(
            self.dbConnection, "storage_pool", pool.id
        )

        self.assertEqual(pool.state, "Up",
                         "Pool state should be 'Up', got '%s'" % pool.state)
        self.assertEqual(pool.type, "OntapiSCSI",
                         "Pool type should be 'OntapiSCSI', got '%s'" % pool.type)

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
        Allocate and grow a detached CloudStack data volume on the iSCSI
        ONTAP pool.
        Verifies:
          - createVolume returns a volume object
          - ONTAP: at least one LUN is created inside the pool's FlexVol
          - detached resize updates CloudStack API, DB, and LUN space.size
        """
        self.assertIsNotNone(self.__class__.pool,
                             "Pool absent — test_01 must pass first")

        vol = self._create_volume(self.__class__.pool.id)
        self.__class__.volume = vol
        self.assertIsNotNone(vol, "createVolume returned None")

        luns = self._data_luns()
        self.assertTrue(
            len(luns) > 0,
            "Expected the data volume's LUN '%s' in ONTAP FlexVol '%s' after "
            "volume creation, found none"
            % (self._data_lun_name(), self.__class__.pool.name)
        )

        volume = self._poll_volume_size(vol.id, int(vol.size))
        detached_target = (
            (int(volume.size) // (1024 ** 3)) + 1
        ) * (1024 ** 3)
        self._resize_volume(vol.id, detached_target // (1024 ** 3))
        self._poll_volume_size(vol.id, detached_target)
        self.assertEqual(
            self._get_db_volume_size(vol.id), detached_target,
            "DB size does not match detached iSCSI grow target")
        self.assertEqual(
            self._data_lun_size(), detached_target,
            "ONTAP LUN size does not match detached grow target")


    # ------------------------------------------------------------------
    # Step 03 — Deploy a VM
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_03_deploy_vm(self):
        """
        Deploy a VM using the first available ready KVM template and the
        compute offering tagged to match the ONTAP pool.
        Verifies:
          - VM reaches Running state
          - ROOT volume is placed on the ONTAP pool
          - template_spool_ref for pool + template is Ready / DOWNLOADED
            (local_path = cache LUN uuid)
          - ONTAP: exactly one cache LUN /vol/<flexVol>/cs_tmpl_<templateId>
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

        result = self._poll_vm_state(vm.id, "Running")
        self.assertEqual(
            result.state, "Running",
            "VM should be 'Running' after deploy, got '%s'" % result.state
        )

        pool = self.__class__.pool
        self._assert_root_on_pool(vm.id, pool)
        spool = self._wait_for_ready_spool_ref(self.__class__.pool_db_id)
        self._assert_cache_on_ontap(pool, spool)

        # Data volume LUN-map must not exist yet (volume not yet attached)
        lun_maps = self._lun_maps()
        self.assertEqual(
            len(lun_maps), 0,
            "Expected 0 data-volume LUN-maps before volume attach, found %d: %s"
            % (len(lun_maps), lun_maps)
        )

    # ------------------------------------------------------------------
    # Step 03a — Deploy a second VM — reuses the template cache LUN
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_03a_deploy_second_vm_reuses_template_cache(self):
        """
        Deploy a second VM from the same template and offering.
        Verifies:
          - VM reaches Running; its ROOT volume is on the ONTAP pool
          - Still exactly one template_spool_ref (cache reused, not re-seeded)
          - ONTAP: still exactly one cs_tmpl_* cache LUN
          - ONTAP: exactly one additional non-cache LUN (the VM-2 ROOT clone)
        """
        self.assertIsNotNone(self.__class__.vm,
                             "VM absent — test_03 must pass first")
        pool = self.__class__.pool
        baseline = self._count_non_cache_luns(pool)
        self.__class__.vm2_baseline_luns = baseline

        cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
        cmd.zoneid = self.zone.id
        cmd.templateid = self.__class__.template_id
        cmd.serviceofferingid = self.__class__.service_offering_id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        if self.__class__.network_id:
            cmd.networkids = self.__class__.network_id

        vm2 = self.apiClient.deployVirtualMachine(cmd)
        self.assertIsNotNone(vm2, "deployVirtualMachine returned None")
        self.__class__.vm2 = vm2
        self._poll_vm_state(vm2.id, "Running")

        self._assert_root_on_pool(vm2.id, pool)
        spool = self._assert_single_ready_spool_ref(self.__class__.pool_db_id)
        self._assert_cache_on_ontap(pool, spool)
        self.assertEqual(
            self._count_non_cache_luns(pool), baseline + 1,
            "Expected one additional non-cache LUN for the VM-2 ROOT clone",
        )

    # ------------------------------------------------------------------
    # Step 03b — Expunge the second VM — template cache LUN survives
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_03b_expunge_second_vm_template_cache_survives(self):
        """
        Destroy (expunge) the second VM. The primary template cache is only
        removed by storage GC / pool deletion, never by VM lifecycle.
        Verifies:
          - ONTAP: the VM-2 ROOT LUN is removed (non-cache LUN count back to
            the pre-deploy baseline)
          - template_spool_ref is still Ready / DOWNLOADED
          - ONTAP: the cs_tmpl_* cache LUN is still present
        """
        self.assertIsNotNone(self.__class__.vm2,
                             "Second VM absent — test_03a must pass first")
        pool = self.__class__.pool

        self._stop_and_expunge_vm(self.__class__.vm2.id)
        self.__class__.vm2 = None

        self._wait_for_non_cache_lun_count(
            pool, self.__class__.vm2_baseline_luns)
        spool = self._assert_single_ready_spool_ref(self.__class__.pool_db_id)
        self._assert_cache_on_ontap(pool, spool)

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

        initial_size = int(result.size)
        self.__class__.initial_volume_size = initial_size
        self.__class__.target_volume_size = (
            (initial_size // (1024 ** 3)) + 1
        ) * (1024 ** 3)
        self.assertEqual(
            self._data_lun_size(), initial_size,
            "Initial ONTAP LUN size should match the CloudStack volume size")


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
            "Expected 0 data-volume LUN-maps after VM stop, found %d: %s"
            % (len(lun_maps), lun_maps)
        )

        # ONTAP: LUN itself must still exist in the FlexVol
        luns = self._data_luns()
        self.assertTrue(
            len(luns) > 0,
            "Data volume LUN should still exist in ONTAP FlexVol after VM stop"
        )

    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # Step 06 — Grow attached volume while VM is stopped
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_06_grow_stopped_vm_volume(self):
        """
        Grow the attached iSCSI volume while its managed KVM VM is stopped.
        Verifies:
          - CloudStack reports the target size and Ready state
          - ONTAP reports the target LUN space.size
          - The LUN remains unmapped while the VM is stopped
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")

        target_size = self.__class__.target_volume_size
        self._resize_volume(
            self.__class__.volume.id, target_size // (1024 ** 3))

        volume = self._poll_volume_size(
            self.__class__.volume.id, target_size)
        self.assertIsNotNone(
            volume, "CloudStack did not report the grown volume size")

        # DB: cloud.volumes.size must be updated by the driver's volumeDao call
        db_size = self._get_db_volume_size(self.__class__.volume.id)
        self.assertEqual(
            db_size, target_size,
            "cloud DB volumes.size (%s) does not match target (%s) after grow"
            % (db_size, target_size))

        # ONTAP: LUN space.size must reflect the new allocation
        self.assertEqual(
            self._data_lun_size(), target_size,
            "ONTAP LUN space.size does not match the requested size")
        self.assertEqual(
            len(self._lun_maps()), 0,
            "The data LUN should remain unmapped while the VM is stopped")

    # ------------------------------------------------------------------
    # Step 07 — Shrink must fail
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_07_shrink_rejected(self):
        """
        Attempt to shrink the grown iSCSI volume back to its initial size.
        Verifies:
          - The ONTAP driver rejects shrink even with shrinkok=true
          - CloudStack and ONTAP LUN sizes remain at the grown value
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped")
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")
        self.assertEqual(
            self._get_db_volume_size(self.__class__.volume.id),
            self.__class__.target_volume_size,
            "Volume was not grown to the target size — test_06 must pass "
            "before the shrink check")

        initial_gib = self.__class__.initial_volume_size // (1024 ** 3)
        with self.assertRaises(Exception) as context:
            self._resize_volume(
                self.__class__.volume.id, initial_gib, shrink_ok=True)
        self.assertIn(
            "Unable to shrink volume", str(context.exception),
            "Shrink failed for an unexpected reason")

        volume = self._poll_volume_size(
            self.__class__.volume.id, self.__class__.target_volume_size)
        self.assertIsNotNone(
            volume, "CloudStack volume size changed after rejected shrink")

        # DB: size must remain at grown value after rejected shrink
        db_size = self._get_db_volume_size(self.__class__.volume.id)
        self.assertEqual(
            db_size, self.__class__.target_volume_size,
            "cloud DB volumes.size did not remain at the grown target after "
            "the rejected iSCSI shrink")

        self.assertEqual(
            self._data_lun_size(), self.__class__.target_volume_size,
            "ONTAP LUN size changed after rejected shrink")

    # Step 08 — Start VM — LUN-maps should be re-created
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_08_start_vm_lun_remapped(self):
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
    # Step 09 — Guest SSH access for snapshot checks
    # ------------------------------------------------------------------

    @attr(tags=[TAG], required_hardware=True)
    def test_09_open_ssh_and_write_root_data_files(self):
        """
        Prepare the running VM for snapshot checks.
        Verifies:
          - ROOT and DATA LUNs are both on the ONTAP test pool
          - SSH login (ontap.cfg templateUsername/templatePassword) works
            through a port forward on the network's source-NAT IP
          - The DATA disk can be formatted, mounted and written
        """
        vm = self._require_snapshot_vm(ssh=False)
        if not self.__class__.network_id:
            self.skipTest("Snapshot steps need an Advanced zone guest network for SSH")
        pool_id = self.__class__.pool.id
        root = self._root_volume(vm.id)
        self.assertEqual(getattr(root, "storageid", None), pool_id,
                         "ROOT volume is on '%s', not the ONTAP test pool"
                         % getattr(root, "storage", None))
        vol_cmd = listVolumesAPI.listVolumesCmd()
        vol_cmd.id = self.__class__.volume.id
        vols = self.apiClient.listVolumes(vol_cmd) or []
        self.assertTrue(vols and getattr(vols[0], "virtualmachineid", None) == vm.id,
                        "DATA volume must be attached before the snapshot steps")

        host, port, _ = open_ssh_port(self.apiClient, vm.id, self.__class__.network_id)
        self.__class__.vm_ssh = (host, port)
        token = new_token("ssh-access-test")
        self._write_marker(token, disks=("ROOT", "DATA"))
        self._assert_marker(token, disk="ROOT", msg="guest access")
        self._assert_marker(token, disk="DATA", msg="guest access")

    # ------------------------------------------------------------------
    # Steps 08-12 — Volume snapshots
    # ------------------------------------------------------------------

    @attr(tags=[TAG], required_hardware=True)
    def test_10_root_volume_snapshot_create(self):
        """
        VS-01: snapshot the ROOT volume of the running VM.
        Verifies: state BackedUp; VM stays Running; one new FlexVol snapshot.
        """
        vm = self._require_snapshot_vm()
        before = self._ontap_snap_names()
        snap = self._take_volume_snapshot(self._root_volume(vm.id).id)
        self.assertEqual(self._vm_state(vm.id), "Running")
        self._assert_new_ontap_snaps(before, [snap], "volume", "ROOT volume snapshot")

    @attr(tags=[TAG], required_hardware=True)
    def test_11_data_volume_snapshot_create(self):
        """
        VS-03: snapshot the attached DATA volume.
        The snapshot is kept for step 14.
        Verifies: state BackedUp; VM stays Running; one new FlexVol snapshot.
        """
        vm = self._require_snapshot_vm()
        token = new_token("data-s1")
        self._write_marker(token, disks=("DATA",))
        before = self._ontap_snap_names()
        snap = self._take_volume_snapshot(self.__class__.volume.id)
        self.assertEqual(self._vm_state(vm.id), "Running")
        new = self._assert_new_ontap_snaps(before, [snap], "volume", "DATA volume snapshot")
        self._keep_data_snapshot(snap, new, token)

    @attr(tags=[TAG], required_hardware=True)
    def test_12_volume_revert_running_vm_rejected(self):
        """
        VS-20: reverting a volume snapshot while the VM is Running fails.
        Verifies: error says the Instance is not shut down; VM stays Running;
        the FlexVol snapshot remains.
        """
        vm = self._require_snapshot_vm()
        before = self._ontap_snap_names()
        snap = self._take_volume_snapshot(self._root_volume(vm.id).id)
        new = self._assert_new_ontap_snaps(before, [snap], "volume", "ROOT volume snapshot")
        with self.assertRaises(Exception) as ctx:
            Volume.revertToSnapshot(self.apiClient, snap.id)
        self.assertIn("shutdown", str(ctx.exception).lower(),
                      "Unexpected error: %s" % ctx.exception)
        self.assertEqual(self._vm_state(vm.id), "Running")
        self._assert_ontap_snaps_present(new, "after rejected revert")

    @attr(tags=[TAG], required_hardware=True)
    def test_13_root_volume_revert(self):
        """
        VS-12: write A, snapshot ROOT, write B, stop, revert, start.
        Verifies: ROOT marker is A; the FlexVol snapshot survives the revert.
        """
        self._volume_revert_flow("ROOT")

    @attr(tags=[TAG], required_hardware=True)
    def test_14_data_volume_revert(self):
        """
        Take a second DATA snapshot, revert to the step 11 snapshot, then to
        this later one, and delete both while the VM is still running.
        Verifies:
          - After revert to the older snapshot, the DATA marker is the step 11
            token and the later snapshot is still listed
          - After revert to the later snapshot, the DATA marker is that token
          - Both snapshots and their FlexVol snapshots are gone; the VM stays
            Running and the later marker is unchanged
        """
        vm = self._require_snapshot_vm()
        older, token_older, ontap_older = self._adopt_kept_data_snapshot()
        token_latest = new_token("data-s2")
        self._write_marker(token_latest, disks=("DATA",))
        before = self._ontap_snap_names()
        latest = self._take_volume_snapshot(self.__class__.volume.id)
        ontap_latest = self._assert_new_ontap_snaps(
            before, [latest], "volume", "latest DATA snapshot")
        self._write_marker(new_token("data-after-both"), disks=("DATA",))

        self._stop_vm(vm.id)
        Volume.revertToSnapshot(self.apiClient, older.id)
        self._start_vm(vm.id)
        self._assert_marker(token_older, disk="DATA",
                            msg="after revert to the older snapshot")
        self.assertTrue(
            self._volume_snapshot_listed(latest.id),
            "The later volume snapshot should still exist")
        self._assert_ontap_snaps_present(
            ontap_older | ontap_latest, "after revert to the older snapshot")

        self._stop_vm(vm.id)
        Volume.revertToSnapshot(self.apiClient, latest.id)
        self._start_vm(vm.id)
        self._assert_marker(token_latest, disk="DATA",
                            msg="after revert to the latest snapshot")

        self._delete_volume_snapshot_while_running(
            vm, latest, ontap_latest, token_latest)
        self._delete_volume_snapshot_while_running(
            vm, older, ontap_older, token_latest)

    # ------------------------------------------------------------------
    # Steps 13-16 — VM snapshots
    # ------------------------------------------------------------------

    @attr(tags=[TAG], required_hardware=True)
    def test_15_vm_snapshot_create_running(self):
        """
        VM snapshot of the running VM (ROOT + DATA LUNs on one FlexVol).
        Writes before-s1, then snapshots. The snapshot is kept for step 17.
        Verifies: state Ready; VM stays Running; one new FlexVol snapshot.
        """
        vm = self._require_snapshot_vm()
        self.__class__.token_s1 = new_token("before-s1")
        self._write_marker(self.__class__.token_s1, disks=("ROOT", "DATA"))
        before = self._ontap_snap_names()
        snap = self._take_vm_snapshot(vm.id)
        self.assertEqual(self._vm_state(vm.id), "Running")
        new = self._assert_new_ontap_snaps(before, [snap], "vm", "VM snapshot")
        self._keep_vm_snapshot(snap, new, "vm_snap_running")

    @attr(tags=[TAG], required_hardware=True)
    def test_16_vm_snapshot_create_stopped(self):
        """
        VM snapshot of the stopped VM.
        Writes before-s2 while Running, then stops and snapshots.
        The snapshot is kept for step 17.
        Verifies: state Ready; VM stays Stopped; one new FlexVol snapshot.
        """
        vm = self._require_snapshot_vm()
        self.__class__.token_s2 = new_token("before-s2")
        self._write_marker(self.__class__.token_s2, disks=("ROOT", "DATA"))
        self._stop_vm(vm.id)
        before = self._ontap_snap_names()
        snap = self._take_vm_snapshot(vm.id)
        self.assertEqual(self._vm_state(vm.id), "Stopped")
        new = self._assert_new_ontap_snaps(before, [snap], "vm", "VM snapshot (Stopped)")
        self._keep_vm_snapshot(snap, new, "vm_snap_stopped")

    @attr(tags=[TAG], required_hardware=True)
    def test_17_vm_snapshot_s1_running_s2_stopped_revert(self):
        """
        VMS-09 (iSCSI ROOT + iSCSI DATA on the same pool): revert to the
        running snapshot from step 15, then to the stopped snapshot from
        step 16.
        Verifies:
          - After revert to S1: ROOT and DATA hold before-s1
          - After revert to S2: ROOT and DATA hold before-s2
          - Both FlexVol snapshots survive the reverts
          - Both VM snapshots are then deleted while the VM is still running
        """
        if self.__class__.vm is None:
            self.skipTest("VM not deployed — test_03 was skipped (no ready template)")
        if self.__class__.vm_ssh is None:
            self.skipTest("Guest SSH not set up — test_09 must pass first")
        s1, s2, token_a, token_b, ontap_names = self._adopt_kept_vm_snapshots()
        vm = self.__class__.vm

        self._stop_vm(vm.id)
        VmSnapshot.revertToSnapshot(self.apiClient, s1.id)
        self._start_vm(vm.id)
        self._assert_marker(token_a, disk="ROOT", msg="after revert to S1")
        self._assert_marker(token_a, disk="DATA", msg="after revert to S1")
        self.assertEqual(len(self._vm_snapshots_of(vm.id)), 2, "S2 should still be listed")

        self._stop_vm(vm.id)
        VmSnapshot.revertToSnapshot(self.apiClient, s2.id)
        self._start_vm(vm.id)
        self._assert_marker(token_b, disk="ROOT", msg="after revert to S2")
        self._assert_marker(token_b, disk="DATA", msg="after revert to S2")
        self._assert_ontap_snaps_present(ontap_names, "after both reverts")

        self._delete_vm_snapshot_while_running(
            vm, s1, self._ontap_names_for_snap(ontap_names, s1, "vm"), token_b)
        self._delete_vm_snapshot_while_running(
            vm, s2, self._ontap_names_for_snap(ontap_names, s2, "vm"), token_b)

    @attr(tags=[TAG], required_hardware=True)
    def test_18_vm_snapshot_duplicate_requests(self):
        """
        VMS-53: two VM snapshot requests for the same VM at the same time.
        A rejected request is retried on its own; one that already succeeded
        is not repeated.
        Verifies: both succeed and are Ready; two new FlexVol snapshots.
        """
        vm = self._require_snapshot_vm()
        before = self._ontap_snap_names()
        pending = [
            lambda: VmSnapshot.create(self.apiClient, vm.id, snapshotmemory="false",
                                      name="ontap_dup_a_%d" % random.randint(0, 99999)),
            lambda: VmSnapshot.create(self.apiClient, vm.id, snapshotmemory="false",
                                      name="ontap_dup_b_%d" % random.randint(0, 99999)),
        ]
        ok = []
        last_errors = []
        attempts = 3
        for attempt in range(1, attempts + 1):
            results = _run_concurrent(*pending)
            last_errors = []
            still_pending = []
            retryable = True
            for fn, (result, exc) in zip(pending, results):
                if exc is None and result is not None:
                    ok.append(result)
                    self._vm_snapshot_ids.append(result.id)
                    continue
                last_errors.append(exc)
                if exc is None or not _is_rejected(exc):
                    retryable = False
                still_pending.append(fn)
            pending = still_pending
            if not pending:
                break
            if not retryable or attempt == attempts:
                break
            logger.warning("duplicate VM snapshot rejected, retry %d/%d: %s",
                           attempt, attempts, last_errors)
            time.sleep(15)
        self.assertFalse(pending,
                         "Duplicate VM snapshot requests failed: %s" % last_errors)
        self.assertEqual(len(ok), 2, "Expected both VM snapshots to succeed; "
                         "got %d" % len(ok))
        for snap in ok:
            self.assertEqual(snap.state, "Ready")
            self.assertEqual(snap.virtualmachineid, vm.id)
        self._assert_new_ontap_snaps(before, ok, "vm", "duplicate VM snapshot requests")

    # ------------------------------------------------------------------
    # Steps 17-21 — Second VM on the same pool: isolation and concurrency
    # ------------------------------------------------------------------

    @attr(tags=[TAG], required_hardware=True)
    def test_19_deploy_second_vm(self):
        """
        Deploy a second VM whose ROOT LUN is on the same ONTAP pool.
        Verifies: VM Running; ROOT on the test pool; SSH works.
        """
        self._require_snapshot_vm()
        cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
        cmd.zoneid = self.zone.id
        cmd.templateid = self.__class__.template_id
        cmd.serviceofferingid = self.__class__.service_offering_id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        cmd.networkids = self.__class__.network_id
        vm2 = self.apiClient.deployVirtualMachine(cmd)
        self.__class__.vm2 = vm2
        self._poll_vm_state(vm2.id, "Running", timeout=900)
        root = self._root_volume(vm2.id)
        self.assertEqual(getattr(root, "storageid", None), self.__class__.pool.id,
                         "Second VM ROOT is not on the ONTAP test pool")
        host, port, _ = open_ssh_port(self.apiClient, vm2.id, self.__class__.network_id)
        self.__class__.vm2_ssh = (host, port)
        token = new_token("second-vm-ssh-access-test")
        self._write_marker(token, which="vm2")
        self._assert_marker(token, which="vm2", msg="second VM access")

    @attr(tags=[TAG], required_hardware=True)
    def test_20_volume_revert_leaves_other_vm_untouched(self):
        """
        VS-28: two VMs on one FlexVol; revert VM-A's ROOT to its snapshot.
        Verifies: VM-A is back at its snapshot; VM-B keeps data written after it.
        """
        vm = self._require_snapshot_vm()
        self._require_second_vm()
        a1 = new_token("vm-a-before-snapshot")
        a2 = new_token("vm-a-after-snapshot")
        b1 = new_token("vm-b-before-snapshot")
        b2 = new_token("vm-b-after-snapshot")
        self._write_marker(a1)
        self._write_marker(b1, which="vm2")
        snap = self._take_volume_snapshot(self._root_volume(vm.id).id)
        self._write_marker(a2)
        self._write_marker(b2, which="vm2")

        self._stop_vm(vm.id)
        Volume.revertToSnapshot(self.apiClient, snap.id)
        self._start_vm(vm.id)
        self._assert_marker(a1, msg="VM-A after revert")
        self._assert_marker(b2, which="vm2", msg="VM-B after VM-A's revert")

    @attr(tags=[TAG], required_hardware=True)
    def test_21_concurrent_vm_snapshots_two_vms(self):
        """
        VMS-55: VM snapshots on two VMs of the same iSCSI pool at the same time.
        Verifies: both Ready and owned by their VMs; two distinct new FlexVol
        snapshots.
        """
        vm = self._require_snapshot_vm()
        vm2 = self._require_second_vm()
        before = self._ontap_snap_names()
        results = _run_concurrent(
            lambda: VmSnapshot.create(self.apiClient, vm.id, snapshotmemory="false",
                                      name="ontap_cc_a_%d" % random.randint(0, 99999)),
            lambda: VmSnapshot.create(self.apiClient, vm2.id, snapshotmemory="false",
                                      name="ontap_cc_b_%d" % random.randint(0, 99999)),
        )
        self._vm_snapshot_ids.extend(s.id for s, _ in results if s is not None)
        errors = [e for _, e in results if e is not None]
        self.assertFalse(errors, "Concurrent VM snapshots failed: %s" % errors)
        snap_a, snap_b = results[0][0], results[1][0]
        self.assertEqual(snap_a.state, "Ready")
        self.assertEqual(snap_b.state, "Ready")
        self.assertEqual(snap_a.virtualmachineid, vm.id)
        self.assertEqual(snap_b.virtualmachineid, vm2.id)
        self._assert_new_ontap_snaps(before, [snap_a, snap_b], "vm", "concurrent VM snapshots")

    @attr(tags=[TAG], required_hardware=True)
    def test_22_concurrent_volume_snapshot_and_revert(self):
        """
        Volume snapshot of VM-A's ROOT while VM-B's ROOT reverts, at the same time.
        A rejected snapshot or revert is retried on its own.
        Verifies: VM-A snapshot BackedUp with its own FlexVol snapshot; VM-B is
        back at its snapshot; VM-A data unchanged.
        """
        vm = self._require_snapshot_vm()
        vm2 = self._require_second_vm()
        a1 = new_token("vm-a-current-data")
        b1 = new_token("vm-b-before-snapshot")
        b2 = new_token("vm-b-after-snapshot")
        self._write_marker(a1)
        self._write_marker(b1, which="vm2")
        snap_b = self._take_volume_snapshot(self._root_volume(vm2.id).id)
        self._write_marker(b2, which="vm2")
        self._stop_vm(vm2.id)
        before = self._ontap_snap_names()
        root_a = self._root_volume(vm.id).id

        snap_a = _snapshot_and_revert_with_retry(
            lambda: Snapshot.create(self.apiClient, root_a,
                                    account=self.account.name, domainid=self.domain.id),
            lambda: Volume.revertToSnapshot(self.apiClient, snap_b.id),
        )
        self._volume_snapshot_ids.append(snap_a.id)
        self.assertEqual(snap_a.state, "BackedUp")
        self._assert_new_ontap_snaps(before, [snap_a], "volume", "VM-A snapshot during VM-B revert")

        self._start_vm(vm2.id)
        self._assert_marker(b1, which="vm2", msg="VM-B after revert")
        self._assert_marker(a1, msg="VM-A during VM-B revert")

    @attr(tags=[TAG], required_hardware=True)
    def test_23_concurrent_vm_snapshot_and_revert(self):
        """
        VM snapshot of VM-A while VM-B reverts to its VM snapshot, at the same time.
        A rejected snapshot or revert is retried on its own.
        Verifies: VM-A snapshot Ready with its own FlexVol snapshot; VM-B is
        back at its snapshot; VM-A data unchanged.
        """
        vm = self._require_snapshot_vm()
        vm2 = self._require_second_vm()
        a1 = new_token("vm-a-current-data")
        b1 = new_token("vm-b-before-snapshot")
        b2 = new_token("vm-b-after-snapshot")
        self._write_marker(a1)
        self._write_marker(b1, which="vm2")
        snap_b = self._take_vm_snapshot(vm2.id)
        self._write_marker(b2, which="vm2")
        self._stop_vm(vm2.id)
        before = self._ontap_snap_names()

        snap_a = _snapshot_and_revert_with_retry(
            lambda: VmSnapshot.create(self.apiClient, vm.id, snapshotmemory="false",
                                      name="ontap_ccr_%d" % random.randint(0, 99999)),
            lambda: VmSnapshot.revertToSnapshot(self.apiClient, snap_b.id),
        )
        self._vm_snapshot_ids.append(snap_a.id)
        self.assertEqual(snap_a.state, "Ready")
        self._assert_new_ontap_snaps(before, [snap_a], "vm", "VM-A snapshot during VM-B revert")

        self._start_vm(vm2.id)
        self._assert_marker(b1, which="vm2", msg="VM-B after revert")
        self._assert_marker(a1, msg="VM-A during VM-B revert")

    # ------------------------------------------------------------------
    # Step 24 — Detach volume from running VM
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_24_detach_volume_from_vm(self):
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
            "Expected 0 data-volume LUN-maps after volume detach, found %d: %s"
            % (len(lun_maps), lun_maps)
        )

        # ONTAP: LUN still exists in FlexVol
        luns = self._data_luns()
        self.assertTrue(
            len(luns) > 0,
            "Data volume LUN should still exist in ONTAP FlexVol after detach"
        )

    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # Step 25 — Grow detached data volume
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_25_grow_detached_volume(self):
        """Grow the materialized LUN after it has been detached."""
        self.assertIsNotNone(self.__class__.volume,
                             "Volume absent — test_02 must pass first")
        detached_target = self.__class__.target_volume_size + (1024 ** 3)
        self._resize_volume(
            self.__class__.volume.id, detached_target // (1024 ** 3))

        self._poll_volume_size(self.__class__.volume.id, detached_target)
        self.assertEqual(
            self._get_db_volume_size(self.__class__.volume.id),
            detached_target,
            "DB size does not match detached iSCSI grow target")
        self.assertEqual(
            self._data_lun_size(), detached_target,
            "ONTAP LUN size does not match detached grow target")
        self.__class__.target_volume_size = detached_target

    # ------------------------------------------------------------------
    # Step 26 — Destroy the second VM while it still has a VM snapshot
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_26_destroy_vm_with_vm_snapshot(self):
        """
        Destroy (expunge) the second VM while it still has a VM snapshot.
        Verifies: destroy succeeds; the VM snapshot is no longer listed; its
        FlexVol snapshot is removed from ONTAP.
        """
        self._require_snapshot_vm()
        vm2 = self._require_second_vm()
        before = self._ontap_snap_names()
        snap = self._take_vm_snapshot(vm2.id)
        new = self._assert_new_ontap_snaps(before, [snap], "vm", "second VM snapshot")

        cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
        cmd.id = vm2.id
        cmd.expunge = True
        self.apiClient.destroyVirtualMachine(cmd)
        self.__class__.vm2 = None
        self.__class__.vm2_ssh = None
        self._vm_snapshot_ids.remove(snap.id)

        deadline = time.time() + 300
        while time.time() < deadline and self._vm_snapshots_of(vm2.id):
            time.sleep(10)
        self.assertFalse(self._vm_snapshots_of(vm2.id),
                         "VM snapshot still listed after destroying its VM")
        self._wait_ontap_snaps_gone(new)

    # ------------------------------------------------------------------
    # Step 27 — Destroy VM and clean up pool
    # ------------------------------------------------------------------

    @attr(tags=["iscsi_vm_workflow"], required_hardware=True)
    def test_27_destroy_vm_and_cleanup(self):
        """
        Destroy the VM (with expunge), delete the data volume, force-delete
        the ONTAP pool, and delete the guest network created for this suite.
        This test leaves no entities behind in either CloudStack or ONTAP.
        Verifies:
          - VM is destroyed and expunged from CloudStack
          - Template cache survives VM delete: template_spool_ref still Ready
            and the cs_tmpl_* cache LUN is still in the FlexVol
          - deleteVolume removes the LUN from the ONTAP FlexVol
          - deleteStoragePool(forced=True) removes the pool from CS
          - ONTAP: FlexVol deleted
          - ONTAP: all per-host igroups deleted
          - CloudStack: guest network deleted (Advanced zones only)
        """
        self._destroy_second_vm()
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

            if pool is not None:
                spool = self._assert_single_ready_spool_ref(
                    self.__class__.pool_db_id)
                self._assert_cache_on_ontap(pool, spool)

        if vol is not None and pool is not None:
            pool_name = pool.name

            # Delete the data volume
            del_cmd = deleteVolumeAPI.deleteVolumeCmd()
            del_cmd.id = vol.id
            self.apiClient.deleteVolume(del_cmd)
            self.__class__.volume = None

            # ONTAP: LUN must be removed after volume deletion
            luns = self._data_luns(vol)
            self.assertEqual(
                len(luns), 0,
                "Expected the data volume's LUN to be gone from FlexVol '%s' "
                "after volume delete, found %d" % (pool_name, len(luns))
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
