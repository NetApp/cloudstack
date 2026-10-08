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

# Agent Guide: ONTAP KVM Live VM Migration

Always-on operating notes for agents working on KVM live VM migration and
live VM-with-storage migration for NetApp ONTAP primary storage.

Ticket: **CSTACKEX-262**. Branch: `feature/CSTACKEX-262`.

Read this before editing Java, Marvin tests, or the lab. Credentials, SVM name,
and host URLs come from `test/integration/plugins/ontap/ontap.cfg`. Do not
hard-code them in test code.

Deeper design lives in the Cursor skill
`~/.cursor/skills/cloudstack-ontap-migration/` (`SKILL.md`, `combinations.md`,
`code-paths.md`, `specs/02-live-vm-with-storage-migration.md`).

---

## Confluence and specs

| Doc | URL | pageId |
|---|---|---|
| Transfer of Information: NetApp ONTAP Storage Plugin | https://netapp.atlassian.net/wiki/spaces/OSSG/pages/624932561 | `624932561` |
| Admin Guide | https://netapp.atlassian.net/wiki/spaces/OSSG/pages/669425954 | `669425954` |
| Integrated Spec | https://netapp.atlassian.net/wiki/spaces/OSSG/pages/330407273 | `330407273` |
| Architectural Spec | https://netapp.atlassian.net/wiki/spaces/OSSG/pages/330407201 | `330407201` |
| Plugin overview | https://netapp.atlassian.net/wiki/spaces/OSSG/pages/633873247 | `633873247` |
| Feature roadmap | https://netapp.atlassian.net/wiki/spaces/OSSG/database/640141625 | `640141625` |
| CI/CD pipeline | https://netapp.atlassian.net/wiki/spaces/OSSG/pages/689709001 | `689709001` |
| Operations / scale test matrix | https://netapp.atlassian.net/wiki/spaces/OSSG/pages/648390908 | `648390908` |

`cloudId`: `cb69b23c-616e-461b-9cf1-b3015880f8dd` (`netapp.atlassian.net`).

Apache CloudStack APIs:

- Compute-only live migrate: `migrateVirtualMachine` (Running; volumes stay)
- Live with storage: `migrateVirtualMachineWithVolume` (Running; qemu/libvirt)
- Offline volume: `migrateVolume` (`livemigrate=true` is **rejected** on KVM)

---

## Working agreement

1. **KVM only.** Leave VMware/Xen branches untouched.
2. **Live with-storage stays qemu/libvirt.** Do not route Running
   `migrateVirtualMachineWithVolume` through agent `CopyCommand`.
3. **ONTAP-to-ONTAP offline copy** (Stopped / detached) may use `CopyCommand`
   only for the **same protocol and same SVM**
   (`StorageSystemDataMotionStrategy.isSupportedOntapMigrationPoolPair`).
4. **Never add a hook to `PrimaryDataStoreDriver`.** Use an inline
   `DataStoreProvider.ONTAP_PLUGIN_NAME` check.
5. **Surgical edits.** No drive-by refactors. Ask before Java production
   changes unless the user already consented this cycle.
6. **User-owned full `mvn install`** unless they said otherwise. Agents run
   targeted unit tests and Marvin. After Java on the KVM agent path, rebuild
   Debian packages and replace `cloudstack-agent` on **both** KVM hosts.
7. **Do not invent lab credentials.** Read `ontap.cfg`.

---

## RTP lab (read live values from ontap.cfg)

Typical layout used for this work:

| Role | Host | Notes |
|---|---|---|
| Management + KVM Cluster1 | `10.193.56.62` (cstack53) | jetty `:cloud-client-ui`; agent; NFS `/export/primary`, `/export/secondary` |
| KVM Cluster2 | `10.193.56.63` (cstack54) | agent only |
| Integration API | `http://10.193.56.62:8096` | `integration.api.port=8096` |
| UI / API | `http://10.193.56.62:8080` | jetty |
| ONTAP | `storageIP` + `svmName` in `ontap.cfg` | vs0 in the current cfg |
| DefaultPrimary | `nfs://10.193.56.62/export/primary` | tag `defaultPrim` |
| Template | CentOS 5.5(64-bit) no GUI (KVM) | must be Ready on Primary1 |

Hosts: Cluster1 `kvmHost`, Cluster2 `kvmHost1`. Advanced isolated guest network.

Indirect agent: KVM agents **connect outbound** to MS `:8250`. They often do
**not** listen on 8250 themselves. Host Up is ping over that channel.

---

## What must work (live)

Data mover for Running VMs with storage: `handleLiveMigrationForKVM` →
`MigrateCommand` → qemu block copy into dest volumes created by the ONTAP
driver.

| Source | Dest | Allowed |
|---|---|---|
| ONTAP NFS3 (same SVM) | ONTAP NFS3 | Yes — dest file QCOW2 |
| ONTAP iSCSI (same SVM) | ONTAP iSCSI | Yes — dest LUN RAW |
| DefaultPrimary / non-managed | ONTAP NFS3 or iSCSI | Yes |
| ONTAP NFS3 | ONTAP iSCSI (or reverse) | No |
| ONTAP | different SVM / storageIP | No |
| ONTAP | non-managed | No |
| Mixed managed + non-managed dests | — | No |
| Cluster-scope pool | Zone-scope pool (same protocol/SVM) | Yes; dest host must reach dest pool |
| Cluster1-only pool | host in Cluster2 | No (`test_22`) |
| `migrateVolume livemigrate=true` | — | Reject; use with-volume VM migrate |

Cluster→zone is supported. Zone pools are visible to every cluster in the zone.

NFS dest files must be created as **QCOW2**
(`UnifiedNASStrategy.createVolumeOnKVMHost`). RAW dest files fail live migrate
with `Image is not in qcow2 format`.

iSCSI LUN IDs are **per igroup**. After `grantAccess`, refresh `VolumeInfo`
before putting dest TOs on `PrepareForMigrationCommand`.

---

## Code map

| Path | Why |
|---|---|
| `engine/storage/datamotion/.../StorageSystemDataMotionStrategy.java` | `handleLiveMigrationForKVM`, `verifyLiveMigrationForKVM`, `isSupportedOntapMigrationPoolPair`, offline `CopyCommand` branch |
| `server/.../vm/VirtualMachineManagerImpl.java` | `migrateWithStorage`, `executeManagedStorageChecksWhenTargetStoragePoolProvided` |
| `server/.../vm/UserVmManagerImpl.java` | `migrateVirtualMachineWithVolume` |
| `engine/orchestration/.../VolumeOrchestrator.java` | `migrateVolumes` |
| `plugins/storage/volume/ontap/.../OntapPrimaryDatastoreDriver.java` | create/grant/revoke |
| `plugins/storage/volume/ontap/.../UnifiedNASStrategy.java` | QCOW2 stamp on KVM create |
| `plugins/storage/volume/ontap/.../UnifiedSANStrategy.java` | LUN map / igroup |
| `plugins/hypervisors/kvm/.../LibvirtMigrateCommandWrapper.java` | libvirt live migrate |
| `plugins/hypervisors/kvm/.../KVMStorageProcessor.java` | CopyCommand / volume copy on agent |
| `test/integration/plugins/ontap/migration/test_01_live_vm_with_storage_migration.py` | live with-storage matrix |
| `test/integration/plugins/ontap/migration/migration_test_base.py` | hosts, pools, deploy, migrate helpers |
| `ontap-migration-test-plan.xlsx` | manual matrix (repo root) |

Do not widen `handleLiveMigrationForKVM` for other vendors. Do not set
`canCopy` / `CAN_CREATE_VOLUME_FROM_VOLUME` on the ONTAP driver.

---

## Marvin suites

| Tag | File |
|---|---|
| `live_storage` | `test_01_live_vm_with_storage_migration.py` (22 cases; 01–13 same cluster; 14–22 cross-cluster) |
| `stopped_vm` | `test_02_stopped_vm_storage_migration.py` |
| `volume_migration` | `test_03_volume_migration.py` |

Same-cluster live needs **two Up hosts in Cluster1**. Cross-cluster needs one
host in Cluster2. Tests chain VMs: if deploy fails, later cases SKIP.

Pool list APIs often omit `storageIP` / `svmName`. Matching must treat missing
details as compatible and accept type `OntapiSCSI` as iSCSI.

---

## Commands

All from CloudStack repo root unless noted. Python: `test/integration/plugins/ontap/.venv/bin/python`.

### Unit tests

```bash
mvn -pl engine/storage/datamotion test -Dtest=StorageSystemDataMotionStrategyTest
mvn -pl plugins/storage/volume/ontap test -Dtest=UnifiedNASStrategyTest,UnifiedSANStrategyTest,OntapPrimaryDatastoreDriverTest
mvn -pl plugins/hypervisors/kvm test -Dtest=KVMStorageProcessorTest
```

Corrupt local `aspectjweaver` (`Invalid CEN header`) is an artifact problem,
not the change.

### Marvin

```bash
export PYTHONPATH=test/integration/plugins/ontap:${PYTHONPATH:-}
export PYTHONUNBUFFERED=1
export ONTAP_MIGRATION_KEEP_POOLS=1   # reuse Up ONTAP pools; omit to recreate

bash test/integration/plugins/ontap/run_tests.sh live_storage
bash test/integration/plugins/ontap/run_tests.sh stopped_vm
bash test/integration/plugins/ontap/run_tests.sh volume_migration
bash test/integration/plugins/ontap/run_tests.sh migration

test/integration/plugins/ontap/.venv/bin/python -m py_compile \
  test/integration/plugins/ontap/migration/migration_test_base.py \
  test/integration/plugins/ontap/migration/test_01_live_vm_with_storage_migration.py
```

Logs: `/tmp/MarvinLogs/` (newest folder + `test_01_live_vm_with_storage_migration_*`).
Read `results.txt` and `failed_plus_exceptions.txt`.

Needs full network for Marvin init (`listUsers`). SSH to KVM hosts uses
`ontap.cfg` host passwords.

### Management server (cstack53)

Source tree is typically `/root/cloudstack`. Integration API is 8096.

```bash
# start (on the MS host)
export MAVEN_OPTS="-Xmx3072m -XX:MaxMetaspaceSize=512m"
cd /root/cloudstack
# already running: ps aux | grep 'jetty:run'
nohup mvn -Dorg.eclipse.jetty.annotations.maxWait=120 -pl :cloud-client-ui jetty:run \
  > /root/cloudstack/jetty-run.log 2>&1 &

mysql -uroot cloud -e "UPDATE configuration SET value='8096' WHERE name='integration.api.port';"
# restart jetty after changing integration.api.port if it was not already 8096

curl -sS 'http://127.0.0.1:8096/client/api?command=listHosts&type=Routing&response=json'
curl -sS 'http://127.0.0.1:8096/client/api?command=listStoragePools&response=json'
curl -sS 'http://127.0.0.1:8096/client/api?command=listVirtualMachines&listall=true&response=json'
curl -sS 'http://127.0.0.1:8096/client/api?command=listRouters&listall=true&response=json'
curl -sS 'http://127.0.0.1:8096/client/api?command=listAsyncJobs&listall=true&response=json'
```

MS logs: `/root/cloudstack/vmops.log`, `/root/cloudstack/api.log`.

### Debian packages and replace KVM agent

Build **on the Ubuntu 22.04 management host** so packages match the KVM OS.
Install **both** `cloudstack-common` and `cloudstack-agent` (same version) on
**every** KVM host (Cluster1 and Cluster2).

```bash
cd /root/cloudstack
export MAVEN_OPTS="-Xmx3072m -XX:MaxMetaspaceSize=512m"
mvn -P developer,systemvm -DskipTests clean install
dpkg-buildpackage -us -uc -b
# fallback: bash packaging/build-deb.sh

ls -1t ../cloudstack-common_*_all.deb ../cloudstack-agent_*_all.deb | head
```

On each KVM host (`10.193.56.62` and `10.193.56.63`):

```bash
systemctl stop cloudstack-agent
dpkg -i /tmp/cloudstack-common_*_all.deb /tmp/cloudstack-agent_*_all.deb
# if needed: apt-get install -fy
systemctl enable cloudstack-agent
systemctl restart cloudstack-agent
systemctl is-active cloudstack-agent
ss -lntp | grep 8250 || true   # may be empty; agent often outbound-only
pgrep -af com.cloud.agent.AgentShell
tail -n 80 /var/log/cloudstack/agent/agent.log
```

Preserve `/etc/cloudstack/agent/agent.properties` (`host`, `port`, `guid`).
Restart libvirtd only if live migrate needs TCP listen (`listen_tcp=1`; this
lab often uses `16514`).

Copy debs from MS:

```bash
scp ../cloudstack-common_*_all.deb ../cloudstack-agent_*_all.deb root@10.193.56.63:/tmp/
```

### Host / libvirt / disk

```bash
virsh list --all
virsh capabilities | head
df -h /export/primary /var/lib/libvirt/images /
uptime
# hung template copy (blocks deploy):
ps -eo pid,etime,pcpu,cmd | grep -E 'cp -f /mnt/|qemu-img' | grep -v grep
ls -lh /export/primary
```

### ONTAP REST (cluster IP and user from ontap.cfg)

```bash
# SVM
curl -sk -u "$USER:$PASS" "https://$ONTAP/api/svm/svms?name=$SVM"
# volumes / LUNs (filter in client)
curl -sk -u "$USER:$PASS" "https://$ONTAP/api/storage/volumes?svm.name=$SVM&max_records=1000"
curl -sk -u "$USER:$PASS" "https://$ONTAP/api/storage/luns?svm.name=$SVM&max_records=1000"
curl -sk -u "$USER:$PASS" "https://$ONTAP/api/protocols/san/igroups?svm.name=$SVM"
```

---

## Failure patterns (do not misdiagnose)

| Symptom | Likely cause |
|---|---|
| `Image is not in qcow2 format` | Dest NFS file created RAW; QCOW2 stamp missing |
| API `NullPointerException` on live with-storage | Check `vmops.log`. May be **cleanup NPE after a real error**. test_19 was `migration of disk vdb failed: No space left on device` then `VolumeObject.stateTransit` NPE on already-deleted dest volume |
| `KVMStoragePool.getType() because pool is null` | Dest storage pool not in libvirt on dest host (`PrepareForMigration`) |
| Deploy hung `Starting`, ROOT `Allocated` | Isolated VR not Running, or template `cp` secondary→Primary1 stuck; MS load high |
| `Cannot stop VM ... state Starting` | Pending `DeployVMCmd` job; destroy/expunge blocked until job ends |
| `createStoragePool` hangs 30+ min | Host attaching NFS; or tests creating extra pools because match required omitted details |
| Relocate/addHost fails, 8250 not listening | Normal for outbound agent; check `AgentShell` + host Up, not listen socket |
| Host maintenance: VMs in starting/stopping | Stuck VR/user VMs on that host; do not loop relocate |
| Cluster1 live 01–13 SKIP no dest host | Need two hosts in Cluster1; one host in Cluster2 is not enough |
| Cross-protocol live mapping | Expected reject: managed storage can only be migrated to itself |

Before another full Marvin run: no stuck `cp` of the CentOS template, no
`Starting` VMs/routers, Primary1 has space, dest host disk not ENOSPC, both
agents Up, jetty on 8096.

Best proven live_storage run: **01–18 and 22 pass**; **19** failed ENOSPC on
cstack54 during iSCSI cluster→zone onto Cluster2 (20–21 cascade). Stopped-VM
and volume suites were not green in that cycle.

---

## Java consent and deploy

If a Marvin failure is an implementation bug:

1. Quote the `vmops.log` stack, not only Marvin `errortext`.
2. Ask before editing Java unless already approved this session.
3. After agent-side Java: DEB install on **both** KVM hosts; jetty restart
   only if management code changed.
4. Re-run the failing Marvin tag with `ONTAP_MIGRATION_KEEP_POOLS=1`.
