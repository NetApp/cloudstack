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
SSH access to the guest VMs deployed by the ONTAP VM workflow tests
(``*/instance/test_vm_volume_attach.py``).

The template is CentOS 5.5; its login comes from ontap.cfg
(``cloudstack.templateUsername`` / ``cloudstack.templatePassword``, default
root/password).  Guests sit on an isolated network, so each VM gets a
port-forwarding rule (public port -> 22) on the network's source-NAT IP.
Markers are plain files written and read with paramiko.
"""

import logging
import random
import time
import uuid

import paramiko

from marvin.cloudstackAPI import (
    createPortForwardingRule as createPortForwardingRuleAPI,
    deletePortForwardingRule as deletePortForwardingRuleAPI,
    listPublicIpAddresses as listPublicIpAddressesAPI,
)

logger = logging.getLogger("OntapGuestSsh")

ROOT_MARKER = "/root/ontap-marker"
DATA_MOUNT = "/mnt/data"
DATA_MARKER = DATA_MOUNT + "/ontap-marker"

_DATA_MOUNT_CMD = (
    "mkdir -p {m}; "
    "mountpoint -q {m} 2>/dev/null || grep -q ' {m} ' /proc/mounts || "
    "mount /dev/vdb {m} 2>/dev/null || mount /dev/sdb {m} 2>/dev/null"
).format(m=DATA_MOUNT)

_DATA_FORMAT_CMD = (
    "mkdir -p {m}; "
    "grep -q ' {m} ' /proc/mounts || "
    "mount /dev/vdb {m} 2>/dev/null || mount /dev/sdb {m} 2>/dev/null || "
    "{{ if [ -b /dev/vdb ]; then d=/dev/vdb; else d=/dev/sdb; fi; "
    "mkfs.ext3 -F -q $d && mount $d {m}; }}"
).format(m=DATA_MOUNT)


def check_paramiko():
    """Fail fast when paramiko cannot talk to the CentOS 5.5 guest sshd."""
    if "diffie-hellman-group14-sha1" not in paramiko.Transport._kex_info:
        raise GuestSshError(
            "paramiko %s has no SHA-1 key exchange, which the CentOS 5.5 guest "
            "requires. Run test/integration/plugins/ontap/setup_env.sh and use "
            "its .venv (it pins paramiko<4)." % paramiko.__version__
        )


def guest_credentials(config):
    """Return (username, password) for the template from ontap.cfg."""
    cs_cfg = config.get("cloudstack", {})
    return (cs_cfg.get("templateUsername", "root"),
            cs_cfg.get("templatePassword", "password"))


def new_token(label="snapshot-test"):
    """Return readable, unique text to write into a snapshot test file."""
    readable_label = "".join(
        char if char.isalnum() or char in ("-", "_") else "-"
        for char in label
    ).strip("-")
    return "%s-%s" % (readable_label or "snapshot-test",
                      uuid.uuid4().hex[:12])


class GuestSshError(Exception):
    """SSH to the guest or a guest command failed."""


class GuestSsh(object):
    """Run shell commands on a guest as root over SSH."""

    def __init__(self, host, port=22, username="root", password="password",
                 connect_timeout=600, retry_interval=10):
        self.host = host
        self.port = int(port)
        self.username = username
        self.password = password
        self.connect_timeout = connect_timeout
        self.retry_interval = retry_interval
        self._ssh = None

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False

    def connect(self):
        """Connect, retrying until the guest's sshd is up after boot."""
        check_paramiko()
        deadline = time.time() + self.connect_timeout
        last_error = None
        while time.time() < deadline:
            ssh = paramiko.SSHClient()
            ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            try:
                ssh.connect(self.host, port=self.port, username=self.username,
                            password=self.password, timeout=15,
                            allow_agent=False, look_for_keys=False)
                self._ssh = ssh
                logger.info("SSH connected to %s:%d", self.host, self.port)
                return
            except Exception as exc:
                last_error = exc
                ssh.close()
                time.sleep(self.retry_interval)
        raise GuestSshError("SSH to %s:%d failed within %ds: %s"
                            % (self.host, self.port, self.connect_timeout, last_error))

    def close(self):
        if self._ssh is not None:
            self._ssh.close()
            self._ssh = None

    def run(self, command, check=True, timeout=300):
        """Run a command; return stdout.  Raise on non-zero exit if check."""
        if self._ssh is None:
            self.connect()
        _, stdout, stderr = self._ssh.exec_command(command, timeout=timeout)
        status = stdout.channel.recv_exit_status()
        out = stdout.read().decode("utf-8", "replace")
        err = stderr.read().decode("utf-8", "replace")
        if check and status != 0:
            raise GuestSshError("'%s' on %s exited %d: %s"
                                % (command, self.host, status, err.strip()))
        return out

    def write_root_marker(self, token):
        self.run("echo '%s' > %s && sync" % (token, ROOT_MARKER))

    def read_root_marker(self):
        return self.run("cat %s 2>/dev/null; true" % ROOT_MARKER).strip()

    def write_data_marker(self, token):
        self.run(_DATA_FORMAT_CMD)
        self.run("echo '%s' > %s && sync" % (token, DATA_MARKER))

    def read_data_marker(self):
        self.run(_DATA_MOUNT_CMD, check=False)
        return self.run("cat %s 2>/dev/null; true" % DATA_MARKER).strip()


# ---------------------------------------------------------------------------
# Port forwarding
# ---------------------------------------------------------------------------

def source_nat_ip(apiclient, network_id):
    """Return the source-NAT public IP record for an isolated network."""
    cmd = listPublicIpAddressesAPI.listPublicIpAddressesCmd()
    cmd.associatednetworkid = network_id
    cmd.issourcenat = True
    cmd.listall = True
    ips = apiclient.listPublicIpAddresses(cmd) or []
    if not ips:
        raise GuestSshError("No source-NAT IP found for network %s" % network_id)
    return ips[0]


def open_ssh_port(apiclient, vm_id, network_id, public_port=None):
    """Forward a public port on the source-NAT IP to the VM's port 22.

    Returns (public_ip, public_port, rule_id).
    """
    ip = source_nat_ip(apiclient, network_id)
    last_error = None
    for _ in range(10):
        port = public_port or random.randint(20000, 29999)
        cmd = createPortForwardingRuleAPI.createPortForwardingRuleCmd()
        cmd.ipaddressid = ip.id
        cmd.privateport = 22
        cmd.publicport = port
        cmd.protocol = "TCP"
        cmd.virtualmachineid = vm_id
        cmd.openfirewall = True
        try:
            rule = apiclient.createPortForwardingRule(cmd)
            return ip.ipaddress, port, rule.id
        except Exception as exc:
            last_error = exc
            if public_port:
                break
    raise GuestSshError("Could not create SSH port forward for VM %s: %s"
                        % (vm_id, last_error))


def close_ssh_port(apiclient, rule_id):
    cmd = deletePortForwardingRuleAPI.deletePortForwardingRuleCmd()
    cmd.id = rule_id
    apiclient.deletePortForwardingRule(cmd)
