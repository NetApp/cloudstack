#!/usr/bin/env python3
#
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

"""Revert one vCenter VM to an exact, named snapshot.

Purpose:
    Find the VM by name, revert to a uniquely named snapshot, optionally power on.

Dependencies:
    Python packages: pyVim / pyVmomi (pyvmomi).
    Environment: VCENTER_USER, VCENTER_PASSWORD.
    CLI: --host, --vm, --snapshot; optional --port, --timeout, --power-on, --insecure.
    Called by Jenkins before run-phase2-remote.sh. Does not SSH or install packages.
"""

import argparse
import atexit
import os
import ssl
import sys
import time

from pyVim.connect import Disconnect, SmartConnect
from pyVmomi import vim


def wait_for_task(task, description, timeout):
    deadline = time.monotonic() + timeout
    while task.info.state in (vim.TaskInfo.State.queued, vim.TaskInfo.State.running):
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Timed out while waiting to {description}")
        time.sleep(2)

    if task.info.state != vim.TaskInfo.State.success:
        raise RuntimeError(f"Failed to {description}: {task.info.error}")


def find_vm(content, vm_name):
    view = content.viewManager.CreateContainerView(
        content.rootFolder, [vim.VirtualMachine], True
    )
    try:
        matches = [vm for vm in view.view if vm.name == vm_name]
    finally:
        view.Destroy()

    if not matches:
        raise ValueError(f"vCenter VM not found: {vm_name}")
    if len(matches) > 1:
        raise ValueError(f"More than one vCenter VM has the name: {vm_name}")
    return matches[0]


def find_snapshot(snapshot_roots, snapshot_name):
    matches = []

    def visit(nodes):
        for node in nodes:
            if node.name == snapshot_name:
                matches.append(node.snapshot)
            visit(node.childSnapshotList)

    visit(snapshot_roots)
    if not matches:
        raise ValueError(f"Snapshot not found: {snapshot_name}")
    if len(matches) > 1:
        raise ValueError(f"More than one snapshot has the name: {snapshot_name}")
    return matches[0]


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--vm", required=True)
    parser.add_argument("--snapshot", required=True)
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--power-on", action="store_true")
    parser.add_argument(
        "--insecure",
        action="store_true",
        help="Disable TLS certificate verification for an internal lab vCenter.",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    username = os.environ.get("VCENTER_USER")
    password = os.environ.get("VCENTER_PASSWORD")
    if not username or not password:
        raise ValueError("VCENTER_USER and VCENTER_PASSWORD must be set")

    context = None
    if args.insecure:
        context = ssl._create_unverified_context()  # pylint: disable=protected-access

    service_instance = SmartConnect(
        host=args.host,
        user=username,
        pwd=password,
        port=args.port,
        sslContext=context,
    )
    atexit.register(Disconnect, service_instance)

    vm = find_vm(service_instance.RetrieveContent(), args.vm)
    if vm.snapshot is None:
        raise ValueError(f"VM has no snapshots: {args.vm}")

    snapshot = find_snapshot(vm.snapshot.rootSnapshotList, args.snapshot)
    print(f"Reverting VM '{args.vm}' to snapshot '{args.snapshot}'")
    wait_for_task(
        snapshot.RevertToSnapshot_Task(),
        f"revert '{args.vm}' to '{args.snapshot}'",
        args.timeout,
    )

    if args.power_on and vm.runtime.powerState != vim.VirtualMachinePowerState.poweredOn:
        print(f"Powering on VM '{args.vm}'")
        wait_for_task(vm.PowerOnVM_Task(), f"power on '{args.vm}'", args.timeout)

    print("Snapshot revert completed")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # pylint: disable=broad-except
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
