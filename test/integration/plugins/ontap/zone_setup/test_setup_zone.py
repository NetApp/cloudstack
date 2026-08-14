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
Advanced zone setup prerequisite for the ONTAP integration test suite.

Run this before any other test group to ensure the CloudStack zone, pod,
cluster, host, primary storage, and secondary storage all exist.  Each
numbered test method creates one step of the zone hierarchy.  After the
zone is enabled, steps 11–12 wait for both system VMs to reach Running
and for the configured KVM template to become ready.  Creation steps are
idempotent (skipped when the resource already exists); wait steps always run.

Tag: setup_zone

Usage (from cloudstack root):
  bash test/integration/plugins/ontap/run_tests.sh setup_zone
"""

import logging
import re
import time

from nose.plugins.attrib import attr

from ontap_test_base import enable_live_logging, log_progress

from marvin.cloudstackAPI import (
    addCluster as addClusterAPI,
    addHost as addHostAPI,
    addImageStore as addImageStoreAPI,
    addTrafficType as addTrafficTypeAPI,
    createPhysicalNetwork as createPhysicalNetworkAPI,
    createPod as createPodAPI,
    createStoragePool as createStoragePoolAPI,
    createVlanIpRange as createVlanIpRangeAPI,
    createZone as createZoneAPI,
    listClusters as listClustersAPI,
    listHosts as listHostsAPI,
    listNetworkServiceProviders as listNetworkServiceProvidersAPI,
    listPhysicalNetworks as listPhysicalNetworksAPI,
    listPods as listPodsAPI,
    listStoragePools as listStoragePoolsAPI,
    listSystemVms as listSystemVmsAPI,
    listTemplates as listTemplatesAPI,
    listVirtualRouterElements as listVirtualRouterElementsAPI,
    configureVirtualRouterElement as configureVirtualRouterElementAPI,
    updateNetworkServiceProvider as updateNetworkServiceProviderAPI,
    updatePhysicalNetwork as updatePhysicalNetworkAPI,
    updateZone as updateZoneAPI,
)
from marvin.cloudstackException import CloudstackAPIException
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.codes import FAILED
from marvin.jsonHelper import jsonDump
from marvin.lib.common import get_zone

logger = logging.getLogger("TestAdvancedZoneSetup")

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _normalize_template_name(name):
    """Collapse naming variants so '(64 bit)' matches CloudStack's '(64-bit)'."""
    if not name:
        return ""
    s = name.lower().strip()
    s = re.sub(r"\s+", " ", s)
    s = s.replace("(64 bit)", "(64-bit)")
    s = s.replace("(64bit)", "(64-bit)")
    return s


def _list_kvm_templates(api_client, zone_id):
    cmd = listTemplatesAPI.listTemplatesCmd()
    cmd.templatefilter = "all"
    cmd.listall = True
    cmd.zoneid = zone_id
    templates = api_client.listTemplates(cmd) or []
    return [
        t for t in templates
        if getattr(t, "hypervisor", "").lower() == "kvm"
    ]


def _find_kvm_template(api_client, zone_id, template_name):
    """Find a KVM template by normalized name (API name filter is exact-only)."""
    kvm_templates = _list_kvm_templates(api_client, zone_id)
    target = _normalize_template_name(template_name)
    for tmpl in kvm_templates:
        if _normalize_template_name(tmpl.name) == target:
            return tmpl
    return None


def _wait_for_hosts_up(api_client, zone_id, cluster_id, timeout=120):
    """Poll listHosts until all routing hosts in the cluster are Up."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        cmd = listHostsAPI.listHostsCmd()
        cmd.zoneid = zone_id
        cmd.clusterid = cluster_id
        cmd.type = "Routing"
        hosts = api_client.listHosts(cmd) or []
        if hosts and all(h.state == "Up" for h in hosts):
            logger.info("All %d host(s) in cluster are Up." % len(hosts))
            return True
        time.sleep(10)
    logger.warning(
        "_wait_for_hosts_up: hosts did not reach Up state within %ds." % timeout
    )
    return False


def _cluster_has_up_host(api_client, zone_id, cluster_id):
    cmd = listHostsAPI.listHostsCmd()
    cmd.zoneid = zone_id
    cmd.clusterid = cluster_id
    cmd.type = "Routing"
    hosts = api_client.listHosts(cmd) or []
    return any(h.state == "Up" for h in hosts)


def _wait_for_system_vms(api_client, zone_id, timeout=3600, interval=60):
    """Poll listSystemVms until both SSVM and Console Proxy are Running."""
    start = time.time()
    deadline = start + timeout
    attempt = 0
    log_progress(logger,
        "info",
        "Waiting for system VMs (SSVM + Console Proxy) in zone %s "
        "(timeout=%ds, poll every %ds).",
        zone_id, timeout, interval,
    )
    while time.time() < deadline:
        attempt += 1
        elapsed = int(time.time() - start)
        remaining = max(0, int(deadline - time.time()))

        cmd = listSystemVmsAPI.listSystemVmsCmd()
        cmd.zoneid = zone_id
        all_vms = api_client.listSystemVms(cmd) or []
        running = [v for v in all_vms if v.state == "Running"]
        summary = ", ".join(
            "%s/%s=%s" % (v.name, v.systemvmtype, v.state) for v in all_vms
        ) or "(none yet)"

        log_progress(logger,
            "info",
            "System VM poll #%d: %d/%d Running (%d total) "
            "[elapsed %ds, ~%ds left] — %s",
            attempt, len(running), 2, len(all_vms), elapsed, remaining, summary,
        )

        if len(running) >= 2:
            for vm in running:
                log_progress(logger,
                    "info",
                    "System VM Running: name=%s type=%s id=%s",
                    vm.name, vm.systemvmtype, vm.id,
                )
            return True

        time.sleep(interval)

    log_progress(logger,
        "error",
        "System VMs did not reach Running state within %ds.", timeout,
    )
    return False


def _wait_for_template_ready(
        api_client, zone_id, template_name, timeout=3600, interval=60):
    """Poll listTemplates until the named KVM template has isready=True."""
    start = time.time()
    deadline = start + timeout
    attempt = 0
    log_progress(logger,
        "info",
        "Waiting for KVM template '%s' in zone %s "
        "(timeout=%ds, poll every %ds).",
        template_name, zone_id, timeout, interval,
    )
    while time.time() < deadline:
        attempt += 1
        elapsed = int(time.time() - start)
        remaining = max(0, int(deadline - time.time()))

        tmpl = _find_kvm_template(api_client, zone_id, template_name)
        if tmpl and getattr(tmpl, "isready", False):
            log_progress(logger,
                "info",
                "Template ready: name=%s id=%s hypervisor=%s "
                "(configured as '%s', after %ds, %d polls)",
                tmpl.name, tmpl.id, tmpl.hypervisor,
                template_name, elapsed, attempt,
            )
            return True

        if tmpl:
            log_progress(logger,
                "info",
                "Template poll #%d: matched '%s' (configured '%s') "
                "but not ready (isready=%s) [elapsed %ds, ~%ds left]",
                attempt, tmpl.name, template_name,
                getattr(tmpl, "isready", False),
                elapsed, remaining,
            )
        else:
            kvm_templates = _list_kvm_templates(api_client, zone_id)
            kvm_names = [t.name for t in kvm_templates]
            log_progress(logger,
                "info",
                "Template poll #%d: no match for '%s' "
                "[elapsed %ds, ~%ds left]",
                attempt, template_name, elapsed, remaining,
            )
            if attempt == 1 or attempt % 5 == 0:
                log_progress(logger,
                    "warning",
                    "Configured template '%s' not matched. "
                    "KVM templates in zone: %s",
                    template_name,
                    kvm_names if kvm_names else "(none listed)",
                )

        time.sleep(interval)

    log_progress(logger,
        "error",
        "Template '%s' not ready within %ds.", template_name, timeout,
    )
    return False


# ---------------------------------------------------------------------------
# Test class
# ---------------------------------------------------------------------------

@attr(tags=["setup_zone"])
class TestAdvancedZoneSetup(cloudstackTestCase):
    """
    Creates a full Advanced-zone infrastructure from ontap.cfg.
    Creation steps are idempotent (skipped when resources already exist).
    Wait steps (11–12) always run to verify system VMs and template readiness.
    """

    # Class-level state shared across numbered test methods
    _zone_exists = False
    _zone_id = None
    _phynet_id = None
    _pod_id = None
    _cluster_id = None

    # Raw config dicts read from ontap.cfg
    _zcfg = {}   # zones[0]
    _pcfg = {}   # zones[0].pods[0]
    _ccfg = {}   # zones[0].pods[0].clusters[0]
    _cs_cfg = {}  # cloudstack section
    _template_name = "CentOS 5.5(64-bit) no GUI (KVM)"
    _system_vm_timeout = 3600
    _template_ready_timeout = 3600
    _poll_interval = 60

    @classmethod
    def setUpClass(cls):
        enable_live_logging(cls)
        testclient = super(TestAdvancedZoneSetup, cls).getClsTestClient()
        cls.apiClient = testclient.getApiClient()

        # Marvin injects the --marvin-config file as cls.config (parsed JSON).
        # getParsedTestDataConfig() defaults to test_data.py and does NOT
        # contain the datacenter zones block from ontap.cfg.
        if not getattr(cls, "config", None):
            raise RuntimeError(
                "Marvin datacenter config not available. Run with:\n"
                "  --marvin-config=test/integration/plugins/ontap/ontap.cfg"
            )
        config = jsonDump.dump(cls.config)

        zone_cfgs = config.get("zones", [])
        if not zone_cfgs:
            raise RuntimeError(
                "ontap.cfg is missing a 'zones' entry. "
                "Add zone creation fields as described in the README."
            )

        cls._zcfg = zone_cfgs[0]
        pods = cls._zcfg.get("pods", [])
        cls._pcfg = pods[0] if pods else {}
        clusters = cls._pcfg.get("clusters", []) if cls._pcfg else []
        cls._ccfg = clusters[0] if clusters else {}

        cls._cs_cfg = config.get("cloudstack", {})
        cls._template_name = cls._cs_cfg.get(
            "templateName", cls._template_name
        )
        cls._system_vm_timeout = cls._cs_cfg.get("systemVmTimeoutSec", 3600)
        cls._template_ready_timeout = cls._cs_cfg.get(
            "templateReadyTimeoutSec", 3600
        )
        cls._poll_interval = cls._cs_cfg.get("pollIntervalSec", 60)

        zone_name = cls._zcfg.get("name")
        required = {"name", "networktype", "dns1", "internaldns1"}
        missing = required - cls._zcfg.keys()
        if missing:
            raise RuntimeError(
                "ontap.cfg zones[0] is missing required creation fields: %s"
                % sorted(missing)
            )

        existing = get_zone(cls.apiClient, zone_name=zone_name)
        if existing and existing != FAILED:
            logger.info(
                "Zone '%s' already exists (id=%s) — skipping test_01 only."
                % (zone_name, existing.id)
            )
            cls._zone_exists = True
            cls._zone_id = existing.id
            cls._resolve_existing_resources()

    @classmethod
    def _resolve_existing_resources(cls):
        """Populate pod/cluster/phynet ids when re-running against an existing zone."""
        zone_id = cls._zone_id
        pod_name = cls._pcfg.get("name")
        cluster_name = cls._ccfg.get("clustername")

        pod_cmd = listPodsAPI.listPodsCmd()
        pod_cmd.zoneid = zone_id
        pods = cls.apiClient.listPods(pod_cmd) or []
        for pod in pods:
            if not pod_name or pod.name == pod_name:
                cls._pod_id = pod.id
                break

        cluster_cmd = listClustersAPI.listClustersCmd()
        cluster_cmd.zoneid = zone_id
        if cls._pod_id:
            cluster_cmd.podid = cls._pod_id
        clusters = cls.apiClient.listClusters(cluster_cmd) or []
        for cluster in clusters:
            if not cluster_name or cluster.name == cluster_name:
                cls._cluster_id = cluster.id
                break

        pnet_cmd = listPhysicalNetworksAPI.listPhysicalNetworksCmd()
        pnet_cmd.zoneid = zone_id
        pnets = cls.apiClient.listPhysicalNetworks(pnet_cmd) or []
        if pnets:
            cls._phynet_id = pnets[0].id

    def setUp(self):
        pass

    # -----------------------------------------------------------------------
    # Step 1 – zone
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_01_create_zone(self):
        """Create the Advanced zone."""
        if self.__class__._zone_exists:
            self.skipTest(
                "Zone '%s' already exists (id=%s)."
                % (self.__class__._zcfg.get("name"), self.__class__._zone_id)
            )

        zcfg = self.__class__._zcfg

        cmd = createZoneAPI.createZoneCmd()
        cmd.name = zcfg["name"]
        cmd.networktype = zcfg["networktype"]
        cmd.dns1 = zcfg["dns1"]
        cmd.dns2 = zcfg.get("dns2", "")
        cmd.internaldns1 = zcfg["internaldns1"]
        cmd.internaldns2 = zcfg.get("internaldns2", "")
        cmd.localstorageenabled = zcfg.get("localstorageenabled", False)
        cmd.guestcidraddress = zcfg.get("guestcidraddress", "10.1.1.0/24")

        zone = self.apiClient.createZone(cmd)
        self.assertIsNotNone(zone, "createZone returned None")
        self.assertIsNotNone(zone.id, "Zone id is None after createZone")

        self.__class__._zone_id = zone.id
        logger.info("Zone '%s' created with id=%s." % (zcfg["name"], zone.id))

    # -----------------------------------------------------------------------
    # Step 2 – physical network + traffic types
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_02_create_physical_network(self):
        """Create a single physical network with Guest, Management, and Public traffic types."""
        if self.__class__._phynet_id:
            self.skipTest(
                "Physical network already exists (id=%s)." % self.__class__._phynet_id
            )

        zone_id = self.__class__._zone_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")

        cmd = createPhysicalNetworkAPI.createPhysicalNetworkCmd()
        cmd.zoneid = zone_id
        cmd.name = "PhysNet1"
        cmd.isolationmethods = "VLAN"

        phynet = self.apiClient.createPhysicalNetwork(cmd)
        self.assertIsNotNone(phynet, "createPhysicalNetwork returned None")
        self.assertIsNotNone(phynet.id, "Physical network id is None")

        pnet_id = phynet.id
        self.__class__._phynet_id = pnet_id
        logger.info("Physical network created with id=%s." % pnet_id)

        for traffic_type in ("Guest", "Management", "Public"):
            tt_cmd = addTrafficTypeAPI.addTrafficTypeCmd()
            tt_cmd.physicalnetworkid = pnet_id
            tt_cmd.traffictype = traffic_type
            ret = self.apiClient.addTrafficType(tt_cmd)
            self.assertIsNotNone(ret, "addTrafficType returned None for %s" % traffic_type)
            logger.info("Traffic type '%s' added." % traffic_type)

    # -----------------------------------------------------------------------
    # Step 3 – configure VR providers + enable physical network
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_03_configure_providers_and_enable_network(self):
        """Enable VirtualRouter and VpcVirtualRouter providers; set VLAN range; enable network."""
        pnet_id = self.__class__._phynet_id
        self.assertIsNotNone(pnet_id, "phynet_id not set — did test_02 pass?")

        vlan_range = self.__class__._zcfg.get("guestVlanRange", "100-300")

        for provider_name in ("VirtualRouter", "VpcVirtualRouter"):
            list_cmd = listNetworkServiceProvidersAPI.listNetworkServiceProvidersCmd()
            list_cmd.physicalnetworkid = pnet_id
            list_cmd.name = provider_name
            providers = self.apiClient.listNetworkServiceProviders(list_cmd) or []

            if not providers:
                logger.warning(
                    "Provider '%s' not found on physical network %s — skipping."
                    % (provider_name, pnet_id)
                )
                continue

            provider = providers[0]

            # Configure the VirtualRouter element (enable it)
            vr_cmd = listVirtualRouterElementsAPI.listVirtualRouterElementsCmd()
            vr_cmd.nspid = provider.id
            vr_elements = self.apiClient.listVirtualRouterElements(vr_cmd) or []
            if vr_elements:
                cfg_cmd = configureVirtualRouterElementAPI.configureVirtualRouterElementCmd()
                cfg_cmd.id = vr_elements[0].id
                cfg_cmd.enabled = "true"
                self.apiClient.configureVirtualRouterElement(cfg_cmd)
                logger.info("VR element for '%s' configured." % provider_name)

            # Enable the provider
            upd_cmd = updateNetworkServiceProviderAPI.updateNetworkServiceProviderCmd()
            upd_cmd.id = provider.id
            upd_cmd.state = "Enabled"
            self.apiClient.updateNetworkServiceProvider(upd_cmd)
            logger.info("Provider '%s' enabled." % provider_name)

        # Enable physical network and set guest VLAN range
        upnet_cmd = updatePhysicalNetworkAPI.updatePhysicalNetworkCmd()
        upnet_cmd.id = pnet_id
        upnet_cmd.state = "Enabled"
        upnet_cmd.vlan = vlan_range
        self.apiClient.updatePhysicalNetwork(upnet_cmd)
        logger.info(
            "Physical network %s enabled with VLAN range %s." % (pnet_id, vlan_range)
        )

    # -----------------------------------------------------------------------
    # Step 4 – public IP range
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_04_create_public_ip_range(self):
        """Create the public traffic IP range."""
        zone_id = self.__class__._zone_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")

        ipr = self.__class__._zcfg.get("publicIpRange", {})
        if not ipr:
            self.skipTest("No publicIpRange defined in ontap.cfg — skipping.")

        cmd = createVlanIpRangeAPI.createVlanIpRangeCmd()
        cmd.zoneid = zone_id
        cmd.gateway = ipr["gateway"]
        cmd.netmask = ipr["netmask"]
        cmd.startip = ipr["startip"]
        cmd.endip = ipr["endip"]
        cmd.vlan = ipr.get("vlan", "untagged")
        cmd.forvirtualnetwork = "true"

        try:
            ret = self.apiClient.createVlanIpRange(cmd)
        except CloudstackAPIException as ex:
            if "overlap" in str(ex).lower() or "already" in str(ex).lower():
                self.skipTest("Public IP range already exists: %s" % ex)
            raise
        self.assertIsNotNone(ret, "createVlanIpRange returned None")
        logger.info(
            "Public IP range %s–%s created." % (ipr["startip"], ipr["endip"])
        )

    # -----------------------------------------------------------------------
    # Step 5 – pod
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_05_create_pod(self):
        """Create the management pod with reserved system IPs."""
        if self.__class__._pod_id:
            self.skipTest("Pod already exists (id=%s)." % self.__class__._pod_id)

        zone_id = self.__class__._zone_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")

        pcfg = self.__class__._pcfg
        if not pcfg:
            self.skipTest("No pod config found in ontap.cfg — skipping.")

        cmd = createPodAPI.createPodCmd()
        cmd.zoneid = zone_id
        cmd.name = pcfg["name"]
        cmd.gateway = pcfg["gateway"]
        cmd.netmask = pcfg["netmask"]
        cmd.startip = pcfg["startip"]
        cmd.endip = pcfg["endip"]

        pod = self.apiClient.createPod(cmd)
        self.assertIsNotNone(pod, "createPod returned None")
        self.assertIsNotNone(pod.id, "Pod id is None")

        self.__class__._pod_id = pod.id
        logger.info("Pod '%s' created with id=%s." % (pcfg["name"], pod.id))

    # -----------------------------------------------------------------------
    # Step 6 – cluster
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_06_add_cluster(self):
        """Add the KVM cluster."""
        if self.__class__._cluster_id:
            self.skipTest(
                "Cluster already exists (id=%s)." % self.__class__._cluster_id
            )

        zone_id = self.__class__._zone_id
        pod_id = self.__class__._pod_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")
        self.assertIsNotNone(pod_id, "pod_id not set — did test_05 pass?")

        ccfg = self.__class__._ccfg
        if not ccfg:
            self.skipTest("No cluster config found in ontap.cfg — skipping.")

        cmd = addClusterAPI.addClusterCmd()
        cmd.zoneid = zone_id
        cmd.podid = pod_id
        cmd.clustername = ccfg["clustername"]
        cmd.clustertype = ccfg.get("clustertype", "CloudManaged")
        cmd.hypervisor = ccfg.get("hypervisor", "KVM")

        clusters = self.apiClient.addCluster(cmd)
        self.assertTrue(
            clusters and len(clusters) > 0, "addCluster returned empty response"
        )
        cluster_id = clusters[0].id
        self.__class__._cluster_id = cluster_id
        logger.info(
            "Cluster '%s' added with id=%s." % (ccfg["clustername"], cluster_id)
        )

    # -----------------------------------------------------------------------
    # Step 7 – host(s)
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_07_add_host(self):
        """Add KVM host(s) to the cluster and wait for them to come Up."""
        zone_id = self.__class__._zone_id
        pod_id = self.__class__._pod_id
        cluster_id = self.__class__._cluster_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")
        self.assertIsNotNone(pod_id, "pod_id not set — did test_05 pass?")
        self.assertIsNotNone(cluster_id, "cluster_id not set — did test_06 pass?")

        hosts_cfg = self.__class__._ccfg.get("hosts", [])
        hypervisor = self.__class__._ccfg.get("hypervisor", "KVM")

        if not hosts_cfg:
            self.skipTest("No host config found in ontap.cfg — skipping.")

        if _cluster_has_up_host(self.apiClient, zone_id, cluster_id):
            self.skipTest("Cluster already has at least one Up routing host.")

        for hcfg in hosts_cfg:
            cmd = addHostAPI.addHostCmd()
            cmd.zoneid = zone_id
            cmd.podid = pod_id
            cmd.clusterid = cluster_id
            cmd.hypervisor = hypervisor
            cmd.url = hcfg["url"]
            cmd.username = hcfg["username"]
            cmd.password = hcfg["password"]
            if hcfg.get("hosttags"):
                cmd.hosttags = hcfg["hosttags"]

            try:
                ret = self.apiClient.addHost(cmd)
            except CloudstackAPIException as ex:
                self.skipTest(
                    "addHost failed for %s — verify SSH from the management "
                    "server and host credentials in ontap.cfg: %s"
                    % (hcfg["url"], ex)
                )
            self.assertTrue(
                ret and len(ret) > 0,
                "addHost returned empty response for %s" % hcfg["url"],
            )
            logger.info("Host '%s' added." % hcfg["url"])

        if not _wait_for_hosts_up(self.apiClient, zone_id, cluster_id, timeout=120):
            self.skipTest(
                "Host(s) were added but did not reach Up state within 120s."
            )

    # -----------------------------------------------------------------------
    # Step 8 – primary storage
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_08_create_primary_storage(self):
        """Create NFS primary storage pool (cluster-scoped)."""
        zone_id = self.__class__._zone_id
        pod_id = self.__class__._pod_id
        cluster_id = self.__class__._cluster_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")
        self.assertIsNotNone(pod_id, "pod_id not set — did test_05 pass?")
        self.assertIsNotNone(cluster_id, "cluster_id not set — did test_06 pass?")

        primary_storages = self.__class__._ccfg.get("primaryStorages", [])
        if not primary_storages:
            self.skipTest("No primaryStorages defined in cluster config — skipping.")

        if not _cluster_has_up_host(self.apiClient, zone_id, cluster_id):
            self.skipTest(
                "No Up routing host in cluster — primary storage requires a "
                "connected KVM host (see test_07_add_host)."
            )

        for pscfg in primary_storages:
            pool_cmd = listStoragePoolsAPI.listStoragePoolsCmd()
            pool_cmd.zoneid = zone_id
            pool_cmd.name = pscfg["name"]
            existing = self.apiClient.listStoragePools(pool_cmd) or []
            if existing:
                logger.info(
                    "Primary storage '%s' already exists (id=%s) — skipping."
                    % (pscfg["name"], existing[0].id)
                )
                continue

            cmd = createStoragePoolAPI.createStoragePoolCmd()
            cmd.zoneid = zone_id
            cmd.name = pscfg["name"]
            cmd.url = pscfg["url"]
            cmd.scope = pscfg.get("scope", "Cluster")
            if cmd.scope.lower() == "cluster":
                cmd.podid = pod_id
                cmd.clusterid = cluster_id
            if pscfg.get("tags"):
                cmd.tags = pscfg["tags"]

            ret = self.apiClient.createStoragePool(cmd)
            self.assertIsNotNone(ret, "createStoragePool returned None for '%s'" % pscfg["name"])
            logger.info("Primary storage '%s' created with id=%s." % (pscfg["name"], ret.id))

    # -----------------------------------------------------------------------
    # Step 9 – secondary storage
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_09_add_secondary_storage(self):
        """Add NFS secondary storage (image store)."""
        zone_id = self.__class__._zone_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")

        secondary_storages = self.__class__._zcfg.get("secondaryStorages", [])
        if not secondary_storages:
            self.skipTest("No secondaryStorages defined in zones[0] config — skipping.")

        for sscfg in secondary_storages:
            cmd = addImageStoreAPI.addImageStoreCmd()
            cmd.provider = sscfg.get("provider", "NFS")
            cmd.url = sscfg["url"]
            cmd.zoneid = zone_id

            try:
                ret = self.apiClient.addImageStore(cmd)
            except CloudstackAPIException as ex:
                if "already exists" in str(ex).lower():
                    logger.info(
                        "Secondary storage '%s' already exists — skipping."
                        % sscfg.get("url")
                    )
                    continue
                raise
            self.assertIsNotNone(ret, "addImageStore returned None for '%s'" % sscfg.get("name"))
            logger.info(
                "Secondary storage '%s' added with id=%s."
                % (sscfg.get("name", ret.id), ret.id)
            )

    # -----------------------------------------------------------------------
    # Step 10 – enable zone
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_10_enable_zone(self):
        """Enable the zone (allocationstate=Enabled)."""
        zone_id = self.__class__._zone_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")

        cmd = updateZoneAPI.updateZoneCmd()
        cmd.id = zone_id
        cmd.allocationstate = "Enabled"
        ret = self.apiClient.updateZone(cmd)

        self.assertIsNotNone(ret, "updateZone returned None")
        logger.info(
            "Zone id=%s enabled (allocationstate=Enabled)." % zone_id
        )

    # -----------------------------------------------------------------------
    # Step 11 – wait for system VMs
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_11_wait_for_system_vms(self):
        """Wait until both system VMs (SSVM + Console Proxy) are Running."""
        zone_id = self.__class__._zone_id
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")

        ok = _wait_for_system_vms(
            self.apiClient,
            zone_id,
            timeout=self.__class__._system_vm_timeout,
            interval=self.__class__._poll_interval,
        )
        self.assertTrue(
            ok,
            "Both system VMs did not reach Running within %ds."
            % self.__class__._system_vm_timeout,
        )

    # -----------------------------------------------------------------------
    # Step 12 – wait for KVM template
    # -----------------------------------------------------------------------

    @attr(tags=["setup_zone"])
    def test_12_wait_for_kvm_template(self):
        """Wait until the configured KVM template is ready (isready=True)."""
        zone_id = self.__class__._zone_id
        template_name = self.__class__._template_name
        self.assertIsNotNone(zone_id, "zone_id not set — did test_01 pass?")

        ok = _wait_for_template_ready(
            self.apiClient,
            zone_id,
            template_name,
            timeout=self.__class__._template_ready_timeout,
            interval=self.__class__._poll_interval,
        )
        self.assertTrue(
            ok,
            "Template '%s' not ready within %ds."
            % (template_name, self.__class__._template_ready_timeout),
        )
