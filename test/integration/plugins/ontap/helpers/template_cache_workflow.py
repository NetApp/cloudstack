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
Shared sequential workflow for ONTAP primary template-cache Marvin suites.

Protocol-specific subclasses set PROTOCOL / NOSE_TAG / pool URL details and
inherit the numbered steps:

  01  Create tagged ONTAP pool + matching tagged service offering
  02  Deploy VM-1 (ROOT on ONTAP) — seeds template cache
  03  Assert template_spool_ref + ONTAP cache object
  04  Deploy VM-2 — reuses cache (no second cache object)
  05  Destroy both VMs — cache and spool_ref must survive
  06  Cleanup — delete SO, force-delete pool
"""

from __future__ import print_function

import base64
import logging
import random
import time
import unittest

from marvin.cloudstackAPI import (
    createNetwork as createNetworkAPI,
    createStoragePool as createStoragePoolAPI,
    deleteNetwork as deleteNetworkAPI,
    deleteServiceOffering as deleteServiceOfferingAPI,
    deployVirtualMachine as deployVirtualMachineAPI,
    destroyVirtualMachine as destroyVirtualMachineAPI,
    enableStorageMaintenance,
    listNetworkOfferings as listNetworkOfferingsAPI,
    listNetworks as listNetworksAPI,
    listTemplates as listTemplatesAPI,
    listVirtualMachines as listVirtualMachinesAPI,
    listVolumes as listVolumesAPI,
    stopVirtualMachine as stopVirtualMachineAPI,
)
from marvin.lib.base import ServiceOffering, StoragePool
from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config
from helpers import template_cache_util as tcu

logger = logging.getLogger("TemplateCacheWorkflow")


def _list_vms_cmd(vm_id):
    cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
    cmd.id = vm_id
    return cmd


def _list_vols_for_vm(vm_id):
    cmd = listVolumesAPI.listVolumesCmd()
    cmd.virtualmachineid = vm_id
    cmd.listall = True
    return cmd


class TemplateCacheTestData(object):
    account = "account"
    ontap = "ontap"
    primaryStorage = "primaryStorage"
    computeOffering = "computeOffering"
    provider = "provider"
    scope = "scope"
    tags = "tags"

    DETAIL_USERNAME = "username"
    DETAIL_PASSWORD = "password"
    DETAIL_SVM_NAME = "svmName"
    DETAIL_PROTOCOL = "protocol"
    DETAIL_STORAGE_IP = "storageIP"

    # ~50 GiB — room for template cache + two ROOT clones
    DEFAULT_CAPACITY_BYTES = 50 * 1024 * 1024 * 1024

    def __init__(self, storage_ip, svm_name, username, password,
                 protocol="NFS3", scope="CLUSTER", provider="NetApp ONTAP",
                 tags="ontap-tmpl-cache", capacitybytes=None,
                 pool_name_prefix="OntapTmplCache"):
        if capacitybytes is None:
            capacitybytes = self.DEFAULT_CAPACITY_BYTES
        encoded_password = base64.b64encode(password.encode()).decode()
        suffix = random.randint(0, 99999)
        self.testdata = {
            self.ontap: {
                self.DETAIL_STORAGE_IP: storage_ip,
                self.DETAIL_SVM_NAME: svm_name,
                self.DETAIL_USERNAME: username,
                self.DETAIL_PASSWORD: password,
            },
            self.account: {
                "email": "ontap-tmpl-cache@test.com",
                "firstname": "ONTAP",
                "lastname": "TmplCache",
                "username": "ontap_tmpl_%d" % suffix,
                "password": "password",
            },
            self.primaryStorage: {
                "name": "%s_%d" % (pool_name_prefix, suffix),
                self.scope: scope,
                self.provider: provider,
                self.tags: tags,
                "capacitybytes": capacitybytes,
                "managed": True,
                "details": {
                    self.DETAIL_USERNAME: username,
                    self.DETAIL_PASSWORD: encoded_password,
                    self.DETAIL_SVM_NAME: svm_name,
                    self.DETAIL_PROTOCOL: protocol,
                    self.DETAIL_STORAGE_IP: storage_ip,
                },
            },
            self.computeOffering: {
                "name": "OntapTmplCacheSO_%d" % suffix,
                "displaytext": "ONTAP template-cache SO (tagged)",
                "cpunumber": 1,
                "cpuspeed": 500,
                "memory": 512,
                "storagetype": "shared",
                "tags": tags,
            },
        }


class OntapTemplateCacheWorkflow(OntapTestBase):
    """
    Protocol-parameterized template-cache suite.

    Subclasses must set:
      PROTOCOL          - "NFS3" or "ISCSI"
      NOSE_TAG          - nose attr tag string
      PROTOCOL_CFG_KEY  - "nfs3" or "iscsi" under storagePool.protocols
      POOL_URL_SCHEME   - used in createStoragePool url (e.g. nfs / iscsi)
    """

    PROTOCOL = "NFS3"
    NOSE_TAG = "nfs3_template_cache"
    PROTOCOL_CFG_KEY = "nfs3"
    POOL_URL_SCHEME = "nfs"
    POOL_NAME_PREFIX = "OntapNfsTmplCache"

    # Shared sequential state
    template_id = None          # API UUID
    template_db_id = None       # numeric DB id
    pool_db_id = None
    service_offering = None
    network_id = None
    _created_network_id = None
    vm1 = None
    vm2 = None

    @classmethod
    def setUpClass(cls):
        super(OntapTemplateCacheWorkflow, cls).setUpClass()
        testclient = super(
            OntapTemplateCacheWorkflow, cls
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

        proto_cfg = pool_cfg.get("protocols", {}).get(cls.PROTOCOL_CFG_KEY, {})
        if not proto_cfg.get("enabled", True):
            raise unittest.SkipTest(
                "%s tests disabled in ontap.cfg "
                "(set protocols.%s.enabled=true to enable)"
                % (cls.PROTOCOL, cls.PROTOCOL_CFG_KEY)
            )

        # Dedicated tag so ROOT is forced onto this ONTAP pool (not defaultPrim)
        default_tag = "ontap-%s-tmpl-cache" % cls.PROTOCOL_CFG_KEY
        tags = proto_cfg.get("templateCacheTags") or default_tag
        scope = pool_cfg.get("storagePoolScope", "CLUSTER")
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")
        capacitybytes = pool_cfg.get(
            "templateCacheCapacitybytes",
            pool_cfg.get("capacitybytes", None),
        )

        cls.testdata = TemplateCacheTestData(
            storage_ip, svm_name, username, password,
            protocol=cls.PROTOCOL, scope=scope, provider=provider,
            tags=tags, capacitybytes=capacitybytes,
            pool_name_prefix=cls.POOL_NAME_PREFIX,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name
        cls.storage_tags = tags

        cls._setup_cloudstack_resources(
            config, cls.testdata[TemplateCacheTestData.account]
        )

        # Ready user KVM template
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
        if not kvm_ready:
            raise unittest.SkipTest(
                "No ready user KVM template in zone '%s'" % cls.zone.name
            )
        cls.template_id = kvm_ready[0].id
        cls.template_db_id = tcu.get_db_id(
            cls.dbConnection, "vm_template", cls.template_id
        )

        cls.network_id = None
        cls._created_network_id = None
        zone_type = getattr(cls.zone, "networktype", "Basic")
        if zone_type.lower() == "advanced":
            net_cmd = listNetworksAPI.listNetworksCmd()
            net_cmd.zoneid = cls.zone.id
            net_cmd.account = cls.account.name
            net_cmd.domainid = cls.domain.id
            nets = cls.apiClient.listNetworks(net_cmd) or []
            if nets:
                cls.network_id = nets[0].id
            else:
                no_cmd = listNetworkOfferingsAPI.listNetworkOfferingsCmd()
                no_cmd.state = "Enabled"
                no_cmd.guestiptype = "Isolated"
                no_cmd.supportedservices = "SourceNat"
                offerings = cls.apiClient.listNetworkOfferings(no_cmd) or []
                if not offerings:
                    raise unittest.SkipTest(
                        "No Isolated network offering for Advanced zone"
                    )
                cn = createNetworkAPI.createNetworkCmd()
                cn.name = "ontap-tmpl-cache-net-%d" % random.randint(0, 9999)
                cn.displaytext = cn.name
                cn.networkofferingid = offerings[0].id
                cn.zoneid = cls.zone.id
                cn.account = cls.account.name
                cn.domainid = cls.domain.id
                created = cls.apiClient.createNetwork(cn)
                cls.network_id = created.id
                cls._created_network_id = created.id

    @classmethod
    def tearDownClass(cls):
        for vm in (cls.vm2, cls.vm1):
            if vm is None:
                continue
            try:
                cls._destroy_vm_static(vm.id)
            except Exception as e:
                logger.warning(
                    "tearDownClass: destroy VM %s failed: %s" % (vm.id, e)
                )
            finally:
                if vm is cls.vm1:
                    cls.vm1 = None
                if vm is cls.vm2:
                    cls.vm2 = None

        if cls.service_offering is not None:
            try:
                cmd = deleteServiceOfferingAPI.deleteServiceOfferingCmd()
                cmd.id = cls.service_offering.id
                cls.apiClient.deleteServiceOffering(cmd)
            except Exception as e:
                logger.warning(
                    "tearDownClass: delete SO failed: %s" % e
                )
            cls.service_offering = None

        if cls._created_network_id is not None:
            try:
                dn = deleteNetworkAPI.deleteNetworkCmd()
                dn.id = cls._created_network_id
                cls.apiClient.deleteNetwork(dn)
            except Exception as e:
                logger.warning(
                    "tearDownClass: delete network failed: %s" % e
                )
            cls._created_network_id = None

        super(OntapTemplateCacheWorkflow, cls).tearDownClass()

    @classmethod
    def _destroy_vm_static(cls, vm_id):
        vms = cls.apiClient.listVirtualMachines(_list_vms_cmd(vm_id))
        if not vms:
            return
        state = (vms[0].state or "").lower()
        if state not in ("stopped", "destroyed", "expunging", "error"):
            stop = stopVirtualMachineAPI.stopVirtualMachineCmd()
            stop.id = vm_id
            cls.apiClient.stopVirtualMachine(stop)
            deadline = time.time() + 300
            while time.time() < deadline:
                cur = cls.apiClient.listVirtualMachines(_list_vms_cmd(vm_id))
                if cur and cur[0].state.lower() == "stopped":
                    break
                time.sleep(10)
        dest = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
        dest.id = vm_id
        dest.expunge = True
        cls.apiClient.destroyVirtualMachine(dest)

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _create_pool(self):
        ps = self.testdata[TemplateCacheTestData.primaryStorage]
        storage_ip = self.testdata[TemplateCacheTestData.ontap][
            TemplateCacheTestData.DETAIL_STORAGE_IP
        ]
        pool_name = "%s_%d" % (
            self.POOL_NAME_PREFIX, random.randint(0, 99999)
        )

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = pool_name
        cmd.url = "%s://%s/ontap" % (self.POOL_URL_SCHEME, storage_ip)
        cmd.zoneid = self.zone.id
        cmd.clusterid = self.cluster.id
        cmd.podid = self.cluster.podid
        cmd.scope = ps[TemplateCacheTestData.scope]
        cmd.provider = ps[TemplateCacheTestData.provider]
        cmd.tags = ps[TemplateCacheTestData.tags]
        cmd.capacitybytes = ps["capacitybytes"]
        cmd.hypervisor = "KVM"
        cmd.managed = True

        count = 1
        for key, value in ps["details"].items():
            setattr(cmd, "details[{}].{}".format(count, key), value)
            count += 1

        response = self.apiClient.createStoragePool(cmd)
        return StoragePool(response.__dict__)

    def _poll_vm_state(self, vm_id, target_state, timeout=900, interval=10):
        deadline = time.time() + timeout
        current = "unknown"
        while time.time() < deadline:
            vms = self.apiClient.listVirtualMachines(_list_vms_cmd(vm_id))
            if vms:
                current = vms[0].state
                if current.lower() == target_state.lower():
                    return vms[0]
            time.sleep(interval)
        self.fail(
            "VM %s did not reach '%s' within %ds (last='%s')"
            % (vm_id, target_state, timeout, current)
        )

    def _deploy_vm(self, name_suffix):
        cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
        cmd.zoneid = self.zone.id
        cmd.templateid = self.__class__.template_id
        cmd.serviceofferingid = self.__class__.service_offering.id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        cmd.name = "tmpl-cache-%s-%d" % (
            name_suffix, random.randint(0, 9999)
        )
        cmd.displayname = cmd.name
        if self.__class__.network_id:
            cmd.networkids = self.__class__.network_id
        vm = self.apiClient.deployVirtualMachine(cmd)
        self.assertIsNotNone(vm, "deployVirtualMachine returned None")
        return self._poll_vm_state(vm.id, "Running", timeout=900)

    def _root_volume_for_vm(self, vm_id):
        vols = self.apiClient.listVolumes(_list_vols_for_vm(vm_id)) or []
        roots = [
            v for v in vols
            if str(getattr(v, "type", "")).upper() == "ROOT"
        ]
        self.assertTrue(roots, "No ROOT volume for VM %s" % vm_id)
        return roots[0]

    def _assert_root_on_pool(self, vm_id, pool):
        root = self._root_volume_for_vm(vm_id)
        self.assertEqual(
            str(root.storageid), str(pool.id),
            "ROOT volume storageid=%s should equal ONTAP pool id=%s "
            "(check service-offering / pool storage tags)"
            % (root.storageid, pool.id),
        )
        return root

    def _assert_cache_on_ontap(self, pool, spool_ref):
        if self.PROTOCOL.upper() == "ISCSI":
            tcu.assert_iscsi_template_cache_lun(
                self, self.ontap, self.svm_name, pool.name,
                self.__class__.template_db_id,
            )
            cache_count = tcu.count_iscsi_template_cache_luns(
                self.ontap, self.svm_name, pool.name,
                self.__class__.template_db_id,
            )
            self.assertEqual(
                cache_count, 1,
                "Expected exactly one cs_tmpl_%s LUN, found %s"
                % (self.__class__.template_db_id, cache_count),
            )
        else:
            tcu.assert_nfs_template_cache_file(
                self, self.ontap, pool.name, spool_ref.get("install_path")
            )

    def _count_volume_objects(self, pool):
        """Non-cache backend objects that represent deployed ROOT volumes."""
        if self.PROTOCOL.upper() == "ISCSI":
            return tcu.count_luns_excluding_template_cache(
                self.ontap, self.svm_name, pool.name
            )
        # NFS: best-effort — count files under FlexVol root minus known dirs
        names = self.ontap.list_files_in_volume(pool.name, path="/") or []
        ignore = {".", "..", ".snapshot"}
        return len([n for n in names if n not in ignore])

    # ==================================================================
    # Step implementations (protocol suites expose tagged test_* wrappers)
    # ==================================================================

    def step_01_create_tagged_pool_and_service_offering(self):
        """
        Create ONTAP pool and a compute offering that share storage tags so
        deployVirtualMachine places ROOT on this pool (managed cache path).
        """
        pool = self._create_pool()
        self.__class__.pool = pool
        self.__class__.pool_db_id = tcu.get_db_id(
            self.dbConnection, "storage_pool", pool.id
        )

        self.assertEqual(pool.state, "Up", "Pool should be Up")
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(ontap_vol, "FlexVol missing for pool")
        self.assertEqual(ontap_vol.get("state"), "online")

        so = ServiceOffering.create(
            self.apiClient,
            self.testdata[TemplateCacheTestData.computeOffering],
        )
        self.__class__.service_offering = so
        self.assertIsNotNone(so.id, "Service offering create failed")

    def step_02_deploy_vm1_seeds_template_cache(self):
        """Deploy first VM — seeds primary template cache and clones ROOT."""
        self.assertIsNotNone(self.__class__.pool, "test_01 must pass first")
        self.assertIsNotNone(
            self.__class__.service_offering, "test_01 must pass first"
        )

        vm = self._deploy_vm("vm1")
        self.__class__.vm1 = vm
        self._assert_root_on_pool(vm.id, self.__class__.pool)

        spool = tcu.wait_for_spool_ref(
            self.dbConnection,
            self.__class__.pool_db_id,
            self.__class__.template_db_id,
            timeout=600,
        )
        tcu.assert_spool_ref_ready(
            self, spool,
            expect_local_path=(self.PROTOCOL.upper() == "ISCSI"),
        )
        self._assert_cache_on_ontap(self.__class__.pool, spool)

    def step_03_assert_single_spool_ref_and_cache(self):
        """Exactly one template_spool_ref and one ONTAP cache object."""
        self.assertIsNotNone(self.__class__.vm1, "test_02 must pass first")
        count = tcu.count_template_spool_refs(
            self.dbConnection,
            self.__class__.pool_db_id,
            self.__class__.template_db_id,
        )
        self.assertEqual(
            count, 1,
            "Expected one template_spool_ref, got %s" % count,
        )

        spool = tcu.get_template_spool_ref(
            self.dbConnection,
            self.__class__.pool_db_id,
            self.__class__.template_db_id,
        )
        tcu.assert_spool_ref_ready(
            self, spool,
            expect_local_path=(self.PROTOCOL.upper() == "ISCSI"),
        )
        self._assert_cache_on_ontap(self.__class__.pool, spool)

        if self.PROTOCOL.upper() == "ISCSI":
            vol_luns = tcu.count_luns_excluding_template_cache(
                self.ontap, self.svm_name, self.__class__.pool.name
            )
            self.assertGreaterEqual(
                vol_luns, 1,
                "Expected at least one volume LUN (ROOT clone) besides cache",
            )

    def step_04_deploy_vm2_reuses_cache(self):
        """Second VM reuses the same cache — no second cs_tmpl_* / spool row."""
        self.assertIsNotNone(self.__class__.vm1, "test_02 must pass first")
        pool = self.__class__.pool

        before_cache = 1
        before_vols = 0
        if self.PROTOCOL.upper() == "ISCSI":
            before_cache = tcu.count_iscsi_template_cache_luns(
                self.ontap, self.svm_name, pool.name,
                self.__class__.template_db_id,
            )
            before_vols = tcu.count_luns_excluding_template_cache(
                self.ontap, self.svm_name, pool.name
            )

        vm = self._deploy_vm("vm2")
        self.__class__.vm2 = vm
        self._assert_root_on_pool(vm.id, pool)

        count = tcu.count_template_spool_refs(
            self.dbConnection,
            self.__class__.pool_db_id,
            self.__class__.template_db_id,
        )
        self.assertEqual(
            count, 1,
            "Second deploy must not create another template_spool_ref",
        )

        spool = tcu.get_template_spool_ref(
            self.dbConnection,
            self.__class__.pool_db_id,
            self.__class__.template_db_id,
        )
        self._assert_cache_on_ontap(pool, spool)

        if self.PROTOCOL.upper() == "ISCSI":
            after_cache = tcu.count_iscsi_template_cache_luns(
                self.ontap, self.svm_name, pool.name,
                self.__class__.template_db_id,
            )
            after_vols = tcu.count_luns_excluding_template_cache(
                self.ontap, self.svm_name, pool.name
            )
            self.assertEqual(
                after_cache, before_cache,
                "Cache LUN count must stay %s after second deploy"
                % before_cache,
            )
            self.assertEqual(
                after_vols, before_vols + 1,
                "Expected one additional volume LUN for VM-2 ROOT",
            )

    def step_05_destroy_vms_cache_survives(self):
        """
        Destroy/expunge both VMs. Template cache must remain (lazy GC only).

        Verifies CloudStack does not delete primary template cache as part of
        VM lifecycle — only storage GC / deleteTemplate / pool cleanup should.
        """
        pool = self.__class__.pool
        self.assertIsNotNone(pool, "test_01 must pass first")

        for attr_name in ("vm1", "vm2"):
            vm = getattr(self.__class__, attr_name)
            if vm is None:
                continue
            self._destroy_vm_static(vm.id)
            setattr(self.__class__, attr_name, None)

        # Allow async expunge of volumes to settle
        time.sleep(15)

        spool = tcu.get_template_spool_ref(
            self.dbConnection,
            self.__class__.pool_db_id,
            self.__class__.template_db_id,
        )
        tcu.assert_spool_ref_ready(
            self, spool,
            expect_local_path=(self.PROTOCOL.upper() == "ISCSI"),
        )
        self._assert_cache_on_ontap(pool, spool)

        if self.PROTOCOL.upper() == "ISCSI":
            vol_luns = tcu.count_luns_excluding_template_cache(
                self.ontap, self.svm_name, pool.name
            )
            self.assertEqual(
                vol_luns, 0,
                "Volume LUNs should be removed after VM expunge; found %s"
                % vol_luns,
            )
            self.assertEqual(
                tcu.count_iscsi_template_cache_luns(
                    self.ontap, self.svm_name, pool.name,
                    self.__class__.template_db_id,
                ),
                1,
                "Template cache LUN must survive VM delete",
            )

    def step_06_cleanup_pool_and_offering(self):
        """Delete service offering and force-delete the ONTAP pool."""
        if self.__class__.service_offering is not None:
            cmd = deleteServiceOfferingAPI.deleteServiceOfferingCmd()
            cmd.id = self.__class__.service_offering.id
            self.apiClient.deleteServiceOffering(cmd)
            self.__class__.service_offering = None

        pool = self.__class__.pool
        self.assertIsNotNone(pool, "Pool absent")
        pool_name = pool.name

        for attr_name in ("vm1", "vm2"):
            vm = getattr(self.__class__, attr_name)
            if vm is not None:
                try:
                    self._destroy_vm_static(vm.id)
                except Exception:
                    pass
                setattr(self.__class__, attr_name, None)

        mc = enableStorageMaintenance.enableStorageMaintenanceCmd()
        mc.id = pool.id
        self.apiClient.enableStorageMaintenance(mc)
        self._poll_pool_state(pool.id, "Maintenance", timeout=120)
        self._delete_pool(pool.id, forced=True)
        self.__class__.pool = None

        # Pool delete / FlexVol removal can take a moment
        deadline = time.time() + 120
        while time.time() < deadline:
            if self.ontap.get_volume(pool_name) is None:
                break
            time.sleep(5)
        self.assertIsNone(
            self.ontap.get_volume(pool_name),
            "FlexVol should be deleted after pool removal",
        )
