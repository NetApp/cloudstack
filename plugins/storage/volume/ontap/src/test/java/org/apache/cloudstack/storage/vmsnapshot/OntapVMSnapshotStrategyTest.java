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
package org.apache.cloudstack.storage.vmsnapshot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VMSnapshotOptions;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.client.SnapshotFeignClient;
import org.apache.cloudstack.storage.feign.model.ConsistencyGroup;
import org.apache.cloudstack.storage.feign.model.ConsistencyGroupSnapshot;
import org.apache.cloudstack.storage.feign.model.FlexVolSnapshot;
import org.apache.cloudstack.storage.feign.model.Job;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FreezeThawVMAnswer;
import com.cloud.agent.api.FreezeThawVMCommand;
import com.cloud.agent.api.VMSnapshotTO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for {@link OntapVMSnapshotStrategy}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>canHandle(VMSnapshot) — various conditions for Allocated and non-Allocated states</li>
 *   <li>canHandle(Long vmId, Long rootPoolId, boolean snapshotMemory) — allocation-phase checks</li>
 *   <li>takeVMSnapshot — state transition failure scenarios</li>
 *   <li>Freeze/thaw behavior (freeze success/failure, thaw success/failure, agent errors)</li>
 *   <li>Quiesce behavior (honors user input; freeze/thaw only when quiesce=true)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntapVMSnapshotStrategyTest {

    private static final long VM_ID = 100L;
    private static final long HOST_ID = 10L;
    private static final long SNAPSHOT_ID = 200L;
    private static final long VOLUME_ID_1 = 301L;
    private static final long VOLUME_ID_2 = 302L;
    private static final long POOL_ID_1 = 401L;
    private static final long POOL_ID_2 = 402L;
    private static final long GUEST_OS_ID = 50L;
    private static final String VM_INSTANCE_NAME = "i-2-100-VM";
    private static final String VM_UUID = "vm-uuid-123";

    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private PrimaryDataStoreDao storagePool;
    @Mock
    private StoragePoolDetailsDao storagePoolDetailsDao;
    @Mock
    private VMSnapshotDetailsDao vmSnapshotDetailsDao;
    @Mock
    private VMSnapshotHelper vmSnapshotHelper;
    @Mock
    private VMSnapshotDao vmSnapshotDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private GuestOSDao guestOSDao;
    @Mock
    private VolumeDataFactory volumeDataFactory;
    @Mock
    private VolumeDetailsDao volumeDetailsDao;
    @Mock
    private StorageStrategy storageStrategy;
    @Mock
    private SnapshotFeignClient snapshotFeignClient;
    @Mock
    private org.apache.cloudstack.framework.config.dao.ConfigurationDao configurationDao;

    @Spy
    @InjectMocks
    private OntapVMSnapshotStrategy strategy;

    // ──────────────────────────────────────────────────────────────────────────
    // Helper: create common mocks
    // ──────────────────────────────────────────────────────────────────────────

    private UserVmVO createMockUserVm(Hypervisor.HypervisorType hypervisorType, VirtualMachine.State state) {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getHypervisorType()).thenReturn(hypervisorType);
        when(userVm.getState()).thenReturn(state);
        return userVm;
    }

    private VolumeVO createMockVolume(long volumeId, long poolId) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(volumeId);
        when(volume.getPoolId()).thenReturn(poolId);
        return volume;
    }

    private StoragePoolVO createOntapManagedPool(long poolId) {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(pool.getStorageProviderName()).thenReturn(OntapStorageConstants.ONTAP_PLUGIN_NAME);
        return pool;
    }

    private VMSnapshotVO createMockVmSnapshot(VMSnapshot.State state, VMSnapshot.Type type) {
        VMSnapshotVO vmSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(vmSnapshot.getVmId()).thenReturn(VM_ID);
        when(vmSnapshot.getState()).thenReturn(state);
        lenient().when(vmSnapshot.getType()).thenReturn(type);
        return vmSnapshot;
    }

    private void setupAllVolumesOnOntap() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeVO vol1 = createMockVolume(VOLUME_ID_1, POOL_ID_1);
        VolumeVO vol2 = createMockVolume(VOLUME_ID_2, POOL_ID_2);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Arrays.asList(vol1, vol2));

        StoragePoolVO pool1 = createOntapManagedPool(POOL_ID_1);
        StoragePoolVO pool2 = createOntapManagedPool(POOL_ID_2);
        when(storagePool.findById(POOL_ID_1)).thenReturn(pool1);
        when(storagePool.findById(POOL_ID_2)).thenReturn(pool2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: canHandle(VMSnapshot)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testCanHandle_AllocatedDiskType_AllVolumesOnOntap_ReturnsHighest() {
        setupAllVolumesOnOntap();
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.HIGHEST, result);
    }

    @Test
    void testCanHandle_AllocatedDiskAndMemoryType_ReturnsCantHandle() {
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.DiskAndMemory);
        when(vmSnapshot.getVmId()).thenReturn(VM_ID);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_VmNotFound_ReturnsCantHandle() {
        when(userVmDao.findById(VM_ID)).thenReturn(null);
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_VmxenHypervisor_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.XenServer, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_VmStopped_ReturnsHighest() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Stopped);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);
        VolumeVO vol = createMockVolume(VOLUME_ID_1, POOL_ID_1);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(vol));
        StoragePoolVO pool = createOntapManagedPool(POOL_ID_1);
        when(storagePool.findById(POOL_ID_1)).thenReturn(pool);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.HIGHEST, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_NoVolumes_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.emptyList());
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_VolumeOnNonManagedPool_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeVO vol = createMockVolume(VOLUME_ID_1, POOL_ID_1);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(vol));

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(false);
        when(pool.getName()).thenReturn("non-managed-pool");
        when(storagePool.findById(POOL_ID_1)).thenReturn(pool);

        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_VolumeOnNonOntapManagedPool_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeVO vol = createMockVolume(VOLUME_ID_1, POOL_ID_1);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(vol));

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(pool.getStorageProviderName()).thenReturn("SolidFire");
        when(pool.getName()).thenReturn("solidfire-pool");
        when(storagePool.findById(POOL_ID_1)).thenReturn(pool);

        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_VolumeWithNullPoolId_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeVO vol = mock(VolumeVO.class);
        when(vol.getPoolId()).thenReturn(null);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(vol));

        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_AllocatedDiskType_PoolNotFound_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeVO vol = createMockVolume(VOLUME_ID_1, POOL_ID_1);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(vol));
        when(storagePool.findById(POOL_ID_1)).thenReturn(null);

        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_NonAllocated_HasFlexVolSnapshotDetails_AllOnOntap_ReturnsHighest() {
        setupAllVolumesOnOntap();
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Ready, VMSnapshot.Type.Disk);

        List<VMSnapshotDetailsVO> details = new ArrayList<>();
        details.add(new VMSnapshotDetailsVO(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT,
                "flex-uuid::snap-uuid::vmsnap_200_123::401", true));
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT)).thenReturn(details);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.HIGHEST, result);
    }

    @Test
    void testCanHandle_NonAllocated_HasLegacyStorageSnapshotDetails_AllOnOntap_ReturnsHighest() {
        setupAllVolumesOnOntap();
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Ready, VMSnapshot.Type.Disk);

        // No FlexVol details
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT)).thenReturn(Collections.emptyList());
        // Has legacy details
        List<VMSnapshotDetailsVO> details = new ArrayList<>();
        details.add(new VMSnapshotDetailsVO(SNAPSHOT_ID, "kvmStorageSnapshot", "123", true));
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot")).thenReturn(details);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.HIGHEST, result);
    }

    @Test
    void testCanHandle_NonAllocated_NoDetails_ReturnsCantHandle() {
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Ready, VMSnapshot.Type.Disk);
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT)).thenReturn(Collections.emptyList());
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot")).thenReturn(Collections.emptyList());

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_NonAllocated_HasFlexVolDetails_NotOnOntap_ReturnsCantHandle() {
        // VM has FlexVol details but volumes are now on non-ONTAP storage
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeVO vol = createMockVolume(VOLUME_ID_1, POOL_ID_1);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(vol));

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(false);
        when(pool.getName()).thenReturn("other-pool");
        when(storagePool.findById(POOL_ID_1)).thenReturn(pool);

        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Ready, VMSnapshot.Type.Disk);
        List<VMSnapshotDetailsVO> flexVolDetails = new ArrayList<>();
        flexVolDetails.add(new VMSnapshotDetailsVO(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT,
                "flex-uuid::snap-uuid::vmsnap_200_123::401", true));
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT)).thenReturn(flexVolDetails);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandle_MixedPools_OneOntapOneNot_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeVO vol1 = createMockVolume(VOLUME_ID_1, POOL_ID_1);
        VolumeVO vol2 = createMockVolume(VOLUME_ID_2, POOL_ID_2);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Arrays.asList(vol1, vol2));

        StoragePoolVO ontapPool = createOntapManagedPool(POOL_ID_1);
        StoragePoolVO otherPool = mock(StoragePoolVO.class);
        when(otherPool.isManaged()).thenReturn(true);
        when(otherPool.getStorageProviderName()).thenReturn("SolidFire");
        when(otherPool.getName()).thenReturn("sf-pool");
        when(storagePool.findById(POOL_ID_1)).thenReturn(ontapPool);
        when(storagePool.findById(POOL_ID_2)).thenReturn(otherPool);

        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        StrategyPriority result = strategy.canHandle(vmSnapshot);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: canHandle(Long vmId, Long rootPoolId, boolean snapshotMemory)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testCanHandleByVmId_MemorySnapshot_ReturnsCantHandle() {
        StrategyPriority result = strategy.canHandle(VM_ID, POOL_ID_1, true);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    void testCanHandleByVmId_DiskOnly_AllOnOntap_ReturnsHighest() {
        setupAllVolumesOnOntap();

        StrategyPriority result = strategy.canHandle(VM_ID, POOL_ID_1, false);

        assertEquals(StrategyPriority.HIGHEST, result);
    }

    @Test
    void testCanHandleByVmId_DiskOnly_NotOnOntap_ReturnsCantHandle() {
        when(userVmDao.findById(VM_ID)).thenReturn(null);

        StrategyPriority result = strategy.canHandle(VM_ID, POOL_ID_1, false);

        assertEquals(StrategyPriority.CANT_HANDLE, result);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: groupVolumesByFlexVol
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testGroupVolumesByFlexVol_SingleFlexVol_TwoVolumes() {
        VolumeObjectTO volumeTO1 = mock(VolumeObjectTO.class);
        when(volumeTO1.getId()).thenReturn(VOLUME_ID_1);
        VolumeObjectTO volumeTO2 = mock(VolumeObjectTO.class);
        when(volumeTO2.getId()).thenReturn(VOLUME_ID_2);

        VolumeVO vol1 = mock(VolumeVO.class);
        when(vol1.getId()).thenReturn(VOLUME_ID_1);
        when(vol1.getPoolId()).thenReturn(POOL_ID_1);
        VolumeVO vol2 = mock(VolumeVO.class);
        when(vol2.getId()).thenReturn(VOLUME_ID_2);
        when(vol2.getPoolId()).thenReturn(POOL_ID_1); // same pool → same FlexVol
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(vol1);
        when(volumeDao.findById(VOLUME_ID_2)).thenReturn(vol2);

        Map<String, String> poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails);

        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups =
                strategy.groupVolumesByFlexVol(Arrays.asList(volumeTO1, volumeTO2));

        assertEquals(1, groups.size());
        assertEquals(2, groups.get("flexvol-uuid-1").volumeIds.size());
    }

    @Test
    void testGroupVolumesByFlexVol_TwoFlexVols() {
        VolumeObjectTO volumeTO1 = mock(VolumeObjectTO.class);
        when(volumeTO1.getId()).thenReturn(VOLUME_ID_1);
        VolumeObjectTO volumeTO2 = mock(VolumeObjectTO.class);
        when(volumeTO2.getId()).thenReturn(VOLUME_ID_2);

        VolumeVO vol1 = mock(VolumeVO.class);
        when(vol1.getId()).thenReturn(VOLUME_ID_1);
        when(vol1.getPoolId()).thenReturn(POOL_ID_1);
        VolumeVO vol2 = mock(VolumeVO.class);
        when(vol2.getId()).thenReturn(VOLUME_ID_2);
        when(vol2.getPoolId()).thenReturn(POOL_ID_2); // different pool → different FlexVol
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(vol1);
        when(volumeDao.findById(VOLUME_ID_2)).thenReturn(vol2);

        Map<String, String> poolDetails1 = new HashMap<>();
        poolDetails1.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        Map<String, String> poolDetails2 = new HashMap<>();
        poolDetails2.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-2");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails1);
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_2)).thenReturn(poolDetails2);

        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups =
                strategy.groupVolumesByFlexVol(Arrays.asList(volumeTO1, volumeTO2));

        assertEquals(2, groups.size());
        assertEquals(1, groups.get("flexvol-uuid-1").volumeIds.size());
        assertEquals(1, groups.get("flexvol-uuid-2").volumeIds.size());
    }

    @Test
    void testGroupVolumesByFlexVol_MissingFlexVolUuid_ThrowsException() {
        VolumeObjectTO volumeTO1 = mock(VolumeObjectTO.class);
        when(volumeTO1.getId()).thenReturn(VOLUME_ID_1);

        VolumeVO vol1 = mock(VolumeVO.class);
        when(vol1.getId()).thenReturn(VOLUME_ID_1);
        when(vol1.getPoolId()).thenReturn(POOL_ID_1);
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(vol1);

        Map<String, String> poolDetails = new HashMap<>();
        // No VOLUME_UUID key
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails);

        assertThrows(CloudRuntimeException.class,
                () -> strategy.groupVolumesByFlexVol(Collections.singletonList(volumeTO1)));
    }

    @Test
    void testGroupVolumesByFlexVol_VolumeNotFound_ThrowsException() {
        VolumeObjectTO volumeTO1 = mock(VolumeObjectTO.class);
        when(volumeTO1.getId()).thenReturn(VOLUME_ID_1);
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> strategy.groupVolumesByFlexVol(Collections.singletonList(volumeTO1)));
    }

    @Test
    void testCreateTemporaryConsistencyGroup_includesSvmName() {
        SnapshotFeignClient client = mock(SnapshotFeignClient.class);
        StorageStrategy storageStrategy = mock(StorageStrategy.class);
        when(client.createConsistencyGroup(any(), any())).thenReturn(createJobResponse("job-cg-create"));
        OntapResponse<ConsistencyGroup> cgResponse = new OntapResponse<>();
        ConsistencyGroup cgRecord = new ConsistencyGroup();
        cgRecord.setUuid("cg-uuid-1");
        cgResponse.setRecords(Collections.singletonList(cgRecord));
        when(client.getConsistencyGroups(any(), any())).thenReturn(cgResponse);

        String cgUuid = strategy.createTemporaryConsistencyGroup(client, storageStrategy, "auth",
                "cg-name", new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "vs0", "svm-uuid-1"),
                java.util.Set.of("flexvol-uuid-1", "flexvol-uuid-2"));

        assertEquals("cg-uuid-1", cgUuid);
        org.mockito.ArgumentCaptor<ConsistencyGroup> payloadCaptor = org.mockito.ArgumentCaptor.forClass(ConsistencyGroup.class);
        verify(client).createConsistencyGroup(eq("auth"), payloadCaptor.capture());
        ConsistencyGroup payload = payloadCaptor.getValue();
        assertEquals("cg-name", payload.getName());
        assertEquals("svm-uuid-1", payload.getSvm().getUuid());
        assertEquals(2, payload.getVolumes().size());
        assertEquals(OntapStorageConstants.CG_VOLUME_PROVISIONING_ACTION_ADD,
                payload.getVolumes().get(0).getProvisioningOptions().getAction());
    }

    @Test
    void testResolveConsistencyGroupScope_rejectsDifferentStorageIpWithSameSvmName() {
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = new HashMap<>();
        Map<String, String> poolDetails1 = new HashMap<>();
        poolDetails1.put(OntapStorageConstants.STORAGE_IP, "10.1.1.1");
        poolDetails1.put(OntapStorageConstants.SVM_NAME, "vs0");
        groups.put("flexvol-uuid-1", new OntapVMSnapshotStrategy.FlexVolGroupInfo(poolDetails1, POOL_ID_1));

        Map<String, String> poolDetails2 = new HashMap<>();
        poolDetails2.put(OntapStorageConstants.STORAGE_IP, "10.2.2.2");
        poolDetails2.put(OntapStorageConstants.SVM_NAME, "vs0");
        groups.put("flexvol-uuid-2", new OntapVMSnapshotStrategy.FlexVolGroupInfo(poolDetails2, POOL_ID_2));

        assertThrows(CloudRuntimeException.class, () -> strategy.resolveConsistencyGroupScope(groups));
    }

    @Test
    void testResolveConsistencyGroupScope_acceptsSameClusterAndSvmUuid() {
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = new HashMap<>();
        Map<String, String> poolDetails1 = new HashMap<>();
        poolDetails1.put(OntapStorageConstants.STORAGE_IP, "10.1.1.1");
        poolDetails1.put(OntapStorageConstants.SVM_NAME, "vs0");
        poolDetails1.put(OntapStorageConstants.SVM_UUID, "svm-uuid-shared");
        groups.put("flexvol-uuid-1", new OntapVMSnapshotStrategy.FlexVolGroupInfo(poolDetails1, POOL_ID_1));

        Map<String, String> poolDetails2 = new HashMap<>();
        poolDetails2.put(OntapStorageConstants.STORAGE_IP, "10.1.1.1");
        poolDetails2.put(OntapStorageConstants.SVM_NAME, "vs0");
        poolDetails2.put(OntapStorageConstants.SVM_UUID, "svm-uuid-shared");
        groups.put("flexvol-uuid-2", new OntapVMSnapshotStrategy.FlexVolGroupInfo(poolDetails2, POOL_ID_2));

        OntapVMSnapshotStrategy.ConsistencyGroupScope scope = strategy.resolveConsistencyGroupScope(groups);
        assertEquals("svm-uuid-shared", scope.svmUuid);
        assertEquals("10.1.1.1", scope.storageIp);
    }

    @Test
    void testResolveConsistencyGroupScope_rejectsDifferentSvmUuidOnSameCluster() {
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = new HashMap<>();
        Map<String, String> poolDetails1 = new HashMap<>();
        poolDetails1.put(OntapStorageConstants.STORAGE_IP, "10.1.1.1");
        poolDetails1.put(OntapStorageConstants.SVM_NAME, "vs0");
        poolDetails1.put(OntapStorageConstants.SVM_UUID, "svm-uuid-1");
        groups.put("flexvol-uuid-1", new OntapVMSnapshotStrategy.FlexVolGroupInfo(poolDetails1, POOL_ID_1));

        Map<String, String> poolDetails2 = new HashMap<>();
        poolDetails2.put(OntapStorageConstants.STORAGE_IP, "10.1.1.1");
        poolDetails2.put(OntapStorageConstants.SVM_NAME, "vs0");
        poolDetails2.put(OntapStorageConstants.SVM_UUID, "svm-uuid-2");
        groups.put("flexvol-uuid-2", new OntapVMSnapshotStrategy.FlexVolGroupInfo(poolDetails2, POOL_ID_2));

        assertThrows(CloudRuntimeException.class, () -> strategy.resolveConsistencyGroupScope(groups));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: FlexVolSnapshotDetail parse/toString
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testFlexVolSnapshotDetail_ParseAndToString_NewFormat() {
        String value = "flexvol-uuid-1::snap-uuid-1::vmsnap_200_1234567890::root-disk.qcow2::401::NFS3";
        OntapVMSnapshotStrategy.FlexVolSnapshotDetail detail =
                OntapVMSnapshotStrategy.FlexVolSnapshotDetail.parse(value);

        assertEquals("flexvol-uuid-1", detail.flexVolUuid);
        assertEquals("snap-uuid-1", detail.snapshotUuid);
        assertEquals("vmsnap_200_1234567890", detail.snapshotName);
        assertEquals("root-disk.qcow2", detail.volumePath);
        assertEquals(401L, detail.poolId);
        assertEquals("NFS3", detail.protocol);
        assertEquals(value, detail.toString());
    }

    @Test
    void testFlexVolSnapshotDetail_ParseLegacy4FieldFormat() {
        // Legacy format without volumePath and protocol
        String value = "flexvol-uuid-1::snap-uuid-1::vmsnap_200_1234567890::401";
        OntapVMSnapshotStrategy.FlexVolSnapshotDetail detail =
                OntapVMSnapshotStrategy.FlexVolSnapshotDetail.parse(value);

        assertEquals("flexvol-uuid-1", detail.flexVolUuid);
        assertEquals("snap-uuid-1", detail.snapshotUuid);
        assertEquals("vmsnap_200_1234567890", detail.snapshotName);
        assertEquals(null, detail.volumePath);
        assertEquals(401L, detail.poolId);
        assertEquals(null, detail.protocol);
    }

    @Test
    void testFlexVolSnapshotDetail_ParseInvalidFormat_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
                () -> OntapVMSnapshotStrategy.FlexVolSnapshotDetail.parse("invalid-format"));
    }

    @Test
    void testFlexVolSnapshotDetail_ParseTooFewParts_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
                () -> OntapVMSnapshotStrategy.FlexVolSnapshotDetail.parse("a::b::c"));
    }

    @Test
    void testFlexVolSnapshotDetail_Parse5Parts_ThrowsException() {
        // 5 parts is neither legacy (4) nor current (6) format
        assertThrows(CloudRuntimeException.class,
                () -> OntapVMSnapshotStrategy.FlexVolSnapshotDetail.parse("a::b::c::d::e"));
    }

    @Test
    void testRollbackFlexVolSnapshot_UsesSharedDelete() {
        OntapVMSnapshotStrategy.FlexVolSnapshotDetail detail = new OntapVMSnapshotStrategy.FlexVolSnapshotDetail(
                "flexvol-uuid-1", "snapshot-uuid-1", "snapshot-name-1", "volume-301.qcow2", POOL_ID_1, "NFS3");
        doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());

        assertDoesNotThrow(() -> strategy.rollbackFlexVolSnapshot(detail));

        verify(storageStrategy).deleteFlexVolSnapshotForCloudStackVolume(
                "flexvol-uuid-1", "snapshot-uuid-1", "snapshot-name-1");
        verify(storageStrategy, never()).getSnapshotFeignClient();
        verify(storageStrategy, never()).getAuthHeader();
        verify(storageStrategy, never()).jobPollForSuccess(any(), anyInt(), anyInt());
    }

    @Test
    void testRollbackFlexVolSnapshot_DeleteFailureIsLoggedAndNotThrown() {
        OntapVMSnapshotStrategy.FlexVolSnapshotDetail detail = new OntapVMSnapshotStrategy.FlexVolSnapshotDetail(
                "flexvol-uuid-1", "snapshot-uuid-1", "snapshot-name-1", "volume-301.qcow2", POOL_ID_1, "NFS3");
        doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
        doThrow(new CloudRuntimeException("delete failed")).when(storageStrategy)
                .deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());

        Logger logger = (Logger) LogManager.getLogger(OntapVMSnapshotStrategy.class);
        Appender appender = mock(Appender.class);
        when(appender.getName()).thenReturn("rollback-test-appender");
        when(appender.isStarted()).thenReturn(true);
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> strategy.rollbackFlexVolSnapshot(detail));

            verify(appender).append(argThat(event -> event.getMessage().getFormattedMessage()
                    .contains("Rollback of FlexVol snapshot failed: delete failed")));
        } finally {
            logger.removeAppender(appender);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: buildSnapshotName
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testBuildSnapshotName_Format() {
        VMSnapshotVO vmSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(vmSnapshot.getName()).thenReturn("UI VM Snapshot");

        String name = strategy.buildSnapshotName(vmSnapshot);

        assertEquals(true, name.startsWith("UI_VM_Snapshot_vm200"));
        assertEquals(true, name.length() <= OntapStorageConstants.MAX_SNAPSHOT_NAME_LENGTH);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: resolveVolumePathOnOntap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testResolveVolumePathOnOntap_NFS_ReturnsVolumePath() {
        VolumeVO vol = mock(VolumeVO.class);
        when(vol.getPath()).thenReturn("abc123-def456.qcow2");
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(vol);

        String path = strategy.resolveVolumePathOnOntap(VOLUME_ID_1, "NFS3", new HashMap<>());

        assertEquals("abc123-def456.qcow2", path);
    }

    @Test
    void testResolveVolumePathOnOntap_ISCSI_ReturnsLunName() {
        VolumeDetailVO lunDetail = mock(VolumeDetailVO.class);
        when(lunDetail.getValue()).thenReturn("/vol/vol1/lun_301");
        when(volumeDetailsDao.findDetail(VOLUME_ID_1, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunDetail);

        String path = strategy.resolveVolumePathOnOntap(VOLUME_ID_1, "ISCSI", new HashMap<>());

        assertEquals("/vol/vol1/lun_301", path);
    }

    @Test
    void testResolveVolumePathOnOntap_ISCSI_NoLunDetail_ThrowsException() {
        when(volumeDetailsDao.findDetail(VOLUME_ID_1, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveVolumePathOnOntap(VOLUME_ID_1, "ISCSI", new HashMap<>()));
    }

    @Test
    void testResolveVolumePathOnOntap_NFS_VolumeNotFound_ThrowsException() {
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveVolumePathOnOntap(VOLUME_ID_1, "NFS3", new HashMap<>()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: takeVMSnapshot — State transitions & Freeze/Thaw
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testTakeVMSnapshot_StateTransitionFails_ThrowsCloudRuntimeException() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        when(vmSnapshotHelper.pickRunningHost(VM_ID)).thenReturn(HOST_ID);
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        // State transition fails
        doThrow(new NoTransitionException("Cannot transition")).when(vmSnapshotHelper)
                .vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.CreateRequested);

        assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));
    }

    @Test
    void testTakeVMSnapshot_FreezeFailure_ThrowsException() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();

        // Freeze failure
        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(false);
        when(freezeAnswer.getDetails()).thenReturn("qemu-guest-agent not responding");
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class))).thenReturn(freezeAnswer);

        // Cleanup mocks for finally block
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.takeVMSnapshot(vmSnapshot));

        assertEquals(true, ex.getMessage().contains("Could not freeze VM"));
        assertEquals(true, ex.getMessage().contains("qemu-guest-agent"));
    }

    @Test
    void testTakeVMSnapshot_FreezeReturnsNull_ThrowsException() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();

        // Freeze returns null
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class))).thenReturn(null);

        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));
    }

    @Test
    void testTakeVMSnapshot_AgentUnavailable_ThrowsCloudRuntimeException() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();

        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenThrow(new AgentUnavailableException(HOST_ID));

        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.takeVMSnapshot(vmSnapshot));
        assertEquals(true, ex.getMessage().contains("failed"));
    }

    @Test
    void testTakeVMSnapshot_OperationTimeout_ThrowsCloudRuntimeException() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();

        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenThrow(new OperationTimedoutException(null, 0, 0, 0, false));

        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.takeVMSnapshot(vmSnapshot));
        assertEquals(true, ex.getMessage().contains("timed out"));
    }

    @Test
    void testTakeVMSnapshot_SingleFlexVolSuccess_UsesDirectSnapshotNotCg() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();

        String snapshotName = strategy.buildSnapshotName(vmSnapshot);
        setupSingleFlexVolFlowMocks(snapshotName);

        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);

        strategy.takeVMSnapshot(vmSnapshot);

        verify(snapshotFeignClient, times(1)).createSnapshot(any(), eq("flexvol-uuid-1"), any());
        verify(snapshotFeignClient, never()).createConsistencyGroup(any(), any());
        verify(snapshotFeignClient, never()).createConsistencyGroupSnapshot(any(), any(), any());
        verify(snapshotFeignClient, never()).commitConsistencyGroupSnapshot(any(), any(), any(), any());
        verify(snapshotFeignClient, never()).deleteConsistencyGroup(any(), any());
        verify(vmSnapshotDetailsDao, atLeastOnce()).persist(any(VMSnapshotDetailsVO.class));
    }

    @Test
    void testTakeVMSnapshot_RollbackDeleteFailurePreservesOriginalError() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        String snapshotName = strategy.buildSnapshotName(vmSnapshot);
        setupSingleFlexVolFlowMocks(snapshotName);

        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);
        doThrow(new CloudRuntimeException("snapshot persistence failed")).when(vmSnapshotDetailsDao)
                .persist(any(VMSnapshotDetailsVO.class));
        doThrow(new CloudRuntimeException("rollback delete failed")).when(storageStrategy)
                .deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> strategy.takeVMSnapshot(vmSnapshot));

        assertEquals("snapshot persistence failed", exception.getMessage());
        verify(storageStrategy).deleteFlexVolSnapshotForCloudStackVolume(
                eq("flexvol-uuid-1"), any(), eq(snapshotName));
    }

    @Test
    void testTakeVMSnapshot_TemporaryCgTwoPhaseSuccess_PersistsDetailsAndCleansUpCg() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupMultiFlexVolForTakeSnapshot();

        String snapshotName = strategy.buildSnapshotName(vmSnapshot);
        setupTemporaryCgFlowMocks(snapshotName);

        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);

        strategy.takeVMSnapshot(vmSnapshot);

        verify(snapshotFeignClient, times(1)).createConsistencyGroup(any(), any());
        verify(snapshotFeignClient, times(1)).createConsistencyGroupSnapshot(any(), eq("cg-uuid-1"), any());
        verify(snapshotFeignClient, times(1)).commitConsistencyGroupSnapshot(any(), eq("cg-uuid-1"), eq("cg-snap-uuid-1"), any());
        verify(snapshotFeignClient, times(1)).deleteConsistencyGroup(any(), eq("cg-uuid-1"));
        verify(vmSnapshotDetailsDao, atLeastOnce()).persist(any(VMSnapshotDetailsVO.class));
    }

    @Test
    void testTakeVMSnapshot_TemporaryCgStartFails_TransitionsToOperationFailed() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupMultiFlexVolForTakeSnapshot();

        String snapshotName = strategy.buildSnapshotName(vmSnapshot);
        setupTemporaryCgFlowMocks(snapshotName);
        when(snapshotFeignClient.createConsistencyGroupSnapshot(any(), eq("cg-uuid-1"), any()))
                .thenThrow(new CloudRuntimeException("start phase failed"));

        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        verify(snapshotFeignClient, times(1)).deleteConsistencyGroup(any(), eq("cg-uuid-1"));
        verify(vmSnapshotHelper, atLeastOnce()).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: Quiesce Behavior
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testTakeVMSnapshot_QuiesceFalse_SkipsFreezeThaw() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        // Explicitly set quiesce to false
        VMSnapshotOptions options = mock(VMSnapshotOptions.class);
        when(options.needQuiesceVM()).thenReturn(false);
        when(vmSnapshot.getOptions()).thenReturn(options);

        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));

        strategy.takeVMSnapshot(vmSnapshot);

        // No freeze/thaw commands should be sent when quiesce is false
        verify(agentMgr, never()).send(eq(HOST_ID), any(FreezeThawVMCommand.class));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tests: Parent snapshot chain
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testTakeVMSnapshot_WithParentSnapshot_SetsParentId() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();

        // Has a current (parent) snapshot
        VMSnapshotVO currentSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshotDao.findCurrentSnapshotByVmId(VM_ID)).thenReturn(currentSnapshot);
        VMSnapshotTO parentTO = mock(VMSnapshotTO.class);
        when(parentTO.getId()).thenReturn(199L);
        when(vmSnapshotHelper.getSnapshotWithParents(currentSnapshot)).thenReturn(parentTO);

        // Freeze success (since quiesce=true by default)
        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));

        strategy.takeVMSnapshot(vmSnapshot);

        // Verify parent was set on the VM snapshot before the FlexVol snapshot attempt
        verify(vmSnapshot).setParent(199L);
    }

    @Test
    void testTakeVMSnapshot_WithNoParentSnapshot_SetsParentNull() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();

        when(vmSnapshotDao.findCurrentSnapshotByVmId(VM_ID)).thenReturn(null);

        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));

        strategy.takeVMSnapshot(vmSnapshot);

        verify(vmSnapshot).setParent(null);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper: Set up common mocks for takeVMSnapshot tests
    // ──────────────────────────────────────────────────────────────────────────

    private VMSnapshotVO createTakeSnapshotVmSnapshot() {
        VMSnapshotVO vmSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(vmSnapshot.getVmId()).thenReturn(VM_ID);
        lenient().when(vmSnapshot.getName()).thenReturn("vm-snap-1");
        lenient().when(vmSnapshot.getType()).thenReturn(VMSnapshot.Type.Disk);
        lenient().when(vmSnapshot.getDescription()).thenReturn("Test ONTAP VM Snapshot");
        lenient().when(vmSnapshot.getOptions()).thenReturn(new VMSnapshotOptions(true));
        return vmSnapshot;
    }

    private UserVmVO setupTakeSnapshotCommon(VMSnapshotVO vmSnapshot) throws Exception {
        when(vmSnapshotHelper.pickRunningHost(VM_ID)).thenReturn(HOST_ID);

        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(VM_ID);
        when(userVm.getGuestOSId()).thenReturn(GUEST_OS_ID);
        when(userVm.getInstanceName()).thenReturn(VM_INSTANCE_NAME);
        when(userVm.getUuid()).thenReturn(VM_UUID);
        when(userVm.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        GuestOSVO guestOS = mock(GuestOSVO.class);
        when(guestOS.getDisplayName()).thenReturn("CentOS 8");
        when(guestOSDao.findById(GUEST_OS_ID)).thenReturn(guestOS);

        when(vmSnapshotDao.findCurrentSnapshotByVmId(VM_ID)).thenReturn(null);

        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.CreateRequested);
        doNothing().when(strategy).processAnswer(any(), any(), any(), any());
        doNothing().when(strategy).publishUsageEvent(any(), any(), any(), any());
        doNothing().when(strategy).publishUsageEvent(any(), any(), any(), anyLong(), anyLong());

        return userVm;
    }

    private void setupSingleVolumeForTakeSnapshot() {
        VolumeObjectTO volumeTO = mock(VolumeObjectTO.class);
        when(volumeTO.getId()).thenReturn(VOLUME_ID_1);
        when(volumeTO.getSize()).thenReturn(10737418240L);
        List<VolumeObjectTO> volumeTOs = Collections.singletonList(volumeTO);
        when(vmSnapshotHelper.getVolumeTOList(VM_ID)).thenReturn(volumeTOs);

        VolumeVO volumeVO = mock(VolumeVO.class);
        when(volumeVO.getId()).thenReturn(VOLUME_ID_1);
        when(volumeVO.getPoolId()).thenReturn(POOL_ID_1);
        when(volumeVO.getPath()).thenReturn("volume-301.qcow2");
        when(volumeVO.getVmSnapshotChainSize()).thenReturn(null);
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(volumeVO);

        // Pool details for FlexVol grouping
        Map<String, String> poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        poolDetails.put(OntapStorageConstants.USERNAME, "admin");
        poolDetails.put(OntapStorageConstants.PASSWORD, "pass");
        poolDetails.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");
        poolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
        poolDetails.put(OntapStorageConstants.SIZE, "107374182400");
        poolDetails.put(OntapStorageConstants.PROTOCOL, "NFS3");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getId()).thenReturn(VOLUME_ID_1);
        when(volumeInfo.getName()).thenReturn("vol-1");
        when(volumeDataFactory.getVolume(VOLUME_ID_1)).thenReturn(volumeInfo);
    }

    private void setupMultiFlexVolForTakeSnapshot() {
        VolumeObjectTO volumeTO1 = mock(VolumeObjectTO.class);
        when(volumeTO1.getId()).thenReturn(VOLUME_ID_1);
        when(volumeTO1.getSize()).thenReturn(10737418240L);
        VolumeObjectTO volumeTO2 = mock(VolumeObjectTO.class);
        when(volumeTO2.getId()).thenReturn(VOLUME_ID_2);
        when(volumeTO2.getSize()).thenReturn(10737418240L);
        List<VolumeObjectTO> volumeTOs = Arrays.asList(volumeTO1, volumeTO2);
        when(vmSnapshotHelper.getVolumeTOList(VM_ID)).thenReturn(volumeTOs);

        VolumeVO volumeVO1 = mock(VolumeVO.class);
        when(volumeVO1.getId()).thenReturn(VOLUME_ID_1);
        when(volumeVO1.getPoolId()).thenReturn(POOL_ID_1);
        when(volumeVO1.getPath()).thenReturn("volume-301.qcow2");
        when(volumeVO1.getVmSnapshotChainSize()).thenReturn(null);
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(volumeVO1);

        VolumeVO volumeVO2 = mock(VolumeVO.class);
        when(volumeVO2.getId()).thenReturn(VOLUME_ID_2);
        when(volumeVO2.getPoolId()).thenReturn(POOL_ID_2);
        when(volumeVO2.getPath()).thenReturn("volume-302.qcow2");
        when(volumeVO2.getVmSnapshotChainSize()).thenReturn(null);
        when(volumeDao.findById(VOLUME_ID_2)).thenReturn(volumeVO2);

        Map<String, String> poolDetails1 = new HashMap<>();
        poolDetails1.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        poolDetails1.put(OntapStorageConstants.USERNAME, "admin");
        poolDetails1.put(OntapStorageConstants.PASSWORD, "pass");
        poolDetails1.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");
        poolDetails1.put(OntapStorageConstants.SVM_NAME, "svm1");
        poolDetails1.put(OntapStorageConstants.SVM_UUID, "svm-uuid-shared");
        poolDetails1.put(OntapStorageConstants.SIZE, "107374182400");
        poolDetails1.put(OntapStorageConstants.PROTOCOL, "NFS3");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails1);

        Map<String, String> poolDetails2 = new HashMap<>();
        poolDetails2.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-2");
        poolDetails2.put(OntapStorageConstants.USERNAME, "admin");
        poolDetails2.put(OntapStorageConstants.PASSWORD, "pass");
        poolDetails2.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");
        poolDetails2.put(OntapStorageConstants.SVM_NAME, "svm1");
        poolDetails2.put(OntapStorageConstants.SVM_UUID, "svm-uuid-shared");
        poolDetails2.put(OntapStorageConstants.SIZE, "107374182400");
        poolDetails2.put(OntapStorageConstants.PROTOCOL, "NFS3");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_2)).thenReturn(poolDetails2);

        VolumeInfo volumeInfo1 = mock(VolumeInfo.class);
        when(volumeInfo1.getId()).thenReturn(VOLUME_ID_1);
        when(volumeDataFactory.getVolume(VOLUME_ID_1)).thenReturn(volumeInfo1);
        VolumeInfo volumeInfo2 = mock(VolumeInfo.class);
        when(volumeInfo2.getId()).thenReturn(VOLUME_ID_2);
        when(volumeDataFactory.getVolume(VOLUME_ID_2)).thenReturn(volumeInfo2);
    }

    private JobResponse createJobResponse(String uuid) {
        Job job = new Job();
        job.setUuid(uuid);
        JobResponse response = new JobResponse();
        response.setJob(job);
        return response;
    }

    private void setupSingleFlexVolFlowMocks(String snapshotName) {
        doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
        when(storageStrategy.getSnapshotFeignClient()).thenReturn(snapshotFeignClient);
        when(storageStrategy.getAuthHeader()).thenReturn("Basic dGVzdDp0ZXN0");
        when(storageStrategy.jobPollForSuccess(any(), anyInt(), anyInt())).thenReturn(true);

        when(snapshotFeignClient.createSnapshot(any(), eq("flexvol-uuid-1"), any()))
                .thenReturn(createJobResponse("job-fv-snap"));

        OntapResponse<FlexVolSnapshot> flexVolSnapshots = new OntapResponse<>();
        FlexVolSnapshot flexVolSnapshot = new FlexVolSnapshot();
        flexVolSnapshot.setUuid("fv-snap-uuid-1");
        flexVolSnapshot.setName(snapshotName);
        flexVolSnapshots.setRecords(Collections.singletonList(flexVolSnapshot));
        when(snapshotFeignClient.getSnapshots(any(), eq("flexvol-uuid-1"), any()))
                .thenReturn(flexVolSnapshots);
    }

    private void setupTemporaryCgFlowMocks(String snapshotName) {
        doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
        when(storageStrategy.getSnapshotFeignClient()).thenReturn(snapshotFeignClient);
        when(storageStrategy.getAuthHeader()).thenReturn("Basic dGVzdDp0ZXN0");
        when(storageStrategy.jobPollForSuccess(any(), anyInt(), anyInt())).thenReturn(true);
        when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), any())).thenAnswer(invocation -> {
            Job completedJob = new Job();
            completedJob.setState(OntapStorageConstants.JOB_SUCCESS);
            String operationName = invocation.getArgument(1);
            if (operationName != null && operationName.startsWith("start CG snapshot")) {
                completedJob.setDescription(
                        "POST /api/application/consistency-groups/cg-uuid-1/snapshots/cg-snap-uuid-1");
            }
            return completedJob;
        });

        when(snapshotFeignClient.createConsistencyGroup(any(), any())).thenReturn(createJobResponse("job-cg-create"));
        OntapResponse<ConsistencyGroup> cgResponse = new OntapResponse<>();
        ConsistencyGroup cgRecord = new ConsistencyGroup();
        cgRecord.setUuid("cg-uuid-1");
        cgResponse.setRecords(Collections.singletonList(cgRecord));
        when(snapshotFeignClient.getConsistencyGroups(any(), any())).thenReturn(cgResponse);

        when(snapshotFeignClient.createConsistencyGroupSnapshot(any(), eq("cg-uuid-1"), any()))
                .thenReturn(createJobResponse("job-cg-start"));
        OntapResponse<ConsistencyGroupSnapshot> cgSnapshotResponse = new OntapResponse<>();
        ConsistencyGroupSnapshot cgSnapshotRecord = new ConsistencyGroupSnapshot();
        cgSnapshotRecord.setUuid("cg-snap-uuid-1");
        cgSnapshotRecord.setName(snapshotName);
        cgSnapshotResponse.setRecords(Collections.singletonList(cgSnapshotRecord));
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-uuid-1"), any()))
                .thenReturn(cgSnapshotResponse);
        when(snapshotFeignClient.commitConsistencyGroupSnapshot(any(), eq("cg-uuid-1"), eq("cg-snap-uuid-1"), any()))
                .thenReturn(createJobResponse("job-cg-commit"));

        when(snapshotFeignClient.deleteConsistencyGroup(any(), eq("cg-uuid-1")))
                .thenReturn(createJobResponse("job-cg-delete"));

        OntapResponse<FlexVolSnapshot> flexVolSnapshots = new OntapResponse<>();
        FlexVolSnapshot flexVolSnapshot = new FlexVolSnapshot();
        flexVolSnapshot.setUuid("fv-snap-uuid-1");
        flexVolSnapshot.setName(snapshotName);
        flexVolSnapshots.setRecords(Collections.singletonList(flexVolSnapshot));
        when(snapshotFeignClient.getSnapshots(any(), eq("flexvol-uuid-1"), any()))
                .thenReturn(flexVolSnapshots);

        OntapResponse<FlexVolSnapshot> flexVolSnapshots2 = new OntapResponse<>();
        FlexVolSnapshot flexVolSnapshot2 = new FlexVolSnapshot();
        flexVolSnapshot2.setUuid("fv-snap-uuid-2");
        flexVolSnapshot2.setName(snapshotName);
        flexVolSnapshots2.setRecords(Collections.singletonList(flexVolSnapshot2));
        when(snapshotFeignClient.getSnapshots(any(), eq("flexvol-uuid-2"), any()))
                .thenReturn(flexVolSnapshots2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Assertions on what takeVMSnapshot reports, freezes, thaws and cleans up
    // ══════════════════════════════════════════════════════════════════════════

    private void stubFreezeAndThawSuccess() throws Exception {
        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);
    }

    private List<FreezeThawVMCommand> freezeThawCommandsSent(int expectedCount) throws Exception {
        org.mockito.ArgumentCaptor<FreezeThawVMCommand> captor = org.mockito.ArgumentCaptor.forClass(FreezeThawVMCommand.class);
        verify(agentMgr, times(expectedCount)).send(eq(HOST_ID), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void testTakeVMSnapshot_ReturnsTheSnapshot_FinalizesWithVolumes_AndPublishesUsageWithChainSizeDelta() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        UserVmVO userVm = setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        VolumeVO volumeRow = volumeDao.findById(VOLUME_ID_1);
        when(volumeRow.getVmSnapshotChainSize()).thenReturn(4000L);
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));
        stubFreezeAndThawSuccess();
        VolumeObjectTO volumeTO = vmSnapshotHelper.getVolumeTOList(VM_ID).get(0);

        assertSame(vmSnapshot, strategy.takeVMSnapshot(vmSnapshot));

        org.mockito.ArgumentCaptor<com.cloud.agent.api.Answer> answer = org.mockito.ArgumentCaptor.forClass(com.cloud.agent.api.Answer.class);
        verify(strategy).processAnswer(eq(vmSnapshot), eq(userVm), answer.capture(), eq(null));
        assertTrue(answer.getValue() instanceof com.cloud.agent.api.CreateVMSnapshotAnswer);
        assertTrue(answer.getValue().getResult());
        assertEquals(List.of(volumeTO), ((com.cloud.agent.api.CreateVMSnapshotAnswer)answer.getValue()).getVolumeTOs());
        verify(strategy).publishUsageEvent(eq(com.cloud.event.EventTypes.EVENT_VM_SNAPSHOT_CREATE), eq(vmSnapshot), eq(userVm), eq(volumeTO));
        verify(strategy).publishUsageEvent(eq(com.cloud.event.EventTypes.EVENT_VM_SNAPSHOT_ON_PRIMARY), eq(vmSnapshot), eq(userVm),
                eq(10737418240L - 4000L), eq(10737418240L));
        verify(vmSnapshotHelper, never()).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));
    }

    @Test
    void testTakeVMSnapshot_SendsFreezeThenThawCommands() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));
        stubFreezeAndThawSuccess();

        strategy.takeVMSnapshot(vmSnapshot);

        List<FreezeThawVMCommand> sent = freezeThawCommandsSent(2);
        assertEquals(FreezeThawVMCommand.FREEZE, sent.get(0).getOption());
        assertEquals(FreezeThawVMCommand.THAW, sent.get(1).getOption());
    }

    @Test
    void testTakeVMSnapshot_StoppedVm_SkipsFreezeThawButStillSnapshots() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        UserVmVO userVm = setupTakeSnapshotCommon(vmSnapshot);
        when(userVm.getState()).thenReturn(VirtualMachine.State.Stopped);
        setupSingleVolumeForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));

        assertSame(vmSnapshot, strategy.takeVMSnapshot(vmSnapshot));

        verify(agentMgr, never()).send(eq(HOST_ID), any(FreezeThawVMCommand.class));
        verify(snapshotFeignClient, times(1)).createSnapshot(any(), eq("flexvol-uuid-1"), any());
    }

    @Test
    void testTakeVMSnapshot_FreezeRejectedByGuest_DoesNotSendThawAndReportsDetails() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(false);
        when(freezeAnswer.getDetails()).thenReturn("guest agent is down");
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class))).thenReturn(freezeAnswer);
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        assertTrue(ex.getMessage().contains("guest agent is down"), ex.getMessage());
        assertEquals(1, freezeThawCommandsSent(1).size());
        verify(snapshotFeignClient, never()).createSnapshot(any(), any(), any());
        verify(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));
    }

    @Test
    void testTakeVMSnapshot_NoFreezeAnswer_ReportsNoResponse() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class))).thenReturn(null);
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        assertTrue(ex.getMessage().contains("no response from agent"), ex.getMessage());
        assertEquals(1, freezeThawCommandsSent(1).size());
    }

    @Test
    void testTakeVMSnapshot_FailureAfterSuccessfulThaw_DoesNotThawAgain() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));
        stubFreezeAndThawSuccess();
        doThrow(new CloudRuntimeException("persist failed")).when(vmSnapshotDetailsDao).persist(any(VMSnapshotDetailsVO.class));
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());

        assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        List<FreezeThawVMCommand> sent = freezeThawCommandsSent(2);
        assertEquals(FreezeThawVMCommand.FREEZE, sent.get(0).getOption());
        assertEquals(FreezeThawVMCommand.THAW, sent.get(1).getOption());
    }

    @Test
    void testTakeVMSnapshot_ThawThrows_ThenLaterFailure_ThawsAgainDuringCleanup() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));
        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenThrow(new OperationTimedoutException(null, HOST_ID, 0, 0, false))
                .thenReturn(thawAnswer);
        doThrow(new CloudRuntimeException("persist failed")).when(vmSnapshotDetailsDao).persist(any(VMSnapshotDetailsVO.class));
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.emptyList());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        assertEquals("persist failed", ex.getMessage());
        List<FreezeThawVMCommand> sent = freezeThawCommandsSent(3);
        assertEquals(FreezeThawVMCommand.FREEZE, sent.get(0).getOption());
        assertEquals(FreezeThawVMCommand.THAW, sent.get(1).getOption());
        assertEquals(FreezeThawVMCommand.THAW, sent.get(2).getOption());
    }

    @Test
    void testTakeVMSnapshot_Failure_RemovesOnlyFlexVolDetailsAndMarksOperationFailed() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupSingleVolumeForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));
        stubFreezeAndThawSuccess();
        doThrow(new CloudRuntimeException("persist failed")).when(vmSnapshotDetailsDao).persist(any(VMSnapshotDetailsVO.class));
        VMSnapshotDetailsVO flexVolRow = mock(VMSnapshotDetailsVO.class);
        when(flexVolRow.getId()).thenReturn(11L);
        when(flexVolRow.getName()).thenReturn(OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT);
        VMSnapshotDetailsVO unrelatedRow = mock(VMSnapshotDetailsVO.class);
        when(unrelatedRow.getId()).thenReturn(12L);
        when(unrelatedRow.getName()).thenReturn("unrelated-detail");
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(List.of(flexVolRow, unrelatedRow));

        assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        verify(vmSnapshotDetailsDao).remove(11L);
        verify(vmSnapshotDetailsDao, never()).remove(12L);
        verify(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.OperationFailed);
    }

    @Test
    void testCanHandle_AllocatedDiskType_OneVolumeOnNonOntapPool_ReturnsCantHandle() {
        setupAllVolumesOnOntap();
        StoragePoolVO foreignPool = mock(StoragePoolVO.class);
        when(foreignPool.isManaged()).thenReturn(false);
        when(foreignPool.getStorageProviderName()).thenReturn("DefaultPrimary");
        when(storagePool.findById(POOL_ID_2)).thenReturn(foreignPool);
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(vmSnapshot));
    }

    @Test
    void testConfigure_DelegatesToParentAndSucceeds() throws Exception {
        when(configurationDao.getValue("vmsnapshot.create.wait")).thenReturn("42");
        Map<String, Object> params = new HashMap<>();
        params.put("marker", "ontap");

        assertTrue(strategy.configure("ontap-vm-snapshot", params));

        verify(configurationDao).getValue("vmsnapshot.create.wait");
        assertEquals(42, strategy._wait);
    }

    /**
     * Covers the delete/revert flows, FlexVol helpers and consistency-group helpers of
     * {@link OntapVMSnapshotStrategy} that are not exercised by {@link OntapVMSnapshotStrategyTest}.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class OntapVMSnapshotStrategyOperationsTest {

        private static final long VM_ID = 100L;
        private static final long SNAPSHOT_ID = 200L;
        private static final long POOL_ID = 401L;
        private static final long OTHER_POOL_ID = 402L;
        private static final String AUTH = "Basic abc";

        @Mock private UserVmDao userVmDao;
        @Mock private VolumeDao volumeDao;
        @Mock private PrimaryDataStoreDao storagePool;
        @Mock private StoragePoolDetailsDao storagePoolDetailsDao;
        @Mock private VMSnapshotDetailsDao vmSnapshotDetailsDao;
        @Mock private VMSnapshotHelper vmSnapshotHelper;
        @Mock private VMSnapshotDao vmSnapshotDao;
        @Mock private AgentManager agentMgr;
        @Mock private GuestOSDao guestOSDao;
        @Mock private VolumeDetailsDao volumeDetailsDao;
        @Mock private StorageStrategy storageStrategy;
        @Mock private SnapshotFeignClient snapshotClient;

        @Spy
        @InjectMocks
        private OntapVMSnapshotStrategy strategy;

        // ------------------------------------------------------------------ helpers

        private VMSnapshotDetailsVO detailRow(long id, String value) {
            VMSnapshotDetailsVO row = mock(VMSnapshotDetailsVO.class);
            when(row.getId()).thenReturn(id);
            when(row.getValue()).thenReturn(value);
            return row;
        }

        private String detailValue(String flexVol, String snapUuid, String snapName, String path, long poolId) {
            return String.join("::", flexVol, snapUuid, snapName, path, String.valueOf(poolId), "NFS3");
        }

        private VMSnapshotVO vmSnapshot() {
            VMSnapshotVO snapshot = mock(VMSnapshotVO.class);
            when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
            when(snapshot.getVmId()).thenReturn(VM_ID);
            when(snapshot.getName()).thenReturn("snap-one");
            when(snapshot.getType()).thenReturn(VMSnapshot.Type.Disk);
            when(snapshot.getDescription()).thenReturn("a description");
            when(snapshot.getCreated()).thenReturn(new Date());
            return snapshot;
        }

        private List<VolumeObjectTO> prepareVmForDeleteOrRevert(VMSnapshotVO snapshot) {
            UserVmVO userVm = mock(UserVmVO.class);
            when(userVm.getId()).thenReturn(VM_ID);
            when(userVm.getInstanceName()).thenReturn("i-2-100-VM");
            when(userVm.getUuid()).thenReturn("vm-uuid");
            when(userVm.getGuestOSId()).thenReturn(50L);
            when(userVmDao.findById(VM_ID)).thenReturn(userVm);
            GuestOSVO guestOs = mock(GuestOSVO.class);
            when(guestOs.getDisplayName()).thenReturn("Ubuntu");
            when(guestOSDao.findById(50L)).thenReturn(guestOs);
            VolumeObjectTO volume = mock(VolumeObjectTO.class);
            when(volume.getSize()).thenReturn(1024L);
            List<VolumeObjectTO> volumes = List.of(volume);
            when(vmSnapshotHelper.getVolumeTOList(VM_ID)).thenReturn(volumes);
            when(vmSnapshotHelper.getSnapshotWithParents(snapshot)).thenReturn(mock(VMSnapshotTO.class));
            return volumes;
        }

        private void stubPool(long poolId, String volumeName) {
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.VOLUME_NAME, volumeName);
            when(storagePoolDetailsDao.listDetailsKeyPairs(poolId)).thenReturn(details);
            doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
        }

        private OntapResponse<ConsistencyGroupSnapshot> cgSnapshots(ConsistencyGroupSnapshot... records) {
            OntapResponse<ConsistencyGroupSnapshot> response = new OntapResponse<>();
            response.setRecords(Arrays.asList(records));
            return response;
        }

        private ConsistencyGroupSnapshot cgSnapshot(String name, String uuid) {
            ConsistencyGroupSnapshot snapshot = new ConsistencyGroupSnapshot(name);
            snapshot.setUuid(uuid);
            return snapshot;
        }

        private OntapVMSnapshotStrategy.FlexVolGroupInfo group(long poolId, String ip, String svmName, String svmUuid) {
            Map<String, String> details = new HashMap<>();
            if (ip != null) {
                details.put(OntapStorageConstants.STORAGE_IP, ip);
            }
            if (svmName != null) {
                details.put(OntapStorageConstants.SVM_NAME, svmName);
            }
            if (svmUuid != null) {
                details.put(OntapStorageConstants.SVM_UUID, svmUuid);
            }
            return new OntapVMSnapshotStrategy.FlexVolGroupInfo(details, poolId);
        }

        // ------------------------------------------------------------------ deleteVMSnapshot

        @Test
        void deleteVMSnapshot_deletesFlexVolSnapshotRemovesDetailAndPublishesUsage() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            prepareVmForDeleteOrRevert(snapshot);
            stubPool(POOL_ID, "vol1");
            VMSnapshotDetailsVO row = detailRow(7L, detailValue("fv-1", "snap-uuid", "snap-name", "disk.qcow2", POOL_ID));
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(List.of(row));
            doNothing().when(strategy).processAnswer(any(), any(), any(), any());
            doNothing().when(strategy).publishUsageEvent(anyString(), any(), any(), any(VolumeObjectTO.class));
            doNothing().when(strategy).publishUsageEvent(anyString(), any(), any(), anyLong(), anyLong());

            assertTrue(strategy.deleteVMSnapshot(snapshot));

            verify(vmSnapshotHelper).vmSnapshotStateTransitTo(snapshot, VMSnapshot.Event.ExpungeRequested);
            verify(storageStrategy).deleteFlexVolSnapshotForCloudStackVolume("fv-1", "snap-uuid", "snap-name");
            verify(vmSnapshotDetailsDao).remove(7L);
            verify(strategy).processAnswer(eq(snapshot), any(), any(), eq(null));
            verify(strategy).publishUsageEvent(anyString(), any(), any(), any(VolumeObjectTO.class));
            verify(strategy).publishUsageEvent(anyString(), any(), any(), eq(1024L), eq(0L));
        }

        @Test
        void deleteVMSnapshot_alsoCleansLegacyStorageSnapshots() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            prepareVmForDeleteOrRevert(snapshot);
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(Collections.emptyList());
            VMSnapshotDetailsVO hoistedRow1 = detailRow(8L, "legacy");
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, StorageVMSnapshotStrategy.STORAGE_SNAPSHOT))
                    .thenReturn(List.of(hoistedRow1));
            doNothing().when(strategy).deleteDiskSnapshot(any());
            doNothing().when(strategy).processAnswer(any(), any(), any(), any());
            doNothing().when(strategy).publishUsageEvent(anyString(), any(), any(), any(VolumeObjectTO.class));
            doNothing().when(strategy).publishUsageEvent(anyString(), any(), any(), anyLong(), anyLong());

            assertTrue(strategy.deleteVMSnapshot(snapshot));

            verify(strategy).deleteDiskSnapshot(snapshot);
            verify(storageStrategy, never()).deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());
        }

        @Test
        void deleteVMSnapshot_failsFast_whenStateTransitionIsRejected() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            when(userVmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));
            doThrow(new NoTransitionException("cannot expunge")).when(vmSnapshotHelper)
                    .vmSnapshotStateTransitTo(snapshot, VMSnapshot.Event.ExpungeRequested);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.deleteVMSnapshot(snapshot));

            assertEquals("cannot expunge", ex.getMessage());
            verify(vmSnapshotDetailsDao, never()).findDetails(anyLong(), anyString());
        }

        @Test
        void deleteVMSnapshot_wrapsOntapFailure_andDoesNotCompleteDeletion() {
            VMSnapshotVO snapshot = vmSnapshot();
            prepareVmForDeleteOrRevert(snapshot);
            stubPool(POOL_ID, "vol1");
            VMSnapshotDetailsVO row = detailRow(7L, detailValue("fv-1", "snap-uuid", "snap-name", "disk.qcow2", POOL_ID));
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(List.of(row));
            doThrow(new CloudRuntimeException("ontap down")).when(storageStrategy)
                    .deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.deleteVMSnapshot(snapshot));

            assertTrue(ex.getMessage().contains("Delete of ONTAP VM Snapshot [snap-one]"), ex.getMessage());
            assertTrue(ex.getMessage().contains("ontap down"), ex.getMessage());
            verify(vmSnapshotDetailsDao, never()).remove(anyLong());
            verify(strategy, never()).processAnswer(any(), any(), any(), any());
        }

        // ------------------------------------------------------------------ revertVMSnapshot

        @Test
        void revertVMSnapshot_restoresEachVolumeFromItsSnapshot() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            List<VolumeObjectTO> volumes = prepareVmForDeleteOrRevert(snapshot);
            stubPool(POOL_ID, "vol1");
            JobResponse job = new JobResponse();
            when(storageStrategy.revertSnapshotForCloudStackVolume(any(), any(), any(), any(), any(), any())).thenReturn(job);
            VMSnapshotDetailsVO row = detailRow(7L, detailValue("fv-1", "snap-uuid", "snap-name", "disk.qcow2", POOL_ID));
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(List.of(row));
            doNothing().when(strategy).processAnswer(any(), any(), any(), any());

            assertTrue(strategy.revertVMSnapshot(snapshot));

            org.mockito.ArgumentCaptor<com.cloud.agent.api.Answer> answer = org.mockito.ArgumentCaptor.forClass(com.cloud.agent.api.Answer.class);
            verify(strategy).processAnswer(eq(snapshot), any(), answer.capture(), eq(null));
            assertTrue(answer.getValue() instanceof com.cloud.agent.api.RevertToVMSnapshotAnswer);
            assertTrue(answer.getValue().getResult());
            assertEquals(volumes, ((com.cloud.agent.api.RevertToVMSnapshotAnswer)answer.getValue()).getVolumeTOs());
            verify(vmSnapshotHelper).vmSnapshotStateTransitTo(snapshot, VMSnapshot.Event.RevertRequested);
            verify(storageStrategy).revertSnapshotForCloudStackVolume("snap-name", "fv-1", "snap-uuid", "disk.qcow2", null, "vol1");
            verify(storageStrategy).executeCliSfsrRestore(eq(job), anyString());
            verify(vmSnapshotHelper, never()).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));
        }

        @Test
        void revertVMSnapshot_alsoRevertsLegacyStorageSnapshots() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            prepareVmForDeleteOrRevert(snapshot);
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(Collections.emptyList());
            VMSnapshotDetailsVO hoistedRow2 = detailRow(8L, "legacy");
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, StorageVMSnapshotStrategy.STORAGE_SNAPSHOT))
                    .thenReturn(List.of(hoistedRow2));
            doNothing().when(strategy).revertDiskSnapshot(any());
            doNothing().when(strategy).processAnswer(any(), any(), any(), any());

            assertTrue(strategy.revertVMSnapshot(snapshot));

            verify(strategy).revertDiskSnapshot(snapshot);
        }

        @Test
        void revertVMSnapshot_failsFast_whenStateTransitionIsRejected() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            when(userVmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));
            doThrow(new NoTransitionException("cannot revert")).when(vmSnapshotHelper)
                    .vmSnapshotStateTransitTo(snapshot, VMSnapshot.Event.RevertRequested);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.revertVMSnapshot(snapshot));

            assertEquals("cannot revert", ex.getMessage());
            verify(vmSnapshotHelper, never()).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));
        }

        @Test
        void revertVMSnapshot_marksSnapshotFailed_whenRestoreFails() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            prepareVmForDeleteOrRevert(snapshot);
            stubPool(POOL_ID, "vol1");
            doThrow(new CloudRuntimeException("restore failed")).when(storageStrategy)
                    .revertSnapshotForCloudStackVolume(any(), any(), any(), any(), any(), any());
            VMSnapshotDetailsVO row = detailRow(7L, detailValue("fv-1", "snap-uuid", "snap-name", "disk.qcow2", POOL_ID));
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(List.of(row));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.revertVMSnapshot(snapshot));

            assertEquals("Revert ONTAP VM Snapshot [snap-one] failed.", ex.getMessage());
            verify(vmSnapshotHelper).vmSnapshotStateTransitTo(snapshot, VMSnapshot.Event.OperationFailed);
            verify(strategy, never()).processAnswer(any(), any(), any(), any());
        }

        @Test
        void revertVMSnapshot_stillReportsRestoreError_whenFailureTransitionIsAlsoRejected() throws Exception {
            VMSnapshotVO snapshot = vmSnapshot();
            prepareVmForDeleteOrRevert(snapshot);
            stubPool(POOL_ID, "vol1");
            doThrow(new CloudRuntimeException("restore failed")).when(storageStrategy)
                    .revertSnapshotForCloudStackVolume(any(), any(), any(), any(), any(), any());
            VMSnapshotDetailsVO hoistedRow3 = detailRow(7L, detailValue("fv-1", "s", "n", "disk.qcow2", POOL_ID));
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(List.of(hoistedRow3));
            doThrow(new NoTransitionException("stuck")).when(vmSnapshotHelper)
                    .vmSnapshotStateTransitTo(snapshot, VMSnapshot.Event.OperationFailed);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.revertVMSnapshot(snapshot));

            assertTrue(ex.getMessage().contains("failed"), ex.getMessage());
        }

        // ------------------------------------------------------------------ deleteFlexVolSnapshots

        @Test
        void deleteFlexVolSnapshots_deletesSharedSnapshotOnce_andRemovesAllItsRows() {
            stubPool(POOL_ID, "vol1");
            VMSnapshotDetailsVO first = detailRow(1L, detailValue("fv-1", "snap-uuid", "snap", "a.qcow2", POOL_ID));
            VMSnapshotDetailsVO second = detailRow(2L, detailValue("fv-1", "snap-uuid", "snap", "b.qcow2", POOL_ID));

            strategy.deleteFlexVolSnapshots(List.of(first, second));

            verify(storageStrategy, times(1)).deleteFlexVolSnapshotForCloudStackVolume("fv-1", "snap-uuid", "snap");
            verify(vmSnapshotDetailsDao).remove(1L);
            verify(vmSnapshotDetailsDao).remove(2L);
        }

        @Test
        void deleteFlexVolSnapshots_keepsFailedRow_butStillProcessesRemainingRows_thenThrowsFirstFailure() {
            stubPool(POOL_ID, "vol1");
            VMSnapshotDetailsVO failing = detailRow(1L, detailValue("fv-1", "snap-a", "snap-a", "a.qcow2", POOL_ID));
            VMSnapshotDetailsVO succeeding = detailRow(2L, detailValue("fv-2", "snap-b", "snap-b", "b.qcow2", POOL_ID));
            doThrow(new CloudRuntimeException("first failed")).when(storageStrategy)
                    .deleteFlexVolSnapshotForCloudStackVolume("fv-1", "snap-a", "snap-a");

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshots(List.of(failing, succeeding)));

            assertEquals("first failed", ex.getMessage());
            verify(vmSnapshotDetailsDao, never()).remove(1L);
            verify(vmSnapshotDetailsDao).remove(2L);
            verify(storageStrategy).deleteFlexVolSnapshotForCloudStackVolume("fv-2", "snap-b", "snap-b");
        }

        @Test
        void deleteFlexVolSnapshots_wrapsNonCloudStackExceptions() {
            stubPool(POOL_ID, "vol1");
            doThrow(new IllegalStateException("boom")).when(storageStrategy)
                    .deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.deleteFlexVolSnapshots(
                    List.of(detailRow(1L, detailValue("fv-1", "snap", "snap", "a.qcow2", POOL_ID)))));

            assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
            assertTrue(ex.getCause() instanceof IllegalStateException);
            verify(vmSnapshotDetailsDao, never()).remove(anyLong());
        }

        @Test
        void deleteFlexVolSnapshots_rejectsMalformedDetailValue() {
            VMSnapshotDetailsVO malformed = detailRow(1L, "only::three::parts");

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshots(List.of(malformed)));

            assertTrue(ex.getMessage().contains("Invalid ONTAP FlexVol snapshot detail format"), ex.getMessage());
            verify(storageStrategy, never()).deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());
        }

        // ------------------------------------------------------------------ revertFlexVolSnapshots

        @Test
        void revertFlexVolSnapshots_skipsLegacyRowsWithoutVolumePath() {
            VMSnapshotDetailsVO legacy = detailRow(1L, "fv-1::snap-uuid::snap-name::" + POOL_ID);

            strategy.revertFlexVolSnapshots(List.of(legacy));

            verify(storageStrategy, never()).revertSnapshotForCloudStackVolume(any(), any(), any(), any(), any(), any());
            verify(storageStrategy, never()).executeCliSfsrRestore(any(), any());
        }

        @Test
        void revertFlexVolSnapshots_failsWhenFlexVolNameMissingFromPoolDetails() {
            when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(new HashMap<>());
            doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.revertFlexVolSnapshots(
                    List.of(detailRow(1L, detailValue("fv-1", "snap", "snap", "a.qcow2", POOL_ID)))));

            assertTrue(ex.getMessage().contains("FlexVolume name not found"), ex.getMessage());
        }

        @Test
        void revertFlexVolSnapshots_restoresEveryRow_includingLunPathsWithLeadingSlash() {
            stubPool(POOL_ID, "vol1");
            stubPool(OTHER_POOL_ID, "vol2");
            JobResponse nfsJob = new JobResponse();
            JobResponse lunJob = new JobResponse();
            when(storageStrategy.revertSnapshotForCloudStackVolume(eq("snap-a"), any(), any(), eq("a.qcow2"), any(), any()))
                    .thenReturn(nfsJob);
            when(storageStrategy.revertSnapshotForCloudStackVolume(eq("snap-b"), any(), any(), eq("/vol/vol2/lun1"), any(), any()))
                    .thenReturn(lunJob);

            strategy.revertFlexVolSnapshots(List.of(
                    detailRow(1L, detailValue("fv-1", "uuid-a", "snap-a", "a.qcow2", POOL_ID)),
                    detailRow(2L, detailValue("fv-2", "uuid-b", "snap-b", "/vol/vol2/lun1", OTHER_POOL_ID))));

            verify(storageStrategy).executeCliSfsrRestore(eq(nfsJob), org.mockito.ArgumentMatchers.contains("/a.qcow2"));
            verify(storageStrategy).executeCliSfsrRestore(eq(lunJob), org.mockito.ArgumentMatchers.contains("/vol/vol2/lun1"));
        }

        // ------------------------------------------------------------------ scope helpers

        @Test
        void consistencyGroupScopeFromPoolDetails_trimsValues_andTreatsBlankUuidAsMissing() {
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.STORAGE_IP, " 10.0.0.1 ");
            details.put(OntapStorageConstants.SVM_NAME, " svm1 ");
            details.put(OntapStorageConstants.SVM_UUID, "   ");

            OntapVMSnapshotStrategy.ConsistencyGroupScope scope = strategy.consistencyGroupScopeFromPoolDetails(details, POOL_ID);

            assertEquals("10.0.0.1|svm1", scope.identityKey());
            assertNull(scope.svmUuid);
        }

        @Test
        void consistencyGroupScopeFromPoolDetails_requiresStorageIpAndSvmName() {
            Map<String, String> noIp = new HashMap<>();
            noIp.put(OntapStorageConstants.SVM_NAME, "svm1");
            Map<String, String> noSvm = new HashMap<>();
            noSvm.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");

            assertTrue(assertThrows(CloudRuntimeException.class,
                    () -> strategy.consistencyGroupScopeFromPoolDetails(noIp, POOL_ID)).getMessage().contains("management IP"));
            assertTrue(assertThrows(CloudRuntimeException.class,
                    () -> strategy.consistencyGroupScopeFromPoolDetails(noSvm, POOL_ID)).getMessage().contains("SVM name"));
        }

        @Test
        void resolveConsistencyGroupScope_returnsSharedScope() {
            Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = new LinkedHashMap<>();
            groups.put("fv-1", group(POOL_ID, "10.0.0.1", "svm1", "svm-uuid"));
            groups.put("fv-2", group(OTHER_POOL_ID, "10.0.0.1", "svm1-alias", "svm-uuid"));

            OntapVMSnapshotStrategy.ConsistencyGroupScope scope = strategy.resolveConsistencyGroupScope(groups);

            assertEquals("10.0.0.1|svm-uuid", scope.identityKey());
        }

        @Test
        void resolveConsistencyGroupScope_rejectsVolumesFromDifferentSvmsOrClusters() {
            Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> differentSvm = new LinkedHashMap<>();
            differentSvm.put("fv-1", group(POOL_ID, "10.0.0.1", "svm1", null));
            differentSvm.put("fv-2", group(OTHER_POOL_ID, "10.0.0.1", "svm2", null));
            Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> differentCluster = new LinkedHashMap<>();
            differentCluster.put("fv-1", group(POOL_ID, "10.0.0.1", "vs0", null));
            differentCluster.put("fv-2", group(OTHER_POOL_ID, "10.0.0.2", "vs0", null));

            assertThrows(CloudRuntimeException.class, () -> strategy.resolveConsistencyGroupScope(differentSvm));
            assertThrows(CloudRuntimeException.class, () -> strategy.resolveConsistencyGroupScope(differentCluster));
        }

        @Test
        void resolveConsistencyGroupScope_returnsNull_forNoGroups() {
            assertNull(strategy.resolveConsistencyGroupScope(new HashMap<>()));
        }

        @Test
        void consistencyGroupScope_buildsOntapReferencesByUuidWhenAvailableOtherwiseName() {
            OntapVMSnapshotStrategy.ConsistencyGroupScope byUuid =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", "svm-uuid");
            OntapVMSnapshotStrategy.ConsistencyGroupScope byName =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", null);

            assertEquals(Map.of("uuid", "svm-uuid"), byUuid.toOntapSvmReference());
            assertEquals(Map.of("name", "svm1"), byName.toOntapSvmReference());
            assertEquals("svm-uuid", byUuid.toOntapSvm().getUuid());
            assertNull(byUuid.toOntapSvm().getName());
            assertEquals("svm1", byName.toOntapSvm().getName());
            assertNull(byName.toOntapSvm().getUuid());

            Map<String, Object> uuidQuery = new HashMap<>();
            byUuid.applySvmQueryFilter(uuidQuery);
            Map<String, Object> nameQuery = new HashMap<>();
            byName.applySvmQueryFilter(nameQuery);
            assertEquals(Map.of("svm.uuid", "svm-uuid"), uuidQuery);
            assertEquals(Map.of("svm.name", "svm1"), nameQuery);

            assertEquals("storageIP=10.0.0.1, svmUUID=svm-uuid, svmName=svm1", byUuid.toString());
            assertEquals("storageIP=10.0.0.1, svmName=svm1", byName.toString());
        }

        @Test
        void consistencyGroupScope_matchesOnlyIdenticalIdentity() {
            OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", null);

            assertTrue(scope.matches(new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", null)));
            assertFalse(scope.matches(new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", "uuid")));
            assertFalse(scope.matches(null));
        }

        // ------------------------------------------------------------------ CG operations

        @Test
        void buildTemporaryConsistencyGroupName_usesPrefixAndSnapshotId() {
            assertEquals(OntapStorageConstants.ONTAP_TEMP_CG_PREFIX + SNAPSHOT_ID,
                    strategy.buildTemporaryConsistencyGroupName(vmSnapshot()));
        }

        @Test
        void createTemporaryConsistencyGroup_requiresScope() {
            assertThrows(CloudRuntimeException.class, () -> strategy.createTemporaryConsistencyGroup(
                    snapshotClient, storageStrategy, AUTH, "cg", null, new LinkedHashSet<>(List.of("fv-1"))));
            verify(snapshotClient, never()).createConsistencyGroup(any(), any());
        }

        @Test
        void createTemporaryConsistencyGroup_createsGroupWithAllFlexVols_andReturnsResolvedUuid() {
            OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", null);
            JobResponse job = new JobResponse();
            when(snapshotClient.createConsistencyGroup(eq(AUTH), any(ConsistencyGroup.class))).thenReturn(job);
            ConsistencyGroup found = new ConsistencyGroup("cg");
            found.setUuid("cg-uuid");
            OntapResponse<ConsistencyGroup> groups = new OntapResponse<>();
            groups.setRecords(List.of(found));
            when(snapshotClient.getConsistencyGroups(eq(AUTH), anyMap())).thenReturn(groups);

            String uuid = strategy.createTemporaryConsistencyGroup(snapshotClient, storageStrategy, AUTH, "cg", scope,
                    new LinkedHashSet<>(List.of("fv-1", "fv-2")));

            assertEquals("cg-uuid", uuid);
            org.mockito.ArgumentCaptor<ConsistencyGroup> payload = org.mockito.ArgumentCaptor.forClass(ConsistencyGroup.class);
            verify(snapshotClient).createConsistencyGroup(eq(AUTH), payload.capture());
            assertEquals("cg", payload.getValue().getName());
            assertEquals("svm1", payload.getValue().getSvm().getName());
            assertEquals(2, payload.getValue().getVolumes().size());
            assertEquals("fv-1", payload.getValue().getVolumes().get(0).getUuid());
            verify(storageStrategy).pollJobIfPresent(eq(job), anyString());
        }

        @Test
        void createTemporaryConsistencyGroup_fails_whenCreatedGroupCannotBeResolved() {
            OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", null);
            when(snapshotClient.getConsistencyGroups(eq(AUTH), anyMap())).thenReturn(new OntapResponse<>());

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.createTemporaryConsistencyGroup(snapshotClient, storageStrategy, AUTH, "cg", scope,
                            new LinkedHashSet<>(List.of("fv-1"))));

            assertTrue(ex.getMessage().contains("Unable to resolve temporary consistency group UUID"), ex.getMessage());
        }

        @Test
        void resolveConsistencyGroupUuidByName_handlesMissingRecords() {
            OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", "svm-uuid");
            OntapResponse<ConsistencyGroup> nullRecord = new OntapResponse<>();
            nullRecord.setRecords(Collections.singletonList(null));
            OntapResponse<ConsistencyGroup> empty = new OntapResponse<>();
            empty.setRecords(Collections.emptyList());
            when(snapshotClient.getConsistencyGroups(eq(AUTH), anyMap()))
                    .thenReturn(null)
                    .thenReturn(empty)
                    .thenReturn(nullRecord);

            assertNull(strategy.resolveConsistencyGroupUuidByName(snapshotClient, AUTH, "cg", scope));
            assertNull(strategy.resolveConsistencyGroupUuidByName(snapshotClient, AUTH, "cg", scope));
            assertNull(strategy.resolveConsistencyGroupUuidByName(snapshotClient, AUTH, "cg", scope));
        }

        @Test
        void startConsistencyGroupSnapshot_extractsSnapshotUuidFromJobDescription() {
            Job completed = new Job();
            completed.setDescription("POST /api/application/consistency-groups/cg-uuid/snapshots/cg-snap-uuid?action=start");
            when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), anyString())).thenReturn(completed);

            assertEquals("cg-snap-uuid",
                    strategy.startConsistencyGroupSnapshot(snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));

            org.mockito.ArgumentCaptor<ConsistencyGroupSnapshot> payload =
                    org.mockito.ArgumentCaptor.forClass(ConsistencyGroupSnapshot.class);
            verify(snapshotClient).createConsistencyGroupSnapshot(eq(AUTH), eq("cg-uuid"), payload.capture());
            assertEquals("snap", payload.getValue().getName());
            assertEquals("start", payload.getValue().getAction());
        }

        @Test
        void startConsistencyGroupSnapshot_returnsNull_whenJobGivesNoUuid() {
            Job withoutUuid = new Job();
            withoutUuid.setDescription("POST /api/something/else");
            when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), anyString()))
                    .thenReturn(null)
                    .thenReturn(withoutUuid);

            assertNull(strategy.startConsistencyGroupSnapshot(snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));
            assertNull(strategy.startConsistencyGroupSnapshot(snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));
        }

        @Test
        void commitAndDeleteConsistencyGroup_pollTheirJobs() {
            JobResponse commitJob = new JobResponse();
            JobResponse deleteJob = new JobResponse();
            when(snapshotClient.commitConsistencyGroupSnapshot(eq(AUTH), eq("cg-uuid"), eq("snap-uuid"), any()))
                    .thenReturn(commitJob);
            when(snapshotClient.deleteConsistencyGroup(AUTH, "cg-uuid")).thenReturn(deleteJob);

            strategy.commitConsistencyGroupSnapshot(snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap-uuid");
            strategy.deleteTemporaryConsistencyGroup(snapshotClient, storageStrategy, AUTH, "cg-uuid");

            org.mockito.ArgumentCaptor<ConsistencyGroupSnapshot> commitPayload =
                    org.mockito.ArgumentCaptor.forClass(ConsistencyGroupSnapshot.class);
            verify(snapshotClient).commitConsistencyGroupSnapshot(eq(AUTH), eq("cg-uuid"), eq("snap-uuid"), commitPayload.capture());
            assertEquals("commit", commitPayload.getValue().getAction());

            verify(storageStrategy).pollJobIfPresent(eq(commitJob), anyString());
            verify(storageStrategy).pollJobIfPresent(eq(deleteJob), anyString());
        }

        @Test
        void resolveStartedConsistencyGroupSnapshotUuid_prefersUuidFromJob() {
            doReturn("from-job").when(strategy).startConsistencyGroupSnapshot(any(), any(), any(), any(), any());

            assertEquals("from-job", strategy.resolveStartedConsistencyGroupSnapshotUuid(
                    snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));
            verify(strategy, never()).resolveConsistencyGroupSnapshotUuid(any(), any(), any(), any(), any());
        }

        @Test
        void resolveStartedConsistencyGroupSnapshotUuid_fallsBackToLookup_whenJobGivesNone() {
            doReturn(null).doReturn("").when(strategy).startConsistencyGroupSnapshot(any(), any(), any(), any(), any());
            doReturn("from-lookup").when(strategy).resolveConsistencyGroupSnapshotUuid(any(), any(), any(), any(), any());

            assertEquals("from-lookup", strategy.resolveStartedConsistencyGroupSnapshotUuid(
                    snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));
            assertEquals("from-lookup", strategy.resolveStartedConsistencyGroupSnapshotUuid(
                    snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));
        }

        @Test
        void resolveConsistencyGroupSnapshotUuid_returnsOnFirstSuccessfulLookup() {
            doReturn("cg-snap-uuid").when(strategy).lookupConsistencyGroupSnapshotUuid(any(), any(), any(), any());

            assertEquals("cg-snap-uuid", strategy.resolveConsistencyGroupSnapshotUuid(
                    snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));
            verify(strategy, times(1)).lookupConsistencyGroupSnapshotUuid(any(), any(), any(), any());
        }

        @Test
        void resolveConsistencyGroupSnapshotUuid_restoresInterruptFlag_whenInterruptedWhileWaiting() {
            doReturn(null).when(strategy).lookupConsistencyGroupSnapshotUuid(any(), any(), any(), any());
            Thread.currentThread().interrupt();
            try {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () ->
                        strategy.resolveConsistencyGroupSnapshotUuid(snapshotClient, storageStrategy, AUTH, "cg-uuid", "snap"));

                assertTrue(ex.getMessage().contains("Interrupted"), ex.getMessage());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        void lookupConsistencyGroupSnapshotUuid_matchesByNameFirst() {
            when(snapshotClient.getConsistencyGroupSnapshots(eq(AUTH), eq("cg-uuid"), anyMap()))
                    .thenReturn(cgSnapshots(cgSnapshot("snap", "uuid-1")));

            assertEquals("uuid-1", strategy.lookupConsistencyGroupSnapshotUuid(snapshotClient, AUTH, "cg-uuid", "snap"));
            verify(snapshotClient, times(1)).getConsistencyGroupSnapshots(eq(AUTH), eq("cg-uuid"), anyMap());
        }

        @Test
        void lookupConsistencyGroupSnapshotUuid_fallsBackToListingAllSnapshots() {
            when(snapshotClient.getConsistencyGroupSnapshots(eq(AUTH), eq("cg-uuid"), anyMap()))
                    .thenReturn(cgSnapshots())
                    .thenReturn(cgSnapshots(cgSnapshot("other", "uuid-0"), cgSnapshot("snap", "uuid-1")));

            assertEquals("uuid-1", strategy.lookupConsistencyGroupSnapshotUuid(snapshotClient, AUTH, "cg-uuid", "snap"));
            verify(snapshotClient, times(2)).getConsistencyGroupSnapshots(eq(AUTH), eq("cg-uuid"), anyMap());
        }

        @Test
        void lookupConsistencyGroupSnapshotUuid_returnsNull_whenNoUsableMatch() {
            when(snapshotClient.getConsistencyGroupSnapshots(eq(AUTH), eq("cg-uuid"), anyMap()))
                    .thenReturn(null)
                    .thenReturn(cgSnapshots(cgSnapshot("other", "uuid-0"), cgSnapshot("snap", ""), cgSnapshot("snap", null)));

            assertNull(strategy.lookupConsistencyGroupSnapshotUuid(snapshotClient, AUTH, "cg-uuid", "snap"));
        }

        @Test
        void resolveSnapshotUuid_returnsUuidOfFirstRecord() {
            FlexVolSnapshot record = new FlexVolSnapshot("snap", "comment");
            record.setUuid("snap-uuid");
            OntapResponse<FlexVolSnapshot> response = new OntapResponse<>();
            response.setRecords(List.of(record));
            when(snapshotClient.getSnapshots(eq(AUTH), eq("fv-1"), anyMap())).thenReturn(response);

            assertEquals("snap-uuid", strategy.resolveSnapshotUuid(snapshotClient, AUTH, "fv-1", "snap"));
        }

        @Test
        void resolveSnapshotUuid_failsWhenSnapshotNotVisibleAfterCreation() {
            OntapResponse<FlexVolSnapshot> empty = new OntapResponse<>();
            empty.setRecords(Collections.emptyList());
            when(snapshotClient.getSnapshots(eq(AUTH), eq("fv-1"), anyMap()))
                    .thenReturn(null)
                    .thenReturn(empty);

            for (int i = 0; i < 2; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> strategy.resolveSnapshotUuid(snapshotClient, AUTH, "fv-1", "snap"));
                assertTrue(ex.getMessage().contains("Could not find FlexVolume snapshot"), ex.getMessage());
            }
        }

        // ------------------------------------------------------------------ groupVolumesByFlexVol

        @Test
        void groupVolumesByFlexVol_groupsVolumesSharingAFlexVol() {
            VolumeObjectTO to1 = mock(VolumeObjectTO.class);
            when(to1.getId()).thenReturn(1L);
            VolumeObjectTO to2 = mock(VolumeObjectTO.class);
            when(to2.getId()).thenReturn(2L);
            VolumeVO vol1 = mock(VolumeVO.class);
            when(vol1.getId()).thenReturn(1L);
            when(vol1.getPoolId()).thenReturn(POOL_ID);
            VolumeVO vol2 = mock(VolumeVO.class);
            when(vol2.getId()).thenReturn(2L);
            when(vol2.getPoolId()).thenReturn(POOL_ID);
            when(volumeDao.findById(1L)).thenReturn(vol1);
            when(volumeDao.findById(2L)).thenReturn(vol2);
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.VOLUME_UUID, "fv-1");
            when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(details);

            Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = strategy.groupVolumesByFlexVol(List.of(to1, to2));

            assertEquals(1, groups.size());
            assertEquals(List.of(1L, 2L), groups.get("fv-1").volumeIds);
            assertEquals(POOL_ID, groups.get("fv-1").poolId);
        }

        @Test
        void groupVolumesByFlexVol_failsForUnknownVolumeOrMissingFlexVolUuid() {
            VolumeObjectTO unknown = mock(VolumeObjectTO.class);
            when(unknown.getId()).thenReturn(1L);
            when(volumeDao.findById(1L)).thenReturn(null);
            VolumeObjectTO noPool = mock(VolumeObjectTO.class);
            when(noPool.getId()).thenReturn(2L);
            VolumeVO noPoolVolume = mock(VolumeVO.class);
            when(noPoolVolume.getPoolId()).thenReturn(null);
            when(volumeDao.findById(2L)).thenReturn(noPoolVolume);
            VolumeObjectTO noFlexVol = mock(VolumeObjectTO.class);
            when(noFlexVol.getId()).thenReturn(3L);
            VolumeVO noFlexVolVolume = mock(VolumeVO.class);
            when(noFlexVolVolume.getPoolId()).thenReturn(POOL_ID);
            when(volumeDao.findById(3L)).thenReturn(noFlexVolVolume);
            when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(new HashMap<>());

            assertThrows(CloudRuntimeException.class, () -> strategy.groupVolumesByFlexVol(List.of(unknown)));
            assertThrows(CloudRuntimeException.class, () -> strategy.groupVolumesByFlexVol(List.of(noPool)));
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.groupVolumesByFlexVol(List.of(noFlexVol)));
            assertTrue(ex.getMessage().contains("FlexVolume UUID not found"), ex.getMessage());
        }

        // ------------------------------------------------------------------ single-FlexVol create negatives

        @Test
        void createVmSnapshotsViaSingleFlexVol_failsWhenOntapReturnsNoJob() {
            VMSnapshotVO snapshot = vmSnapshot();
            UserVmVO userVm = mock(UserVmVO.class);
            when(userVm.getInstanceName()).thenReturn("i-2-100-VM");
            doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
            when(storageStrategy.getSnapshotFeignClient()).thenReturn(snapshotClient);
            when(storageStrategy.getAuthHeader()).thenReturn(AUTH);
            when(snapshotClient.createSnapshot(eq(AUTH), eq("fv-1"), any(FlexVolSnapshot.class))).thenReturn(null);
            Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = new LinkedHashMap<>();
            groups.put("fv-1", group(POOL_ID, "10.0.0.1", "svm1", null));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () ->
                    strategy.createVmSnapshotsViaSingleFlexVol(snapshot, userVm, groups, "snap", new java.util.ArrayList<>()));

            assertTrue(ex.getMessage().contains("Failed to initiate FlexVolume snapshot"), ex.getMessage());
        }

        @Test
        void createVmSnapshotsViaSingleFlexVol_failsWhenSnapshotJobDoesNotSucceed() {
            VMSnapshotVO snapshot = vmSnapshot();
            UserVmVO userVm = mock(UserVmVO.class);
            when(userVm.getInstanceName()).thenReturn("i-2-100-VM");
            doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
            when(storageStrategy.getSnapshotFeignClient()).thenReturn(snapshotClient);
            when(storageStrategy.getAuthHeader()).thenReturn(AUTH);
            JobResponse response = new JobResponse();
            Job job = new Job();
            job.setUuid("job-1");
            response.setJob(job);
            when(snapshotClient.createSnapshot(eq(AUTH), eq("fv-1"), any(FlexVolSnapshot.class))).thenReturn(response);
            when(storageStrategy.jobPollForSuccess(eq("job-1"), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
            Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = new LinkedHashMap<>();
            groups.put("fv-1", group(POOL_ID, "10.0.0.1", "svm1", null));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () ->
                    strategy.createVmSnapshotsViaSingleFlexVol(snapshot, userVm, groups, "snap", new java.util.ArrayList<>()));

            assertTrue(ex.getMessage().contains("snapshot job failed"), ex.getMessage());
            verify(snapshotClient, never()).getSnapshots(any(), any(), anyMap());
        }

        // ------------------------------------------------------------------ canHandle / eligibility edge cases

        private void stubVmWithVolume(VirtualMachine.State vmState, Long poolId, StoragePoolVO pool) {
            UserVmVO userVm = mock(UserVmVO.class);
            when(userVm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
            when(userVm.getState()).thenReturn(vmState);
            when(userVmDao.findById(VM_ID)).thenReturn(userVm);
            VolumeVO volume = mock(VolumeVO.class);
            when(volume.getId()).thenReturn(1L);
            when(volume.getPoolId()).thenReturn(poolId);
            when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume));
            if (poolId != null) {
                when(storagePool.findById(poolId)).thenReturn(pool);
            }
        }

        @Test
        void canHandleVm_rejectsVmsThatAreNeitherRunningNorStopped() {
            stubVmWithVolume(VirtualMachine.State.Migrating, POOL_ID, mock(StoragePoolVO.class));

            assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(VM_ID, POOL_ID, false));
            verify(volumeDao, never()).findByInstance(anyLong());
        }

        @Test
        void canHandleVm_rejectsVolumeWithoutPool() {
            stubVmWithVolume(VirtualMachine.State.Running, null, null);

            assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(VM_ID, POOL_ID, false));
        }

        @Test
        void canHandleVm_rejectsVolumeWhosePoolNoLongerExists() {
            stubVmWithVolume(VirtualMachine.State.Running, POOL_ID, null);

            assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(VM_ID, POOL_ID, false));
        }

        @Test
        void canHandleVm_rejectsMemorySnapshotsWithoutLookingUpTheVm() {
            assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(VM_ID, POOL_ID, true));
            verify(userVmDao, never()).findById(anyLong());
        }

        @Test
        void canHandleSnapshot_rejectsExistingSnapshotWhenVolumesMovedOffOntap() {
            VMSnapshotVO existing = vmSnapshot();
            when(existing.getState()).thenReturn(VMSnapshot.State.Ready);
            VMSnapshotDetailsVO hoistedRow4 = detailRow(1L, "x");
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(List.of(hoistedRow4));
            when(userVmDao.findById(VM_ID)).thenReturn(null);

            assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(existing));
        }

        @Test
        void canHandleSnapshot_handlesExistingSnapshotWithOntapDetailsWhenVolumesStillOnOntap() {
            VMSnapshotVO existing = vmSnapshot();
            when(existing.getState()).thenReturn(VMSnapshot.State.Ready);
            VMSnapshotDetailsVO hoistedRow5 = detailRow(1L, "x");
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(List.of(hoistedRow5));
            StoragePoolVO pool = mock(StoragePoolVO.class);
            when(pool.isManaged()).thenReturn(true);
            when(pool.getStorageProviderName()).thenReturn(OntapStorageConstants.ONTAP_PLUGIN_NAME);
            stubVmWithVolume(VirtualMachine.State.Running, POOL_ID, pool);

            assertEquals(StrategyPriority.HIGHEST, strategy.canHandle(existing));
        }

        @Test
        void canHandleSnapshot_rejectsExistingSnapshotWithNoOntapOrLegacyDetails() {
            VMSnapshotVO existing = vmSnapshot();
            when(existing.getState()).thenReturn(VMSnapshot.State.Ready);
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(Collections.emptyList());
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, StorageVMSnapshotStrategy.STORAGE_SNAPSHOT))
                    .thenReturn(Collections.emptyList());

            assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(existing));
        }

        @Test
        void canHandleSnapshot_acceptsLegacyStorageSnapshotsWhileVolumesAreOnOntap() {
            VMSnapshotVO existing = vmSnapshot();
            when(existing.getState()).thenReturn(VMSnapshot.State.Ready);
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                    .thenReturn(Collections.emptyList());
            VMSnapshotDetailsVO hoistedRow6 = detailRow(1L, "legacy");
            when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, StorageVMSnapshotStrategy.STORAGE_SNAPSHOT))
                    .thenReturn(List.of(hoistedRow6));
            StoragePoolVO pool = mock(StoragePoolVO.class);
            when(pool.isManaged()).thenReturn(true);
            when(pool.getStorageProviderName()).thenReturn(OntapStorageConstants.ONTAP_PLUGIN_NAME);
            stubVmWithVolume(VirtualMachine.State.Stopped, POOL_ID, pool);

            assertEquals(StrategyPriority.HIGHEST, strategy.canHandle(existing));
        }

        @Test
        void resolveConsistencyGroupUuidByName_queriesByNameAndSvmUuid_whenUuidIsKnown() {
            OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", "svm-uuid");
            ConsistencyGroup found = new ConsistencyGroup("cg");
            found.setUuid("cg-uuid");
            OntapResponse<ConsistencyGroup> groups = new OntapResponse<>();
            groups.setRecords(List.of(found));
            when(snapshotClient.getConsistencyGroups(eq(AUTH), anyMap())).thenReturn(groups);

            assertEquals("cg-uuid", strategy.resolveConsistencyGroupUuidByName(snapshotClient, AUTH, "cg", scope));

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Map<String, Object>> query = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(snapshotClient).getConsistencyGroups(eq(AUTH), query.capture());
            assertEquals("cg", query.getValue().get("name"));
            assertEquals("svm-uuid", query.getValue().get("svm.uuid"));
            assertFalse(query.getValue().containsKey("svm.name"));
            assertEquals("uuid,name", query.getValue().get("fields"));
        }

        @Test
        void resolveConsistencyGroupUuidByName_queriesBySvmName_whenUuidIsUnknown() {
            OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                    new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "svm1", null);
            when(snapshotClient.getConsistencyGroups(eq(AUTH), anyMap())).thenReturn(new OntapResponse<>());

            assertNull(strategy.resolveConsistencyGroupUuidByName(snapshotClient, AUTH, "cg", scope));

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Map<String, Object>> query = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(snapshotClient).getConsistencyGroups(eq(AUTH), query.capture());
            assertEquals("svm1", query.getValue().get("svm.name"));
            assertFalse(query.getValue().containsKey("svm.uuid"));
        }

        @Test
        void resolveStorageStrategy_delegatesToStorageUtilsWithTheGivenPoolDetails() {
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.SVM_NAME, "svm1");
            try (MockedStatic<OntapStorageUtils> utils = Mockito.mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(details)).thenReturn(storageStrategy);

                assertSame(storageStrategy, strategy.resolveStorageStrategy(details));

                utils.verify(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(details));
            }
        }
    }
}
