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
import re
import shlex
import time
import xml.etree.ElementTree as ET

from marvin.sshClient import SshClient

logger = logging.getLogger("kvm_host")

# '6' (NAA IEEE Registered Extended) + NetApp OUI '00a098' + pad nibble '0'.
# Mirrors OntapStorageConstants.NETAPP_NAA_PREFIX in the ONTAP plugin. It is
# deliberately re-implemented here (not read from the plugin) so a mistake in
# the plugin's serial -> WWID mapping is caught by the tests.
NETAPP_NAA_PREFIX = "600a0980"
WWID_RE = re.compile(r"^%s[0-9a-f]{24}$" % NETAPP_NAA_PREFIX)


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


# ---------------------------------------------------------------------------
# Host-side verification helpers (services, libvirt domain, iSCSI devices)
#
# Marvin's SshClient.execute() ignores exit codes and returns stderr only when
# stdout is empty, so every command below merges stderr (2>&1) or prints an
# explicit sentinel and the callers look for that text.
# ---------------------------------------------------------------------------

def naa_from_serial(serial):
    """Return the SCSI WWID the plugin derives from an ONTAP LUN serial number.

    The WWID is NETAPP_NAA_PREFIX followed by the hex of the 12 ASCII serial
    bytes, e.g. serial ``x0M-8?/kEsJT`` ->
    ``600a098078304d2d383f2f6b45734a54``.
    """
    if not serial:
        raise ValueError("ONTAP reported no LUN serial number")
    return NETAPP_NAA_PREFIX + "".join(
        "%02x" % b for b in serial.encode("ascii"))


def by_id_paths(wwid):
    """The two /dev/disk/by-id aliases udev creates for a NetApp LUN WWID."""
    return ["/dev/disk/by-id/wwn-0x%s" % wwid,
            "/dev/disk/by-id/scsi-3%s" % wwid]


def _creds_for(kvm_hosts_ssh_creds, host_ip):
    for creds in kvm_hosts_ssh_creds:
        if creds.get("host") == host_ip:
            return creds
    raise RuntimeError("No SSH credentials for KVM host %s" % host_ip)


def run_on_host(kvm_hosts_ssh_creds, host_ip, command, timeout=20.0):
    """Run one command on a KVM host and return its output lines."""
    creds = _creds_for(kvm_hosts_ssh_creds, host_ip)
    ssh = SshClient(
        host_ip, 22, creds["user"], creds["password"],
        retries=3, delay=3, timeout=timeout,
    )
    return ssh.execute(command) or []


def service_state(kvm_hosts_ssh_creds, host_ip, unit):
    """``systemctl is-active`` for a unit ('active', 'inactive', 'failed')."""
    out = run_on_host(
        kvm_hosts_ssh_creds, host_ip,
        "systemctl is-active %s 2>&1; true" % shlex.quote(unit))
    return out[0].strip() if out else "unknown"


def virsh_responsive(kvm_hosts_ssh_creds, host_ip):
    """True when libvirt answers a plain ``virsh list`` within 15 seconds."""
    out = run_on_host(
        kvm_hosts_ssh_creds, host_ip,
        "timeout 15 virsh list >/dev/null 2>&1 "
        "&& echo VIRSH_OK || echo VIRSH_FAIL")
    return "VIRSH_OK" in "".join(out)


def domain_state(kvm_hosts_ssh_creds, host_ip, instance_name):
    """libvirt state of a domain: 'running', 'shut off', ... or 'absent'.

    CloudStack defines its guests as transient domains, so a stopped or
    destroyed VM is 'absent' rather than 'shut off'.
    """
    out = run_on_host(
        kvm_hosts_ssh_creds, host_ip,
        "timeout 15 virsh domstate %s 2>&1; true"
        % shlex.quote(str(instance_name)))
    text = " ".join(line.strip() for line in out).strip()
    lowered = text.lower()
    if "failed to get domain" in lowered or "domain not found" in lowered:
        return "absent"
    return text


def domain_xml(kvm_hosts_ssh_creds, host_ip, instance_name):
    """Return the parsed ``virsh dumpxml`` root element, or None if absent."""
    out = run_on_host(
        kvm_hosts_ssh_creds, host_ip,
        "timeout 15 virsh dumpxml %s 2>&1; true"
        % shlex.quote(str(instance_name)))
    text = "\n".join(out)
    start = text.find("<domain")
    if start < 0:
        return None
    return ET.fromstring(text[start:])


def parse_domain_disks(domain_root):
    """One dict per ``<disk>`` of a libvirt domain XML (any device type)."""
    disks = []
    for disk in domain_root.findall("./devices/disk"):
        driver = disk.find("driver")
        source = disk.find("source")
        target = disk.find("target")
        disks.append({
            "device": disk.get("device"),
            "type": disk.get("type"),
            "driver_name": driver.get("name") if driver is not None else None,
            "driver_type": driver.get("type") if driver is not None else None,
            "cache": driver.get("cache") if driver is not None else None,
            "source_dev": source.get("dev") if source is not None else None,
            "source_file": source.get("file") if source is not None else None,
            "target_dev": target.get("dev") if target is not None else None,
            "target_bus": target.get("bus") if target is not None else None,
            "serial": disk.findtext("serial"),
        })
    return disks


def device_present(kvm_hosts_ssh_creds, host_ip, wwid):
    """True when a block device for the WWID exists under /dev/disk/by-id."""
    wwn, scsi = by_id_paths(wwid)
    out = run_on_host(
        kvm_hosts_ssh_creds, host_ip,
        "test -b %s -o -b %s && echo DEV_YES || echo DEV_NO"
        % (shlex.quote(wwn), shlex.quote(scsi)))
    return "DEV_YES" in "".join(out)


def wait_device(kvm_hosts_ssh_creds, host_ip, wwid, present,
                timeout=90, interval=3):
    """Poll until the WWID's device appears/disappears; return the final state.

    udev and the plugin's SCSI rescan/removal are asynchronous, so a single
    immediate check would be flaky.
    """
    deadline = time.time() + timeout
    state = device_present(kvm_hosts_ssh_creds, host_ip, wwid)
    while state != present and time.time() < deadline:
        time.sleep(interval)
        state = device_present(kvm_hosts_ssh_creds, host_ip, wwid)
    return state


_SESSION_RE = re.compile(
    r"^\w+:\s*\[(\d+)\]\s+([^,\s]+):(\d+),(\d+)\s+(\S+)")


def iscsi_sessions(kvm_hosts_ssh_creds, host_ip):
    """Parse ``iscsiadm -m session`` into dicts (sid, portal_ip, port, target).

    Returns [] when there are no sessions.
    """
    out = run_on_host(
        kvm_hosts_ssh_creds, host_ip, "iscsiadm -m session 2>&1; true")
    sessions = []
    for line in out:
        match = _SESSION_RE.match(line.strip())
        if match:
            sessions.append({
                "sid": match.group(1),
                "portal_ip": match.group(2),
                "port": match.group(3),
                "target": match.group(5),
            })
    return sessions
