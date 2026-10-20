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
"""Shared run-id, naming, statistics, and logging helpers for every benchmark script."""

import datetime
import logging
import re
import secrets
import statistics
import sys

LOG_NAME = "ontap.benchmark"


def configure_logging():
    """One format for every benchmark script and the API client."""
    root = logging.getLogger(LOG_NAME)
    if not root.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter(
            "%(asctime)s %(levelname)s %(message)s",
            "%Y-%m-%dT%H:%M:%S",
        ))
        root.addHandler(handler)
    root.setLevel(logging.INFO)
    root.propagate = False
    return root


def get_logger():
    return logging.getLogger(LOG_NAME)


def mean_seconds(values):
    """Average duration. Empty input is 0 so checkpoint rows stay numeric."""
    if not values:
        return 0.0
    return statistics.mean(values)


def new_run_id():
    """Timestamp plus a short random suffix. Underscores only: pool names reject hyphens."""
    stamp = datetime.datetime.utcnow().strftime("%Y%m%d_%H%M%S")
    return f"RUN_{stamp}_{secrets.token_hex(2)}"


def normalize_run_id(run_id):
    """Accept a caller-supplied id, or generate one. Hyphens are rewritten to underscores."""
    if not run_id:
        return new_run_id()
    normalized = run_id.strip().replace("-", "_")
    if not re.fullmatch(r"[A-Za-z0-9_]+", normalized):
        sys.exit(
            f"Run id {run_id!r} is not valid. Use letters, digits, and underscores "
            "(hyphens are rewritten to underscores because storage pool names disallow '-')."
        )
    if normalized != run_id.strip():
        get_logger().warning(
            "Rewrote run id %r to %r so it can be embedded in a storage pool name",
            run_id, normalized,
        )
    return normalized


def format_pool_name(prefix, protocol_key, run_id, index, concurrency_level=None):
    if concurrency_level is None:
        return f"{prefix}_seq_{protocol_key}_{run_id}_{index:03d}"
    return f"{prefix}_c{concurrency_level}_{protocol_key}_{run_id}_{index:03d}"


def format_vm_name(prefix, protocol_key, run_id, index, concurrency_level=None):
    """VM names are hostnames: underscores in the run id become hyphens."""
    kind = "seq" if concurrency_level is None else f"c{concurrency_level}"
    raw = f"{prefix}-{kind}-{protocol_key}-{run_id}-{index:03d}"
    return raw.replace("_", "-")


def vm_name_token(value):
    """Same underscore-to-hyphen rewrite used by format_vm_name, for cleanup filters."""
    return (value or "").replace("_", "-")


def assert_pool_name_prefix(prefix):
    if not prefix or not re.fullmatch(r"[A-Za-z0-9_]+", prefix):
        sys.exit(
            f"benchmark.pool_name_prefix {prefix!r} must be letters, digits, and underscores "
            "(storage pool names disallow '-')."
        )


def assert_vm_name_prefix(prefix):
    if not prefix or "_" in prefix or not re.fullmatch(r"[A-Za-z0-9-]+", prefix):
        sys.exit(
            f"vm_bench.vm_name_prefix {prefix!r} must be letters, digits, and hyphens "
            "(VM names are hostnames and disallow '_')."
        )
