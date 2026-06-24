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

import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.net.NetUtils;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.poll.BackgroundPollManager;
import org.apache.cloudstack.poll.BackgroundPollTask;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.EmsApplicationLog;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.List;
import java.util.Map;

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

    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private StoragePoolDetailsDao storagePoolDetailsDao;
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
            for (StoragePoolVO pool : pools) {
                pushAsupForPool(pool, cloudStackVersion, computerName);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Pushes the heartbeat (event-id 0) and pool (event-id 1) ASUP messages for a single pool.
     * Best-effort: any failure is logged and swallowed.
     */
    protected void pushAsupForPool(StoragePoolVO pool, String cloudStackVersion, String computerName) {
        try {
            Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(pool.getId());
            if (details == null || details.isEmpty()) {
                logger.warn("ONTAP ASUP: storage pool [{}] has no details; skipping.", pool.getId());
                return;
            }

            StorageStrategy strategy = OntapStorageUtils.getStrategyByStoragePoolDetails(details);
            String ontapVersion = strategy.getClusterVersion();

            // event-id 0: CloudStack -> ONTAP cluster heartbeat (versions)
            String appVersion = buildAppVersion(cloudStackVersion, ontapVersion);
            EmsApplicationLog heartbeat = buildBaseMessage(computerName, appVersion);
            heartbeat.setEventId(OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT);
            heartbeat.setEventDescription(String.format(
                    "CloudStack connected to ONTAP cluster (CloudStack version=%s, ONTAP version=%s)",
                    cloudStackVersion, defaultUnknown(ontapVersion)));
            strategy.sendAsupMessage(heartbeat);

            // event-id 1: CloudStack storage pool -> backing ONTAP volume mapping
            EmsApplicationLog poolMessage = buildBaseMessage(computerName, appVersion);
            poolMessage.setEventId(OntapStorageConstants.ASUP_EVENT_ID_POOL);
            poolMessage.setEventDescription(buildPoolDescription(pool, details));
            strategy.sendAsupMessage(poolMessage);

            logger.debug("ONTAP ASUP: pushed telemetry for pool [{}] (ONTAP version={})",
                    pool.getId(), defaultUnknown(ontapVersion));
        } catch (Exception e) {
            // Best-effort telemetry; never propagate.
            logger.warn("ONTAP ASUP: failed to push telemetry for pool [{}]: {}", pool.getId(), e.getMessage());
        }
    }

    /**
     * Builds the minimal pool->backing-volume description (NFS and iSCSI), reading the values
     * persisted in storage_pool_details at pool creation time.
     */
    private String buildPoolDescription(StoragePoolVO pool, Map<String, String> details) {
        String protocol = defaultUnknown(details.get(OntapStorageConstants.PROTOCOL));
        String svmName = defaultUnknown(details.get(OntapStorageConstants.SVM_NAME));
        String ontapVolumeUuid = defaultUnknown(details.get(OntapStorageConstants.VOLUME_UUID));
        String ontapVolumeName = defaultUnknown(details.get(OntapStorageConstants.VOLUME_NAME));
        return String.format(
                "CloudStack storage pool backed by ONTAP volume "
                        + "{poolId=%d, poolUuid=%s, poolName=%s, protocol=%s, svm=%s, "
                        + "ontapVolumeUuid=%s, ontapVolumeName=%s}",
                pool.getId(), defaultUnknown(pool.getUuid()), defaultUnknown(pool.getName()),
                protocol, svmName, ontapVolumeUuid, ontapVolumeName);
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
