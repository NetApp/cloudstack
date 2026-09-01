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

import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.event.EventTypes;
import com.cloud.server.ManagementService;
import com.cloud.storage.Volume;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.net.NetUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.poll.BackgroundPollManager;
import org.apache.cloudstack.poll.BackgroundPollTask;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Cluster;
import org.apache.cloudstack.storage.feign.model.EmsApplicationLog;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.utils.OntapConfigurationManager;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
public class OntapAsupManager extends ManagerBase {
    private static final int ASUP_LOCK_TIMEOUT_SECONDS = 5;

    /**
     * Fixed wakeup interval (ms) for {@link OntapAsupPollTask} (2 hours). The task wakes on
     * this cadence and checks whether the live configured push interval
     * ({@link OntapConfigurationManager#AsupIntervalSeconds}) has elapsed. UI edits of that
     * interval are applied immediately via the configuration-edit event; this delay is only
     * the background check so a due push is still noticed with no UI click.
     */
    static final long ASUP_POLL_CHECK_INTERVAL_MS =
            TimeUnit.SECONDS.toMillis(OntapStorageConstants.ASUP_POLL_CHECK_INTERVAL_SECONDS);

    /**
     * Volume states that guarantee a physical object exists on the ONTAP FlexVolume.
     * States like {@link Volume.State#Allocated} have a CloudStack DB row pointing to this
     * pool but ONTAP provisioning has not been called yet — they must be excluded to avoid
     * inflating disk counts and provisioned-size totals. Upload-family states live on
     * secondary storage, not on the primary ONTAP volume, so they are also excluded.
     */
    private static final Set<Volume.State> CS_VOLUME_STATES = EnumSet.of(
            Volume.State.Ready,
            Volume.State.Snapshotting,
            Volume.State.RevertSnapshotting,
            Volume.State.Attaching,
            Volume.State.Restoring,
            Volume.State.Expunging,
            Volume.State.Destroying
    );

    /** Serializes the structured event-description payloads to JSON. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Timestamp of the last successful ASUP push. Starts at {@link Instant#EPOCH} so the
     * very first wakeup always fires immediately. {@code volatile} ensures the poll-task
     * thread's write is visible without synchronization overhead.
     */
    volatile Instant lastPushTime = Instant.EPOCH;

    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private BackgroundPollManager backgroundPollManager;
    @Inject
    private ManagementService managementService;
    @Inject
    private ManagementServerHostDao managementServerHostDao;
    @Inject
    private MessageBus messageBus;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        // Submit the periodic ASUP task to CloudStack's shared background poll manager.
        // This must happen in the configure-phase: the poll manager schedules all submitted
        // tasks during its own start-phase and rejects late submissions. Using the shared
        // scheduler means this plugin does not create or manage its own thread.
        backgroundPollManager.submitTask(new OntapAsupPollTask());
        // re-run the existing poll check when our
        // dynamic keys change so the new value is applied without waiting for the next wakeup.
        messageBus.subscribe(EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, this::onAsupConfigEdited);
        logger.info("OntapAsupManager configured; ASUP poll task submitted to BackgroundPollManager");
        return true;
    }

    /**
     * CloudStack publishes this after invalidating the config cache. Reuses
     * {@link OntapAsupPollTask} so enable/interval rules stay in one place.
     */
    @SuppressWarnings("unchecked")
    private void onAsupConfigEdited(String senderAddress, String subject, Object args) {
        if (!(args instanceof Ternary)) {
            return;
        }
        String updatedKey = ((Ternary<String, ConfigKey.Scope, Long>) args).first();
        if (!OntapConfigurationManager.AsupEnabled.key().equals(updatedKey)
                && !OntapConfigurationManager.AsupIntervalSeconds.key().equals(updatedKey)) {
            return;
        }
        logger.debug("ONTAP ASUP: [{}] was updated; re-evaluating now.", updatedKey);
        new OntapAsupPollTask().run();
    }

    /**
     * Background poll task that runs the ASUP push within a managed CloudStack context.
     *
     * <p>Wakes every {@link #ASUP_POLL_CHECK_INTERVAL_MS} ms. If ASUP is disabled the
     * wakeup returns immediately without advancing {@link #lastPushTime}. Otherwise it
     * reads the live {@link OntapConfigurationManager#AsupIntervalSeconds} and only pushes if that interval has
     * elapsed. Interval and enable changes in the UI also trigger this check via
     * {@link #onAsupConfigEdited}.</p>
     */
    protected class OntapAsupPollTask extends ManagedContextRunnable implements BackgroundPollTask {
        @Override
        protected void runInContext() {
            try {
                if (Boolean.FALSE.equals(OntapConfigurationManager.AsupEnabled.value())) {
                    logger.debug("ONTAP ASUP: telemetry is disabled ({}=false); skipping this cycle.",
                            OntapConfigurationManager.AsupEnabled.key());
                    return;
                }
                Duration configuredInterval = Duration.ofSeconds(
                        getAsupIntervalSeconds(OntapConfigurationManager.AsupIntervalSeconds.value()));
                Instant now = Instant.now();
                if (Duration.between(lastPushTime, now).compareTo(configuredInterval) < 0) {
                    return; // configured interval has not elapsed yet
                }
                lastPushTime = now;
                pushAsupTelemetry();
            } catch (Exception e) {
                logger.warn("ONTAP ASUP: unexpected error during periodic push: {}", e.getMessage());
            }
        }

        @Override
        public Long getDelay() {
            return ASUP_POLL_CHECK_INTERVAL_MS;
        }
    }

    /**
     * Iterates all ONTAP-backed primary storage pools and pushes ASUP telemetry for each.
     *
     * <p>Guarded by a {@link GlobalLock} so that, in a multi-management-server deployment,
     * only one node emits per cycle.</p>
     */
    protected void pushAsupTelemetry() {
        if (Boolean.FALSE.equals(OntapConfigurationManager.AsupEnabled.value())) {
            logger.debug("ONTAP ASUP: telemetry is disabled ({}=false); skipping this cycle.",
                    OntapConfigurationManager.AsupEnabled.key());
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
            logger.debug("ONTAP ASUP: pushing telemetry for {} pool(s) [CloudStack version={}]",
                    pools.size(), getCloudStackVersion());
            // Tracks clusters that have already received a heartbeat this cycle, so that multiple
            // pools backed by the same ONTAP cluster emit only a single heartbeat (event-id 0),
            // while each distinct cluster still gets its own heartbeat per cycle.
            Set<String> clustersHeartBeated = new HashSet<>();
            for (StoragePoolVO pool : pools) {
                pushAsupForStoragePool(pool, clustersHeartBeated);
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
    protected void pushAsupForStoragePool(StoragePoolVO pool, Set<String> clustersHeartbeated) {
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
            String ontapVersion = strategy.getClusterVersion(cluster);
            String clusterUuid = cluster != null ? cluster.getUuid() : null;
            String clusterName = cluster != null ? cluster.getName() : null;
            String cloudStackVersion = getCloudStackVersion();
            String computerName = getComputerName();

            // event-id 0: CloudStack -> ONTAP cluster heartbeat (versions), emitted once per ontap cluster.
            // Key on the cluster UUID; fall back to the storage IP if the UUID is unavailable.
            String clusterKey = StringUtils.isNotBlank(clusterUuid) ? clusterUuid
                    : details.get(OntapStorageConstants.STORAGE_IP);
            if (clusterKey == null || clustersHeartbeated.add(clusterKey)) {
                EmsApplicationLog heartbeat = buildBaseMessage(computerName, cloudStackVersion);
                heartbeat.setEventId(OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT);
                heartbeat.setEventDescription(buildHeartbeatDescription(cloudStackVersion, ontapVersion, clusterUuid));
                strategy.sendAsupMessage(heartbeat);
            } else {
                logger.debug("ONTAP ASUP: heartbeat already sent this cycle for cluster [{}]; skipping for pool [{}]",
                        defaultUnknown(clusterName), pool.getId());
            }

            // event-id 1: CloudStack storage pool -> backing ONTAP volume mapping, once per pool.
            // The description also includes disk usage and snapshot telemetry
            EmsApplicationLog poolMessage = buildBaseMessage(computerName, cloudStackVersion);
            poolMessage.setEventId(OntapStorageConstants.ASUP_EVENT_ID_STORAGE_POOL);
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
     * ONTAP versions, the management-server operating system platform, and the ONTAP cluster UUID.
     * Example: {@code {"message":"CloudStack connected to ONTAP cluster","cloudstackVersion":
     * "4.23.0.0","platform":"Linux 5.15.0-91-generic (amd64)","ontapVersion":"9.17.1",
     * "clusterUuid":"...","managementServerCount":2}}
     */
    private String buildHeartbeatDescription(String cloudStackVersion, String ontapVersion,
            String clusterUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(OntapStorageConstants.ASUP_MESSAGE, OntapStorageConstants.ASUP_HEARTBEAT_MESSAGE);
        payload.put(OntapStorageConstants.ASUP_CLOUDSTACK_VERSION, defaultUnknown(cloudStackVersion));
        payload.put(OntapStorageConstants.ASUP_PLATFORM, getOperatingSystem());
        payload.put(OntapStorageConstants.ASUP_ONTAP_VERSION, defaultUnknown(ontapVersion));
        payload.put(OntapStorageConstants.ASUP_CLUSTER_UUID, defaultUnknown(clusterUuid));
        payload.put(OntapStorageConstants.ASUP_MANAGEMENT_SERVER_COUNT, getManagementServerCount());
        return toJson(payload);
    }

    /**
     * Builds the pool description (event-id 1) as a JSON object combining the backing-volume
     * mapping, disk usage, and snapshot telemetry into a single EMS message.
     *
     * <p>Example: {@code {"message":"CloudStack storage pool backed by ONTAP volume",
     * "poolName":"...","poolStatus":"Up","protocol":"nfs","clusterUuid":"...","svm":"...",
     * "ontapVolumeUuid":"...","rootDiskCount":12,"dataDiskCount":18,
     * "totalLogicalSizeBytes":322122547200,"multiPrimaryStoragePoolVm":false,
     * "volumeSnapshotCount":5,"vmSnapshotCount":3}}</p>
     */
    private String buildPoolDescription(StoragePoolVO pool, Map<String, String> details,
            String clusterUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(OntapStorageConstants.ASUP_MESSAGE, OntapStorageConstants.ASUP_POOL_MESSAGE);
        payload.put(OntapStorageConstants.ASUP_POOL_NAME, defaultUnknown(pool.getName()));
        payload.put(OntapStorageConstants.ASUP_POOL_STATUS,
                pool.getStatus() == null ? OntapStorageConstants.ASUP_UNKNOWN : pool.getStatus().toString());
        payload.put(OntapStorageConstants.ASUP_PROTOCOL, defaultUnknown(details.get(OntapStorageConstants.PROTOCOL)));
        payload.put(OntapStorageConstants.ASUP_CLUSTER_UUID, defaultUnknown(clusterUuid));
        payload.put(OntapStorageConstants.ASUP_SVM, defaultUnknown(details.get(OntapStorageConstants.SVM_NAME)));
        payload.put(OntapStorageConstants.ASUP_ONTAP_VOLUME_UUID, defaultUnknown(details.get(OntapStorageConstants.VOLUME_UUID)));
        addStoragePoolUsage(pool, payload);
        hasMultiPrimaryStoragePoolVm(pool, payload);
        addSnapshotMetrics(pool, payload);
        return toJson(payload);
    }

    /**
     * Computes pool usage from CloudStack's volume records and adds it to the payload:
     * <ul>
     *     <li>{@code rootDiskCount} - number of ROOT (boot) disks physically on this pool</li>
     *     <li>{@code dataDiskCount} - number of DATADISK disks physically on this pool</li>
     *     <li>{@code totalLogicalSizeBytes} - sum of those volumes' provisioned (logical) sizes
     *         in bytes; for thin-provisioned volumes this is the logical size requested at
     *         creation time, not the physical space consumed on ONTAP</li>
     * </ul>
     * All volume types are counted (both ROOT and DATADISK); the {@code null} type argument
     * disables the type filter in the DAO. All derived values are computed in-memory from the
     * same single query (no extra round-trips). Best-effort: any failure leaves the usage
     * fields out and never breaks telemetry.
     */
    private void addStoragePoolUsage(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            // Pass null volume-type to include ALL volumes (ROOT + DATADISK).
            List<VolumeVO> volumes = volumeDao.findNonDestroyedVolumesByPoolId(pool.getId(), null);

            // Only count volumes that definitely have a physical object on the ONTAP FlexVolume.
            // "Allocated" volumes have a pool_id row in the CS DB but ONTAP provisioning has not
            // yet been called, so including them would inflate counts and provisioned size.
            List<VolumeVO> cstackVolumes = volumes.stream()
                    .filter(v -> CS_VOLUME_STATES.contains(v.getState()))
                    .collect(java.util.stream.Collectors.toList());

            long rootDiskCount = cstackVolumes.stream()
                    .filter(v -> Volume.Type.ROOT.equals(v.getVolumeType())).count();
            long dataDiskCount = cstackVolumes.stream()
                    .filter(v -> Volume.Type.DATADISK.equals(v.getVolumeType())).count();

            long totalLogicalSizeBytes = cstackVolumes.stream()
                    .mapToLong(v -> v.getSize() != null ? v.getSize() : 0L).sum();
            payload.put(OntapStorageConstants.ASUP_ROOT_DISK_COUNT, rootDiskCount);
            payload.put(OntapStorageConstants.ASUP_DATA_DISK_COUNT, dataDiskCount);
            payload.put(OntapStorageConstants.ASUP_TOTAL_LOGICAL_SIZE_BYTES, totalLogicalSizeBytes);
        } catch (Exception e) {
            logger.error("ONTAP ASUP: failed to compute usage for pool [{}]: {}", pool.getId(), e.getMessage());
        }
    }

    /**
     * Adds {@code hasMultiPrimaryStoragePoolVm}: true when at least one VM with ROOT on this
     * pool also has an attached DATADISK on a different primary storage pool. Uses a single
     * {@code LIMIT 1} existence query.
     */
    private void hasMultiPrimaryStoragePoolVm(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            payload.put(OntapStorageConstants.ASUP_MULTI_PRIMARY_STORAGE_POOL_VM,
                    volumeDao.hasMultiPrimaryStoragePoolVm(pool.getId()));
        } catch (Exception e) {
            logger.warn("ONTAP ASUP: failed to compute multiPrimaryStoragePoolVm for pool [{}]: {}",
                    pool.getId(), e.getMessage());
        }
    }

    /**
     * Computes and adds two groups of snapshot telemetry to the pool description payload.
     *
     * <p><b>Volume-snapshot metrics</b> ({@code volumeSnapshotCount}): counts all
     * non-destroyed CloudStack volume-level snapshots for volumes on this pool.</p>
     *
     * <p><b>VM-snapshot metrics</b> ({@code vmSnapshotCount}): counts all active
     * (non-expunging, non-removed) VM snapshots for VMs that have at least one volume on
     * this pool.</p>
     *
     * <p>Best-effort: any failure leaves the fields out without breaking telemetry.</p>
     */
    private void addSnapshotMetrics(StoragePoolVO pool, Map<String, Object> payload) {
        addVmSnapshotMetrics(pool, payload);
        addVolumeSnapshotMetrics(pool, payload);
    }

    /**
     * Adds {@code volumeSnapshotCount} to the payload.
     * Counts all non-destroyed CloudStack volume-level snapshots for volumes on this pool.
     */
    private void addVolumeSnapshotMetrics(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            List<VolumeVO> volumes = volumeDao.findNonDestroyedVolumesByPoolId(pool.getId(), null);
            if (volumes == null || volumes.isEmpty()) {
                payload.put(OntapStorageConstants.ASUP_VOLUME_SNAPSHOT_COUNT, 0);
                return;
            }

            List<Long> volumeIds = volumes.stream()
                    .map(VolumeVO::getId)
                    .collect(java.util.stream.Collectors.toList());

            List<SnapshotVO> snapshots = snapshotDao.searchByVolumes(volumeIds);
            long snapCount = snapshots == null ? 0L : snapshots.stream()
                    .filter(snap -> !com.cloud.storage.Snapshot.State.Destroyed.equals(snap.getState()))
                    .count();

            payload.put(OntapStorageConstants.ASUP_VOLUME_SNAPSHOT_COUNT, snapCount);
        } catch (Exception e) {
            logger.warn("ONTAP ASUP: failed to compute volume-snapshot metrics for pool [{}]: {}",
                    pool.getId(), e.getMessage());
        }
    }

    /**
     * Adds {@code vmSnapshotCount} to the payload.
     * Counts all active (non-expunging, non-removed) VM snapshots for VMs that have at
     * least one volume on this pool.
     */
    private void addVmSnapshotMetrics(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            List<VolumeVO> volumes = volumeDao.findNonDestroyedVolumesByPoolId(pool.getId(), null);
            if (volumes == null || volumes.isEmpty()) {
                payload.put(OntapStorageConstants.ASUP_VM_SNAPSHOT_COUNT, 0);
                return;
            }

            java.util.Set<Long> vmIds = volumes.stream()
                    .map(VolumeVO::getInstanceId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            if (vmIds.isEmpty()) {
                payload.put(OntapStorageConstants.ASUP_VM_SNAPSHOT_COUNT, 0);
                return;
            }

            List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.searchByVms(new java.util.ArrayList<>(vmIds));
            long vmSnapCount = vmSnapshots == null ? 0L : vmSnapshots.stream()
                    .filter(vmSnap -> !VMSnapshot.State.Expunging.equals(vmSnap.getState())
                            && vmSnap.getRemoved() == null)
                    .count();

            payload.put(OntapStorageConstants.ASUP_VM_SNAPSHOT_COUNT, vmSnapCount);
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

    /**
     * CloudStack version of this management server, same source as {@link ManagementService#getVersion()}
     * / {@code listCapabilities}. Falls back to "unknown" when the server JAR has no manifest
     * (for example running from an IDE).
     */
    protected String getCloudStackVersion() {
        String version = managementService != null ? managementService.getVersion() : null;
        return StringUtils.isBlank(version) ? OntapStorageConstants.ASUP_UNKNOWN : version;
    }

    /**
     * Number of management servers registered in {@code mshost} (not removed), including nodes
     * that are not currently {@code Up}.
     */
    protected int getManagementServerCount() {
        try {
            List<ManagementServerHostVO> hosts = managementServerHostDao.listAll();
            return hosts == null ? 0 : hosts.size();
        } catch (Exception e) {
            logger.debug("ONTAP ASUP: unable to count management servers: {}", e.getMessage());
            return 0;
        }
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

    /**
     * Returns a usable interval for the poller. Out-of-range or missing DB values
     * (for example set outside the API) fall back to the default so ASUP is not
     * sent every poll cycle.
     */
    int getAsupIntervalSeconds(Integer configured) {
        if (configured == null) {
            return OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS;
        }
        if (configured < OntapStorageConstants.ASUP_MIN_INTERVAL_SECONDS
                || configured > OntapStorageConstants.ASUP_MAX_INTERVAL_SECONDS) {
            logger.warn("ONTAP ASUP: {} value [{}] is outside [{}-{}]; using default [{}]",
                    OntapStorageConstants.ASUP_INTERVAL_CONFIG_KEY, configured,
                    OntapStorageConstants.ASUP_MIN_INTERVAL_SECONDS,
                    OntapStorageConstants.ASUP_MAX_INTERVAL_SECONDS,
                    OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS);
            return OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS;
        }
        return configured;
    }
}
