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
Shared helpers for the storage-pool benchmark scripts
(benchmark_storage_pool_sequential.py / benchmark_storage_pool_concurrency.py).

Kept in one module (rather than duplicated per script) so the createStoragePool/
deleteStoragePool call shape, CSV formats, and cleanup logic can't drift between
the two - only the sequential-vs-concurrent driving logic differs per script.
"""

import csv
import dataclasses
import datetime
import os
import random
import sys
import time

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required: pip install -r requirements.txt")

from cloudstack_client import CloudStackAPIError, CloudStackClient

RAW_FIELDNAMES = [
    "run_id", "phase", "test_id", "protocol", "scale_or_concurrency",
    "index", "pool_name", "pool_id", "success", "duration_sec",
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
    pool_name: str
    pool_id: str = None
    error: str = None


def now_iso():
    return datetime.datetime.utcnow().isoformat(timespec="milliseconds") + "Z"


def new_run_id():
    # ONTAP volume names only allow alphanumeric + underscore (no hyphens), and
    # pool names flow straight through to the ONTAP volume name, so avoid "-" here.
    return datetime.datetime.utcnow().strftime("RUN_%Y%m%d_%H%M%S")


def build_create_pool_params(name, infra_cfg, ontap_cfg):
    params = {
        "name": name,
        "zoneid": infra_cfg["zoneid"],
        "scope": infra_cfg.get("scope", "cluster"),
        "provider": ontap_cfg["provider"],
        "url": ontap_cfg["url"],
        "managed": "true",
        "details[0].username": ontap_cfg["username"],
        "details[0].password": ontap_cfg["password_b64"],
        "details[0].svmName": ontap_cfg["svmName"],
        "details[0].protocol": ontap_cfg["protocol"],
        "details[0].storageIP": ontap_cfg["storageIP"],
    }
    if infra_cfg.get("scope", "cluster") == "cluster":
        params["podid"] = infra_cfg["podid"]
        params["clusterid"] = infra_cfg["clusterid"]
    if ontap_cfg.get("capacitybytes"):
        params["capacitybytes"] = str(ontap_cfg["capacitybytes"])
    if ontap_cfg.get("tags"):
        params["tags"] = ontap_cfg["tags"]
    return params


def create_pool(client, name, infra_cfg, ontap_cfg):
    start_ts = now_iso()
    t0 = time.perf_counter()
    try:
        payload, elapsed = client.call(
            "createStoragePool", build_create_pool_params(name, infra_cfg, ontap_cfg)
        )
        pool = payload.get("storagepool", payload) if isinstance(payload, dict) else {}
        pool_id = pool.get("id") if isinstance(pool, dict) else None
        return OpResult(True, elapsed, start_ts, now_iso(), name, pool_id=pool_id)
    except (CloudStackAPIError, TimeoutError, Exception) as exc:  # noqa: BLE001
        elapsed = time.perf_counter() - t0
        return OpResult(False, elapsed, start_ts, now_iso(), name, error=str(exc))


def delete_pool(client, pool_id, name, forced=True):
    """deleteStoragePool requires the pool to already be in Maintenance state
    (CloudStack error 431 otherwise), so this enables maintenance first and
    waits for that async job before issuing the actual delete. Both steps are
    included in the reported duration since that is the real wall-clock cost
    of retiring a pool."""
    start_ts = now_iso()
    t0 = time.perf_counter()
    try:
        client.call("enableStorageMaintenance", {"id": pool_id})
        payload, _ = client.call(
            "deleteStoragePool", {"id": pool_id, "forced": "true" if forced else "false"}
        )
        elapsed = time.perf_counter() - t0
        success = payload.get("success", True) if isinstance(payload, dict) else True
        return OpResult(bool(success), elapsed, start_ts, now_iso(), name, pool_id=pool_id)
    except (CloudStackAPIError, TimeoutError, Exception) as exc:  # noqa: BLE001
        elapsed = time.perf_counter() - t0
        return OpResult(False, elapsed, start_ts, now_iso(), name, pool_id=pool_id, error=str(exc))


def fake_create(name):
    time.sleep(random.uniform(0.01, 0.05))
    return OpResult(True, random.uniform(0.5, 3.0), now_iso(), now_iso(), name, pool_id=f"dryrun-{name}")


def fake_delete(pool_id, name):
    time.sleep(random.uniform(0.01, 0.05))
    return OpResult(True, random.uniform(0.3, 2.0), now_iso(), now_iso(), name, pool_id=pool_id)


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
            "pool_name": result.pool_name, "pool_id": result.pool_id or "",
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
    accumulates into one combined summary_<run_id>.csv that render_report.py
    can render as a single report, instead of the second script clobbering the
    first script's rows."""
    is_new = not os.path.exists(path) or os.path.getsize(path) == 0
    with open(path, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=SUMMARY_FIELDNAMES)
        if is_new:
            writer.writeheader()
        writer.writerows(rows)


def cleanup_by_filter(client, name_filter):
    payload, _ = client.call("listStoragePools", {}, poll_async=False)
    pools = payload.get("storagepool", []) if isinstance(payload, dict) else []
    matches = [p for p in pools if name_filter in p.get("name", "")]
    print(f"Found {len(matches)} pool(s) whose name contains '{name_filter}'")
    for p in matches:
        try:
            if p.get("state") != "Maintenance":
                client.call("enableStorageMaintenance", {"id": p["id"]})
            client.call("deleteStoragePool", {"id": p["id"], "forced": "true"})
            print(f"  deleted {p['name']} ({p['id']})")
        except Exception as exc:  # noqa: BLE001
            print(f"  FAILED to delete {p['name']}: {exc}")


def load_config(path):
    with open(path) as f:
        return yaml.safe_load(f)


def resolve_protocols(cfg, requested):
    available = list(cfg.get("ontap", {}).keys())
    if requested == "both":
        return available
    if requested not in available:
        sys.exit(f"Protocol '{requested}' not found in config.ontap (available: {available})")
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
