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
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.ManagementService;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Cluster;
import org.apache.cloudstack.storage.feign.model.EmsApplicationLog;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.utils.OntapConfigurationManager;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OntapAsupManagerTest {

    // ── DAOs ──────────────────────────────────────────────────────────────────
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private StoragePoolDetailsDao storagePoolDetailsDao;
    @Mock private VolumeDao volumeDao;
    @Mock private SnapshotDao snapshotDao;
    @Mock private VMSnapshotDao vmSnapshotDao;
    @Mock private ManagementService managementService;
    @Mock private ManagementServerHostDao managementServerHostDao;

    @InjectMocks
    private OntapAsupManager asupManager;

    // ── Common fixtures ──────────────────────────────────────────────────────
    private StoragePoolVO pool;
    private Map<String, String> poolDetails;
    private StorageStrategy mockStrategy;
    private Cluster mockCluster;

    @BeforeEach
    void setUp() {
        pool = mock(StoragePoolVO.class);
        lenient().when(pool.getId()).thenReturn(1L);
        lenient().when(pool.getName()).thenReturn("ontap-pool-1");

        poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.STORAGE_IP, "192.168.1.10");
        poolDetails.put(OntapStorageConstants.PROTOCOL, "NFS3");
        poolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
        poolDetails.put(OntapStorageConstants.VOLUME_UUID, "fv-uuid-1");
        poolDetails.put(OntapStorageConstants.VOLUME_NAME, "fv-name-1");

        mockStrategy = mock(StorageStrategy.class);

        mockCluster = mock(Cluster.class);
        lenient().when(mockCluster.getUuid()).thenReturn("cluster-uuid-1");
        lenient().when(mockCluster.getName()).thenReturn("ontap-cluster-1");
        lenient().when(managementService.getVersion()).thenReturn("4.23.0.0-SNAPSHOT");
        lenient().when(managementServerHostDao.listAll()).thenReturn(Collections.emptyList());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // pushAsupTelemetry – no pools
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void pushAsupTelemetry_noOntapPools_sendsNoMessages() {
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME))
                .thenReturn(Collections.emptyList());

        asupManager.pushAsupTelemetry();

        verify(mockStrategy, never()).sendAsupMessage(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Message count / event-id routing
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void pushAsupForStoragePool_newCluster_sendsHeartbeatThenPoolMessage() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        // heartbeat (event-id 0) + pool (event-id 1) = 2 messages
        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());

        List<EmsApplicationLog> msgs = cap.getAllValues();
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT,    msgs.get(0).getEventId());
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_STORAGE_POOL, msgs.get(1).getEventId());
        assertTrue(msgs.get(0).getEventDescription().contains("\"snapshot_across_pool\":true"),
                msgs.get(0).getEventDescription());
        assertTrue(msgs.get(0).getEventDescription().contains("\"managementServerCount\":0"),
                msgs.get(0).getEventDescription());
    }

    @Test
    void pushAsupForStoragePool_clusterAlreadyHeartbeated_sendsOnlyPoolMessage() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        HashSet<String> clustersHeartbeated = new HashSet<>();
        clustersHeartbeated.add("cluster-uuid-1"); // already sent this cycle

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, clustersHeartbeated);
        }

        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(1)).sendAsupMessage(cap.capture());
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_STORAGE_POOL, cap.getValue().getEventId());
    }

    @Test
    void pushAsupForStoragePool_strategyThrows_doesNotPropagateException() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenThrow(new RuntimeException("connection refused"));
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        verify(mockStrategy, never()).sendAsupMessage(any());
    }

    @Test
    void pushAsupForStoragePool_poolDetailsEmpty_skipsPool() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Collections.emptyMap());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenThrow(new RuntimeException("no details"));
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        verify(mockStrategy, never()).sendAsupMessage(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pool message — content verification
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void poolMessage_containsPoolNameClusterUuidAndSnapshotKeys() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("ontap-pool-1"),          "should contain pool name");
        assertTrue(desc.contains("cluster-uuid-1"),        "should contain cluster UUID");
        assertTrue(desc.contains("volumeSnapshotCount"), "should contain volumeSnapshotCount");
        assertTrue(desc.contains("vmSnapshotCount"),       "should contain vmSnapshotCount");
        assertTrue(desc.contains("\"multiPrimaryStoragePoolVm\":false"), "desc=" + desc);
    }

    @Test
    void poolMessage_volumeSnapshots_zeroWhenNoVolumes() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"volumeSnapshotCount\":0"), "desc=" + desc);
        assertTrue(desc.contains("\"vmSnapshotCount\":0"),       "desc=" + desc);
    }

    @Test
    void poolMessage_multiPrimaryStoragePoolVm_trueWhenDaoReportsSpan() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());
        when(volumeDao.hasMultiPrimaryStoragePoolVm(1L)).thenReturn(true);

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"multiPrimaryStoragePoolVm\":true"), "desc=" + desc);
    }

    @Test
    void poolMessage_volumeSnapshots_countExcludesDestroyed() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");

        // instanceId=null → no VM IDs → vmSnapshotDao never called
        VolumeVO vol = mockVolume(10L, null, 10_737_418_240L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.singletonList(vol));

        // 2 active + 1 Destroyed → only 2 counted
        SnapshotVO s1 = makeSnapshot(1L, 10L, Snapshot.State.BackedUp);
        SnapshotVO s2 = makeSnapshot(2L, 10L, Snapshot.State.Creating);
        SnapshotVO s3 = makeSnapshot(3L, 10L, Snapshot.State.Destroyed);
        when(snapshotDao.searchByVolumes(anyList())).thenReturn(Arrays.asList(s1, s2, s3));

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"volumeSnapshotCount\":2"), "Destroyed must be excluded; desc=" + desc);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pool message — VM-snapshot fields (vmSnapshotCount)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void poolMessage_vmSnapshots_countsActiveSnapshotsAcrossDistinctVMs() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");

        VolumeVO vol1 = mockVolume(10L, 100L, 10_737_418_240L); // vm 100
        VolumeVO vol2 = mockVolume(20L, 200L, 10_737_418_240L); // vm 200
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull()))
                .thenReturn(Arrays.asList(vol1, vol2));
        when(snapshotDao.searchByVolumes(anyList())).thenReturn(Collections.emptyList());

        // 2 active VM snapshots; 1 is Expunging (should be excluded)
        VMSnapshotVO vmSnap1   = makeVmSnapshot(VMSnapshot.State.Ready,     null);
        VMSnapshotVO vmSnap2   = makeVmSnapshot(VMSnapshot.State.Ready,     null);
        VMSnapshotVO vmSnapExp = makeVmSnapshot(VMSnapshot.State.Expunging, null);
        when(vmSnapshotDao.searchByVms(anyList())).thenReturn(Arrays.asList(vmSnap1, vmSnap2, vmSnapExp));

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"vmSnapshotCount\":2"), "Expunging must be excluded; desc=" + desc);
    }

    @Test
    void poolMessage_vmSnapshots_removedSnapshotsExcluded() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");

        VolumeVO vol = mockVolume(10L, 100L, 1_073_741_824L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.singletonList(vol));
        when(snapshotDao.searchByVolumes(anyList())).thenReturn(Collections.emptyList());

        VMSnapshotVO active  = makeVmSnapshot(VMSnapshot.State.Ready, null);
        VMSnapshotVO deleted = makeVmSnapshot(VMSnapshot.State.Ready, new java.util.Date()); // removed
        when(vmSnapshotDao.searchByVms(anyList())).thenReturn(Arrays.asList(active, deleted));

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"vmSnapshotCount\":1"), "removed snapshots must be excluded; desc=" + desc);
    }

    @Test
    void poolMessage_vmSnapshots_zeroWhenNoVmIdsOnPool() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");

        // instanceId=null → detached data disk → vmSnapshotDao must NOT be called
        VolumeVO vol = mockVolume(10L, null, 1_073_741_824L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.singletonList(vol));

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"vmSnapshotCount\":0"), "desc=" + desc);
        verify(vmSnapshotDao, never()).searchByVms(anyList());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Best-effort: DAO failures must never suppress the pool message
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void poolMessage_snapshotDaoThrows_poolMessageStillSent() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull()))
                .thenThrow(new RuntimeException("DB error"));

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        // heartbeat + pool both sent even when DAO fails
        verify(mockStrategy, times(2)).sendAsupMessage(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Multi-pool: same cluster → single heartbeat
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void twoPoolsSameCluster_singleHeartbeat() {
        StoragePoolVO pool2 = mock(StoragePoolVO.class);
        when(pool2.getId()).thenReturn(2L);
        when(pool2.getName()).thenReturn("ontap-pool-2");

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(storagePoolDetailsDao.listDetailsKeyPairs(2L)).thenReturn(new HashMap<>(poolDetails));
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(anyLong(), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);

            HashSet<String> clustersHeartbeated = new HashSet<>();
            asupManager.pushAsupForStoragePool(pool,  clustersHeartbeated);
            asupManager.pushAsupForStoragePool(pool2, clustersHeartbeated);
        }

        // 1 heartbeat + 2 pool messages = 3 total
        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(3)).sendAsupMessage(cap.capture());

        long heartbeats = cap.getAllValues().stream()
                .filter(m -> OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT.equals(m.getEventId()))
                .count();
        assertEquals(1, heartbeats, "exactly 1 heartbeat for two pools sharing a cluster");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Common EMS envelope fields
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void allMessages_haveCorrectEnvelopeFields() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashSet<>());
        }

        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());

        for (EmsApplicationLog msg : cap.getAllValues()) {
            assertEquals(OntapStorageConstants.ASUP_EVENT_SOURCE, msg.getEventSource());
            assertEquals(OntapStorageConstants.ASUP_CATEGORY,     msg.getCategory());
            assertEquals(OntapStorageConstants.ASUP_SEVERITY,     msg.getSeverity());
            assertFalse(msg.getAutosupportRequired(), "autosupport_required should be false");
            assertEquals(asupManager.getComputerName(), msg.getComputerName());
            assertEquals(asupManager.getCloudStackVersion(), msg.getAppVersion());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Config defaults
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void asupIntervalSeconds_defaultIsProductionValue() {
        assertEquals(String.valueOf(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS),
                OntapConfigurationManager.AsupIntervalSeconds.defaultValue());
    }

    @Test
    void asupIntervalSeconds_descriptionIncludesAllowedRange() {
        String description = OntapConfigurationManager.AsupIntervalSeconds.description();
        assertTrue(description.contains(String.valueOf(OntapStorageConstants.ASUP_MIN_INTERVAL_SECONDS)));
        assertTrue(description.contains(String.valueOf(OntapStorageConstants.ASUP_MAX_INTERVAL_SECONDS)));
    }

    @Test
    void asupEnabled_defaultIsTrue() {
        assertEquals("true", OntapConfigurationManager.AsupEnabled.defaultValue());
    }

    @Test
    void validateAsupInterval_acceptsMinMaxAndDefault() {
        OntapConfigurationManager.AsupIntervalSeconds.validateValue(String.valueOf(OntapStorageConstants.ASUP_MIN_INTERVAL_SECONDS));
        OntapConfigurationManager.AsupIntervalSeconds.validateValue(String.valueOf(OntapStorageConstants.ASUP_MAX_INTERVAL_SECONDS));
        OntapConfigurationManager.AsupIntervalSeconds.validateValue(String.valueOf(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS));
    }

    @Test
    void validateAsupInterval_rejectsOutOfRangeAndNonInteger() {
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalSeconds.validateValue("59"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalSeconds.validateValue("86401"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalSeconds.validateValue("0"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalSeconds.validateValue("abc"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalSeconds.validateValue(""));
    }

    @Test
    void getAsupIntervalSeconds_fallsBackOutsideRange() {
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS,
                asupManager.getAsupIntervalSeconds(null));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS,
                asupManager.getAsupIntervalSeconds(0));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS,
                asupManager.getAsupIntervalSeconds(59));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_SECONDS,
                asupManager.getAsupIntervalSeconds(86401));
        assertEquals(OntapStorageConstants.ASUP_MIN_INTERVAL_SECONDS,
                asupManager.getAsupIntervalSeconds(OntapStorageConstants.ASUP_MIN_INTERVAL_SECONDS));
        assertEquals(OntapStorageConstants.ASUP_MAX_INTERVAL_SECONDS,
                asupManager.getAsupIntervalSeconds(OntapStorageConstants.ASUP_MAX_INTERVAL_SECONDS));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OntapAsupPollTask – self-throttle (interval change takes effect without restart)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void pollTask_getDelay_returnsFixedCheckInterval() {
        OntapAsupManager.OntapAsupPollTask task = asupManager.new OntapAsupPollTask();
        assertEquals(OntapAsupManager.ASUP_POLL_CHECK_INTERVAL_MS, task.getDelay());
    }

    @Test
    void pollTask_whenDisabled_doesNotAdvanceLastPushTime() throws Exception {
        Instant original = Instant.EPOCH;
        asupManager.lastPushTime = original;
        ConfigDepotImpl previousDepot = getConfigDepot();
        try {
            ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
            when(depot.getConfigStringValue(eq(OntapStorageConstants.ASUP_ENABLED_CONFIG_KEY),
                    eq(ConfigKey.Scope.Global), isNull())).thenReturn("false");
            setConfigDepot(depot);
            OntapAsupManager.OntapAsupPollTask task = asupManager.new OntapAsupPollTask();
            task.run();
        } finally {
            setConfigDepot(previousDepot);
        }
        assertEquals(original, asupManager.lastPushTime);
        verify(storagePoolDao, never()).findPoolsByProvider(any());
    }

    @Test
    void pollTask_skipsWhenIntervalNotElapsed() {
        asupManager.lastPushTime = Instant.now(); // just pushed
        OntapAsupManager.OntapAsupPollTask task = asupManager.new OntapAsupPollTask();
        task.run();
        verify(storagePoolDao, never()).findPoolsByProvider(any());
    }

    @Test
    void pollTask_pushesWhenIntervalElapsed() {
        asupManager.lastPushTime = Instant.EPOCH; // never pushed
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME))
                .thenReturn(Collections.emptyList());
        OntapAsupManager.OntapAsupPollTask task = asupManager.new OntapAsupPollTask();
        task.run();
        verify(storagePoolDao).findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getCloudStackVersion_returnsManagementServiceVersion() {
        assertEquals("4.23.0.0-SNAPSHOT", asupManager.getCloudStackVersion());
    }

    @Test
    void getCloudStackVersion_blank_returnsUnknown() {
        when(managementService.getVersion()).thenReturn("  ");
        assertEquals(OntapStorageConstants.ASUP_UNKNOWN, asupManager.getCloudStackVersion());
    }

    @Test
    void getManagementServerCount_returnsRegisteredHostCount() {
        when(managementServerHostDao.listAll()).thenReturn(Arrays.asList(
                mock(ManagementServerHostVO.class), mock(ManagementServerHostVO.class)));
        assertEquals(2, asupManager.getManagementServerCount());
    }

    @Test
    void getComputerName_returnsNonEmpty() {
        String host = asupManager.getComputerName();
        assertNotNull(host);
        assertFalse(host.isEmpty());
    }

    @Test
    void getOperatingSystem_returnsNonEmpty() {
        String os = asupManager.getOperatingSystem();
        assertNotNull(os);
        assertFalse(os.isEmpty());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Captures and returns the event-id 1 (pool) message description.
     * Expects exactly 2 messages to have been sent (heartbeat + pool).
     */
    private String capturePoolMessage() {
        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());
        EmsApplicationLog poolMsg = cap.getAllValues().get(1);
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_STORAGE_POOL, poolMsg.getEventId());
        String desc = poolMsg.getEventDescription();
        assertNotNull(desc);
        return desc;
    }

    /**
     * Creates a mock VolumeVO with state=Ready so that CS_VOLUME_STATES filter includes it
     * and getSize() is exercised (avoiding UnnecessaryStubbingException in strict mode).
     */
    private VolumeVO mockVolume(long id, Long instanceId, long size) {
        VolumeVO vol = mock(VolumeVO.class);
        when(vol.getId()).thenReturn(id);
        when(vol.getInstanceId()).thenReturn(instanceId);
        when(vol.getSize()).thenReturn(size);
        when(vol.getState()).thenReturn(Volume.State.Ready);
        return vol;
    }

    /** Creates a mock SnapshotVO with the given id, volumeId and state. */
    private SnapshotVO makeSnapshot(long id, long volumeId, Snapshot.State state) {
        SnapshotVO snap = mock(SnapshotVO.class);
        when(snap.getState()).thenReturn(state);
        return snap;
    }

    private static ConfigDepotImpl getConfigDepot() throws Exception {
        Field field = ConfigKey.class.getDeclaredField("s_depot");
        field.setAccessible(true);
        return (ConfigDepotImpl) field.get(null);
    }

    private static void setConfigDepot(ConfigDepotImpl depot) throws Exception {
        Field field = ConfigKey.class.getDeclaredField("s_depot");
        field.setAccessible(true);
        field.set(null, depot);
    }

    /** Creates a mock VMSnapshotVO with the given state and removed timestamp. */
    private VMSnapshotVO makeVmSnapshot(VMSnapshot.State state, java.util.Date removed) {
        VMSnapshotVO vmSnap = mock(VMSnapshotVO.class);
        when(vmSnap.getState()).thenReturn(state);
        lenient().when(vmSnap.getRemoved()).thenReturn(removed);
        return vmSnap;
    }
}
