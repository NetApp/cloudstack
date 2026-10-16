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

"""KVM host SSH helpers for ONTAP Marvin tests.

Host-side checks (libvirt, qemu-img, NFS mount cleanup) live here so
``ontap_test_base`` stays focused on CloudStack and ONTAP REST helpers.
"""

import json
import logging
import shlex

from marvin.sshClient import SshClient

logger = logging.getLogger("kvm_host")


def cleanup_kvm_storage_pool_mounts(kvm_hosts_ssh_creds, pool_uuid):
    """Unmount and undefine the libvirt pool for ``pool_uuid`` on each host.

    Call this before the ONTAP FlexVol is deleted so the unmount finishes
    while the NFS export is still reachable. Stale NFS mounts can make
    KVMHAMonitor reboot the host.
    """
    for creds in kvm_hosts_ssh_creds:
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
                        "cleanup_kvm_storage_pool_mounts: cmd '%s' "
                        "failed on %s: %s" % (cmd, host_ip, cmd_ex)
                    )
        except Exception as ex:
            logger.warning(
                "cleanup_kvm_storage_pool_mounts: SSH to %s failed: %s"
                % (host_ip, ex)
            )


def get_qcow2_virtual_size(kvm_hosts_ssh_creds, pool_uuid, volume_path):
    """Read a qcow2 virtual size from a KVM host mounting the NFS pool."""
    pool_arg = shlex.quote(str(pool_uuid))
    volume_arg = shlex.quote(str(volume_path))
    command = (
        "path=$(virsh vol-path --pool {pool} {volume} 2>/dev/null || true); "
        "if [ -z \"$path\" ]; then path=/mnt/{pool}/{volume}; fi; "
        "qemu_img=$(command -v cloud-qemu-img || command -v qemu-img); "
        "test -n \"$qemu_img\" || "
        "{{ echo 'qemu-img executable not found' >&2; exit 1; }}; "
        "\"$qemu_img\" info -U --output=json \"$path\""
    ).format(pool=pool_arg, volume=volume_arg)

    errors = []
    for creds in kvm_hosts_ssh_creds:
        try:
            ssh = SshClient(
                creds["host"], 22, creds["user"], creds["password"],
                retries=3, delay=3, timeout=15.0,
            )
            output = "\n".join(ssh.execute(command))
            json_start = output.find("{")
            if json_start < 0:
                raise ValueError(output)
            info, _ = json.JSONDecoder().raw_decode(output[json_start:])
            return int(info["virtual-size"])
        except Exception as exc:
            errors.append("%s: %s" % (creds.get("host", "?"), exc))

    raise RuntimeError(
        "Could not read qcow2 virtual size from any KVM host: %s"
        % "; ".join(errors)
    )


def get_vm_data_disk_capacity(kvm_hosts_ssh_creds, instance_name):
    """Return the libvirt capacity of the VM's first data disk in bytes.

    The ONTAP VM workflow attaches exactly one data disk. The first libvirt
    disk is the root disk and the second is the resized data disk, whether
    its source is NFS or iSCSI multipath.
    """
    instance_arg = shlex.quote(str(instance_name))
    command = (
        "target=$(virsh domblklist {vm} --details | "
        "awk '$2 == \"disk\" {{ count++; if (count == 2) "
        "{{ print $3; exit }} }}'); "
        "test -n \"$target\" || "
        "{{ echo 'data disk target not found' >&2; exit 1; }}; "
        "virsh domblkinfo {vm} \"$target\" | "
        "awk '$1 == \"Capacity:\" {{ print $2; exit }}'"
    ).format(vm=instance_arg)

    errors = []
    for creds in kvm_hosts_ssh_creds:
        try:
            ssh = SshClient(
                creds["host"], 22, creds["user"], creds["password"],
                retries=3, delay=3, timeout=15.0,
            )
            output = "\n".join(ssh.execute(command)).strip()
            if not output:
                raise ValueError("virsh returned no data-disk capacity")
            return int(output.splitlines()[-1].strip())
        except Exception as exc:
            errors.append("%s: %s" % (creds.get("host", "?"), exc))

    raise RuntimeError(
        "Could not read VM data-disk capacity from any KVM host: %s"
        % "; ".join(errors)
    )
