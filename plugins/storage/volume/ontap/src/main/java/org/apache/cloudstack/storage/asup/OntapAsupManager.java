/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cloudstack.storage.asup;

import com.cloud.storage.Volume;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.net.NetUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.poll.BackgroundPollManager;
import org.apache.cloudstack.poll.BackgroundPollTask;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Cluster;
import org.apache.cloudstack.storage.feign.model.EmsApplicationLog;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Periodic ASUP (AutoSupport) telemetry pusher for the NetApp ONTAP plugin.
 *
 * <p>This manager runs on a fixed interval and, for each
 * ONTAP-backed primary storage pool, pushes two minimal EMS application-log messages to
 * the backing ONTAP cluster:</p>
 *
 * <ul>
 *     <li><b>event-id 0 (heartbeat):</b> identifies the CloudStack deployment (CloudStack
 *         version, management host) connected to the ONTAP cluster (ONTAP cluster version).</li>
 *     <li><b>event-id 1 (pool):</b> maps the CloudStack storage pool to its backing ONTAP
 *         volume - protocol (NFS/iSCSI), ONTAP FlexVolume UUID, SVM, disk usage, and
 *         snapshot telemetry (counts by state, total provisioned size).</li>
 * </ul>
 */
public class OntapAsupManager extends ManagerBase implements Configurable {

    public static final ConfigKey<Boolean> AsupEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "ontap.asup.enabled", "true",
            "Enable periodic ASUP (AutoSupport) telemetry push from the CloudStack ONTAP plugin to the ONTAP cluster.",
            true, ConfigKey.Scope.Global);

    public static final ConfigKey<Integer> AsupIntervalSeconds = new ConfigKey<>("Advanced", Integer.class,
            "ontap.asup.interval", "3600",
            "Interval (in seconds) between periodic ASUP telemetry pushes from the CloudStack ONTAP plugin.",
            true, ConfigKey.Scope.Global);

    /** Time (in seconds) to wait while acquiring the single-emitter global lock. */
    private static final int ASUP_LOCK_TIMEOUT_SECONDS = 5;
    /** Default interval (in seconds) used when the configured value is missing or invalid. */
    private static final int ASUP_DEFAULT_INTERVAL_SECONDS = 3600;

    /**
     * Volume states that guarantee a physical object exists on the ONTAP FlexVolume.
     * States like {@link Volume.State#Allocated} have a CloudStack DB row pointing to this
     * pool but ONTAP provisioning has not been called yet — they must be excluded to avoid
     * inflating disk counts and provisioned-size totals. Upload-family states live on
     * secondary storage, not on the primary ONTAP volume, so they are also excluded.
     */
    private static final Set<Volume.State> ONTAP_PRESENT_STATES = EnumSet.of(
            Volume.State.Ready,
            Volume.State.Migrating,
            Volume.State.Snapshotting,
            Volume.State.RevertSnapshotting,
            Volume.State.Resizing,
            Volume.State.Attaching,
            Volume.State.Restoring,
            Volume.State.Expunging,
            Volume.State.Destroying
    );

    /** Serializes the structured event-description payloads to JSON. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private SnapshotDetailsDao snapshotDetailsDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private BackgroundPollManager backgroundPollManager;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        // Submit the periodic ASUP task to CloudStack's shared background poll manager.
        // This must happen in the configure-phase: the poll manager schedules all submitted
        // tasks during its own start-phase and rejects late submissions. Using the shared
        // scheduler means this plugin does not create or manage its own thread.
        backgroundPollManager.submitTask(new OntapAsupPollTask());
        logger.info("OntapAsupManager configured; ASUP poll task submitted to BackgroundPollManager");
        return true;
    }

    /**
     * Iterates all ONTAP-backed primary storage pools and pushes ASUP telemetry for each.
     *
     * <p>Guarded by a {@link GlobalLock} so that, in a multi-management-server deployment,
     * only one node emits per cycle.</p>
     */
    protected void pushAsupForAllPools() {
        if (Boolean.FALSE.equals(AsupEnabled.value())) {
            logger.debug("ONTAP ASUP: telemetry is disabled ({}=false); skipping this cycle.", AsupEnabled.key());
            return;
        }
        List<StoragePoolVO> pools = storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME);
        if (CollectionUtils.isEmpty(pools)) {
            logger.debug("ONTAP ASUP: no ONTAP-backed storage pools found; nothing to push.");
            return;
        }

        GlobalLock lock = GlobalLock.getInternLock(OntapStorageConstants.ASUP_GLOBAL_LOCK_NAME);
        try {
            if (!lock.lock(ASUP_LOCK_TIMEOUT_SECONDS)) {
                logger.debug("ONTAP ASUP: another management server holds the ASUP lock; skipping this cycle.");
                return;
            }
            String cloudStackVersion = getCloudStackVersion();
            String computerName = getComputerName();
            logger.debug("ONTAP ASUP: pushing telemetry for {} pool(s) [CloudStack version={}]",
                    pools.size(), cloudStackVersion);
            // Tracks clusters that have already received a heartbeat this cycle, so that multiple
            // pools backed by the same ONTAP cluster emit only a single heartbeat (event-id 0),
            // while each distinct cluster still gets its own heartbeat per cycle.
            Set<String> clustersHeartbeated = new HashSet<>();
            for (StoragePoolVO pool : pools) {
                pushAsupForPool(pool, cloudStackVersion, computerName, clustersHeartbeated);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Pushes the heartbeat (event-id 0) and pool (event-id 1) ASUP messages for a single pool.
     *
     * <p>The heartbeat is emitted at most once per distinct ONTAP cluster per cycle: the cluster's
     * UUID (or its storage IP when the UUID is unavailable) is recorded in {@code clustersHeartbeated},
     * and subsequent pools backed by the same cluster skip the heartbeat. The pool mapping message
     * is always emitted, once per pool.</p>
     *
     * <p>Best-effort: any failure is logged and swallowed.</p>
     */
    protected void pushAsupForPool(StoragePoolVO pool, String cloudStackVersion, String computerName,
            Set<String> clustersHeartbeated) {
        try {
            Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(pool.getId());
            if (details == null || details.isEmpty()) {
                logger.warn("ONTAP ASUP: storage pool [{}] has no details; skipping.", pool.getId());
                return;
            }

            StorageStrategy strategy = OntapStorageUtils.getStrategyByStoragePoolDetails(details);
            // Fetch the ONTAP cluster once and reuse its identity (uuid, name) and version
            // for both messages, avoiding extra REST round-trips.
            Cluster cluster = strategy.getClusterInfo();
            String ontapVersion = strategy.extractClusterVersion(cluster);
            String clusterUuid = cluster != null ? cluster.getUuid() : null;
            String clusterName = cluster != null ? cluster.getName() : null;
            String appVersion = buildAppVersion(cloudStackVersion, ontapVersion);

            // event-id 0: CloudStack -> ONTAP cluster heartbeat (versions), emitted once per cluster.
            // Key on the cluster UUID; fall back to the storage IP if the UUID is unavailable.
            String clusterKey = StringUtils.isNotBlank(clusterUuid) ? clusterUuid
                    : details.get(OntapStorageConstants.STORAGE_IP);
            if (clusterKey == null || clustersHeartbeated.add(clusterKey)) {
                EmsApplicationLog heartbeat = buildBaseMessage(computerName, appVersion);
                heartbeat.setEventId(OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT);
                heartbeat.setEventDescription(
                        buildHeartbeatDescription(cloudStackVersion, ontapVersion, clusterUuid));
                strategy.sendAsupMessage(heartbeat);
            } else {
                logger.debug("ONTAP ASUP: heartbeat already sent this cycle for cluster [{}]; skipping for pool [{}]",
                        defaultUnknown(clusterName), pool.getId());
            }

            // event-id 1: CloudStack storage pool -> backing ONTAP volume mapping, once per pool.
            // The description also includes disk usage and snapshot telemetry (see buildPoolDescription).
            EmsApplicationLog poolMessage = buildBaseMessage(computerName, appVersion);
            poolMessage.setEventId(OntapStorageConstants.ASUP_EVENT_ID_POOL);
            poolMessage.setEventDescription(buildPoolDescription(pool, details, clusterUuid));
            strategy.sendAsupMessage(poolMessage);

            logger.debug("ONTAP ASUP: pushed telemetry for pool [{}] (ONTAP version={})",
                    pool.getId(), defaultUnknown(ontapVersion));
        } catch (Exception e) {
            // Best-effort telemetry; never propagate.
            logger.warn("ONTAP ASUP: failed to push telemetry for pool [{}]: {}", pool.getId(), e.getMessage());
        }
    }

    /**
     * Builds the heartbeat (event-id 0) description as a JSON object carrying the CloudStack and
     * ONTAP versions, the management-server operating system platform, plus the ONTAP cluster UUID.
     * Example: {@code {"message":"CloudStack connected to ONTAP cluster","cloudstackVersion":
     * "4.23.0.0","platform":"Linux 5.15.0-91-generic (amd64)","ontapVersion":"9.17.1",
     * "clusterUuid":"..."}}
     */
    private String buildHeartbeatDescription(String cloudStackVersion, String ontapVersion,
            String clusterUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "CloudStack connected to ONTAP cluster");
        payload.put("cloudstackVersion", defaultUnknown(cloudStackVersion));
        payload.put("platform", getOperatingSystem());
        payload.put("ontapVersion", defaultUnknown(ontapVersion));
        payload.put("clusterUuid", defaultUnknown(clusterUuid));
        return toJson(payload);
    }

    /**
     * Builds the pool description (event-id 1) as a JSON object combining the backing-volume
     * mapping, disk usage, and snapshot telemetry into a single EMS message.
     *
     * <p>Example: {@code {"message":"CloudStack storage pool backed by ONTAP volume",
     * "poolName":"...","protocol":"nfs","clusterUuid":"...","svm":"...",
     * "ontapVolumeUuid":"...","rootDiskCount":12,"dataDiskCount":18,
     * "totalProvisionedSizeBytes":322122547200,
     * "csVolumeSnapshotCount":5,"csVolumeSnapshotProvisionedSizeBytes":107374182400,
     * "vmSnapshotCount":3,"vmSnapshotSizeBytes":322122547200}}</p>
     */
    private String buildPoolDescription(StoragePoolVO pool, Map<String, String> details,
            String clusterUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "CloudStack storage pool backed by ONTAP volume");
        payload.put("poolName", defaultUnknown(pool.getName()));
        payload.put("protocol", defaultUnknown(details.get(OntapStorageConstants.PROTOCOL)));
        payload.put("clusterUuid", defaultUnknown(clusterUuid));
        payload.put("svm", defaultUnknown(details.get(OntapStorageConstants.SVM_NAME)));
        payload.put("ontapVolumeUuid", defaultUnknown(details.get(OntapStorageConstants.VOLUME_UUID)));
        addPoolUsage(pool, payload);
        addSnapshotUsage(pool, payload);
        return toJson(payload);
    }

    /**
     * Computes pool usage from CloudStack's volume records and adds it to the payload:
     * <ul>
     *     <li>{@code rootDiskCount} - number of ROOT (boot) disks physically on this pool</li>
     *     <li>{@code dataDiskCount} - number of DATADISK disks physically on this pool</li>
     *     <li>{@code attachedVmCount} - number of distinct VMs that have at least one volume on
     *         this pool; always &ge; {@code rootDiskCount} because a VM's root disk may live on a
     *         different pool while one of its data disks resides here</li>
     *     <li>{@code totalProvisionedSizeBytes} - sum of those volumes' provisioned (logical) sizes
     *         in bytes; for thin-provisioned volumes this is the logical size requested at
     *         creation time, not the physical space consumed on ONTAP</li>
     * </ul>
     * All volume types are counted (both ROOT and DATADISK); the {@code null} type argument
     * disables the type filter in the DAO. All derived values are computed in-memory from the
     * same single query (no extra round-trips). Best-effort: any failure leaves the usage
     * fields out and never breaks telemetry.
     */
    private void addPoolUsage(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            // Pass null volume-type to include ALL volumes (ROOT + DATADISK). The single-arg
            // findNonDestroyedVolumesByPoolId(poolId) overload hardcodes ROOT-only and would
            // undercount data disks.
            List<VolumeVO> volumes = volumeDao.findNonDestroyedVolumesByPoolId(pool.getId(), null);

            // Only count volumes that definitely have a physical object on the ONTAP FlexVolume.
            // "Allocated" volumes have a pool_id row in the CS DB but ONTAP provisioning has not
            // yet been called, so including them would inflate counts and provisioned size.
            // Upload-family states live on secondary storage, not on this primary pool.
            List<VolumeVO> ontapVolumes = volumes.stream()
                    .filter(v -> ONTAP_PRESENT_STATES.contains(v.getState()))
                    .collect(java.util.stream.Collectors.toList());

            long rootDiskCount = ontapVolumes.stream()
                    .filter(v -> Volume.Type.ROOT.equals(v.getVolumeType())).count();
            long dataDiskCount = ontapVolumes.stream()
                    .filter(v -> Volume.Type.DATADISK.equals(v.getVolumeType())).count();
            // Count distinct VMs that have at least one volume on this pool.
            // A VM whose root disk is on a different pool but has a data disk here is still counted,
            // so attachedVmCount >= rootDiskCount is always guaranteed.
            long attachedVmCount = ontapVolumes.stream()
                    .map(VolumeVO::getInstanceId)
                    .filter(id -> id != null)
                    .distinct()
                    .count();
            // getSize() is the provisioned (logical) size from the CloudStack volumes table; for
            // thin-provisioned volumes the physical space used on ONTAP can be far smaller.
            long totalProvisionedSizeBytes = ontapVolumes.stream()
                    .mapToLong(v -> v.getSize() != null ? v.getSize() : 0L).sum();
            payload.put("rootDiskCount", rootDiskCount);
            payload.put("dataDiskCount", dataDiskCount);
            payload.put("attachedVmCount", attachedVmCount);
            payload.put("totalProvisionedSizeBytes", totalProvisionedSizeBytes);
        } catch (Exception e) {
            logger.warn("ONTAP ASUP: failed to compute usage for pool [{}]: {}", pool.getId(), e.getMessage());
        }
    }

    /**
     * Computes and adds two groups of snapshot telemetry to the pool description payload.
     *
     * <p><b>Volume-snapshot metrics</b> ({@code csVolumeSnapshotCount},
     * {@code csVolumeSnapshotSizeBytes}): counts all non-destroyed CloudStack
     * volume-level snapshots for volumes on this pool.  Where an ONTAP-side size has been
     * recorded in {@code snapshot_details} (key {@code ontap_snap_size}) that value is used;
     * otherwise the source volume's provisioned size is used as a conservative upper bound.</p>
     *
     * <p><b>VM-snapshot metrics</b> ({@code vmSnapshotCount},
     * {@code vmSnapshotSizeBytes}): counts all active (non-expunging, non-removed) VM
     * snapshots for VMs that have at least one volume on this pool.  Because ONTAP stores
     * VM snapshots as FlexVol-level snapshots, the ONTAP-space estimate is computed as
     * {@code sum(poolVolumes.size) × vmSnapshotCount} — a pool-level approximation.</p>
     *
     * <p>Best-effort: any failure leaves the fields out without breaking telemetry.</p>
     */
    private void addSnapshotUsage(StoragePoolVO pool, Map<String, Object> payload) {
        addVolumeSnapshotMetrics(pool, payload);
        addVmSnapshotMetrics(pool, payload);
    }

    /**
     * Adds {@code csVolumeSnapshotCount} and {@code csVolumeSnapshotProvisionedSizeBytes} to the payload.
     *
     * <p>Size per snapshot is read from the {@code ontap_snap_size} snapshot-detail key, which is
     * written at {@code takeSnapshot} time and holds the source volume's provisioned size at that
     * moment. If the detail is missing (e.g. snapshots taken before this feature was deployed) the
     * current provisioned size of the source volume is used as a fallback.</p>
     */
    private void addVolumeSnapshotMetrics(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            List<VolumeVO> volumes = volumeDao.findNonDestroyedVolumesByPoolId(pool.getId(), null);
            if (volumes == null || volumes.isEmpty()) {
                payload.put("csVolumeSnapshotCount", 0);
                payload.put("csVolumeSnapshotProvisionedSizeBytes", 0L);
                return;
            }

            List<Long> volumeIds = new java.util.ArrayList<>();
            for (VolumeVO v : volumes) {
                volumeIds.add(v.getId());
            }

            List<SnapshotVO> snapshots = snapshotDao.searchByVolumes(volumeIds);
            long snapCount = 0;
            long snapSizeBytes = 0L;

            if (snapshots != null) {
                for (SnapshotVO snap : snapshots) {
                    if (com.cloud.storage.Snapshot.State.Destroyed.equals(snap.getState())) {
                        continue;
                    }
                    snapCount++;
                    // Prefer the size stored at takeSnapshot time (source volume provisioned size
                    // captured at the moment of snapshot creation). Falls back to the current
                    // provisioned size of the source volume for older snapshots that pre-date
                    // the ONTAP_SNAP_SIZE detail being written.
                    com.cloud.storage.dao.SnapshotDetailsVO sizeDetail =
                            snapshotDetailsDao.findDetail(snap.getId(), OntapStorageConstants.ONTAP_SNAP_SIZE);
                    if (sizeDetail != null && sizeDetail.getValue() != null) {
                        try {
                            snapSizeBytes += Long.parseLong(sizeDetail.getValue());
                        } catch (NumberFormatException ignored) {}
                    } else {
                        VolumeVO srcVol = volumeDao.findById(snap.getVolumeId());
                        if (srcVol != null && srcVol.getSize() != null) {
                            snapSizeBytes += srcVol.getSize();
                        }
                    }
                }
            }

            payload.put("csVolumeSnapshotCount", snapCount);
            payload.put("csVolumeSnapshotProvisionedSizeBytes", snapSizeBytes);
        } catch (Exception e) {
            logger.warn("ONTAP ASUP: failed to compute volume-snapshot metrics for pool [{}]: {}",
                    pool.getId(), e.getMessage());
        }
    }

    /**
     * Adds {@code vmSnapshotCount} and {@code vmSnapshotSizeBytes} to the payload.
     *
     * <p>VM snapshots are stored on ONTAP as FlexVol-level snapshots, so the size estimate
     * is {@code sum(poolVolumeSize) × vmSnapshotCount} — the total provisioned pool space
     * that could be consumed by those snapshots.</p>
     */
    private void addVmSnapshotMetrics(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            List<VolumeVO> volumes = volumeDao.findNonDestroyedVolumesByPoolId(pool.getId(), null);
            if (volumes == null || volumes.isEmpty()) {
                payload.put("vmSnapshotCount", 0);
                payload.put("vmSnapshotSizeBytes", 0L);
                return;
            }

            // Collect the unique VM IDs that have volumes on this pool, and total pool volume size.
            java.util.Set<Long> vmIds = new java.util.HashSet<>();
            long totalPoolVolumeSizeBytes = 0L;
            for (VolumeVO v : volumes) {
                if (v.getInstanceId() != null) {
                    vmIds.add(v.getInstanceId());
                }
                if (v.getSize() != null) {
                    totalPoolVolumeSizeBytes += v.getSize();
                }
            }

            if (vmIds.isEmpty()) {
                payload.put("vmSnapshotCount", 0);
                payload.put("vmSnapshotSizeBytes", 0L);
                return;
            }

            // Fetch all active VM snapshots for those VMs in one query.
            List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.searchByVms(new java.util.ArrayList<>(vmIds));
            long vmSnapCount = 0;
            if (vmSnapshots != null) {
                for (VMSnapshotVO vmSnap : vmSnapshots) {
                    VMSnapshot.State state = vmSnap.getState();
                    // Count only active (visible) VM snapshots; skip expunging / removed entries.
                    if (!VMSnapshot.State.Expunging.equals(state) && vmSnap.getRemoved() == null) {
                        vmSnapCount++;
                    }
                }
            }

            // Size estimate: each VM snapshot captures all volumes currently on the FlexVol.
            // totalPoolVolumeSizeBytes * vmSnapCount gives the cumulative space that could
            // be attributed to VM snapshots on this pool.
            long vmSnapSizeBytes = totalPoolVolumeSizeBytes * vmSnapCount;

            payload.put("vmSnapshotCount", vmSnapCount);
            payload.put("vmSnapshotSizeBytes", vmSnapSizeBytes);
        } catch (Exception e) {
            logger.warn("ONTAP ASUP: failed to compute VM-snapshot metrics for pool [{}]: {}",
                    pool.getId(), e.getMessage());
        }
    }

    /**
     * Serializes a payload map to a JSON string. Falls back to the map's {@code toString()} if
     * serialization unexpectedly fails, so telemetry is still emitted (best-effort).
     */
    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            logger.warn("ONTAP ASUP: failed to serialize event description to JSON: {}", e.getMessage());
            return String.valueOf(payload);
        }
    }

    /** Builds the common EMS message envelope shared by all ASUP messages. */
    private EmsApplicationLog buildBaseMessage(String computerName, String appVersion) {
        EmsApplicationLog message = new EmsApplicationLog();
        message.setComputerName(computerName);
        message.setEventSource(OntapStorageConstants.ASUP_EVENT_SOURCE);
        message.setAppVersion(appVersion);
        message.setCategory(OntapStorageConstants.ASUP_CATEGORY);
        message.setSeverity(OntapStorageConstants.ASUP_SEVERITY);
        message.setAutosupportRequired(Boolean.FALSE);
        return message;
    }

    /** Composes "cloudstack-&lt;version&gt;|ontap-&lt;version&gt;" for the EMS app-version field. */
    private String buildAppVersion(String cloudStackVersion, String ontapVersion) {
        return "cloudstack-" + cloudStackVersion + "|ontap-" + defaultUnknown(ontapVersion);
    }

    /**
     * Resolves the CloudStack management server version from the running artifact's manifest.
     * Falls back to "unknown" when not available (e.g. running from an IDE without a manifest).
     */
    protected String getCloudStackVersion() {
        String version = this.getClass().getPackage().getImplementationVersion();
        if (StringUtils.isBlank(version)) {
            version = OntapAsupManager.class.getPackage().getImplementationVersion();
        }
        return StringUtils.isBlank(version) ? OntapStorageConstants.ASUP_UNKNOWN : version;
    }

    /** Resolves the management server hostname for the EMS computer-name field. */
    protected String getComputerName() {
        String hostName = NetUtils.getCanonicalHostName();
        return StringUtils.isBlank(hostName) ? OntapStorageConstants.ASUP_UNKNOWN : hostName;
    }

    /**
     * Resolves the management server operating system (name, version and architecture) from JVM
     * system properties, e.g. {@code "Linux 5.15.0-91-generic (amd64)"}. Falls back to "unknown".
     */
    protected String getOperatingSystem() {
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        if (StringUtils.isBlank(osName)) {
            return OntapStorageConstants.ASUP_UNKNOWN;
        }
        StringBuilder sb = new StringBuilder(osName);
        if (StringUtils.isNotBlank(osVersion)) {
            sb.append(' ').append(osVersion);
        }
        if (StringUtils.isNotBlank(osArch)) {
            sb.append(" (").append(osArch).append(')');
        }
        return sb.toString();
    }

    private String defaultUnknown(String value) {
        return StringUtils.isBlank(value) ? OntapStorageConstants.ASUP_UNKNOWN : value;
    }

    @Override
    public String getConfigComponentName() {
        return OntapAsupManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {AsupEnabled, AsupIntervalSeconds};
    }

    /**
     * Background poll task that runs the ASUP push within a managed CloudStack context.
     *
     * <p>Submitted once to the shared {@link BackgroundPollManager} during the configure-phase;
     * the poll manager owns the thread and invokes this task every {@link #getDelay()} ms.</p>
     */
    protected class OntapAsupPollTask extends ManagedContextRunnable implements BackgroundPollTask {
        @Override
        protected void runInContext() {
            try {
                pushAsupForAllPools();
            } catch (Exception e) {
                // Best-effort telemetry; never let the poll thread die.
                logger.warn("ONTAP ASUP: unexpected error during periodic push: {}", e.getMessage());
            }
        }

        @Override
        public Long getDelay() {
            int intervalSeconds = AsupIntervalSeconds.value() != null
                    ? AsupIntervalSeconds.value() : ASUP_DEFAULT_INTERVAL_SECONDS;
            if (intervalSeconds <= 0) {
                intervalSeconds = ASUP_DEFAULT_INTERVAL_SECONDS;
            }
            return intervalSeconds * 1000L;
        }
    }
}
