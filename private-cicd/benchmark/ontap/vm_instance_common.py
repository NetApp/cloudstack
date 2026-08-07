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
"""
Shared helpers for the VM-instance benchmark scripts
(benchmark_vm_instance_sequential.py / benchmark_vm_instance_concurrency.py /
benchmark_vm_instance_combined.py).

Kept in one module (rather than duplicated per script) so the
deployVirtualMachine/destroyVirtualMachine call shape, CSV formats, and
cleanup logic can't drift between the three - only the sequential-vs-
concurrent-vs-both driving logic differs per script.
"""

import csv
import dataclasses
import datetime
import os
import random
import re
import sys
import time

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required: pip install -r requirements.txt")

from cloudstack_client import CloudStackAPIError, CloudStackClient

RAW_FIELDNAMES = [
    "run_id", "phase", "test_id", "protocol", "scale_or_concurrency",
    "index", "vm_name", "vm_id", "success", "duration_sec",
    "start_ts", "end_ts", "error",
]

SUMMARY_FIELDNAMES = [
    "run_id", "phase", "test_id", "protocol", "checkpoint",
    "total_time_sec", "avg_time_sec", "success_count", "failure_count", "notes",
]


@dataclasses.dataclass
class OpResult:
    success: bool
    duration_sec: float
    start_ts: str
    end_ts: str
    vm_name: str
    vm_id: str = None
    error: str = None


def now_iso():
    return datetime.datetime.utcnow().isoformat(timespec="milliseconds") + "Z"


def new_run_id():
    return datetime.datetime.utcnow().strftime("RUN_%Y%m%d_%H%M%S")


def build_deploy_vm_params(name, infra_cfg, vm_cfg, proto_cfg):
    return {
        "name": name,
        "displayname": name,
        "zoneid": infra_cfg["zoneid"],
        "templateid": vm_cfg["templateid"],
        "networkids": vm_cfg["networkid"],
        "serviceofferingid": proto_cfg["serviceofferingid"],
        "diskofferingid": proto_cfg["diskofferingid"],
        "startvm": "true",
    }


_UUID_RE = re.compile(r'"uuid"\s*:\s*"([0-9a-fA-F-]{36})"')


def _extract_vm_id_from_error(error_text):
    """When deployVirtualMachine's async job fails deep in orchestration (e.g. the
    ENOSPC-driven "Unable to orchestrate the start of VM instance" failures - see
    Confluence Issue #10), CloudStack has ALREADY created the VM (and its ROOT/DATA
    volume records) before the failure - it just never finished starting it. The
    job's errortext embeds that VM's uuid (e.g. '...{"instanceName":"i-2-107-VM",
    "uuid":"b8f4..."}.'), so scrape it out here so the leftover VM/volume can at
    least be identified and reported for manual inspection (see
    report_failed_creates()) instead of being silently invisible."""
    if not error_text:
        return None
    m = _UUID_RE.search(error_text)
    return m.group(1) if m else None


def deploy_vm(client, name, infra_cfg, vm_cfg, proto_cfg):
    start_ts = now_iso()
    t0 = time.perf_counter()
    try:
        payload, elapsed = client.call(
            "deployVirtualMachine", build_deploy_vm_params(name, infra_cfg, vm_cfg, proto_cfg)
        )
        vm = payload.get("virtualmachine", payload) if isinstance(payload, dict) else {}
        vm_id = vm.get("id") if isinstance(vm, dict) else None
        return OpResult(True, elapsed, start_ts, now_iso(), name, vm_id=vm_id)
    except (CloudStackAPIError, TimeoutError, Exception) as exc:  # noqa: BLE001
        elapsed = time.perf_counter() - t0
        error = str(exc)
        return OpResult(False, elapsed, start_ts, now_iso(), name,
                         vm_id=_extract_vm_id_from_error(error), error=error)


def _force_purge_destroy_state_volumes(client, volume_ids, max_wait_sec=10):
    """destroyVirtualMachine(expunge=true) only guarantees the VM/volume DB records
    move to "Destroy" state - actual physical removal from the backing storage
    (ONTAP LUN/file deletion) is deferred to CloudStack's periodic storage-cleanup
    background thread, which by default only *considers* volumes for real deletion
    after `storage.cleanup.delay` (86400s / 24h) has elapsed, even though the
    cleanup thread itself polls every `storage.cleanup.interval` (40s by default).
    Confirmed directly against our management server via listConfigurations.

    That means every "successfully destroyed" VM's ROOT/DATA disks still
    physically occupy space on the ONTAP pool for up to 24h unless something
    calls deleteVolume() on them explicitly - which force-deletes a Destroy-state
    volume immediately, bypassing the delay. Without this, repeated benchmark
    runs progressively fill up the dedicated bench_vm_<protocol> pool with
    "deleted" disks, compounding the ENOSPC failures in Confluence Issue #10.
    """
    deadline = time.time() + max_wait_sec
    remaining = set(volume_ids)
    while remaining and time.time() < deadline:
        still_pending = set()
        for vol_id in remaining:
            try:
                vol_payload, _ = client.call("listVolumes", {"id": vol_id}, poll_async=False)
                vols = vol_payload.get("volume", []) if isinstance(vol_payload, dict) else []
                if not vols:
                    continue  # already fully gone
                state = vols[0].get("state")
                if state == "Destroy":
                    client.call("deleteVolume", {"id": vol_id})
                elif state not in ("Expunged",):
                    still_pending.add(vol_id)  # not yet transitioned to Destroy - retry shortly
            except (CloudStackAPIError, TimeoutError, Exception):  # noqa: BLE001
                still_pending.add(vol_id)
        remaining = still_pending
        if remaining:
            time.sleep(1)


def destroy_vm(client, vm_id, name, expunge=True):
    """destroyVirtualMachine with expunge=true only reclaims the ROOT disk;
    data disks are merely DETACHED (left behind as free-floating DATADISK
    volumes) unless their ids are explicitly passed via `volumeids`. So this
    looks up any attached data disks first and includes them, otherwise every
    VM with a data disk leaks a LUN/file on the backing storage pool.

    It also force-purges any ROOT/DATA volumes still left in "Destroy" state
    afterwards (see _force_purge_destroy_state_volumes) so this VM's disks are
    truly gone from ONTAP before the next benchmark op runs, instead of
    lingering for CloudStack's 24h background cleanup delay.
    """
    start_ts = now_iso()
    t0 = time.perf_counter()
    try:
        vol_payload, _ = client.call(
            "listVolumes", {"virtualmachineid": vm_id}, poll_async=False
        )
        all_vols = vol_payload.get("volume", []) if isinstance(vol_payload, dict) else []
        all_volume_ids = [v["id"] for v in all_vols]
        data_disk_ids = [v["id"] for v in all_vols if v.get("type") == "DATADISK"]

        params = {"id": vm_id, "expunge": "true" if expunge else "false"}
        if data_disk_ids:
            params["volumeids"] = ",".join(data_disk_ids)
        payload, _ = client.call("destroyVirtualMachine", params)

        if expunge and all_volume_ids:
            _force_purge_destroy_state_volumes(client, all_volume_ids)

        elapsed = time.perf_counter() - t0
        success = payload.get("success", True) if isinstance(payload, dict) else True
        return OpResult(bool(success), elapsed, start_ts, now_iso(), name, vm_id=vm_id)
    except (CloudStackAPIError, TimeoutError, Exception) as exc:  # noqa: BLE001
        elapsed = time.perf_counter() - t0
        return OpResult(False, elapsed, start_ts, now_iso(), name, vm_id=vm_id, error=str(exc))


def fake_create(name):
    time.sleep(random.uniform(0.01, 0.05))
    return OpResult(True, random.uniform(3.0, 15.0), now_iso(), now_iso(), name, vm_id=f"dryrun-{name}")


def fake_delete(vm_id, name):
    time.sleep(random.uniform(0.01, 0.05))
    return OpResult(True, random.uniform(2.0, 8.0), now_iso(), now_iso(), name, vm_id=vm_id)


class RawLogger:
    def __init__(self, path):
        self.file = open(path, "a", newline="")
        self.writer = csv.DictWriter(self.file, fieldnames=RAW_FIELDNAMES)
        if self.file.tell() == 0:
            self.writer.writeheader()

    def log(self, run_id, phase, test_id, protocol, scale_or_concurrency, index, result: OpResult):
        self.writer.writerow({
            "run_id": run_id, "phase": phase, "test_id": test_id, "protocol": protocol,
            "scale_or_concurrency": scale_or_concurrency, "index": index,
            "vm_name": result.vm_name, "vm_id": result.vm_id or "",
            "success": result.success, "duration_sec": round(result.duration_sec, 4),
            "start_ts": result.start_ts, "end_ts": result.end_ts, "error": result.error or "",
        })
        self.file.flush()

    def close(self):
        self.file.close()


def append_summary_csv(path, rows):
    """Appends checkpoint rows to the run's summary CSV, writing the header only
    if the file is new/empty. Append (not overwrite) so that running the
    sequential and concurrency scripts separately under the *same* --run-id
    accumulates into one combined summary_vm_<run_id>.csv that render_report.py
    can render as a single report, instead of the second script clobbering the
    first script's rows."""
    is_new = not os.path.exists(path) or os.path.getsize(path) == 0
    with open(path, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=SUMMARY_FIELDNAMES)
        if is_new:
            writer.writeheader()
        writer.writerows(rows)


def report_failed_creates(failed):
    """Failed deployVirtualMachine calls (e.g. the ENOSPC "Unable to orchestrate
    the start of VM instance" failures in Confluence Issue #10) still leave a real
    VM + ROOT/DATA volume record behind in CloudStack - they just never finished
    starting. These are intentionally NOT auto-destroyed here (unlike the
    `created` list, which the benchmark itself deletes as part of 5.2.2/6.2.2) so
    the leftover VM/volume artifacts stay inspectable afterwards - e.g. via
    `listVolumes --state Destroy` on the bench_vm_<protocol> pool - to confirm
    exactly what got left behind and why. Clean them up manually (or via
    --cleanup-only) once you're done inspecting them."""
    failed_with_id = [(name, vm_id) for name, vm_id in failed if vm_id]
    if not failed_with_id:
        return
    print(f"\n=== {len(failed_with_id)} failed-create VM(s) left in place for inspection (not cleaned up) ===")
    for name, vm_id in failed_with_id:
        print(f"  {name} ({vm_id})")


def cleanup_by_filter(client, name_filter):
    payload, _ = client.call("listVirtualMachines", {}, poll_async=False)
    vms = payload.get("virtualmachine", []) if isinstance(payload, dict) else []
    matches = [v for v in vms if name_filter in v.get("name", "")]
    print(f"Found {len(matches)} VM(s) whose name contains '{name_filter}'")
    for v in matches:
        try:
            result = destroy_vm(client, v["id"], v["name"])
            if result.success:
                print(f"  destroyed {v['name']} ({v['id']}) [incl. any data disks]")
            else:
                print(f"  FAILED to destroy {v['name']}: {result.error}")
        except Exception as exc:  # noqa: BLE001
            print(f"  FAILED to destroy {v['name']}: {exc}")

    # Data disks can also end up orphaned (unattached) from prior runs whose
    # VM was already destroyed without this volumeids fix. Volume names (e.g.
    # "DATA-25") don't carry the run id/prefix, so instead of matching
    # name_filter here, sweep any unattached data disk left on our dedicated
    # bench_vm_* pools - nothing else legitimately lives there.
    vol_payload, _ = client.call("listVolumes", {"type": "DATADISK"}, poll_async=False)
    vols = vol_payload.get("volume", []) if isinstance(vol_payload, dict) else []
    orphan_vols = [v for v in vols if not v.get("virtualmachineid") and v.get("storage", "").startswith("bench_vm")]
    if orphan_vols:
        print(f"Found {len(orphan_vols)} orphaned unattached DATADISK volume(s) on matching pools")
        for v in orphan_vols:
            try:
                client.call("deleteVolume", {"id": v["id"]})
                print(f"  deleted orphan volume {v['name']} ({v['id']})")
            except Exception as exc:  # noqa: BLE001
                print(f"  FAILED to delete orphan volume {v['name']}: {exc}")


def load_config(path):
    with open(path) as f:
        return yaml.safe_load(f)


def resolve_protocols(cfg, requested):
    available = list(cfg.get("vm_bench", {}).get("protocols", {}).keys())
    if requested == "both":
        return available
    if requested not in available:
        sys.exit(f"Protocol '{requested}' not found in config.vm_bench.protocols (available: {available})")
    return [requested]


def make_cloudstack_client(cfg):
    cs_cfg = cfg["cloudstack"]
    return CloudStackClient(
        cs_cfg["api_url"], cs_cfg["username"], cs_cfg["password"],
        verify_ssl=cs_cfg.get("verify_ssl", True),
        http_timeout_sec=cs_cfg.get("http_timeout_sec", 30),
        job_timeout_sec=cs_cfg.get("job_timeout_sec", 300),
        job_poll_interval_sec=cs_cfg.get("job_poll_interval_sec", 1.5),
    )
