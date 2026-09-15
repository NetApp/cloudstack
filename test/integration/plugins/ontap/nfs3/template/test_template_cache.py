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
NFS3 primary template-cache workflow (Marvin).

Seeds and reuses an ONTAP FlexVol-backed template cache when ROOT is placed
on a tagged NetApp ONTAP NFS3 pool via a matching service offering.

Workflow (sequential — run full suite):
  01  Create tagged NFS3 pool + tagged service offering
  02  Deploy VM-1 — seeds cache + clones ROOT
  03  Assert single template_spool_ref + NFS cache file
  04  Deploy VM-2 — reuses cache
  05  Destroy VMs — cache survives (lazy GC)
  06  Cleanup pool + offering

Running:
  nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/nfs3/template/test_template_cache.py \\
      -a tags=nfs3_template_cache -v

  # or via runner:
  bash test/integration/plugins/ontap/run_tests.sh nfs3_template_cache
"""

from nose.plugins.attrib import attr

from helpers.template_cache_workflow import OntapTemplateCacheWorkflow


class TestOntapNfs3TemplateCache(OntapTemplateCacheWorkflow):
    PROTOCOL = "NFS3"
    NOSE_TAG = "nfs3_template_cache"
    PROTOCOL_CFG_KEY = "nfs3"
    POOL_URL_SCHEME = "nfs"
    POOL_NAME_PREFIX = "OntapNfsTmplCache"

    @attr(tags=["nfs3_template_cache"], required_hardware=True)
    def test_01_create_tagged_pool_and_service_offering(self):
        self.step_01_create_tagged_pool_and_service_offering()

    @attr(tags=["nfs3_template_cache"], required_hardware=True)
    def test_02_deploy_vm1_seeds_template_cache(self):
        self.step_02_deploy_vm1_seeds_template_cache()

    @attr(tags=["nfs3_template_cache"], required_hardware=True)
    def test_03_assert_single_spool_ref_and_cache(self):
        self.step_03_assert_single_spool_ref_and_cache()

    @attr(tags=["nfs3_template_cache"], required_hardware=True)
    def test_04_deploy_vm2_reuses_cache(self):
        self.step_04_deploy_vm2_reuses_cache()

    @attr(tags=["nfs3_template_cache"], required_hardware=True)
    def test_05_destroy_vms_cache_survives(self):
        self.step_05_destroy_vms_cache_survives()

    @attr(tags=["nfs3_template_cache"], required_hardware=True)
    def test_06_cleanup_pool_and_offering(self):
        self.step_06_cleanup_pool_and_offering()
