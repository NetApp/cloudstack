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

"""Shared QoS offering cases for the NFS3 and iSCSI Marvin suites.

Each test is independent. setUpClass creates one cluster pool. tearDown
removes that test's CloudStack VMs and volumes, then the ONTAP LUN or NFS
file and any QoS policy those volumes no longer use. tearDownClass unmounts
an NFS pool on the KVM hosts, then force-deletes the CloudStack pool and
the ONTAP FlexVol.

Minimum IOPS are applied only on AFF. On FAS the same request must fail
before ONTAP creates a policy. The driver writes that reason to the
management log; the API error only says the volume create failed.
createDiskOffering rejects a minimum of 0, so a max-only offering is
stored with min_iops = 0 after the API create.
"""

import functools
import logging
import random
import shlex
import time

from nose.plugins.attrib import attr

from marvin.cloudstackAPI import (
    attachVolume as attachVolumeAPI,
    createServiceOffering as createServiceOfferingAPI,
    createStoragePool as createStoragePoolAPI,
    createVolume as createVolumeAPI,
    deleteVolume as deleteVolumeAPI,
    deployVirtualMachine as deployVirtualMachineAPI,
    destroyVolume as destroyVolumeAPI,
    detachVolume as detachVolumeAPI,
    enableStorageMaintenance,
    listServiceOfferings as listServiceOfferingsAPI,
    listVolumes as listVolumesAPI,
    resizeVolume as resizeVolumeAPI,
)
from marvin.cloudstackException import CloudstackAPIException
from marvin.lib.base import DiskOffering, ServiceOffering, StoragePool
from marvin.lib.common import list_storage_pools

from helpers.template_cache_base import template_cache_capacity_bytes
from ontap_test_base import OntapRestClient, OntapTestBase, get_datacenter_config

logger = logging.getLogger("OntapQosOfferings")

FAS_MIN_IOPS = (
    "Minimum IOPS must be 0 for FAS. Only a Maximum IOPS limit is supported"
)
MIN_ABOVE_MAX = "min IOPS must be less than or equal to the max IOPS"
MIN_NOT_POSITIVE = "min IOPS must be greater than 0"
POOL_IOPS_EXCEEDED = "Insufficient IOPS capacity"
STRICT_DISK_OVERRIDE = "strictly mapped to its disk offering"


def tag_qos_tests(cls, tag):
    """Give every inherited test the protocol tag the runner filters on."""
    for name in dir(OntapQosOfferingsBase):
        if not name.startswith("test_"):
            continue
        method = getattr(OntapQosOfferingsBase, name)

        @functools.wraps(method)
        def wrapped(self, _method=method, *args, **kwargs):
            return _method(self, *args, **kwargs)

        setattr(cls, name, attr(tags=[tag], required_hardware=True)(wrapped))


class OntapQosOfferingsBase(OntapTestBase):
    """Protocol subclasses set PROTOCOL, PROTOCOL_CFG_KEY and POOL_URL_SCHEME."""

    PROTOCOL = "NFS3"
    PROTOCOL_CFG_KEY = "nfs3"
    POOL_URL_SCHEME = "nfs"
    _vol_name_prefix = "QosVol"

    @classmethod
    def setUpClass(cls):
        super(OntapQosOfferingsBase, cls).setUpClass()
        testclient = super(OntapQosOfferingsBase, cls).getClsTestClient()
        cls.apiClient = testclient.getApiClient()
        cls.dbConnection = testclient.getDbConnection()
        config = get_datacenter_config(testclient, cls)

        ontap_cfg = config.get("ontap", {})
        pool_cfg = config.get("storagePool", {})
        proto_cfg = pool_cfg.get("protocols", {}).get(cls.PROTOCOL_CFG_KEY, {})
        if not proto_cfg.get("enabled", True):
            raise unittest_skip(
                "%s tests disabled in ontap.cfg" % cls.PROTOCOL
            )

        cls.config_data = config
        cls.svm_name = ontap_cfg.get("svmName", "")
        cls.ontap = OntapRestClient(
            ontap_cfg.get("storageIP", ""),
            ontap_cfg.get("username", ""),
            ontap_cfg.get("password", ""),
        )
        cls.pool_tag = "%s-qos-%d" % (
            proto_cfg.get("storagePoolTags", "ontap"),
            random.randint(0, 99999),
        )
        cls.extra_volumes = []
        cls._qos_vms = []
        cls._account_spec = {
            "email": "ontap-qos@test.com",
            "firstname": "ONTAP",
            "lastname": "QoS",
            "username": "ontap_qos_%d" % random.randint(0, 99999),
            "password": "password",
        }
        cls.testdata = {
            "primaryStorage": {
                "scope": pool_cfg.get("storagePoolScope", "CLUSTER"),
                "provider": pool_cfg.get("storagePoolProvider", "NetApp ONTAP"),
                "capacitybytes": template_cache_capacity_bytes(pool_cfg),
                "details": {
                    "username": ontap_cfg.get("username", ""),
                    "password": _b64(ontap_cfg.get("password", "")),
                    "svmName": cls.svm_name,
                    "protocol": cls.PROTOCOL,
                    "storageIP": ontap_cfg.get("storageIP", ""),
                },
            },
        }
        cls._setup_cloudstack_resources(config, cls._account_spec)
        cls._discover_vm_deploy_resources()
        cls.is_aff = cls.ontap.is_all_flash()
        logger.info(
            "ONTAP QoS platform for %s is %s",
            cls.PROTOCOL, "AFF" if cls.is_aff else "FAS",
        )
        cls.pool = cls._create_qos_pool()

    @classmethod
    def _create_qos_pool(cls, capacityiops=None, name=None):
        ps = cls.testdata["primaryStorage"]
        storage_ip = ps["details"]["storageIP"]
        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = name or ("%sQos_%d" % (cls.PROTOCOL, random.randint(0, 99999)))
        cmd.url = "%s://%s/ontap" % (cls.POOL_URL_SCHEME, storage_ip)
        cmd.zoneid = cls.zone.id
        cmd.scope = ps["scope"]
        if str(ps["scope"]).upper() == "CLUSTER":
            cmd.clusterid = cls.cluster.id
            cmd.podid = cls.cluster.podid
        cmd.provider = ps["provider"]
        cmd.tags = cls.pool_tag
        cmd.capacitybytes = ps["capacitybytes"]
        if capacityiops is not None:
            cmd.capacityiops = int(capacityiops)
        cmd.hypervisor = "KVM"
        cmd.managed = True
        for index, (key, value) in enumerate(ps["details"].items(), 1):
            setattr(cmd, "details[%d].%s" % (index, key), value)
        pool = StoragePool(cls.apiClient.createStoragePool(cmd).__dict__)
        deadline = time.time() + 180
        while time.time() < deadline:
            listed = list_storage_pools(cls.apiClient, id=pool.id) or []
            if listed and listed[0].state == "Up":
                return pool
            time.sleep(5)
        raise RuntimeError("storage pool %s did not reach Up" % cmd.name)

    @classmethod
    def tearDownClass(cls):
        for vm in list(getattr(cls, "_qos_vms", []) or []):
            cls.vm = vm
            cls._destroy_vm_if_present()
        cls._qos_vms = []
        for volume in list(getattr(cls, "extra_volumes", []) or []):
            cls._cleanup_tracked_volume(volume)
        # Unmount NFS before the base class deletes the FlexVol. A stale
        # mount makes KVMHAMonitor reboot the host.
        if cls.PROTOCOL == "NFS3":
            for pool in (getattr(cls, "pool2", None), getattr(cls, "pool", None)):
                if pool is None:
                    continue
                try:
                    cls._cleanup_kvm_storage_pool_mounts(pool.id)
                except Exception as exc:
                    logger.warning(
                        "tearDownClass: could not unmount pool %s: %s",
                        getattr(pool, "id", None), exc,
                    )
        super(OntapQosOfferingsBase, cls).tearDownClass()

    def setUp(self):
        self._volumes = []
        self._vms = []

    def tearDown(self):
        cls = self.__class__
        for vm in list(self._vms):
            cls.vm = vm
            cls._destroy_vm_if_present()
            if vm in cls._qos_vms:
                cls._qos_vms.remove(vm)
        self._vms = []
        for volume in list(self._volumes):
            self._delete_volume(volume)
            if volume in cls.extra_volumes:
                cls.extra_volumes.remove(volume)
        self._volumes = []

    # ------------------------------------------------------------------
    # Offerings
    # ------------------------------------------------------------------

    def _disk_offering(self, disksize=1, miniops=None, maxiops=None,
                       custom_size=False, custom_iops=False,
                       provisioningtype=None, zero_min=False,
                       zero_max=False, tagged=True, bytesreadrate=None):
        data = {
            "name": "ontap-qos-do-%d" % random.randint(0, 999999),
            "displaytext": "ONTAP QoS disk offering",
            "storagetype": "shared",
        }
        if not custom_size:
            data["disksize"] = disksize
        if custom_iops:
            data["customizediops"] = True
        elif miniops is not None or maxiops is not None:
            # The create API rejects a minimum of 0, so a max-only or
            # min-only offering is adjusted in the database after create.
            high = int(maxiops if maxiops else miniops)
            low = int(miniops if miniops else high)
            if low <= 0 or (maxiops is not None and low > int(maxiops)):
                low = high
            data["miniops"] = low
            data["maxiops"] = int(maxiops if maxiops is not None else low)
        if provisioningtype:
            data["provisioningtype"] = provisioningtype
        if tagged:
            data["tags"] = self.__class__.pool_tag
        kwargs = {}
        if bytesreadrate is not None:
            kwargs["bytesreadrate"] = int(bytesreadrate)
            kwargs["iopsreadrate"] = int(bytesreadrate)
        offering = DiskOffering.create(
            self.apiClient, data, custom=custom_size, **kwargs
        )
        self.__class__._cleanup.append(offering)
        if zero_min:
            self._set_disk_iops(offering.id, min_iops=0)
        if zero_max:
            self._set_disk_iops(offering.id, max_iops=0)
        return offering

    def _fixed_compute(self, disk=None, strict=False, miniops=None,
                       maxiops=None, custom_iops=False):
        data = {
            "name": "ontap-qos-so-%d" % random.randint(0, 999999),
            "displaytext": "ONTAP QoS compute offering",
            "cpunumber": 1,
            "cpuspeed": 500,
            "memory": 512,
            "storagetype": "shared",
        }
        if disk is None:
            data["tags"] = self.__class__.pool_tag
        if custom_iops:
            data["customizediops"] = True
        elif miniops is not None or maxiops is not None:
            # Omitted customizediops arrives as null, and the server then
            # clears both IOPS on the implicit disk offering.
            high = int(maxiops if maxiops else miniops)
            data["customizediops"] = False
            data["miniops"] = high
            data["maxiops"] = high
        if disk is not None:
            data["diskofferingid"] = disk.id
        if strict:
            data["diskofferingstrictness"] = True
        offering = ServiceOffering.create(self.apiClient, data)
        self.__class__._cleanup.append(offering)
        if not custom_iops and miniops is not None and int(miniops) <= 0:
            self._set_disk_iops(self._service_disk_uuid(offering), min_iops=0)
        return offering

    def _custom_compute(self, disk):
        cmd = createServiceOfferingAPI.createServiceOfferingCmd()
        cmd.name = "ontap-qos-cso-%d" % random.randint(0, 999999)
        cmd.displaytext = "ONTAP QoS custom compute offering"
        cmd.customized = True
        cmd.storagetype = "shared"
        cmd.diskofferingid = disk.id
        offering = ServiceOffering(
            self.apiClient.createServiceOffering(cmd).__dict__
        )
        self.__class__._cleanup.append(offering)
        return offering

    def _constrained_compute(self, disk, min_cpu=1, max_cpu=2,
                             min_memory=512, max_memory=1024, cpu_speed=500):
        """Custom offering whose CPU count and memory must fall inside a range.

        CloudStack stores the CPU speed on the offering and requires the
        speed together with every min and max. The deployer still chooses
        the CPU count and memory at deploy time.
        """
        cmd = createServiceOfferingAPI.createServiceOfferingCmd()
        cmd.name = "ontap-qos-ccso-%d" % random.randint(0, 999999)
        cmd.displaytext = "ONTAP QoS constrained compute offering"
        cmd.customized = True
        cmd.storagetype = "shared"
        cmd.diskofferingid = disk.id
        cmd.cpuspeed = int(cpu_speed)
        cmd.mincpunumber = int(min_cpu)
        cmd.maxcpunumber = int(max_cpu)
        cmd.minmemory = int(min_memory)
        cmd.maxmemory = int(max_memory)
        offering = ServiceOffering(
            self.apiClient.createServiceOffering(cmd).__dict__
        )
        self.__class__._cleanup.append(offering)
        return offering

    def _service_disk_uuid(self, offering):
        disk_id = getattr(offering, "diskofferingid", None)
        if disk_id:
            return disk_id
        cmd = listServiceOfferingsAPI.listServiceOfferingsCmd()
        cmd.id = offering.id
        rows = self.apiClient.listServiceOfferings(cmd) or []
        self.assertTrue(rows, "service offering %s is not listed" % offering.id)
        disk_id = getattr(rows[0], "diskofferingid", None)
        self.assertTrue(
            disk_id, "service offering %s has no disk offering" % offering.id
        )
        return disk_id

    def _set_disk_iops(self, offering_uuid, min_iops=None, max_iops=None):
        columns = []
        params = []
        if min_iops is not None:
            columns.append("min_iops = %s")
            params.append(int(min_iops))
        if max_iops is not None:
            columns.append("max_iops = %s")
            params.append(int(max_iops))
        params.append(offering_uuid)
        self.__class__.dbConnection.execute(
            "UPDATE disk_offering SET %s WHERE uuid = %%s" % ", ".join(columns),
            tuple(params),
        )

    # ------------------------------------------------------------------
    # Volumes, VMs, and ONTAP checks
    # ------------------------------------------------------------------

    def _new_volume_name(self):
        return "%s%d" % (self._vol_name_prefix, random.randint(100000, 999999))

    def _create_data_volume(self, offering, size=None, miniops=None,
                            maxiops=None, name=None, pool=None):
        pool = pool or self.__class__.pool
        cmd = createVolumeAPI.createVolumeCmd()
        cmd.name = name or self._new_volume_name()
        cmd.diskofferingid = offering.id
        cmd.zoneid = self.zone.id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        cmd.storageid = pool.id
        if size is not None:
            cmd.size = int(size)
        if miniops is not None:
            cmd.miniops = int(miniops)
        if maxiops is not None:
            cmd.maxiops = int(maxiops)
        volume = self.apiClient.createVolume(cmd)
        self._remember_volume(volume)
        return volume

    def _remember_volume(self, volume):
        volume_id = getattr(volume, "id", None)
        if any(getattr(item, "id", None) == volume_id for item in self._volumes):
            return
        self._volumes.append(volume)
        if not any(getattr(item, "id", None) == volume_id
                   for item in self.__class__.extra_volumes):
            self.__class__.extra_volumes.append(volume)

    def _delete_volume(self, volume):
        self.__class__._cleanup_tracked_volume(volume)

    @classmethod
    def _cleanup_tracked_volume(cls, volume):
        """Remove one volume from CloudStack, then its ONTAP object.

        deleteVolume is tried first. destroyVolume(expunge=False) is the
        fallback used by the other suites when libvirt cannot expunge the
        volume. A shared QoS policy is removed only after its last object
        is gone.
        """
        if volume is None or not getattr(volume, "id", None):
            return
        if getattr(cls, "apiClient", None) is None:
            return
        policy_name, lun_path, nfs_path = cls._ontap_volume_identity(volume)
        cls._detach_volume_if_attached(volume.id)
        try:
            cmd = deleteVolumeAPI.deleteVolumeCmd()
            cmd.id = volume.id
            cls.apiClient.deleteVolume(cmd)
        except Exception as exc:
            logger.warning(
                "cleanup: deleteVolume failed for %s (%s); "
                "falling back to destroyVolume",
                volume.id, exc,
            )
            try:
                destroy = destroyVolumeAPI.destroyVolumeCmd()
                destroy.id = volume.id
                destroy.expunge = False
                cls.apiClient.destroyVolume(destroy)
            except Exception as destroy_exc:
                logger.warning(
                    "cleanup: destroyVolume also failed for %s: %s",
                    volume.id, destroy_exc,
                )
        cls._delete_ontap_volume_object(lun_path, nfs_path)
        cls._delete_unused_qos_policy(policy_name)

    @classmethod
    def _ontap_volume_identity(cls, volume):
        """LUN path or NFS file path, and the QoS policy name, if present."""
        pool = getattr(cls, "pool", None)
        pool_name = getattr(pool, "name", None)
        nfs_path = getattr(volume, "path", None)
        lun_path = None
        if pool_name and getattr(volume, "name", None):
            lun_path = "/vol/%s/%s" % (
                pool_name, volume.name.replace("-", "_")
            )
        record = None
        try:
            if cls.PROTOCOL == "NFS3" and pool_name and nfs_path:
                record = cls.ontap.get_nfs_file(pool_name, nfs_path)
            elif cls.PROTOCOL != "NFS3" and lun_path:
                record = cls.ontap.get_lun(cls.svm_name, lun_path)
        except Exception as exc:
            logger.warning(
                "cleanup: could not read ONTAP object for volume %s: %s",
                volume.id, exc,
            )
        policy = (record or {}).get("qos_policy") or {}
        if isinstance(policy, dict):
            policy_name = policy.get("name")
        else:
            policy_name = policy or None
        return policy_name, lun_path, nfs_path

    @classmethod
    def _detach_volume_if_attached(cls, volume_id):
        try:
            cmd = listVolumesAPI.listVolumesCmd()
            cmd.id = volume_id
            cmd.listall = True
            listed = cls.apiClient.listVolumes(cmd) or []
        except Exception as exc:
            logger.warning(
                "cleanup: could not list volume %s: %s", volume_id, exc
            )
            return
        if not listed or not getattr(listed[0], "virtualmachineid", None):
            return
        try:
            detach = detachVolumeAPI.detachVolumeCmd()
            detach.id = volume_id
            cls.apiClient.detachVolume(detach)
        except Exception as exc:
            logger.warning(
                "cleanup: could not detach volume %s: %s", volume_id, exc
            )

    @classmethod
    def _delete_ontap_volume_object(cls, lun_path, nfs_path):
        pool = getattr(cls, "pool", None)
        pool_name = getattr(pool, "name", None)
        ontap = getattr(cls, "ontap", None)
        if ontap is None or not pool_name:
            return
        try:
            if cls.PROTOCOL == "NFS3" and nfs_path:
                ontap.delete_file_in_volume(pool_name, nfs_path)
            elif lun_path:
                ontap.delete_lun(cls.svm_name, lun_path)
        except Exception as exc:
            logger.warning(
                "cleanup: could not delete ONTAP object for pool %s: %s",
                pool_name, exc,
            )

    @classmethod
    def _delete_unused_qos_policy(cls, policy_name):
        if not policy_name or not str(policy_name).startswith("cs_"):
            return
        ontap = getattr(cls, "ontap", None)
        if ontap is None:
            return
        try:
            policy = ontap.get_qos_policy(cls.svm_name, policy_name)
            if not policy:
                return
            count = policy.get("object_count")
            if count is None or int(count) > 0:
                return
            ontap.delete_qos_policy(cls.svm_name, policy_name)
        except Exception as exc:
            logger.warning(
                "cleanup: could not delete QoS policy %s: %s",
                policy_name, exc,
            )

    def _delete_volumes_named(self, name):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.name = name
        cmd.listall = True
        cmd.zoneid = self.zone.id
        for volume in self.apiClient.listVolumes(cmd) or []:
            if getattr(volume, "name", None) == name:
                self._delete_volume(volume)

    def _get_volume(self, volume_id):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.id = volume_id
        cmd.listall = True
        volumes = self.apiClient.listVolumes(cmd) or []
        return volumes[0] if volumes else None

    def _wait_ready(self, volume_id, timeout=300):
        deadline = time.time() + timeout
        last = "missing"
        while time.time() < deadline:
            volume = self._get_volume(volume_id)
            if volume is not None:
                last = getattr(volume, "state", "")
                if str(last).lower() == "ready":
                    return volume
            time.sleep(5)
        self.fail("volume %s did not become Ready (last state %s)" % (volume_id, last))

    def _policy_name(self, miniops, maxiops):
        svm = str(self.__class__.svm_name).replace(".", "_")
        return "cs_%d_to_%d_iops_%s" % (int(miniops), int(maxiops), svm)

    def _backend(self, volume):
        pool_name = self.__class__.pool.name
        deadline = time.time() + 90
        current = volume
        while time.time() < deadline:
            if self.PROTOCOL == "NFS3":
                path = getattr(current, "path", None)
                if not path:
                    current = self._get_volume(volume.id) or current
                    path = getattr(current, "path", None)
                if path:
                    record = self.ontap.get_nfs_file(pool_name, path)
                    if record:
                        return record
            else:
                lun_path = "/vol/%s/%s" % (
                    pool_name, current.name.replace("-", "_")
                )
                record = self.ontap.get_lun(self.svm_name, lun_path)
                if record:
                    return record
            time.sleep(5)
        self.fail(
            "no ONTAP object for volume %s (%s)"
            % (volume.id, getattr(volume, "name", ""))
        )

    def _policy_on(self, record):
        policy = (record or {}).get("qos_policy") or {}
        if isinstance(policy, dict):
            return policy.get("name")
        return policy

    def _offering_row(self, offering):
        cmd = listServiceOfferingsAPI.listServiceOfferingsCmd()
        cmd.id = offering.id
        rows = self.apiClient.listServiceOfferings(cmd) or []
        self.assertTrue(rows, "service offering %s is not listed" % offering.id)
        return rows[0]

    def _offering_details(self, offering):
        """Read the constrained CPU and memory limits from the offering.

        Marvin turns the serviceofferingdetails map into its own object.
        Iterating that object never finishes, because a missing index
        returns None instead of raising IndexError. The four limits are
        attributes on the object.
        """
        raw = getattr(self._offering_row(offering), "serviceofferingdetails", None)
        if isinstance(raw, dict):
            source = raw
        else:
            source = {}
            for name in ("mincpunumber", "maxcpunumber", "minmemory", "maxmemory"):
                value = getattr(raw, name, None)
                if value is not None:
                    source[name] = value
        return dict((str(key), str(value)) for key, value in source.items())

    def _assert_constrained_offering(self, offering, min_cpu, max_cpu,
                                     min_memory, max_memory, cpu_speed):
        row = self._offering_row(offering)
        self.assertTrue(getattr(row, "iscustomized", False))
        self.assertEqual(int(getattr(row, "cpuspeed", 0) or 0), int(cpu_speed))
        self.assertFalse(getattr(row, "cpunumber", None))
        self.assertFalse(getattr(row, "memory", None))
        details = self._offering_details(offering)
        self.assertEqual(details.get("mincpunumber"), str(min_cpu))
        self.assertEqual(details.get("maxcpunumber"), str(max_cpu))
        self.assertEqual(details.get("minmemory"), str(min_memory))
        self.assertEqual(details.get("maxmemory"), str(max_memory))

    def _assert_vm_compute(self, vm, cpu, speed, memory):
        cmd = _vm_cmd(vm.id)
        rows = self.apiClient.listVirtualMachines(cmd) or []
        self.assertTrue(rows, "VM %s is not listed" % vm.id)
        current = rows[0]
        self.assertEqual(int(current.cpunumber), int(cpu))
        self.assertEqual(int(current.cpuspeed), int(speed))
        self.assertEqual(int(current.memory), int(memory))
        return current

    def _assert_qos(self, volume, miniops, maxiops):
        current = self._wait_ready(volume.id)
        self.assertEqual(int(getattr(current, "miniops", 0) or 0), int(miniops))
        self.assertEqual(int(getattr(current, "maxiops", 0) or 0), int(maxiops))
        expected = self._policy_name(miniops, maxiops)
        deadline = time.time() + 120
        seen = None
        while time.time() < deadline:
            current = self._get_volume(volume.id) or current
            seen = self._policy_on(self._backend(current))
            if seen == expected:
                break
            time.sleep(5)
        self.assertEqual(
            seen, expected,
            "volume %s QoS is %s, expected %s" % (volume.id, seen, expected),
        )
        policy = self.ontap.get_qos_policy(self.svm_name, expected)
        self.assertIsNotNone(policy, "ONTAP has no policy %s" % expected)
        fixed = policy.get("fixed") or {}
        if int(miniops) > 0 and fixed.get("min_throughput_iops") is not None:
            self.assertEqual(int(fixed["min_throughput_iops"]), int(miniops))
        if int(maxiops) > 0 and fixed.get("max_throughput_iops") is not None:
            self.assertEqual(int(fixed["max_throughput_iops"]), int(maxiops))
        return current

    def _assert_no_qos(self, volume):
        current = self._wait_ready(volume.id)
        self.assertEqual(int(getattr(current, "miniops", 0) or 0), 0)
        self.assertEqual(int(getattr(current, "maxiops", 0) or 0), 0)
        deadline = time.time() + 90
        seen = "missing"
        while time.time() < deadline:
            current = self._get_volume(volume.id) or current
            seen = self._policy_on(self._backend(current))
            if not seen or str(seen).lower() == "none":
                return current
            time.sleep(5)
        self.fail("volume %s still has QoS policy %s" % (volume.id, seen))

    def _assert_policy_absent(self, miniops, maxiops, timeout=180):
        name = self._policy_name(miniops, maxiops)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.ontap.get_qos_policy(self.svm_name, name) is None:
                return
            time.sleep(5)
        self.fail("ONTAP policy %s is still present" % name)

    def _mgmt_ssh(self):
        """Management server address and login from ontap.cfg."""
        config = self.__class__.config_data
        mgt = (config.get("mgtSvr") or [None])[0] or {}
        host = {}
        try:
            host = config["zones"][0]["pods"][0]["clusters"][0]["hosts"][0]
        except (IndexError, KeyError, TypeError):
            host = {}
        ip = mgt.get("mgtSvrIp")
        user = host.get("username")
        password = host.get("password")
        self.assertTrue(ip and user and password,
                        "ontap.cfg has no management server SSH login")
        return ip, user, password

    def _assert_fas_rejection_logged(self, volume_name):
        """The API wraps the driver error. The management log keeps it.

        The log file is vmops.log in the working directory of the running
        management server, found from that process rather than a fixed path.
        """
        import paramiko
        ip, user, password = self._mgmt_ssh()
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        script = (
            "pid=$(ps -eo pid,args | awk "
            "'/java/ && /jetty:run/ && !/awk/ {print $1; exit}'); "
            "if [ -z \"$pid\" ]; then echo NO_JAVA; exit 0; fi; "
            "cwd=$(readlink -f /proc/$pid/cwd 2>/dev/null || "
            "readlink /proc/$pid/cwd); "
            "log=\"$cwd/vmops.log\"; "
            "if [ ! -f \"$log\" ]; then echo NO_LOG; exit 0; fi; "
            "grep -F -- %s \"$log\" | grep -F -- %s | tail -1"
        ) % (shlex.quote(volume_name), shlex.quote(FAS_MIN_IOPS))
        try:
            client.connect(
                ip, username=user, password=password,
                allow_agent=False, look_for_keys=False, timeout=20,
            )
            _stdin, stdout, stderr = client.exec_command(script, timeout=30)
            out = stdout.read().decode("utf-8", "replace").strip()
            err = stderr.read().decode("utf-8", "replace").strip()
        finally:
            client.close()
        self.assertTrue(
            volume_name in out and FAS_MIN_IOPS in out,
            "management log has no FAS rejection for %s (%s %s)"
            % (volume_name, out[:200], err[:200]),
        )

    def _create_min_iops_volume(self, offering, miniops, maxiops, size=None,
                                custom_miniops=None, custom_maxiops=None):
        """Create the volume, or require the FAS rejection when min IOPS > 0.

        AFF: the volume exists and its QoS policy has the requested IOPS.
        FAS: createVolume fails, the management log has the driver message,
        and ONTAP has no policy for those IOPS.
        """
        if self.__class__.is_aff or int(miniops) <= 0:
            volume = self._create_data_volume(
                offering, size=size, miniops=custom_miniops,
                maxiops=custom_maxiops,
            )
            self._assert_qos(volume, miniops, maxiops)
            return volume
        name = self._new_volume_name()
        with self.assertRaises(Exception):
            self._create_data_volume(
                offering, size=size, name=name,
                miniops=custom_miniops, maxiops=custom_maxiops,
            )
        self._assert_fas_rejection_logged(name)
        self._delete_volumes_named(name)
        self._assert_policy_absent(miniops, maxiops)
        return None

    def _require_vm(self):
        cls = self.__class__
        if not cls.template_id:
            self.skipTest("No ready KVM template in zone %s" % cls.zone.name)
        if not cls.cluster_hosts:
            self.skipTest("No routing host in cluster %s" % cls.cluster.name)
        if (str(getattr(cls.zone, "networktype", "")).lower() == "advanced"
                and not cls.network_id):
            self.skipTest("Advanced zone has no guest network for the QoS account")

    def _deploy(self, service_offering, root_disk=None, rootdisksize=None,
                details=None):
        self._require_vm()
        cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
        cmd.zoneid = self.zone.id
        cmd.templateid = self.__class__.template_id
        cmd.serviceofferingid = service_offering.id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        cmd.hostid = self.__class__.cluster_hosts[0].id
        if root_disk is not None:
            cmd.overridediskofferingid = root_disk.id
        if rootdisksize is not None:
            cmd.rootdisksize = int(rootdisksize)
        if self.__class__.network_id:
            cmd.networkids = self.__class__.network_id
        if details:
            cmd.details = [details]
        old_timeout = getattr(self.apiClient.connection, "asyncTimeout", 3600)
        self.apiClient.connection.asyncTimeout = 1800
        try:
            vm = self.apiClient.deployVirtualMachine(cmd)
        finally:
            self.apiClient.connection.asyncTimeout = old_timeout
        self.assertIsNotNone(vm, "deployVirtualMachine returned no VM")
        self._vms.append(vm)
        self.__class__._qos_vms.append(vm)
        self.__class__.vm = vm
        return self._wait_vm(vm.id, "Running")

    def _wait_vm(self, vm_id, state, timeout=1800):
        deadline = time.time() + timeout
        last = "missing"
        while time.time() < deadline:
            cmd = _vm_cmd(vm_id)
            vms = self.apiClient.listVirtualMachines(cmd) or []
            if vms:
                last = vms[0].state
                if str(last).lower() == state.lower():
                    return vms[0]
            time.sleep(5)
        self.fail("VM %s did not reach %s (last state %s)" % (vm_id, state, last))

    def _root_volume(self, vm):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.virtualmachineid = vm.id
        cmd.listall = True
        volumes = self.apiClient.listVolumes(cmd) or []
        roots = [
            volume for volume in volumes
            if str(getattr(volume, "type", "")).upper() == "ROOT"
        ]
        self.assertEqual(len(roots), 1, "VM %s has %d ROOT volumes" % (vm.id, len(roots)))
        ready = self._wait_ready(roots[0].id, timeout=600)
        self._remember_volume(ready)
        self.assertEqual(
            ready.storageid, self.__class__.pool.id,
            "ROOT volume is on pool %s, expected %s"
            % (ready.storageid, self.__class__.pool.id),
        )
        return ready

    def _attach(self, vm, volume):
        cmd = attachVolumeAPI.attachVolumeCmd()
        cmd.id = volume.id
        cmd.virtualmachineid = vm.id
        self.apiClient.attachVolume(cmd)
        deadline = time.time() + 300
        while time.time() < deadline:
            current = self._get_volume(volume.id)
            if current and getattr(current, "virtualmachineid", None) == vm.id:
                return current
            time.sleep(5)
        self.fail("volume %s did not attach to VM %s" % (volume.id, vm.id))

    def _resize(self, volume, offering):
        """Move a volume onto another disk offering.

        A fixed offering supplies its own size, so no size is sent; the API
        rejects a size on a non-custom offering.
        """
        cmd = resizeVolumeAPI.resizeVolumeCmd()
        cmd.id = volume.id
        cmd.diskofferingid = offering.id
        self.apiClient.resizeVolume(cmd)
        return self._wait_ready(volume.id, timeout=600)

    def _force_delete_pool(self, pool):
        """CloudStack force-delete, then the ONTAP FlexVol and export policy.

        NFS is unmounted first. If that unmount fails, the pool is left in
        place so the export is not removed out from under KVM.
        """
        if pool is None:
            return
        if self.PROTOCOL == "NFS3":
            try:
                self._cleanup_kvm_storage_pool_mounts(pool.id)
            except Exception as exc:
                logger.warning(
                    "cleanup: could not unmount pool %s: %s", pool.name, exc
                )
                self.__class__.pool2 = pool
                return
        try:
            listed = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            listed = None
        if listed and getattr(listed[0], "state", None) != "Maintenance":
            try:
                maint = enableStorageMaintenance.enableStorageMaintenanceCmd()
                maint.id = pool.id
                self.apiClient.enableStorageMaintenance(maint)
                self._poll_pool_state(pool.id, "Maintenance", timeout=120)
            except Exception as exc:
                logger.warning("could not maintain pool %s: %s", pool.id, exc)
        if listed:
            try:
                self._delete_pool(pool.id, forced=True)
            except Exception as exc:
                logger.warning("could not delete pool %s: %s", pool.id, exc)
        self._delete_ontap_pool(pool)
        try:
            remaining = list_storage_pools(self.apiClient, id=pool.id)
        except Exception:
            remaining = None
        if not remaining:
            self.__class__.pool2 = None
        else:
            self.__class__.pool2 = pool

    def _delete_ontap_pool(self, pool):
        """Delete the FlexVol, and the NFS export policy, left after CloudStack."""
        try:
            if self.ontap.get_volume(pool.name) is not None:
                self.ontap.offline_and_delete_volume(pool.name)
        except Exception as exc:
            logger.warning(
                "cleanup: could not delete ONTAP FlexVol %s: %s",
                pool.name, exc,
            )
        if self.PROTOCOL != "NFS3":
            return
        ep_name = "cs-%s-%s" % (self.__class__.svm_name, pool.name)
        try:
            if self.ontap.get_export_policy(ep_name) is not None:
                self.ontap.delete_export_policy(ep_name)
        except Exception as exc:
            logger.warning(
                "cleanup: could not delete export policy %s: %s",
                ep_name, exc,
            )

    # ------------------------------------------------------------------
    # Disk offerings
    # ------------------------------------------------------------------

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_01_fixed_disk_without_iops(self):
        """A fixed disk with no IOPS creates the volume and no ONTAP policy."""
        offering = self._disk_offering()
        volume = self._create_data_volume(offering)
        self._assert_no_qos(volume)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_02_fixed_disk_max_iops_only(self):
        """A fixed disk with only a maximum maps to cs_0_to_{max}."""
        offering = self._disk_offering(miniops=0, maxiops=410, zero_min=True)
        volume = self._create_data_volume(offering)
        self._assert_qos(volume, 0, 410)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_03_fixed_disk_min_and_max_iops(self):
        """Min and max IOPS are applied on AFF and rejected on FAS."""
        offering = self._disk_offering(miniops=120, maxiops=460)
        self._create_min_iops_volume(offering, 120, 460)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_04_fixed_disk_min_iops_only(self):
        """A minimum with no maximum is applied on AFF and rejected on FAS."""
        offering = self._disk_offering(miniops=180, maxiops=180, zero_max=True)
        self._create_min_iops_volume(offering, 180, 0)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_05_custom_size_disk_with_fixed_iops(self):
        """A custom-size disk keeps the IOPS written on the offering."""
        offering = self._disk_offering(
            custom_size=True, miniops=0, maxiops=470, zero_min=True
        )
        volume = self._create_data_volume(offering, size=1)
        self._assert_qos(volume, 0, 470)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_06_custom_iops_disk_max_only(self):
        """Custom IOPS with a zero minimum stores only the maximum."""
        offering = self._disk_offering(custom_iops=True)
        volume = self._create_data_volume(offering, miniops=0, maxiops=480)
        self._assert_qos(volume, 0, 480)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_07_custom_iops_disk_min_and_max(self):
        """Custom min and max IOPS are applied on AFF and rejected on FAS."""
        offering = self._disk_offering(custom_iops=True)
        self._create_min_iops_volume(
            offering, 130, 490, custom_miniops=130, custom_maxiops=490
        )

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_08_custom_size_and_custom_iops(self):
        """Size and IOPS can both be chosen when the volume is created."""
        offering = self._disk_offering(custom_size=True, custom_iops=True)
        volume = self._create_data_volume(
            offering, size=1, miniops=0, maxiops=500
        )
        self._assert_qos(volume, 0, 500)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_09_provisioning_types_keep_iops(self):
        """Thin, sparse, and fat disks still receive a max-only QoS policy."""
        for provisioning, max_iops in (("thin", 511), ("sparse", 512), ("fat", 513)):
            offering = self._disk_offering(
                miniops=0, maxiops=max_iops, zero_min=True,
                provisioningtype=provisioning,
            )
            volume = self._create_data_volume(offering)
            self._assert_qos(volume, 0, max_iops)
            self._delete_volume(volume)
            self._volumes.remove(volume)
            self.__class__.extra_volumes.remove(volume)
            self._assert_policy_absent(0, max_iops)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_10_shared_policy_survives_until_last_volume(self):
        """Two volumes with the same IOPS share a policy until both are gone."""
        offering = self._disk_offering(miniops=0, maxiops=610, zero_min=True)
        first = self._create_data_volume(offering)
        second = self._create_data_volume(offering)
        self._assert_qos(first, 0, 610)
        self._assert_qos(second, 0, 610)
        policy = self.ontap.get_qos_policy(self.svm_name, self._policy_name(0, 610))
        if policy is not None and policy.get("object_count") is not None:
            self.assertGreaterEqual(int(policy["object_count"]), 2)
        self._delete_volume(first)
        self._volumes.remove(first)
        self.__class__.extra_volumes.remove(first)
        survivor = self._assert_qos(second, 0, 610)
        self.assertIsNotNone(survivor)
        self._delete_volume(second)
        self._volumes.remove(second)
        self.__class__.extra_volumes.remove(second)
        self._assert_policy_absent(0, 610)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_11_resize_replaces_qos_policy(self):
        """Resizing onto another disk offering replaces the QoS policy."""
        original = self._disk_offering(disksize=1, miniops=0, maxiops=415, zero_min=True)
        resized = self._disk_offering(disksize=2, miniops=0, maxiops=825, zero_min=True)
        volume = self._create_data_volume(original)
        self._assert_qos(volume, 0, 415)
        self._resize(volume, resized)
        self._assert_qos(volume, 0, 825)
        self._assert_policy_absent(0, 415)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_12_resize_clears_qos_policy(self):
        """Resizing onto a disk offering without IOPS removes the policy."""
        original = self._disk_offering(disksize=1, miniops=0, maxiops=430, zero_min=True)
        cleared = self._disk_offering(disksize=2)
        volume = self._create_data_volume(original)
        self._assert_qos(volume, 0, 430)
        self._resize(volume, cleared)
        self._assert_no_qos(volume)
        self._assert_policy_absent(0, 430)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_13_reject_disk_offering_min_above_max(self):
        """A disk offering whose minimum exceeds its maximum is rejected."""
        with self.assertRaises(Exception) as caught:
            DiskOffering.create(self.apiClient, {
                "name": "ontap-qos-bad-%d" % random.randint(0, 999999),
                "displaytext": "invalid QoS disk",
                "disksize": 1,
                "storagetype": "shared",
                "miniops": 500,
                "maxiops": 100,
            })
        self.assertIn(MIN_ABOVE_MAX, str(caught.exception))

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_14_reject_disk_offering_min_iops_zero(self):
        """The disk-offering API rejects a minimum of zero when a maximum is set."""
        with self.assertRaises(Exception) as caught:
            DiskOffering.create(self.apiClient, {
                "name": "ontap-qos-zero-%d" % random.randint(0, 999999),
                "displaytext": "zero min QoS disk",
                "disksize": 1,
                "storagetype": "shared",
                "miniops": 0,
                "maxiops": 100,
            })
        self.assertIn(MIN_NOT_POSITIVE, str(caught.exception))

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_15_reject_custom_iops_min_above_max(self):
        """Custom IOPS on the volume are rejected when min is above max."""
        offering = self._disk_offering(custom_iops=True)
        name = self._new_volume_name()
        with self.assertRaises(Exception) as caught:
            self._create_data_volume(
                offering, name=name, miniops=400, maxiops=100
            )
        self.assertIn(MIN_ABOVE_MAX, str(caught.exception))
        self._delete_volumes_named(name)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_16_reject_pool_iops_capacity(self):
        """A minimum above the pool IOPS capacity is rejected on AFF."""
        if not self.__class__.is_aff:
            self.skipTest("Pool IOPS capacity is enforced on minimum IOPS, which FAS rejects earlier")
        offering = self._disk_offering(miniops=200, maxiops=200, tagged=True)
        pool = None
        try:
            pool = self.__class__._create_qos_pool(
                capacityiops=100,
                name="QosIopsCap_%d" % random.randint(0, 99999),
            )
            self.__class__.pool2 = pool
            name = self._new_volume_name()
            with self.assertRaises(Exception) as caught:
                self._create_data_volume(offering, name=name, pool=pool)
            self.assertIn(POOL_IOPS_EXCEEDED, str(caught.exception))
            self._delete_volumes_named(name)
            self._assert_policy_absent(200, 200)
        finally:
            self._force_delete_pool(pool)

    # ------------------------------------------------------------------
    # Compute offerings choose the root disk
    # ------------------------------------------------------------------

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_17_fixed_compute_override_qos_disk(self):
        """A fixed compute offering plus an override disk applies that disk's QoS."""
        root_disk = self._disk_offering(disksize=8, miniops=0, maxiops=440, zero_min=True)
        compute = self._fixed_compute()
        vm = self._deploy(compute, root_disk=root_disk)
        self._assert_qos(self._root_volume(vm), 0, 440)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_18_compute_offering_with_its_own_iops(self):
        """IOPS set on the compute offering are applied to its implicit root disk."""
        compute = self._fixed_compute(miniops=0, maxiops=450)
        vm = self._deploy(compute)
        self._assert_qos(self._root_volume(vm), 0, 450)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_19_compute_bound_to_qos_disk(self):
        """A compute offering bound to a disk offering uses that disk's QoS."""
        root_disk = self._disk_offering(disksize=8, miniops=0, maxiops=455, zero_min=True)
        compute = self._fixed_compute(disk=root_disk)
        vm = self._deploy(compute)
        self._assert_qos(self._root_volume(vm), 0, 455)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_20_strict_compute_rejects_disk_override(self):
        """A strict compute offering rejects a different disk at deploy."""
        bound = self._disk_offering(disksize=8, miniops=0, maxiops=465, zero_min=True)
        override = self._disk_offering(disksize=8, miniops=0, maxiops=475, zero_min=True)
        compute = self._fixed_compute(disk=bound, strict=True)
        with self.assertRaises(CloudstackAPIException) as caught:
            self._deploy(compute, root_disk=override)
        error = str(caught.exception)
        self.assertIn("errorCode: 431", error)
        self.assertIn(STRICT_DISK_OVERRIDE, error)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_21_custom_compute_with_qos_disk(self):
        """A custom compute offering deploys with the QoS of its bound disk."""
        root_disk = self._disk_offering(disksize=8, miniops=0, maxiops=485, zero_min=True)
        compute = self._custom_compute(root_disk)
        vm = self._deploy(compute, details={
            "cpuNumber": "1",
            "cpuSpeed": "500",
            "memory": "512",
        })
        self._assert_qos(self._root_volume(vm), 0, 485)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_22_custom_compute_custom_root_disk(self):
        """Custom CPU and a custom root disk apply the IOPS given at deploy."""
        root_disk = self._disk_offering(custom_size=True, custom_iops=True)
        compute = self._custom_compute(root_disk)
        vm = self._deploy(
            compute, rootdisksize=8,
            details={
                "cpuNumber": "1",
                "cpuSpeed": "500",
                "memory": "512",
                "minIops": "0",
                "maxIops": "540",
            },
        )
        self._assert_qos(self._root_volume(vm), 0, 540)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_23_custom_iops_on_compute_offering(self):
        """A compute offering with custom IOPS takes min and max from deploy details."""
        compute = self._fixed_compute(custom_iops=True)
        vm = self._deploy(compute, details={
            "minIops": "0",
            "maxIops": "530",
        })
        self._assert_qos(self._root_volume(vm), 0, 530)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_24_root_without_iops_and_qos_data_disk(self):
        """A plain root disk stays unlimited while the attached data disk has a policy."""
        root_disk = self._disk_offering(disksize=8)
        data_disk = self._disk_offering(miniops=0, maxiops=545, zero_min=True)
        compute = self._fixed_compute()
        vm = self._deploy(compute, root_disk=root_disk)
        self._assert_no_qos(self._root_volume(vm))
        data = self._create_data_volume(data_disk)
        self._attach(vm, data)
        self._assert_qos(data, 0, 545)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_25_root_and_data_use_different_policies(self):
        """Root and data disks keep the different policies from their offerings."""
        root_disk = self._disk_offering(disksize=8, miniops=0, maxiops=550, zero_min=True)
        data_disk = self._disk_offering(miniops=0, maxiops=560, zero_min=True)
        compute = self._fixed_compute()
        vm = self._deploy(compute, root_disk=root_disk)
        self._assert_qos(self._root_volume(vm), 0, 550)
        data = self._create_data_volume(data_disk)
        self._attach(vm, data)
        self._assert_qos(data, 0, 560)
        self._assert_qos(self._root_volume(vm), 0, 550)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_26_hypervisor_rate_limits_do_not_create_ontap_policy(self):
        """Hypervisor byte and IOPS rates are not an ONTAP QoS policy."""
        offering = self._disk_offering(bytesreadrate=1048576)
        volume = self._create_data_volume(offering)
        self._assert_no_qos(volume)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_27_constrained_compute_fixed_iops(self):
        """Constrained CPU and memory deploy with the bound disk's fixed IOPS.

        CPU speed is saved on the offering. The deployer picks a CPU count
        and memory inside the offering's minimum and maximum.
        """
        root_disk = self._disk_offering(
            disksize=8, miniops=0, maxiops=570, zero_min=True
        )
        compute = self._constrained_compute(root_disk)
        self._assert_constrained_offering(
            compute, min_cpu=1, max_cpu=2, min_memory=512,
            max_memory=1024, cpu_speed=500,
        )
        vm = self._deploy(compute, details={
            "cpuNumber": "2",
            "memory": "1024",
        })
        self._assert_vm_compute(vm, cpu=2, speed=500, memory=1024)
        self._assert_qos(self._root_volume(vm), 0, 570)

    @attr(tags=["ontap_qos"], required_hardware=True)
    def test_28_constrained_compute_custom_iops(self):
        """Constrained CPU and memory deploy with IOPS chosen at deploy.

        CPU speed stays the value saved on the offering. The deployer picks
        a CPU count and memory inside the range, and the root disk's IOPS.
        """
        root_disk = self._disk_offering(disksize=8, custom_iops=True)
        compute = self._constrained_compute(root_disk)
        self._assert_constrained_offering(
            compute, min_cpu=1, max_cpu=2, min_memory=512,
            max_memory=1024, cpu_speed=500,
        )
        vm = self._deploy(compute, details={
            "cpuNumber": "1",
            "memory": "768",
            "minIops": "0",
            "maxIops": "580",
        })
        self._assert_vm_compute(vm, cpu=1, speed=500, memory=768)
        self._assert_qos(self._root_volume(vm), 0, 580)


def unittest_skip(reason):
    import unittest
    raise unittest.SkipTest(reason)


def _b64(value):
    import base64
    return base64.b64encode(value.encode()).decode()


def _vm_cmd(vm_id):
    from marvin.cloudstackAPI import listVirtualMachines as listVirtualMachinesAPI
    cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
    cmd.id = vm_id
    cmd.listall = True
    return cmd
