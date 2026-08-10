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
Full teardown of the Advanced zone created by setup_zone.

Destroys user VMs, all primary storage pools (NFS + ONTAP), secondary
storage, guest networks, hosts, cluster, pod, physical network, and
the zone itself.  Each step is idempotent (skips when the resource is
already gone).

Tag: cleanup_zone

Usage (from cloudstack root):
  bash test/integration/plugins/ontap/run_tests.sh cleanup_zone

Warning: destructive — not run as part of ``run_tests.sh all``.
"""

import logging
import time
import unittest
from urllib.parse import urlparse

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    cancelStorageMaintenance as cancelStorageMaintenanceAPI,
    deleteCluster as deleteClusterAPI,
    deleteHost as deleteHostAPI,
    deleteImageStore as deleteImageStoreAPI,
    deleteNetwork as deleteNetworkAPI,
    deletePhysicalNetwork as deletePhysicalNetworkAPI,
    deletePod as deletePodAPI,
    deleteStoragePool as deleteStoragePoolAPI,
    deleteVlanIpRange as deleteVlanIpRangeAPI,
    deleteVolume as deleteVolumeAPI,
    deleteZone as deleteZoneAPI,
    destroyRouter as destroyRouterAPI,
    destroySystemVm as destroySystemVmAPI,
    destroyVirtualMachine as destroyVirtualMachineAPI,
    destroyVolume as destroyVolumeAPI,
    enableStorageMaintenance as enableStorageMaintenanceAPI,
    listClusters as listClustersAPI,
    listHosts as listHostsAPI,
    listImageStores as listImageStoresAPI,
    listNetworks as listNetworksAPI,
    listPhysicalNetworks as listPhysicalNetworksAPI,
    listPods as listPodsAPI,
    listPublicIpAddresses as listPublicIpAddressesAPI,
    listRouters as listRoutersAPI,
    listStoragePools as listStoragePoolsAPI,
    listSystemVms as listSystemVmsAPI,
    listVirtualMachines as listVirtualMachinesAPI,
    listVolumes as listVolumesAPI,
    listVlanIpRanges as listVlanIpRangesAPI,
    releaseIpAddress as releaseIpAddressAPI,
    stopVirtualMachine as stopVirtualMachineAPI,
    updatePhysicalNetwork as updatePhysicalNetworkAPI,
    updateStoragePool as updateStoragePoolAPI,
    updateZone as updateZoneAPI,
)
from marvin.cloudstackException import CloudstackAPIException
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.codes import FAILED
from marvin.jsonHelper import jsonDump
from marvin.lib.common import get_zone, list_storage_pools
from marvin.sshClient import SshClient

from ontap_test_base import OntapRestClient, enable_live_logging

logger = logging.getLogger("TestAdvancedZoneCleanup")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _list_all_zone_volumes(api_client, zone_id):
    """List user and system VM volumes in the zone (deduped by id)."""
    seen = {}
    for list_system in (False, True):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.zoneid = zone_id
        cmd.listall = True
        if list_system:
            cmd.listsystemvms = True
        for vol in api_client.listVolumes(cmd) or []:
            seen[vol.id] = vol
    return list(seen.values())


def _purge_volume(api_client, vol):
    """Delete or expunge a single volume."""
    state = (getattr(vol, "state", "") or "").lower()
    if state in ("expunged",):
        return
    if state in ("destroy", "destroyed", "expunging"):
        dc = destroyVolumeAPI.destroyVolumeCmd()
        dc.id = vol.id
        dc.expunge = True
        api_client.destroyVolume(dc)
    else:
        try:
            dc = deleteVolumeAPI.deleteVolumeCmd()
            dc.id = vol.id
            api_client.deleteVolume(dc)
        except CloudstackAPIException:
            dc = destroyVolumeAPI.destroyVolumeCmd()
            dc.id = vol.id
            dc.expunge = True
            api_client.destroyVolume(dc)
    logger.info(
        "Purged volume %s (%s) state=%s."
        % (vol.id, getattr(vol, "name", ""), state)
    )


def _purge_all_zone_volumes(api_client, zone_id):
    """Remove every volume CloudStack still tracks for the zone."""
    volumes = _list_all_zone_volumes(api_client, zone_id)
    for vol in volumes:
        try:
            _purge_volume(api_client, vol)
        except CloudstackAPIException as ex:
            logger.warning(
                "Could not purge volume %s: %s" % (vol.id, ex)
            )
    remaining = _list_all_zone_volumes(api_client, zone_id)
    return remaining


def _parse_kvm_ssh_creds(config):
    """Return SSH credential dicts for KVM hosts from ontap.cfg zones block."""
    creds = []
    for zone in config.get("zones", []):
        for pod in zone.get("pods", []):
            for cluster in pod.get("clusters", []):
                for host_cfg in cluster.get("hosts", []):
                    host_ip = urlparse(host_cfg.get("url", "")).hostname or ""
                    if host_ip:
                        creds.append({
                            "host": host_ip,
                            "user": host_cfg.get("username", "root"),
                            "password": host_cfg.get("password", ""),
                        })
    return creds


def _cleanup_kvm_storage_pool_mounts(pool_uuid, kvm_creds):
    """Unmount and undefine libvirt NFS pool on each KVM host."""
    for creds in kvm_creds:
        host_ip = creds["host"]
        try:
            ssh = SshClient(
                host_ip, 22,
                creds["user"], creds["password"],
                retries=3, delay=3, timeout=15.0,
            )
            for cmd in [
                "umount -f -l /mnt/{u} 2>/dev/null; true".format(u=pool_uuid),
                "virsh pool-destroy {u} 2>/dev/null; true".format(u=pool_uuid),
                "virsh pool-undefine {u} 2>/dev/null; true".format(u=pool_uuid),
            ]:
                try:
                    ssh.execute(cmd)
                except Exception as cmd_ex:
                    logger.warning(
                        "KVM cleanup cmd '%s' failed on %s: %s"
                        % (cmd, host_ip, cmd_ex)
                    )
        except Exception as ex:
            logger.warning("KVM cleanup SSH to %s failed: %s" % (host_ip, ex))


def _pool_provider(pool):
    return (getattr(pool, "provider", "") or "").upper()


def _is_ontap_pool(pool):
    return "ONTAP" in _pool_provider(pool)


def _list_pools_in_zone(api_client, zone_id):
    cmd = listStoragePoolsAPI.listStoragePoolsCmd()
    cmd.zoneid = zone_id
    return api_client.listStoragePools(cmd) or []


def _delete_volumes_on_pool(api_client, pool_id):
    cmd = listVolumesAPI.listVolumesCmd()
    cmd.listall = True
    cmd.storagepoolid = pool_id
    volumes = api_client.listVolumes(cmd) or []
    for vol in volumes:
        try:
            dc = deleteVolumeAPI.deleteVolumeCmd()
            dc.id = vol.id
            api_client.deleteVolume(dc)
            logger.info("Deleted volume %s on pool %s." % (vol.id, pool_id))
        except CloudstackAPIException as ex:
            logger.warning(
                "Could not delete volume %s on pool %s: %s"
                % (vol.id, pool_id, ex)
            )


def _force_delete_pool(
        api_client, pool, ontap_client=None, kvm_creds=None, svm_name=None):
    """
    Delete a storage pool: volumes → maintenance → forced delete.
    Optional ONTAP REST cleanup on failure; optional KVM NFS unmount.
    """
    pool_id = pool.id
    pool_name = pool.name
    pools = list_storage_pools(api_client, id=pool_id)
    if not pools:
        logger.info("Pool '%s' already gone." % pool_name)
        return True

    pool_state = pools[0].state

    if pool_state == "Maintenance":
        try:
            cc = cancelStorageMaintenanceAPI.cancelStorageMaintenanceCmd()
            cc.id = pool_id
            api_client.cancelStorageMaintenance(cc)
            time.sleep(5)
        except Exception:
            pass
        try:
            ec = updateStoragePoolAPI.updateStoragePoolCmd()
            ec.id = pool_id
            ec.enabled = True
            api_client.updateStoragePool(ec)
            time.sleep(3)
        except Exception:
            pass
        pools = list_storage_pools(api_client, id=pool_id)
        if pools:
            pool_state = pools[0].state

    _delete_volumes_on_pool(api_client, pool_id)

    if pool_state in ("Up", "Disabled"):
        try:
            mc = enableStorageMaintenanceAPI.enableStorageMaintenanceCmd()
            mc.id = pool_id
            api_client.enableStorageMaintenance(mc)
            deadline = time.time() + 60
            while time.time() < deadline:
                ps = list_storage_pools(api_client, id=pool_id)
                if ps and ps[0].state == "Maintenance":
                    break
                time.sleep(5)
        except Exception as ex:
            logger.warning(
                "Could not enter maintenance for pool '%s': %s"
                % (pool_name, ex)
            )

    if kvm_creds:
        _cleanup_kvm_storage_pool_mounts(pool_id, kvm_creds)

    try:
        dc = deleteStoragePoolAPI.deleteStoragePoolCmd()
        dc.id = pool_id
        dc.forced = True
        api_client.deleteStoragePool(dc)
        logger.info("Deleted storage pool '%s' (id=%s)." % (pool_name, pool_id))
        return True
    except CloudstackAPIException as ex:
        logger.warning(
            "deleteStoragePool failed for '%s': %s" % (pool_name, ex)
        )
        if ontap_client is not None:
            try:
                ontap_client.delete_volume(pool_name)
                logger.info(
                    "Deleted ONTAP FlexVol '%s' directly." % pool_name
                )
            except Exception as oe:
                logger.warning(
                    "ONTAP FlexVol delete '%s' failed: %s" % (pool_name, oe)
                )
            try:
                ep_name = "cs-%s-%s" % (svm_name or "", pool_name)
                ontap_client.delete_export_policy(ep_name)
                logger.info(
                    "Deleted export policy '%s' directly." % ep_name
                )
            except Exception:
                pass
            pools = list_storage_pools(api_client, id=pool_id)
            if not pools:
                return True
        return False


def _stop_and_destroy_vm(api_client, vm):
    state = (getattr(vm, "state", "") or "").lower()
    if state == "running":
        try:
            sc = stopVirtualMachineAPI.stopVirtualMachineCmd()
            sc.id = vm.id
            api_client.stopVirtualMachine(sc)
            deadline = time.time() + 120
            while time.time() < deadline:
                lcmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
                lcmd.id = vm.id
                cur = api_client.listVirtualMachines(lcmd) or []
                if cur and cur[0].state.lower() in ("stopped", "destroyed"):
                    break
                time.sleep(5)
        except CloudstackAPIException as ex:
            logger.warning("Could not stop VM %s: %s" % (vm.id, ex))

    dc = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
    dc.id = vm.id
    dc.expunge = True
    api_client.destroyVirtualMachine(dc)
    logger.info("Destroyed VM '%s' (id=%s)." % (vm.name, vm.id))


def _destroy_system_vms_and_routers(api_client, zone_id):
    """Destroy SSVM, console proxy, and virtual routers in the zone."""
    sys_cmd = listSystemVmsAPI.listSystemVmsCmd()
    sys_cmd.zoneid = zone_id
    sysvms = api_client.listSystemVms(sys_cmd) or []
    for svm in sysvms:
        try:
            dc = destroySystemVmAPI.destroySystemVmCmd()
            dc.id = svm.id
            api_client.destroySystemVm(dc)
            logger.info(
                "Destroyed system VM %s type=%s id=%s"
                % (svm.name, svm.systemvmtype, svm.id)
            )
        except CloudstackAPIException as ex:
            logger.warning(
                "Could not destroy system VM %s: %s" % (svm.id, ex)
            )

    router_cmd = listRoutersAPI.listRoutersCmd()
    router_cmd.zoneid = zone_id
    router_cmd.listall = True
    routers = api_client.listRouters(router_cmd) or []
    for router in routers:
        try:
            dc = destroyRouterAPI.destroyRouterCmd()
            dc.id = router.id
            api_client.destroyRouter(dc)
            logger.info("Destroyed router %s id=%s." % (router.name, router.id))
        except CloudstackAPIException as ex:
            logger.warning(
                "Could not destroy router %s: %s" % (router.id, ex)
            )


def _release_zone_public_ips(api_client, zone_id):
    """Release all allocated public IPs in the zone."""
    cmd = listPublicIpAddressesAPI.listPublicIpAddressesCmd()
    cmd.zoneid = zone_id
    cmd.listall = True
    cmd.allocatedonly = True
    ips = api_client.listPublicIpAddresses(cmd) or []
    for ip in ips:
        try:
            rc = releaseIpAddressAPI.releaseIpAddressCmd()
            rc.id = ip.id
            api_client.releaseIpAddress(rc)
            logger.info(
                "Released public IP %s (id=%s)."
                % (getattr(ip, "ipaddress", ip.id), ip.id)
            )
        except CloudstackAPIException as ex:
            logger.warning(
                "Could not release public IP %s: %s" % (ip.id, ex)
            )


# ---------------------------------------------------------------------------
# Test class
# ---------------------------------------------------------------------------

@attr(tags=["cleanup_zone"])
class TestAdvancedZoneCleanup(cloudstackTestCase):
    """
    Tears down the full Advanced zone from ontap.cfg.
    Idempotent: skips steps when resources are already removed.
    """

    _zone_id = None
    _zone_name = None
    _phynet_id = None
    _pod_id = None
    _cluster_id = None

    _zcfg = {}
    _pcfg = {}
    _ccfg = {}
    _kvm_creds = []
    _ontap_client = None
    _svm_name = None

    @classmethod
    def setUpClass(cls):
        enable_live_logging(cls)
        testclient = super(TestAdvancedZoneCleanup, cls).getClsTestClient()
        cls.apiClient = testclient.getApiClient()

        if not getattr(cls, "config", None):
            raise RuntimeError(
                "Marvin datacenter config not available. Run with:\n"
                "  --marvin-config=test/integration/plugins/ontap/ontap.cfg"
            )
        config = jsonDump.dump(cls.config)

        zone_cfgs = config.get("zones", [])
        if not zone_cfgs:
            raise unittest.SkipTest("No zones block in ontap.cfg — nothing to clean up.")

        cls._zcfg = zone_cfgs[0]
        pods = cls._zcfg.get("pods", [])
        cls._pcfg = pods[0] if pods else {}
        clusters = cls._pcfg.get("clusters", []) if cls._pcfg else []
        cls._ccfg = clusters[0] if clusters else {}

        cs_cfg = config.get("cloudstack", {})
        cls._zone_name = cs_cfg.get("zoneName") or cls._zcfg.get("name")
        cls._kvm_creds = _parse_kvm_ssh_creds(config)

        ontap_cfg = config.get("ontap", {})
        if ontap_cfg.get("storageIP"):
            cls._ontap_client = OntapRestClient(
                ontap_cfg["storageIP"],
                ontap_cfg.get("username", "admin"),
                ontap_cfg.get("password", ""),
            )
            cls._svm_name = ontap_cfg.get("svmName", "")

        existing = get_zone(cls.apiClient, zone_name=cls._zone_name)
        if not existing or existing == FAILED:
            raise unittest.SkipTest(
                "Zone '%s' not found — nothing to clean up." % cls._zone_name
            )

        cls._zone_id = existing.id
        cls._resolve_resources()

    @classmethod
    def _resolve_resources(cls):
        zone_id = cls._zone_id
        pod_name = cls._pcfg.get("name")
        cluster_name = cls._ccfg.get("clustername")

        pod_cmd = listPodsAPI.listPodsCmd()
        pod_cmd.zoneid = zone_id
        for pod in cls.apiClient.listPods(pod_cmd) or []:
            if not pod_name or pod.name == pod_name:
                cls._pod_id = pod.id
                break

        cluster_cmd = listClustersAPI.listClustersCmd()
        cluster_cmd.zoneid = zone_id
        if cls._pod_id:
            cluster_cmd.podid = cls._pod_id
        for cluster in cls.apiClient.listClusters(cluster_cmd) or []:
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
    # Step 01 – disable zone
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_01_disable_zone(self):
        """Disable the zone before removing resources."""
        zone_id = self.__class__._zone_id
        cmd = updateZoneAPI.updateZoneCmd()
        cmd.id = zone_id
        cmd.allocationstate = "Disabled"
        try:
            ret = self.apiClient.updateZone(cmd)
        except CloudstackAPIException as ex:
            if "disabled" in str(ex).lower():
                logger.info("Zone already disabled.")
                return
            raise
        self.assertIsNotNone(ret)
        logger.info("Zone id=%s disabled." % zone_id)

    # -----------------------------------------------------------------------
    # Step 02 – destroy user VMs
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_02_destroy_user_vms(self):
        """Destroy all non-system user VMs in the zone."""
        zone_id = self.__class__._zone_id
        cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
        cmd.zoneid = zone_id
        cmd.listall = True
        vms = self.apiClient.listVirtualMachines(cmd) or []

        user_vms = [
            vm for vm in vms
            if (getattr(vm, "account", "") or "").lower() != "system"
            and getattr(vm, "state", "").lower()
            not in ("destroyed", "expunging", "error")
        ]
        if not user_vms:
            logger.info("No user VMs to destroy in zone.")
            return

        for vm in user_vms:
            try:
                _stop_and_destroy_vm(self.apiClient, vm)
            except CloudstackAPIException as ex:
                logger.warning(
                    "Could not destroy VM %s (%s): %s"
                    % (vm.id, vm.name, ex)
                )

    # -----------------------------------------------------------------------
    # Step 02b – destroy system VMs and routers
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_02_system_vms_destroy(self):
        """Destroy system VMs (SSVM, console proxy) and virtual routers."""
        _destroy_system_vms_and_routers(
            self.apiClient, self.__class__._zone_id
        )

    # -----------------------------------------------------------------------
    # Step 03 – delete volumes
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_03_delete_volumes(self):
        """Delete remaining volumes in the zone (user + system VM volumes)."""
        remaining = _purge_all_zone_volumes(
            self.apiClient, self.__class__._zone_id
        )
        if remaining:
            logger.warning(
                "%d volume(s) still present after purge." % len(remaining)
            )

    # -----------------------------------------------------------------------
    # Step 04 – delete primary storage (NFS / non-ONTAP)
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_04_delete_primary_storage(self):
        """Delete NFS primary pools from config and any remaining non-ONTAP pools."""
        zone_id = self.__class__._zone_id
        all_pools = _list_pools_in_zone(self.apiClient, zone_id)
        targets = [p for p in all_pools if not _is_ontap_pool(p)]

        if not targets:
            logger.info("No primary (non-ONTAP) storage pools to delete.")
            return

        for pool in targets:
            _force_delete_pool(
                self.apiClient, pool,
                kvm_creds=self.__class__._kvm_creds,
            )

    # -----------------------------------------------------------------------
    # Step 05 – delete ONTAP pools
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_05_delete_ontap_pools(self):
        """Delete NetApp ONTAP primary pools left by integration tests."""
        zone_id = self.__class__._zone_id
        ontap_pools = [
            p for p in _list_pools_in_zone(self.apiClient, zone_id)
            if _is_ontap_pool(p)
        ]
        if not ontap_pools:
            logger.info("No ONTAP storage pools to delete.")
            return

        for pool in ontap_pools:
            _force_delete_pool(
                self.apiClient, pool,
                ontap_client=self.__class__._ontap_client,
                kvm_creds=self.__class__._kvm_creds,
                svm_name=self.__class__._svm_name,
            )

    # -----------------------------------------------------------------------
    # Step 06 – delete secondary storage
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_06_delete_secondary_storage(self):
        """Delete all secondary/image stores in the zone."""
        zone_id = self.__class__._zone_id
        cmd = listImageStoresAPI.listImageStoresCmd()
        cmd.zoneid = zone_id
        stores = self.apiClient.listImageStores(cmd) or []
        if not stores:
            logger.info("No image stores to delete in zone.")
            return

        for store in stores:
            store_url = getattr(store, "url", "") or ""
            try:
                dc = deleteImageStoreAPI.deleteImageStoreCmd()
                dc.id = store.id
                self.apiClient.deleteImageStore(dc)
                logger.info(
                    "Deleted image store '%s' (id=%s)." % (store_url, store.id)
                )
            except CloudstackAPIException as ex:
                if "not found" in str(ex).lower():
                    logger.info("Image store already gone.")
                else:
                    logger.warning(
                        "Could not delete image store %s: %s"
                        % (store.id, ex)
                    )

    # -----------------------------------------------------------------------
    # Step 07 – delete guest networks
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_07_delete_guest_networks(self):
        """Delete isolated/guest networks (skip system networks)."""
        zone_id = self.__class__._zone_id
        cmd = listNetworksAPI.listNetworksCmd()
        cmd.zoneid = zone_id
        cmd.listall = True
        networks = self.apiClient.listNetworks(cmd) or []

        skip_types = frozenset({"system", "shared", "l2vlan"})
        for net in networks:
            net_type = (getattr(net, "type", "") or "").lower()
            if net_type in skip_types:
                continue
            if getattr(net, "issystem", False):
                continue
            try:
                dc = deleteNetworkAPI.deleteNetworkCmd()
                dc.id = net.id
                self.apiClient.deleteNetwork(dc)
                logger.info(
                    "Deleted network '%s' (id=%s)." % (net.name, net.id)
                )
            except CloudstackAPIException as ex:
                logger.warning(
                    "Could not delete network %s: %s" % (net.id, ex)
                )

    # -----------------------------------------------------------------------
    # Step 08 – delete hosts
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_08_delete_hosts(self):
        """Remove routing hosts from the zone."""
        zone_id = self.__class__._zone_id
        cmd = listHostsAPI.listHostsCmd()
        cmd.zoneid = zone_id
        cmd.type = "Routing"
        hosts = self.apiClient.listHosts(cmd) or []
        if not hosts:
            logger.info("No routing hosts to delete.")
            return

        for host in hosts:
            try:
                dc = deleteHostAPI.deleteHostCmd()
                dc.id = host.id
                dc.forced = True
                self.apiClient.deleteHost(dc)
                logger.info("Deleted host '%s' (id=%s)." % (host.name, host.id))
            except CloudstackAPIException as ex:
                logger.warning(
                    "Could not delete host %s: %s" % (host.id, ex)
                )

    # -----------------------------------------------------------------------
    # Step 09 – delete cluster
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_09_delete_cluster(self):
        """Delete the cluster from config."""
        zone_id = self.__class__._zone_id
        cluster_id = self.__class__._cluster_id
        cluster_name = self.__class__._ccfg.get("clustername")

        if not cluster_id and cluster_name:
            cmd = listClustersAPI.listClustersCmd()
            cmd.zoneid = zone_id
            for c in self.apiClient.listClusters(cmd) or []:
                if c.name == cluster_name:
                    cluster_id = c.id
                    break

        if not cluster_id:
            logger.info("No cluster to delete.")
            return

        try:
            dc = deleteClusterAPI.deleteClusterCmd()
            dc.id = cluster_id
            self.apiClient.deleteCluster(dc)
            logger.info("Deleted cluster id=%s." % cluster_id)
        except CloudstackAPIException as ex:
            if "not found" in str(ex).lower():
                logger.info("Cluster already gone.")
            else:
                logger.warning("Could not delete cluster %s: %s" % (cluster_id, ex))

    # -----------------------------------------------------------------------
    # Step 10 – delete pod
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_10_delete_pod(self):
        """Delete the pod from config."""
        zone_id = self.__class__._zone_id
        pod_id = self.__class__._pod_id
        pod_name = self.__class__._pcfg.get("name")

        if not pod_id and pod_name:
            cmd = listPodsAPI.listPodsCmd()
            cmd.zoneid = zone_id
            for p in self.apiClient.listPods(cmd) or []:
                if p.name == pod_name:
                    pod_id = p.id
                    break

        if not pod_id:
            logger.info("No pod to delete.")
            return

        try:
            dc = deletePodAPI.deletePodCmd()
            dc.id = pod_id
            self.apiClient.deletePod(dc)
            logger.info("Deleted pod id=%s." % pod_id)
        except CloudstackAPIException as ex:
            if "not found" in str(ex).lower():
                logger.info("Pod already gone.")
            else:
                logger.warning("Could not delete pod %s: %s" % (pod_id, ex))

    # -----------------------------------------------------------------------
    # Step 10a – release public IPs
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_10a_release_public_ips(self):
        """Release allocated public IPs before deleting VLAN ranges."""
        _release_zone_public_ips(self.apiClient, self.__class__._zone_id)

    # -----------------------------------------------------------------------
    # Step 11 – delete public IP ranges
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_11_delete_public_ip_ranges(self):
        """Delete VLAN IP ranges on the physical network."""
        phynet_id = self.__class__._phynet_id
        if not phynet_id:
            logger.info("No physical network — skipping IP range deletion.")
            return

        cmd = listVlanIpRangesAPI.listVlanIpRangesCmd()
        cmd.physicalnetworkid = phynet_id
        ranges = self.apiClient.listVlanIpRanges(cmd) or []
        if not ranges:
            logger.info("No public IP ranges to delete.")
            return

        for ipr in ranges:
            try:
                dc = deleteVlanIpRangeAPI.deleteVlanIpRangeCmd()
                dc.id = ipr.id
                self.apiClient.deleteVlanIpRange(dc)
                logger.info("Deleted IP range id=%s." % ipr.id)
            except CloudstackAPIException as ex:
                logger.warning(
                    "Could not delete IP range %s: %s" % (ipr.id, ex)
                )

    # -----------------------------------------------------------------------
    # Step 12 – delete physical network
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_12_delete_physical_network(self):
        """Disable and delete the physical network."""
        phynet_id = self.__class__._phynet_id
        if not phynet_id:
            logger.info("No physical network to delete.")
            return

        try:
            up = updatePhysicalNetworkAPI.updatePhysicalNetworkCmd()
            up.id = phynet_id
            up.state = "Disabled"
            self.apiClient.updatePhysicalNetwork(up)
        except CloudstackAPIException as ex:
            logger.warning(
                "Could not disable physical network %s: %s" % (phynet_id, ex)
            )

        try:
            dc = deletePhysicalNetworkAPI.deletePhysicalNetworkCmd()
            dc.id = phynet_id
            self.apiClient.deletePhysicalNetwork(dc)
            logger.info("Deleted physical network id=%s." % phynet_id)
        except CloudstackAPIException as ex:
            if "not found" in str(ex).lower():
                logger.info("Physical network already gone.")
            else:
                raise

    # -----------------------------------------------------------------------
    # Step 12b – final volume purge before deleteZone
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_12b_purge_zone_volumes(self):
        """Expunge any volumes still blocking deleteZone (incl. system VM disks)."""
        remaining = _purge_all_zone_volumes(
            self.apiClient, self.__class__._zone_id
        )
        if remaining:
            self.fail(
                "%d volume(s) still in zone after final purge: %s"
                % (len(remaining), [v.id for v in remaining])
            )

    # -----------------------------------------------------------------------
    # Step 13 – delete zone
    # -----------------------------------------------------------------------

    @attr(tags=["cleanup_zone"])
    def test_13_delete_zone(self):
        """Delete the zone — final step."""
        zone_id = self.__class__._zone_id
        zone_name = self.__class__._zone_name

        # Safety net: purge volumes that block deleteZone (e.g. system VM ROOT disks).
        _purge_all_zone_volumes(self.apiClient, zone_id)

        try:
            dc = deleteZoneAPI.deleteZoneCmd()
            dc.id = zone_id
            self.apiClient.deleteZone(dc)
        except CloudstackAPIException as ex:
            self.fail(
                "deleteZone failed for '%s' (id=%s): %s\n"
                "Ensure all VMs, pools, hosts, and storage are removed first."
                % (zone_name, zone_id, ex)
            )

        remaining = get_zone(self.apiClient, zone_name=zone_name)
        self.assertTrue(
            not remaining or remaining == FAILED,
            "Zone '%s' still exists after deleteZone." % zone_name,
        )
        logger.info("Zone '%s' deleted." % zone_name)
