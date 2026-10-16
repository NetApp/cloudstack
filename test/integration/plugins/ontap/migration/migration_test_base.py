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

"""Shared infrastructure for the ordered ONTAP migration Marvin suites.

Before test methods run, the base inventories the configured zone and prepares
the ONTAP pool scopes and cluster attachments requested by each suite.
"""

import base64
import logging
import os
import random
import re
import shlex
import time
import unittest

from marvin.cloudstackAPI import (
    addHost as addHostAPI,
    attachVolume as attachVolumeAPI,
    createNetwork as createNetworkAPI,
    createStoragePool as createStoragePoolAPI,
    createVolume as createVolumeAPI,
    deleteHost as deleteHostAPI,
    deleteNetwork as deleteNetworkAPI,
    deleteVolume as deleteVolumeAPI,
    deployVirtualMachine as deployVirtualMachineAPI,
    destroyRouter as destroyRouterAPI,
    destroySystemVm as destroySystemVmAPI,
    destroyVirtualMachine as destroyVirtualMachineAPI,
    listClusters as listClustersAPI,
    listNetworkOfferings as listNetworkOfferingsAPI,
    listNetworks as listNetworksAPI,
    listRouters as listRoutersAPI,
    listSystemVms as listSystemVmsAPI,
    listVirtualMachines as listVirtualMachinesAPI,
    listVolumes as listVolumesAPI,
    migrateVirtualMachine as migrateVirtualMachineAPI,
    migrateVirtualMachineWithVolume as migrateVMWithVolumeAPI,
    migrateVolume as migrateVolumeAPI,
    startSystemVm as startSystemVmAPI,
    startVirtualMachine as startVirtualMachineAPI,
    stopSystemVm as stopSystemVmAPI,
    stopVirtualMachine as stopVirtualMachineAPI,
)
from marvin.lib.base import DiskOffering, Host, ServiceOffering, StoragePool
from marvin.lib.common import list_storage_pools
from marvin.sshClient import SshClient

from ontap_test_base import (
    OntapRestClient,
    OntapTestBase,
    _parse_pool_details,
    get_datacenter_config,
    get_ready_hosts,
    is_configured,
    list_kvm_templates,
)

logger = logging.getLogger("OntapMigrationTestBase")
DISK_SOURCE_RE = re.compile(
    r"""<source\s[^>]*(?:file|dev)=['"]([^'"]+)['"]""",
    re.IGNORECASE,
)


class OntapMigrationTestBase(OntapTestBase):
    """Common setup, resource creation, assertions, and force cleanup."""

    config_data = None
    template_id = None
    network_id = None
    _created_network_id = None
    ready_hosts = None
    created_pools = None
    created_volumes = None
    created_vms = None
    service_offerings = None
    disk_offerings = None
    default_pool = None
    ontap_pools = None
    hosts_by_cluster = None
    secondary_cluster_id = None

    @classmethod
    def setUpClass(cls):
        super(OntapMigrationTestBase, cls).setUpClass()
        testclient = super(
            OntapMigrationTestBase, cls
        ).getClsTestClient()
        cls.apiClient = testclient.getApiClient()
        cls.dbConnection = testclient.getDbConnection()
        cls.config_data = get_datacenter_config(testclient, cls)

        ontap_cfg = cls.config_data.get("ontap", {})
        cls.ontap = OntapRestClient(
            ontap_cfg.get("storageIP", ""),
            ontap_cfg.get("username", ""),
            ontap_cfg.get("password", ""),
        )
        cls.svm_name = ontap_cfg.get("svmName", "")
        account_data = {
            "email": "ontap-migration@test.invalid",
            "firstname": "ONTAP",
            "lastname": "Migration",
            "username": "ontap_migration_%d" % random.randint(0, 999999),
            "password": "password",
        }
        cls._setup_cloudstack_resources(cls.config_data, account_data)
        configured_cluster = cls.config_data.get(
            "cloudstack", {}
        ).get("clusterName")
        if (configured_cluster
                and cls.cluster.name != configured_cluster):
            raise RuntimeError(
                "Configured primary cluster %s resolved to %s"
                % (configured_cluster, cls.cluster.name)
            )
        host_requirements = cls._host_requirements()
        cls._configure_host_states(host_requirements["minimum_hosts"])
        cls._ensure_host_topology(host_requirements)
        _, cls.ready_hosts = cls._validate_migration_prerequisites(
            cls.config_data, host_requirements["minimum_hosts"]
        )
        cls.hosts_by_cluster = {}
        for host in cls.ready_hosts:
            cls.hosts_by_cluster.setdefault(host.clusterid, []).append(host)
        cls._validate_host_topology(host_requirements)
        cls._validate_host_credentials()
        if host_requirements.get("live_migration"):
            cls._prepare_live_migration_hosts()
        other_cluster_ids = [
            cluster_id for cluster_id in cls.hosts_by_cluster
            if cluster_id != cls.cluster.id
        ]
        cls.secondary_cluster_id = (
            other_cluster_ids[0] if other_cluster_ids else None
        )

        cls.created_pools = []
        cls.created_volumes = []
        cls.created_vms = []
        cls.service_offerings = {}
        cls.disk_offerings = {}
        try:
            cls.default_pool = cls._find_default_primary_pool()
            cls.ontap_pools = cls._ensure_migration_pools()
            logger.info(
                "Migration pool matrix: DefaultPrimary=%s, NFS3=%s, ISCSI=%s",
                cls.default_pool.name,
                {
                    key: [pool.name for pool in pools]
                    for key, pools in cls.ontap_pools["NFS3"].items()
                },
                {
                    key: [pool.name for pool in pools]
                    for key, pools in cls.ontap_pools["ISCSI"].items()
                },
            )
            cls.template_id = cls._find_template()
            cls.network_id = cls._find_or_create_network()
        except Exception:
            cls.tearDownClass()
            raise

    @classmethod
    def _host_requirements(cls):
        return {
            "minimum_hosts": 1,
            "same_primary_cluster": False,
            "multiple_clusters": False,
            "live_migration": False,
        }

    @classmethod
    def _configure_host_states(cls, minimum_hosts):
        hosts = Host.list(
            cls.apiClient,
            zoneid=cls.zone.id,
            type="Routing",
            hypervisor="KVM",
        ) or []
        for host in hosts:
            resource_state = str(
                getattr(host, "resourcestate", "")
            ).lower()
            if resource_state in ("maintenance", "errorinmaintenance"):
                Host.cancelMaintenance(cls.apiClient, host.id)
            elif resource_state != "enabled":
                Host.update(
                    cls.apiClient,
                    id=host.id,
                    resourcestate="Enabled",
                )
            host_state = str(getattr(host, "state", "")).lower()
            if host_state in ("alert", "disconnected", "down"):
                try:
                    Host.reconnect(cls.apiClient, id=host.id)
                except Exception as error:
                    logger.warning(
                        "Host %s reconnect was not accepted: %s",
                        host.id, error,
                    )

        for attempt in range(12):
            current = Host.list(
                cls.apiClient,
                zoneid=cls.zone.id,
                type="Routing",
                hypervisor="KVM",
            ) or []
            if len(get_ready_hosts(current)) >= minimum_hosts:
                return
            if attempt < 11:
                time.sleep(5)
        raise RuntimeError("Configured KVM hosts did not reach Up/Enabled")

    @classmethod
    def _ready_hosts_by_cluster(cls):
        hosts = get_ready_hosts(Host.list(
            cls.apiClient,
            zoneid=cls.zone.id,
            type="Routing",
            hypervisor="KVM",
        ) or [])
        grouped = {}
        for host in hosts:
            grouped.setdefault(host.clusterid, []).append(host)
        return grouped

    @classmethod
    def _ensure_host_topology(cls, requirements):
        """Move a KVM host between clusters when the suite needs it."""
        if requirements["same_primary_cluster"]:
            cls._ensure_hosts_in_primary_cluster(2)
        if requirements["multiple_clusters"]:
            cls._ensure_hosts_across_clusters()

    @classmethod
    def _ensure_hosts_in_primary_cluster(cls, minimum_hosts):
        grouped = cls._ready_hosts_by_cluster()
        while len(grouped.get(cls.cluster.id, [])) < minimum_hosts:
            donor = next(
                (hosts[0] for cluster_id, hosts in grouped.items()
                 if cluster_id != cls.cluster.id and hosts),
                None,
            )
            if donor is None:
                return
            before = len(grouped.get(cls.cluster.id, []))
            cls._relocate_host(donor, cls.cluster.id)
            grouped = cls._ready_hosts_by_cluster()
            if len(grouped.get(cls.cluster.id, [])) <= before:
                logger.warning(
                    "Could not move host %s into the primary cluster; "
                    "continuing with %s host(s) there",
                    donor.name, before,
                )
                return

    @classmethod
    def _ensure_hosts_across_clusters(cls):
        grouped = cls._ready_hosts_by_cluster()
        if len(grouped) >= 2:
            return
        donors = grouped.get(cls.cluster.id, [])
        target_cluster_id = cls._find_alternate_cluster()
        if len(donors) < 2 or target_cluster_id is None:
            return
        cls._relocate_host(donors[-1], target_cluster_id)

    @classmethod
    def _refresh_host_inventory(cls):
        cls.ready_hosts = get_ready_hosts(Host.list(
            cls.apiClient,
            zoneid=cls.zone.id,
            type="Routing",
            hypervisor="KVM",
        ) or [])
        cls.hosts_by_cluster = {}
        for host in cls.ready_hosts:
            cls.hosts_by_cluster.setdefault(host.clusterid, []).append(host)
        other_cluster_ids = [
            cluster_id for cluster_id in cls.hosts_by_cluster
            if cluster_id != cls.cluster.id
        ]
        cls.secondary_cluster_id = (
            other_cluster_ids[0] if other_cluster_ids else None
        )

    @classmethod
    def _find_alternate_cluster(cls):
        cmd = listClustersAPI.listClustersCmd()
        cmd.zoneid = cls.zone.id
        cmd.hypervisor = "KVM"
        clusters = cls.apiClient.listClusters(cmd) or []
        configured_names = []
        for zone in cls.config_data.get("zones", []):
            if zone.get("name") != cls.zone.name:
                continue
            for pod in zone.get("pods", []):
                configured_names.extend(
                    cluster.get("clustername")
                    for cluster in pod.get("clusters", [])
                    if cluster.get("clustername") != cls.cluster.name
                )
        for name in configured_names:
            configured = next(
                (cluster for cluster in clusters if cluster.name == name),
                None,
            )
            if configured is not None:
                return configured.id
        return next(
            (cluster.id for cluster in clusters
             if cluster.id != cls.cluster.id),
            None,
        )

    @classmethod
    def _host_credentials(cls, host):
        """Return the ontap.cfg host entry that matches a listed host."""
        identifiers = {
            str(getattr(host, attribute, "") or "")
            for attribute in ("ipaddress", "name")
        }
        identifiers.discard("")
        for zone in cls.config_data.get("zones", []):
            for pod in zone.get("pods", []):
                for cluster in pod.get("clusters", []):
                    for entry in cluster.get("hosts", []):
                        endpoint = str(
                            entry.get("url", "")
                        ).rsplit("/", 1)[-1]
                        if endpoint and endpoint in identifiers:
                            return entry
        return None

    @classmethod
    def _relocate_host(cls, host, target_cluster_id):
        """Remove a KVM host from its cluster and re-add it to another."""
        credentials = cls._host_credentials(host)
        if credentials is None:
            logger.warning(
                "No configured credentials for host %s; leaving it in "
                "cluster %s", host.name, host.clusterid,
            )
            return
        if not cls._wait_for_kvm_agent_listen(credentials):
            logger.warning(
                "KVM agent on %s is not listening; leaving host in cluster %s",
                host.name, host.clusterid,
            )
            return
        logger.info(
            "Relocating host %s from cluster %s to cluster %s",
            host.name, host.clusterid, target_cluster_id,
        )
        original_cluster_id = host.clusterid
        pod_id = host.podid
        stopped_system_vms = []
        try:
            still_busy = cls._force_clear_host_workloads(host.id)
            if still_busy:
                logger.warning(
                    "Host %s still has Starting/Stopping VMs; leaving it in "
                    "cluster %s",
                    host.name, host.clusterid,
                )
                return
            stopped_system_vms = cls._stop_system_vms_on_host(host.id)
            cls._prepare_host_for_relocation(host.id)
        except Exception as exc:
            logger.warning(
                "Could not prepare host %s for relocation; leaving it in "
                "cluster %s: %s",
                host.name, host.clusterid, exc,
            )
            cls._start_system_vms(stopped_system_vms)
            return

        if not cls._stop_kvm_agent(credentials):
            logger.warning(
                "Could not stop the KVM agent on %s before relocation; "
                "leaving it in cluster %s",
                host.name, host.clusterid,
            )
            cls._start_system_vms(stopped_system_vms)
            return

        delete_cmd = deleteHostAPI.deleteHostCmd()
        delete_cmd.id = host.id
        delete_cmd.forced = True
        delete_cmd.forcedestroylocalstorage = True
        cls.apiClient.deleteHost(delete_cmd)

        added = cls._add_kvm_host(credentials, pod_id, target_cluster_id)
        if added is None:
            logger.warning(
                "addHost of %s to cluster %s failed; restoring cluster %s",
                host.name, target_cluster_id, original_cluster_id,
            )
            added = cls._add_kvm_host(
                credentials, pod_id, original_cluster_id
            )
            if added is None:
                cls._start_system_vms(stopped_system_vms)
                return
        cls._wait_for_host_up(added[0].id)
        cls._ensure_libvirt_tcp_listen(credentials)
        cls._wait_for_host_up(added[0].id)
        cls._start_system_vms(stopped_system_vms)

    @classmethod
    def _add_kvm_host(cls, credentials, pod_id, cluster_id):
        add_cmd = addHostAPI.addHostCmd()
        add_cmd.zoneid = cls.zone.id
        add_cmd.podid = pod_id
        add_cmd.clusterid = cluster_id
        add_cmd.hypervisor = "KVM"
        add_cmd.url = credentials["url"]
        add_cmd.username = credentials["username"]
        add_cmd.password = credentials["password"]
        if credentials.get("hosttags"):
            add_cmd.hosttags = credentials["hosttags"]
        try:
            return cls.apiClient.addHost(add_cmd)
        except Exception as exc:
            logger.warning(
                "addHost %s to cluster %s failed: %s",
                credentials.get("url"), cluster_id, exc,
            )
            return None

    @classmethod
    def _wait_for_kvm_agent_listen(cls, credentials, timeout=15):
        """Return whether the KVM agent is reachable for addHost."""
        return cls._kvm_agent_listening(credentials)

    @classmethod
    def _kvm_agent_listening(cls, credentials):
        endpoint = str(credentials.get("url", "")).rsplit("/", 1)[-1]
        if not endpoint:
            return False
        try:
            ssh = SshClient(
                endpoint, 22,
                credentials.get("username", "root"),
                credentials.get("password", ""),
                retries=2, delay=2, timeout=10.0,
            )
            ssh.execute("mkdir -p /vmware-vix-disklib-distrib")
            output = ssh.execute(
                "pgrep -f com.cloud.agent.AgentShell >/dev/null "
                "&& echo AGENT_OK || true"
            )
            text = " ".join(str(line) for line in (output or []))
            return "AGENT_OK" in text
        except Exception as exc:
            logger.warning(
                "Could not check KVM agent on %s: %s", endpoint, exc
            )
            return False

    @classmethod
    def _stop_kvm_agent(cls, credentials):
        """Stop the old agent before addHost rewrites and restarts it."""
        endpoint = str(credentials.get("url", "")).rsplit("/", 1)[-1]
        if not endpoint:
            return False
        try:
            ssh = SshClient(
                endpoint, 22,
                credentials.get("username", "root"),
                credentials.get("password", ""),
                retries=3, delay=3, timeout=15.0,
            )
            ssh.execute("systemctl stop cloudstack-agent")
            output = ssh.execute(
                "pgrep -f '[c]om.cloud.agent.AgentShell' >/dev/null "
                "|| echo AGENT_STOPPED"
            )
            text = " ".join(str(line) for line in (output or []))
            return "AGENT_STOPPED" in text
        except Exception as exc:
            logger.warning(
                "Could not stop KVM agent on %s: %s", endpoint, exc
            )
            return False

    @classmethod
    def _ensure_libvirt_tcp_listen(cls, credentials):
        """Live migration needs TCP libvirtd on the destination host."""
        endpoint = str(credentials.get("url", "")).rsplit("/", 1)[-1]
        if not endpoint:
            return
        try:
            ssh = SshClient(
                endpoint, 22,
                credentials.get("username", "root"),
                credentials.get("password", ""),
                retries=3, delay=3, timeout=15.0,
            )
            ssh.execute(
                "sed -i 's/^listen_tls=.*/listen_tls=0/' "
                "/etc/libvirt/libvirtd.conf; "
                "sed -i 's/^listen_tcp=.*/listen_tcp=1/' "
                "/etc/libvirt/libvirtd.conf; "
                "systemctl restart libvirtd"
            )
        except Exception as exc:
            logger.warning(
                "Could not enable libvirt TCP listen on %s: %s",
                endpoint, exc,
            )

    @classmethod
    def _force_clear_host_workloads(cls, host_id):
        """Destroy stuck Starting VMs/routers so host maintenance can proceed."""
        list_cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
        list_cmd.hostid = host_id
        list_cmd.listall = True
        for vm in cls.apiClient.listVirtualMachines(list_cmd) or []:
            state = str(getattr(vm, "state", "")).lower()
            if state not in ("starting", "stopping", "error", "unknown"):
                continue
            logger.warning(
                "Destroying stuck %s VM %s on host %s before relocation",
                state, vm.id, host_id,
            )
            destroy_cmd = destroyVirtualMachineAPI.destroyVirtualMachineCmd()
            destroy_cmd.id = vm.id
            destroy_cmd.expunge = True
            try:
                cls.apiClient.destroyVirtualMachine(destroy_cmd)
            except Exception as exc:
                logger.warning(
                    "Could not destroy stuck VM %s: %s", vm.id, exc
                )
        router_cmd = listRoutersAPI.listRoutersCmd()
        router_cmd.hostid = host_id
        router_cmd.listall = True
        for router in cls.apiClient.listRouters(router_cmd) or []:
            state = str(getattr(router, "state", "")).lower()
            if state == "running":
                continue
            logger.warning(
                "Destroying stuck %s router %s on host %s before relocation",
                state, router.id, host_id,
            )
            destroy_cmd = destroyRouterAPI.destroyRouterCmd()
            destroy_cmd.id = router.id
            try:
                cls.apiClient.destroyRouter(destroy_cmd)
            except Exception as exc:
                logger.warning(
                    "Could not destroy stuck router %s: %s", router.id, exc
                )
        return cls._host_has_transitioning_vms(host_id)

    @classmethod
    def _host_has_transitioning_vms(cls, host_id):
        list_cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
        list_cmd.hostid = host_id
        list_cmd.listall = True
        for vm in cls.apiClient.listVirtualMachines(list_cmd) or []:
            if str(getattr(vm, "state", "")).lower() in (
                "starting", "stopping"
            ):
                return True
        router_cmd = listRoutersAPI.listRoutersCmd()
        router_cmd.hostid = host_id
        router_cmd.listall = True
        for router in cls.apiClient.listRouters(router_cmd) or []:
            if str(getattr(router, "state", "")).lower() in (
                "starting", "stopping"
            ):
                return True
        return False

    @classmethod
    def _stop_system_vms_on_host(cls, host_id):
        list_cmd = listSystemVmsAPI.listSystemVmsCmd()
        list_cmd.hostid = host_id
        system_vms = cls.apiClient.listSystemVms(list_cmd) or []
        running_ids = [
            system_vm.id for system_vm in system_vms
            if str(getattr(system_vm, "state", "")).lower() == "running"
        ]
        for system_vm_id in running_ids:
            logger.info(
                "Stopping system VM %s before relocating host %s",
                system_vm_id, host_id,
            )
            stop_cmd = stopSystemVmAPI.stopSystemVmCmd()
            stop_cmd.id = system_vm_id
            stop_cmd.forced = True
            cls.apiClient.stopSystemVm(stop_cmd)
        return running_ids

    @classmethod
    def _start_system_vms(cls, system_vm_ids):
        for system_vm_id in system_vm_ids:
            logger.info(
                "Restarting system VM %s after host relocation",
                system_vm_id,
            )
            start_cmd = startSystemVmAPI.startSystemVmCmd()
            start_cmd.id = system_vm_id
            try:
                cls.apiClient.startSystemVm(start_cmd)
            except Exception as error:
                logger.warning(
                    "System VM %s did not restart after host relocation; "
                    "destroying it so CloudStack can recreate it: %s",
                    system_vm_id, error,
                )
                destroy_cmd = destroySystemVmAPI.destroySystemVmCmd()
                destroy_cmd.id = system_vm_id
                try:
                    cls.apiClient.destroySystemVm(destroy_cmd)
                except Exception as destroy_error:
                    logger.warning(
                        "System VM %s could not be destroyed after its "
                        "restart failed; continuing because host relocation "
                        "already completed: %s",
                        system_vm_id, destroy_error,
                    )

    @classmethod
    def _prepare_host_for_relocation(cls, host_id):
        for attempt in range(6):
            try:
                Host.enableMaintenance(cls.apiClient, id=host_id)
                cls._wait_for_host_resource_state(
                    host_id, "maintenance", timeout=300
                )
                return
            except Exception as exc:
                hosts = Host.list(
                    cls.apiClient, id=host_id, listall=True
                ) or []
                state = str(
                    getattr(hosts[0], "resourcestate", "")
                    if hosts else ""
                ).lower()
                if (state == "errorinmaintenance"
                        and attempt < 5):
                    logger.warning(
                        "Host %s entered ErrorInMaintenance; cancelling and "
                        "retrying", host_id,
                    )
                    Host.cancelMaintenance(cls.apiClient, host_id)
                    cls._wait_for_host_resource_state(
                        host_id, "enabled", timeout=300
                    )
                    continue
                if ("starting/stopping state" in str(exc).lower()
                        and attempt < 5):
                    logger.warning(
                        "Host %s has a transitioning VM; retrying "
                        "maintenance", host_id,
                    )
                    time.sleep(15)
                    continue
                else:
                    raise

    @classmethod
    def _wait_for_host_resource_state(cls, host_id, resource_state,
                                      timeout=600):
        deadline = time.time() + timeout
        while time.time() < deadline:
            hosts = Host.list(cls.apiClient, id=host_id, listall=True) or []
            current_state = str(
                getattr(hosts[0], "resourcestate", "") if hosts else ""
            ).lower()
            if current_state == resource_state:
                return
            if (resource_state == "maintenance"
                    and current_state == "errorinmaintenance"):
                raise RuntimeError(
                    "Host %s entered ErrorInMaintenance" % host_id
                )
            time.sleep(10)
        raise RuntimeError(
            "Host %s did not reach resource state %s"
            % (host_id, resource_state)
        )

    @classmethod
    def _wait_for_host_up(cls, host_id, timeout=900):
        deadline = time.time() + timeout
        consecutive_ready_checks = 0
        while time.time() < deadline:
            hosts = Host.list(cls.apiClient, id=host_id, listall=True) or []
            host = hosts[0] if hosts else None
            if (host is not None
                    and str(getattr(host, "state", "")).lower() == "up"
                    and str(getattr(
                        host, "resourcestate", ""
                    )).lower() == "enabled"):
                consecutive_ready_checks += 1
                if consecutive_ready_checks >= 3:
                    return
            else:
                consecutive_ready_checks = 0
            time.sleep(5)
        raise RuntimeError("Relocated host %s did not come back Up" % host_id)

    @classmethod
    def _validate_host_topology(cls, requirements):
        if (requirements["same_primary_cluster"]
                and len(cls.hosts_by_cluster.get(cls.cluster.id, [])) < 2):
            raise unittest.SkipTest(
                "Suite requires two Up/Enabled KVM hosts in primary cluster "
                "%s; found %s"
                % (
                    cls.cluster.name,
                    len(cls.hosts_by_cluster.get(cls.cluster.id, [])),
                )
            )
        if (requirements["multiple_clusters"]
                and (
                    not cls.hosts_by_cluster.get(cls.cluster.id)
                    or len(cls.hosts_by_cluster) < 2
                )):
            raise unittest.SkipTest(
                "Suite requires an Up/Enabled KVM host in primary cluster %s "
                "and another configured cluster"
                % cls.cluster.name
            )

    @classmethod
    def _validate_host_credentials(cls):
        missing = [
            host.name for host in cls.ready_hosts
            if cls._host_credentials(host) is None
        ]
        if missing:
            raise RuntimeError(
                "ontap.cfg has no SSH credentials for KVM hosts: %s"
                % ", ".join(sorted(missing))
            )

    @classmethod
    def _ssh_output(cls, host, command):
        credentials = cls._host_credentials(host)
        if credentials is None:
            raise RuntimeError(
                "No configured SSH credentials for host %s" % host.name
            )
        endpoint = str(credentials.get("url", "")).rsplit("/", 1)[-1]
        try:
            ssh = SshClient(
                endpoint, 22,
                credentials.get("username", "root"),
                credentials.get("password", ""),
                retries=2, delay=2, timeout=15.0,
            )
            output = ssh.execute(command) or []
            return "\n".join(str(line) for line in output)
        except Exception as exc:
            raise RuntimeError(
                "SSH command failed on KVM host %s: %s"
                % (host.name, exc)
            )

    @classmethod
    def _prepare_live_migration_hosts(cls):
        for host in cls.ready_hosts:
            credentials = cls._host_credentials(host)
            cls._ensure_libvirt_tcp_listen(credentials)
            output = cls._ssh_output(
                host,
                "virsh version >/dev/null 2>&1 "
                "&& ss -lnt | grep -Eq ':(16509|16514)[[:space:]]' "
                "&& echo LIVE_MIGRATION_READY",
            )
            if "LIVE_MIGRATION_READY" not in output:
                raise RuntimeError(
                    "KVM host %s is not ready for libvirt TCP migration"
                    % host.name
                )
        # Restarting libvirtd drops the KVM agent. A pool created while a
        # host is reconnecting is exported only to the hosts that are Up
        # at that moment, so wait until every host is Up again first.
        for host in cls.ready_hosts:
            cls._wait_for_host_up(host.id)

    @classmethod
    def _validate_migration_prerequisites(
            cls, config, minimum_kvm_hosts=2):
        """Validate the single ONTAP SVM and two-host migration lab."""
        issues = []
        cloudstack_cfg = config.get("cloudstack", {})
        zone_name = cloudstack_cfg.get("zoneName")
        if not is_configured(zone_name):
            issues.append("cloudstack.zoneName")

        ontap_cfg = config.get("ontap", {}) or {}
        for field in ("storageIP", "svmName", "username", "password"):
            if not is_configured(ontap_cfg.get(field)):
                issues.append("ontap.%s" % field)

        configured_hosts = []
        for zone in config.get("zones", []):
            if is_configured(zone_name) and zone.get("name") != zone_name:
                continue
            for pod in zone.get("pods", []):
                for cluster in pod.get("clusters", []):
                    configured_hosts.extend(cluster.get("hosts", []))
        if len(configured_hosts) < minimum_kvm_hosts:
            issues.append(
                "configured KVM host count (need %d, found %d)"
                % (minimum_kvm_hosts, len(configured_hosts))
            )
        for index, host in enumerate(configured_hosts):
            for field in ("url", "username", "password"):
                if not is_configured(host.get(field)):
                    issues.append(
                        "configured KVM host %d.%s" % (index, field)
                    )

        zone_hosts = Host.list(
            cls.apiClient,
            zoneid=cls.zone.id,
            type="Routing",
            hypervisor="KVM",
        ) or []
        migration_hosts = get_ready_hosts(zone_hosts)
        if len(migration_hosts) < minimum_kvm_hosts:
            issues.append(
                "Up/Enabled KVM host count (need %d, found %d)"
                % (minimum_kvm_hosts, len(migration_hosts))
            )

        if issues:
            raise RuntimeError(
                "Migration test prerequisites are incomplete: %s"
                % ", ".join(issues)
            )

        try:
            OntapRestClient(
                ontap_cfg["storageIP"],
                ontap_cfg["username"],
                ontap_cfg["password"],
            ).check_connection()
        except Exception as ex:
            raise RuntimeError("ONTAP is not ready: %s" % ex)
        return ontap_cfg, migration_hosts

    @classmethod
    def _find_default_primary_pool(cls):
        pools = list_storage_pools(
            cls.apiClient, zoneid=cls.zone.id, clusterid=cls.cluster.id
        ) or []
        default_pools = [
            pool for pool in pools
            if str(getattr(pool, "provider", "")).lower() == "defaultprimary"
            and getattr(pool, "state", "") == "Up"
        ]
        expected_tag = cls._default_storage_tag()
        if expected_tag:
            for pool in default_pools:
                pool_tags = {
                    tag.strip()
                    for tag in str(getattr(pool, "tags", "")).split(",")
                    if tag.strip()
                }
                if expected_tag in pool_tags:
                    logger.info(
                        "Using configured DefaultPrimary pool '%s' (id=%s).",
                        pool.name, pool.id,
                    )
                    return pool
        if default_pools:
            pool = default_pools[0]
            logger.info(
                "Using existing DefaultPrimary pool '%s' (id=%s).",
                pool.name, pool.id,
            )
            return pool
        raise unittest.SkipTest(
            "No Up default primary storage pool exists in the test cluster"
        )

    @classmethod
    def _pool_protocol(cls, pool):
        details = {
            str(key).lower(): value
            for key, value in cls._pool_details(pool).items()
        }
        protocol = str(details.get("protocol", "")).upper()
        if protocol in ("NFS", "NFS3"):
            return "NFS3"
        if protocol == "ISCSI":
            return "ISCSI"
        pool_type = str(getattr(pool, "type", "")).lower()
        if pool_type == "networkfilesystem":
            return "NFS3"
        if pool_type in ("iscsilun", "ontapiscsi"):
            return "ISCSI"
        return None

    @classmethod
    def _pool_details(cls, pool):
        details = dict(_parse_pool_details(pool))
        required = {"protocol", "storageIP", "svmName"}
        if required.issubset(details):
            return details
        if cls.dbConnection is None:
            return details
        rows = cls.dbConnection.execute(
            "SELECT storage_pool_details.name, storage_pool_details.value "
            "FROM storage_pool_details "
            "JOIN storage_pool "
            "ON storage_pool.id = storage_pool_details.pool_id "
            "WHERE storage_pool.uuid = %s",
            (pool.id,),
        ) or []
        details.update({name: value for name, value in rows})
        return details

    @classmethod
    def _is_compatible_ontap_pool(
            cls, pool, protocol, scope=None, cluster_id=None):
        ontap_cfg = cls.config_data["ontap"]
        provider = str(getattr(pool, "provider", "")).lower()
        if provider != str(
                cls.config_data["storagePool"].get(
                    "storagePoolProvider", "NetApp ONTAP"
                )).lower():
            return False
        if str(getattr(pool, "state", "")).lower() != "up":
            return False
        if cls._pool_protocol(pool) != protocol:
            return False
        if scope and str(getattr(pool, "scope", "")).upper() != scope:
            return False
        if (scope == "CLUSTER"
                and getattr(pool, "clusterid", None) != cluster_id):
            return False
        expected_tag = cls._storage_tag(protocol)
        pool_tags = {
            tag.strip()
            for tag in str(getattr(pool, "tags", "")).split(",")
            if tag.strip()
        }
        if expected_tag and expected_tag not in pool_tags:
            return False
        details = {
            str(key).lower(): str(value)
            for key, value in cls._pool_details(pool).items()
        }
        storage_ip = details.get("storageip")
        svm_name = details.get("svmname")
        if storage_ip != str(ontap_cfg["storageIP"]):
            return False
        if svm_name != str(ontap_cfg["svmName"]):
            return False
        reported = (
            getattr(pool, "capacitybytes", None)
            or getattr(pool, "disksizetotal", None)
        )
        if reported is not None:
            try:
                if int(reported) < int(
                        cls._migration_pool_capacity_bytes() * 0.9
                ):
                    return False
            except (TypeError, ValueError):
                return False
        return True

    @classmethod
    def _migration_pool_capacity_bytes(cls):
        return int(cls.config_data["storagePool"]["capacitybytes"])

    @classmethod
    def _pool_requirements(cls):
        return {
            "NFS3": [("CLUSTER", cls.cluster.id, 2)],
            "ISCSI": [("CLUSTER", cls.cluster.id, 2)],
        }

    @classmethod
    def _pool_key(cls, scope, cluster_id=None):
        return (
            "ZONE" if scope == "ZONE"
            else "CLUSTER:%s" % cluster_id
        )

    @classmethod
    def _ensure_migration_pools(cls):
        """Discover or create each protocol/scope/cluster pool requirement."""
        pools = list_storage_pools(cls.apiClient, zoneid=cls.zone.id) or []
        selected = {"NFS3": {}, "ISCSI": {}}
        for protocol, requirements in cls._pool_requirements().items():
            cls._protocol_config(protocol)
            for scope, cluster_id, count in requirements:
                key = cls._pool_key(scope, cluster_id)
                selected[protocol][key] = [
                    pool for pool in pools
                    if cls._is_compatible_ontap_pool(
                        pool, protocol, scope, cluster_id
                    )
                ][:count]
                for pool in selected[protocol][key]:
                    logger.info(
                        "Using ONTAP %s %s pool '%s' (id=%s).",
                        protocol, key, pool.name, pool.id,
                    )
                    if (pool.name.startswith("OntapMigration")
                            and all(
                                item.id != pool.id
                                for item in cls.created_pools
                            )):
                        cls.created_pools.append(pool)
                while len(selected[protocol][key]) < count:
                    pool = cls._create_ontap_pool(
                        protocol, scope, cluster_id
                    )
                    selected[protocol][key].append(pool)
                    pools.append(pool)
                if len(selected[protocol][key]) != count:
                    raise RuntimeError(
                        "Expected %d ONTAP %s pools for %s, found %d"
                        % (
                            count, protocol, key,
                            len(selected[protocol][key]),
                        )
                    )
                for pool in selected[protocol][key]:
                    cls._validate_migration_pool(
                        pool, protocol, scope, cluster_id
                    )
        return selected

    @classmethod
    def _validate_migration_pool(
            cls, pool, protocol, scope, cluster_id=None):
        if not cls._is_compatible_ontap_pool(
                pool, protocol, scope, cluster_id):
            raise RuntimeError(
                "ONTAP pool %s does not match %s %s prerequisites"
                % (pool.name, protocol, cls._pool_key(scope, cluster_id))
            )
        backend = cls.ontap.get_volume(pool.name)
        if backend is None:
            raise RuntimeError(
                "ONTAP FlexVol %s for storage pool %s does not exist"
                % (pool.name, pool.id)
            )
        if str(backend.get("state", "")).lower() != "online":
            raise RuntimeError(
                "ONTAP FlexVol %s is not online" % pool.name
            )

    @classmethod
    def _migration_pool(
            cls, protocol, index=0, scope="CLUSTER", cluster_id=None):
        protocol = protocol.upper()
        cls._protocol_config(protocol)
        if scope == "CLUSTER" and cluster_id is None:
            cluster_id = cls.cluster.id
        key = cls._pool_key(scope, cluster_id)
        pools = cls.ontap_pools.get(protocol, {}).get(key, [])
        if len(pools) <= index:
            raise unittest.SkipTest(
                "ONTAP %s %s pool %d is unavailable"
                % (protocol, key, index + 1)
            )
        return pools[index]

    def _pool_by_id(self, pool_id):
        pools = list_storage_pools(self.apiClient, id=pool_id) or []
        self.assertTrue(pools, "Storage pool %s was not found" % pool_id)
        return pools[0]

    @classmethod
    def _find_template(cls):
        ready = [
            template for template in list_kvm_templates(
                cls.apiClient, cls.zone.id
            )
            if getattr(template, "isready", False)
            and str(getattr(template, "templatetype", "")).upper() != "SYSTEM"
        ]
        if not ready:
            raise unittest.SkipTest("No ready user KVM template is available")
        configured_name = cls.config_data.get(
            "cloudstack", {}
        ).get("templateName")
        configured = [
            template for template in ready
            if getattr(template, "name", None) == configured_name
        ]
        return (configured or ready)[0].id

    @classmethod
    def _find_or_create_network(cls):
        network_type = str(
            getattr(cls.zone, "networktype", "Basic")
        ).lower()
        if network_type != "advanced":
            return None
        cmd = listNetworksAPI.listNetworksCmd()
        cmd.zoneid = cls.zone.id
        cmd.account = cls.account.name
        cmd.domainid = cls.domain.id
        networks = cls.apiClient.listNetworks(cmd) or []
        if networks:
            return networks[0].id

        offering_cmd = listNetworkOfferingsAPI.listNetworkOfferingsCmd()
        offering_cmd.state = "Enabled"
        offering_cmd.guestiptype = "Isolated"
        offering_cmd.specifyvlan = False
        offerings = cls.apiClient.listNetworkOfferings(offering_cmd) or []
        offering = next(
            (
                item for item in offerings
                if "SourceNat" in item.name
                and "Vpc" not in item.name
                and "NSX" not in item.name
                and "Netris" not in item.name
            ),
            offerings[0] if offerings else None,
        )
        if offering is None:
            raise unittest.SkipTest(
                "No enabled isolated network offering is available"
            )
        create_cmd = createNetworkAPI.createNetworkCmd()
        create_cmd.zoneid = cls.zone.id
        create_cmd.networkofferingid = offering.id
        create_cmd.name = "ontap-migration-%d" % random.randint(0, 999999)
        create_cmd.displaytext = "ONTAP migration test network"
        create_cmd.account = cls.account.name
        create_cmd.domainid = cls.domain.id
        network = cls.apiClient.createNetwork(create_cmd)
        cls._created_network_id = network.id
        return network.id

    @classmethod
    def _recreate_test_network(cls):
        if cls._created_network_id:
            deadline = time.time() + 180
            while True:
                cmd = deleteNetworkAPI.deleteNetworkCmd()
                cmd.id = cls._created_network_id
                try:
                    cls.apiClient.deleteNetwork(cmd)
                    break
                except Exception as exc:
                    if time.time() >= deadline:
                        raise
                    logger.warning(
                        "Could not delete network %s; retrying: %s",
                        cls._created_network_id,
                        exc,
                    )
                    time.sleep(5)
            cls._created_network_id = None
        cls.network_id = cls._find_or_create_network()

    @classmethod
    def _protocol_config(cls, protocol):
        key = "nfs3" if protocol.upper() == "NFS3" else "iscsi"
        config = cls.config_data.get(
            "storagePool", {}
        ).get("protocols", {}).get(key, {})
        if not config.get("enabled", False):
            raise unittest.SkipTest("%s is disabled in ontap.cfg" % protocol)
        return config

    @classmethod
    def _storage_tag(cls, protocol):
        return cls._protocol_config(protocol).get("storagePoolTags")

    @classmethod
    def _default_storage_tag(cls):
        for zone in cls.config_data.get("zones", []):
            if zone.get("name") != cls.zone.name:
                continue
            for pod in zone.get("pods", []):
                for cluster in pod.get("clusters", []):
                    if cluster.get("clustername") != cls.cluster.name:
                        continue
                    for pool in cluster.get("primaryStorages", []):
                        if pool.get("provider") == "DefaultPrimary":
                            return pool.get("tags")
        return getattr(cls.default_pool, "tags", None)

    @classmethod
    def _service_offering(cls):
        if "shared" in cls.service_offerings:
            return cls.service_offerings["shared"]
        data = {
            "name": "ontap-migration-so-%d" % random.randint(0, 999999),
            "displaytext": "ONTAP migration service offering",
            "cpunumber": 1,
            "cpuspeed": 100,
            "memory": 256,
            "storagetype": "shared",
        }
        offering = ServiceOffering.create(cls.apiClient, data)
        cls.service_offerings["shared"] = offering
        cls._cleanup.insert(0, offering)
        return offering

    @classmethod
    def _root_disk_offering(cls, storage_tag):
        key = storage_tag or "__untagged__"
        if key in cls.disk_offerings:
            return cls.disk_offerings[key]
        data = {
            "name": "ontap-migration-root-do-%d"
                    % random.randint(0, 999999),
            "displaytext": "ONTAP migration root disk offering",
            "disksize": 8,
            "storagetype": "shared",
        }
        if storage_tag:
            data["tags"] = storage_tag
        offering = DiskOffering.create(cls.apiClient, data)
        cls.disk_offerings[key] = offering
        cls._cleanup.insert(0, offering)
        return offering

    @classmethod
    def _create_ontap_pool(cls, protocol, scope, cluster_id=None):
        config = cls.config_data
        ontap_cfg = config["ontap"]
        pool_cfg = config["storagePool"]
        name = "OntapMigration%s_%d" % (
            protocol.upper(), random.randint(0, 999999)
        )
        details = {
            "username": ontap_cfg["username"],
            "password": base64.b64encode(
                ontap_cfg["password"].encode()
            ).decode(),
            "svmName": ontap_cfg["svmName"],
            "protocol": protocol.upper(),
            "storageIP": ontap_cfg["storageIP"],
        }

        cmd = createStoragePoolAPI.createStoragePoolCmd()
        cmd.name = name
        scheme = "nfs" if protocol.upper() == "NFS3" else "iscsi"
        cmd.url = "%s://%s/ontap" % (scheme, ontap_cfg["storageIP"])
        cmd.zoneid = cls.zone.id
        if scope == "CLUSTER":
            cmd.podid = cls.cluster.podid
            cmd.clusterid = cluster_id
        cmd.scope = scope
        cmd.provider = pool_cfg.get(
            "storagePoolProvider", "NetApp ONTAP"
        )
        cmd.tags = cls._storage_tag(protocol)
        cmd.capacitybytes = cls._migration_pool_capacity_bytes()
        cmd.hypervisor = "KVM"
        cmd.managed = True
        for index, (key, value) in enumerate(details.items(), 1):
            setattr(cmd, "details[%d].%s" % (index, key), value)
        last_error = None
        pool = None
        for attempt in range(1, 4):
            try:
                pool = StoragePool(
                    cls.apiClient.createStoragePool(cmd).__dict__
                )
                break
            except Exception as exc:
                last_error = exc
                try:
                    pools = list_storage_pools(
                        cls.apiClient, zoneid=cls.zone.id, name=name
                    ) or []
                except Exception as lookup_exc:
                    logger.warning(
                        "Could not check whether ONTAP pool '%s' was "
                        "created: %s", name, lookup_exc
                    )
                    pools = []
                if pools:
                    pool = pools[0]
                    logger.warning(
                        "Recovered ONTAP pool '%s' after its create API "
                        "response failed: %s", name, exc
                    )
                    break
                if attempt < 3:
                    logger.warning(
                        "ONTAP pool '%s' create attempt %d failed: %s; "
                        "retrying.", name, attempt, exc
                    )
                    time.sleep(5)
        if pool is None:
            raise last_error
        cls.created_pools.append(pool)
        logger.info(
            "Created ONTAP %s %s pool '%s' (id=%s).",
            protocol.upper(), cls._pool_key(scope, cluster_id),
            pool.name, pool.id,
        )
        return pool

    def _deploy_vm(self, storage_tag=None, host_id=None):
        offering = self._service_offering()
        root_disk_offering = self._root_disk_offering(storage_tag)
        cmd = deployVirtualMachineAPI.deployVirtualMachineCmd()
        cmd.zoneid = self.__class__.zone.id
        cmd.templateid = self.__class__.template_id
        cmd.serviceofferingid = offering.id
        cmd.overridediskofferingid = root_disk_offering.id
        cmd.account = self.__class__.account.name
        cmd.domainid = self.__class__.domain.id
        if self.__class__.network_id:
            cmd.networkids = self.__class__.network_id
        if host_id:
            cmd.hostid = host_id
        disabled_hosts = self._disable_hosts_outside_cluster(
            self.__class__.cluster.id
        )
        old_timeout = getattr(self.apiClient.connection, "asyncTimeout", 3600)
        self.apiClient.connection.asyncTimeout = 180
        try:
            vm = self.apiClient.deployVirtualMachine(cmd)
        finally:
            self.apiClient.connection.asyncTimeout = old_timeout
            self._enable_hosts(disabled_hosts)
        if vm is None:
            raise RuntimeError(
                "deployVirtualMachine did not finish within 180s"
            )
        self.__class__.created_vms.append(vm)
        return self._poll_vm(vm.id, "Running", timeout=120)

    def _disable_hosts_outside_cluster(self, cluster_id):
        disabled = []
        for host in self.__class__.ready_hosts:
            if host.clusterid == cluster_id:
                continue
            try:
                Host.update(
                    self.apiClient,
                    id=host.id,
                    allocationstate="Disable",
                )
                disabled.append(host.id)
            except Exception as exc:
                logger.warning(
                    "Could not disable host %s for VR placement: %s",
                    host.id, exc,
                )
        return disabled

    def _enable_hosts(self, host_ids):
        for host_id in host_ids:
            try:
                Host.update(
                    self.apiClient,
                    id=host_id,
                    allocationstate="Enable",
                )
            except Exception as exc:
                logger.warning(
                    "Could not re-enable host %s after deploy: %s",
                    host_id, exc,
                )

    def _migration_target(self, vm, cluster_id=None):
        current = self._get_vm(vm.id)
        hosts = Host.listForMigration(
            self.apiClient, virtualmachineid=vm.id
        ) or []
        suitable = [
            host for host in hosts
            if host.id != current.hostid
            and (cluster_id is None or host.clusterid == cluster_id)
            and getattr(host, "suitableformigration", True)
            and str(getattr(host, "state", "Up")).lower() == "up"
            and str(
                getattr(host, "resourcestate", "Enabled")
            ).lower() == "enabled"
        ]
        if not suitable:
            raise unittest.SkipTest(
                "No suitable destination host is available for VM %s"
                % vm.id
            )
        return suitable[0]

    def _migrate_stopped_vm_storage(self, vm, pool):
        cmd = migrateVirtualMachineAPI.migrateVirtualMachineCmd()
        cmd.virtualmachineid = vm.id
        cmd.storageid = pool.id
        return self.apiClient.migrateVirtualMachine(cmd)

    def _migrate_vm_volumes(self, vm, host, mappings):
        cmd = migrateVMWithVolumeAPI.migrateVirtualMachineWithVolumeCmd()
        cmd.virtualmachineid = vm.id
        cmd.hostid = host.id
        cmd.migrateto = [
            {"volume": str(volume.id), "pool": str(pool.id)}
            for volume, pool in mappings
        ]
        return self.apiClient.migrateVirtualMachineWithVolume(cmd)

    def _migrate_volume_offline(self, volume, pool, timeout=600):
        cmd = migrateVolumeAPI.migrateVolumeCmd()
        cmd.volumeid = volume.id
        cmd.storageid = pool.id
        cmd.livemigrate = False
        self.apiClient.migrateVolume(cmd)
        return self._poll_volume(
            volume.id, "storageid", pool.id, timeout=timeout
        )

    @classmethod
    def _host_for_cluster(cls, cluster_id):
        hosts = cls.hosts_by_cluster.get(cluster_id, [])
        if not hosts:
            raise unittest.SkipTest(
                "No Up/Enabled KVM host exists in cluster %s" % cluster_id
            )
        return hosts[0]

    @classmethod
    def _require_secondary_cluster(cls):
        if cls.secondary_cluster_id is None:
            raise unittest.SkipTest(
                "A second KVM cluster with an Up host is required"
            )
        return cls.secondary_cluster_id

    def _create_data_volume(self, pool):
        cls = self.__class__
        offering_id = getattr(cls, "small_disk_offering_id", None)
        if offering_id is None:
            offering = DiskOffering.create(cls.apiClient, {
                "name": "ontap-migration-data-%d" % random.randint(0, 999999),
                "displaytext": "ONTAP migration 1GB data disk",
                "disksize": 1,
            })
            cls.small_disk_offering_id = offering.id
            offering_id = offering.id
        cmd = createVolumeAPI.createVolumeCmd()
        cmd.name = "%s_%d" % (self._vol_name_prefix, random.randint(0, 99999))
        cmd.diskofferingid = offering_id
        cmd.zoneid = cls.zone.id
        cmd.storageid = pool.id
        cmd.account = cls.account.name
        cmd.domainid = cls.domain.id
        volume = self.apiClient.createVolume(cmd)
        cls.created_volumes.append(volume)
        return volume

    def _attach_volume(self, vm, volume):
        cmd = attachVolumeAPI.attachVolumeCmd()
        cmd.id = volume.id
        cmd.virtualmachineid = vm.id
        self.apiClient.attachVolume(cmd)
        return self._poll_volume(
            volume.id, "virtualmachineid", vm.id, timeout=180
        )

    def _stop_vm(self, vm):
        cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
        cmd.id = vm.id
        self.apiClient.stopVirtualMachine(cmd)
        return self._poll_vm(vm.id, "Stopped")

    def _start_vm(self, vm):
        cmd = startVirtualMachineAPI.startVirtualMachineCmd()
        cmd.id = vm.id
        self.apiClient.startVirtualMachine(cmd)
        return self._poll_vm(vm.id, "Running")

    def _poll_vm(self, vm_id, state, timeout=300):
        deadline = time.time() + timeout
        while time.time() < deadline:
            cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
            cmd.id = vm_id
            cmd.listall = True
            vms = self.apiClient.listVirtualMachines(cmd) or []
            if vms and str(vms[0].state).lower() == state.lower():
                return vms[0]
            time.sleep(5)
        self.fail("VM %s did not reach %s" % (vm_id, state))

    def _get_vm(self, vm_id):
        cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
        cmd.id = vm_id
        cmd.listall = True
        vms = self.apiClient.listVirtualMachines(cmd) or []
        self.assertTrue(vms, "VM %s was not found" % vm_id)
        return vms[0]

    def _get_volume(self, volume_id):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.id = volume_id
        cmd.listall = True
        volumes = self.apiClient.listVolumes(cmd) or []
        self.assertTrue(volumes, "Volume %s was not found" % volume_id)
        return volumes[0]

    def _vm_volumes(self, vm_id):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.virtualmachineid = vm_id
        cmd.listall = True
        return self.apiClient.listVolumes(cmd) or []

    def _poll_volume(self, volume_id, field, value, timeout=300):
        deadline = time.time() + timeout
        while time.time() < deadline:
            volume = self._get_volume(volume_id)
            if getattr(volume, field, None) == value:
                return volume
            time.sleep(5)
        self.fail(
            "Volume %s field %s did not reach %r"
            % (volume_id, field, value)
        )

    def _assert_backend_object(self, protocol, pool, volume):
        backend_name = self._backend_name(protocol, pool, volume)
        deadline = time.time() + 60
        last_error = None
        while time.time() < deadline:
            try:
                if self._backend_object_exists(
                        protocol, pool, backend_name):
                    return
            except Exception as exc:
                last_error = exc
                logger.warning(
                    "Could not verify %s backend object %s: %s",
                    protocol, backend_name, exc,
                )
            time.sleep(5)
        error = " Last ONTAP error: %s" % last_error if last_error else ""
        self.fail(
            "No %s backend object %s for volume %s in %s.%s"
            % (protocol, backend_name, volume.id, pool.name, error)
        )

    def _backend_name(self, protocol, pool, volume):
        if protocol.upper() == "NFS3":
            return getattr(volume, "path", None) or volume.id
        return "/vol/%s/%s" % (
            pool.name, volume.name.replace("-", "_")
        )

    def _backend_object_exists(self, protocol, pool, backend_name):
        if protocol.upper() == "NFS3":
            files = self.__class__.ontap.list_files_in_volume(pool.name)
            return any(str(backend_name) in name for name in files)
        return self.__class__.ontap.get_lun(
            self.__class__.svm_name, backend_name
        ) is not None

    def _assert_backend_object_removed(
            self, protocol, pool, backend_name, timeout=180):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self._backend_object_exists(
                    protocol, pool, backend_name):
                return
            time.sleep(5)
        self.fail(
            "%s backend object %s still exists in source pool %s"
            % (protocol, backend_name, pool.name)
        )

    def _lun_maps(self, pool, volume):
        lun_name = self._backend_name("ISCSI", pool, volume)
        return [
            item for item in self.__class__.ontap.list_lun_maps_for_volume(
                self.__class__.svm_name, pool.name
            )
            if item.get("lun", {}).get("name") == lun_name
        ]

    @classmethod
    def _igroup_name_for_host(cls, host):
        host_uuid = re.sub(
            r"[^a-zA-Z0-9_-]", "_",
            str(getattr(host, "id", None) or getattr(host, "uuid", "")),
        )
        return ("cs_%s_%s" % (host_uuid, cls.svm_name))[:96]

    @classmethod
    def _host_iqn(cls, host):
        listed = Host.list(cls.apiClient, id=host.id, listall=True) or [host]
        current = listed[0]
        iqn = (
            getattr(current, "storageurl", None)
            or getattr(current, "StorageUrl", None)
        )
        if iqn and str(iqn).startswith("iqn."):
            return str(iqn)
        return None

    def _assert_destination_access(
            self, protocol, pool, volume, host=None, is_running=False):
        if protocol.upper() == "NFS3":
            clients = self._export_policy_clients(pool)
            scope = str(getattr(pool, "scope", "")).upper()
            allowed_cluster = getattr(pool, "clusterid", None)
            for cluster_id, hosts in self.__class__.hosts_by_cluster.items():
                for candidate in hosts:
                    host_ip = getattr(candidate, "ipaddress", None)
                    if not host_ip:
                        continue
                    is_allowed = (
                        scope == "ZONE" or cluster_id == allowed_cluster
                    )
                    matches = any(
                        host_ip in client for client in clients
                    )
                    self.assertEqual(
                        matches, is_allowed,
                        "NFS export clients %s do not match %s scope for "
                        "host %s" % (clients, scope, host_ip),
                    )
            return

        maps = self._lun_maps(pool, volume)
        if not is_running:
            self.assertEqual(
                maps, [],
                "Stopped or detached volume %s retained LUN maps: %s"
                % (volume.id, maps),
            )
            return
        self.assertIsNotNone(host)
        expected_igroup = self._igroup_name_for_host(host)
        mapped_igroups = {
            item.get("igroup", {}).get("name") for item in maps
        }
        self.assertEqual(
            mapped_igroups, {expected_igroup},
            "Volume %s maps %s do not target destination igroup %s"
            % (volume.id, maps, expected_igroup),
        )
        igroup = self.__class__.ontap.get_igroup(
            self.__class__.svm_name, expected_igroup
        )
        self.assertIsNotNone(
            igroup,
            "Destination igroup %s does not exist on ONTAP"
            % expected_igroup,
        )
        host_iqn = self._host_iqn(host)
        if host_iqn:
            initiator_names = [
                item.get("name", "")
                for item in igroup.get("initiators", []) or []
            ]
            self.assertIn(
                host_iqn, initiator_names,
                "Host IQN %s is not in dest igroup %s: %s"
                % (host_iqn, expected_igroup, initiator_names),
            )

    def _snapshot_state(
            self, vm=None, volumes=None, source_pool=None, protocol=None,
            destination_pool=None, destination_protocol=None):
        current_vm = self._get_vm(vm.id) if vm is not None else None
        current_volumes = [
            self._get_volume(volume.id) for volume in (volumes or [])
        ]
        snapshot = {
            "vm": None if current_vm is None else (
                current_vm.state,
                current_vm.hostid,
                getattr(current_vm, "clusterid", None),
            ),
            "volumes": {
                volume.id: (
                    volume.storageid,
                    volume.path,
                    str(getattr(volume, "format", "") or "").upper(),
                    getattr(volume, "virtualmachineid", None),
                    str(volume.state),
                )
                for volume in current_volumes
            },
            "source_backend": {},
            "destination_backend": {},
        }
        if source_pool is not None and protocol is not None:
            for volume in current_volumes:
                name = self._backend_name(protocol, source_pool, volume)
                snapshot["source_backend"][volume.id] = (
                    name,
                    self._backend_object_exists(
                        protocol, source_pool, name
                    ),
                    tuple(sorted(
                        item.get("igroup", {}).get("name", "")
                        for item in self._lun_maps(source_pool, volume)
                    )) if protocol.upper() == "ISCSI" else tuple(
                        sorted(self._export_policy_clients(source_pool))
                    ),
                )
        if destination_pool is not None and protocol is not None:
            target_protocol = destination_protocol or protocol
            for volume in current_volumes:
                name = self._backend_name(
                    target_protocol, destination_pool, volume
                )
                snapshot["destination_backend"][volume.id] = (
                    name,
                    self._backend_object_exists(
                        target_protocol, destination_pool, name
                    ),
                )
            snapshot["destination_protocol"] = target_protocol
        return snapshot

    def _assert_state_unchanged(
            self, snapshot, vm=None, source_pool=None, protocol=None,
            destination_pool=None):
        current_vm = self._get_vm(vm.id) if vm is not None else None
        vm_state = None if current_vm is None else (
            current_vm.state,
            current_vm.hostid,
            getattr(current_vm, "clusterid", None),
        )
        self.assertEqual(vm_state, snapshot["vm"])
        for volume_id, expected in snapshot["volumes"].items():
            volume = self._get_volume(volume_id)
            actual = (
                volume.storageid,
                volume.path,
                str(getattr(volume, "format", "") or "").upper(),
                getattr(volume, "virtualmachineid", None),
                str(volume.state),
            )
            self.assertEqual(actual, expected)
            if source_pool is not None and protocol is not None:
                backend_name, existed, access = (
                    snapshot["source_backend"][volume_id]
                )
                self.assertEqual(
                    self._backend_object_exists(
                        protocol, source_pool, backend_name
                    ), existed,
                )
                if protocol.upper() == "ISCSI":
                    current_access = tuple(sorted(
                        item.get("igroup", {}).get("name", "")
                        for item in self._lun_maps(source_pool, volume)
                    ))
                else:
                    current_access = tuple(sorted(
                        self._export_policy_clients(source_pool)
                    ))
                self.assertEqual(current_access, access)
            if destination_pool is not None and protocol is not None:
                backend_name, existed = (
                    snapshot["destination_backend"][volume_id]
                )
                target_protocol = snapshot.get(
                    "destination_protocol", protocol
                )
                self.assertEqual(
                    self._backend_object_exists(
                        target_protocol, destination_pool, backend_name
                    ), existed,
                )

    def _volumes_on_pool(self, pool):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.storageid = pool.id
        cmd.listall = True
        return self.apiClient.listVolumes(cmd) or []

    def _volume_ids_on_pool(self, pool):
        return {volume.id for volume in self._volumes_on_pool(pool)}

    def _finish_rejected_migration(
            self, volumes, destination_pool, existing_dest_ids):
        """Put a rejected migration's disks back before the test returns.

        A refused copy can leave the source in Migrating and a duplicate on
        the destination. The source has to be Ready again, and the duplicate
        has to be gone, or the next case in this file cannot run.
        """
        source_ids = set()
        for volume in volumes or []:
            source_ids.add(volume.id)
            current = self._get_volume(volume.id)
            if str(current.state) == "Migrating":
                logger.warning(
                    "Rejected migration left volume %s (%s) in Migrating; "
                    "restoring Ready",
                    current.id, getattr(current, "name", ""),
                )
                self.__class__.dbConnection.execute(
                    "UPDATE volumes SET state = 'Ready' "
                    "WHERE uuid = %s AND state = 'Migrating' "
                    "AND removed IS NULL",
                    (volume.id,),
                )
                current = self._get_volume(volume.id)
            self.assertEqual(
                str(current.state),
                "Ready",
                "Volume %s (%s) is %s after a rejected migration; "
                "expected Ready" % (
                    current.id, getattr(current, "name", ""), current.state,
                ),
            )
        if destination_pool is None:
            return
        for created in self._volumes_on_pool(destination_pool):
            if created.id in source_ids or created.id in existing_dest_ids:
                continue
            logger.warning(
                "Rejected migration left destination volume %s (%s) in %s; "
                "expunging it",
                created.id, getattr(created, "name", ""), created.state,
            )
            self.__class__.dbConnection.execute(
                "UPDATE volumes SET state = 'Expunged', removed = NOW() "
                "WHERE uuid = %s AND removed IS NULL",
                (created.id,),
            )

    def _assert_migration_success(
            self, original_volumes, pool, protocol, vm=None,
            expected_vm_state=None, expected_host=None,
            expected_vm_id=None, source_pool=None,
            check_source_removed=True):
        if vm is not None:
            if expected_vm_state is not None:
                current_vm = self._poll_vm(
                    vm.id, expected_vm_state, timeout=120
                )
            else:
                current_vm = self._get_vm(vm.id)
            if expected_host is not None:
                self.assertEqual(current_vm.hostid, expected_host.id)
                actual_host = next(
                    (host for host in self.__class__.ready_hosts
                     if host.id == current_vm.hostid),
                    None,
                )
                self.assertIsNotNone(
                    actual_host,
                    "VM host %s is not in the ready host inventory"
                    % current_vm.hostid,
                )
                self.assertEqual(
                    actual_host.clusterid, expected_host.clusterid
                )
        for original in original_volumes:
            current = self._get_volume(original.id)
            self.assertEqual(current.id, original.id)
            self.assertEqual(current.storageid, pool.id)
            self.assertEqual(
                getattr(current, "virtualmachineid", None),
                expected_vm_id,
            )
            self.assertTrue(current.path)
            expected_format = (
                "QCOW2" if protocol.upper() == "NFS3" else "RAW"
            )
            reported_format = getattr(current, "format", None)
            if reported_format:
                self.assertEqual(
                    str(reported_format).upper(), expected_format
                )
            self._assert_backend_object(protocol, pool, current)
            self._assert_destination_access(
                protocol,
                pool,
                current,
                host=expected_host,
                is_running=expected_vm_state == "Running",
            )
            if (source_pool is not None
                    and source_pool.id != pool.id
                    and self._pool_protocol(source_pool) == protocol
                    and check_source_removed):
                source_name = self._backend_name(
                    protocol, source_pool, original
                )
                self._assert_backend_object_removed(
                    protocol, source_pool, source_name
                )
        return [self._get_volume(volume.id) for volume in original_volumes]

    def _export_policy_clients(self, pool):
        details = self.__class__._pool_details(pool)
        policy_name = details.get("exportPolicyName")
        if not policy_name:
            policy_name = "cs-%s-%s" % (
                self.__class__.svm_name, pool.name
            )
        policy = self.__class__.ontap.get_export_policy(policy_name)
        self.assertIsNotNone(
            policy,
            "Export policy '%s' not found on ONTAP" % policy_name,
        )
        return [
            client.get("match", "")
            for rule in policy.get("rules", [])
            for client in rule.get("clients", [])
        ]

    def _host_ips(self, cluster_id):
        return [
            host.ipaddress
            for host in self.__class__.hosts_by_cluster.get(cluster_id, [])
            if getattr(host, "ipaddress", None)
        ]

    @classmethod
    def _host_by_id(cls, host_id):
        host = next(
            (candidate for candidate in cls.ready_hosts
             if candidate.id == host_id),
            None,
        )
        if host is None:
            raise RuntimeError(
                "KVM host %s is not in the ready host inventory" % host_id
            )
        return host

    @staticmethod
    def _vm_instance_name(vm):
        name = getattr(vm, "instancename", None)
        if not name:
            raise RuntimeError(
                "CloudStack VM %s has no KVM instance name" % vm.id
            )
        return name

    @classmethod
    def _dumpxml_disk_sources(cls, host, domain_name):
        xml = cls._ssh_output(
            host,
            "virsh dumpxml %s 2>/dev/null || true"
            % shlex.quote(domain_name),
        )
        return DISK_SOURCE_RE.findall(xml)

    @classmethod
    def _iscsi_sessions(cls, host):
        return cls._ssh_output(
            host,
            "iscsiadm -m session 2>/dev/null || true",
        )

    @classmethod
    def _host_vm_snapshot(cls, vm):
        domain = shlex.quote(cls._vm_instance_name(vm))
        command = (
            "state=$(virsh domstate %s 2>/dev/null || echo ABSENT); "
            "printf 'STATE=%%s\\n' \"$state\"; "
            "echo '---XML---'; "
            "virsh dumpxml %s 2>/dev/null "
            "| grep -E \"source (file|dev)=\" || true; "
            "echo '---SESSIONS---'; "
            "iscsiadm -m session 2>/dev/null || true"
        ) % (domain, domain)
        return {
            host.id: cls._ssh_output(host, command)
            for host in cls.ready_hosts
        }

    def _assert_host_snapshot_unchanged(self, snapshot, vm):
        self.assertEqual(
            self.__class__._host_vm_snapshot(vm),
            snapshot,
            "Rejected migration changed KVM domain, dumpxml, or iSCSI sessions",
        )

    def _assert_vm_not_running_on_hosts(self, vm):
        snapshots = self.__class__._host_vm_snapshot(vm)
        for host_id, snapshot in snapshots.items():
            state_line = next(
                (line for line in snapshot.splitlines()
                 if line.startswith("STATE=")),
                "",
            )
            self.assertNotEqual(
                state_line.strip().lower(),
                "state=running",
                "Stopped VM %s is running on host %s"
                % (vm.id, host_id),
            )

    def _volume_path_tokens(self, volume):
        tokens = []
        for value in (
            getattr(volume, "path", None),
            getattr(volume, "chaininfo", None),
            volume.id,
        ):
            if not value:
                continue
            text = str(value)
            tokens.append(text)
            tokens.append(os.path.basename(text.rstrip("/")))
        return [token for token in tokens if token]

    def _assert_dumpxml_contains_volumes(self, host, vm, volumes):
        sources = self.__class__._dumpxml_disk_sources(
            host, self._vm_instance_name(vm)
        )
        self.assertTrue(
            sources,
            "virsh dumpxml on host %s has no disk sources for VM %s"
            % (host.id, vm.id),
        )
        joined = " ".join(sources)
        for volume in volumes:
            tokens = self._volume_path_tokens(volume)
            self.assertTrue(
                any(token in joined for token in tokens),
                "dumpxml on host %s does not include volume %s path %s "
                "in %s"
                % (host.id, volume.id, getattr(volume, "path", None), sources),
            )
        return sources

    def _nfs_mount_candidates(self, pool, host):
        uuid = str(getattr(pool, "id", "") or "")
        candidates = ["/mnt/%s" % uuid]
        xml = self.__class__._ssh_output(
            host,
            "virsh pool-dumpxml %s 2>/dev/null || true"
            % shlex.quote(uuid),
        )
        match = re.search(r"<path>([^<]+)</path>", xml or "")
        if match:
            candidates.append(match.group(1).strip())
        return candidates

    def _scoped_nfs_hosts(self, pool):
        scope = str(getattr(pool, "scope", "")).upper()
        cluster_id = getattr(pool, "clusterid", None)
        return [
            host for host in self.__class__.ready_hosts
            if scope == "ZONE" or host.clusterid == cluster_id
        ]

    def _assert_nfs_mount_and_file(self, host, pool, volume):
        filename = os.path.basename(
            str(getattr(volume, "path", "") or volume.id).rstrip("/")
        )
        found_mount = None
        for mount in self._nfs_mount_candidates(pool, host):
            quoted = shlex.quote(mount)
            mounted = self.__class__._ssh_output(
                host,
                "findmnt -n %s >/dev/null 2>&1 && echo MOUNTED || "
                "grep -F %s /proc/mounts >/dev/null && echo MOUNTED || true"
                % (quoted, quoted),
            )
            if "MOUNTED" in mounted:
                found_mount = mount
                break
        self.assertIsNotNone(
            found_mount,
            "NFS pool %s is not mounted on host %s"
            % (pool.id, host.id),
        )
        file_path = "%s/%s" % (found_mount.rstrip("/"), filename)
        exists = self.__class__._ssh_output(
            host,
            "test -f %s && echo FILE_EXISTS || "
            "find %s -name %s -type f 2>/dev/null | head -1"
            % (
                shlex.quote(file_path),
                shlex.quote(found_mount),
                shlex.quote(filename),
            ),
        )
        self.assertTrue(
            "FILE_EXISTS" in exists or filename in exists,
            "NFS file %s for volume %s is missing on host %s under %s"
            % (filename, volume.id, host.id, found_mount),
        )
        return found_mount, file_path

    def _iscsi_target_tokens(self, pool, volumes):
        details = {
            str(key).lower(): str(value)
            for key, value in self.__class__._pool_details(pool).items()
        }
        tokens = [
            details.get("storageip") or "",
            str(self.__class__.config_data.get("ontap", {}).get(
                "storageIP", ""
            )),
        ]
        for volume in volumes:
            path = str(getattr(volume, "path", "") or "")
            tokens.append(path)
            if "iqn." in path:
                tokens.append(path.strip("/").split("/")[0])
        return [token for token in tokens if token]

    def _session_mentions_target(self, sessions, tokens):
        text = sessions.lower()
        return any(str(token).lower() in text for token in tokens)

    def _iscsi_lun_wwid(self, pool, volume):
        path = self._backend_name("ISCSI", pool, volume)
        lun = self.__class__.ontap.get_lun(
            self.__class__.svm_name, path
        )
        self.assertTrue(
            lun is not None,
            "ONTAP LUN %s for volume %s does not exist"
            % (path, volume.id),
        )
        serial_number = str(lun.get("serial_number", "") or "")
        self.assertTrue(
            serial_number,
            "ONTAP LUN %s for volume %s has no serial number"
            % (path, volume.id),
        )
        return "600a0980%s" % serial_number.encode("utf-8").hex()

    def _iscsi_device_listing(self, host):
        return self.__class__._ssh_output(
            host,
            "ls -1 /dev/disk/by-path 2>/dev/null; "
            "lsscsi -t 2>/dev/null || true; "
            "for path in /dev/disk/by-path/*; do "
            "device=$(readlink -f \"$path\") || continue; "
            "wwid=$(cat \"/sys/class/block/${device##*/}/device/wwid\" "
            "2>/dev/null) || continue; "
            "printf '%s WWID=%s\\n' \"$path\" \"${wwid#naa.}\"; "
            "done",
        )

    def _assert_iscsi_luns_not_visible(self, host, pool, volumes):
        listing = self._iscsi_device_listing(host)
        joined = listing.lower()
        for volume in volumes:
            wwid = self._iscsi_lun_wwid(pool, volume)
            self.assertNotIn(
                wwid, joined,
                "LUN for volume %s path %s is still visible on host %s: %s"
                % (volume.id, volume.path, host.id, listing),
            )

    def _assert_iscsi_sessions(
            self, pool, volumes, dest_host=None, source_host=None,
            expect_dest_session=False):
        tokens = self._iscsi_target_tokens(pool, volumes)
        for host in self.__class__.ready_hosts:
            sessions = self.__class__._iscsi_sessions(host)
            mentions = self._session_mentions_target(sessions, tokens)
            if expect_dest_session and dest_host is not None and (
                    host.id == dest_host.id):
                self.assertTrue(
                    mentions,
                    "Destination host %s has no iSCSI session for %s: %s"
                    % (host.id, tokens, sessions),
                )
                continue
            if source_host is not None and host.id == source_host.id:
                self._assert_iscsi_luns_not_visible(host, pool, volumes)
                continue
            if not expect_dest_session:
                self._assert_iscsi_luns_not_visible(host, pool, volumes)

    def _assert_iscsi_lun_visible(self, host, pool, volumes):
        listing = self._iscsi_device_listing(host)
        joined = listing.lower()
        for volume in volumes:
            wwid = self._iscsi_lun_wwid(pool, volume)
            self.assertIn(
                wwid, joined,
                "LUN for volume %s path %s is not visible on host %s: %s"
                % (volume.id, volume.path, host.id, listing),
            )

    def _assert_live_host_state(
            self, vm, source_host_id, destination_host, volumes, protocol):
        current_volumes = [self._get_volume(volume.id) for volume in volumes]
        domain = self._vm_instance_name(vm)
        dest_state = self.__class__._ssh_output(
            destination_host,
            "virsh domstate %s 2>/dev/null || echo ABSENT"
            % shlex.quote(domain),
        )
        self.assertIn(
            "running", dest_state.lower(),
            "VM %s is not running on destination host %s"
            % (vm.id, destination_host.id),
        )
        sources = self._assert_dumpxml_contains_volumes(
            destination_host, vm, current_volumes
        )

        source_host = self.__class__._host_by_id(source_host_id)
        source_state = self.__class__._ssh_output(
            source_host,
            "virsh domstate %s 2>/dev/null || echo ABSENT"
            % shlex.quote(domain),
        )
        self.assertNotIn(
            "running", source_state.lower(),
            "VM %s remains active on source host %s"
            % (vm.id, source_host_id),
        )

        pool = self._pool_by_id(current_volumes[0].storageid)
        if protocol.upper() == "NFS3":
            for volume in current_volumes:
                _, file_path = self._assert_nfs_mount_and_file(
                    destination_host, pool, volume
                )
                info = self.__class__._ssh_output(
                    destination_host,
                    "qemu-img info --force-share --output=json %s"
                    % shlex.quote(file_path),
                )
                self.assertIn(
                    '"format": "qcow2"', info.lower(),
                    "Destination disk %s is not QCOW2" % file_path,
                )
            for path in sources:
                if not path.startswith("/"):
                    continue
                info = self.__class__._ssh_output(
                    destination_host,
                    "qemu-img info --force-share --output=json %s "
                    "2>/dev/null || true"
                    % shlex.quote(path),
                )
                if info.strip():
                    self.assertIn(
                        '"format": "qcow2"', info.lower(),
                        "dumpxml disk %s is not QCOW2" % path,
                    )
        else:
            self._assert_iscsi_sessions(
                pool, current_volumes,
                dest_host=destination_host,
                source_host=source_host,
                expect_dest_session=True,
            )
            self._assert_iscsi_lun_visible(
                destination_host, pool, current_volumes
            )
            for path in sources:
                result = self.__class__._ssh_output(
                    destination_host,
                    "test -b %s && echo BLOCK_DEVICE || true"
                    % shlex.quote(path),
                )
                if path.startswith("/dev"):
                    self.assertIn(
                        "BLOCK_DEVICE", result,
                        "dumpxml iSCSI disk %s is not a block device" % path,
                    )

    def _assert_nfs_pool_active_on_scoped_hosts(self, pool):
        hosts = self._scoped_nfs_hosts(pool)
        self.assertTrue(
            hosts,
            "No eligible KVM host exists for NFS pool %s" % pool.id,
        )
        pool_id = shlex.quote(str(pool.id))
        for host in hosts:
            output = self.__class__._ssh_output(
                host,
                "virsh pool-info %s 2>/dev/null || true" % pool_id,
            )
            self.assertIn(
                "state:", output.lower(),
                "NFS pool %s is not defined on host %s"
                % (pool.id, host.id),
            )
            self.assertIn(
                "running", output.lower(),
                "NFS pool %s is not active on host %s"
                % (pool.id, host.id),
            )

    @classmethod
    def _host_volume_snapshot(cls, volume):
        tokens = {
            str(value) for value in (
                volume.id,
                getattr(volume, "path", None),
                getattr(volume, "name", None),
            ) if value
        }
        pattern = "|".join(re.escape(token) for token in sorted(tokens))
        if not pattern:
            return {}
        command = (
            "echo '---XML---'; "
            "for domain in $(virsh list --name); do "
            "virsh dumpxml \"$domain\" 2>/dev/null; "
            "done | grep -E \"source (file|dev)=\" || true; "
            "echo '---REFS---'; "
            "for domain in $(virsh list --name); do "
            "printf 'DOMAIN=%%s\\n' \"$domain\"; "
            "virsh dumpxml \"$domain\" 2>/dev/null; "
            "done | grep -E %s || true; "
            "echo '---SESSIONS---'; "
            "iscsiadm -m session 2>/dev/null || true"
        ) % shlex.quote(pattern)
        return {
            host.id: cls._ssh_output(host, command)
            for host in cls.ready_hosts
        }

    def _assert_volume_not_referenced_on_hosts(self, volume):
        for host_id, output in (
                self.__class__._host_volume_snapshot(volume).items()):
            refs = ""
            if "---REFS---" in output:
                refs = output.split("---REFS---", 1)[1]
                if "---SESSIONS---" in refs:
                    refs = refs.split("---SESSIONS---", 1)[0]
            self.assertEqual(
                refs.strip(),
                "",
                "Volume %s is referenced by a running domain on host %s"
                % (volume.id, host_id),
            )

    def _assert_offline_host_state(
            self, protocol, pool, vm=None, volumes=None):
        current_volumes = [
            self._get_volume(volume.id) for volume in (volumes or [])
        ]
        if vm is not None:
            self._assert_vm_not_running_on_hosts(vm)
        if protocol.upper() == "NFS3":
            self._assert_nfs_pool_active_on_scoped_hosts(pool)
            hosts = self._scoped_nfs_hosts(pool)
            for volume in current_volumes:
                self._assert_nfs_mount_and_file(hosts[0], pool, volume)
        else:
            self._assert_iscsi_sessions(
                pool, current_volumes, expect_dest_session=False
            )
        for volume in current_volumes:
            self._assert_volume_not_referenced_on_hosts(volume)

    @classmethod
    def tearDownClass(cls):
        old_timeout = getattr(
            getattr(cls, "apiClient", None) and cls.apiClient.connection,
            "asyncTimeout",
            None,
        )
        if old_timeout is not None:
            cls.apiClient.connection.asyncTimeout = 60
        try:
            cls._expunge_account_vms()
        except Exception as exc:
            logger.warning("Could not expunge leftover VMs: %s", exc)
        for volume in reversed(cls.created_volumes or []):
            try:
                cmd = deleteVolumeAPI.deleteVolumeCmd()
                cmd.id = volume.id
                cls.apiClient.deleteVolume(cmd)
            except Exception:
                pass

        if os.environ.get("ONTAP_MIGRATION_KEEP_POOLS") != "1":
            try:
                pools = list_storage_pools(
                    cls.apiClient, zoneid=cls.zone.id
                ) or []
            except Exception as exc:
                logger.warning(
                    "Could not list migration pools for cleanup: %s", exc
                )
                pools = []
            migration_pools = {
                pool.id: pool for pool in pools
                if str(getattr(pool, "name", "")).startswith(
                    "OntapMigration"
                )
            }
            for pool in cls.created_pools or []:
                migration_pools[pool.id] = pool

            for pool in migration_pools.values():
                if str(
                        getattr(pool, "type", "")
                ) == "NetworkFilesystem":
                    cls._cleanup_kvm_storage_pool_mounts(pool.id)

            for pool in reversed(list(migration_pools.values())):
                try:
                    cls._delete_extra_pool(pool)
                except Exception as exc:
                    logger.warning(
                        "Could not clean pool %s: %s", pool.id, exc
                    )
                    try:
                        cls.ontap.delete_volume(pool.name)
                        cls.ontap.delete_export_policy(
                            "cs-%s-%s" % (cls.svm_name, pool.name)
                        )
                    except Exception as ontap_exc:
                        logger.warning(
                            "Could not directly clean ONTAP pool %s: %s",
                            pool.name, ontap_exc
                        )

        cls.pool = None
        cls.pool2 = None
        try:
            super(OntapMigrationTestBase, cls).tearDownClass()
            if cls._created_network_id:
                try:
                    cmd = deleteNetworkAPI.deleteNetworkCmd()
                    cmd.id = cls._created_network_id
                    cls.apiClient.deleteNetwork(cmd)
                except Exception as exc:
                    logger.warning(
                        "Could not clean network %s: %s",
                        cls._created_network_id, exc
                    )
        finally:
            if old_timeout is not None:
                cls.apiClient.connection.asyncTimeout = old_timeout

    @classmethod
    def _list_vm_for_cleanup(cls, vm_id):
        cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
        cmd.id = vm_id
        cmd.listall = True
        vms = cls.apiClient.listVirtualMachines(cmd) or []
        return vms[0] if vms else None

    @classmethod
    def _expunge_account_vms(cls):
        vms = list(reversed(cls.created_vms or []))
        try:
            cmd = listVirtualMachinesAPI.listVirtualMachinesCmd()
            cmd.listall = True
            if getattr(cls, "account", None) is not None:
                cmd.account = cls.account.name
                cmd.domainid = cls.domain.id
            listed = cls.apiClient.listVirtualMachines(cmd) or []
            known_ids = {getattr(vm, "id", None) for vm in vms}
            vms.extend(
                vm for vm in listed if getattr(vm, "id", None) not in known_ids
            )
        except Exception as exc:
            logger.warning("Could not list VMs for cleanup: %s", exc)
        cleanup_states = ("stopped", "destroyed", "expunging", "error")
        skip_stop_states = cleanup_states + ("starting", "migrating")
        for vm in vms:
            try:
                current = cls._list_vm_for_cleanup(vm.id)
                if (current
                        and str(current.state).lower() not in skip_stop_states):
                    stop_cmd = stopVirtualMachineAPI.stopVirtualMachineCmd()
                    stop_cmd.id = vm.id
                    stop_cmd.forced = True
                    cls.apiClient.stopVirtualMachine(stop_cmd)
                destroy_cmd = (
                    destroyVirtualMachineAPI.destroyVirtualMachineCmd()
                )
                destroy_cmd.id = vm.id
                destroy_cmd.expunge = True
                cls.apiClient.destroyVirtualMachine(destroy_cmd)
            except Exception as exc:
                logger.warning("Could not clean VM %s: %s", vm.id, exc)

    @classmethod
    def _delete_extra_pool(cls, pool):
        from marvin.cloudstackAPI import (
            deleteStoragePool as deleteStoragePoolAPI,
            enableStorageMaintenance,
        )
        pools = list_storage_pools(cls.apiClient, id=pool.id) or []
        if pools and getattr(pools[0], "state", None) in ("Up", "Disabled"):
            maintenance_cmd = (
                enableStorageMaintenance.enableStorageMaintenanceCmd()
            )
            maintenance_cmd.id = pool.id
            cls.apiClient.enableStorageMaintenance(maintenance_cmd)
            deadline = time.time() + 120
            while time.time() < deadline:
                pools = list_storage_pools(cls.apiClient, id=pool.id) or []
                if pools and pools[0].state == "Maintenance":
                    break
                time.sleep(5)
        cmd = deleteStoragePoolAPI.deleteStoragePoolCmd()
        cmd.id = pool.id
        cmd.forced = True
        cls.apiClient.deleteStoragePool(cmd)

    @classmethod
    def _pool_has_volumes(cls, pool):
        cmd = listVolumesAPI.listVolumesCmd()
        cmd.storageid = pool.id
        cmd.listall = True
        return bool(cls.apiClient.listVolumes(cmd))

    def _release_idle_pools(self, protocol, keep_pool=None):
        """Delete FlexVols the suite is finished with, then recreate empty ones.

        The lab aggregates are about 24 GB. Live migration keeps the source
        copy until the destination copy finishes, so idle template caches
        from earlier hops have to be removed first.
        """
        cls = self.__class__
        keep_id = None if keep_pool is None else keep_pool.id
        for pools in cls.ontap_pools.get(protocol, {}).values():
            for pool in list(pools):
                if keep_id is not None and pool.id == keep_id:
                    continue
                if cls._pool_has_volumes(pool):
                    logger.info(
                        "Keeping %s pool %s because a volume is still on it",
                        protocol, pool.name,
                    )
                    continue
                try:
                    cls._delete_extra_pool(pool)
                except Exception as exc:
                    logger.warning(
                        "Could not release idle %s pool %s: %s",
                        protocol, getattr(pool, "name", pool.id), exc,
                    )
                cls.created_pools = [
                    item for item in cls.created_pools
                    if item.id != pool.id
                ]
        cls.ontap_pools = cls._ensure_migration_pools()
