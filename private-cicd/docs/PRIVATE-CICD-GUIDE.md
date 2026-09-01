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

# Private CloudStack ONTAP CI/CD guide

## 1. Purpose, scope, and authority

This directory implements the NetApp-fork-only presubmit for Apache CloudStack ONTAP primary storage changes. It
builds an exact source revision, runs CloudStack unit tests, creates Debian packages, deploys those packages to a
reverted lab VM, and runs ONTAP iSCSI and NFS3 integration suites.

This is the consolidated operator and developer reference. The current [`Jenkinsfile`](../Jenkinsfile), scripts under
[`scripts/`](../scripts/), and ONTAP test code are authoritative if prose and executable code differ.

The guide covers:

- the trusted production Pipeline-from-SCM job and GitHub pull-request webhook, including first-PR cutover from
  `feature/CSTACKEX-223` to `main` ([`CREATE-PRESUBMIT-JOB.md`](CREATE-PRESUBMIT-JOB.md));
- the separate triggerless manual branch job;
- local CI-file validation and direct disposable-VM validation;
- Stage 1 build, unit-test, and Debian package handoff;
- Phase 2 snapshot, deployment, health, zone, and ONTAP tests;
- concurrency, reporting, artifacts, troubleshooting, and upstream hygiene.

`private-cicd/` is downstream infrastructure. Do not include it in a pull request to `apache/cloudstack`.

## 2. Current status

Implemented:

- trusted CI checkout and separate exact-SHA source checkout;
- full, no-tags Git checkout with SHA and branch/PR-ref reachability checks;
- builder, credential, inventory, and eligibility validation;
- full Maven build, unit tests, preserved Surefire XML, and Jenkins JUnit publication;
- Debian packages reusing Maven output, with manifests, checksums, and Marvin handoff;
- compatible-VM locking, exact vCenter snapshot revert, and one-run SSH-key bootstrap;
- package deployment, CloudStack configuration, health checks, zone setup, then iSCSI and NFS3 suites;
- runtime log collection, result retrieval, and literal/base64 password redaction;
- GitHub Check creation and final conclusion, start mail, final collated mail, and archived artifacts;
- source-aware cancellation of superseded runs only before VM lock acquisition.

Phase 3 is partially implemented. JUnit publication, GitHub Check conclusion, final collated mail, and Jenkins
artifacts exist. A stable external per-PR artifact store and an explicit retention/cleanup policy for that store do
not exist. Jenkins currently retains 30 builds; that is not a stable external retention policy.

`PAUSE_BETWEEN_STAGES` is a boolean parameter for manual verification, off by default and intended to stay off for
webhook runs. When enabled, each pause times out after 60 minutes; the pause after snapshot revert holds the VM lock.

## 3. Architecture and data flow

```text
GitHub pull_request webhook
  -> trusted Jenkinsfile/scripts from protected main
  -> request, eligibility, credential, inventory, and builder checks
  -> exact PR or branch SHA checkout into cloudstack-src
  -> Maven clean install and unit tests
  -> Debian packages plus Marvin/API handoff
  -> lock one compatible VM
  -> exact vCenter snapshot revert and power-on
  -> render temporary ontap.cfg and secrets.json
  -> copy packages, Marvin, ONTAP tests, and Phase 2 scripts
  -> configure VM -> health gates -> setup_zone -> iscsi -> nfs3
  -> collect, redact, retrieve, publish, archive, notify
```

Three domains remain separate:

1. **Trusted CI source:** production loads `Jenkinsfile` and helpers from protected `main`.
2. **Source under test:** PR/manual source is fetched into `cloudstack-src` at an exact 40-character SHA.
3. **Lab configuration:** populated inventory is a Jenkins Secret file outside Git; secrets use separate credentials.

The Kubernetes pod compiles and packages CloudStack; it does not run CloudStack services. The locked VM runs MySQL,
management, agent, KVM/libvirt, NFS, iSCSI, and Marvin.

The full source tree is not copied to the VM. Phase 2 transfers the required `.deb` files and manifest, the
version-matched Marvin archive, `test/integration/plugins/ontap/`, VM-side scripts, trusted
`check_ontap_prereqs.py`, and generated runtime files.

## 4. Current folder layout

```text
private-cicd/
├── Jenkinsfile
├── README.md
├── config/
│   └── vm-inventory.yaml.example
├── docker/
│   └── Dockerfile.driver
├── docs/
│   ├── CREATE-PRESUBMIT-JOB.md
│   └── PRIVATE-CICD-GUIDE.md
└── scripts/
    ├── build-debs.sh
    ├── check-build-prereqs.sh
    ├── check-phase2-health.sh
    ├── collect-phase2-logs.sh
    ├── configure-cloudstack-vm.sh
    ├── github-check-run.sh
    ├── marvin-run.sh
    ├── mvn-full.sh
    ├── redact-phase2-results.py
    ├── render-phase2-config.py
    ├── run-phase2-remote.sh
    ├── run-phase2-vm.sh
    ├── validate-local.sh
    └── vcenter-revert.py
```

The 14 scripts respectively build packages; check the builder; check Phase 2 health; collect logs; configure the
VM; manage GitHub Checks; run Marvin; run Maven; redact results; render runtime config; drive remote Phase 2; drive
on-VM Phase 2; validate locally; and revert vCenter.

Product code stays under `plugins/storage/volume/ontap/`. Tests stay under
[`test/integration/plugins/ontap/`](../../test/integration/plugins/ontap/) and are documented by
[`test/integration/plugins/ontap/README.md`](../../test/integration/plugins/ontap/README.md).

## 5. Branch and security model

### Production trust boundary

Production loads executable CI code only from protected NetApp `main`:

```text
Definition: Pipeline script from SCM
Repository: https://github.com/NetApp/cloudstack.git
Branch: */main
Script Path: private-cicd/Jenkinsfile
Lightweight checkout: off
```

PR code cannot replace trusted CI helpers before credentials are used. The source under test is checked out
separately.

Until `feature/CSTACKEX-223` is merged, that Jenkinsfile is not on `main`. Create **one** webhook job named
`cloudstack-ontap-presubmit` that loads CI from `*/feature/CSTACKEX-223`, using
[`CREATE-PRESUBMIT-JOB.md`](CREATE-PRESUBMIT-JOB.md). After merge, change only **Branches to build**
to `*/main`. Keep the same job, webhook, GitHub App, credentials, and Check. Do not create a second production job
and do not leave the feature branch as trusted CI after cutover.

### Manual-job trust boundary

A manual job may load the Jenkinsfile from an unreviewed remote feature branch. That branch can request every
credential visible to the job. Create a separate triggerless job, restrict Configure/Build permissions, expose only
least-privilege lab credentials, review the diff before every run, and never convert it into the webhook job.

The manual job is only triggerless while disabled. Because it loads the same Jenkinsfile, it also carries the
Jenkinsfile's Generic Webhook Trigger and token. Keep `cloudstack-presubmit-manual` disabled and enable it only for
the duration of a branch run, or use `SOURCE_MODE=branch` on the webhook job instead.

### Secret boundaries

Never commit populated inventory, real `ontap.cfg`, `secrets.json`, credentials, tokens, or private keys. Only
[`config/vm-inventory.yaml.example`](../config/vm-inventory.yaml.example) is tracked. Keep populated inventory outside
Git and upload it as a Secret file; keep passwords and keys in dedicated Jenkins credentials.

Generated runtime files are mode `0600`, excluded from retrieved results, and deleted by the VM wrapper. Use only
approved lab networks, disposable CloudStack state, and an ONTAP SVM approved for destructive tests.

## 6. Entry modes

### Webhook PR

Production uses `SOURCE_MODE=pull_request`. The Generic Webhook Trigger maps:

| Parameter | JSON path |
|---|---|
| `PR_ID` | `$.number` |
| `PR_ACTION` | `$.action` |
| `PR_DRAFT` | `$.pull_request.draft` |
| `PR_REPOSITORY` | `$.repository.full_name` |
| `PR_BASE_BRANCH` | `$.pull_request.base.ref` |
| `PR_HEAD_BRANCH` | `$.pull_request.head.ref` |
| `PR_HEAD_SHA` | `$.pull_request.head.sha` |
| `PR_AUTHOR_LOGIN` | `$.pull_request.user.login` |
| `PR_AUTHOR_EMAIL` | `$.pull_request.user.email` |
| `PR_URL` | `$.pull_request.html_url` |
| `PR_TITLE` | `$.pull_request.title` |

The trigger admits only non-draft `NetApp/cloudstack` PRs to `main` for `opened`, `synchronize`, `reopened`, or
`ready_for_review`. The eligibility stage repeats safety checks. Ineligible parameterized requests become
`NOT_BUILT`.

### Manual exact-SHA branch

```text
SOURCE_MODE=branch
SOURCE_BRANCH=<remote branch>
SOURCE_SHA=<full 40-character SHA reachable from that branch>
```

Jenkins validates syntax, fetches only that branch ref, checks out the exact SHA, verifies reachability from the
branch, and requires a clean worktree. A moving branch name is never the build identity.

### Local validation

```bash
./private-cicd/scripts/validate-local.sh
./private-cicd/scripts/validate-local.sh --with-docker
```

The first command checks Bash syntax, compiles Python, and parses YAML when Ruby/Psych, PyYAML, or `yq` exists.
Read the output because YAML may be skipped. The Docker option builds the driver image. Neither runs Maven, package
creation, deployment, or ONTAP tests.

### Direct throwaway VM

Use a disposable Ubuntu 22.04 nested-KVM VM for script-by-script validation. Keep temporary inventory and secrets
under `/tmp`. This path does not test Jenkins, Lockable Resources, or vCenter revert. Never reuse it as the
production inventory VM.

## 7. Prerequisites and pod resources

Required Jenkins plugins:

- Pipeline and Pipeline Utility Steps (`readYaml`);
- Kubernetes and Git;
- Lockable Resources;
- Credentials and Credentials Binding;
- Email Extension;
- Generic Webhook Trigger;
- GitHub Branch Source 2.7.1 or newer.

Jenkins must create pods that can pull both images, reach GitHub/dependency sources, vCenter, and the VM, and satisfy
the Jenkinsfile resources.

Current pod:

```text
jnlp:
  image: docker.repo.eng.netapp.com/global/devts-daas-prod/jnlp/jnlp-slave:latest
  requests: 512Mi memory, 100m CPU
  limits: 1Gi memory, 500m CPU

cloudstack-driver:
  image: docker.repo.eng.netapp.com/slocharl/cloudstack-presubmit-driver:v202608261233
  requests: 1Gi memory, 500m CPU, 5Gi ephemeral storage
  limits: 8Gi memory, 2 CPU, 20Gi ephemeral storage
```

Pipeline steps run in `cloudstack-driver`. The `jnlp` sidecar is required for agent attachment; do not override its
command or arguments.

Lab prerequisites: vCenter revert/power access; dedicated network/IP/VLAN allocations; reachable NFS and KVM
template URLs; ONTAP SVM with NFS3/iSCSI and matching data LIFs; dedicated lab accounts; and an externally reachable
HTTPS Jenkins URL for webhook and Check links.

## 8. Builder image

The current Jenkinsfile uses:

```text
docker.repo.eng.netapp.com/slocharl/cloudstack-presubmit-driver:v202608261233
```

Do not overwrite this immutable tag. Confirm workstation and Kubernetes pulls before production.

[`docker/Dockerfile.driver`](../docker/Dockerfile.driver) builds Ubuntu 22.04 with JDK 17, Maven, Node 16.20.2, npm 8,
Debian/CloudStack packaging dependencies, Git/SSH, Python, PyYAML, and pyvmomi.

Reproduce the current image reference:

```bash
export IMAGE='docker.repo.eng.netapp.com/slocharl/cloudstack-presubmit-driver:v202608261233'
DOCKER_BUILDKIT=1 docker build --no-cache --provenance=false --sbom=false \
  -f private-cicd/docker/Dockerfile.driver -t "$IMAGE" private-cicd
docker push "$IMAGE"
docker pull "$IMAGE"
docker run --rm --entrypoint /bin/bash "$IMAGE" -lc \
  'command -v sshpass java mvn node npm python3 dpkg-buildpackage dpkg-deb \
   dpkg-scanpackages sha256sum ssh scp ssh-keygen ssh-keyscan gzip gawk mysql virsh; \
   python3 -c "import setuptools, yaml, pyVim; print(\"ok\")"'
```

Only push that exact tag if publishing it for the first time. After a Dockerfile change, choose a new immutable tag,
push and pull it, then update the trusted Jenkinsfile image reference. Reused non-`latest` tags can remain cached
under Kubernetes `IfNotPresent`. This registry may reject BuildKit attestations, hence the two disabled options.
A local build does not prove a successful push; require a digest, zero status, and successful pull.

The image contains build/client tools. MySQL server, KVM daemons, nginx, NFS server, and CloudStack services are
installed on the VM, where systemd is available.

## 9. Jenkins credentials

Create credentials in a scope visible to the job:

| Suggested ID | Kind | Use |
|---|---|---|
| `cloudstack-presubmit-inventory` | Secret file | Populated inventory outside Git |
| `cloudstack-presubmit-ssh` | Username with password | Initial VM login |
| `cloudstack-vcenter` | Username with password | Snapshot revert/power-on |
| `cloudstack-mysql-root` | Username with password | DB deployment administrator |
| `cloudstack-db` | Username with password | CloudStack DB |
| `cloudstack-kvm-host` | Username with password | Marvin `addHost` |
| `cloudstack-ontap` | Username with password | ONTAP test SVM |
| `cloudstack-admin` | Username with password | Fresh CloudStack API |
| `cloudstack-presubmit-github-app` | GitHub App | Check Runs/commit email |
| `cloudstack-presubmit-webhook-token` | Secret text | Generic trigger authorization |
| site-defined optional ID | Username with password | Read-only Git access |

Parameter mapping:

| Parameter | Credential |
|---|---|
| `VM_INVENTORY_CREDENTIALS_ID` | Inventory Secret file |
| `VM_SSH_CREDENTIALS_ID` | VM username/password |
| `VCENTER_CREDENTIALS_ID` | vCenter username/password |
| `MYSQL_ROOT_CREDENTIALS_ID` | MySQL administrator |
| `CLOUD_DB_CREDENTIALS_ID` | CloudStack DB |
| `KVM_HOST_CREDENTIALS_ID` | KVM host |
| `ONTAP_CREDENTIALS_ID` | ONTAP |
| `CLOUDSTACK_ADMIN_CREDENTIALS_ID` | CloudStack API |
| `GITHUB_APP_CREDENTIALS_ID` | GitHub App |
| `GIT_CREDENTIALS_ID` | Optional read-only Git |

`VM_SSH_CREDENTIALS_ID` is not a private-key credential. The pipeline uses its password once, generates an
ephemeral Ed25519 key, installs the public key, uses key authentication, then deletes local key material.

All Jenkins password credentials must be non-empty; empty credential values do not arrive as usable environment
variables. Database passwords must work in `user:password@host`; avoid unescaped `:`, `@`, and whitespace.

Typical UI path:

**Manage Jenkins > Credentials > System/Jenkins > Global credentials (unrestricted) > Add Credentials**

The Add action appears only after opening a store and domain. Folder credentials are visible only to jobs in that
folder.

## 10. Inventory contract

```bash
cp private-cicd/config/vm-inventory.yaml.example /tmp/cloudstack-presubmit-vm-inventory.yaml
chmod 600 /tmp/cloudstack-presubmit-vm-inventory.yaml
```

Fill every placeholder, validate YAML, upload it as a Secret file, and never copy it into Git. Include no passwords,
tokens, or keys.

Each enabled VM needs:

| Field | Contract |
|---|---|
| `id` | Unique readable ID |
| `enabled` | Omitted or `true` |
| `lock_resource` | Unique Jenkins resource name |
| `capabilities` | Includes `ubuntu22`, `kvm`, `ontap` |
| `vcenter_vm`, `snapshot` | Exact case-sensitive names |
| `ssh_host`, `ssh_user` | Pod-reachable host and deployment user |
| `bridge` | Existing bridge, normally `cloudbr0` |
| `dedicated_ips.management_server` | IP already on a VM interface |
| `dedicated_ips.kvm_host` | Marvin `addHost` address |
| `dedicated_ips.public.*` | Gateway, netmask, start, end |
| `dedicated_ips.pod.*` | Gateway, netmask, start, end |
| `zone.name`, `zone.network_type` | Zone identity/type |
| `zone.dns1`, `zone.internal_dns1` | Reachable DNS |
| `zone.guest_cidr`, `zone.guest_vlan_range` | Approved non-conflicting allocation |
| `zone.pod_name`, `zone.cluster_name` | Pod/cluster names |
| `zone.primary_storage_url`, `zone.secondary_storage_url` | Reachable NFS URLs |
| `zone.systemvm_template_url`, `zone.template_name` | KVM template data |
| `ontap.storage_ip`, `ontap.svm_name` | ONTAP data LIF and SVM |

`dns2` and `internal_dns2` are optional when absent, but any present placeholder fails recursive validation. Confirm
concrete example defaults such as bridge and guest CIDR against the lab.

The Pipeline requires a non-empty VM list, no placeholders anywhere, unique non-empty resource names, at least one
enabled compatible VM, and all current required fields. Runtime rendering selects exactly one enabled VM by `id` and
checks placeholders again.

For each enabled VM create:

```text
Lockable Resource Name: <exact lock_resource>
Label: cloudstack-presubmit-vm
Reserved by: empty
```

The Pipeline locks by label, receives the resource name as `LOCKED_VM`, and maps it back to inventory.

## 11. Clean snapshot contract

The reusable baseline must:

- run Ubuntu 22.04 with unique hostname and reserved networking;
- expose `/dev/kvm` and an up configured bridge;
- already have the management IP on an interface;
- permit password SSH for the deployment account;
- have working DNS, routes, apt, ONTAP 443, NFS, template, and dependency access;
- have enough CPU, RAM, and disk for nested KVM and CloudStack;
- contain no CloudStack packages/databases, Marvin state, secrets, or previous test resources.

Take one uniquely named snapshot and record exact VM/snapshot names. The pipeline then disables apt timers, removes
third-party CloudStack sources, installs VM services, creates a local package repository, force-recreates databases,
sets API port 8096 before first management start, configures management with `--no-start`, configures libvirt/agent,
disables Docker and `ufw` in the isolated lab, and starts management once.

The next snapshot revert provides isolation. `cleanup_zone` is not automatic.

## 12. Production job setup

### Controller and plugins

1. Install section 7 plugins.
2. Set **Manage Jenkins > System > Jenkins Location > Jenkins URL** to externally reachable HTTPS; this controls
   GitHub Check Details links.
3. Configure **Extended E-mail Notification** SMTP and an accepted sender.
4. Add section 9 credentials and section 10 resources.

SMTP failure is non-fatal but should be tested.

### GitHub App

Create an organization-owned app, install it only on `NetApp/cloudstack`, disable its own webhook, and subscribe to
no events. Grant:

| Permission | Level |
|---|---|
| Checks | Read and write |
| Contents | Read-only |
| Metadata | Read-only |

Grant no organization/account permissions. Convert the downloaded key to PKCS#8:

```bash
openssl pkcs8 -topk8 -inform PEM -outform PEM \
  -in <downloaded-key>.private-key.pem -out converted-github-app.pem -nocrypt
```

The result starts with `BEGIN PRIVATE KEY`. Add Kind **GitHub App**, ID
`cloudstack-presubmit-github-app`, with App ID and converted key. Test it when possible, then delete both PEM files.
An app not installed on `cloudstack` receives API 404. The pipeline rebinds the credential per API call because
installation tokens are short-lived.

### Webhook token and webhook

```bash
openssl rand -hex 32
```

Store as Secret text `cloudstack-presubmit-webhook-token`. After first load and a good smoke test, add:

```text
Payload URL: https://<JENKINS_URL>/generic-webhook-trigger/invoke?token=<token value>
Content type: application/json
GitHub Secret/HMAC field: empty
SSL verification: enabled
Events: Pull requests only
Active: enabled
```

The query uses the Secret text value, not credential ID or App key. Do not select Pushes; PR commits produce
`synchronize`.

### Pipeline item

Create a regular Pipeline, not Freestyle, Multibranch, or Organization:

```text
Name: cloudstack-ontap-presubmit
Definition: Pipeline script from SCM
SCM: Git
Repository: https://github.com/NetApp/cloudstack.git
Credentials: none or read-only Git credential
Branch: */main
Script Path: private-cicd/Jenkinsfile
Lightweight checkout: off
Concurrent builds: enabled
Job-level abort previous: disabled
```

Leave UI triggers unchecked; the Jenkinsfile declares Generic Webhook Trigger.

### First load and script approvals

Click **Build Now** once. Expected: request validation fails because `PR_ID` is empty, but Jenkins loads parameters
and trigger. Refresh, confirm **Build with Parameters**, then confirm **Configure > Build Triggers > Generic Webhook
Trigger** uses `cloudstack-presubmit-webhook-token`.

`Abort superseded run` uses Jenkins internal APIs. Under **Manage Jenkins > In-process Script Approval**, approve
only signatures actually requested by this trusted `WorkflowScript` for obtaining the job, enumerating builds,
reading parameters/environment, and stopping a matching build. Never approve unrelated calls or use **Approve
assuming permission check**. Exact signatures vary by controller/plugin versions.

### Parameterized smoke test

Before enabling webhook, run a real non-draft PR manually:

```text
SOURCE_MODE=pull_request
PR_ID=<number>
PR_ACTION=opened
PR_DRAFT=false
PR_REPOSITORY=NetApp/cloudstack
PR_BASE_BRANCH=main
PR_HEAD_BRANCH=<head branch>
PR_HEAD_SHA=<full 40-character SHA>
EXPECTED_REPOSITORY=NetApp/cloudstack
CLOUDSTACK_GIT_URL=https://github.com/NetApp/cloudstack.git
GITHUB_APP_CREDENTIALS_ID=cloudstack-presubmit-github-app
VM_INVENTORY_CREDENTIALS_ID=cloudstack-presubmit-inventory
VCENTER_HOST=<hostname without scheme>
<runtime credential parameters>=<section 9 IDs>
PAUSE_BETWEEN_STAGES=true
```

Confirm exact-SHA Check, start mail attempt, clean checkout, Stage 1/2 pass, matching Check conclusion, one final
mail attempt, and secret-free archives. Turn pauses off for normal webhook operation.

### Required Check

After the Check exists, edit **Repository Settings > Branches > main rule**, enable required status checks, and
select exactly:

```text
cloudstack-ontap-presubmit
```

Without protection it is informational. Verify pending/failed results block merge and each new SHA needs a new Check.

## 13. Manual branch job and exact SHA

Create a separate Pipeline-from-SCM job:

```text
Repository: https://github.com/NetApp/cloudstack.git
Branch: */<remote feature branch>
Script Path: private-cicd/Jenkinsfile
Lightweight checkout: off
All triggers: off
Concurrent builds: enabled
```

The SCM branch selects executable CI; source parameters independently select product source.

```bash
git push origin <branch>
git fetch origin <branch>
BRANCH='<branch>'
LOCAL_SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git rev-parse "origin/$BRANCH")"
test "$LOCAL_SHA" = "$REMOTE_SHA"
printf '%s\n' "$REMOTE_SHA"
```

Run:

```text
SOURCE_MODE=branch
SOURCE_BRANCH=<branch>
SOURCE_SHA=<full REMOTE_SHA>
PR_* fields=blank except PR_DRAFT=false and optional author email/title
EXPECTED_REPOSITORY=NetApp/cloudstack
CLOUDSTACK_GIT_URL=https://github.com/NetApp/cloudstack.git
VM_INVENTORY_CREDENTIALS_ID=cloudstack-presubmit-inventory
VCENTER_HOST=<real hostname>
<runtime credential parameters>=<section 9 IDs>
```

Branch mode creates no GitHub Check. It sends mail only when an address is available. The first unparameterized run
may fail while loading parameters. Keep the job triggerless and review approvals against the untrusted branch.

## 14. Jenkins flow by stage

The Pipeline has an eight-hour timeout and retains 30 builds.

### Validate source request

Records start time/integration false; validates mode, branch/SHA or PR/repository, HTTPS `.git` URL, credential IDs,
and vCenter hostname; sets display name/description. PR repository must match `EXPECTED_REPOSITORY`.

### Check eligibility

Branch mode continues. PR mode requires an eligible action, non-draft state, and base `main`; otherwise it becomes
`NOT_BUILT`.

### Abort superseded run

Source key is `pull_request:<PR_ID>` or `branch:<SOURCE_BRANCH>`. Only an older running build of this job with the
same key is considered. It is stopped only while `PRESUBMIT_INTEGRATION_STARTED` is not true. That flag becomes true
immediately after VM lock acquisition. Maven, packaging, and lock wait are abortable; snapshot revert onward is not.

### Checkout CI scripts

Deletes workspace and runs `checkout(scm)`. Production SCM therefore must be protected `main`.

### Start PR reporting

PR mode creates `cloudstack-ontap-presubmit` on exact head SHA, links `BUILD_URL`, resolves payload or commit email,
ignores GitHub noreply addresses, and attempts one start mail. GitHub/SMTP errors do not change the build result.

### Validate builder

Rejects empty/placeholder configuration, validates inventory before a lock, runs `check-build-prereqs.sh` in
`cloudstack-driver`, and optionally pauses.

### Checkout source

Fetches `refs/pull/<PR_ID>/head` or `refs/heads/<SOURCE_BRANCH>`, then:

```text
honorRefspec: true
noTags: true
shallow: false
```

Checkout is not shallow. Jenkins requires exact `HEAD`, reachability from fetched ref, clean status, and writes
`presubmit-results/source.properties`.

### Build and unit tests

Calls `mvn-full.sh` with simulator false, `MAVEN_THREADS=1C`, `MAVEN_OPTS=-Xms1g -Xmx8g`, mode-independent source ID,
and exact SHA.

### Build Debian packages

Calls `build-debs.sh` with `REUSE_MAVEN_BUILD=true`, the same Maven options, and output
`cloudstack-src/dist/deb-all`.

### Deploy and run ONTAP integration

Locks one `cloudstack-presubmit-vm`; marks integration started inside the lock; maps inventory; reverts exact
snapshot with `--insecure` internal-lab TLS mode; powers on; optionally pauses while holding lock; binds runtime
credentials; runs remote Phase 2; and holds the lock through result retrieval/cleanup.

### Post actions

Always publishes Maven Surefire XML as JUnit (empty allowed), maps `SUCCESS`/`ABORTED`/other to
`success`/`cancelled`/`failure`, updates the collated log, completes an existing Check, attempts one final mail, and
archives/fingerprints `presubmit-results/**/*` plus `cloudstack-src/dist/deb-all/**/*`. `NOT_BUILT` gets no final PR
reporting.

## 15. Stage 1 contracts

### Builder

`check-build-prereqs.sh` requires Java 17, Maven, Node 16.x, npm 8.x, Git, Python with setuptools/PyYAML/pyvmomi,
Debian tools, checksum tools, and SSH/scp/sshpass/key tools.

### Maven

Effective Jenkins command:

```bash
mvn -B -P developer,systemvm clean install -T1C \
  -Dsystemvm -Dcs.replace.properties=replace.properties.tmp
```

Simulator/noredist are off and tests remain enabled. Packaging `replace.properties` plus project version allows
target reuse.

```text
presubmit-results/unit-tests/
├── maven.log
├── stage1-handoff.properties
├── surefire/<module paths>/target/surefire-reports/*.xml
└── surefire-reports.tar.gz
```

The handoff records source ID/SHA, mirrored legacy PR keys, Maven result, and report count. Failure prevents package
creation while preserving available results outside the source tree.

### Debian packages

The script edits only workspace `debian/rules` to skip its second Maven build, then runs:

```bash
dpkg-buildpackage -uc -us -b -d -nc
```

`-nc` preserves target; Debian configuration, npm UI, install, and assembly remain. `-d` skips dependency checks, so
the immutable image must supply dependencies.

Required packages: `cloudstack-common`, `cloudstack-agent`, `cloudstack-management`. Required handoff:

```text
cloudstack-src/dist/deb-all/
├── *.deb, *.changes, *.buildinfo
├── deb-build.log
├── cloudstack-{common,agent,management}.contents
├── package-manifest.tsv
├── SHA256SUMS
└── marvin/
    ├── Marvin-*.tar.gz
    ├── commands.xml
    ├── manifest.tsv
    └── SHA256SUMS
```

Missing/duplicate Marvin, API metadata, package, expected contents, or checksum fails before VM acquisition.

## 16. Phase 2 contracts

### Render and transfer

`render-phase2-config.py` combines inventory with DB, KVM, ONTAP, and CloudStack API credentials; enables both
protocols; sets API port 8096; and writes mode-`0600` `ontap.cfg` and `secrets.json`.

`run-phase2-remote.sh` verifies Marvin checksum, generates an Ed25519 key, waits up to 900 seconds for password SSH,
uses strict checking against a freshly scanned temporary known-hosts file, and enables keepalives. Remote root:

```text
/tmp/cloudstack-phase2-<SOURCE_ID>-<first-12-SHA>
```

### Configure gate

`configure-cloudstack-vm.sh`:

1. requires root;
2. masks apt timers, waits on locks, and signals only apt-family holders;
3. installs VM dependencies and configures MySQL;
4. exports local primary/secondary NFS;
5. disables active Docker and `ufw` in the isolated lab;
6. serves exact built packages from local nginx apt;
7. installs exact common/management versions;
8. force-recreates databases and inserts API port 8096 before first start;
9. runs `cloudstack-setup-management --no-start`;
10. installs exact agent, configures unauthenticated lab libvirt TCP and bridge;
11. starts management once, then restarts agent.

If setup lacks `--systemvm-templates`, the script fallback installs the published 4.22.0 KVM template URL directly;
do not assume that fallback comes from inventory.

### Health gate

Requires active MySQL/libvirtd/iscsid/NFS/agent/management; manifest-matching package versions; `/dev/kvm`; bridge;
local management IP; virsh; iSCSI IQN; both NFS exports; ONTAP module in the shaded management JAR; DB access; API
8096 within 900 seconds; ONTAP 443; and SVM protocol/data-LIF prerequisites for both protocols.

The plugin is shaded into the management uber-JAR; no standalone ONTAP JAR is expected.

### Marvin order

Marvin comes from Stage 1 and is installed into an isolated environment. Tests use `nose_compat.py`.

1. `run_tests.sh setup_zone`
2. `run_tests.sh iscsi`
3. `run_tests.sh nfs3`

Zone failure prevents protocols. NFS3 is still attempted after iSCSI failure, then combined result fails. Suites are
sequential because they share one KVM host. `cleanup_zone` is intentionally absent.

See [`test/integration/plugins/ontap/README.md`](../../test/integration/plugins/ontap/README.md) for suite semantics.

### Exit

Every VM-wrapper exit collects system/CloudStack/Marvin logs best-effort, redacts literal/base64 password values,
writes `phase2.properties`, and deletes generated secrets. Jenkins retrieves via SSH/tar and removes remote root.
Successful tests with failed retrieval still fail the stage.

## 17. Reporting and artifacts

PR mode creates one `cloudstack-ontap-presubmit` Check on exact SHA, starts it `in_progress`, links the build, then
completes the same Check as `success`, `failure`, or `cancelled`. API failures are non-fatal to Jenkins. A required
Check still remains merge-safe when API reporting fails because no required success exists.

`presubmit-results/stage-events.tsv` contains structured UTC stage events. The final post action converts text logs
to HTML, writes `presubmit-results/report.html`, and archives those files before sending mail. Mail behavior:

- one short HTML start mail styled like the final report, with a `STARTED` banner and a bordered table of source,
  title, diff number, SHA, and start time, plus build and console links;
- one final HTML mail with result, diff number, VM, total duration, and a stage table with start time, duration, VM
  wait, and links to the applicable HTML log;
- a complete ONTAP test table with failures first and per-test links into the archived suite HTML logs;
- no raw log tails in the message body; use the linked report or log to debug;
- payload email first, then commit email; noreply ignored;
- missing address or SMTP failure does not alter result;
- no per-stage threading because Email Extension provides no stable Message-ID.

Both mails share the subject base `CloudStack presubmit PR-<id> <title>, diff #<n> (<sha12>)`, so mail clients thread
the pair. The diff number counts the distinct commits presubmitted for that PR: re-running the same commit keeps its
number and each new push increments it. Only builds still retained by `logRotator(numToKeepStr: '30')` are inspected,
so a PR with more than 30 intervening builds can report a lower number. When the lookup fails the subject falls back
to `diff <sha12>` and the diff row is omitted.

Jenkins archives:

```text
presubmit-results/**/*
cloudstack-src/dist/deb-all/**/*
```

Key paths:

- browsable report: `presubmit-results/report.html`;
- stage events: `presubmit-results/stage-events.tsv`;
- Maven/JUnit: `presubmit-results/unit-tests/`;
- packages: `cloudstack-src/dist/deb-all/`;
- configure/health: `presubmit-results/phase2/{configure-cloudstack,health-check}.log`;
- Marvin: `presubmit-results/phase2/marvin/`;
- runtime: `presubmit-results/phase2/runtime-logs/`;
- marker: `presubmit-results/phase2/phase2.properties`.

Phase 2 summaries are archived but not currently Jenkins JUnit.

## 18. Direct VM/script validation

Use root on a disposable clean VM.

### Validate, build, package

```bash
export SOURCE_DIR=/root/cloudstack
export CICD_DIR="$SOURCE_DIR/private-cicd"
cd "$SOURCE_DIR"
test -z "$(git status --porcelain)"
"$CICD_DIR/scripts/validate-local.sh"
"$CICD_DIR/scripts/check-build-prereqs.sh"

export RESULT_DIR=/tmp/presubmit-results/unit-tests
export SOURCE_ID=local
export SOURCE_SHA="$(git rev-parse HEAD)"
export ENABLE_SIMULATOR=false MAVEN_THREADS=1C MAVEN_OPTS='-Xms1g -Xmx8g'
"$CICD_DIR/scripts/mvn-full.sh"

export RESULT_DIR="$SOURCE_DIR/dist/deb-all" REUSE_MAVEN_BUILD=true
"$CICD_DIR/scripts/build-debs.sh"
```

Do not package after Maven failure. Verify three packages, manifests, Marvin, commands XML, and checksums.

### Render temporary config

Create `/tmp/throwaway-inventory.yaml` from the example, remove every placeholder, use `id: throwaway-vm`, and give
non-placeholder dummy values to Jenkins-only fields. Read secrets without shell history:

```bash
read -rp 'MySQL admin user: ' MYSQL_ROOT_USER
read -rsp 'MySQL admin password: ' MYSQL_ROOT_PASSWORD; echo
read -rp 'Cloud DB user: ' CLOUD_DB_USER
read -rsp 'Cloud DB password: ' CLOUD_DB_PASSWORD; echo
read -rp 'KVM host user: ' KVM_HOST_USER
read -rsp 'KVM host password: ' KVM_HOST_PASSWORD; echo
read -rp 'ONTAP user: ' ONTAP_USER
read -rsp 'ONTAP password: ' ONTAP_PASSWORD; echo
read -rp 'CloudStack API user: ' CLOUDSTACK_ADMIN_USER
read -rsp 'CloudStack API password: ' CLOUDSTACK_ADMIN_PASSWORD; echo
export MYSQL_ROOT_USER MYSQL_ROOT_PASSWORD CLOUD_DB_USER CLOUD_DB_PASSWORD
export KVM_HOST_USER KVM_HOST_PASSWORD ONTAP_USER ONTAP_PASSWORD
export CLOUDSTACK_ADMIN_USER CLOUDSTACK_ADMIN_PASSWORD

python3 "$CICD_DIR/scripts/render-phase2-config.py" \
  --inventory /tmp/throwaway-inventory.yaml --vm-id throwaway-vm \
  --ontap-config /tmp/ontap.cfg --secrets /tmp/secrets.json
```

Do not print those files.

### Build workspace and run gates

Create `/tmp/cloudstack-phase2-manual` with `scripts/`, `source/`, `debs/`, and `results/`. Copy the six VM-side
scripts plus `check_ontap_prereqs.py`; ONTAP tests and generated `ontap.cfg`; one Marvin archive; packages and
manifest; and `secrets.json`. Set secrets/config mode `0600`.

```bash
export PHASE2_ROOT=/tmp/cloudstack-phase2-manual BRIDGE=cloudbr0
set -o pipefail
DEB_DIR="$PHASE2_ROOT/debs" SECRETS_FILE="$PHASE2_ROOT/secrets.json" \
ONTAP_CONFIG="$PHASE2_ROOT/source/test/integration/plugins/ontap/ontap.cfg" BRIDGE="$BRIDGE" \
  "$PHASE2_ROOT/scripts/configure-cloudstack-vm.sh" 2>&1 \
  | tee "$PHASE2_ROOT/results/configure-cloudstack.log"

MANIFEST_FILE="$PHASE2_ROOT/debs/package-manifest.tsv" \
ONTAP_CONFIG="$PHASE2_ROOT/source/test/integration/plugins/ontap/ontap.cfg" BRIDGE="$BRIDGE" \
  "$PHASE2_ROOT/scripts/check-phase2-health.sh" 2>&1 \
  | tee "$PHASE2_ROOT/results/health-check.log"

cd "$PHASE2_ROOT/source"
bash test/integration/plugins/ontap/run_tests.sh setup_zone
bash test/integration/plugins/ontap/run_tests.sh iscsi
bash test/integration/plugins/ontap/run_tests.sh nfs3

OUTPUT_DIR="$PHASE2_ROOT/results" "$PHASE2_ROOT/scripts/collect-phase2-logs.sh"
python3 "$PHASE2_ROOT/scripts/redact-phase2-results.py" \
  --results "$PHASE2_ROOT/results" --secrets "$PHASE2_ROOT/secrets.json" \
  --ontap-config "$PHASE2_ROOT/source/test/integration/plugins/ontap/ontap.cfg"
```

Delete generated secrets after redaction. `pipefail` prevents `tee` hiding a failed gate. After partial
configuration, rebuild/revert rather than stacking another run on uncertain state.

To test the wrapper, rebuild a clean workspace and run only:

```bash
PHASE2_ROOT=/tmp/cloudstack-phase2-manual BRIDGE=cloudbr0 \
  /tmp/cloudstack-phase2-manual/scripts/run-phase2-vm.sh
```

Expect `PHASE2_RESULT=SUCCESS` and deleted generated secret files.

## 19. Concurrency

Keep concurrent builds enabled and job-level abort-previous disabled.

- same PR ID: newer revision aborts older run only before lock;
- same manual source branch: newer exact SHA aborts older run only before lock;
- different PR IDs or branch names never abort each other;
- after lock acquisition, older integration finishes independently;
- with one VM, builds compile/package concurrently and serialize at Phase 2;
- aborted PR Check concludes `cancelled`; protected old/new revisions report separately.

The boundary is VM lock acquisition, not stage entry or snapshot completion. Test same-source cancellation during
Maven and after `Deploy and run ONTAP integration STARTED`, plus different PRs concurrently.

## 20. Troubleshooting

### Pod or builder

No stage output, `ErrImagePull`, `ImagePullBackOff`, or `FailedScheduling`: verify exact image existence, pull secret,
namespace, `jnlp`, pod events, and current requests. Pull occurs before scripts can diagnose it.

Builder check failure: wrong Java/Node/npm or missing sshpass/PyYAML/pyvmomi/setuptools means stale/cached image.

`OOMKilled` means the 8Gi cgroup limit was exceeded; `FailedScheduling` means requests could not be satisfied. The
authoritative memory request is 1Gi, limit 8Gi, with Maven heap options `-Xms1g -Xmx8g`.

### Source, Groovy, Maven, package

Source failure: verify full SHA, selected PR/branch ref, reachability, credential, HTTPS `.git` URL, and branch
syntax. Checkout is full, so do not diagnose it as shallow history.

Groovy rejection: approve only the reported trusted WorkflowScript signature. Later skipped stages and empty-JUnit
messages are symptoms.

Maven/package: inspect `maven.log` then `deb-build.log`. Exit 141 before Maven can be shell SIGPIPE under `pipefail`.
Target reuse requires `client/target`, API `commands.xml`, and exactly one Marvin archive.

### Inventory, lock, snapshot, SSH

Inventory errors: placeholder, missing field, no compatible enabled VM, or duplicate/empty resource. Lock wait:
missing label, name mismatch, reservation, or another build.

Snapshot: verify vCenter host/permission/network and exact unique names. SSH: verify password login in snapshot,
Username-with-password Kind, correct user/password, pod-reachable host, and boot within 900 seconds.

### VM configure and health

Apt: current script disables timers, waits 300 seconds, signals only apt-family holders, repairs dpkg, and retries.
It deliberately leaves a non-apt lock holder alone and logs it.

Duplicate schema key/column: likely interrupted first upgrade or stale script. Current flow force-recreates DBs,
sets 8096 before start, uses `--no-start`, and starts management once. Revert cleanly; do not repair ad hoc.

API timeout: inspect management journal/log and DB config. Initial upgrade can take minutes; gate waits 900 seconds.

Template 404: published names may use three version components, such as
`systemvmtemplate-4.22.0-x86_64-kvm.qcow2.bz2`; four-part project versions can be wrong. HTTP `000` is connectivity.

ONTAP: inspect health, setup-env, setup_zone, iscsi/nfs3 logs, summaries, and copied Marvin logs. iSCSI hot-unplug
error 530 may be a guest/lab limitation but remains a failure.

The final mail and `presubmit-results/report.html` list every Marvin test with failures first. Select a test's
**HTML log** link to open its archived suite log at that test. For an iSCSI VM workflow failure, use the link under
`phase2/marvin/ontap-results/.../suites/iscsi_vm_workflow/stdout.log.html`; do not diagnose it from Maven,
configure, or health logs that already passed.

### GitHub and mail

- rejected App key: require PKCS#8 `BEGIN PRIVATE KEY`;
- missing GitHub App Kind: update GitHub Branch Source;
- Check 401: bad credential; 403: permission/rate; 404: installation/repository;
- broken Details link: fix Jenkins URL;
- webhook 404: job has not loaded trigger; 403: token mismatch;
- HTTP 200/no run: inspect action and trigger filter;
- branch mode: no Check is expected.

Use **Settings > Webhooks > Recent Deliveries**. Never log token/raw payload.

Mail failures appear as `Presubmit mail was not sent`; no address, noreply address, or SMTP failure does not change
the build.

## 21. Production checklist

- [ ] trusted `main` contains reviewed current CI code;
- [ ] immutable builder image pulls in Kubernetes;
- [ ] driver request is 1Gi and limit is 8Gi;
- [ ] plugins, external HTTPS Jenkins URL, and SMTP are configured;
- [ ] credentials have correct Kinds and non-empty passwords;
- [ ] populated inventory remains outside Git;
- [ ] each enabled VM maps to a labeled resource and clean snapshot;
- [ ] production SCM is `*/main` after CSTACKEX-223 is merged (until then `*/feature/CSTACKEX-223`), lightweight off, and source checkout full;
- [ ] first load applied parameters/trigger and only needed signatures are approved;
- [ ] parameterized PR smoke run passed;
- [ ] App installation, exact-SHA Check conclusion, and webhook HTTP 200 are proven;
- [ ] Check is required on `main`;
- [ ] concurrency behavior is proven;
- [ ] archives contain no generated config, token, key, or secrets;
- [ ] `PAUSE_BETWEEN_STAGES` is off for normal webhook runs.

## 22. Remaining gaps and future invariants

Remaining delivery gaps:

1. stable external per-PR result location;
2. retention, cleanup, ownership, and access policy for it.

Current limitations: internal-lab vCenter `--insecure`; Phase 2 not Jenkins JUnit; snapshot-based rather than
automatic zone cleanup; and unauthenticated libvirt TCP suitable only for an isolated lab.

Future changes must preserve trusted-main execution, exact-SHA identity/reachability, secret separation, package
gates before lock, source-key cancellation only before lock, lock ownership through result retrieval, and
notification failures not replacing test results.

## 23. Upstream hygiene

Before an Apache PR:

```bash
git fetch apache
git log --oneline apache/main..HEAD -- private-cicd/
git diff --name-only apache/main...HEAD
```

If private CI commits appear, branch from the intended `apache/main` and cherry-pick only upstream product/test
changes. The private-cicd log should be empty. ONTAP product/integration tests may belong upstream; NetApp Jenkins,
inventory operations, credentials, and private CI implementation do not.
