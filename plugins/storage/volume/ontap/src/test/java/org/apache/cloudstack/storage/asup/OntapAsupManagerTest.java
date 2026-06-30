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

import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Cluster;
import org.apache.cloudstack.storage.feign.model.EmsApplicationLog;
import org.apache.cloudstack.storage.service.StorageStrategy;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        when(pool.getId()).thenReturn(1L);
        when(pool.getName()).thenReturn("ontap-pool-1");

        poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.STORAGE_IP, "192.168.1.10");
        poolDetails.put(OntapStorageConstants.PROTOCOL, "NFS3");
        poolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
        poolDetails.put(OntapStorageConstants.VOLUME_UUID, "fv-uuid-1");
        poolDetails.put(OntapStorageConstants.VOLUME_NAME, "fv-name-1");

        mockStrategy = mock(StorageStrategy.class);

        mockCluster = mock(Cluster.class);
        when(mockCluster.getUuid()).thenReturn("cluster-uuid-1");
        when(mockCluster.getName()).thenReturn("ontap-cluster-1");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // pushAsupForAllStoragePools – no pools
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void pushAsupForAllPools_noOntapPools_sendsNoMessages() {
        when(storagePoolDao.findPoolsByProvider(OntapStorageConstants.ONTAP_PLUGIN_NAME))
                .thenReturn(Collections.emptyList());

        asupManager.pushAsupForAllStoragePools();

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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
        }

        // heartbeat (event-id 0) + pool (event-id 1) = 2 messages
        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());

        List<EmsApplicationLog> msgs = cap.getAllValues();
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_HEARTBEAT,    msgs.get(0).getEventId());
        assertEquals(OntapStorageConstants.ASUP_EVENT_ID_STORAGE_POOL, msgs.get(1).getEventId());
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", clustersHeartbeated);
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
        }

        verify(mockStrategy, never()).sendAsupMessage(any());
    }

    @Test
    void pushAsupForStoragePool_poolDetailsEmpty_skipsPool() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Collections.emptyMap());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenThrow(new RuntimeException("no details"));
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("ontap-pool-1"),          "should contain pool name");
        assertTrue(desc.contains("cluster-uuid-1"),        "should contain cluster UUID");
        assertTrue(desc.contains("csVolumeSnapshotCount"), "should contain csVolumeSnapshotCount");
        assertTrue(desc.contains("vmSnapshotCount"),       "should contain vmSnapshotCount");
    }

    @Test
    void poolMessage_volumeSnapshots_zeroWhenNoVolumes() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(mockStrategy.getClusterInfo()).thenReturn(mockCluster);
        when(mockStrategy.getClusterVersion(mockCluster)).thenReturn("9.17.1");
        when(volumeDao.findNonDestroyedVolumesByPoolId(eq(1L), isNull())).thenReturn(Collections.emptyList());

        try (MockedStatic<OntapStorageUtils> u = mockStatic(OntapStorageUtils.class)) {
            u.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(mockStrategy);
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"csVolumeSnapshotCount\":0"), "desc=" + desc);
        assertTrue(desc.contains("\"vmSnapshotCount\":0"),       "desc=" + desc);
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
        }

        String desc = capturePoolMessage();
        assertTrue(desc.contains("\"csVolumeSnapshotCount\":2"), "Destroyed must be excluded; desc=" + desc);
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
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
            asupManager.pushAsupForStoragePool(pool,  "4.20.0", "mgmt-host", clustersHeartbeated);
            asupManager.pushAsupForStoragePool(pool2, "4.20.0", "mgmt-host", clustersHeartbeated);
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
            asupManager.pushAsupForStoragePool(pool, "4.20.0", "mgmt-host", new HashSet<>());
        }

        ArgumentCaptor<EmsApplicationLog> cap = ArgumentCaptor.forClass(EmsApplicationLog.class);
        verify(mockStrategy, times(2)).sendAsupMessage(cap.capture());

        for (EmsApplicationLog msg : cap.getAllValues()) {
            assertEquals(OntapStorageConstants.ASUP_EVENT_SOURCE, msg.getEventSource());
            assertEquals(OntapStorageConstants.ASUP_CATEGORY,     msg.getCategory());
            assertEquals(OntapStorageConstants.ASUP_SEVERITY,     msg.getSeverity());
            assertFalse(msg.getAutosupportRequired(), "autosupport_required should be false");
            assertEquals("mgmt-host", msg.getComputerName());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Config defaults
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void asupIntervalSeconds_defaultIsProductionValue() {
        assertEquals("3600", OntapAsupManager.AsupIntervalSeconds.defaultValue());
    }

    @Test
    void asupEnabled_defaultIsTrue() {
        assertEquals("true", OntapAsupManager.AsupEnabled.defaultValue());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getCloudStackVersion_returnsNonBlank() {
        String v = asupManager.getCloudStackVersion();
        assertNotNull(v);
        assertFalse(v.isEmpty());
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
        when(snap.getId()).thenReturn(id);
        when(snap.getVolumeId()).thenReturn(volumeId);
        when(snap.getState()).thenReturn(state);
        return snap;
    }

    /** Creates a mock VMSnapshotVO with the given state and removed timestamp. */
    private VMSnapshotVO makeVmSnapshot(VMSnapshot.State state, java.util.Date removed) {
        VMSnapshotVO vmSnap = mock(VMSnapshotVO.class);
        when(vmSnap.getState()).thenReturn(state);
        when(vmSnap.getRemoved()).thenReturn(removed);
        return vmSnap;
    }
}
