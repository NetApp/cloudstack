# Volume Snapshot Workflow in CloudStack

This document describes the end-to-end workflow for taking volume-level snapshots from the CloudStack Management Server, organized in the sequence that CloudStack orchestrates the operation.

---

## Overview

A volume snapshot in CloudStack captures the state of a disk at a point in time. The snapshot can be stored on primary storage, secondary storage, or replicated across zones and storage pools. The workflow involves multiple layers: API, orchestration, storage engine, and storage-specific strategy plugins.

---

## Step-by-Step Workflow

### Step 1 — API Entry Point: `CreateSnapshotCmd.execute()`

**File:** `api/src/main/java/org/apache/cloudstack/api/command/user/snapshot/CreateSnapshotCmd.java`

The user (or scheduler) calls the `createSnapshot` API. The command is a `BaseAsyncCreateCmd`, meaning snapshot *allocation* and *execution* happen in two separate phases (create and execute).

In the `execute()` phase:

```java
snapshot = _volumeService.takeSnapshot(
    getVolumeId(), getPolicyId(), getEntityId(),
    getAccount(), getQuiescevm(), getLocationType(),
    getAsyncBackup(), getTags(), getZoneIds(),
    getStoragePoolIds(), useStorageReplication());
```

Key parameters available to the caller:
- `volumeId` – the volume to snapshot
- `policyId` – optional snapshot policy to apply
- `locationType` – `PRIMARY` or `SECONDARY`
- `asyncBackup` – whether to back up to secondary asynchronously
- `zoneIds` – destination zones to copy the snapshot to
- `storagePoolIds` – specific primary storage pools to copy the snapshot to
- `useStorageReplication` – use native cross-zone storage replication (StorPool)

---

### Step 2 — Allocation Phase: `VolumeApiServiceImpl.allocSnapshot()`

**File:** `server/src/main/java/com/cloud/storage/VolumeApiServiceImpl.java`

Before `execute()` is called, `create()` runs `allocSnapshot()` which:

1. Verifies the caller has access to the volume.
2. Validates resource limits (snapshot count, secondary storage quota).
3. Generates a snapshot name in the format `<vmName>_<volumeName>_<timestamp>`.
4. Creates a `SnapshotVO` record in the database in state `Allocated`.
5. Increments resource counters for the account (snapshot count, storage size).

The allocation returns the snapshot ID, which is then used by the `execute()` phase.

---

### Step 3 — Validation and Path Selection: `VolumeApiServiceImpl.takeSnapshotInternal()`

**File:** `server/src/main/java/com/cloud/storage/VolumeApiServiceImpl.java`

`takeSnapshotInternal()` performs pre-flight checks before dispatching work:

1. Re-validates volume exists and is in `Ready` state.
2. Rejects snapshots on `External` hypervisor type volumes.
3. Resolves `zoneIds` and `poolIds` from snapshot policy details if a `policyId` is provided.
4. Validates each destination zone exists.
5. Checks that the caller has access to both the volume and (if attached) the VM.
6. If the storage pool is managed and `locationType` is unset, defaults to `LocationType.PRIMARY`.
7. Calls `snapshotHelper.addStoragePoolsForCopyToPrimary()` to resolve storage pool IDs when `useStorageReplication` is enabled.

**Path selection based on VM attachment:**

```
Volume attached to running VM?
├── YES → Serialize via VM Work Job Queue
│         (Step 4a — job queue path)
└── NO  → Direct execution
          (Step 4b — direct path)
```

---

### Step 4a — Serialized Execution via VM Work Job Queue

**File:** `server/src/main/java/com/cloud/storage/VolumeApiServiceImpl.java`

When the volume is attached to a VM, CloudStack serializes the operation using the VM Work Job queue. This prevents concurrent conflicting operations on the same VM.

```java
Outcome<Snapshot> outcome = takeVolumeSnapshotThroughJobQueue(
    vm.getId(), volumeId, policyId, snapshotId,
    account.getId(), quiesceVm, locationType,
    asyncBackup, zoneIds, poolIds);
```

A `VmWorkTakeVolumeSnapshot` work item is created and dispatched. The job framework eventually calls `orchestrateTakeVolumeSnapshot(VmWorkTakeVolumeSnapshot work)` from within the VM work job dispatcher.

If the current thread is *already* running inside the job dispatcher (re-entrant case), a placeholder work record is created and `orchestrateTakeVolumeSnapshot()` is called directly to avoid deadlock.

**`VmWorkTakeVolumeSnapshot` carries:**

```java
// engine/components-api/src/main/java/com/cloud/vm/VmWorkTakeVolumeSnapshot.java
new VmWorkTakeVolumeSnapshot(userId, accountId, vmId, handlerName,
    volumeId, policyId, snapshotId, quiesceVm,
    locationType, asyncBackup, zoneIds, poolIds);
```

---

### Step 4b — Direct Execution (Volume Not Attached to VM)

**File:** `server/src/main/java/com/cloud/storage/VolumeApiServiceImpl.java`

When the volume is not attached to a VM, a `CreateSnapshotPayload` is built and attached directly to the volume:

```java
CreateSnapshotPayload payload = new CreateSnapshotPayload();
payload.setSnapshotId(snapshotId);
payload.setSnapshotPolicyId(policyId);
payload.setAccount(account);
payload.setQuiescevm(quiescevm);
payload.setLocationType(locationType);
payload.setAsyncBackup(asyncBackup);
payload.setZoneIds(zoneIds);
payload.setStoragePoolIds(poolIds);

volume.addPayload(payload);
return volService.takeSnapshot(volume);
```

---

### Step 5 — Orchestration: `orchestrateTakeVolumeSnapshot()`

**File:** `server/src/main/java/com/cloud/storage/VolumeApiServiceImpl.java`

Whether coming from the job queue or directly, `orchestrateTakeVolumeSnapshot()` handles the final preparation:

1. Re-validates the volume is still `Ready`.
2. Detects whether the volume is encrypted and on a running VM; rejects such snapshots unless the storage is StorPool (which supports live encrypted volume snapshots).
3. Builds the `CreateSnapshotPayload` with all execution parameters.
4. Attaches the payload to the volume.
5. Calls `volService.takeSnapshot(volume)` — delegating to `SnapshotManagerImpl`.

**StorPool encrypted volume exception:**

```java
boolean isSnapshotOnStorPoolOnly =
    volume.getStoragePoolType() == StoragePoolType.StorPool &&
    SnapshotInfo.BackupSnapshotAfterTakingSnapshot.value();
// Allow live snapshot of encrypted volumes on StorPool primary storage
```

---

### Step 6 — Strategy Selection and Snapshot Execution: `SnapshotManagerImpl.takeSnapshot()`

**File:** `server/src/main/java/com/cloud/storage/snapshot/SnapshotManagerImpl.java`

This is the core snapshot execution method:

1. Extracts `CreateSnapshotPayload` from the volume.
2. Determines whether to use KVM file-based storage path.
3. Checks if backup to secondary storage is needed for this zone.
4. For KVM file-based storage with secondary backup, allocates an image store.
5. Selects the appropriate `SnapshotStrategy` via `StorageStrategyFactory.getSnapshotStrategy(snapshot, TAKE)`.

**Strategy selection priority (highest wins):**

| Strategy | Priority | Handles |
|---|---|---|
| `StorPoolSnapshotStrategy` | HIGHEST (for DELETE/COPY) | DELETE, COPY on StorPool storage |
| `StorageSystemSnapshotStrategy` | HIGH | Managed storage (TAKE, DELETE) |
| `DefaultSnapshotStrategy` | DEFAULT | File-based hypervisor snapshots |
| `CephSnapshotStrategy` | HIGH | Ceph RBD snapshots |
| `ScaleIOSnapshotStrategy` | HIGH | ScaleIO/PowerFlex snapshots |

6. Calls `snapshotStrategy.takeSnapshot(snapshot)` which returns a `SnapshotInfo` on primary storage.

---

### Step 7 — Primary Storage Snapshot Creation: `SnapshotServiceImpl.takeSnapshot()`

**File:** `engine/storage/snapshot/src/main/java/org/apache/cloudstack/storage/snapshot/SnapshotServiceImpl.java`

The storage engine creates the snapshot on primary storage:

1. Creates a snapshot state object on the primary data store.
2. Transitions snapshot state: `CreateRequested`.
3. Transitions volume state: `Volume.Event.SnapshotRequested`.
4. Issues an asynchronous command to the primary data store driver (`PrimaryDataStoreDriver.takeSnapshot()`).
5. Waits for the async callback via `AsyncCallFuture<SnapshotResult>`.
6. On success:
   - Updates physical size from the driver response.
   - Publishes `EVENT_SNAPSHOT_ON_PRIMARY` usage event.
   - Transitions volume: `Volume.Event.OperationSucceeded`.
7. On failure:
   - Transitions snapshot to `OperationFailed`.
   - Transitions volume: `Volume.Event.OperationFailed`.

---

### Step 8 — Secondary Storage Backup Decision

**File:** `server/src/main/java/com/cloud/storage/snapshot/SnapshotManagerImpl.java`

After the snapshot is created on primary, CloudStack decides whether to back it up:

```
BackupSnapshotAfterTakingSnapshot == true?
├── YES
│   ├── KVM file-based → postSnapshotDirectlyToSecondary()
│   │   (snapshot already on secondary — update DB reference only)
│   └── Otherwise → backupSnapshotToSecondary()
│       ├── asyncBackup == true → schedule BackupSnapshotTask
│       └── asyncBackup == false → synchronous backupSnapshot() + postSnapshotCreation()
└── NO
    ├── storagePoolIds provided AND asyncBackup → schedule BackupSnapshotTask for pool copy
    └── Otherwise → markBackedUp() (snapshot stays on primary only)
```

**`BackupSnapshotTask`** (async retry runner):
- Retries backup up to `snapshot.backup.to.secondary.retries` times.
- On exhausting retries, calls `snapshotSrv.cleanupOnSnapshotBackupFailure()` to remove the snapshot record.

---

### Step 9 — StorPool Cross-Zone Snapshot Copy: `StorPoolSnapshotStrategy.copySnapshot()`

**File:** `plugins/storage/volume/storpool/src/main/java/org/apache/cloudstack/storage/snapshot/StorPoolSnapshotStrategy.java`

When `storagePoolIds` are provided and the storage is StorPool, the snapshot is replicated natively between clusters:

1. **Export** the snapshot from the local StorPool cluster to the remote location using `snapshotExport()`.
2. **Persist recovery information** in `snapshot_details` table with the exported name and location, so that partial cross-zone copies can be recovered.
3. **Copy from remote** on the destination StorPool cluster using `snapshotFromRemote()`.
4. **Reconcile** the snapshot on the remote cluster using `snapshotReconcile()`.
5. **Update** the `snapshot_store_ref.install_path` in the database to reflect the destination path.
6. Invoke the async callback with success or failure.

**Recovery detail saved:**

```java
// Stored so incomplete exports can be cleaned up later
String detail = "~" + snapshotName + ";" + location;
new SnapshotDetailsVO(snapshot.getId(), SP_RECOVERED_SNAPSHOT, detail, true);
```

---

### Step 10 — Post-Snapshot Processing: `postCreateSnapshot()` and Zone/Pool Copies

**File:** `server/src/main/java/com/cloud/storage/snapshot/SnapshotManagerImpl.java`

After snapshot creation (and optional backup):

1. **`postCreateSnapshot()`**: Updates snapshot policy retention — removes the oldest snapshot if the retention count is exceeded.
2. **`snapshotZoneDao.addSnapshotToZone()`**: Associates the snapshot with its origin zone.
3. **Usage event**: Publishes `EVENT_SNAPSHOT_CREATE` with the physical size of the snapshot.
4. **Resource limit correction**: For delta (incremental) snapshots, decrements the pre-allocated resource count by `(volumeSize − snapshotPhysicalSize)` since the actual snapshot is smaller than the volume.
5. **`copyNewSnapshotToZones()`** *(synchronous backup path only)*: Copies the snapshot to secondary storage in additional destination zones.
6. **`copyNewSnapshotToZonesOnPrimary()`** *(synchronous backup path only)*: Copies the snapshot to additional primary storage pools.

---

### Step 11 — Rollback on Failure

**File:** `server/src/main/java/com/cloud/storage/snapshot/SnapshotManagerImpl.java`

The outer `try/catch` in `takeSnapshot()` ensures resource cleanup on any failure:

```java
} catch (CloudRuntimeException | UnsupportedOperationException cre) {
    ResourceType storeResourceType = getStoreResourceType(...);
    _resourceLimitMgr.decrementResourceCount(snapshotOwner.getId(), ResourceType.snapshot);
    _resourceLimitMgr.decrementResourceCount(snapshotOwner.getId(), storeResourceType, volumeSize);
    throw cre;
} catch (Exception e) {
    // Same resource rollback
    throw new CloudRuntimeException("Failed to create snapshot", e);
}
```

**Additional cleanup methods:**

| Method | Trigger | Action |
|---|---|---|
| `cleanupVolumeDuringSnapshotFailure()` | Snapshot creation fails completely | Removes `snapshot_store_ref` entries (non-Destroyed) and deletes the `SnapshotVO` record |
| `cleanupOnSnapshotBackupFailure()` | Async backup exhausts all retries | Transitions snapshot state, removes async job MS_ID, deletes snapshot record |
| `StorPoolSnapshotStrategy.deleteSnapshot()` | Snapshot DELETE operation on StorPool | Calls StorPool API `snapshotDelete`, transitions state, cleans up DB |

---

## Sequence Diagram (Text Form)

```
User/Scheduler
    │
    ▼
CreateSnapshotCmd.create()
    │ allocSnapshot() → SnapshotVO persisted (Allocated state)
    ▼
CreateSnapshotCmd.execute()
    │
    ▼
VolumeApiServiceImpl.takeSnapshot()
    │
    ▼
takeSnapshotInternal()
    │  validate volume, account, zones, policies
    │
    ├── [Volume attached to VM] ─────────────────────────────┐
    │        takeVolumeSnapshotThroughJobQueue()               │
    │        VmWorkTakeVolumeSnapshot dispatched               │
    │        ← job queue serializes VM operations →           │
    │                                                          ▼
    └── [Volume not attached] ──► orchestrateTakeVolumeSnapshot()
                                       │ build CreateSnapshotPayload
                                       │ volume.addPayload(payload)
                                       ▼
                               SnapshotManagerImpl.takeSnapshot()
                                       │
                                       │ StorageStrategyFactory.getSnapshotStrategy(TAKE)
                                       ▼
                               snapshotStrategy.takeSnapshot(snapshot)
                                       │
                                       ▼
                               SnapshotServiceImpl.takeSnapshot()
                                       │ PrimaryDataStoreDriver.takeSnapshot() [async]
                                       │ ← waits on AsyncCallFuture →
                                       │ snapshot created on primary storage
                                       ▼
                               Backup decision
                               ├── BackupSnapshotAfterTakingSnapshot=true
                               │       backupSnapshotToSecondary()  [sync or async]
                               └── BackupSnapshotAfterTakingSnapshot=false
                                       markBackedUp() / schedule pool copy
                                       ▼
                               postCreateSnapshot()
                                   snapshotZoneDao.addSnapshotToZone()
                                   UsageEventUtils.publishUsageEvent()
                                   _resourceLimitMgr.decrementResourceCount()
                                   copyNewSnapshotToZones()         [if zoneIds]
                                   copyNewSnapshotToZonesOnPrimary() [if poolIds]
                                       ▼
                               Return SnapshotInfo to caller
```

---

## Key Classes and Their Roles

| Class | Package | Role |
|---|---|---|
| `CreateSnapshotCmd` | `api/.../command/user/snapshot` | API command entry point; two-phase create+execute |
| `VolumeApiServiceImpl` | `server/.../storage` | Validates, dispatches, and orchestrates snapshot requests |
| `VmWorkTakeVolumeSnapshot` | `engine/components-api/.../vm` | Work item for job queue; carries all snapshot parameters |
| `SnapshotManagerImpl` | `server/.../storage/snapshot` | Core business logic; strategy selection; resource accounting |
| `SnapshotHelper` | `server/.../snapshot` | Resolves storage pool IDs for cross-zone replication |
| `SnapshotServiceImpl` | `engine/storage/snapshot` | Interacts with primary data store driver asynchronously |
| `DefaultSnapshotStrategy` | `engine/storage/snapshot` | Hypervisor-based (file) snapshot implementation |
| `StorageSystemSnapshotStrategy` | `engine/storage/snapshot` | Managed storage native snapshot implementation |
| `StorPoolSnapshotStrategy` | `plugins/storage/volume/storpool` | StorPool native snapshot; handles DELETE and cross-zone COPY |
| `StorageStrategyFactory` | `engine/storage` | Selects the highest-priority strategy for each operation |

---

## Key Configuration Parameters

| Parameter | Default | Description |
|---|---|---|
| `backup.snapshot.after.taking.snapshot` (`BackupSnapshotAfterTakingSnapshot`) | `true` | Whether to back up snapshot to secondary storage after creation |
| `snapshot.backup.retries` | `3` | Number of retry attempts for asynchronous snapshot backup |
| `snapshot.backup.retry.interval` | `300` (seconds) | Interval between retry attempts for async backup |
| `use.storage.replication` | `false` | Use native storage replication (e.g., StorPool cross-zone copy) instead of secondary storage copy |
| `snapshot.copy.multiply.exp.backoff` | — | Exponential backoff configuration for snapshot copy retries |

---

## Rollback Summary

CloudStack implements rollback at multiple layers to maintain consistency:

1. **Resource limit rollback** — On any exception in `SnapshotManagerImpl.takeSnapshot()`, snapshot count and storage quotas are decremented back to their original values.
2. **Volume state rollback** — `Volume.Event.OperationFailed` is fired so the volume returns to `Ready` state.
3. **Snapshot state machine** — Snapshot transitions to `Error` or `Destroyed` so it can be cleaned up by the background expunge process.
4. **Async backup failure cleanup** — After exhausting all retries, `cleanupOnSnapshotBackupFailure()` runs in a transaction to delete the snapshot record and associated job metadata.
5. **StorPool cross-zone recovery** — The exported (but not yet imported) snapshot name is persisted in `snapshot_details` with the key `SP_RECOVERED_SNAPSHOT`, enabling manual or automated cleanup of partial cross-zone copies.
