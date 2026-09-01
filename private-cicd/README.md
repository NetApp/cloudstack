<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->

# Private CloudStack CI/CD

This downstream-only directory implements the NetApp ONTAP presubmit for
CloudStack. Do not include it in pull requests to `apache/cloudstack`.

For setup, operation, testing, security boundaries, troubleshooting, and the
current implementation contract, see
[`docs/PRIVATE-CICD-GUIDE.md`](docs/PRIVATE-CICD-GUIDE.md).

## How it works

The production Jenkins job loads [`Jenkinsfile`](Jenkinsfile) and helper scripts
from the protected NetApp `main` branch. It checks out the exact pull-request
commit into a separate `cloudstack-src` directory, then:

1. validates the request, builder, credentials, and VM inventory;
2. runs the full Maven build and unit tests;
3. builds and verifies CloudStack Debian packages;
4. locks and reverts a compatible lab VM;
5. installs and configures CloudStack, MySQL, KVM, NFS, and iSCSI;
6. creates the test zone and runs the ONTAP iSCSI and NFS3 suites;
7. redacts and archives results, publishes a GitHub Check, and sends mail.

Eligible pull-request revisions receive the required Check
`cloudstack-ontap-presubmit`. GitHub API and SMTP failures are reported without
replacing the underlying build or test result.

## Entry points

- **Pull request:** a GitHub `pull_request` webhook starts the production job.
- **Manual branch:** a separate triggerless job uses `SOURCE_MODE=branch` with
  a remote branch and exact 40-character commit SHA.
- **Local validation:** `scripts/validate-local.sh` checks the CI files without
  building CloudStack.
- **Direct lab validation:** the scripts can be run gate by gate on a disposable
  Ubuntu 22.04 nested-KVM VM.

Different sources may run concurrently. A newer run aborts an older run only
for the same pull-request ID or manual source branch, and only before the older
run acquires its integration-test VM.

## Configuration and secrets

[`config/vm-inventory.yaml.example`](config/vm-inventory.yaml.example) is the
only tracked inventory file. Create populated inventory outside Git and upload
it to Jenkins as a Secret file. Keep passwords, tokens, and private keys in
dedicated Jenkins credentials; generated runtime configuration is not archived.

The Kubernetes pod uses a `jnlp` agent container and a separate
`cloudstack-driver` build container. The immutable driver image reference,
resource requests, build commands, credential mapping, and snapshot contract
are documented in the comprehensive guide.

## Local validation

```bash
./private-cicd/scripts/validate-local.sh

# Also build the driver image
./private-cicd/scripts/validate-local.sh --with-docker
```

Local validation checks shell syntax, compiles Python, and parses YAML when a
supported parser is available. The Docker option builds the driver image. These
commands do not run Maven, create Debian packages, deploy CloudStack, or run
ONTAP tests.

## Related code

- ONTAP plugin: [`../plugins/storage/volume/ontap/`](../plugins/storage/volume/ontap/)
- ONTAP integration tests:
  [`../test/integration/plugins/ontap/`](../test/integration/plugins/ontap/)
