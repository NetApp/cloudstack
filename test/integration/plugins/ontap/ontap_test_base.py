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
import os
import random
import requests
import sys
import time
import urllib3
from urllib.parse import quote, urlparse

from marvin.cloudstackAPI import (
    cancelStorageMaintenance,
    createNetwork as createNetworkAPI,
    createVolume as createVolumeAPI,
    deleteNetwork as deleteNetworkAPI,
    deleteStoragePool as deleteStoragePoolAPI,
    deleteVolume as deleteVolumeAPI,
    destroyVirtualMachine as destroyVirtualMachineAPI,
    detachVolume as detachVolumeAPI,
    enableStorageMaintenance,
    listDiskOfferings as listDiskOfferingsAPI,
    listNetworkOfferings as listNetworkOfferingsAPI,
    listNetworks as listNetworksAPI,
    listServiceOfferings as listServiceOfferingsAPI,
    listTemplates as listTemplatesAPI,
    listVirtualMachines as listVirtualMachinesAPI,
    listVolumes as listVolumesAPI,
    stopVirtualMachine as stopVirtualMachineAPI,
    updateStoragePool as updateStoragePoolAPI,
)
from marvin.cloudstackAPI import listHosts as listHostsAPI
from marvin.cloudstackException import CloudstackAPIException
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.jsonHelper import jsonDump
from marvin.lib.base import Account, DiskOffering
from marvin.sshClient import SshClient
from marvin.lib.common import (
    get_domain, get_zone, list_clusters, list_storage_pools,
)
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

    def _post(self, path, params=None, data=None, json_body=None, timeout=60,
              headers=None, files=None):
        url = self._base + path
        resp = requests.post(
            url, auth=self._auth, params=params, data=data, json=json_body,
            headers=headers, files=files, verify=False, timeout=timeout,
        )
        self._raise_http(resp)
        if not resp.content:
            return None
        try:
            return resp.json()
        except ValueError:
            return None

    def _volume_uuid(self, vol_name):
        vol = self.get_volume(vol_name)
        return (vol or {}).get("uuid")

    def _files_path(self, vol_uuid, filename):
        encoded = quote("/" + filename.lstrip("/"), safe="")
        return "/storage/volumes/%s/files/%s" % (vol_uuid, encoded)

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

    def write_file_in_volume(self, vol_name, filename, size_bytes,
                             chunk_bytes=512 * 1024):
        """Write *size_bytes* of incompressible data into a FlexVol file.

        Zeros compress to almost nothing on ONTAP, so the payload is random.
        The files API requires ``multipart/form-data`` and rejects writes
        larger than 1 MiB, so data is sent in chunks.  No VM, NFS mount, or
        CloudStack volume is required.
        """
        vol_uuid = self._volume_uuid(vol_name)
        if not vol_uuid:
            raise RuntimeError("ONTAP FlexVol '%s' not found" % vol_name)
        url_path = self._files_path(vol_uuid, filename)
        written = 0
        size_bytes = int(size_bytes)
        while written < size_bytes:
            chunk = min(int(chunk_bytes), size_bytes - written)
            files = {
                "file": (filename, os.urandom(chunk),
                         "application/octet-stream"),
            }
            self._post(
                url_path,
                params={"byte_offset": written, "overwrite": "true"},
                files=files,
                timeout=120,
            )
            written += chunk

    def delete_file_in_volume(self, vol_name, filename):
        """Delete a file from the FlexVol. No-op if the volume or file is gone."""
        vol_uuid = self._volume_uuid(vol_name)
        if not vol_uuid:
            return
        try:
            self._delete(self._files_path(vol_uuid, filename))
        except requests.HTTPError as exc:
            status = getattr(exc.response, "status_code", None)
            if status not in (404, 409):
                raise


# ---------------------------------------------------------------------------
# Base test class
# ---------------------------------------------------------------------------

class OntapTestBase(cloudstackTestCase):

    # ONTAP refuses to shrink a FlexVol below this; distinct from the
    # plugin's 1.56 GiB create-time floor (ONTAP_MIN_VOLUME_SIZE).
    ONTAP_MIN_FLEXVOL_SIZE = 20 * 1024 * 1024
    FILLER_FILENAME = "ontap-filler.bin"
    FILLER_SIZE = 32 * 1024 * 1024

    # ---- shared state (set/cleared by individual tests) ----------------
    pool = None
    volume = None
    # Volumes a single test allocates on top of ``volume`` and deletes before
    # it returns; tracked here only so a failed run still cleans them up.
    extra_volumes = []
    # File written into the FlexVol by tests that must raise ONTAP used space.
    filler_filename = None
    filler_flexvol = None
    pool2 = None
    volume2 = None
    # ---- VM state, for suites that deploy an instance ------------------
    vm = None
    template_id = None
    service_offering_id = None
    network_id = None
    _created_network_id = None
    # Prefix for a guest network this suite creates on Advanced zones.
    _vm_network_name_prefix = "ontap-vm-net"
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
    def _all_tracked_volumes(cls):
        """Every volume the suite created, most recent first, without repeats."""
        seen = set()
        ordered = []
        for vol in list(cls.extra_volumes or []) + [cls.volume2, cls.volume]:
            vol_id = getattr(vol, "id", None)
            if vol is None or vol_id in seen:
                continue
            seen.add(vol_id)
            ordered.append(vol)
        return ordered

    # ---- VM deploy/attach helpers --------------------------------------

    @classmethod
    def _discover_vm_deploy_resources(cls):
        """Resolve the template, service offering, and network for VM deploys.

        A missing template is not fatal: ``template_id`` is left as None so
        callers can skip the VM step while the rest of the suite still runs.
        On Advanced zones an existing account network is reused when present,
        otherwise an Isolated one is created and torn down in tearDownClass.
        """
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
                "No ready user KVM template in zone '%s' — VM steps will skip."
                % cls.zone.name
            )

        so_cmd = listServiceOfferingsAPI.listServiceOfferingsCmd()
        offerings = cls.apiClient.listServiceOfferings(so_cmd) or []
        if offerings:
            offerings.sort(key=lambda s: getattr(s, "memory", 9999))
            cls.service_offering_id = offerings[0].id

        cls.network_id = None
        if getattr(cls.zone, "networktype", "Basic").lower() != "advanced":
            return

        net_cmd = listNetworksAPI.listNetworksCmd()
        net_cmd.zoneid = cls.zone.id
        net_cmd.account = cls.account.name
        net_cmd.domainid = cls.domain.id
        nets = cls.apiClient.listNetworks(net_cmd) or []
        if nets:
            cls.network_id = nets[0].id
            return

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
        if snat_offering is None:
            return
        cn_cmd = createNetworkAPI.createNetworkCmd()
        cn_cmd.zoneid = cls.zone.id
        cn_cmd.networkofferingid = snat_offering.id
        cn_cmd.name = "%s-%d" % (cls._vm_network_name_prefix,
                                 random.randint(0, 9999))
        cn_cmd.displaytext = "ONTAP test VM network"
        cn_cmd.account = cls.account.name
        cn_cmd.domainid = cls.domain.id
        net = cls.apiClient.createNetwork(cn_cmd)
        cls.network_id = net.id
        cls._created_network_id = net.id

    @classmethod
    def _destroy_vm_if_present(cls):
        """Stop and expunge the suite's VM. Safe to call when none exists."""
        if cls.vm is None:
            return
        vm_id = cls.vm.id
        try:
            vms = cls.apiClient.listVirtualMachines(_list_vms_cmd(vm_id))
            state = vms[0].state.lower() if vms else "unknown"
            if state not in ("stopped", "destroyed", "expunging", "error"):
                stop_cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
                stop_cmd.id = vm_id
                stop_cmd.forced = True
                cls.apiClient.stopVirtualMachine(stop_cmd)
                _wait_for_vm_state(cls.apiClient, vm_id, "Stopped", timeout=180)
        except Exception as e:
            logger.warning("Could not stop VM %s: %s" % (vm_id, e))
        try:
            dest_cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
            dest_cmd.id = vm_id
            dest_cmd.expunge = True
            cls.apiClient.destroyVirtualMachine(dest_cmd)
        except Exception as e:
            logger.warning("Could not destroy VM %s: %s" % (vm_id, e))
        cls.vm = None

    @classmethod
    def _delete_created_network(cls):
        """Delete the guest network this suite created, if any."""
        if cls._created_network_id is None:
            return
        try:
            dn_cmd = deleteNetworkAPI.deleteNetworkCmd()
            dn_cmd.id = cls._created_network_id
            cls.apiClient.deleteNetwork(dn_cmd)
        except Exception as e:
            logger.warning(
                "Could not delete network %s: %s" % (cls._created_network_id, e)
            )
        cls._created_network_id = None

    def _poll_volume_attached(self, vol_id, timeout=180, interval=5):
        """Poll listVolumes until virtualmachineid is set; return it or None.

        ONTAP-backed volumes stay in state 'Ready' when attached, so the
        virtualmachineid field is the reliable signal.
        """
        deadline = time.time() + timeout
        vol_vmid = None
        while time.time() < deadline:
            vols = self.apiClient.listVolumes(_list_vols_cmd(vol_id)) or []
            vol_vmid = getattr(vols[0], "virtualmachineid", None) if vols else None
            if vol_vmid:
                return vol_vmid
            time.sleep(interval)
        return vol_vmid

    def _assert_vm_running_with_volume(self, vm_id, vol_id, label):
        """Assert the VM is still up and still owns the volume."""
        vm_obj = _wait_for_vm_state(self.apiClient, vm_id, "Running",
                                    timeout=60)
        self.assertIsNotNone(
            vm_obj, "[%s] VM %s vanished from listVirtualMachines"
            % (label, vm_id),
        )
        self.assertEqual(
            vm_obj.state, "Running",
            "[%s] VM should still be 'Running', got '%s'"
            % (label, vm_obj.state),
        )
        vol = self._get_cs_volume(vol_id)
        self.assertIsNotNone(
            vol, "[%s] volume %s is no longer listed" % (label, vol_id),
        )
        self.assertEqual(
            getattr(vol, "virtualmachineid", None), vm_id,
            "[%s] volume %s should still be attached to VM %s, "
            "virtualmachineid is %s"
            % (label, vol_id, vm_id, getattr(vol, "virtualmachineid", None)),
        )

    def _detach_volume_if_attached(self, vol_id):
        """Detach the volume when a VM holds it. No-op otherwise."""
        vols = self.apiClient.listVolumes(_list_vols_cmd(vol_id)) or []
        if not vols or not getattr(vols[0], "virtualmachineid", None):
            return
        try:
            cmd = detachVolumeAPI.detachVolumeCmd()
            cmd.id = vol_id
            self.apiClient.detachVolume(cmd)
        except Exception as e:
            logger.warning("Could not detach volume %s: %s" % (vol_id, e))
            return
        deadline = time.time() + 120
        while time.time() < deadline:
            vols = self.apiClient.listVolumes(_list_vols_cmd(vol_id)) or []
            if not vols or not getattr(vols[0], "virtualmachineid", None):
                return
            time.sleep(5)
        logger.warning(
            "Volume %s still reports a virtualmachineid after detach" % vol_id
        )

    @classmethod
    def tearDownClass(cls):
        """Best-effort cleanup of any resources left behind by a failed run."""
        cls._destroy_vm_if_present()
        cls._delete_created_network()
        if (getattr(cls, "ontap", None) is not None
                and getattr(cls, "filler_filename", None)
                and getattr(cls, "filler_flexvol", None)):
            try:
                cls.ontap.delete_file_in_volume(
                    cls.filler_flexvol, cls.filler_filename
                )
            except Exception as fe:
                logger.warning(
                    "tearDownClass: could not delete filler file %s in %s: %s"
                    % (cls.filler_filename, cls.filler_flexvol, fe)
                )
            cls.filler_filename = None
            cls.filler_flexvol = None
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
                for vol in cls._all_tracked_volumes():
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
        for vol in cls._all_tracked_volumes():
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

    def _poll_pool_capacity(self, pool_id, expected_bytes, timeout=120,
                            interval=5):
        """Poll listStoragePools until capacitybytes equals expected_bytes."""
        start = time.time()
        deadline = start + timeout
        attempt = 0
        current = 0
        log_progress(
            logger, "info",
            "Waiting for pool %s to report capacitybytes=%d "
            "(timeout=%ds, poll every %ds).",
            pool_id, expected_bytes, timeout, interval,
        )
        while time.time() < deadline:
            attempt += 1
            elapsed = int(time.time() - start)
            remaining = max(0, int(deadline - time.time()))
            pools = list_storage_pools(self.apiClient, id=pool_id)
            if pools:
                current = int(getattr(pools[0], "capacitybytes", 0) or 0)
                if current == expected_bytes:
                    log_progress(
                        logger, "info",
                        "Pool %s reported capacitybytes=%d after %ds (%d polls).",
                        pool_id, expected_bytes, elapsed, attempt,
                    )
                    return pools[0]
            log_progress(
                logger, "info",
                "Capacity poll #%d: pool %s capacitybytes=%d (want %d) "
                "[elapsed %ds, ~%ds left]",
                attempt, pool_id, current, expected_bytes,
                elapsed, remaining,
            )
            time.sleep(interval)
        log_progress(
            logger, "error",
            "Pool %s did not report capacitybytes=%d within %ds (last: %d).",
            pool_id, expected_bytes, timeout, current,
        )
        self.fail(
            "Pool %s did not report capacitybytes=%d within %ds (last: %d)"
            % (pool_id, expected_bytes, timeout, current)
        )

    def _poll_ontap_volume_size(self, volume_name, expected_bytes,
                                timeout=120, interval=5):
        """Poll ONTAP until the FlexVol space.size equals expected_bytes."""
        start = time.time()
        deadline = start + timeout
        attempt = 0
        current = 0
        log_progress(
            logger, "info",
            "Waiting for ONTAP FlexVol '%s' to report space.size=%d "
            "(timeout=%ds, poll every %ds).",
            volume_name, expected_bytes, timeout, interval,
        )
        while time.time() < deadline:
            attempt += 1
            elapsed = int(time.time() - start)
            remaining = max(0, int(deadline - time.time()))
            volume = self.ontap.get_volume(volume_name)
            if volume:
                current = int(volume.get("space", {}).get("size", 0) or 0)
                if current == expected_bytes:
                    log_progress(
                        logger, "info",
                        "ONTAP FlexVol '%s' reported space.size=%d after "
                        "%ds (%d polls).",
                        volume_name, expected_bytes, elapsed, attempt,
                    )
                    return volume
            log_progress(
                logger, "info",
                "ONTAP capacity poll #%d: FlexVol '%s' space.size=%d "
                "(want %d) [elapsed %ds, ~%ds left]",
                attempt, volume_name, current, expected_bytes,
                elapsed, remaining,
            )
            time.sleep(interval)
        log_progress(
            logger, "error",
            "ONTAP FlexVol '%s' did not report space.size=%d within %ds "
            "(last: %d).",
            volume_name, expected_bytes, timeout, current,
        )
        self.fail(
            "ONTAP FlexVol '%s' did not report space.size=%d within %ds "
            "(last: %d)"
            % (volume_name, expected_bytes, timeout, current)
        )

    def _get_cs_volume(self, vol_id):
        """Return the CloudStack volume object, or None if it is gone."""
        from marvin.cloudstackAPI import listVolumes as listVolumesAPI
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.id = vol_id
        cmd.listall = True
        vols = self.apiClient.listVolumes(cmd) or []
        return vols[0] if vols else None

    def _volume_exists_in_cs(self, vol_id):
        """Return True if the volume is still listed by CloudStack."""
        return self._get_cs_volume(vol_id) is not None

    def _cs_volume_snapshot(self, vol_id):
        """Capture id, state, size, and pool so a later check can detect mutation."""
        vol = self._get_cs_volume(vol_id)
        self.assertIsNotNone(
            vol, "CloudStack volume %s is not listed in listVolumes" % vol_id
        )
        pool_id = (
            getattr(vol, "storageid", None)
            or getattr(vol, "poolid", None)
        )
        return {
            "id": getattr(vol, "id", None),
            "state": getattr(vol, "state", None),
            "size": int(getattr(vol, "size", 0) or 0),
            "poolid": pool_id,
        }

    def _assert_cs_volume_untouched(self, before, label):
        """Assert listVolumes still returns the same id, state, size, and pool."""
        after = self._cs_volume_snapshot(before["id"])
        self.assertEqual(
            after["id"], before["id"],
            "[%s] CloudStack volume id changed (%s -> %s)"
            % (label, before["id"], after["id"]),
        )
        self.assertEqual(
            after["state"], before["state"],
            "[%s] CloudStack volume state changed (%s -> %s)"
            % (label, before["state"], after["state"]),
        )
        self.assertEqual(
            after["size"], before["size"],
            "[%s] CloudStack volume size changed (%s -> %s)"
            % (label, before["size"], after["size"]),
        )
        self.assertEqual(
            after["poolid"], before["poolid"],
            "[%s] CloudStack volume poolid changed (%s -> %s)"
            % (label, before["poolid"], after["poolid"]),
        )

    def _align_flexvol_bytes(self, value):
        """Round *value* down to the 4 KiB boundary ONTAP uses for FlexVol size."""
        return (int(value) // 4096) * 4096

    def _flexvol_used_bytes(self, vol_name):
        """ONTAP physical used bytes on the FlexVol (used + reserved)."""
        ontap_vol = self.ontap.get_volume(vol_name)
        self.assertIsNotNone(
            ontap_vol, "ONTAP FlexVol '%s' not found" % vol_name
        )
        space = ontap_vol.get("space") or {}
        return int(space.get("used") or 0)

    def _fill_flexvol_above_minimum(self, vol_name, timeout=90):
        """Write a FlexVol file until ONTAP used space exceeds the minimum.

        Returns ``(filler_filename_or_None, used_bytes)``.  The caller must
        delete any returned filename.  No CloudStack volume or VM is created.
        """
        used = self._flexvol_used_bytes(vol_name)
        if used > self.ONTAP_MIN_FLEXVOL_SIZE:
            log_progress(
                logger, "info",
                "FlexVol '%s' already has %d B used (ONTAP FlexVol minimum "
                "%d B); no filler file needed",
                vol_name, used, self.ONTAP_MIN_FLEXVOL_SIZE,
            )
            return None, used
        log_progress(
            logger, "info",
            "FlexVol '%s' has %d B used; writing %d B filler file '%s'",
            vol_name, used, self.FILLER_SIZE, self.FILLER_FILENAME,
        )
        self.ontap.write_file_in_volume(
            vol_name, self.FILLER_FILENAME, self.FILLER_SIZE
        )
        deadline = time.time() + timeout
        while time.time() < deadline:
            used = self._flexvol_used_bytes(vol_name)
            if used > self.ONTAP_MIN_FLEXVOL_SIZE:
                break
            time.sleep(2)
        self.assertGreater(
            used, self.ONTAP_MIN_FLEXVOL_SIZE,
            "FlexVol '%s' still has only %d B used after writing a %d B "
            "filler file; cannot exceed the ONTAP FlexVol minimum of %d B"
            % (vol_name, used, self.FILLER_SIZE, self.ONTAP_MIN_FLEXVOL_SIZE),
        )
        log_progress(
            logger, "info",
            "FlexVol '%s' has %d B used after filler file '%s'",
            vol_name, used, self.FILLER_FILENAME,
        )
        return self.FILLER_FILENAME, used

    def _delete_filler_file(self, vol_name, filename):
        if not filename or not vol_name:
            return
        try:
            self.ontap.delete_file_in_volume(vol_name, filename)
        except Exception as exc:
            logger.warning(
                "could not delete filler file '%s' in FlexVol '%s': %s",
                filename, vol_name, exc,
            )
        if self.__class__.filler_filename == filename:
            self.__class__.filler_filename = None
            self.__class__.filler_flexvol = None

    def _shrink_target_below_used(self, used_bytes):
        """4 KiB-aligned size below used_bytes but above the FlexVol min."""
        used_bytes = int(used_bytes)
        self.assertGreater(
            used_bytes, self.ONTAP_MIN_FLEXVOL_SIZE,
            "Used capacity %d B is not above the ONTAP FlexVol "
            "minimum %d B; cannot distinguish a used-capacity reject "
            "from a minimum-size reject"
            % (used_bytes, self.ONTAP_MIN_FLEXVOL_SIZE),
        )
        target = self._align_flexvol_bytes(used_bytes - 4096)
        if target <= self.ONTAP_MIN_FLEXVOL_SIZE:
            target = self._align_flexvol_bytes(
                (used_bytes + self.ONTAP_MIN_FLEXVOL_SIZE) // 2
            )
        self.assertGreater(
            target, self.ONTAP_MIN_FLEXVOL_SIZE,
            "Shrink target %d B is not above the ONTAP FlexVol minimum %d B"
            % (target, self.ONTAP_MIN_FLEXVOL_SIZE),
        )
        self.assertLess(
            target, used_bytes,
            "Shrink target %d B must be below used capacity %d B"
            % (target, used_bytes),
        )
        return target

    def _assert_capacity_update_rejected(
            self, cmd_pool_id, capacitybytes, label, verify_pool_id,
            volume_name, expected_error=None):
        """Assert updateStoragePool(capacitybytes) fails and sizes stay put.

        CloudStack and ONTAP are compared against their own pre-request values
        because the two do not have to agree: a FlexVol created with a snapshot
        reserve reports a larger space.size than the usable capacity
        CloudStack records.
        """
        listed = list_storage_pools(self.apiClient, id=verify_pool_id)
        self.assertTrue(
            listed,
            "[%s] listStoragePools returned no result for pool %s"
            % (label, verify_pool_id),
        )
        ontap_vol = self.ontap.get_volume(volume_name)
        self.assertIsNotNone(
            ontap_vol,
            "[%s] ONTAP FlexVol '%s' not found" % (label, volume_name),
        )
        before_cs = int(getattr(listed[0], "capacitybytes", 0) or 0)
        before_ontap = int(ontap_vol.get("space", {}).get("size", 0) or 0)
        log_progress(
            logger, "info",
            "Negative resize %s: pool_id=%s capacitybytes=%s "
            "(expect reject; CS=%d B ONTAP=%d B)",
            label, cmd_pool_id, capacitybytes, before_cs, before_ontap,
        )
        cmd = updateStoragePoolAPI.updateStoragePoolCmd()
        cmd.id = cmd_pool_id
        cmd.capacitybytes = capacitybytes
        with self.assertRaises(CloudstackAPIException) as caught:
            self.apiClient.updateStoragePool(cmd)
        error_text = str(caught.exception)
        log_progress(
            logger, "info", "Rejected resize %s: %s", label, error_text,
        )
        if expected_error:
            needles = (
                expected_error
                if isinstance(expected_error, (list, tuple))
                else (expected_error,)
            )
            self.assertTrue(
                any(needle in error_text for needle in needles),
                "[%s] expected the rejection to report one of %r, got: %s"
                % (label, needles, error_text),
            )

        listed = list_storage_pools(self.apiClient, id=verify_pool_id)
        self.assertTrue(
            listed,
            "[%s] pool disappeared after rejected resize" % label,
        )
        after_cs = int(getattr(listed[0], "capacitybytes", 0) or 0)
        self.assertEqual(
            after_cs, before_cs,
            "[%s] CloudStack capacity changed after rejected resize "
            "(got %d, want %d)" % (label, after_cs, before_cs),
        )
        self.assertEqual(
            listed[0].state, "Up",
            "[%s] pool should remain Up after rejected resize, got '%s'"
            % (label, listed[0].state),
        )
        ontap_vol = self.ontap.get_volume(volume_name)
        self.assertIsNotNone(
            ontap_vol,
            "[%s] ONTAP FlexVol disappeared after rejected resize" % label,
        )
        after_ontap = int(ontap_vol.get("space", {}).get("size", 0) or 0)
        self.assertEqual(
            after_ontap, before_ontap,
            "[%s] ONTAP FlexVol size changed after rejected resize "
            "(got %d, want %d)" % (label, after_ontap, before_ontap),
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


# ---------------------------------------------------------------------------
# Module-level VM helpers
# ---------------------------------------------------------------------------

def _list_vms_cmd(vm_id):
    cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
    cmd.id = vm_id
    cmd.listall = True
    return cmd


def _list_vols_cmd(vol_id):
    cmd = listVolumesAPI.listVolumesCmd()
    cmd.id = vol_id
    cmd.listall = True
    return cmd


def _wait_for_vm_state(api_client, vm_id, target_state, timeout=120,
                       interval=5):
    """Poll listVirtualMachines until the VM reaches target_state.

    Returns the last VM object seen, which may not be in target_state if the
    timeout expires — callers assert on the state themselves.
    """
    deadline = time.time() + timeout
    vm_obj = None
    while time.time() < deadline:
        vms = api_client.listVirtualMachines(_list_vms_cmd(vm_id)) or []
        if vms:
            vm_obj = vms[0]
            if vm_obj.state == target_state:
                return vm_obj
        time.sleep(interval)
    return vm_obj
