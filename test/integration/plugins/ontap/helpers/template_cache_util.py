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
Helpers for ONTAP primary template-cache Marvin assertions.

Covers CloudStack DB (``template_spool_ref``) and ONTAP REST checks for the
cache LUN (iSCSI: ``/vol/<flexVol>/cs_tmpl_<templateId>``) or cache file (NFS).
"""

from __future__ import print_function

import logging
import time

logger = logging.getLogger("template_cache_util")

# Must stay in sync with OntapStorageConstants.TEMPLATE_LUN_PREFIX
TEMPLATE_LUN_PREFIX = "cs_tmpl_"


def get_db_id(db_connection, table, api_uuid):
    """Resolve CloudStack API UUID to numeric DB id."""
    rows = db_connection.execute(
        "SELECT id FROM `%s` WHERE uuid = '%s'" % (table, api_uuid)
    )
    if not rows:
        raise AssertionError(
            "No row in %s for uuid=%s" % (table, api_uuid)
        )
    return rows[0][0]


def get_template_spool_ref(db_connection, pool_db_id, template_db_id):
    """
    Return template_spool_ref row as a dict, or None if absent.

    Columns: id, pool_id, template_id, download_state, local_path,
    install_path, template_size, marked_for_gc, state
    """
    rows = db_connection.execute(
        "SELECT id, pool_id, template_id, download_state, local_path, "
        "install_path, template_size, marked_for_gc, state "
        "FROM template_spool_ref "
        "WHERE pool_id = %s AND template_id = %s"
        % (int(pool_db_id), int(template_db_id))
    )
    if not rows:
        return None
    row = rows[0]
    return {
        "id": row[0],
        "pool_id": row[1],
        "template_id": row[2],
        "download_state": row[3],
        "local_path": row[4],
        "install_path": row[5],
        "template_size": row[6],
        "marked_for_gc": row[7],
        "state": row[8],
    }


def count_template_spool_refs(db_connection, pool_db_id, template_db_id):
    """Return number of template_spool_ref rows for pool+template."""
    rows = db_connection.execute(
        "SELECT COUNT(*) FROM template_spool_ref "
        "WHERE pool_id = %s AND template_id = %s"
        % (int(pool_db_id), int(template_db_id))
    )
    return int(rows[0][0]) if rows else 0


def assert_spool_ref_ready(testcase, spool_ref, expect_local_path=True):
    """Assert spool_ref is DOWNLOADED / Ready after a successful cache seed."""
    testcase.assertIsNotNone(spool_ref, "Expected template_spool_ref row")
    testcase.assertEqual(
        str(spool_ref["download_state"]).upper(),
        "DOWNLOADED",
        "download_state should be DOWNLOADED, got %s"
        % spool_ref["download_state"],
    )
    testcase.assertEqual(
        str(spool_ref["state"]),
        "Ready",
        "state should be Ready, got %s" % spool_ref["state"],
    )
    if expect_local_path:
        testcase.assertTrue(
            spool_ref.get("local_path"),
            "local_path (LUN uuid / cache id) should be set",
        )
    testcase.assertTrue(
        spool_ref.get("install_path"),
        "install_path should be set after template is cached",
    )


def assert_no_spool_ref(testcase, db_connection, pool_db_id, template_db_id):
    """Assert there is no template_spool_ref for pool+template."""
    count = count_template_spool_refs(
        db_connection, pool_db_id, template_db_id
    )
    testcase.assertEqual(
        count, 0,
        "Expected no template_spool_ref for pool=%s template=%s, found %s"
        % (pool_db_id, template_db_id, count),
    )


def template_cache_lun_path(flexvol_name, template_db_id):
    """iSCSI cache LUN path: /vol/<flexVol>/cs_tmpl_<templateId>."""
    return "/vol/%s/%s%s" % (
        flexvol_name, TEMPLATE_LUN_PREFIX, int(template_db_id)
    )


def assert_iscsi_template_cache_lun(testcase, ontap, svm_name, flexvol_name,
                                    template_db_id):
    """Assert the deterministic template-cache LUN exists on ONTAP."""
    path = template_cache_lun_path(flexvol_name, template_db_id)
    lun = ontap.get_lun(svm_name, path)
    testcase.assertIsNotNone(
        lun,
        "Expected template cache LUN at %s on SVM %s" % (path, svm_name),
    )
    return lun


def assert_no_iscsi_template_cache_lun(testcase, ontap, svm_name, flexvol_name,
                                       template_db_id):
    """Assert the template-cache LUN is absent."""
    path = template_cache_lun_path(flexvol_name, template_db_id)
    lun = ontap.get_lun(svm_name, path)
    testcase.assertIsNone(
        lun,
        "Template cache LUN should be absent at %s" % path,
    )


def count_iscsi_template_cache_luns(ontap, svm_name, flexvol_name,
                                    template_db_id=None):
    """Count cs_tmpl_* LUNs in the FlexVol (optionally for one template id)."""
    luns = ontap.list_luns_in_volume(svm_name, flexvol_name) or []
    prefix = "/vol/%s/%s" % (flexvol_name, TEMPLATE_LUN_PREFIX)
    matches = [l for l in luns if l.get("name", "").startswith(prefix)]
    if template_db_id is not None:
        exact = template_cache_lun_path(flexvol_name, template_db_id)
        matches = [l for l in matches if l.get("name") == exact]
    return len(matches)


def count_luns_excluding_template_cache(ontap, svm_name, flexvol_name):
    """Count non-cache LUNs (volume clones / blank volumes) in the FlexVol."""
    luns = ontap.list_luns_in_volume(svm_name, flexvol_name) or []
    prefix = "/vol/%s/%s" % (flexvol_name, TEMPLATE_LUN_PREFIX)
    return len([l for l in luns if not l.get("name", "").startswith(prefix)])


def _normalize_nfs_path(install_path):
    """Strip leading slash for OntapRestClient.list_files_in_volume paths."""
    if not install_path:
        return "/"
    path = install_path if install_path.startswith("/") else "/" + install_path
    # Parent directory listing: if path is a file, list its parent
    if path.endswith("/"):
        return path.rstrip("/") or "/"
    parent = path.rsplit("/", 1)[0]
    return parent or "/"


def assert_nfs_template_cache_file(testcase, ontap, flexvol_name, install_path):
    """
    Assert the NFS cache file referenced by install_path exists in the FlexVol.

    ``install_path`` comes from ``template_spool_ref.install_path`` (relative or
    absolute path inside the FlexVol as reported by the KVM copy).
    """
    testcase.assertTrue(
        install_path,
        "install_path is required to locate the NFS template cache file",
    )
    file_name = install_path.rstrip("/").rsplit("/", 1)[-1]
    parent = _normalize_nfs_path(install_path)
    names = ontap.list_files_in_volume(flexvol_name, path=parent)
    # Also try volume root if parent listing is empty (path styles differ)
    if file_name not in names:
        root_names = ontap.list_files_in_volume(flexvol_name, path="/")
        testcase.assertIn(
            file_name, root_names + names,
            "Expected NFS template cache file '%s' under '%s' or '/' in "
            "FlexVol '%s'; listed parent=%s root=%s"
            % (file_name, parent, flexvol_name, names, root_names),
        )
    return file_name


def wait_for_spool_ref(db_connection, pool_db_id, template_db_id,
                       timeout=600, interval=10):
    """
    Poll until template_spool_ref exists and is Ready/DOWNLOADED.

    Returns the spool_ref dict, or None on timeout.
    """
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = get_template_spool_ref(
            db_connection, pool_db_id, template_db_id
        )
        if last and str(last.get("download_state", "")).upper() == "DOWNLOADED" \
                and str(last.get("state", "")) == "Ready":
            return last
        time.sleep(interval)
    return last
