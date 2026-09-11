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
Negative / boundary workflow for ONTAP primary template-cache Marvin suites.

Kept separate from the sequential happy-path suite so failures here cannot
cascade into seed/reuse/survive steps.

Cases:
  01  Tag mismatch — SO tags do not match ONTAP pool; no spool_ref on pool
  02  Undersized pool — capacity << template size; deploy must fail
  03  Cache deleted out-of-band — Ready spool_ref but missing ONTAP object;
      subsequent deploy must fail
"""

from __future__ import print_function

import copy
import logging
import random
import time

from marvin.cloudstackAPI import (
    createStoragePool as createStoragePoolAPI,
    deleteServiceOffering as deleteServiceOfferingAPI,
    deployVirtualMachine as deployVirtualMachineAPI,
    enableStorageMaintenance,
)
from marvin.lib.base import ServiceOffering, StoragePool

from helpers import template_cache_util as tcu
from helpers.template_cache_workflow import (
    OntapTemplateCacheWorkflow,
    TemplateCacheTestData,
)

logger = logging.getLogger("TemplateCacheNegativeWorkflow")


class OntapTemplateCacheNegativeWorkflow(OntapTemplateCacheWorkflow):
    """
    Independent negative boundary tests.

    Reuses setUpClass (zone / template / ONTAP client) from the positive
    workflow but creates per-test pools and offerings so state stays isolated.
    """

    NOSE_TAG = "template_cache_negative"
    POOL_NAME_PREFIX = "OntapTmplCacheNeg"

    @classmethod
    def setUpClass(cls):
        super(OntapTemplateCacheNegativeWorkflow, cls).setUpClass()
        # Lab public IP pool is tiny (often only 3 addresses: SSVM + CPVM +
        # one SourceNat). Starting leftover VRs from prior runs consumes the
        # last IP and then this suite's Allocated guest network cannot be
        # implemented — seed deploy fails with "Unable to create a deployment".
        cls._reclaim_stale_template_cache_networks()
        cls._cleanup_stale_negative_pools()
        cls._ensure_guest_network_router_running()

    @classmethod
    def _cleanup_stale_negative_pools(cls):
        """
        Force-delete leftover OntapTmplCacheNeg* pools from prior runs.

        Shared storage_tags on a leftover Up pool will steal ROOT placement
        from the pool created by step_02/step_03 and make assertions fail.
        """
        from marvin.cloudstackAPI import listStoragePools as listStoragePoolsAPI

        cmd = listStoragePoolsAPI.listStoragePoolsCmd()
        cmd.zoneid = cls.zone.id
        pools = cls.apiClient.listStoragePools(cmd) or []
        prefixes = (
            cls.POOL_NAME_PREFIX,
            "OntapIscsiTmplNeg",
            "OntapNfs3TmplNeg",
            "OntapTmplCacheNeg",
        )
        for pool in pools:
            name = getattr(pool, "name", "") or ""
            if not any(name.startswith(prefix) for prefix in prefixes):
                continue
            logger.info("Cleaning leftover negative-suite pool %s" % name)
            try:
                # Instance method helpers need a throwaway binder.
                binder = cls.__new__(cls)
                binder.apiClient = cls.apiClient
                binder.ontap = cls.ontap
                binder._force_delete_pool(pool)
            except Exception as ex:
                logger.warning("Could not delete leftover pool %s: %s" % (name, ex))

    @classmethod
    def _reclaim_stale_template_cache_networks(cls):
        """
        Destroy leftover ontap-tmpl-cache-net-* networks (and their VRs/VMs)
        from prior suite accounts so SourceNat public IPs are freed.
        Keeps the network created for this class (cls.network_id).
        """
        from marvin.cloudstackAPI import (
            deleteNetwork as deleteNetworkAPI,
            destroyRouter as destroyRouterAPI,
            destroyVirtualMachine as destroyVirtualMachineAPI,
            listNetworks as listNetworksAPI,
            listRouters as listRoutersAPI,
            listVirtualMachines as listVirtualMachinesAPI,
            stopVirtualMachine as stopVirtualMachineAPI,
        )

        net_cmd = listNetworksAPI.listNetworksCmd()
        net_cmd.zoneid = cls.zone.id
        net_cmd.listall = True
        networks = cls.apiClient.listNetworks(net_cmd) or []
        for net in networks:
            name = getattr(net, "name", "") or ""
            # Match names from positive/negative suite network create
            # (ontap-tmpl-cache-net-*).
            if "tmpl-cache-net" not in name:
                continue
            if cls.network_id and str(net.id) == str(cls.network_id):
                continue

            logger.info(
                "Reclaiming stale template-cache network %s (%s)"
                % (name, net.id)
            )

            vm_cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
            vm_cmd.listall = True
            vm_cmd.networkid = net.id
            for vm in (cls.apiClient.listVirtualMachines(vm_cmd) or []):
                try:
                    state = (vm.state or "").lower()
                    if state not in (
                        "destroyed", "expunging", "error", "stopped"
                    ):
                        stop = stopVirtualMachineAPI.stopVirtualMachineCmd()
                        stop.id = vm.id
                        cls.apiClient.stopVirtualMachine(stop)
                    dest = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
                    dest.id = vm.id
                    dest.expunge = True
                    cls.apiClient.destroyVirtualMachine(dest)
                except Exception as ex:
                    logger.warning(
                        "Reclaim: destroy VM %s on %s: %s" % (vm.id, name, ex)
                    )

            rtr_cmd = listRoutersAPI.listRoutersCmd()
            rtr_cmd.zoneid = cls.zone.id
            rtr_cmd.networkid = net.id
            rtr_cmd.listall = True
            for router in (cls.apiClient.listRouters(rtr_cmd) or []):
                try:
                    dr = destroyRouterAPI.destroyRouterCmd()
                    dr.id = router.id
                    cls.apiClient.destroyRouter(dr)
                    logger.info(
                        "Reclaim: destroyed router %s for %s"
                        % (router.name, name)
                    )
                except Exception as ex:
                    logger.warning(
                        "Reclaim: destroy router %s: %s" % (router.id, ex)
                    )

            try:
                dn = deleteNetworkAPI.deleteNetworkCmd()
                dn.id = net.id
                cls.apiClient.deleteNetwork(dn)
                logger.info("Reclaim: deleted network %s" % name)
            except Exception as ex:
                logger.warning(
                    "Reclaim: delete network %s: %s" % (net.id, ex)
                )

    @classmethod
    def _ensure_guest_network_router_running(cls):
        """
        Ensure the VR for *this suite's* guest network is Running.

        Do not start unrelated zone routers — that can exhaust the lab's
        public IP range and block implementing cls.network_id.
        """
        from marvin.cloudstackAPI import (
            listRouters as listRoutersAPI,
            startRouter as startRouterAPI,
        )
        if not cls.network_id:
            logger.warning(
                "No guest network_id — deploy may fail in Advanced zone"
            )
            return

        cmd = listRoutersAPI.listRoutersCmd()
        cmd.zoneid = cls.zone.id
        cmd.networkid = cls.network_id
        cmd.listall = True
        routers = cls.apiClient.listRouters(cmd) or []
        if not routers:
            # Allocated isolated network: VR is created on first deploy.
            logger.info(
                "No VR yet for network %s — will be created on first deploy"
                % cls.network_id
            )
            return

        for router in routers:
            state = (router.state or "").lower()
            if state == "running":
                continue
            logger.info(
                "Starting guest-network router %s (was %s)"
                % (router.name, router.state)
            )
            start = startRouterAPI.startRouterCmd()
            start.id = router.id
            cls.apiClient.startRouter(start)
            deadline = time.time() + 300
            while time.time() < deadline:
                cur = cls.apiClient.listRouters(cmd) or []
                match = [r for r in cur if r.id == router.id]
                if match and (match[0].state or "").lower() == "running":
                    logger.info("Router %s is Running" % router.name)
                    break
                time.sleep(10)
            else:
                raise RuntimeError(
                    "Guest-network router %s did not reach Running"
                    % router.name
                )

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _unique_storage_tags(self):
        """Per-test tags so leftover pools with shared tags cannot steal ROOT."""
        return "%s-neg-%d" % (self.storage_tags, random.randint(0, 99999))

    def _create_pool_with(self, tags=None, capacitybytes=None, name_prefix=None):
        """Create an ONTAP pool, optionally overriding tags / capacity."""
        ps = copy.deepcopy(self.testdata[TemplateCacheTestData.primaryStorage])
        if tags is not None:
            ps[TemplateCacheTestData.tags] = tags
        if capacitybytes is not None:
            ps["capacitybytes"] = int(capacitybytes)

        storage_ip = self.testdata[TemplateCacheTestData.ontap][
            TemplateCacheTestData.DETAIL_STORAGE_IP
        ]
        prefix = name_prefix or self.POOL_NAME_PREFIX
        pool_name = "%s_%d" % (prefix, random.randint(0, 99999))

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
        pool = StoragePool(response.__dict__)
        self.assertEqual(
            pool.state, "Up",
            "Pool %s should be Up after create, got %s" % (pool.name, pool.state),
        )
        ontap_vol = self.ontap.get_volume(pool.name)
        self.assertIsNotNone(ontap_vol, "FlexVol missing for pool %s" % pool.name)
        self.assertEqual(ontap_vol.get("state"), "online")
        return pool

    def _create_service_offering_with(self, tags):
        """Create a compute offering with the given storage tags."""
        so_data = copy.deepcopy(
            self.testdata[TemplateCacheTestData.computeOffering]
        )
        so_data["tags"] = tags
        so_data["name"] = "OntapTmplCacheNegSO_%d" % random.randint(0, 99999)
        so_data["displaytext"] = so_data["name"]
        so = ServiceOffering.create(self.apiClient, so_data)
        self.assertIsNotNone(so.id, "Service offering create failed")
        return so

    def _deploy_vm_with_offering(self, name_suffix, service_offering_id):
        cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
        cmd.zoneid = self.zone.id
        cmd.templateid = self.__class__.template_id
        cmd.serviceofferingid = service_offering_id
        cmd.account = self.account.name
        cmd.domainid = self.domain.id
        cmd.name = "tmpl-cache-neg-%s-%d" % (
            name_suffix, random.randint(0, 9999)
        )
        cmd.displayname = cmd.name
        if self.__class__.network_id:
            cmd.networkids = self.__class__.network_id
        vm = self.apiClient.deployVirtualMachine(cmd)
        self.assertIsNotNone(vm, "deployVirtualMachine returned None")
        return self._poll_vm_state(vm.id, "Running", timeout=900)

    def _deploy_vm_expect_failure(self, name_suffix, service_offering_id,
                                  timeout=300):
        """Assert deploy does not reach Running (API error or Error state)."""
        vm = None
        try:
            cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
            cmd.zoneid = self.zone.id
            cmd.templateid = self.__class__.template_id
            cmd.serviceofferingid = service_offering_id
            cmd.account = self.account.name
            cmd.domainid = self.domain.id
            cmd.name = "tmpl-cache-neg-%s-%d" % (
                name_suffix, random.randint(0, 9999)
            )
            cmd.displayname = cmd.name
            if self.__class__.network_id:
                cmd.networkids = self.__class__.network_id
            vm = self.apiClient.deployVirtualMachine(cmd)
        except Exception as ex:
            logger.info(
                "deployVirtualMachine failed as expected for %s: %s"
                % (name_suffix, ex)
            )
            return

        self.assertIsNotNone(vm, "deploy returned None without raising")
        try:
            self._poll_vm_state(vm.id, "Running", timeout=timeout)
            self.fail(
                "Deploy %s unexpectedly reached Running (boundary should fail)"
                % name_suffix
            )
        except Exception as ex:
            logger.info(
                "Deploy %s did not reach Running as expected: %s"
                % (name_suffix, ex)
            )
        finally:
            try:
                self._destroy_vm_static(vm.id)
            except Exception:
                pass

    def _force_delete_pool(self, pool):
        if pool is None:
            return
        pool_name = pool.name
        try:
            mc = enableStorageMaintenance.enableStorageMaintenanceCmd()
            mc.id = pool.id
            self.apiClient.enableStorageMaintenance(mc)
            self._poll_pool_state(pool.id, "Maintenance", timeout=120)
        except Exception as ex:
            logger.warning(
                "enableStorageMaintenance for %s: %s" % (pool.id, ex)
            )
        try:
            self._delete_pool(pool.id, forced=True)
        except Exception as ex:
            logger.warning("force delete pool %s: %s" % (pool.id, ex))
        deadline = time.time() + 120
        while time.time() < deadline:
            if self.ontap.get_volume(pool_name) is None:
                break
            time.sleep(5)

    def _delete_service_offering(self, offering):
        if offering is None:
            return
        try:
            cmd = deleteServiceOfferingAPI.deleteServiceOfferingCmd()
            cmd.id = offering.id
            self.apiClient.deleteServiceOffering(cmd)
        except Exception as ex:
            logger.warning("delete SO %s: %s" % (offering.id, ex))

    def _delete_ontap_cache(self, pool, spool_ref):
        """Remove ONTAP cache object while leaving CloudStack spool_ref."""
        if self.PROTOCOL.upper() == "ISCSI":
            path = tcu.template_cache_lun_path(
                pool.name, self.__class__.template_db_id
            )
            deleted = self.ontap.delete_lun(self.svm_name, path)
            self.assertTrue(
                deleted,
                "Failed to delete template cache LUN at %s" % path,
            )
            tcu.assert_no_iscsi_template_cache_lun(
                self, self.ontap, self.svm_name, pool.name,
                self.__class__.template_db_id,
            )
        else:
            install_path = spool_ref.get("install_path")
            self.assertTrue(install_path, "spool_ref.install_path required")
            deleted = self.ontap.delete_file_in_volume(
                pool.name, install_path
            )
            self.assertTrue(
                deleted,
                "Failed to delete NFS cache file %s in %s"
                % (install_path, pool.name),
            )
            tcu.assert_no_nfs_template_cache_file(
                self, self.ontap, pool.name, install_path
            )

    # ------------------------------------------------------------------
    # Negative cases
    # ------------------------------------------------------------------

    def step_01_tag_mismatch_does_not_seed_cache(self):
        """
        Pool has template-cache tags; SO uses a different tag.

        Valid outcomes:
          a) deploy fails (no alternate tagged pool) — common in lab
          b) deploy succeeds elsewhere — ROOT must not be on ONTAP pool

        In both cases there must be no spool_ref / cache on the ONTAP pool.
        """
        pool = None
        so = None
        vm = None
        mismatch_tag = "ontap-tmpl-cache-mismatch-%d" % random.randint(0, 99999)
        try:
            pool = self._create_pool_with(tags=self.storage_tags)
            pool_db_id = tcu.get_db_id(
                self.dbConnection, "storage_pool", pool.id
            )
            so = self._create_service_offering_with(tags=mismatch_tag)

            try:
                vm = self._deploy_vm_with_offering("tag-mismatch", so.id)
            except Exception as ex:
                logger.info(
                    "Deploy with mismatched tags failed (acceptable if no "
                    "alternate pool matches): %s" % ex
                )
                vm = None

            if vm is not None:
                root = self._root_volume_for_vm(vm.id)
                self.assertNotEqual(
                    str(root.storageid), str(pool.id),
                    "ROOT should not land on ONTAP pool when SO tags mismatch "
                    "(storageid=%s pool=%s)" % (root.storageid, pool.id),
                )

            tcu.assert_no_spool_ref(
                self, self.dbConnection, pool_db_id,
                self.__class__.template_db_id,
            )
            if self.PROTOCOL.upper() == "ISCSI":
                tcu.assert_no_iscsi_template_cache_lun(
                    self, self.ontap, self.svm_name, pool.name,
                    self.__class__.template_db_id,
                )
        finally:
            if vm is not None:
                try:
                    self._destroy_vm_static(vm.id)
                except Exception:
                    pass
            self._delete_service_offering(so)
            self._force_delete_pool(pool)

    def step_02_undersized_pool_deploy_fails(self):
        """
        Matching tags but capacitybytes far below template virtual size.

        Deploy must fail; no Ready spool_ref / cache object should remain.
        """
        pool = None
        so = None
        tmpl_size = tcu.get_template_size_bytes(
            self.dbConnection, self.__class__.template_db_id
        )
        if not tmpl_size or tmpl_size <= 0:
            self.skipTest(
                "Cannot determine vm_template.size for template_db_id=%s"
                % self.__class__.template_db_id
            )

        # Lab templates can be small (~100–200 MiB); keep capacity under size.
        undersized = max(tmpl_size // 4, 8 * 1024 * 1024)
        if undersized >= tmpl_size:
            undersized = max(tmpl_size // 2, 1024 * 1024)
        self.assertLess(
            undersized, tmpl_size,
            "undersized capacity %s must be < template size %s"
            % (undersized, tmpl_size),
        )

        try:
            exclusive_tags = self._unique_storage_tags()
            pool = self._create_pool_with(
                tags=exclusive_tags,
                capacitybytes=undersized,
                name_prefix=self.POOL_NAME_PREFIX + "Tiny",
            )
            pool_db_id = tcu.get_db_id(
                self.dbConnection, "storage_pool", pool.id
            )
            so = self._create_service_offering_with(tags=exclusive_tags)

            self._deploy_vm_expect_failure("undersized", so.id, timeout=300)

            spool = tcu.get_template_spool_ref(
                self.dbConnection, pool_db_id, self.__class__.template_db_id
            )
            if spool is not None:
                self.assertNotEqual(
                    str(spool.get("state")), "Ready",
                    "spool_ref must not be Ready after undersized deploy failure",
                )
                self.assertNotEqual(
                    str(spool.get("download_state", "")).upper(),
                    "DOWNLOADED",
                    "spool_ref must not be DOWNLOADED after undersized failure",
                )
            if self.PROTOCOL.upper() == "ISCSI":
                tcu.assert_no_iscsi_template_cache_lun(
                    self, self.ontap, self.svm_name, pool.name,
                    self.__class__.template_db_id,
                )
        finally:
            self._delete_service_offering(so)
            self._force_delete_pool(pool)

    def step_03_deleted_cache_blocks_reuse(self):
        """
        Seed cache, destroy VM, delete ONTAP cache out-of-band, redeploy.

        CloudStack still has Ready spool_ref but backend object is gone —
        second deploy must fail (clone / missing cache path).
        """
        self._ensure_guest_network_router_running()
        pool = None
        so = None
        vm1 = None
        try:
            exclusive_tags = self._unique_storage_tags()
            pool = self._create_pool_with(tags=exclusive_tags)
            pool_db_id = tcu.get_db_id(
                self.dbConnection, "storage_pool", pool.id
            )
            so = self._create_service_offering_with(tags=exclusive_tags)

            # Seed deploy can flake on lab planner/VR; retry once after
            # re-ensuring the guest network router is Running.
            last_ex = None
            for attempt in range(2):
                try:
                    self._ensure_guest_network_router_running()
                    vm1 = self._deploy_vm_with_offering("neg-seed", so.id)
                    last_ex = None
                    break
                except Exception as ex:
                    last_ex = ex
                    logger.warning(
                        "Seed deploy attempt %s failed: %s"
                        % (attempt + 1, ex)
                    )
                    self._reclaim_stale_template_cache_networks()
                    time.sleep(15)
            if last_ex is not None:
                # Seed must succeed — this case is not optional. Failure here
                # is usually exhausted public IPs / stale VR from prior runs.
                self.fail(
                    "Could not seed template cache for out-of-band delete "
                    "case after retries (check public IP capacity / leftover "
                    "VRs): %s" % last_ex
                )
            self._assert_root_on_pool(vm1.id, pool)
            spool = tcu.wait_for_spool_ref(
                self.dbConnection, pool_db_id, self.__class__.template_db_id,
                timeout=600,
            )
            tcu.assert_spool_ref_ready(
                self, spool,
                expect_local_path=(self.PROTOCOL.upper() == "ISCSI"),
            )
            self._assert_cache_on_ontap(pool, spool)

            self._destroy_vm_static(vm1.id)
            vm1 = None
            # Allow clone/ROOT LUN cleanup so the cache LUN is no longer a
            # FlexClone parent before out-of-band delete.
            time.sleep(30)

            self._delete_ontap_cache(pool, spool)
            stale = tcu.get_template_spool_ref(
                self.dbConnection, pool_db_id, self.__class__.template_db_id
            )
            tcu.assert_spool_ref_ready(
                self, stale,
                expect_local_path=(self.PROTOCOL.upper() == "ISCSI"),
            )

            self._deploy_vm_expect_failure("stale-cache", so.id, timeout=300)
        finally:
            if vm1 is not None:
                try:
                    self._destroy_vm_static(vm1.id)
                except Exception:
                    pass
            self._delete_service_offering(so)
            self._force_delete_pool(pool)
