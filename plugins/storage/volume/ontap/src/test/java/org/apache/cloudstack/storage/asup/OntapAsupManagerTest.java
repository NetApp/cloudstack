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
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.ManagementService;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.DbUtil;
import com.cloud.utils.net.NetUtils;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.MessageSubscriber;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
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
    @Mock private MessageBus messageBus;

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
        lenient().when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);

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
        lenient().when(mockCluster.getModel()).thenReturn("AFF-A400");
        lenient().when(mockCluster.getPlatformType())
                .thenReturn(OntapStorageConstants.ASUP_PLATFORM_TYPE_PERFORMANCE);
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        // heartbeat (event-id 0) + pool (event-id 1) = 2 messages
        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());

        List<EmsApplicationLog> msgs = cap.getAllValues();
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT,    msgs.get(0).getEventId());
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_STORAGE_POOL, msgs.get(1).getEventId());
        assertTrue(msgs.get(0).getEventDescription().contains("\"managementServerCount\":0"),
                msgs.get(0).getEventDescription());
        assertTrue(msgs.get(0).getEventDescription().contains("\"ontapClusterModel\":\"AFF-A400\""),
                msgs.get(0).getEventDescription());
        assertTrue(msgs.get(0).getEventDescription().contains("\"ontapPlatformType\":\"performance\""),
                msgs.get(0).getEventDescription());
    }

    @Test
    void pushAsupForStoragePool_cachedStorageIp_sendsOnlyPoolMessage() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        Map<String, OntapAsupManager.AsupClusterClient> clientsByStorageIp = new HashMap<>();
        clientsByStorageIp.put("192.168.1.10", new OntapAsupManager.AsupClusterClient(mockStrategy, mockCluster));

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            asupManager.pushAsupForStoragePool(pool, clientsByStorageIp);
            u.verify(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any()), never());
        }

        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(1)).sendAsupMessage(cap.capture());
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_STORAGE_POOL, cap.getValue().getEventId());
        verify(mockStrategy, never()).getClusterInfo();
    }

    @Test
    void heartbeat_clusterModelUnknownWhenNodesGetReturnsNull() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(mockCluster.getModel()).thenReturn(null);
        when(mockCluster.getPlatformType()).thenReturn(null);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());
        String heartbeat = cap.getAllValues().get(0).getEventDescription();
        assertTrue(heartbeat.contains("\"ontapClusterModel\":\"unknown\""), heartbeat);
        assertTrue(heartbeat.contains("\"ontapPlatformType\":\"unknown\""), heartbeat);
    }

    @Test
    void pushAsupForStoragePool_strategyThrows_doesNotPropagateException() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any()))
                    .thenThrow(new RuntimeException("connection refused"));
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        verify(mockStrategy, never()).sendAsupMessage(any());
    }

    @Test
    void pushAsupForStoragePool_poolDetailsEmpty_skipsPool() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Collections.emptyMap());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any()))
                    .thenThrow(new RuntimeException("no details"));
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("ontap-pool-1"),          "should contain pool name");
        assertTrue(desc.contains("\"poolStatus\":\"Up\""), "should contain pool status");
        assertTrue(desc.contains("cluster-uuid-1"),        "should contain cluster UUID");
        assertTrue(desc.contains("volumeSnapshotCount"), "should contain volumeSnapshotCount");
        assertTrue(desc.contains("vmSnapshotCount"),       "should contain vmSnapshotCount");
        assertTrue(desc.contains("\"multiPrimaryStoragePoolVm\":false"), "desc=" + desc);
    }

    @Test
    void poolMessage_includesMaintenanceStatus() {
        when(pool.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"poolStatus\":\"Maintenance\""), "desc=" + desc);
    }

    @Test
    void poolMessage_volumeSnapshots_zeroWhenNoVolumes() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"volumeSnapshotCount\":2"), "Destroyed must be excluded; desc=" + desc);
        verify(volumeDao, times(1)).findNonDestroyedVolumesByPoolId(eq(1L), isNull());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"vmSnapshotCount\":0"), "desc=" + desc);
        verify(vmSnapshotDao, never()).searchByVms(anyList());
    }

    @Test
    void poolMessage_vmSnapshots_countedOnlyOnRootDiskPool() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");

        // Data disk of a VM whose ROOT lives on another pool — must not count that VM snapshot.
        VolumeVO dataDisk = mockVolume(20L, 100L, 1_073_741_824L, Volume.Type.DATADISK);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull()))
                .thenReturn(Collections.singletonList(dataDisk));

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);

            Map<String, OntapAsupManager.AsupClusterClient> clientsByStorageIp = new HashMap<>();
            asupManager.pushAsupForStoragePool(pool, clientsByStorageIp);
            asupManager.pushAsupForStoragePool(pool2, clientsByStorageIp);

            u.verify(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any()), times(1));
        }

        verify(mockStrategy, times(1)).getClusterInfo();
        // 1 heartbeat + 2 pool messages = 3 total
        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(3)).sendAsupMessage(cap.capture());

        long heartbeats = cap.getAllValues().stream()
                .filter(m -> OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT.equals(m.getEventId()))
                .count();
        assertEquals(1, heartbeats, "exactly 1 heartbeat for two pools sharing a cluster");
    }

    @Test
    void twoPoolsDifferentStorageIp_resolvesStrategyPerCluster() {
        StoragePoolVO pool2 = mock(StoragePoolVO.class);
        when(pool2.getId()).thenReturn(2L);
        when(pool2.getName()).thenReturn("ontap-pool-2");

        Map<String, String> otherCluster = new HashMap<>(poolDetails);
        otherCluster.put(OntapStorageConstants.STORAGE_IP, "192.168.1.20");

        Cluster cluster2 = mock(Cluster.class);
        when(cluster2.getUuid()).thenReturn("cluster-uuid-2");

        StorageStrategy strategy2 = mock(StorageStrategy.class);
        when(strategy2.getClusterInfo()).thenReturn(cluster2);
        when(strategy2.getClusterVersion(cluster2)).thenReturn("9.16.1");

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(storagePoolDetailsDao.listDetailsKeyPairs(2L)).thenReturn(otherCluster);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(anyLong(), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(poolDetails)).thenReturn(mockStrategy);
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(otherCluster)).thenReturn(strategy2);

            Map<String, OntapAsupManager.AsupClusterClient> clientsByStorageIp = new HashMap<>();
            asupManager.pushAsupForStoragePool(pool, clientsByStorageIp);
            asupManager.pushAsupForStoragePool(pool2, clientsByStorageIp);

            u.verify(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any()), times(2));
        }

        verify(mockStrategy, times(1)).getClusterInfo();
        verify(strategy2, times(1)).getClusterInfo();
        verify(mockStrategy, times(2)).sendAsupMessage(any());
        verify(strategy2, times(2)).sendAsupMessage(any());
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
            u.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
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
    void asupIntervalHours_defaultIsProductionValue() {
        assertEquals(4, OntapStorageConstants.ASUP_MIN_INTERVAL_HOURS);
        assertEquals(24, OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS);
        assertEquals(String.valueOf(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS),
                OntapConfigurationManager.AsupIntervalHours.defaultValue());
    }

    @Test
    void asupIntervalHours_keyIsAutosupportInterval() {
        assertEquals(OntapStorageConstants.ASUP_INTERVAL_CONFIG_KEY,
                OntapConfigurationManager.AsupIntervalHours.key());
        assertEquals("ontap.autosupport.interval", OntapConfigurationManager.AsupIntervalHours.key());
    }

    @Test
    void asupIntervalHours_descriptionIncludesAllowedRange() {
        String description = OntapConfigurationManager.AsupIntervalHours.description();
        assertTrue(description.contains(String.valueOf(OntapStorageConstants.ASUP_MIN_INTERVAL_HOURS)));
        assertTrue(description.contains(String.valueOf(OntapStorageConstants.ASUP_MAX_INTERVAL_HOURS)));
        assertTrue(description.contains(String.valueOf(OntapStorageConstants.ASUP_DISABLED_INTERVAL_HOURS)));
        assertTrue(description.contains("1 week"), description);
    }

    @Test
    void validateAsupInterval_acceptsDisabledMinMaxAndDefault() {
        OntapConfigurationManager.AsupIntervalHours.validateValue(String.valueOf(OntapStorageConstants.ASUP_DISABLED_INTERVAL_HOURS));
        OntapConfigurationManager.AsupIntervalHours.validateValue(String.valueOf(OntapStorageConstants.ASUP_MIN_INTERVAL_HOURS));
        OntapConfigurationManager.AsupIntervalHours.validateValue(String.valueOf(OntapStorageConstants.ASUP_MAX_INTERVAL_HOURS));
        OntapConfigurationManager.AsupIntervalHours.validateValue(String.valueOf(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS));
    }

    @Test
    void validateAsupInterval_rejectsOutOfRangeAndNonInteger() {
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalHours.validateValue("-1"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalHours.validateValue("1"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalHours.validateValue("169"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalHours.validateValue("abc"));
        assertThrows(InvalidParameterValueException.class, () -> OntapConfigurationManager.AsupIntervalHours.validateValue(""));
    }

    @Test
    void getAsupIntervalHours_fallsBackOutsideRange() {
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(null));
        assertEquals(OntapStorageConstants.ASUP_DISABLED_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(OntapStorageConstants.ASUP_DISABLED_INTERVAL_HOURS));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(1));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(2));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(3));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(-1));
        assertEquals(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(169));
        assertEquals(OntapStorageConstants.ASUP_MIN_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(OntapStorageConstants.ASUP_MIN_INTERVAL_HOURS));
        assertEquals(OntapStorageConstants.ASUP_MAX_INTERVAL_HOURS,
                asupManager.getAsupIntervalHours(OntapStorageConstants.ASUP_MAX_INTERVAL_HOURS));
        assertEquals(25, asupManager.getAsupIntervalHours(25));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OntapAsupTask / schedule
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void millisUntilNextPush_zeroWhenOverdue() {
        asupManager.lastPushTime = Instant.EPOCH; // never pushed
        assertEquals(0L, asupManager.millisUntilNextPush());
    }

    @Test
    void millisUntilNextPush_remainderOfConfiguredInterval() {
        asupManager.lastPushTime = Instant.now();
        long remaining = asupManager.millisUntilNextPush();
        long configured = Duration.ofHours(OntapStorageConstants.ASUP_DEFAULT_INTERVAL_HOURS).toMillis();
        assertTrue(remaining > configured - 5000L && remaining <= configured,
                "remaining=" + remaining);
    }

    @Test
    void asupTask_stampsLastPushTimeAndPushes() {
        asupManager.lastPushTime = Instant.EPOCH; // never pushed
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME))
                .thenReturn(Collections.emptyList());
        Instant before = Instant.now();
        OntapAsupManager.OntapAsupTask task = asupManager.new OntapAsupTask();
        task.run();
        verify(storagePoolDao).findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME);
        assertFalse(asupManager.lastPushTime.equals(Instant.EPOCH));
        assertFalse(asupManager.lastPushTime.isBefore(before));
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

    @Test
    void getOperatingSystem_blankNameVersionAndArch() {
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", " ");
            assertEquals(OntapStorageConstants.ASUP_UNKNOWN, asupManager.getOperatingSystem());

            System.setProperty("os.name", "TestOS");
            System.setProperty("os.version", " ");
            System.setProperty("os.arch", " ");
            assertEquals("TestOS", asupManager.getOperatingSystem());

            System.setProperty("os.version", "1.2");
            assertEquals("TestOS 1.2", asupManager.getOperatingSystem());

            System.setProperty("os.arch", "x64");
            assertEquals("TestOS 1.2 (x64)", asupManager.getOperatingSystem());
        } finally {
            restoreSystemProperty("os.name", osName);
            restoreSystemProperty("os.version", osVersion);
            restoreSystemProperty("os.arch", osArch);
        }
    }

    @Test
    void getComputerName_blankOrNull_returnsUnknown() {
        try (MockedStatic<NetUtils> net = mockStatic(NetUtils.class)) {
            net.when(NetUtils::getCanonicalHostName).thenReturn(" ");
            assertEquals(OntapStorageConstants.ASUP_UNKNOWN, asupManager.getComputerName());
            net.when(NetUtils::getCanonicalHostName).thenReturn(null);
            assertEquals(OntapStorageConstants.ASUP_UNKNOWN, asupManager.getComputerName());
            net.when(NetUtils::getCanonicalHostName).thenReturn("mgmt-1");
            assertEquals("mgmt-1", asupManager.getComputerName());
        }
    }

    @Test
    void configureStartAndConfigEdit_booksCancelsAndStopsScheduler() throws Exception {
        ArgumentCaptor<MessageSubscriber> subscriberCaptor = ArgumentCaptor.forClass(MessageSubscriber.class);
        assertTrue(asupManager.configure("ontap-asup", Collections.emptyMap()));
        verify(messageBus).subscribe(eq(EventTypes.EVENT_CONFIGURATION_VALUE_EDIT), subscriberCaptor.capture());
        MessageSubscriber subscriber = subscriberCaptor.getValue();

        CountDownLatch started = new CountDownLatch(1);
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME)).thenAnswer(invocation -> {
            started.countDown();
            return Collections.emptyList();
        });
        assertTrue(asupManager.start());
        assertTrue(started.await(5, TimeUnit.SECONDS));

        subscriber.onPublishMessage("addr", EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, "not-a-ternary");
        subscriber.onPublishMessage("addr", EventTypes.EVENT_CONFIGURATION_VALUE_EDIT,
                new Ternary<>("some.other.key", ConfigKey.Scope.Global, null));
        subscriber.onPublishMessage("addr", EventTypes.EVENT_CONFIGURATION_VALUE_EDIT,
                new Ternary<>(OntapConfigurationManager.AsupIntervalHours.key(), ConfigKey.Scope.Global, 1L));

        assertTrue(asupManager.stop());
        subscriber.onPublishMessage("addr", EventTypes.EVENT_CONFIGURATION_VALUE_EDIT,
                new Ternary<>(OntapConfigurationManager.AsupIntervalHours.key(), ConfigKey.Scope.Global, 1L));
    }

    @Test
    void stop_beforeStart_returnsTrue() {
        assertTrue(asupManager.stop());
    }

    @Test
    void start_disabledInterval_doesNotSchedulePush() throws Exception {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        when(depot.getConfigStringValue(anyString(), any(), nullable(Long.class))).thenReturn("0");
        ConfigKey.init(depot);
        try {
            assertTrue(asupManager.start());
            Thread.sleep(150);
            verify(storagePoolDao, never()).findPoolsByProvider(anyString());
        } finally {
            asupManager.stop();
            ConfigKey.init(null);
        }
    }

    @Test
    void asupTask_unexpectedErrorIsSwallowedAndRescheduled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME)).thenAnswer(invocation -> {
            started.countDown();
            throw new RuntimeException("db down");
        });
        assertTrue(asupManager.start());
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
        } finally {
            asupManager.stop();
        }
    }

    @Test
    void pushAsupTelemetry_lockNotAcquired_skipsPools() {
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME))
                .thenReturn(Collections.singletonList(pool));
        try (MockedStatic<DbUtil> db = mockStatic(DbUtil.class)) {
            db.when(() -> DbUtil.getGlobalLock(anyString(), anyInt())).thenReturn(false);
            asupManager.pushAsupTelemetry();
            db.verify(() -> DbUtil.releaseGlobalLock(anyString()), never());
        }
        verify(storagePoolDetailsDao, never()).listDetailsKeyPairs(anyLong());
    }

    @Test
    void pushAsupTelemetry_lockAcquired_pushesEachPoolAndReleasesLock() {
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME))
                .thenReturn(Collections.singletonList(pool));
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Collections.emptyMap());
        try (MockedStatic<DbUtil> db = mockStatic(DbUtil.class)) {
            db.when(() -> DbUtil.getGlobalLock(anyString(), anyInt())).thenReturn(true);
            db.when(() -> DbUtil.releaseGlobalLock(anyString())).thenReturn(true);
            asupManager.pushAsupTelemetry();
            db.verify(() -> DbUtil.releaseGlobalLock(eq(OntapStorageConstants.ASUP_GLOBAL_LOCK_NAME)));
        }
        verify(storagePoolDetailsDao).listDetailsKeyPairs(1L);
    }

    @Test
    void pushAsupForStoragePool_nullDetails_skipsPool() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(null);
        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
            utils.verify(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any()), never());
        }
        verify(mockStrategy, never()).sendAsupMessage(any());
    }

    @Test
    void pushAsupForStoragePool_blankStorageIp_sendsHeartbeatWithoutCachingClient() {
        poolDetails.put(OntapStorageConstants.STORAGE_IP, " ");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());
        Map<String, OntapAsupManager.AsupClusterClient> clients = new HashMap<>();

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, clients);
        }

        assertTrue(clients.isEmpty());
        verify(mockStrategy, times(2)).sendAsupMessage(any());
        verify(mockStrategy).getClusterInfo();
    }

    @Test
    void heartbeat_nullCluster_reportsUnknownIdentity() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(null);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());
        String heartbeat = cap.getAllValues().get(0).getEventDescription();
        assertTrue(heartbeat.contains("\"ontapClusterModel\":\"unknown\""), heartbeat);
        assertTrue(heartbeat.contains("\"ontapPlatformType\":\"unknown\""), heartbeat);
        assertTrue(heartbeat.contains("\"clusterUuid\":\"unknown\""), heartbeat);
        assertTrue(heartbeat.contains("\"ontapVersion\":\"unknown\""), heartbeat);
        assertTrue(cap.getAllValues().get(1).getEventDescription().contains("\"clusterUuid\":\"unknown\""));
    }

    @Test
    void poolMessage_nullStatusAndBlankBackingFields_useUnknown() {
        when(pool.getStatus()).thenReturn(null);
        poolDetails.put(OntapStorageConstants.PROTOCOL, " ");
        poolDetails.put(OntapStorageConstants.SVM_NAME, "");
        poolDetails.remove(OntapStorageConstants.VOLUME_UUID);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"poolStatus\":\"unknown\""), desc);
        assertTrue(desc.contains("\"protocol\":\"unknown\""), desc);
        assertTrue(desc.contains("\"svm\":\"unknown\""), desc);
        assertTrue(desc.contains("\"ontapVolumeUuid\":\"unknown\""), desc);
        assertTrue(desc.contains("\"rootDiskCount\":0"), desc);
        assertTrue(desc.contains("\"volumeSnapshotCount\":0"), desc);
    }

    @Test
    void poolMessage_countsReadyRootAndDataAndSkipsAllocatedAndNullSize() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");

        VolumeVO root = mockVolume(10L, 100L, 100L);
        VolumeVO data = mockVolume(20L, 100L, 50L, Volume.Type.DATADISK);
        VolumeVO nullSize = mockVolume(40L, 200L, 0L);
        when(nullSize.getSize()).thenReturn(null);
        VolumeVO allocated = mock(VolumeVO.class);
        when(allocated.getId()).thenReturn(99L);
        when(allocated.getState()).thenReturn(Volume.State.Allocated);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull()))
                .thenReturn(Arrays.asList(root, data, nullSize, allocated));

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"rootDiskCount\":2"), desc);
        assertTrue(desc.contains("\"dataDiskCount\":1"), desc);
        assertTrue(desc.contains("\"totalLogicalSizeBytes\":150"), desc);
        assertTrue(desc.contains("\"volumeSnapshotCount\":0"), desc);
        assertTrue(desc.contains("\"vmSnapshotCount\":0"), desc);
    }

    @Test
    void poolMessage_snapshotListsNull_countAsZero() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        VolumeVO root = mockVolume(10L, 100L, 100L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.singletonList(root));
        when(snapshotDao.searchByVolumes(anyList())).thenReturn(null);
        when(vmSnapshotDao.searchByVms(anyList())).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"volumeSnapshotCount\":0"), desc);
        assertTrue(desc.contains("\"vmSnapshotCount\":0"), desc);
    }

    @Test
    void poolMessage_volumeSnapshotDaoThrows_omitsVolumeSnapshotCount() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        VolumeVO detached = mockVolume(10L, null, 100L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.singletonList(detached));
        when(snapshotDao.searchByVolumes(anyList())).thenThrow(new RuntimeException("snap db"));

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"vmSnapshotCount\":0"), desc);
        assertFalse(desc.contains("volumeSnapshotCount"), desc);
    }

    @Test
    void poolMessage_vmSnapshotDaoThrows_omitsVmSnapshotCount() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        VolumeVO root = mockVolume(10L, 100L, 100L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.singletonList(root));
        when(snapshotDao.searchByVolumes(anyList())).thenReturn(Collections.emptyList());
        when(vmSnapshotDao.searchByVms(anyList())).thenThrow(new RuntimeException("vm db"));

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"volumeSnapshotCount\":0"), desc);
        assertFalse(desc.contains("vmSnapshotCount"), desc);
    }

    @Test
    void poolMessage_usageComputationThrows_omitsDiskCounts() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        VolumeVO bad = mock(VolumeVO.class);
        when(bad.getState()).thenThrow(new RuntimeException("state"));
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.singletonList(bad));
        when(snapshotDao.searchByVolumes(anyList())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertFalse(desc.contains("rootDiskCount"), desc);
        assertTrue(desc.contains("\"vmSnapshotCount\":0"), desc);
        assertTrue(desc.contains("\"volumeSnapshotCount\":0"), desc);
    }

    @Test
    void poolMessage_multiPrimaryQueryThrows_omitsFlag() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());
        when(volumeDao.hasMultiPrimaryStoragePoolVm(1L)).thenThrow(new RuntimeException("span"));

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.resolveStrategyFromPoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, new HashMap<>());
        }

        String desc = capturePoolMessage();
        assertFalse(desc.contains("multiPrimaryStoragePoolVm"), desc);
        assertTrue(desc.contains("\"poolName\":\"ontap-pool-1\""), desc);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

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
        return mockVolume(id, instanceId, size, Volume.Type.ROOT);
    }

    private VolumeVO mockVolume(long id, Long instanceId, long size, Volume.Type type) {
        VolumeVO vol = mock(VolumeVO.class);
        when(vol.getId()).thenReturn(id);
        lenient().when(vol.getInstanceId()).thenReturn(instanceId);
        when(vol.getSize()).thenReturn(size);
        when(vol.getState()).thenReturn(Volume.State.Ready);
        when(vol.getVolumeType()).thenReturn(type);
        return vol;
    }

    /** Creates a mock SnapshotVO with the given id, volumeId and state. */
    private SnapshotVO makeSnapshot(long id, long volumeId, Snapshot.State state) {
        SnapshotVO snap = mock(SnapshotVO.class);
        when(snap.getState()).thenReturn(state);
        return snap;
    }

    /** Creates a mock VMSnapshotVO with the given state and removed timestamp. */
    private VMSnapshotVO makeVmSnapshot(VMSnapshot.State state, java.util.Date removed) {
        VMSnapshotVO vmSnap = mock(VMSnapshotVO.class);
        when(vmSnap.getState()).thenReturn(state);
        lenient().when(vmSnap.getRemoved()).thenReturn(removed);
        return vmSnap;
    }
}
