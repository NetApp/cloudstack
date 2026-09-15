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
iSCSI primary template-cache negative / boundary suite (Marvin).

Independent of the sequential happy-path suite.

  01  Tag mismatch — no spool_ref / cache on ONTAP pool
  02  Undersized pool — deploy fails
  03  Out-of-band cache delete — reuse deploy fails

Running:
  bash test/integration/plugins/ontap/run_tests.sh iscsi_template_cache_negative
"""

from nose.plugins.attrib import attr

from helpers.template_cache_negative_workflow import (
    OntapTemplateCacheNegativeWorkflow,
)


class TestOntapIscsiTemplateCacheNegative(OntapTemplateCacheNegativeWorkflow):
    PROTOCOL = "ISCSI"
    NOSE_TAG = "iscsi_template_cache_negative"
    PROTOCOL_CFG_KEY = "iscsi"
    POOL_URL_SCHEME = "iscsi"
    POOL_NAME_PREFIX = "OntapIscsiTmplNeg"

    @attr(tags=["iscsi_template_cache_negative"], required_hardware=True)
    def test_01_tag_mismatch_does_not_seed_cache(self):
        self.step_01_tag_mismatch_does_not_seed_cache()

    @attr(tags=["iscsi_template_cache_negative"], required_hardware=True)
    def test_02_undersized_pool_deploy_fails(self):
        self.step_02_undersized_pool_deploy_fails()

    @attr(tags=["iscsi_template_cache_negative"], required_hardware=True)
    def test_03_deleted_cache_blocks_reuse(self):
        self.step_03_deleted_cache_blocks_reuse()
