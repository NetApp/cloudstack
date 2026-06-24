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
Shared base class and helper utilities for NetApp ONTAP Marvin integration tests.

Provides:
  OntapRestClient    - thin wrapper around the ONTAP REST API (NFS + iSCSI methods)
  _parse_pool_details - converts a StoragePool details attribute to a plain dict
  OntapTestBase      - base cloudstackTestCase with common tearDownClass,
                       _poll_pool_state, _create_volume, and _delete_pool
"""

import logging
import random
import requests
import time
import urllib3
from urllib.parse import urlparse

from marvin.cloudstackAPI import (
    cancelStorageMaintenance,
    createVolume as createVolumeAPI,
    deleteStoragePool as deleteStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    listDiskOfferings as listDiskOfferingsAPI,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.cloudstackAPI import listHosts as listHostsAPI
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.lib.base import Account, DiskOffering
from marvin.sshClient import SshClient
from marvin.lib.common import get_domain, get_zone, list_clusters, list_storage_pools
from marvin.lib.utils import cleanup_resources

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

logger = logging.getLogger("OntapTestBase")


# ---------------------------------------------------------------------------
# Pool detail helper
# ---------------------------------------------------------------------------

def _parse_pool_details(pool):
    """
    Convert a StoragePool object's ``details`` attribute to a plain Python dict,
    regardless of how Marvin chose to represent it.

    Note: listStoragePools only returns a subset of detail keys
    (volumeUUID, exportPolicyName, exportPolicyId).  For the full set
    use the pool object returned directly by createStoragePool.
    """
    details_raw = getattr(pool, "details", None)
    if not details_raw:
        return {}
    if isinstance(details_raw, dict):
        return details_raw
    if isinstance(details_raw, list):
        return {d.name: d.value for d in details_raw}
    return {
        k: v for k, v in vars(details_raw).items()
        if not k.startswith("_") and k != "typeInfo"
    }


# ---------------------------------------------------------------------------
# ONTAP REST helper
# ---------------------------------------------------------------------------

class OntapRestClient:
    """Thin wrapper around the ONTAP REST API for backend validation."""

    def __init__(self, storage_ip, username, password, port=443):
        self._base = "https://%s:%d/api" % (storage_ip, port)
        self._auth = (username, password)

    def _get(self, path, params=None):
        url = self._base + path
        resp = requests.get(url, auth=self._auth, params=params,
                            verify=False, timeout=30)
        resp.raise_for_status()
        return resp.json()

    def _delete(self, path, params=None):
        url = self._base + path
        resp = requests.delete(url, auth=self._auth, params=params,
                               verify=False, timeout=30)
        resp.raise_for_status()

    def delete_volume(self, name):
        """Delete the ONTAP FlexVol with the given name. No-op if not found."""
        data = self._get("/storage/volumes", params={"name": name})
        records = data.get("records", [])
        if not records:
            return
        uuid = records[0].get("uuid")
        if uuid:
            self._delete("/storage/volumes/%s" % uuid)

    def delete_export_policy(self, name):
        """Delete the NFS export policy with the given name. No-op if not found."""
        data = self._get("/protocols/nfs/export-policies", params={"name": name})
        records = data.get("records", [])
        if not records:
            return
        policy_id = records[0].get("id")
        if policy_id:
            self._delete("/protocols/nfs/export-policies/%s" % policy_id)

    def get_volume(self, name):
        """Return the ONTAP FlexVol record for the given name, or None."""
        data = self._get("/storage/volumes", params={"name": name})
        records = data.get("records", [])
        if not records:
            return None
        uuid = records[0].get("uuid")
        if uuid:
            return self._get("/storage/volumes/%s" % uuid,
                             params={"fields": "name,uuid,state,space"})
        return records[0]

    # -- NFS helpers ---------------------------------------------------------

    def get_export_policy(self, name):
        """Return the ONTAP NFS export policy record for the given name, or None."""
        data = self._get("/protocols/nfs/export-policies", params={"name": name})
        records = data.get("records", [])
        if not records:
            return None
        policy_id = records[0].get("id")
        if policy_id:
            return self._get(
                "/protocols/nfs/export-policies/%s" % policy_id,
                params={"fields": "name,svm,rules"}
            )
        return records[0]

    def get_data_lifs(self, svm_name):
        """Return a list of NFS data LIF IP addresses for the given SVM."""
        data = self._get(
            "/network/ip/interfaces",
            params={"svm.name": svm_name, "services": "data-nfs",
                    "fields": "ip,name"}
        )
        records = data.get("records", [])
        return [r.get("ip", {}).get("address")
                for r in records if r.get("ip", {}).get("address")]

    # -- iSCSI helpers -------------------------------------------------------

    def get_igroup(self, svm_name, igroup_name):
        """Return the ONTAP igroup record, or None if not found."""
        data = self._get("/protocols/san/igroups",
                         params={"svm.name": svm_name, "name": igroup_name,
                                 "fields": "name,uuid,initiators"})
        records = data.get("records", [])
        return records[0] if records else None

    def get_lun(self, svm_name, lun_path):
        """Return the ONTAP LUN record for the given full path, or None."""
        data = self._get("/storage/luns",
                         params={"svm.name": svm_name, "name": lun_path,
                                 "fields": "name,uuid,enabled,status"})
        records = data.get("records", [])
        return records[0] if records else None

    def list_luns_in_volume(self, svm_name, vol_name):
        """Return all LUN records whose path starts with /vol/{vol_name}/."""
        prefix = "/vol/%s/" % vol_name
        data = self._get("/storage/luns",
                         params={"svm.name": svm_name,
                                 "fields": "name,uuid,enabled,status"})
        return [r for r in data.get("records", [])
                if r.get("name", "").startswith(prefix)]

    def list_lun_maps_for_volume(self, svm_name, vol_name):
        """Return all LUN-map records for LUNs residing in the given FlexVol."""
        prefix = "/vol/%s/" % vol_name
        data = self._get("/protocols/san/lun-maps",
                         params={"svm.name": svm_name,
                                 "fields": "lun.name,igroup.name"})
        return [r for r in data.get("records", [])
                if r.get("lun", {}).get("name", "").startswith(prefix)]

    # -- NFS file helpers ----------------------------------------------------

    def list_files_in_volume(self, vol_name, path="/"):
        """Return a list of file names at ``path`` inside the named FlexVol.

        Uses the ONTAP REST file-system API:
          GET /api/storage/volumes/{uuid}/files/{url_encoded_path}

        The path must appear in the URL (not as a query parameter).  The root
        directory is represented as ``%2F``.

        Returns an empty list if the volume does not exist, the path is empty,
        or the request fails.
        """
        vol = self.get_volume(vol_name)
        if not vol:
            return []
        vol_uuid = vol.get("uuid", "")
        if not vol_uuid:
            return []
        # URL-encode the path component (/ → %2F) and embed it in the URL.
        from urllib.parse import quote
        encoded_path = quote(path, safe="")
        try:
            resp = self._get(
                "/storage/volumes/%s/files/%s" % (vol_uuid, encoded_path),
                params={"fields": "name,type", "max_records": "500"}
            )
        except Exception:
            return []
        return [r.get("name", "") for r in resp.get("records", [])
                if r.get("name") not in (".", "..")]


# ---------------------------------------------------------------------------
# Base test class
# ---------------------------------------------------------------------------

class OntapTestBase(cloudstackTestCase):
    """
    Shared base for sequential ONTAP primary-storage workflow tests.

    Subclasses must:
      - Set ``_vol_name_prefix`` to distinguish volume names per protocol.
      - Define ``setUpClass`` that builds ``cls.testdata``, creates
        ``cls.ontap`` and ``cls.svm_name``, then calls
        ``cls._setup_cloudstack_resources(config, account_testdata)``.
      - Define ``_create_pool`` (protocol-specific URL scheme and name).
    """

    # ---- shared state (set/cleared by individual tests) ----------------
    pool = None
    volume = None
    pool2 = None
    volume2 = None
    disk_offering_id = None
    svm_name = None
    cluster_hosts = None
    kvm_hosts_ssh_creds = []  # [{'host': '10.x.x.x', 'user': 'root', 'password': '...'}]
    ontap = None
    testdata = None
    zone = None
    cluster = None
    domain = None
    account = None
    _cleanup = []

    # Subclass sets this to distinguish volume names, e.g. "OntapNFS3Vol"
    _vol_name_prefix = "OntapVol"

    # ---- shared setup helper -------------------------------------------

    @classmethod
    def _setup_cloudstack_resources(cls, config, account_testdata):
        """
        Resolve zone, cluster, domain, account, cluster hosts, and disk
        offering from the Marvin config.  Call this from subclass setUpClass
        after ``cls.ontap`` and ``cls.svm_name`` have been assigned.
        """
        cs_cfg = config.get("cloudstack", {})
        zone_name = cs_cfg.get("zoneName", None)
        cluster_name = cs_cfg.get("clusterName", None)
        domain_name = cs_cfg.get("domainName", "ROOT")

        cls.zone = get_zone(cls.apiClient, zone_name=zone_name)
        clusters = (list_clusters(cls.apiClient, name=cluster_name)
                    if cluster_name else list_clusters(cls.apiClient))
        cls.cluster = clusters[0]
        cls.domain = get_domain(cls.apiClient, domain_name=domain_name)

        cls.account = Account.create(cls.apiClient, account_testdata, admin=1)
        cls._cleanup = [cls.account]

        list_hosts_cmd = listHostsAPI.listHostsCmd()
        list_hosts_cmd.clusterid = cls.cluster.id
        list_hosts_cmd.type = "Routing"
        cls.cluster_hosts = cls.apiClient.listHosts(list_hosts_cmd) or []

        list_do_cmd = listDiskOfferingsAPI.listDiskOfferingsCmd()
        list_do_cmd.domainid = cls.domain.id
        offerings = cls.apiClient.listDiskOfferings(list_do_cmd)
        if offerings:
            cls.disk_offering_id = offerings[0].id
        else:
            # No disk offerings exist yet — create a minimal one for tests
            do = DiskOffering.create(
                cls.apiClient,
                {"name": "ontap-test-do", "displaytext": "ONTAP test disk offering", "disksize": 2},
            )
            cls._cleanup.append(do)
            cls.disk_offering_id = do.id

        # Parse KVM host SSH credentials from zones/pods/clusters/hosts config.
        # Used by _cleanup_kvm_storage_pool_mounts to unmount stale NFS pools.
        cls.kvm_hosts_ssh_creds = []
        try:
            for zone in config.get("zones", []):
                for pod in zone.get("pods", []):
                    for cluster in pod.get("clusters", []):
                        for host_cfg in cluster.get("hosts", []):
                            host_ip = urlparse(
                                host_cfg.get("url", "")
                            ).hostname or ""
                            if host_ip:
                                cls.kvm_hosts_ssh_creds.append({
                                    "host": host_ip,
                                    "user": host_cfg.get("username", "root"),
                                    "password": host_cfg.get("password", ""),
                                })
        except Exception as parse_ex:
            logger.warning(
                "_setup_cloudstack_resources: could not parse KVM SSH creds: %s"
                % parse_ex
            )

    # ---- KVM storage cleanup helper ------------------------------------

    @classmethod
    def _cleanup_kvm_storage_pool_mounts(cls, pool_uuid):
        """
        SSH to each KVM host and unmount the NFS storage pool mount for
        *pool_uuid*, then destroy and undefine the libvirt storage pool.

        Must be called BEFORE the ONTAP FlexVol is deleted (i.e., before
        deleteStoragePool) so that the unmount completes while the NFS
        export is still reachable.  Prevents stale NFS mounts from
        triggering KVMHAMonitor heartbeat failures that reboot the host
        via ``echo b > /proc/sysrq-trigger``.
        """
        for creds in cls.kvm_hosts_ssh_creds:
            host_ip = creds["host"]
            try:
                ssh = SshClient(
                    host_ip, 22,
                    creds["user"], creds["password"],
                    retries=3, delay=3, timeout=15.0,
                )
                for cmd in [
                    "umount -f -l /mnt/{u} 2>/dev/null; true".format(
                        u=pool_uuid),
                    "virsh pool-destroy {u} 2>/dev/null; true".format(
                        u=pool_uuid),
                    "virsh pool-undefine {u} 2>/dev/null; true".format(
                        u=pool_uuid),
                ]:
                    try:
                        ssh.execute(cmd)
                    except Exception as cmd_ex:
                        logger.warning(
                            "_cleanup_kvm_storage_pool_mounts: cmd '%s' "
                            "failed on %s: %s" % (cmd, host_ip, cmd_ex)
                        )
            except Exception as ex:
                logger.warning(
                    "_cleanup_kvm_storage_pool_mounts: SSH to %s failed: %s"
                    % (host_ip, ex)
                )

    # ---- shared teardown -----------------------------------------------

    @classmethod
    def tearDownClass(cls):
        """Best-effort cleanup of any resources left behind by a failed run."""
        for pool in [p for p in (cls.pool2, cls.pool) if p is not None]:
            try:
                # Step 1: Check current pool state
                pools = list_storage_pools(cls.apiClient, id=pool.id)
                if not pools:
                    continue  # already deleted
                pool_state = pools[0].state

                # Step 2: If in Maintenance, attempt to exit it
                if pool_state == "Maintenance":
                    try:
                        cc = cancelStorageMaintenance.cancelStorageMaintenanceCmd()
                        cc.id = pool.id
                        cls.apiClient.cancelStorageMaintenance(cc)
                        time.sleep(5)
                    except Exception:
                        pass
                    try:
                        ec = updateStoragePoolAPI.updateStoragePoolCmd()
                        ec.id = pool.id
                        ec.enabled = True
                        cls.apiClient.updateStoragePool(ec)
                        time.sleep(3)
                    except Exception:
                        pass
                    pools = list_storage_pools(cls.apiClient, id=pool.id)
                    if pools:
                        pool_state = pools[0].state

                # Step 3: Delete volumes — always attempt regardless of pool
                # state. For iSCSI this works even in Maintenance; for NFS3/KVM
                # it may fail with NPE ("storagePoolInformation is null") when
                # pool is in Maintenance — that exception is caught below.
                for vol in [v for v in (cls.volume2, cls.volume) if v is not None]:
                    try:
                        cmd = deleteVolumeAPI.deleteVolumeCmd()
                        cmd.id = vol.id
                        cls.apiClient.deleteVolume(cmd)
                    except Exception as ve:
                        logger.warning(
                            "tearDownClass: could not delete volume %s: %s"
                            % (vol.id, ve))

                # Re-enter Maintenance only if pool was Up/Disabled (avoid
                # double-entering when cancel maintenance above already left it
                # in Maintenance)
                if pool_state in ("Up", "Disabled"):
                    try:
                        mc = enableStorageMaintenance.enableStorageMaintenanceCmd()
                        mc.id = pool.id
                        cls.apiClient.enableStorageMaintenance(mc)
                        deadline = time.time() + 60
                        while time.time() < deadline:
                            ps = list_storage_pools(cls.apiClient, id=pool.id)
                            if ps and ps[0].state == "Maintenance":
                                break
                            time.sleep(5)
                    except Exception:
                        pass

                # Step 4: Force-delete the pool
                dc = deleteStoragePoolAPI.deleteStoragePoolCmd()
                dc.id = pool.id
                dc.forced = True
                cls.apiClient.deleteStoragePool(dc)
            except Exception as e:
                logger.warning("tearDownClass: could not delete pool %s: %s"
                               % (pool.id, e))
                # Last resort: delete ONTAP FlexVol and export policy directly
                # so that ONTAP is never left with orphaned volumes even when
                # the CloudStack pool record cannot be removed.
                if hasattr(cls, "ontap") and cls.ontap is not None:
                    try:
                        cls.ontap.delete_volume(pool.name)
                        logger.warning(
                            "tearDownClass: deleted ONTAP FlexVol '%s' directly"
                            % pool.name)
                    except Exception as oe:
                        logger.warning(
                            "tearDownClass: ONTAP direct volume delete '%s' "
                            "failed: %s" % (pool.name, oe))
                    try:
                        # For NFS3 pools also remove the export policy
                        ep_name = getattr(cls, "pool_ep_name", None)
                        if ep_name is None:
                            ep_name = "cs-%s-%s" % (
                                getattr(cls, "svm_name", ""), pool.name)
                        cls.ontap.delete_export_policy(ep_name)
                        logger.warning(
                            "tearDownClass: deleted export policy '%s' directly"
                            % ep_name)
                    except Exception:
                        pass

        # Clean up volumes that may not have been handled with pool teardown
        for vol in [v for v in (cls.volume2, cls.volume) if v is not None]:
            try:
                cmd = deleteVolumeAPI.deleteVolumeCmd()
                cmd.id = vol.id
                cls.apiClient.deleteVolume(cmd)
            except Exception as e:
                logger.warning("tearDownClass: could not delete volume %s: %s"
                               % (vol.id, e))

        try:
            cleanup_resources(cls.apiClient, cls._cleanup)
        except Exception as e:
            logger.debug("tearDownClass cleanup_resources: %s" % e)

    # No per-test tearDown — state intentionally persists between steps.

    # ---- shared helpers ------------------------------------------------

    def _poll_pool_state(self, pool_id, target_state, timeout=120, interval=5):
        """Poll listStoragePools until the pool reaches target_state or timeout."""
        deadline = time.time() + timeout
        current_state = "unknown"
        while time.time() < deadline:
            pools = list_storage_pools(self.apiClient, id=pool_id)
            if pools:
                current_state = pools[0].state
                if current_state == target_state:
                    return pools[0]
            time.sleep(interval)
        self.fail(
            "Pool %s did not reach state '%s' within %ds (last: '%s')"
            % (pool_id, target_state, timeout, current_state)
        )

    def _create_volume(self, pool_id):
        """Create a data volume on the given pool; uses _vol_name_prefix."""
        cmd = createVolumeAPI.createVolumeCmd()
        cmd.name = "%s_%d" % (self._vol_name_prefix, random.randint(0, 99999))
        cmd.diskofferingid = self.disk_offering_id
        cmd.zoneid = self.zone.id
        cmd.storageid = pool_id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        return self.apiClient.createVolume(cmd)

    def _delete_pool(self, pool_id, forced=False):
        """Issue deleteStoragePool for the given pool id."""
        cmd = deleteStoragePoolAPI.deleteStoragePoolCmd()
        cmd.id = pool_id
        if forced:
            cmd.forced = True
        self.apiClient.deleteStoragePool(cmd)
