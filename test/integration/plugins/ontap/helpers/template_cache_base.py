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
Shared building blocks for ONTAP primary template-cache Marvin checks.

  TemplateCacheAssertionsMixin
      ROOT placement, template_spool_ref and ONTAP cache-object assertions.
      Mixed into the VM instance suites (happy path: seed / reuse / survive)
      and the negative suite.

  OntapTemplateCacheBase
      setUpClass for the standalone negative suite (zone, template, guest
      network, ONTAP client) without the sequential pool / VM state.
"""

from __future__ import print_function

import base64
import logging
import random
import time
import unittest

from marvin.cloudstackAPI import (
    createNetwork as createNetworkAPI,
    deleteNetwork as deleteNetworkAPI,
    destroyVirtualMachine as destroyVirtualMachineAPI,
    listNetworkOfferings as listNetworkOfferingsAPI,
    listNetworks as listNetworksAPI,
    listTemplates as listTemplatesAPI,
    listVirtualMachines as listVirtualMachinesAPI,
    listVolumes as listVolumesAPI,
    stopVirtualMachine as stopVirtualMachineAPI,
)
from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config
from helpers import template_cache_util as tcu

logger = logging.getLogger("TemplateCacheBase")

# ~50 GiB — room for template cache + ROOT clones (+ a data volume)
DEFAULT_TEMPLATE_CACHE_CAPACITY_BYTES = 50 * 1024 * 1024 * 1024


def _list_vms_cmd(vm_id):
    cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
    cmd.id = vm_id
    cmd.listall = True
    return cmd


def _list_vols_for_vm(vm_id):
    cmd = listVolumesAPI.listVolumesCmd()
    cmd.virtualmachineid = vm_id
    cmd.listall = True
    return cmd


def template_cache_tags(proto_cfg, protocol_cfg_key):
    """Dedicated storage tag so ROOT is forced onto the ONTAP pool."""
    return (proto_cfg.get("templateCacheTags")
            or "ontap-%s-tmpl-cache" % protocol_cfg_key)


def template_cache_capacity_bytes(pool_cfg):
    """Pool size large enough to hold the template cache plus ROOT clones."""
    return (pool_cfg.get("templateCacheCapacitybytes")
            or pool_cfg.get("capacitybytes")
            or DEFAULT_TEMPLATE_CACHE_CAPACITY_BYTES)


def tagged_compute_offering_data(tags, name_prefix="OntapTmplCacheSO"):
    """Compute offering whose storage tags match the ONTAP pool."""
    name = "%s_%d" % (name_prefix, random.randint(0, 99999))
    return {
        "name": name,
        "displaytext": "ONTAP template-cache SO (tagged)",
        "cpunumber": 1,
        "cpuspeed": 500,
        "memory": 512,
        "storagetype": "shared",
        "tags": tags,
    }


def find_ready_kvm_template(api_client, zone_id):
    """Return the first ready, non-SYSTEM KVM template in the zone, or None."""
    tpl_cmd = listTemplatesAPI.listTemplatesCmd()
    tpl_cmd.templatefilter = "all"
    tpl_cmd.listall = True
    tpl_cmd.zoneid = zone_id
    templates = api_client.listTemplates(tpl_cmd) or []
    kvm_ready = [
        t for t in templates
        if getattr(t, "hypervisor", "").lower() == "kvm"
        and getattr(t, "isready", False)
        and getattr(t, "templatetype", "").upper() != "SYSTEM"
    ]
    return kvm_ready[0] if kvm_ready else None


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

    def __init__(self, storage_ip, svm_name, username, password,
                 protocol="NFS3", scope="CLUSTER", provider="NetApp ONTAP",
                 tags="ontap-tmpl-cache", capacitybytes=None,
                 pool_name_prefix="OntapTmplCache"):
        if capacitybytes is None:
            capacitybytes = DEFAULT_TEMPLATE_CACHE_CAPACITY_BYTES
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
            self.computeOffering: tagged_compute_offering_data(tags),
        }


class TemplateCacheAssertionsMixin(object):
    """
    Template-cache assertions shared by the instance and negative suites.

    The host class must provide ``apiClient``, ``dbConnection``, ``ontap``,
    ``svm_name``, ``PROTOCOL`` ("NFS3" / "ISCSI") and a class-level
    ``template_db_id``.
    """

    PROTOCOL = "NFS3"
    template_db_id = None

    def _is_iscsi(self):
        return self.PROTOCOL.upper() == "ISCSI"

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

    def _wait_for_ready_spool_ref(self, pool_db_id, timeout=600):
        spool = tcu.wait_for_spool_ref(
            self.dbConnection, pool_db_id, self.__class__.template_db_id,
            timeout=timeout,
        )
        tcu.assert_spool_ref_ready(
            self, spool, expect_local_path=self._is_iscsi(),
        )
        return spool

    def _assert_single_ready_spool_ref(self, pool_db_id):
        count = tcu.count_template_spool_refs(
            self.dbConnection, pool_db_id, self.__class__.template_db_id,
        )
        self.assertEqual(
            count, 1, "Expected one template_spool_ref, got %s" % count,
        )
        spool = tcu.get_template_spool_ref(
            self.dbConnection, pool_db_id, self.__class__.template_db_id,
        )
        tcu.assert_spool_ref_ready(
            self, spool, expect_local_path=self._is_iscsi(),
        )
        return spool

    def _assert_cache_on_ontap(self, pool, spool_ref):
        if self._is_iscsi():
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

    def _count_non_cache_luns(self, pool):
        return tcu.count_luns_excluding_template_cache(
            self.ontap, self.svm_name, pool.name
        )

    def _wait_for_non_cache_lun_count(self, pool, expected, timeout=180,
                                      interval=10):
        """Poll until the FlexVol holds ``expected`` non-cache LUNs."""
        deadline = time.time() + timeout
        current = self._count_non_cache_luns(pool)
        while current != expected and time.time() < deadline:
            time.sleep(interval)
            current = self._count_non_cache_luns(pool)
        self.assertEqual(
            current, expected,
            "Expected %s non-cache LUNs in FlexVol '%s', found %s"
            % (expected, pool.name, current),
        )


class OntapTemplateCacheBase(TemplateCacheAssertionsMixin, OntapTestBase):
    """
    Class-level setup for standalone template-cache suites.

    Subclasses must set:
      PROTOCOL          - "NFS3" or "ISCSI"
      NOSE_TAG          - nose attr tag string
      PROTOCOL_CFG_KEY  - "nfs3" or "iscsi" under storagePool.protocols
      POOL_URL_SCHEME   - used in createStoragePool url (e.g. nfs / iscsi)
      POOL_NAME_PREFIX  - prefix for pools created by the suite
    """

    PROTOCOL = "NFS3"
    NOSE_TAG = "template_cache"
    PROTOCOL_CFG_KEY = "nfs3"
    POOL_URL_SCHEME = "nfs"
    POOL_NAME_PREFIX = "OntapTmplCache"

    template_id = None          # API UUID
    template_db_id = None       # numeric DB id
    network_id = None
    _created_network_id = None

    @classmethod
    def setUpClass(cls):
        super(OntapTemplateCacheBase, cls).setUpClass()
        testclient = super(OntapTemplateCacheBase, cls).getClsTestClient()

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

        tags = template_cache_tags(proto_cfg, cls.PROTOCOL_CFG_KEY)
        scope = pool_cfg.get("storagePoolScope", "CLUSTER")
        provider = pool_cfg.get("storagePoolProvider", "NetApp ONTAP")

        cls.testdata = TemplateCacheTestData(
            storage_ip, svm_name, username, password,
            protocol=cls.PROTOCOL, scope=scope, provider=provider,
            tags=tags, capacitybytes=template_cache_capacity_bytes(pool_cfg),
            pool_name_prefix=cls.POOL_NAME_PREFIX,
        ).testdata
        cls.ontap = OntapRestClient(storage_ip, username, password)
        cls.svm_name = svm_name
        cls.storage_tags = tags

        cls._setup_cloudstack_resources(
            config, cls.testdata[TemplateCacheTestData.account]
        )

        template = find_ready_kvm_template(cls.apiClient, cls.zone.id)
        if template is None:
            raise unittest.SkipTest(
                "No ready user KVM template in zone '%s'" % cls.zone.name
            )
        cls.template_id = template.id
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

        super(OntapTemplateCacheBase, cls).tearDownClass()

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
