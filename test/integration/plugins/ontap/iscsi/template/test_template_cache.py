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
iSCSI primary template-cache workflow (Marvin).

Seeds and reuses an ONTAP LUN cache named ``/vol/<flexVol>/cs_tmpl_<templateId>``
when ROOT is placed on a tagged NetApp ONTAP iSCSI pool via a matching
service offering.

Workflow (sequential — run full suite):
  01  Create tagged iSCSI pool + tagged service offering
  02  Deploy VM-1 — seeds cache LUN + clones ROOT LUN
  03  Assert single template_spool_ref + cs_tmpl_* LUN
  04  Deploy VM-2 — reuses cache (still one cs_tmpl_*, two volume LUNs)
  05  Destroy VMs — cache LUN survives (lazy GC)
  06  Cleanup pool + offering

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/template/test_template_cache.py \\
      -a tags=iscsi_template_cache -v

  # or via runner:
  bash test/integration/plugins/ontap/run_tests.sh iscsi_template_cache
"""

from nose.plugins.attrib import attr

from helpers.template_cache_workflow import OntapTemplateCacheWorkflow


class TestOntapIscsiTemplateCache(OntapTemplateCacheWorkflow):
    PROTOCOL = "ISCSI"
    NOSE_TAG = "iscsi_template_cache"
    PROTOCOL_CFG_KEY = "iscsi"
    POOL_URL_SCHEME = "iscsi"
    POOL_NAME_PREFIX = "OntapIscsiTmplCache"

    @attr(tags=["iscsi_template_cache"], required_hardware=True)
    def test_01_create_tagged_pool_and_service_offering(self):
        self.step_01_create_tagged_pool_and_service_offering()

    @attr(tags=["iscsi_template_cache"], required_hardware=True)
    def test_02_deploy_vm1_seeds_template_cache(self):
        self.step_02_deploy_vm1_seeds_template_cache()

    @attr(tags=["iscsi_template_cache"], required_hardware=True)
    def test_03_assert_single_spool_ref_and_cache(self):
        self.step_03_assert_single_spool_ref_and_cache()

    @attr(tags=["iscsi_template_cache"], required_hardware=True)
    def test_04_deploy_vm2_reuses_cache(self):
        self.step_04_deploy_vm2_reuses_cache()

    @attr(tags=["iscsi_template_cache"], required_hardware=True)
    def test_05_destroy_vms_cache_survives(self):
        self.step_05_destroy_vms_cache_survives()

    @attr(tags=["iscsi_template_cache"], required_hardware=True)
    def test_06_cleanup_pool_and_offering(self):
        self.step_06_cleanup_pool_and_offering()
