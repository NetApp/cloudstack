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
import re
import requests
import sys
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
from marvin.jsonHelper import jsonDump
from marvin.lib.base import Account, DiskOffering
from marvin.sshClient import SshClient
from marvin.lib.common import get_domain, get_zone, list_clusters, list_storage_pools
from marvin.lib.utils import cleanup_resources

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

logger = logging.getLogger("OntapTestBase")


def configure_console_logging(log, level=logging.INFO):
    """Send INFO/WARNING/ERROR from *log* to stdout for live test-run visibility."""
    if any(isinstance(h, logging.StreamHandler) for h in log.handlers):
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)s [%(name)s] %(message)s",
        datefmt="%H:%M:%S",
    ))
    handler.setLevel(level)
    log.addHandler(handler)
    if log.level == logging.NOTSET or log.level > level:
        log.setLevel(level)
    log.propagate = False


def enable_live_logging(test_cls):
    """Attach stdout handlers to OntapTestBase and the test module logger."""
    configure_console_logging(logger)
    if test_cls is not None:
        mod = sys.modules.get(test_cls.__module__)
        if mod is not None:
            mod_logger = getattr(mod, "logger", None)
            if mod_logger is not None:
                configure_console_logging(mod_logger)


def log_progress(log, level, msg, *args):
    """Log to Marvin files and stdout so long polls remain visible."""
    text = msg % args if args else msg
    getattr(log, level)(text)
    print("[%s] %s" % (level.upper(), text), flush=True)


def get_datacenter_config(testclient, test_cls):
    """
    Return the --marvin-config file (e.g. ontap.cfg) as a plain dict.

    Marvin injects the datacenter config as ``test_cls.config``.  The separate
    ``getParsedTestDataConfig()`` API defaults to test_data.py and does not
    contain ontap/cloudstack/zones sections from ontap.cfg.
    """
    if getattr(test_cls, "config", None):
        return jsonDump.dump(test_cls.config)
    cfg = testclient.getParsedTestDataConfig() or {}
    if cfg.get("ontap") or cfg.get("cloudstack") or cfg.get("zones"):
        return cfg
    return cfg


# ---------------------------------------------------------------------------
# Pool detail helper
# ---------------------------------------------------------------------------

def _parse_pool_details(pool):
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
        self._raise_http(resp)
        return self._response_data(resp)

    @staticmethod
    def _response_data(resp):
        """Return response JSON, including async job UUID from Location."""
        if resp.content:
            try:
                return resp.json()
            except ValueError:
                pass
        location = resp.headers.get("Location", "")
        marker = "/cluster/jobs/"
        if marker in location:
            return {"job": {"uuid": location.split(marker, 1)[1].split("?", 1)[0]}}
        return None

    def _raise_http(self, resp):
        if resp.ok:
            return
        body = ""
        try:
            body = resp.text
        except Exception:
            body = ""
        raise requests.HTTPError(
            "%s Client Error: %s for url: %s body: %s"
            % (resp.status_code, resp.reason, resp.url, body),
            response=resp,
        )

    def _patch(self, path, params=None, json_body=None, timeout=60):
        url = self._base + path
        resp = requests.patch(
            url, auth=self._auth, params=params, json=json_body,
            verify=False, timeout=timeout,
        )
        self._raise_http(resp)
        return self._response_data(resp)

    def _post(self, path, params=None, data=None, json_body=None, timeout=60,
              headers=None, files=None):
        url = self._base + path
        resp = requests.post(
            url, auth=self._auth, params=params, data=data, json=json_body,
            headers=headers, files=files, verify=False, timeout=timeout,
        )
        self._raise_http(resp)
        return self._response_data(resp)

    def _wait_for_job(self, response, timeout=120):
        """Wait for an asynchronous ONTAP response, if it contains a job."""
        job = (response or {}).get("job") or {}
        job_uuid = job.get("uuid")
        if not job_uuid:
            return response
        deadline = time.time() + timeout
        while time.time() < deadline:
            current = self._get("/cluster/jobs/%s" % job_uuid)
            state = (current.get("state") or "").lower()
            if state == "success":
                return current
            if state in ("failure", "failed", "error"):
                message = current.get("message") or "unknown ONTAP job failure"
                raise RuntimeError("ONTAP job %s failed: %s" % (job_uuid, message))
            time.sleep(2)
        raise RuntimeError("Timed out waiting for ONTAP job %s" % job_uuid)

    def _svm_aggregates(self, svm_name):
        """Return detailed aggregate records assigned to an SVM."""
        svms = self._get(
            "/svm/svms",
            params={"name": svm_name, "fields": "aggregates"},
        ).get("records", [])
        if not svms:
            raise RuntimeError("ONTAP SVM '%s' was not found" % svm_name)
        aggregates = []
        for aggregate in svms[0].get("aggregates", []):
            uuid = aggregate.get("uuid")
            if not uuid:
                continue
            aggregates.append(self._get(
                "/storage/aggregates/%s" % uuid,
                params={"fields": "name,uuid,state,space.block_storage.available"},
            ))
        return aggregates

    def max_online_aggregate_available_bytes(self, svm_name):
        """Return the largest free-space value among assigned online aggregates."""
        available = []
        for aggregate in self._svm_aggregates(svm_name):
            if (aggregate.get("state") or "").lower() != "online":
                continue
            free = (aggregate.get("space", {})
                    .get("block_storage", {}).get("available"))
            if free is not None:
                available.append(int(float(free)))
        if not available:
            raise RuntimeError(
                "SVM '%s' has no online aggregate with space data" % svm_name
            )
        return max(available)

    def create_flexvol(self, svm_name, volume_name, size_bytes, nas_path=True):
        """Create a thin FlexVol directly on a suitable SVM aggregate."""
        suitable = []
        for aggregate in self._svm_aggregates(svm_name):
            free = (aggregate.get("space", {})
                    .get("block_storage", {}).get("available"))
            if ((aggregate.get("state") or "").lower() == "online"
                    and free is not None and int(float(free)) > int(size_bytes)):
                suitable.append((int(float(free)), aggregate))
        if not suitable:
            raise RuntimeError(
                "No ONTAP aggregate can hold FlexVol '%s'" % volume_name
            )
        aggregate = max(suitable, key=lambda item: item[0])[1]
        request = {
            "name": volume_name,
            "svm": {"name": svm_name},
            "size": int(size_bytes),
            "aggregates": [{"name": aggregate.get("name")}],
            "guarantee": {"type": "none"},
        }
        if nas_path:
            request["nas"] = {"path": "/" + volume_name}
        response = self._post(
            "/storage/volumes",
            params={"return_timeout": 15},
            json_body=request,
        )
        self._wait_for_job(response)
        deadline = time.time() + 120
        while time.time() < deadline:
            volume = self.get_volume(volume_name)
            if volume is not None:
                return volume
            time.sleep(2)
        raise RuntimeError(
            "FlexVol '%s' was not visible after creation" % volume_name
        )

    def offline_and_delete_volume(self, name):
        """Offline and delete a FlexVol directly; no-op when already absent."""
        volume = self.get_volume(name)
        if not volume:
            return
        uuid = volume.get("uuid")
        if not uuid:
            raise RuntimeError("FlexVol '%s' has no UUID" % name)
        if (volume.get("nas") or {}).get("path"):
            response = self._patch(
                "/storage/volumes/%s" % uuid,
                params={"return_timeout": 15},
                json_body={"nas": {"path": ""}},
            )
            self._wait_for_job(response)
        if (volume.get("state") or "").lower() != "offline":
            response = self._patch(
                "/storage/volumes/%s" % uuid,
                params={"return_timeout": 15},
                json_body={"state": "offline"},
            )
            self._wait_for_job(response)
        response = self._delete(
            "/storage/volumes/%s" % uuid,
            params={"return_timeout": 15},
        )
        self._wait_for_job(response)
        deadline = time.time() + 120
        while time.time() < deadline:
            if self.get_volume(name) is None:
                return
            time.sleep(2)
        raise RuntimeError("FlexVol '%s' still exists after deletion" % name)

    def reassign_volume_export_policy(self, volume_name, policy_name="default"):
        """Assign a FlexVol to another export policy before deleting its policy."""
        volume = self.get_volume(volume_name)
        if not volume:
            raise RuntimeError("FlexVol '%s' was not found" % volume_name)
        uuid = volume.get("uuid")
        if not uuid:
            raise RuntimeError("FlexVol '%s' has no UUID" % volume_name)
        response = self._patch(
            "/storage/volumes/%s" % uuid,
            params={"return_timeout": 15},
            json_body={"nas": {"export_policy": {"name": policy_name}}},
        )
        self._wait_for_job(response)

    def create_igroup(self, svm_name, igroup_name, initiator_iqn):
        """Create an ONTAP igroup holding a single initiator."""
        self._post(
            "/protocols/san/igroups",
            json_body={
                "svm": {"name": svm_name},
                "name": igroup_name,
                "os_type": "linux",
                "protocol": "iscsi",
                "initiators": [{"name": initiator_iqn}],
            },
        )
        igroup = self.get_igroup(svm_name, igroup_name)
        if igroup is None:
            raise RuntimeError(
                "ONTAP igroup '%s' absent right after creation" % igroup_name
            )
        return igroup

    def delete_igroup(self, svm_name, igroup_name):
        """Delete an ONTAP igroup by name; no-op when already absent."""
        igroup = self.get_igroup(svm_name, igroup_name)
        if not igroup:
            return
        uuid = igroup.get("uuid")
        if not uuid:
            raise RuntimeError("ONTAP igroup '%s' has no UUID" % igroup_name)
        self._delete("/protocols/san/igroups/%s" % uuid)

    def create_lun_map(self, svm_name, lun_path, igroup_name):
        """Map an existing LUN to an existing igroup."""
        response = self._post(
            "/protocols/san/lun-maps",
            params={"return_timeout": 15},
            json_body={
                "svm": {"name": svm_name},
                "lun": {"name": lun_path},
                "igroup": {"name": igroup_name},
            },
        )
        self._wait_for_job(response)

    def delete_lun_map(self, lun_map):
        """Delete one LUN map returned by list_lun_maps_for_volume."""
        lun_uuid = lun_map.get("lun", {}).get("uuid")
        igroup_uuid = lun_map.get("igroup", {}).get("uuid")
        if not lun_uuid or not igroup_uuid:
            raise RuntimeError("ONTAP LUN map is missing LUN or igroup UUID")
        self._delete(
            "/protocols/san/lun-maps/%s/%s" % (lun_uuid, igroup_uuid)
        )

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
                             params={"fields": "name,uuid,state,space,nas.path,nas.export_policy"})
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
                                 "fields": "lun.name,lun.uuid,igroup.name,igroup.uuid"})
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

    # ---- shared state (set/cleared by individual tests) ----------------
    pool = None
    volume = None
    pool2 = None
    volume2 = None
    disk_offering_id = None
    svm_name = None
    cluster_hosts = None
    kvm_hosts_ssh_creds = []  # [{'host': '10.x.x.x', 'user': 'root', 'password': '...'}]
    _host_iqn_cache = {}
    igroup_baseline = {}
    ontap = None
    testdata = None
    zone = None
    cluster = None
    domain = None
    account = None
    _cleanup = []

    # Subclass sets this to distinguish volume names, e.g. "OntapNFS3Vol"
    _vol_name_prefix = "OntapVol"

    # ---- zone guard ----------------------------------------------------

    @classmethod
    def _ensure_zone(cls, config, zone_name, cluster_name):
        """
        Verify that the named zone and cluster already exist and return
        (zone, cluster).  Raises RuntimeError with a clear message if the
        zone is absent — run the setup_zone step first:

          bash test/integration/plugins/ontap/run_tests.sh setup_zone
        """
        zone = get_zone(cls.apiClient, zone_name=zone_name)
        if not zone:
            raise RuntimeError(
                "Zone '%s' not found. Create it first by running:\n"
                "  bash test/integration/plugins/ontap/run_tests.sh setup_zone\n"
                "Then re-run the tests."
                % (zone_name or "<default>")
            )
        clusters = (list_clusters(cls.apiClient, name=cluster_name)
                    if cluster_name else list_clusters(cls.apiClient))
        if not clusters:
            raise RuntimeError(
                "No cluster found (filter: %r) in zone '%s'. "
                "Verify the cluster was created by the setup_zone step."
                % (cluster_name, zone.name)
            )
        return zone, clusters[0]

    # ---- shared setup helper -------------------------------------------

    @classmethod
    def setUpClass(cls):
        enable_live_logging(cls)

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

        cls.zone, cls.cluster = cls._ensure_zone(config, zone_name, cluster_name)
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
        start = time.time()
        deadline = start + timeout
        attempt = 0
        current_state = "unknown"
        log_progress(
            logger, "info",
            "Waiting for pool %s to reach state '%s' "
            "(timeout=%ds, poll every %ds).",
            pool_id, target_state, timeout, interval,
        )
        while time.time() < deadline:
            attempt += 1
            elapsed = int(time.time() - start)
            remaining = max(0, int(deadline - time.time()))
            pools = list_storage_pools(self.apiClient, id=pool_id)
            if pools:
                current_state = pools[0].state
                if current_state == target_state:
                    log_progress(
                        logger, "info",
                        "Pool %s reached state '%s' after %ds (%d polls).",
                        pool_id, target_state, elapsed, attempt,
                    )
                    return pools[0]
            log_progress(
                logger, "info",
                "Pool poll #%d: pool %s state=%s (want %s) "
                "[elapsed %ds, ~%ds left]",
                attempt, pool_id, current_state, target_state,
                elapsed, remaining,
            )
            time.sleep(interval)
        log_progress(
            logger, "error",
            "Pool %s did not reach state '%s' within %ds (last: '%s').",
            pool_id, target_state, timeout, current_state,
        )
        self.fail(
            "Pool %s did not reach state '%s' within %ds (last: '%s')"
            % (pool_id, target_state, timeout, current_state)
        )


    @classmethod
    def host_iqn(cls, host):
        """The iSCSI initiator IQN for a cluster host, or None."""
        host_ip = getattr(host, "ipaddress", None)
        if not host_ip:
            return None
        if host_ip in cls._host_iqn_cache:
            return cls._host_iqn_cache[host_ip]
        iqn = None
        creds = next(
            (c for c in cls.kvm_hosts_ssh_creds if c["host"] == host_ip), None
        )
        if creds is not None:
            try:
                ssh = SshClient(host_ip, 22, creds["user"], creds["password"],
                                retries=3, delay=3, timeout=15.0)
                out = ssh.execute(
                    "awk -F= '/^InitiatorName=/{print $2}' "
                    "/etc/iscsi/initiatorname.iscsi 2>/dev/null"
                )
                for line in out or []:
                    line = line.strip()
                    if line.startswith("iqn."):
                        iqn = line
                        break
            except Exception as ex:
                logger.warning("host_iqn: SSH to %s failed: %s", host_ip, ex)
        cls._host_iqn_cache[host_ip] = iqn
        return iqn

    @classmethod
    def _igroup_name(cls, host_uuid):
        """Return the igroup name used by OntapStorageUtils."""
        sanitized = re.sub(r"[^a-zA-Z0-9_-]", "_", str(host_uuid))
        return ("cs_%s_%s" % (sanitized, cls.svm_name))[:96]

    @classmethod
    def _iscsi_host_specs(cls):
        """Return (igroup name, initiator IQN) for iSCSI cluster hosts."""
        specs = []
        for host in cls.cluster_hosts or []:
            iqn = (
                getattr(host, "storageurl", None)
                or getattr(host, "StorageUrl", None)
                or cls.host_iqn(host)
            )
            host_uuid = getattr(host, "id", None)
            if not iqn or not iqn.startswith("iqn.") or not host_uuid:
                continue
            specs.append((cls._igroup_name(host_uuid), iqn))
        return specs

    @staticmethod
    def _igroup_initiators(igroup):
        if igroup is None:
            return None
        return tuple(sorted(
            i.get("name", "") for i in igroup.get("initiators", [])
        ))

    @classmethod
    def _capture_igroup_baseline(cls):
        """Snapshot shared host igroups before an iSCSI suite creates a pool."""
        cls.igroup_baseline = {}
        for igroup_name, _ in cls._iscsi_host_specs():
            igroup = cls.ontap.get_igroup(cls.svm_name, igroup_name)
            cls.igroup_baseline[igroup_name] = cls._igroup_initiators(igroup)
        logger.info(
            "Captured iSCSI igroup baseline for SVM '%s': %s",
            cls.svm_name, cls.igroup_baseline,
        )

    def _assert_igroup_baseline_unchanged(self, context):
        """Assert an operation did not change pre-existing shared igroups."""
        for igroup_name, expected_initiators in self.igroup_baseline.items():
            igroup = self.ontap.get_igroup(self.svm_name, igroup_name)
            actual_initiators = self._igroup_initiators(igroup)
            self.assertEqual(
                actual_initiators, expected_initiators,
                "ONTAP igroup '%s' changed %s: expected initiators %s, got %s"
                % (igroup_name, context, expected_initiators,
                   actual_initiators),
            )

    def _assert_no_lun_maps_for_volume(self, volume_name, context):
        """Assert no LUN in a test FlexVol remains mapped to any igroup."""
        maps = self.ontap.list_lun_maps_for_volume(
            self.svm_name, volume_name
        )
        self.assertFalse(
            maps,
            "LUN maps for FlexVol '%s' remain %s: %s"
            % (volume_name, context, maps),
        )

    def _get_cs_volume(self, vol_id):
        """Return the CloudStack volume object, or None if it is gone."""
        from marvin.cloudstackAPI import listVolumes as listVolumesAPI
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.id = vol_id
        cmd.listall = True
        vols = self.apiClient.listVolumes(cmd) or []
        return vols[0] if vols else None

    def _other_ontap_pools_on_svm(self, current_pool_id):
        """Return other CloudStack ONTAP pools that use this suite's SVM."""
        try:
            pools = list_storage_pools(self.apiClient) or []
        except Exception:
            return ["unable to list storage pools"]
        others = []
        for pool in pools:
            if str(getattr(pool, "id", "")) == str(current_pool_id):
                continue
            details = _parse_pool_details(pool)
            if details.get("svmName") == getattr(self, "svm_name", None):
                others.append(pool)
                continue
            provider = (getattr(pool, "provider", "") or "").lower()
            if not details and "netapp" in provider and "ontap" in provider:
                others.append(pool)
        return others

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
