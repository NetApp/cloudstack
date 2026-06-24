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

import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *         volume - protocol (NFS/iSCSI), ONTAP FlexVolume UUID and name, and SVM.</li>
 * </ul>
 */
public class OntapAsupManager extends ManagerBase implements Configurable {

    public static final ConfigKey<Boolean> AsupEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "ontap.asup.enabled", "true",
            "Enable periodic ASUP (AutoSupport) telemetry push from the CloudStack ONTAP plugin to the ONTAP cluster.",
            true, ConfigKey.Scope.Global);

    // TODO(test-only): default lowered to 120s (2 min) for testing; revert to "3600" before merging.
    public static final ConfigKey<Integer> AsupIntervalSeconds = new ConfigKey<>("Advanced", Integer.class,
            "ontap.asup.interval", "120",
            "Interval (in seconds) between periodic ASUP telemetry pushes from the CloudStack ONTAP plugin.",
            true, ConfigKey.Scope.Global);

    /** Time (in seconds) to wait while acquiring the single-emitter global lock. */
    private static final int ASUP_LOCK_TIMEOUT_SECONDS = 5;
    /** Default interval (in seconds) used when the configured value is missing or invalid. */
    private static final int ASUP_DEFAULT_INTERVAL_SECONDS = 3600;

    /** Serializes the structured event-description payloads to JSON. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject
    private VolumeDao volumeDao;
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
     * ONTAP versions, the management-server operating system, plus the ONTAP cluster UUID. Example:
     * {@code {"message":"CloudStack connected to ONTAP cluster","cloudstackVersion":"4.23.0.0",
     * "os":"Linux 5.15.0-91-generic (amd64)","ontapVersion":"9.17.1","clusterUuid":"..."}}
     */
    private String buildHeartbeatDescription(String cloudStackVersion, String ontapVersion,
            String clusterUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "CloudStack connected to ONTAP cluster");
        payload.put("cloudstackVersion", defaultUnknown(cloudStackVersion));
        payload.put("os", getOperatingSystem());
        payload.put("ontapVersion", defaultUnknown(ontapVersion));
        payload.put("clusterUuid", defaultUnknown(clusterUuid));
        return toJson(payload);
    }

    /**
     * Builds the minimal pool->backing-volume description (NFS and iSCSI) as a JSON object,
     * reading the values persisted in storage_pool_details at pool creation time and including the
     * ONTAP cluster UUID plus pool usage (VM count, disk count, total disk size in bytes). Example:
     * {@code {"message":"CloudStack storage pool backed by ONTAP volume","poolId":1,
     * "poolUuid":"...","poolName":"...","protocol":"nfs","clusterUuid":"...","svm":"...",
     * "ontapVolumeUuid":"...","vmCount":12,"diskCount":30,"totalDiskSizeBytes":322122547200}}
     */
    private String buildPoolDescription(StoragePoolVO pool, Map<String, String> details,
            String clusterUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "CloudStack storage pool backed by ONTAP volume");
        payload.put("poolId", pool.getId());
        payload.put("poolUuid", defaultUnknown(pool.getUuid()));
        payload.put("poolName", defaultUnknown(pool.getName()));
        payload.put("protocol", defaultUnknown(details.get(OntapStorageConstants.PROTOCOL)));
        payload.put("clusterUuid", defaultUnknown(clusterUuid));
        payload.put("svm", defaultUnknown(details.get(OntapStorageConstants.SVM_NAME)));
        payload.put("ontapVolumeUuid", defaultUnknown(details.get(OntapStorageConstants.VOLUME_UUID)));
        addPoolUsage(pool, payload);
        return toJson(payload);
    }

    /**
     * Computes pool usage from CloudStack's volume records and adds it to the payload:
     * <ul>
     *     <li>{@code vmCount} - number of distinct VMs with at least one disk on the pool</li>
     *     <li>{@code diskCount} - number of (non-destroyed) CloudStack volumes on the pool</li>
     *     <li>{@code totalDiskSizeBytes} - sum of those volumes' sizes, in bytes</li>
     * </ul>
     * Best-effort: any failure leaves the usage fields out and never breaks telemetry.
     */
    private void addPoolUsage(StoragePoolVO pool, Map<String, Object> payload) {
        try {
            List<VolumeVO> volumes = volumeDao.findNonDestroyedVolumesByPoolId(pool.getId());
            long diskCount = volumes.size();
            long totalDiskSizeBytes = volumes.stream()
                    .mapToLong(v -> v.getSize() != null ? v.getSize() : 0L).sum();
            long vmCount = volumes.stream().map(VolumeVO::getInstanceId)
                    .filter(Objects::nonNull).distinct().count();
            payload.put("vmCount", vmCount);
            payload.put("diskCount", diskCount);
            payload.put("totalDiskSizeBytes", totalDiskSizeBytes);
        } catch (Exception e) {
            logger.warn("ONTAP ASUP: failed to compute usage for pool [{}]: {}", pool.getId(), e.getMessage());
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
