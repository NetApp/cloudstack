#!/usr/bin/env python3
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

"""Build runtime ontap.cfg and secrets.json from inventory plus credential env vars.

Purpose:
    Load one enabled VM from the inventory YAML, reject PLACEHOLDER-* values, and
    write Marvin ontap.cfg JSON plus a secrets file (mode 0600).

Dependencies:
    Python packages: PyYAML. Stdlib: argparse, json, os, pathlib.
    CLI: --inventory, --vm-id, --ontap-config, --secrets.
    Environment: CLOUD_DB_USER, CLOUD_DB_PASSWORD, MYSQL_ROOT_USER, MYSQL_ROOT_PASSWORD,
    KVM_HOST_USER, KVM_HOST_PASSWORD, ONTAP_USER, ONTAP_PASSWORD,
    CLOUDSTACK_ADMIN_USER, CLOUDSTACK_ADMIN_PASSWORD.
    Called by run-phase2-remote.sh. Does not talk to vCenter or the VM.
"""

import argparse
import json
import os
from pathlib import Path

import yaml


def required_env(name):
    value = os.environ.get(name)
    if value is None:
        raise RuntimeError(f"Required environment variable is missing: {name}")
    return value


def reject_placeholders(value, path="inventory"):
    if isinstance(value, dict):
        for key, child in value.items():
            reject_placeholders(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_placeholders(child, f"{path}[{index}]")
    elif isinstance(value, str) and value.startswith("PLACEHOLDER-"):
        raise RuntimeError(f"Unresolved placeholder at {path}: {value}")


def get_vm(inventory_path, vm_id):
    with inventory_path.open(encoding="utf-8") as stream:
        inventory = yaml.safe_load(stream) or {}
    matches = [
        vm for vm in inventory.get("vms", [])
        if vm.get("id") == vm_id and vm.get("enabled", True)
    ]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one enabled inventory VM named {vm_id}")
    vm = matches[0]
    reject_placeholders(vm)
    return vm


def build_config(vm):
    ips = vm["dedicated_ips"]
    zone = vm["zone"]
    ontap = vm["ontap"]
    management_ip = ips["management_server"]
    kvm_ip = ips["kvm_host"]

    return {
        "zones": [{
            "name": zone["name"],
            "networktype": zone.get("network_type", "Advanced"),
            "dns1": zone["dns1"],
            "dns2": zone.get("dns2", ""),
            "internaldns1": zone["internal_dns1"],
            "internaldns2": zone.get("internal_dns2", ""),
            "localstorageenabled": True,
            "guestcidraddress": zone.get("guest_cidr", "10.1.1.0/24"),
            "guestVlanRange": zone["guest_vlan_range"],
            "publicIpRange": {
                "gateway": ips["public"]["gateway"],
                "netmask": ips["public"]["netmask"],
                "startip": ips["public"]["start"],
                "endip": ips["public"]["end"],
                "vlan": ips["public"].get("vlan", "untagged"),
            },
            "secondaryStorages": [{
                "name": "Secondary1",
                "provider": "NFS",
                "url": zone["secondary_storage_url"],
            }],
            "pods": [{
                "name": zone.get("pod_name", "Pod1"),
                "gateway": ips["pod"]["gateway"],
                "netmask": ips["pod"]["netmask"],
                "startip": ips["pod"]["start"],
                "endip": ips["pod"]["end"],
                "clusters": [{
                    "clustername": zone.get("cluster_name", "Cluster1"),
                    "clustertype": "CloudManaged",
                    "hypervisor": "KVM",
                    "primaryStorages": [{
                        "name": "Primary1",
                        "scope": "Cluster",
                        "url": zone["primary_storage_url"],
                        "provider": "DefaultPrimary",
                        "tags": "defaultPrim",
                    }],
                    "hosts": [{
                        "url": f"http://{kvm_ip}",
                        "username": required_env("KVM_HOST_USER"),
                        "password": required_env("KVM_HOST_PASSWORD"),
                        "hosttags": "kvmHost",
                    }],
                }],
            }],
        }],
        "dbSvr": {
            "dbSvr": "127.0.0.1",
            "passwd": required_env("CLOUD_DB_PASSWORD"),
            "db": "cloud",
            "port": 3306,
            "user": required_env("CLOUD_DB_USER"),
        },
        "logger": {"LogFolderPath": "/tmp/"},
        "mgtSvr": [{
            "mgtSvrIp": management_ip,
            "port": 8096,
            "user": required_env("CLOUDSTACK_ADMIN_USER"),
            "passwd": required_env("CLOUDSTACK_ADMIN_PASSWORD"),
            "hypervisor": "kvm",
        }],
        "ontap": {
            "storageIP": ontap["storage_ip"],
            "svmName": ontap["svm_name"],
            "username": required_env("ONTAP_USER"),
            "password": required_env("ONTAP_PASSWORD"),
        },
        "storagePool": {
            "storagePoolScope": "CLUSTER",
            "storagePoolProvider": "NetApp ONTAP",
            "capacitybytes": None,
            "protocols": {
                "iscsi": {
                    "enabled": True,
                    "storagePoolTags": "ontap-iscsi",
                },
                "nfs3": {
                    "enabled": True,
                    "storagePoolTags": "ontap-nfs3",
                },
            },
        },
        "cloudstack": {
            "zoneName": zone["name"],
            "clusterName": zone.get("cluster_name"),
            "domainName": "ROOT",
            "templateName": zone["template_name"],
            "systemVmTemplateUrl": zone["systemvm_template_url"],
            "systemVmTimeoutSec": 3600,
            "templateReadyTimeoutSec": 3600,
            "pollIntervalSec": 60,
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--vm-id", required=True)
    parser.add_argument("--ontap-config", required=True, type=Path)
    parser.add_argument("--secrets", required=True, type=Path)
    args = parser.parse_args()

    vm = get_vm(args.inventory, args.vm_id)
    config = build_config(vm)
    secrets = {
        "cloudDbUser": required_env("CLOUD_DB_USER"),
        "cloudDbPassword": required_env("CLOUD_DB_PASSWORD"),
        "mysqlRootUser": required_env("MYSQL_ROOT_USER"),
        "mysqlRootPassword": required_env("MYSQL_ROOT_PASSWORD"),
    }

    for output in (args.ontap_config, args.secrets):
        output.parent.mkdir(parents=True, exist_ok=True)
    args.ontap_config.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    args.secrets.write_text(json.dumps(secrets) + "\n", encoding="utf-8")
    args.ontap_config.chmod(0o600)
    args.secrets.chmod(0o600)


if __name__ == "__main__":
    main()
