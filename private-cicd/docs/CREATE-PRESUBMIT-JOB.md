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

# Create the auto-triggered ONTAP presubmit job

This guide creates **one** Pipeline job, `cloudstack-ontap-presubmit`. Timer
builds poll GitHub every five minutes and self-queue one worker build for each
new eligible PR head SHA. Worker builds compile and package concurrently, then
wait for an available `cloudstack-presubmit-vm` Lockable Resource before
integration. Until `feature/CSTACKEX-223` is merged, the job loads trusted CI
from that branch. After merge, change only **Branches to build** to `*/main`.

Do not create a GitHub repository webhook. OpenLab Jenkins is not reachable
from GitHub.com, so inbound `pull_request` deliveries fail with
`failed to connect to host`. That is expected. NetApp's
[CI/CD in Github](https://netapp.atlassian.net/wiki/spaces/NGAGE/pages/121930531/CI+CD+in+Github)
guide says to leave the GitHub App webhook inactive and to **poll** instead.
Do **not** create a Jenkins **GitHub Organization** / **Organization Folder**
item for this presubmit: that would run `Jenkinsfile` from the PR branch. This
design loads trusted CI from a fixed branch and checks the PR SHA out
separately.

Do not create a separate poller, Multibranch Pipeline, or second production
copy of this job.

This guide starts before the Jenkins item exists and ends after polling and
required Check have been verified. Follow sections 1 through 21 in order. Do
not skip a section merely because `cloudstack-presubmit-manual` already works;
each relevant section says whether to confirm existing controller state or
create new state.

### Where to start if you already followed an earlier revision

If the job, GitHub App credential, and parameterized smoke test already
work, do not recreate them. Start here:

1. On GitHub, deactivate or delete any `NetApp/cloudstack` webhook whose payload
   URL points at Jenkins. GitHub cannot deliver it.
2. On the GitHub App, keep **Webhook active** unchecked and add
   **Pull requests: Read-only** if that permission is missing (section 9.1).
   Also confirm **Checks: Read and write** is both requested *and* approved on
   the installation; the NetApp guide omits it, so an App built from that guide
   cannot publish the Check (section 9.0).
3. Push this branch so the polling-enabled `private-cicd/Jenkinsfile` is on
   `origin/feature/CSTACKEX-223`.
4. Create the named Lockable Resource `cloudstack-presubmit-discovery`
   (section 8).
5. **Build Now** once on `cloudstack-ontap-presubmit`. This first `discover`
   build seeds the watermark and queues no existing PRs.
6. Push a new commit to the PR, then continue at section 16.

The executable pipeline is [`../Jenkinsfile`](../Jenkinsfile). For pipeline
behavior and troubleshooting beyond job creation, see
[`PRIVATE-CICD-GUIDE.md`](PRIVATE-CICD-GUIDE.md).

## Before you begin

The manual job and this job load the same Jenkinsfile, use the same lab VM, and
use the same lab credentials. Reuse that controller state; do not recreate it.
You will still open each Jenkins screen in this guide and confirm the existing
value before continuing.

Do not create duplicate credentials, a second Lockable Resource, or a second
inventory Secret file. Duplicated lab state is the most common cause of a
presubmit that hangs on the lock or configures the wrong VM.

The following items should already exist from `cloudstack-presubmit-manual`:

| Section | Existing state | What to do now |
|---|---|---|
| 4.1 | Most plugins already installed | Confirm every plugin; install any missing plugin |
| 4.3 | Extended E-mail SMTP configured | Confirm sender still resolves |
| 4.4 | Kubernetes cloud proven by manual runs | Confirm the same cloud remains available |
| 5.1 | Driver image already pulled by the pod | Confirm the tag is unchanged |
| 5.2 | Clean VM snapshot exists | Confirm the last manual run left it clean |
| 5.3 | Inventory YAML populated | Confirm it has no placeholders; keep it outside Git |
| 6.1 | `cloudstack-presubmit-inventory` Secret file | Confirm the ID resolves |
| 6.2 | The 7 username/password credentials | Confirm the IDs resolve |
| 6.3 | Optional Git credential decision | Reuse the existing decision |
| 8 | VM Lockable Resources labeled `cloudstack-presubmit-vm` | Reuse them; add the discovery lock |
| 13 | Groovy signatures approved during manual runs | Approve only new pending ones |

The 7 reused username/password credentials are `cloudstack-vcenter`,
`cloudstack-presubmit-ssh`, `cloudstack-mysql-root`, `cloudstack-db`,
`cloudstack-kvm-host`, `cloudstack-ontap`, and `cloudstack-admin`.

In-process Script Approval is controller-wide, not per job. Signatures approved
while testing the manual job stay approved for this job, so section 13 is
normally a short confirmation rather than a full pass.

If the manual job ran with a folder-scoped credential store, this job must live
in the same folder or a descendant of it. Otherwise the credential IDs will not
resolve and the build fails during **Validate source request**.

The following work is new for the automatic job:

| Section | New because |
|---|---|
| 4.1 | GitHub Branch Source is required for the GitHub App credential |
| 4.2 | Jenkins URL is still required for Check **Details** links, not for inbound GitHub webhooks |
| 7 | Self-queued workers need committed non-interactive defaults |
| 9 | The manual job never authenticated to GitHub; discovery needs Pull requests read |
| 10 | The manual job must stay disabled so it does not publish the same Check |
| 11, 12 | This is a new Pipeline item and needs a bootstrap/discovery run |
| 14 | The manual job only exercised `SOURCE_MODE=branch` |
| 15, 16 | The polling mode and its verification are new |
| 17 | The GitHub Check must be required on `main` to block merges |
| 20 | The trusted Pipeline SCM must move to `*/main` after merge |

Terms used in the Jenkins UI:

- **Jenkins controller:** the Jenkins web site where you create jobs and
  credentials.
- **Pipeline job:** the `cloudstack-ontap-presubmit` page you create in
  section 11.
- **Pipeline SCM:** the trusted branch from which Jenkins reads the Jenkinsfile;
  it is not necessarily the PR branch being tested.
- **Credential ID:** a non-secret name Jenkins uses to locate a stored secret.
  Enter the ID exactly; never enter the password itself in a Jenkinsfile field.
- **Discovery build:** a short `SOURCE_MODE=discover` run started by Jenkins
  cron. It calls GitHub and self-queues worker builds; GitHub never connects to
  Jenkins.

If a named menu is missing, stop and ask the Jenkins administrator. Jenkins
permissions and plugin versions can hide or rename administration pages.

## 1. Understand what will run

The job has two different source locations:

1. **Trusted pipeline source:** Jenkins loads `private-cicd/Jenkinsfile` and
   its helper scripts from the job's SCM branch (`feature/CSTACKEX-223` now,
   `main` after cutover in section 20).
2. **Source under test:** A self-queued worker fetches the exact PR head SHA.

The PR cannot replace the Jenkinsfile used by this job. The Jenkinsfile checks
out the PR separately under `cloudstack-src/`, verifies that `HEAD` equals
`PR_HEAD_SHA`, and requires that SHA to be reachable from
`refs/pull/<PR_ID>/head`.

Discovery queues a worker build when all of these are true:

- the repository is `NetApp/cloudstack`;
- the PR is open, not a draft, and targets `main`;
- the PR was updated after the latest successful discovery watermark;
- the job has no queued or recorded worker for that PR number and head SHA;
- fewer than `MAX_ACTIVE_WORKERS` (default 5) `pull_request` workers are already
  queued or running.

Discovery queues oldest-updated eligible PRs first. Extra SHAs are deferred to
the next poll and the watermark is held so they are not skipped.

Discovery sets `PR_ACTION` to `opened` for the first SHA of a PR and
`synchronize` for later SHAs. Worker eligibility still accepts
`reopened` and `ready_for_review` for manual parameterized runs.

A push to a PR branch is visible to GitHub immediately. Jenkins notices it on
the next poll, typically within five minutes, not at the instant of `git push`.

### Security boundary

Only trusted maintainers may push to `feature/CSTACKEX-223` while it is the
job's SCM branch. That branch supplies executable Groovy and shell scripts to a
job that can access Jenkins credentials and a lab VM. After cutover, protect
`main` the same way and stop treating the feature branch as trusted CI.

Discovery currently does not inspect whether the PR head repository is a fork.
Do not enable it for this lab unless PR authors and PR code are trusted to run
in the credentialed ONTAP lab. If fork PRs are accepted, add and validate a
head-repository restriction before using this design.

## 2. Values to collect

Record these values before opening Jenkins:

| Value | Example or required form |
|---|---|
| Jenkins external URL | `https://jenkins.example.net/` |
| Jenkins job name | `cloudstack-ontap-presubmit` |
| Trusted pipeline branch | `feature/CSTACKEX-223` |
| Git repository | `https://github.com/NetApp/cloudstack.git` |
| Script Path | `private-cicd/Jenkinsfile` |
| Driver image | The immutable image in the Jenkinsfile pod template |
| vCenter host | Hostname only; no scheme or path |
| Inventory file | Populated copy of `vm-inventory.yaml.example` outside Git |
| Inventory VM | VM name, clean snapshot, SSH host/user, and lock resource |
| Test PR | Non-draft PR to `NetApp/cloudstack:main` |
| Test PR head SHA | Full 40-character SHA |

You also need the credential IDs listed in section 6.

## 3. Confirm the trusted branch

Run these commands from the CloudStack repository:

```bash
git fetch origin feature/CSTACKEX-223
git status --short
git rev-parse HEAD
git rev-parse origin/feature/CSTACKEX-223
```

Requirements:

- the two SHAs are identical;
- all intended Jenkinsfile and script changes are committed and pushed;
- the working tree contains no change that the Jenkins job needs;
- only trusted maintainers can update this branch.

In the GitHub UI, protect or restrict the branch while it is used as pipeline
source:

1. Open `NetApp/cloudstack`.
2. Select **Settings**.
3. Open **Branches** or **Rules > Rulesets**, depending on the repository UI.
4. Create or verify a rule for `feature/CSTACKEX-223`.
5. Restrict pushes to the maintainers operating this test.
6. Do not allow force pushes while this branch is trusted CI.

The job always reads the remote branch. Local uncommitted files are invisible
to Jenkins.

## 4. Confirm Jenkins prerequisites

You need Jenkins administrator help for controller, plugin, credential, and
script-approval operations.

### 4.1 Required plugins

In Jenkins:

1. Select **Manage Jenkins**.
2. Select **Plugins**.
3. Open **Installed plugins**.
4. Search for and confirm each plugin:

| Plugin | Pipeline use |
|---|---|
| Pipeline | Declarative pipeline execution |
| Pipeline Utility Steps | `readYaml` for VM inventory |
| Kubernetes | Jenkins agent pod |
| Git | trusted and PR source checkout |
| Lockable Resources | exclusive integration VM |
| Credentials | credential storage |
| Credentials Binding | temporary secret bindings |
| Email Extension | start and final email |
| GitHub Branch Source 2.7.1+ | GitHub App credential kind and token exchange |

Generic Webhook Trigger is **not** required. Do not install it for this
presubmit. GitHub.com cannot POST to OpenLab Jenkins.

If any plugin is missing:

1. Open **Available plugins**.
2. Select the plugin.
3. Choose the site's approved installation option.
4. Restart Jenkins only when the controller requires it.
5. Return to **Installed plugins** and confirm the plugin is enabled.

### 4.2 Jenkins URL

The GitHub Check links back to `BUILD_URL`, so Jenkins needs a public HTTPS URL
that GitHub users can reach.

1. Select **Manage Jenkins > System**.
2. Find **Jenkins Location**.
3. Set **Jenkins URL** to the externally reachable HTTPS root URL.
4. Ensure it ends in `/`.
5. Save.

Do not use a pod-local hostname, private controller service name, or
`localhost`.

The URL is used for GitHub Check **Details** links that people open in a
browser, often over VPN. GitHub.com never needs to connect to this host. Do
not create a repository webhook pointing at Jenkins; GitHub deliveries will
fail with `failed to connect to host`.

### 4.3 Email

The pipeline tolerates SMTP failure, but email should still be configured:

1. Select **Manage Jenkins > System**.
2. Find **Extended E-mail Notification**.
3. Confirm the SMTP server, port, TLS mode, credential, and default sender.
4. Use **Test configuration by sending test e-mail**, if available.
5. Save.

A missing PR email or SMTP failure does not replace the build result.

### 4.4 Kubernetes agent

Confirm the configured Jenkins Kubernetes cloud can:

- start a pod in the intended namespace;
- pull the `jnlp` and `cloudstack-driver` images in the Jenkinsfile;
- provide the image pull secret, if required;
- reach GitHub, Maven/npm/package sources, vCenter, and the inventory VM;
- satisfy the pod CPU, memory, and ephemeral-storage requests.

The current driver container requests 1 GiB memory and limits memory to 8 GiB.
The `jnlp` container is required for Jenkins agent attachment.

## 5. Prepare the driver image and lab VM

### 5.1 Driver image

Open the Kubernetes pod YAML in
[`../Jenkinsfile`](../Jenkinsfile) and record the exact
`cloudstack-driver` image.

From a machine with registry access:

```bash
docker pull <exact-cloudstack-driver-image>
docker image inspect <exact-cloudstack-driver-image> --format '{{index .RepoDigests 0}}'
```

Use an immutable tag. If the Dockerfile changed, publish a new tag and update
the trusted branch before creating the job. Do not overwrite an existing
immutable tag because Kubernetes may retain an `IfNotPresent` cached image.

### 5.2 Clean snapshot

The inventory VM must satisfy the clean snapshot contract in the comprehensive
guide. At minimum, confirm:

- Ubuntu 22.04;
- `/dev/kvm` exists;
- the configured bridge, normally `cloudbr0`, exists and is up;
- the management IP is configured;
- password SSH is available to the deployment user;
- DNS, apt repositories, ONTAP HTTPS, NFS, and template URLs are reachable;
- no previous CloudStack packages, databases, Marvin state, or credentials
  remain.

Take one clean vCenter snapshot and record its exact, case-sensitive VM and
snapshot names.

### 5.3 Inventory file

Create populated inventory outside Git:

```bash
cp private-cicd/config/vm-inventory.yaml.example \
  /tmp/cloudstack-presubmit-vm-inventory.yaml
chmod 600 /tmp/cloudstack-presubmit-vm-inventory.yaml
```

Fill every `PLACEHOLDER-*` value. Do not put passwords, tokens, or private keys
in this file. Check it:

```bash
python3 - <<'PY'
from pathlib import Path
import yaml

path = Path("/tmp/cloudstack-presubmit-vm-inventory.yaml")
data = yaml.safe_load(path.read_text())
assert data and data.get("vms"), "vms must be non-empty"
assert "PLACEHOLDER-" not in path.read_text(), "placeholder remains"
print("Inventory YAML parsed and has no placeholders.")
PY
```

If PyYAML is unavailable, use an approved YAML parser. Do not print the
populated file into a shared terminal or Jenkins log.

For each enabled VM, the inventory `lock_resource` must exactly match a Jenkins
Lockable Resource name and the resource must carry label
`cloudstack-presubmit-vm`.

## 6. Create Jenkins credentials

Open the credential store:

1. Select **Manage Jenkins**.
2. Select **Credentials**.
3. Select the appropriate store, normally **System**.
4. Select **Global credentials (unrestricted)**, or the approved domain.
5. Select **Add Credentials**.

If the job is inside a Jenkins folder, folder-scoped credentials are safer.
They must be created in the same folder or an ancestor visible to the job.

Create or confirm these credentials:

| Suggested ID | Jenkins Kind | Value |
|---|---|---|
| `cloudstack-presubmit-inventory` | Secret file | populated inventory YAML |
| `cloudstack-vcenter` | Username with password | vCenter account |
| `cloudstack-presubmit-ssh` | Username with password | initial VM login |
| `cloudstack-mysql-root` | Username with password | MySQL deployment administrator |
| `cloudstack-db` | Username with password | CloudStack database account |
| `cloudstack-kvm-host` | Username with password | KVM host account for `addHost` |
| `cloudstack-ontap` | Username with password | ONTAP SVM account |
| `cloudstack-admin` | Username with password | CloudStack API administrator |
| `cloudstack-presubmit-github-app` | GitHub App | App ID and PKCS#8 private key |
| site-defined ID | Username with password | optional read-only Git credential |

Credential IDs are configuration names, not secret values. Password fields
must not be empty. The database passwords must work in the
`user:password@host` syntax accepted by `cloudstack-setup-databases`; avoid
unescaped `:`, `@`, and whitespace.

### 6.1 Inventory Secret file

1. Select **Add Credentials**.
2. Set **Kind** to **Secret file**.
3. Upload `/tmp/cloudstack-presubmit-vm-inventory.yaml`.
4. Set **ID** to `cloudstack-presubmit-inventory`, or your selected ID.
5. Add a description such as `ONTAP presubmit VM inventory`.
6. Select **Create**.
7. Delete the local temporary file after setup is verified.

### 6.2 Username/password credentials

Repeat these steps for vCenter, VM SSH, MySQL root, CloudStack DB, KVM host,
ONTAP, and CloudStack API credentials:

1. Select **Add Credentials**.
2. Set **Kind** to **Username with password**.
3. Enter the service account username and password.
4. Enter the exact ID from the table.
5. Enter a description that names the service and lab.
6. Select **Create**.

`VM_SSH_CREDENTIALS_ID` must point to Username with password, not an SSH key.
The pipeline uses that password once to install an ephemeral Ed25519 key.

### 6.3 Optional Git credential

Public HTTPS checkout normally needs no credential. If policy requires one:

1. Create a least-privilege read-only GitHub credential.
2. Record its Jenkins ID.
3. Use the same credential in Pipeline SCM and `GIT_CREDENTIALS_ID`.

Do not use a personal credential with repository write permission.

## 7. Set non-interactive pipeline defaults

Self-queued worker builds do not display **Build with Parameters**. Discovery
contributes only the PR fields. All other values come from the Jenkinsfile
parameter defaults.

This change is already made in the Jenkinsfile on
`feature/CSTACKEX-223`. Before creating the job, open
[`../Jenkinsfile`](../Jenkinsfile), find the `parameters` block, and confirm
these exact non-secret defaults:

```text
VM_INVENTORY_CREDENTIALS_ID=cloudstack-presubmit-inventory
VCENTER_HOST=cstack-netapp-lab.rtp.openenglab.netapp.com
VCENTER_CREDENTIALS_ID=cloudstack-vcenter
VM_SSH_CREDENTIALS_ID=cloudstack-presubmit-ssh
MYSQL_ROOT_CREDENTIALS_ID=cloudstack-mysql-root
CLOUD_DB_CREDENTIALS_ID=cloudstack-db
KVM_HOST_CREDENTIALS_ID=cloudstack-kvm-host
ONTAP_CREDENTIALS_ID=cloudstack-ontap
CLOUDSTACK_ADMIN_CREDENTIALS_ID=cloudstack-admin
GITHUB_APP_CREDENTIALS_ID=cloudstack-presubmit-github-app
MAX_ACTIVE_WORKERS=5
```

These are credential lookup names, not usernames or passwords. The vCenter
hostname is configuration, not a credential.

From the repository root, verify no parameter default still contains a
placeholder:

```bash
git grep -n "defaultValue: 'PLACEHOLDER-" -- private-cicd/Jenkinsfile
```

The command must print nothing and exit with status 1, meaning no match.

Review the change:

```bash
git diff -- private-cicd/Jenkinsfile
```

Commit and push this Jenkinsfile change together with the rest of the
presubmit implementation on `feature/CSTACKEX-223` before creating the Jenkins
job. Do not commit usernames, passwords, private keys, or populated inventory.
Re-run section 3 afterward and require the local and remote SHAs to match.

Do not rely on editing generated parameter defaults in the Jenkins job UI.
Declarative Pipeline properties can replace those values from the Jenkinsfile
on a later run.

## 8. Create the Lockable Resources

For each enabled inventory VM:

1. Select **Manage Jenkins**.
2. Open **System** or **Configure System**, depending on Jenkins version.
3. Find **Lockable Resources Manager**.
4. Select **Add Lockable Resource**.
5. Set **Name** to the exact inventory `lock_resource`.
6. Set **Labels** to `cloudstack-presubmit-vm`.
7. Leave **Reserved by** empty.
8. Add a description naming the vCenter VM and snapshot.
9. Save.

Open the Lockable Resources page and confirm the resource is free. A spelling
or case mismatch prevents the pipeline from mapping the lock back to inventory.
Only enabled inventory VMs may carry label `cloudstack-presubmit-vm`. The
pipeline locks a resource by label and validates its name afterward, so a stale
or unrelated resource with this label can be selected and then rejected.

Create one additional resource that serializes only the short discovery runs:

1. Select **Add Lockable Resource**.
2. Set **Name** to `cloudstack-presubmit-discovery`.
3. Leave **Labels** and **Reserved by** empty.
4. Add description `Serializes CloudStack GitHub PR discovery`.
5. Save and confirm the resource is free.

Do not use `cloudstack-presubmit-vm` for discovery. Worker builds remain
concurrent through compilation and queue on that label only at integration.

A discovery run that finds the lock held skips its work and finishes as
`NOT_BUILT` instead of waiting, so a slow poll cannot build a backlog of
discovery runs. The skipped run leaves the watermark alone, so the next run
still queues everything that changed.

## 9. Create and install the GitHub App

The App publishes the `cloudstack-ontap-presubmit` Check, looks up a commit
author email, and lets discovery list open pull requests. GitHub never sends
events to Jenkins.

### 9.0 Where this differs from the NetApp CI/CD in Github guide

NetApp's [CI/CD in Github](https://netapp.atlassian.net/wiki/spaces/NGAGE/pages/121930531/CI+CD+in+Github)
guide grants **Commit statuses: Read and write** and never mentions Checks.
That is correct for the standard GitHub Branch Source integration, which
publishes commit statuses. This presubmit publishes a Check Run instead, so it
additionally requires **Checks: Read and write**.

An App created strictly from that guide therefore returns HTTP 403 from
`POST /repos/NetApp/cloudstack/check-runs` while discovery, pull-request
listing, and commit-email lookup all keep working, because those need only the
read permissions the guide does grant. A 403 on the write with successful reads
is the signature of this gap.

Two further steps in that guide do not apply here:

| Guide step | This presubmit |
|---|---|
| Subscribe to all events | Subscribe to no events and leave the webhook inactive |
| Create a Jenkins GitHub Organization item | Create one Pipeline job; an Organization folder would run `Jenkinsfile` from the PR branch |

Adding a permission to an App that is **already installed** only requests it.
An organization owner must then approve the updated permission on the
`NetApp/cloudstack` installation before GitHub issues tokens that carry it.
Until that approval the App settings page shows Checks read and write while
every Check write still returns 403. Installing a newly created App grants
everything it requests, so a fresh App needs no separate approval step.

### 9.1 Create the App

Use an organization-owned App:

1. Sign in to GitHub with permission to manage Apps for the NetApp
   organization.
2. Open the organization **Settings**.
3. Open **Developer settings > GitHub Apps**. If the organization UI links to a
   personal developer-settings page, confirm the owner is the organization.
4. Select **New GitHub App**.
5. Enter a unique name, such as `Jenkins - CloudStack ONTAP Presubmit`.
6. Set **Homepage URL** to the Jenkins external URL or repository URL.
7. Uncheck **Webhook active**. Leave **Webhook URL** empty and do not subscribe
   to events. OpenLab Jenkins cannot receive GitHub deliveries.
8. Under **Repository permissions**, set:
   - **Checks:** Read and write;
   - **Contents:** Read-only;
   - **Metadata:** Read-only;
   - **Pull requests:** Read-only.
9. Leave every other repository, organization, and account permission at
   **No access**.
10. Under event subscriptions, select no events.
11. Limit installation to the owning organization.
12. Select **Create GitHub App**.
13. Record the numeric **App ID**. Do not use Client ID.

### 9.2 Install the App

1. On the App settings page, select **Install App**.
2. Select the NetApp organization.
3. Choose **Only select repositories**.
4. Select only `cloudstack`.
5. Complete installation.

If the App is not installed on `NetApp/cloudstack`, Check API calls normally
return HTTP 404 even when the App ID and key are valid.

### 9.3 Generate and convert the private key

1. Return to the App settings page.
2. Under **Private keys**, select **Generate a private key**.
3. Save the downloaded PEM file to a protected local directory.
4. Convert it to unencrypted PKCS#8:

```bash
umask 077
openssl pkcs8 -topk8 -inform PEM -outform PEM \
  -in <downloaded-key>.private-key.pem \
  -out converted-github-app.pem \
  -nocrypt
head -1 converted-github-app.pem
```

The first line must be:

```text
-----BEGIN PRIVATE KEY-----
```

`BEGIN RSA PRIVATE KEY` is not the converted PKCS#8 form expected by the
Jenkins GitHub App credential.

### 9.4 Add the App credential to Jenkins

1. Open the Jenkins credential store used by the job.
2. Select **Add Credentials**.
3. Set **Kind** to **GitHub App**.
4. Set **ID** to `cloudstack-presubmit-github-app`.
5. Enter the numeric **App ID**.
6. Set **Owner** to `NetApp` if the credential form provides that field. This
   selects the organization installation used for token exchange.
7. Paste or upload `converted-github-app.pem` in the private-key field.
8. Add a description naming the repository and this Jenkins job.
9. Select **Create**.
10. Use **Test Connection** if the plugin provides it.
11. Securely delete both local private-key files after Jenkins stores the key.

If **GitHub App** is not available under **Kind**, update or enable GitHub
Branch Source before continuing.

## 10. Disable competing jobs and inbound webhooks

Do not create `cloudstack-presubmit-webhook-token`. The production Jenkinsfile no
longer declares Generic Webhook Trigger.

### Delete any GitHub webhook aimed at Jenkins

If you already created a repository webhook while following an earlier revision:

1. Open `https://github.com/NetApp/cloudstack/settings/hooks`.
2. Open the webhook whose payload URL contains Jenkins or
   `generic-webhook-trigger`.
3. Select **Delete**, or clear **Active** and save.
4. `failed to connect to host` on Recent Deliveries is expected and is not a
   Jenkins misconfiguration.

### Disable `cloudstack-presubmit-manual`

Keep the manual job disabled so it cannot publish Check
`cloudstack-ontap-presubmit` on the same SHA as a production worker.

1. Open `cloudstack-presubmit-manual`.
2. Select **Disable Project**, or tick **Disable this project** in
   **Configure** and save.
3. Confirm the job page shows the disabled banner.

To run a branch-mode test later, enable it for that run only, or use
`SOURCE_MODE=branch` on `cloudstack-ontap-presubmit` through **Build with
Parameters**. That path needs no second job.

Do not select **GitHub Organization** or **Organization Folder** as a
substitute trigger. The Confluence page uses that item type for repos with a
root `Jenkinsfile`. This presubmit is `private-cicd/Jenkinsfile` and must not
execute untrusted PR Groovy.

## 11. Create the Jenkins Pipeline item

Create a regular Pipeline job:

1. Return to the Jenkins dashboard.
2. Select **New Item**.
3. Enter `cloudstack-ontap-presubmit`.
4. Select **Pipeline**.
5. Select **OK**.

Do not select Freestyle, Multi-configuration, Multibranch Pipeline, or
Organization Folder.

### 11.1 General

In **General**:

1. Add a description:

   ```text
   Loads trusted CI from feature/CSTACKEX-223 until that branch is merged to
   main, then change Branches to build to */main. Tests exact
   NetApp/cloudstack PR head SHAs.
   ```

2. Keep **This project is parameterized** as generated by the Jenkinsfile; do
   not manually create duplicate parameters before the first load.
3. Keep **Disable concurrent builds** unchecked.
4. If the UI offers **Abort previous builds**, keep it disabled.

The pipeline implements source-aware cancellation itself. Job-level
abort-previous would incorrectly abort unrelated PRs.

### 11.2 Build Triggers

Do not configure triggers manually:

- do not select **Poll SCM**;
- do not select **GitHub hook trigger for GITScm polling**;
- do not add a Generic Webhook Trigger.

After the first load, the Jenkinsfile applies **Build periodically** with
`H/5 * * * *`. Do not add a second schedule in the UI.

### 11.3 Pipeline

In the **Pipeline** section:

1. Set **Definition** to **Pipeline script from SCM**.
2. Set **SCM** to **Git**.
3. Set **Repository URL** to:

   ```text
   https://github.com/NetApp/cloudstack.git
   ```

4. Set **Credentials** to `- none -` for public HTTPS, or select the approved
   read-only Git credential.
5. Under **Branches to build > Branch Specifier**, enter:

   ```text
   */feature/CSTACKEX-223
   ```

6. Set **Script Path** to:

   ```text
   private-cicd/Jenkinsfile
   ```

7. Uncheck **Lightweight checkout**.
8. Save.

Do not enter the PR branch in Branch Specifier. This field chooses the trusted
pipeline definition. Discovery parameters choose the source under test.

## 12. Bootstrap the job

Jenkins applies Declarative Pipeline parameters and the cron after it loads the
Jenkinsfile at least once.

1. Open `cloudstack-ontap-presubmit`.
2. Select **Build Now**.
3. Open the new build.
4. Select **Console Output**.

The first run may use old parameter defaults and fail validation. If it starts
with `SOURCE_MODE=discover`, it calls GitHub using the current build start as
its initial watermark, queues no older PR revisions, and finishes `NOT_BUILT`.
Both outcomes are safe. Its purpose is to load:

- the Declarative Pipeline parameter definitions;
- the five-minute timer;
- the build retention and timeout settings.

After the run:

1. Return to the job page.
2. Confirm **Build with Parameters** is available.
3. Select **Configure**.
4. Confirm the generated parameters appear once.
5. Confirm `SOURCE_MODE` choices are `discover`, `pull_request`, `branch`.
6. Under **Build Triggers**, confirm **Build periodically** uses
   `H/5 * * * *` and Generic Webhook Trigger is absent.
7. Run **Build with Parameters**, select `SOURCE_MODE=discover`, and confirm
   the build title reads `discover: queued 0, deferred 0` and the description
   starts `seeded at` if no prior successful discovery exists.

If the job still shows Generic Webhook Trigger, the SCM branch still has the
old Jenkinsfile. Confirm `origin/feature/CSTACKEX-223` contains a Jenkinsfile
with no `triggers { GenericTrigger(...) }` block, then **Build Now** again.

## 13. Approve only required Groovy signatures

Discovery and **Abort superseded run** inspect this job's queue and older
builds through Jenkins internal APIs. Script Security may stop the first runs.

Approvals are stored per controller, not per job. Signatures already approved
while running `cloudstack-presubmit-manual` remain approved here, so expect few
or no pending entries. Approve anything that does appear rather than assuming
the list is complete.

When Jenkins reports `Scripts not permitted to use method ...`:

1. Copy the exact rejected signature from Console Output.
2. Select **Manage Jenkins**.
3. Select **In-process Script Approval**.
4. Find the exact pending signature from this trusted
   `feature/CSTACKEX-223` Jenkinsfile.
5. Review what it allows.
6. Select the normal **Approve** action.
7. Do not select **Approve assuming permission check**.
8. Re-run the parameterized smoke test.
9. Repeat only for signatures reported by this pipeline.

Expected operations include obtaining this Jenkins job, enumerating its builds,
reading build parameters/environment, checking whether a build is running, and
stopping an older matching build. Discovery also reads the job's configured Git
branch and remote so it can clone one commit of `private-cicd/scripts` instead
of the whole repository; until those two signatures are approved it logs
`Shallow discovery checkout unavailable` and falls back to a full checkout that
takes minutes. Exact signatures vary by Jenkins and plugin version, so do not
approve a copied generic list.

Never approve unrelated file, process, credential, network, reflection, or
Jenkins-administration access.

## 14. Run a parameterized smoke test before enabling discovery

Use an existing non-draft PR targeting `main`. In GitHub:

1. Open the PR.
2. Copy its number from the URL.
3. Copy the head branch name.
4. Open the latest commit and copy its full 40-character SHA.
5. Copy the PR URL, title, and author login.

In Jenkins:

1. Open `cloudstack-ontap-presubmit`.
2. Select **Build with Parameters**.
3. Enter:

| Parameter | Value |
|---|---|
| `SOURCE_MODE` | `pull_request` |
| `SOURCE_BRANCH` | blank |
| `SOURCE_SHA` | blank |
| `PR_ID` | PR number |
| `PR_ACTION` | `opened` |
| `PR_DRAFT` | `false` |
| `PR_REPOSITORY` | `NetApp/cloudstack` |
| `PR_BASE_BRANCH` | `main` |
| `PR_HEAD_BRANCH` | PR head branch |
| `PR_HEAD_SHA` | full 40-character head SHA |
| `PR_AUTHOR_LOGIN` | PR author login |
| `PR_AUTHOR_EMAIL` | author email or blank |
| `PR_URL` | full PR URL |
| `PR_TITLE` | PR title |
| `EXPECTED_REPOSITORY` | `NetApp/cloudstack` |
| `CLOUDSTACK_GIT_URL` | `https://github.com/NetApp/cloudstack.git` |
| `GIT_CREDENTIALS_ID` | blank or approved read-only ID |
| `GITHUB_APP_CREDENTIALS_ID` | `cloudstack-presubmit-github-app` |
| inventory/vCenter/runtime fields | exact section 6 IDs and vCenter host |
| `PAUSE_BETWEEN_STAGES` | `true` for the first observed run |

4. Select **Build**.
5. Open **Console Output**.
6. At each pause, inspect the completed stage, then select **Proceed**.

Expected stage order:

1. Validate source request
2. Check eligibility
3. Abort superseded run
4. Checkout CI scripts
5. Start PR reporting
6. Validate builder
7. Checkout source
8. Build and unit tests
9. Build Debian packages
10. Deploy and run ONTAP integration

Confirm:

- **Checkout CI scripts** shows `feature/CSTACKEX-223`;
- `presubmit-results/source.properties` records the test PR and exact SHA;
- the source checkout `HEAD` equals `PR_HEAD_SHA`;
- Maven tests and required Debian packages pass;
- the selected inventory VM is locked through result retrieval;
- the exact snapshot is reverted;
- `setup_zone`, `iscsi`, and `nfs3` run in that order;
- the PR commit receives Check `cloudstack-ontap-presubmit`;
- start and final mail are attempted;
- the start mail shows a `STARTED` banner and a bordered table of source, title,
  diff number, SHA, and start time;
- both subjects end with `diff #<n> (<sha12>)`, where the diff number is `1` for
  the first commit presubmitted on that PR and increments on each new push, so a
  second push reports `diff #2` while a re-run of the same commit stays `diff #1`;
- the final mail contains the diff number, stage durations, and an ONTAP test
  table with failures first;
- `presubmit-results/report.html` and each test's linked HTML log open from
  Jenkins artifacts;
- archived artifacts contain no inventory, `ontap.cfg`, `secrets.json`,
  passwords, private keys, or webhook tokens.

For normal automatic runs, `PAUSE_BETWEEN_STAGES` must be `false`. A
self-queued worker cannot click **Proceed**, and a pause after snapshot revert
holds the VM lock.

You may add Check `cloudstack-ontap-presubmit` as a required status on `main`
after a successful smoke test. This is the same job that will load CI from
`main` after section 20, so you do not wait for a second Jenkins item.

## 15. Enable polling on the existing job

No second Jenkins item is needed. The same `cloudstack-ontap-presubmit` job
uses two kinds of builds:

- short `discover` builds started every five minutes;
- `pull_request` worker builds self-queued by discovery.

Concurrent builds must stay enabled. The named
`cloudstack-presubmit-discovery` lock serializes only discovery; each worker
later queues at the existing `cloudstack-presubmit-vm` lock.

1. Open `cloudstack-ontap-presubmit`.
2. Select **Configure**.
3. Confirm **Disable concurrent builds** remains unchecked.
4. Confirm **Abort previous builds** remains disabled.
5. Under **Build Triggers**, confirm **Build periodically** is present with
   `H/5 * * * *`.
6. Confirm **Poll SCM**, **GitHub hook trigger**, and Generic Webhook Trigger
   are absent.
7. Save only if Jenkins requires it.
8. Select **Build with Parameters**, choose `SOURCE_MODE=discover`, and build.
9. Confirm the first successful discovery is titled `discover: queued 0,
   deferred 0` with description `seeded at ...`. This prevents historical open
   PRs from being started.

A successful discovery build finishes as `NOT_BUILT` by design. That status is
its watermark marker and prevents worker stages, mail, artifacts, and GitHub
Checks from running. A discovery build that fails reports `FAILURE`, so a red
discovery build always means discovery itself is broken.

Build titles distinguish the three kinds of run in the history:

| Title | Meaning |
|---|---|
| `#N discover: queued 1, deferred 0` | a poll that queued one worker |
| `#N discover: skipped, lock held` | a poll that overlapped a slower one |
| `#N PR-98 diff #3 synchronize` | the presubmit for PR-98's third commit |

## 16. Verify automatic polling

### 16.1 Confirm discovery can call GitHub

Open the latest discovery **Console Output**. Confirm it reached
`api.github.com` and wrote `eligible-prs.json`. A 401/403/404 here is an App
credential or **Pull requests** permission problem, not a webhook problem.

### 16.2 Push a new PR revision

Use the test PR from section 14. Push an intended small change to its branch.
An empty commit is enough to change the head SHA:

```bash
git checkout <pr-head-branch>
git pull --ff-only
git commit --allow-empty -m "Test ONTAP presubmit discovery"
git push origin HEAD
```

Wait up to five minutes, or run `SOURCE_MODE=discover` manually.

### 16.3 Confirm discovery queued a worker

The discovery console should contain a line similar to:

```text
Queueing PR-<number> synchronize at <sha12>.
```

A later discovery run for the same SHA should say the presubmit is already
queued or recorded.

### 16.4 Confirm Jenkins parameters and checkout

Open the new worker build in the same job. Confirm in **Parameters** or
Console Output:

```text
SOURCE_MODE=pull_request
PR_ACTION=synchronize
PR_REPOSITORY=NetApp/cloudstack
PR_BASE_BRANCH=main
PR_DRAFT=false
PR_HEAD_SHA=<new-full-head-sha>
```

Confirm **Checkout source** checks out that exact SHA, not the moving branch
name or merge commit.

### 16.5 Confirm same-PR cancellation

While one revision of the test PR is still in Maven or packaging, push another
revision and wait for the next poll. The newer build should abort the older
build because both have source key `pull_request:<PR_ID>`.

Repeat only if needed after the older run acquires the VM. Once
`PRESUBMIT_INTEGRATION_STARTED=true`, the newer run must not abort it. It waits
for a compatible VM while the older integration completes.

Different PR IDs must not abort each other.

### 16.6 Confirm the five-worker cap

This is optional if you have only one test PR. When several eligible SHAs exist:

1. Confirm five `pull_request` workers are already queued or running, or queue
   that many with **Build with Parameters**.
2. Push two more eligible SHAs.
3. Run `SOURCE_MODE=discover`.
4. Confirm the console reports two `Deferred PR-... at cap 5` lines and the
   description includes `deferred 2`.
5. Confirm the next discovery watermark in the description is unchanged from
   this run.
6. After a worker finishes, run discover again and confirm the deferred SHAs
   are queued.
7. Confirm extra workers wait on `cloudstack-presubmit-vm` rather than failing.

### 16.7 Confirm GitHub Check

On the new PR commit:

1. Open the PR **Checks** area or the commit status.
2. Find `cloudstack-ontap-presubmit`.
3. Confirm it starts **In progress**.
4. Confirm **Details** opens this Jenkins build.
5. After completion, confirm conclusion is **Success**, **Failure**, or
   **Cancelled** as expected.

You may require this Check on `main` after the smoke test.

## 17. Make the GitHub Check required on `main`

Do this only after section 16 has created at least one Check named
`cloudstack-ontap-presubmit`. GitHub may not offer a Check name until it has
been reported recently.

Changing branch protection requires repository administrator permission. If
you cannot see **Settings**, or cannot edit branch rules, send this section to
a `NetApp/cloudstack` repository administrator. Do not weaken or replace an
existing rule to gain access.

### 17.1 Open the rule for `main`

On GitHub:

1. Open `https://github.com/NetApp/cloudstack`.
2. Select **Settings**. This is the repository's Settings tab, not your
   personal settings and not the GitHub App settings.
3. In the left sidebar, select **Branches**.
4. Under **Branch protection rules**, find the rule whose branch name pattern
   covers `main`.
5. Select **Edit**.

If the repository uses rulesets instead of the Branches page:

1. Select **Settings > Rules > Rulesets**.
2. Open the active ruleset whose target branches include `main`.
3. Select **Edit**.
4. Find the rule that requires status checks before merging.

Ask the repository administrator which existing rule owns `main` if both
systems are present. Do not create a second overlapping rule without their
approval.

### 17.2 Require the presubmit Check

In the existing rule:

1. Enable **Require status checks to pass before merging**.
2. Select **Add checks**, or use the status-check search field.
3. Search for this exact, case-sensitive name:

   ```text
   cloudstack-ontap-presubmit
   ```

4. Select only that Check name for this presubmit. If GitHub displays the
   source App, select the result produced by the new GitHub App.
5. Leave all existing required checks selected.
6. Leave other review, signed-commit, force-push, deletion, and administrator
   settings unchanged unless the repository administrator explicitly asks for
   a change.
7. Select **Save changes**.
8. Complete any organization confirmation or approval prompt.

The required Check applies to every PR targeting `main`, including the first PR
from `feature/CSTACKEX-223` and later PRs from other branches. It does not
filter on the source branch.

### 17.3 Verify merge blocking

Return to the test PR:

1. Confirm `cloudstack-ontap-presubmit` appears in the required checks list.
2. While the Check is queued or running, confirm GitHub says merging is
   blocked.
3. If the Check fails, confirm merging remains blocked.
4. After a successful Check for the current head SHA, confirm this requirement
   is satisfied.
5. Push a harmless new commit during the controlled smoke test.
6. Confirm the prior success no longer satisfies the PR and the Check becomes
   pending for the new SHA.

Do not merge solely because a Jenkins email says success. The GitHub Check on
the current PR head SHA is the merge gate.

## 18. Expected trigger cases

| Change | Expected result |
|---|---|
| Non-draft PR to `main` updated after the watermark | discovery queues a worker |
| New commit pushed to an eligible PR | discovery queues `synchronize` for the new SHA |
| Same SHA already queued or recorded | discovery skips it |
| Draft PR | no queue |
| PR targeting a branch other than `main` | no queue |
| PR closed | no queue |
| Open PR last updated before initial activation | never queued by the initial seed |
| More than five eligible SHAs while five workers are already active | discovery queues none extra and holds the watermark |
| Push with no open PR to `main` | no queue |
| GitHub repository webhook ping | ignored; do not create that webhook |

The Jenkins **Check eligibility** stage repeats the action, draft, and base
checks. A manually parameterized ineligible request becomes `NOT_BUILT`.

## 19. Troubleshooting

### Discovery does not queue a new push

- Confirm the job is enabled and a `discover` build ran after the push.
- Confirm the PR is not a draft and targets `main`.
- Confirm the job has no existing worker for that `PR_ID` and `PR_HEAD_SHA`.
- Confirm GitHub App **Pull requests** is Read-only and the App is installed
  on `cloudstack`.
- Confirm the latest successful discovery description has an earlier watermark
  than the PR's `updated_at`.
- If the console says `Deferred PR-... at cap`, wait for an active worker to
  finish and for the next discovery run. Manual `Build with Parameters` with
  `SOURCE_MODE=pull_request` bypasses the cap.

### Discovery builds pile up behind the discovery lock

A queue of `discover` builds all reporting `The resource
[cloudstack-presubmit-discovery] is locked by build ...` means one discovery run
is taking longer than the five-minute timer. Cancel the queued discovery builds
from the job page, then confirm the running one finishes in seconds:

- Confirm the loaded Jenkinsfile passes `skipIfLocked: true` to the discovery
  lock, so a run that finds the lock held finishes as `NOT_BUILT`.
- Confirm the console shows a shallow clone of `private-cicd/scripts` and not a
  full CloudStack checkout. `Shallow discovery checkout unavailable` means the
  two Git signatures in section 13 still need approval.

### `failed to connect to host` on a GitHub webhook

That webhook cannot work against OpenLab Jenkins. Delete or deactivate it.
Use `SOURCE_MODE=discover`. Do not ask GitHub to redeliver.

### Duplicate builds

- Confirm `cloudstack-presubmit-discovery` exists so discovery runs serialize.
- Confirm `cloudstack-presubmit-manual` is disabled.
- Do not click **Build with Parameters** for a SHA discovery already queued.

### Build fails on `PLACEHOLDER-*`

- Self-queued workers use Jenkinsfile defaults for lab parameter values.
- Replace placeholder host/credential IDs on the fixed trusted branch.
- Push the branch and bootstrap the job again.
- Confirm the credential IDs exist in the job's scope.

### Pipeline cannot load

- Confirm SCM branch is exactly `*/feature/CSTACKEX-223` before cutover, or
  `*/main` after section 20.
- Confirm Script Path is exactly `private-cicd/Jenkinsfile`.
- Confirm the remote branch contains that file.
- Check Git credential and network access.
- Keep Lightweight Checkout off.

### Script approval failure

- Approve only the exact pending signature produced by this trusted job.
- Re-run after each approved signature.
- Do not approve broad or unrelated signatures.

### Pod never starts

- Inspect the Kubernetes pod event.
- `ErrImagePull` or `ImagePullBackOff`: verify image, tag, registry credential,
  and namespace pull secret.
- `FailedScheduling`: verify CPU, memory, and ephemeral-storage capacity.
- No Jenkins agent connection: verify the `jnlp` container and controller
  connectivity.

### GitHub Check API failure

| Response | Likely cause |
|---|---|
| 401 | invalid App ID/key or installation token |
| 403 | missing Checks write permission, policy, or rate limit |
| 404 | App not installed on `NetApp/cloudstack` or wrong repository |

Also confirm the private key begins `BEGIN PRIVATE KEY`, the credential Kind is
GitHub App, and the ID matches `GITHUB_APP_CREDENTIALS_ID`.

GitHub reporting errors do not replace the build result, but a missing success
cannot satisfy a required Check.

### Check has the wrong Details link

Set **Manage Jenkins > System > Jenkins Location > Jenkins URL** to the external
HTTPS URL, then start a new build. Existing Check links are not rewritten.

### Build waits for VM

- Open Lockable Resources.
- Confirm at least one enabled inventory VM has label
  `cloudstack-presubmit-vm`.
- Confirm resource name exactly equals inventory `lock_resource`.
- Remove this label from stale or unrelated resources that are absent from the
  inventory.
- Check whether an older integration build owns the VM.
- Never manually unlock a resource while its build is active.

### ONTAP integration failed but the console ends with later passing tests

The pipeline deliberately runs NFS3 after an iSCSI failure so both protocol
results are available. The final stage remains failed if either protocol
failed.

1. Open the final mail and find the **ONTAP tests** table.
2. Failed and exception rows are listed first.
3. Select **HTML log** on the failing test to jump directly to that test in
   the archived suite log.
4. Alternatively, open Jenkins **Artifacts >
   presubmit-results/report.html**.

Do not infer that the run passed because the last NFS3 summary is green. Do not
debug an ONTAP test failure from `maven.log`, `configure-cloudstack.log`, or
`health-check.log` when their stage rows are green.

### Two Check results conflict

Disable any older job that still publishes `cloudstack-ontap-presubmit`. The
worker builds in this job are the only intended publisher of that Check name.

## 20. Cutover to `main`

Do this after `feature/CSTACKEX-223` is merged to `main`. Do **not** create a
second job. Do **not** delete this job, the GitHub App, credentials, or
Lockable Resources.

1. Confirm `origin/main` contains `private-cicd/Jenkinsfile` at the merged
   revision:

   ```bash
   git fetch origin main
   git show origin/main:private-cicd/Jenkinsfile | head
   ```

2. In Jenkins, open `cloudstack-ontap-presubmit` → **Configure**.
3. Under **Pipeline > Branches to build > Branch Specifier**, replace
   `*/feature/CSTACKEX-223` with:

   ```text
   */main
   ```

4. Leave Script Path `private-cicd/Jenkinsfile`, Lightweight checkout off, and
   concurrent builds enabled.
5. Save.
6. Run **Build Now** once so Jenkins reloads the Jenkinsfile from `main`.
7. Confirm the timer remains `H/5 * * * *` and Generic Webhook Trigger is absent.
8. Update the job description so it no longer says the trusted branch is
    `feature/CSTACKEX-223`.
9. Restrict or delete `feature/CSTACKEX-223` so it is no longer a trusted CI
    source.

Leave the Check name and branch-protection rule unchanged. Later PRs from any
feature branch into `main` use the same job.

If you ever retire the presubmit entirely, disable the timer, let any in-flight
integration finish, then disable the job.

## 21. Completion checklist

- [ ] Trusted CI branch is pushed, restricted to maintainers, and used as SCM
  until merge.
- [ ] PR code is trusted for the credentialed integration environment.
- [ ] Required plugins are installed and enabled.
- [ ] Jenkins external URL and SMTP are configured.
- [ ] Kubernetes can pull both pod images and reach dependencies.
- [ ] Driver image uses an immutable, pullable tag.
- [ ] Clean VM snapshot satisfies the baseline contract.
- [ ] Populated inventory remains outside Git and has no placeholders.
- [ ] Every enabled VM maps to a free labeled Lockable Resource.
- [ ] All credentials exist with the correct Kind and ID.
- [ ] Jenkinsfile defaults contain usable host and credential IDs.
- [ ] GitHub App is installed only on `NetApp/cloudstack`.
- [ ] App key is PKCS#8 and local copies have been deleted.
- [ ] Pipeline job name is `cloudstack-ontap-presubmit`.
- [ ] Before merge, SCM branch is `*/feature/CSTACKEX-223`.
- [ ] Script Path is `private-cicd/Jenkinsfile`.
- [ ] Lightweight Checkout is off and concurrent builds are enabled.
- [ ] Timer is `H/5 * * * *`; no competing trigger is enabled.
- [ ] `MAX_ACTIVE_WORKERS` defaults to `5`.
- [ ] `cloudstack-presubmit-discovery` exists and is free.
- [ ] Bootstrap registered `discover`, `pull_request`, and `branch` parameters.
- [ ] Only required Groovy signatures were approved.
- [ ] Parameterized smoke test passed with exact-SHA checkout.
- [ ] `PAUSE_BETWEEN_STAGES` is off for automatic worker runs.
- [ ] No old job publishes the same Check name.
- [ ] `cloudstack-presubmit-manual` is disabled.
- [ ] No GitHub repository webhook points at Jenkins.
- [ ] Initial discovery seeded without queueing historical PRs.
- [ ] Discovery queued one worker for a new PR SHA within five minutes.
- [ ] Jenkins parameters and source checkout match the new PR SHA.
- [ ] GitHub Check links to and concludes with the correct build.
- [ ] Same-PR cancellation and post-lock protection behave as designed.
- [ ] Archives contain no secrets or generated runtime configuration.
- [ ] After merge, SCM branch is `*/main` and the feature branch is no longer
  trusted CI.
