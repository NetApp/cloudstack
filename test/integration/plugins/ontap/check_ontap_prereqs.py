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

"""Fail fast when ontap.cfg asks for a protocol the SVM cannot serve.

Usage:
  python3 check_ontap_prereqs.py <ontap.cfg> [iscsi|nfs3|both]
"""

from __future__ import print_function

import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from base64 import b64encode


def _get(base, auth, path, params, ctx):
    url = base + path + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(
        url,
        headers={"Authorization": "Basic " + auth, "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, context=ctx, timeout=15) as response:
        return json.load(response)


def _svm_protocol_enabled(svm, protocol):
    block = svm.get(protocol) or {}
    return bool(block.get("enabled"))


def _check_protocol(base, auth, ctx, svm_name, svm, protocol):
    rest_name = "iscsi" if protocol == "iscsi" else "nfs"
    service = "data-iscsi" if protocol == "iscsi" else "data-nfs"
    label = "iSCSI" if protocol == "iscsi" else "NFS"

    if not _svm_protocol_enabled(svm, rest_name):
        raise RuntimeError(
            "%s protocol is not enabled on SVM %s. "
            "Enable the %s service and assign at least one %s data LIF "
            "before running these tests."
            % (label, svm_name, label, service)
        )

    lifs = _get(
        base, auth, "/api/network/ip/interfaces",
        {"svm.name": svm_name, "services": service, "fields": "name,ip,state,enabled"},
        ctx,
    )
    records = lifs.get("records") or []
    up_lifs = [
        r for r in records
        if r.get("enabled") and (r.get("state") or "").lower() == "up"
        and (r.get("ip") or {}).get("address")
    ]
    if not up_lifs:
        raise RuntimeError(
            "SVM %s has no UP %s data LIF. Assign service %s to at least one "
            "data LIF before running %s tests."
            % (svm_name, label, service, label)
        )

    if protocol == "iscsi":
        services = _get(
            base, auth, "/api/protocols/san/iscsi/services",
            {"svm.name": svm_name, "fields": "enabled,target"},
            ctx,
        )
        svc_records = services.get("records") or []
        if not svc_records:
            raise RuntimeError("No iSCSI service found for SVM %s." % svm_name)
        target = (svc_records[0].get("target") or {}).get("name")
        if not target:
            raise RuntimeError("iSCSI target IQN not found for SVM %s." % svm_name)

    print("ONTAP %s prerequisite OK on SVM %s (%d data LIF(s))"
          % (label, svm_name, len(up_lifs)))


def main(argv):
    if len(argv) < 2 or len(argv) > 3:
        print("Usage: %s <ontap.cfg> [iscsi|nfs3|both]" % argv[0], file=sys.stderr)
        return 2

    cfg_path = argv[1]
    wanted = argv[2] if len(argv) == 3 else "both"
    if wanted not in ("iscsi", "nfs3", "both"):
        print("Unknown protocol filter: %s" % wanted, file=sys.stderr)
        return 2

    with open(cfg_path, encoding="utf-8") as stream:
        config = json.load(stream)

    ontap = config.get("ontap") or {}
    pool = config.get("storagePool") or {}
    protocols = pool.get("protocols") or {}
    storage_ip = ontap.get("storageIP")
    svm_name = ontap.get("svmName")
    username = ontap.get("username")
    password = ontap.get("password")
    if not all([storage_ip, svm_name, username, password is not None]):
        print("ontap.cfg is missing storageIP, svmName, username, or password", file=sys.stderr)
        return 1

    checks = []
    if wanted in ("iscsi", "both") and protocols.get("iscsi", {}).get("enabled", True):
        checks.append("iscsi")
    if wanted in ("nfs3", "both") and protocols.get("nfs3", {}).get("enabled", True):
        checks.append("nfs3")
    if not checks:
        print("No enabled protocols to check for filter '%s'" % wanted)
        return 0

    ctx = ssl._create_unverified_context()
    auth = b64encode(("%s:%s" % (username, password)).encode()).decode()
    base = "https://%s" % storage_ip
    try:
        svms = _get(
            base, auth, "/api/svm/svms",
            {"name": svm_name, "fields": "name,uuid,iscsi,nfs"},
            ctx,
        )
    except urllib.error.HTTPError as exc:
        print("ONTAP REST query failed: HTTP %s" % exc.code, file=sys.stderr)
        return 1
    except Exception as exc:
        print("ONTAP REST query failed: %s" % exc, file=sys.stderr)
        return 1

    records = svms.get("records") or []
    if not records:
        print("SVM '%s' was not found on %s" % (svm_name, storage_ip), file=sys.stderr)
        return 1
    svm = records[0]

    try:
        for protocol in checks:
            _check_protocol(base, auth, ctx, svm_name, svm, protocol)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
