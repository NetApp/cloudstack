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
QoS offering coverage for a NetApp ONTAP iSCSI primary pool.

Disk offerings cover no IOPS, max only, min and max, min only, custom
size, custom IOPS, thin/sparse/fat, a shared policy, and resize. Compute
offerings cover a fixed offering, an offering that carries its own IOPS,
one bound to a disk offering, a strict offering, a custom offering, and
custom IOPS supplied at deploy. Root and data disks are also checked
together. iSCSI checks the QoS policy on the LUN.

Minimum IOPS run on AFF. On FAS those creates are rejected and no QoS
policy is left behind. Each test removes its own volumes and VMs.
tearDownClass still force-deletes the pool.

Workflow:
  01  Fixed disk, no IOPS
  02  Fixed disk, maximum IOPS only
  03  Fixed disk, minimum and maximum IOPS
  04  Fixed disk, minimum IOPS only
  05  Custom-size disk with fixed IOPS
  06  Custom IOPS, maximum only
  07  Custom IOPS, minimum and maximum
  08  Custom size and custom IOPS
  09  Thin, sparse, and fat disks keep maximum IOPS
  10  Two volumes share one policy until the last delete
  11  Resize onto a disk offering with a higher maximum
  12  Resize onto a disk offering without IOPS
  13  Reject a disk offering whose minimum exceeds its maximum
  14  Reject a disk offering whose minimum is zero
  15  Reject custom IOPS whose minimum exceeds the maximum
  16  Reject a minimum that exceeds the pool IOPS capacity
  17  Fixed compute offering with an override QoS disk
  18  Compute offering that carries its own maximum IOPS
  19  Compute offering bound to a QoS disk
  20  Strict compute offering rejects a different override disk
  21  Custom compute offering with a bound QoS disk
  22  Custom compute offering with a custom-size, custom-IOPS root disk
  23  Compute offering with custom IOPS supplied at deploy
  24  Root disk without IOPS and a data disk with IOPS
  25  Root and data disks use different policies
  26  Hypervisor rate limits do not create an ONTAP policy
  27  Constrained custom compute with a fixed-IOPS root disk
  28  Constrained custom compute with custom IOPS at deploy

Prerequisites:
  - CloudStack management server with the NetApp ONTAP plugin deployed
  - KVM cluster whose hosts have an iSCSI initiator
  - Ready KVM template for the compute-offering cases
  - ONTAP SVM with iSCSI enabled
  - ontap.cfg populated

Running:
  PYTHONPATH=test/integration/plugins/ontap nosetests --with-marvin \\
      --marvin-config=test/integration/plugins/ontap/ontap.cfg \\
      test/integration/plugins/ontap/iscsi/qos/test_qos_offerings.py -v
"""

# Import the module, not the base class: nose would otherwise collect the
# unparameterised base as a second test class in this file.
from helpers import qos_offerings as qos


class TestOntapISCSIQosOfferings(qos.OntapQosOfferingsBase):

    PROTOCOL = "ISCSI"
    PROTOCOL_CFG_KEY = "iscsi"
    POOL_URL_SCHEME = "iscsi"
    _vol_name_prefix = "QosIscsi"


qos.tag_qos_tests(TestOntapISCSIQosOfferings, "iscsi_qos")
