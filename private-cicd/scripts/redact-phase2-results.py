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

"""Strip passwords from Phase 2 result files before they are archived.

Purpose:
    Read password fields from secrets.json and ontap.cfg, also match their
    base64 encodings, and replace occurrences in text files under --results
    with [REDACTED].

Dependencies:
    Stdlib only (argparse, base64, json, pathlib).
    CLI: --results, --secrets, --ontap-config.
    Called from the run-phase2-vm.sh EXIT trap after collect-phase2-logs.sh.
    Does not delete ontap.cfg/secrets.json (the wrapper does that).
"""

import argparse
import base64
import json
from pathlib import Path


def collect_passwords(value, passwords):
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(child, str) and ("password" in key.lower() or "passwd" in key.lower()):
                if child:
                    passwords.add(child)
            else:
                collect_passwords(child, passwords)
    elif isinstance(value, list):
        for child in value:
            collect_passwords(child, passwords)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--secrets", required=True, type=Path)
    parser.add_argument("--ontap-config", required=True, type=Path)
    args = parser.parse_args()

    passwords = set()
    for config_file in (args.secrets, args.ontap_config):
        with config_file.open(encoding="utf-8") as stream:
            collect_passwords(json.load(stream), passwords)
    encoded_passwords = {
        base64.b64encode(password.encode("utf-8")).decode("ascii")
        for password in passwords
    }
    passwords.update(encoded_passwords)

    for result_file in args.results.rglob("*"):
        if not result_file.is_file() or result_file.is_symlink():
            continue
        try:
            content = result_file.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        redacted = content
        for password in sorted(passwords, key=len, reverse=True):
            redacted = redacted.replace(password, "[REDACTED]")
        if redacted != content:
            result_file.write_text(redacted, encoding="utf-8")


if __name__ == "__main__":
    main()
