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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
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

    private void setupTwoVolumesSameFlexVolForTakeSnapshot() {
        setupSingleVolumeForTakeSnapshot();
        VolumeObjectTO volumeTO1 = mock(VolumeObjectTO.class);
        when(volumeTO1.getId()).thenReturn(VOLUME_ID_1);
        when(volumeTO1.getSize()).thenReturn(10737418240L);
        VolumeObjectTO volumeTO2 = mock(VolumeObjectTO.class);
        when(volumeTO2.getId()).thenReturn(VOLUME_ID_2);
        when(volumeTO2.getSize()).thenReturn(10737418240L);
        when(vmSnapshotHelper.getVolumeTOList(VM_ID)).thenReturn(Arrays.asList(volumeTO1, volumeTO2));

        VolumeVO volumeVO2 = mock(VolumeVO.class);
        when(volumeVO2.getId()).thenReturn(VOLUME_ID_2);
        when(volumeVO2.getPoolId()).thenReturn(POOL_ID_1);
        when(volumeVO2.getPath()).thenReturn("volume-302.qcow2");
        when(volumeVO2.getVmSnapshotChainSize()).thenReturn(0L);
        when(volumeDao.findById(VOLUME_ID_2)).thenReturn(volumeVO2);

        VolumeInfo volumeInfo2 = mock(VolumeInfo.class);
        when(volumeInfo2.getId()).thenReturn(VOLUME_ID_2);
        when(volumeDataFactory.getVolume(VOLUME_ID_2)).thenReturn(volumeInfo2);
    }

    private Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> singleFlexVolGroup() {
        setupSingleVolumeForTakeSnapshot();
        VolumeObjectTO volumeTO = mock(VolumeObjectTO.class);
        when(volumeTO.getId()).thenReturn(VOLUME_ID_1);
        return strategy.groupVolumesByFlexVol(Collections.singletonList(volumeTO));
    }

    private UserVmVO mockSnapshotUser(String instanceName) {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(VM_ID);
        when(userVm.getInstanceName()).thenReturn(instanceName);
        when(userVm.getGuestOSId()).thenReturn(GUEST_OS_ID);
        when(userVm.getUuid()).thenReturn(VM_UUID);
        return userVm;
    }

    private VMSnapshotVO mockDeleteOrRevertSnapshot() throws Exception {
        VMSnapshotVO vmSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(vmSnapshot.getVmId()).thenReturn(VM_ID);
        when(vmSnapshot.getName()).thenReturn("vm-snap-1");
        when(vmSnapshot.getType()).thenReturn(VMSnapshot.Type.Disk);
        when(vmSnapshot.getCreated()).thenReturn(new Date());
        when(vmSnapshot.getDescription()).thenReturn("Test ONTAP VM Snapshot");
        when(vmSnapshot.getCurrent()).thenReturn(Boolean.FALSE);

        UserVmVO userVm = mockSnapshotUser(VM_INSTANCE_NAME);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        GuestOSVO guestOS = mock(GuestOSVO.class);
        when(guestOS.getDisplayName()).thenReturn("CentOS 8");
        when(guestOSDao.findById(GUEST_OS_ID)).thenReturn(guestOS);

        VMSnapshotTO parentChain = mock(VMSnapshotTO.class);
        when(vmSnapshotHelper.getSnapshotWithParents(vmSnapshot)).thenReturn(parentChain);
        when(parentChain.getParent()).thenReturn(null);

        VolumeObjectTO volumeTO = mock(VolumeObjectTO.class);
        when(volumeTO.getSize()).thenReturn(1024L);
        when(vmSnapshotHelper.getVolumeTOList(VM_ID)).thenReturn(Collections.singletonList(volumeTO));

        doNothing().when(strategy).processAnswer(any(), any(), any(), any());
        doNothing().when(strategy).publishUsageEvent(any(), any(), any(), any());
        doNothing().when(strategy).publishUsageEvent(any(), any(), any(), anyLong(), anyLong());
        doNothing().when(strategy).deleteDiskSnapshot(any());
        doNothing().when(strategy).revertDiskSnapshot(any());
        doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(new HashMap<>());
        return vmSnapshot;
    }

    private VMSnapshotDetailsVO flexDetail(long id, String value) {
        VMSnapshotDetailsVO detail = mock(VMSnapshotDetailsVO.class);
        when(detail.getId()).thenReturn(id);
        when(detail.getValue()).thenReturn(value);
        lenient().when(detail.getName()).thenReturn(OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT);
        return detail;
    }

    private OntapResponse<ConsistencyGroupSnapshot> cgSnapshotResponse(String name, String uuid) {
        OntapResponse<ConsistencyGroupSnapshot> response = new OntapResponse<>();
        ConsistencyGroupSnapshot record = new ConsistencyGroupSnapshot();
        record.setName(name);
        record.setUuid(uuid);
        response.setRecords(Collections.singletonList(record));
        return response;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Additional coverage: selection, take, delete, revert, CG helpers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void testConfigure_returnsTrue() throws Exception {
        org.apache.cloudstack.framework.config.dao.ConfigurationDao configurationDao =
                mock(org.apache.cloudstack.framework.config.dao.ConfigurationDao.class);
        when(configurationDao.getValue("vmsnapshot.create.wait")).thenReturn("1800");
        strategy.configurationDao = configurationDao;

        assertTrue(strategy.configure("ontap-vm-snapshot", Collections.emptyMap()));
    }

    @Test
    void testCanHandle_AllocatedDiskAndMemory_AllVolumesOnOntap_ReturnsCantHandle() {
        setupAllVolumesOnOntap();
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.DiskAndMemory);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(vmSnapshot));
    }

    @Test
    void testCanHandle_VmStarting_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Starting);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(vmSnapshot));
    }

    @Test
    void testCanHandle_NullVolumeList_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(null);
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Allocated, VMSnapshot.Type.Disk);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(vmSnapshot));
    }

    @Test
    void testCanHandle_NonAllocated_LegacyDetailsPresentButVolumesLeftOntap_ReturnsCantHandle() {
        UserVmVO userVm = createMockUserVm(Hypervisor.HypervisorType.KVM, VirtualMachine.State.Running);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.emptyList());
        VMSnapshotVO vmSnapshot = createMockVmSnapshot(VMSnapshot.State.Ready, VMSnapshot.Type.Disk);
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT)).thenReturn(null);
        List<VMSnapshotDetailsVO> legacy = new ArrayList<>();
        legacy.add(new VMSnapshotDetailsVO(SNAPSHOT_ID, "kvmStorageSnapshot", "123", true));
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot")).thenReturn(legacy);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(vmSnapshot));
    }

    @Test
    void testGroupVolumesByFlexVol_NullPoolId_ThrowsException() {
        VolumeObjectTO volumeTO = mock(VolumeObjectTO.class);
        when(volumeTO.getId()).thenReturn(VOLUME_ID_1);
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(VOLUME_ID_1);
        when(volume.getPoolId()).thenReturn(null);
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(volume);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.groupVolumesByFlexVol(Collections.singletonList(volumeTO)));
        assertTrue(ex.getMessage().contains("no pool"));
    }

    @Test
    void testGroupVolumesByFlexVol_BlankFlexVolUuid_ThrowsException() {
        VolumeObjectTO volumeTO = mock(VolumeObjectTO.class);
        when(volumeTO.getId()).thenReturn(VOLUME_ID_1);
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(VOLUME_ID_1);
        when(volume.getPoolId()).thenReturn(POOL_ID_1);
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(volume);
        Map<String, String> poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.VOLUME_UUID, "");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails);

        assertThrows(CloudRuntimeException.class,
                () -> strategy.groupVolumesByFlexVol(Collections.singletonList(volumeTO)));
    }

    @Test
    void testResolveStorageStrategy_delegatesToOntapStorageUtils() {
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");
        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(details)).thenReturn(storageStrategy);
            assertSame(storageStrategy, strategy.resolveStorageStrategy(details));
        }
    }

    @Test
    void testBuildTemporaryConsistencyGroupName_usesSnapshotId() {
        VMSnapshotVO vmSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshot.getId()).thenReturn(SNAPSHOT_ID);
        assertEquals(OntapStorageConstants.ONTAP_TEMP_CG_PREFIX + SNAPSHOT_ID,
                strategy.buildTemporaryConsistencyGroupName(vmSnapshot));
    }

    @Test
    void testTakeVMSnapshot_StoppedVmWithNullOptions_SkipsFreezeAndUsesChainSize() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        UserVmVO userVm = setupTakeSnapshotCommon(vmSnapshot);
        when(userVm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vmSnapshot.getOptions()).thenReturn(null);
        setupSingleVolumeForTakeSnapshot();
        VolumeVO volume = volumeDao.findById(VOLUME_ID_1);
        when(volume.getVmSnapshotChainSize()).thenReturn(5L);
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));

        assertSame(vmSnapshot, strategy.takeVMSnapshot(vmSnapshot));

        verify(agentMgr, never()).send(eq(HOST_ID), any(FreezeThawVMCommand.class));
        verify(vmSnapshot).setParent(null);
    }

    @Test
    void testTakeVMSnapshot_PersistFails_DedupesRollbackAndRemovesFlexVolDetail() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupTwoVolumesSameFlexVolForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));
        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        FreezeThawVMAnswer thawAnswer = mock(FreezeThawVMAnswer.class);
        when(thawAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenReturn(thawAnswer);
        doThrow(new CloudRuntimeException("snapshot persistence failed")).when(vmSnapshotDetailsDao)
                .persist(any(VMSnapshotDetailsVO.class));
        VMSnapshotDetailsVO flex = flexDetail(11L, "ignored");
        VMSnapshotDetailsVO other = mock(VMSnapshotDetailsVO.class);
        when(other.getId()).thenReturn(12L);
        when(other.getName()).thenReturn("kvmStorageSnapshot");
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Arrays.asList(flex, other));
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        assertEquals("snapshot persistence failed", ex.getMessage());
        verify(storageStrategy, times(1)).deleteFlexVolSnapshotForCloudStackVolume(eq("flexvol-uuid-1"), any(), any());
        verify(vmSnapshotDetailsDao).remove(11L);
        verify(vmSnapshotDetailsDao, never()).remove(12L);
    }

    @Test
    void testTakeVMSnapshot_CleanupRetriesThawAndStateTransitionFails_PreservesOriginalError() throws Exception {
        VMSnapshotVO vmSnapshot = createTakeSnapshotVmSnapshot();
        setupTakeSnapshotCommon(vmSnapshot);
        setupTwoVolumesSameFlexVolForTakeSnapshot();
        setupSingleFlexVolFlowMocks(strategy.buildSnapshotName(vmSnapshot));
        FreezeThawVMAnswer freezeAnswer = mock(FreezeThawVMAnswer.class);
        when(freezeAnswer.getResult()).thenReturn(true);
        when(agentMgr.send(eq(HOST_ID), any(FreezeThawVMCommand.class)))
                .thenReturn(freezeAnswer)
                .thenThrow(new AgentUnavailableException(HOST_ID))
                .thenThrow(new OperationTimedoutException(null, 0, 0, 0, false));
        doThrow(new CloudRuntimeException("snapshot persistence failed")).when(vmSnapshotDetailsDao)
                .persist(any(VMSnapshotDetailsVO.class));
        VMSnapshotDetailsVO flex = flexDetail(11L, "ignored");
        when(vmSnapshotDetailsDao.listDetails(SNAPSHOT_ID)).thenReturn(Collections.singletonList(flex));
        doThrow(new NoTransitionException("stuck")).when(vmSnapshotHelper)
                .vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.takeVMSnapshot(vmSnapshot));

        assertEquals("snapshot persistence failed", ex.getMessage());
        verify(storageStrategy, times(1)).deleteFlexVolSnapshotForCloudStackVolume(eq("flexvol-uuid-1"), any(), any());
        verify(agentMgr, times(3)).send(eq(HOST_ID), any(FreezeThawVMCommand.class));
        verify(vmSnapshotDetailsDao).remove(11L);
    }

    @Test
    void testCreateVmSnapshotsViaSingleFlexVol_nullJob_throws() {
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = singleFlexVolGroup();
        setupSingleFlexVolFlowMocks("snap-name");
        when(snapshotFeignClient.createSnapshot(any(), eq("flexvol-uuid-1"), any())).thenReturn(null);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.createVmSnapshotsViaSingleFlexVol(
                createTakeSnapshotVmSnapshot(), mockSnapshotUser(VM_INSTANCE_NAME), groups, "snap-name", new ArrayList<>()));
        assertTrue(ex.getMessage().contains("Failed to initiate"));
    }

    @Test
    void testCreateVmSnapshotsViaSingleFlexVol_jobMissing_throws() {
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = singleFlexVolGroup();
        setupSingleFlexVolFlowMocks("snap-name");
        when(snapshotFeignClient.createSnapshot(any(), eq("flexvol-uuid-1"), any())).thenReturn(new JobResponse());

        assertThrows(CloudRuntimeException.class, () -> strategy.createVmSnapshotsViaSingleFlexVol(
                createTakeSnapshotVmSnapshot(), mockSnapshotUser(VM_INSTANCE_NAME), groups, "snap-name", new ArrayList<>()));
    }

    @Test
    void testCreateVmSnapshotsViaSingleFlexVol_jobPollFalse_throws() {
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = singleFlexVolGroup();
        setupSingleFlexVolFlowMocks("snap-name");
        when(storageStrategy.jobPollForSuccess(any(), anyInt(), anyInt())).thenReturn(Boolean.FALSE);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.createVmSnapshotsViaSingleFlexVol(
                createTakeSnapshotVmSnapshot(), mockSnapshotUser(VM_INSTANCE_NAME), groups, "snap-name", new ArrayList<>()));
        assertTrue(ex.getMessage().contains("job failed"));
    }

    @Test
    void testCreateVmSnapshotsViaSingleFlexVol_snapshotLookupEmpty_throws() {
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups = singleFlexVolGroup();
        setupSingleFlexVolFlowMocks("snap-name");
        OntapResponse<FlexVolSnapshot> empty = new OntapResponse<>();
        empty.setRecords(Collections.emptyList());
        when(snapshotFeignClient.getSnapshots(any(), eq("flexvol-uuid-1"), any())).thenReturn(empty);

        assertThrows(CloudRuntimeException.class, () -> strategy.createVmSnapshotsViaSingleFlexVol(
                createTakeSnapshotVmSnapshot(), mockSnapshotUser(VM_INSTANCE_NAME), groups, "snap-name", new ArrayList<>()));
    }

    @Test
    void testResolveSnapshotUuid_nullOrEmptyResponsesThrow() {
        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveSnapshotUuid(snapshotFeignClient, "auth", "flex", "snap"));
        when(snapshotFeignClient.getSnapshots(any(), eq("flex"), any())).thenReturn(new OntapResponse<>());
        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveSnapshotUuid(snapshotFeignClient, "auth", "flex", "snap"));
        OntapResponse<FlexVolSnapshot> empty = new OntapResponse<>();
        empty.setRecords(Collections.emptyList());
        when(snapshotFeignClient.getSnapshots(any(), eq("flex"), any())).thenReturn(empty);
        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveSnapshotUuid(snapshotFeignClient, "auth", "flex", "snap"));

        OntapResponse<FlexVolSnapshot> found = new OntapResponse<>();
        FlexVolSnapshot snapshot = new FlexVolSnapshot();
        snapshot.setUuid("snap-uuid");
        found.setRecords(Collections.singletonList(snapshot));
        when(snapshotFeignClient.getSnapshots(any(), eq("flex"), any())).thenReturn(found);
        assertEquals("snap-uuid", strategy.resolveSnapshotUuid(snapshotFeignClient, "auth", "flex", "snap"));
    }

    @Test
    void testCreateVmSnapshotsViaTemporaryCg_createFailsBeforeUuid_skipsCleanup() {
        setupMultiFlexVolForTakeSnapshot();
        setupTemporaryCgFlowMocks("snap-name");
        when(snapshotFeignClient.createConsistencyGroup(any(), any()))
                .thenThrow(new CloudRuntimeException("cg create failed"));
        Map<String, OntapVMSnapshotStrategy.FlexVolGroupInfo> groups =
                strategy.groupVolumesByFlexVol(vmSnapshotHelper.getVolumeTOList(VM_ID));

        assertThrows(CloudRuntimeException.class, () -> strategy.createVmSnapshotsViaTemporaryCg(
                createTakeSnapshotVmSnapshot(), mockSnapshotUser(VM_INSTANCE_NAME), groups, "snap-name", new ArrayList<>()));
        verify(snapshotFeignClient, never()).deleteConsistencyGroup(any(), any());
    }

    @Test
    void testConsistencyGroupScopeFromPoolDetails_blankIdentityRejectedAndUuidTrimmed() {
        Map<String, String> details = new HashMap<>();
        assertThrows(CloudRuntimeException.class,
                () -> strategy.consistencyGroupScopeFromPoolDetails(details, POOL_ID_1));
        details.put(OntapStorageConstants.STORAGE_IP, " ");
        assertThrows(CloudRuntimeException.class,
                () -> strategy.consistencyGroupScopeFromPoolDetails(details, POOL_ID_1));
        details.put(OntapStorageConstants.STORAGE_IP, " 10.1.1.1 ");
        assertThrows(CloudRuntimeException.class,
                () -> strategy.consistencyGroupScopeFromPoolDetails(details, POOL_ID_1));
        details.put(OntapStorageConstants.SVM_NAME, " ");
        assertThrows(CloudRuntimeException.class,
                () -> strategy.consistencyGroupScopeFromPoolDetails(details, POOL_ID_1));

        details.put(OntapStorageConstants.SVM_NAME, " vs0 ");
        details.put(OntapStorageConstants.SVM_UUID, " ");
        OntapVMSnapshotStrategy.ConsistencyGroupScope blankUuid =
                strategy.consistencyGroupScopeFromPoolDetails(details, POOL_ID_1);
        assertEquals("10.1.1.1", blankUuid.storageIp);
        assertEquals("vs0", blankUuid.svmName);
        assertNull(blankUuid.svmUuid);

        details.put(OntapStorageConstants.SVM_UUID, "  svm-trim  ");
        OntapVMSnapshotStrategy.ConsistencyGroupScope trimmed =
                strategy.consistencyGroupScopeFromPoolDetails(details, POOL_ID_1);
        assertEquals("svm-trim", trimmed.svmUuid);
    }

    @Test
    void testConsistencyGroupScope_svmReferenceFilterAndToString() {
        OntapVMSnapshotStrategy.ConsistencyGroupScope withUuid =
                new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "vs0", "svm-1");
        OntapVMSnapshotStrategy.ConsistencyGroupScope nameOnly =
                new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "vs0", null);
        OntapVMSnapshotStrategy.ConsistencyGroupScope blankUuid =
                new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "vs0", "");

        assertFalse(withUuid.matches(null));
        assertTrue(withUuid.matches(new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "other", "svm-1")));
        assertEquals("10.0.0.1|svm-1", withUuid.identityKey());
        assertEquals("10.0.0.1|vs0", nameOnly.identityKey());
        assertEquals("10.0.0.1|vs0", blankUuid.identityKey());
        assertEquals("svm-1", withUuid.toOntapSvm().getUuid());
        assertEquals("vs0", nameOnly.toOntapSvm().getName());
        assertEquals("vs0", blankUuid.toOntapSvm().getName());
        assertEquals("svm-1", withUuid.toOntapSvmReference().get("uuid"));
        assertEquals("vs0", nameOnly.toOntapSvmReference().get("name"));

        Map<String, Object> byUuid = new HashMap<>();
        withUuid.applySvmQueryFilter(byUuid);
        assertEquals("svm-1", byUuid.get("svm.uuid"));
        Map<String, Object> byName = new HashMap<>();
        nameOnly.applySvmQueryFilter(byName);
        assertEquals("vs0", byName.get("svm.name"));
        assertTrue(withUuid.toString().contains("svmUUID=svm-1"));
        assertTrue(nameOnly.toString().contains("svmName=vs0"));
        assertTrue(blankUuid.toString().contains("svmName=vs0"));
        assertFalse(blankUuid.toString().contains("svmUUID"));
    }

    @Test
    void testCreateTemporaryConsistencyGroup_nullScopeAndUnresolvedUuid() {
        assertThrows(CloudRuntimeException.class, () -> strategy.createTemporaryConsistencyGroup(
                snapshotFeignClient, storageStrategy, "auth", "cg-name", null, Set.of("flex-1")));

        OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "vs0", "svm-1");
        when(snapshotFeignClient.createConsistencyGroup(any(), any())).thenReturn(createJobResponse("job-cg"));
        when(snapshotFeignClient.getConsistencyGroups(any(), any())).thenReturn(new OntapResponse<>());
        assertThrows(CloudRuntimeException.class, () -> strategy.createTemporaryConsistencyGroup(
                snapshotFeignClient, storageStrategy, "auth", "cg-name", scope, Set.of("flex-1")));

        OntapResponse<ConsistencyGroup> nullRecord = new OntapResponse<>();
        nullRecord.setRecords(Collections.singletonList(null));
        when(snapshotFeignClient.getConsistencyGroups(any(), any())).thenReturn(nullRecord);
        assertThrows(CloudRuntimeException.class, () -> strategy.createTemporaryConsistencyGroup(
                snapshotFeignClient, storageStrategy, "auth", "cg-name", scope, Set.of("flex-1")));

        OntapResponse<ConsistencyGroup> blankUuid = new OntapResponse<>();
        ConsistencyGroup group = new ConsistencyGroup();
        group.setUuid("");
        blankUuid.setRecords(Collections.singletonList(group));
        when(snapshotFeignClient.getConsistencyGroups(any(), any())).thenReturn(blankUuid);
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.createTemporaryConsistencyGroup(
                snapshotFeignClient, storageStrategy, "auth", "cg-name", scope, Set.of("flex-1")));
        assertTrue(ex.getMessage().contains("Unable to resolve"));
        verify(storageStrategy, atLeastOnce()).pollJobIfPresent(any(), any());
    }

    @Test
    void testResolveConsistencyGroupUuidByName_handlesMissingAndPresentRecords() {
        OntapVMSnapshotStrategy.ConsistencyGroupScope scope =
                new OntapVMSnapshotStrategy.ConsistencyGroupScope("10.0.0.1", "vs0", null);
        assertNull(strategy.resolveConsistencyGroupUuidByName(snapshotFeignClient, "auth", "cg-name", scope));

        when(snapshotFeignClient.getConsistencyGroups(any(), any())).thenReturn(new OntapResponse<>());
        assertNull(strategy.resolveConsistencyGroupUuidByName(snapshotFeignClient, "auth", "cg-name", scope));

        OntapResponse<ConsistencyGroup> empty = new OntapResponse<>();
        empty.setRecords(Collections.emptyList());
        when(snapshotFeignClient.getConsistencyGroups(any(), any())).thenReturn(empty);
        assertNull(strategy.resolveConsistencyGroupUuidByName(snapshotFeignClient, "auth", "cg-name", scope));

        OntapResponse<ConsistencyGroup> found = new OntapResponse<>();
        ConsistencyGroup group = new ConsistencyGroup();
        group.setUuid("cg-uuid");
        found.setRecords(Collections.singletonList(group));
        when(snapshotFeignClient.getConsistencyGroups(any(), any())).thenReturn(found);
        assertEquals("cg-uuid", strategy.resolveConsistencyGroupUuidByName(snapshotFeignClient, "auth", "cg-name", scope));

        ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
        verify(snapshotFeignClient, atLeastOnce()).getConsistencyGroups(eq("auth"), query.capture());
        assertEquals("vs0", query.getValue().get("svm.name"));
    }

    @Test
    void testStartConsistencyGroupSnapshot_nullJobMissingUuidAndExtractedUuid() {
        when(snapshotFeignClient.createConsistencyGroupSnapshot(any(), eq("cg-1"), any()))
                .thenReturn(createJobResponse("job-start"));
        when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), any())).thenReturn(null);
        assertNull(strategy.startConsistencyGroupSnapshot(snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));

        Job undescribed = new Job();
        undescribed.setDescription("completed without a snapshot path");
        when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), any())).thenReturn(undescribed);
        assertNull(strategy.startConsistencyGroupSnapshot(snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));

        Job described = new Job();
        described.setDescription("POST /api/application/consistency-groups/cg-1/snapshots/cg-snap-9");
        when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), any())).thenReturn(described);
        assertEquals("cg-snap-9",
                strategy.startConsistencyGroupSnapshot(snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));
    }

    @Test
    void testResolveStartedConsistencyGroupSnapshotUuid_prefersJobThenFallsBackToLookup() {
        Job described = new Job();
        described.setDescription("POST /api/application/consistency-groups/cg-1/snapshots/from-job");
        when(snapshotFeignClient.createConsistencyGroupSnapshot(any(), eq("cg-1"), any()))
                .thenReturn(createJobResponse("job-start"));
        when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), any())).thenReturn(described);
        assertEquals("from-job", strategy.resolveStartedConsistencyGroupSnapshotUuid(
                snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));
        verify(snapshotFeignClient, never()).getConsistencyGroupSnapshots(any(), any(), any());

        when(storageStrategy.pollJobIfPresentAndGetCompletedJob(any(), any())).thenReturn(null);
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any()))
                .thenReturn(cgSnapshotResponse("snap", "from-get"));
        assertEquals("from-get", strategy.resolveStartedConsistencyGroupSnapshotUuid(
                snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));
    }

    @Test
    void testLookupConsistencyGroupSnapshotUuid_matchesFiltersAndFallbackList() {
        assertNull(strategy.lookupConsistencyGroupSnapshotUuid(snapshotFeignClient, "auth", "cg-1", "snap"));

        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any()))
                .thenReturn(cgSnapshotResponse("snap", "direct"));
        assertEquals("direct", strategy.lookupConsistencyGroupSnapshotUuid(snapshotFeignClient, "auth", "cg-1", "snap"));

        OntapResponse<ConsistencyGroupSnapshot> mixed = new OntapResponse<>();
        ConsistencyGroupSnapshot other = new ConsistencyGroupSnapshot();
        other.setName("other");
        other.setUuid("other-uuid");
        ConsistencyGroupSnapshot blank = new ConsistencyGroupSnapshot();
        blank.setName("snap");
        blank.setUuid("");
        ConsistencyGroupSnapshot good = new ConsistencyGroupSnapshot();
        good.setName("snap");
        good.setUuid("later-uuid");
        mixed.setRecords(Arrays.asList(null, other, blank, good));
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any())).thenReturn(mixed);
        assertEquals("later-uuid", strategy.lookupConsistencyGroupSnapshotUuid(snapshotFeignClient, "auth", "cg-1", "snap"));

        OntapResponse<ConsistencyGroupSnapshot> empty = new OntapResponse<>();
        empty.setRecords(Collections.emptyList());
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any()))
                .thenReturn(empty, cgSnapshotResponse("snap", "from-list"));
        assertEquals("from-list", strategy.lookupConsistencyGroupSnapshotUuid(snapshotFeignClient, "auth", "cg-1", "snap"));

        OntapResponse<ConsistencyGroupSnapshot> missing = null;
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any())).thenReturn(missing, missing);
        assertNull(strategy.lookupConsistencyGroupSnapshotUuid(snapshotFeignClient, "auth", "cg-1", "snap"));
    }

    @Test
    void testResolveConsistencyGroupSnapshotUuid_succeedsOnFirstAndSecondAttempt() {
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any()))
                .thenReturn(cgSnapshotResponse("snap", "immediate"));
        assertEquals("immediate", strategy.resolveConsistencyGroupSnapshotUuid(
                snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));

        AtomicInteger calls = new AtomicInteger();
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() < 3) {
                return new OntapResponse<ConsistencyGroupSnapshot>();
            }
            return cgSnapshotResponse("snap", "retried");
        });
        assertEquals("retried", strategy.resolveConsistencyGroupSnapshotUuid(
                snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));
    }

    @Test
    void testResolveConsistencyGroupSnapshotUuid_interruptedWhileWaiting() {
        OntapResponse<ConsistencyGroupSnapshot> empty = new OntapResponse<>();
        empty.setRecords(Collections.emptyList());
        when(snapshotFeignClient.getConsistencyGroupSnapshots(any(), eq("cg-1"), any())).thenReturn(empty);
        Thread.currentThread().interrupt();
        try {
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.resolveConsistencyGroupSnapshotUuid(
                    snapshotFeignClient, storageStrategy, "auth", "cg-1", "snap"));
            assertTrue(ex.getMessage().contains("Interrupted"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testDeleteVMSnapshot_stateTransitionFails() throws Exception {
        VMSnapshotVO vmSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshot.getVmId()).thenReturn(VM_ID);
        doThrow(new NoTransitionException("cannot expunge")).when(vmSnapshotHelper)
                .vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.ExpungeRequested);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.deleteVMSnapshot(vmSnapshot));
        assertEquals("cannot expunge", ex.getMessage());
    }

    @Test
    void testDeleteVMSnapshot_noDetails_returnsTrue() throws Exception {
        VMSnapshotVO vmSnapshot = mockDeleteOrRevertSnapshot();
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.ExpungeRequested);
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                .thenReturn(Collections.emptyList());
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot")).thenReturn(Collections.emptyList());

        assertTrue(strategy.deleteVMSnapshot(vmSnapshot));
        verify(strategy, never()).deleteDiskSnapshot(any());
        verify(storageStrategy, never()).deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());
    }

    @Test
    void testDeleteVMSnapshot_flexAndLegacyDetails_deletesBoth() throws Exception {
        VMSnapshotVO vmSnapshot = mockDeleteOrRevertSnapshot();
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.ExpungeRequested);
        VMSnapshotDetailsVO first = flexDetail(1L, "flex-1::snap-1::name::file-1::401::NFS3");
        VMSnapshotDetailsVO second = flexDetail(2L, "flex-1::snap-1::name::file-2::401::NFS3");
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                .thenReturn(Arrays.asList(first, second));
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot"))
                .thenReturn(Collections.singletonList(new VMSnapshotDetailsVO(SNAPSHOT_ID, "kvmStorageSnapshot", "9", true)));

        assertTrue(strategy.deleteVMSnapshot(vmSnapshot));
        verify(storageStrategy, times(1)).deleteFlexVolSnapshotForCloudStackVolume("flex-1", "snap-1", "name");
        verify(vmSnapshotDetailsDao).remove(1L);
        verify(vmSnapshotDetailsDao).remove(2L);
        verify(strategy).deleteDiskSnapshot(vmSnapshot);
    }

    @Test
    void testDeleteVMSnapshot_flexDeleteFailure_wrapsError() throws Exception {
        VMSnapshotVO vmSnapshot = mockDeleteOrRevertSnapshot();
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.ExpungeRequested);
        VMSnapshotDetailsVO failedDetail = flexDetail(1L, "flex-1::snap-1::name::file::401::NFS3");
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                .thenReturn(Collections.singletonList(failedDetail));
        doThrow(new IllegalStateException("ontap down")).when(storageStrategy)
                .deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.deleteVMSnapshot(vmSnapshot));
        assertTrue(ex.getMessage().contains("Delete of ONTAP VM Snapshot"));
        assertTrue(ex.getMessage().contains("ontap down"));
        verify(vmSnapshotDetailsDao, never()).remove(anyLong());
    }

    @Test
    void testDeleteFlexVolSnapshots_keepsFirstFailureAndSkipsDetailRemoval() {
        VMSnapshotDetailsVO first = flexDetail(1L, "flex-1::snap-1::name::file-1::401::NFS3");
        VMSnapshotDetailsVO second = flexDetail(2L, "flex-1::snap-2::name::file-2::401::NFS3");
        doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
        doThrow(new CloudRuntimeException("first-failure"))
                .doThrow(new IllegalStateException("second-failure"))
                .when(storageStrategy).deleteFlexVolSnapshotForCloudStackVolume(any(), any(), any());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteFlexVolSnapshots(Arrays.asList(first, second)));
        assertEquals("first-failure", ex.getMessage());
        verify(vmSnapshotDetailsDao, never()).remove(anyLong());
    }

    @Test
    void testDeleteFlexVolSnapshots_invalidDetail_throws() {
        VMSnapshotDetailsVO detail = flexDetail(1L, "not-a-detail");
        assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteFlexVolSnapshots(Collections.singletonList(detail)));
    }

    @Test
    void testRevertVMSnapshot_stateTransitionFails() throws Exception {
        VMSnapshotVO vmSnapshot = mock(VMSnapshotVO.class);
        when(vmSnapshot.getVmId()).thenReturn(VM_ID);
        doThrow(new NoTransitionException("cannot revert")).when(vmSnapshotHelper)
                .vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.RevertRequested);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.revertVMSnapshot(vmSnapshot));
        assertEquals("cannot revert", ex.getMessage());
    }

    @Test
    void testRevertVMSnapshot_flexAndLegacy_returnsTrue() throws Exception {
        VMSnapshotVO vmSnapshot = mockDeleteOrRevertSnapshot();
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.RevertRequested);
        Map<String, String> poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.VOLUME_NAME, "fv-name");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails);
        VMSnapshotDetailsVO nfsDetail = flexDetail(1L, "flex-1::snap-1::snap-name::file.qcow2::401::NFS3");
        VMSnapshotDetailsVO iscsiDetail = flexDetail(2L, "flex-1::snap-1::snap-name::/vol/vol1/lun_301::401::ISCSI");
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                .thenReturn(Arrays.asList(nfsDetail, iscsiDetail));
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot"))
                .thenReturn(Collections.singletonList(new VMSnapshotDetailsVO(SNAPSHOT_ID, "kvmStorageSnapshot", "9", true)));
        when(storageStrategy.revertSnapshotForCloudStackVolume(any(), any(), any(), any(), any(), any()))
                .thenReturn(createJobResponse("job-restore"));

        assertTrue(strategy.revertVMSnapshot(vmSnapshot));
        verify(storageStrategy).revertSnapshotForCloudStackVolume(
                "snap-name", "flex-1", "snap-1", "file.qcow2", null, "fv-name");
        verify(storageStrategy).revertSnapshotForCloudStackVolume(
                "snap-name", "flex-1", "snap-1", "/vol/vol1/lun_301", null, "fv-name");
        verify(storageStrategy, times(2)).executeCliSfsrRestore(any(), any());
        verify(strategy).revertDiskSnapshot(vmSnapshot);
    }

    @Test
    void testRevertFlexVolSnapshots_missingOrBlankFlexVolName_throws() {
        doReturn(storageStrategy).when(strategy).resolveStorageStrategy(any());
        VMSnapshotDetailsVO detail = flexDetail(1L, "flex-1::snap-1::snap-name::file.qcow2::401::NFS3");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(new HashMap<>());
        assertThrows(CloudRuntimeException.class,
                () -> strategy.revertFlexVolSnapshots(Collections.singletonList(detail)));

        Map<String, String> blank = new HashMap<>();
        blank.put(OntapStorageConstants.VOLUME_NAME, "");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(blank);
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.revertFlexVolSnapshots(Collections.singletonList(detail)));
        assertTrue(ex.getMessage().contains("FlexVolume name"));
    }

    @Test
    void testRevertVMSnapshot_restoreFailsAndOperationFailedTransitionFails() throws Exception {
        VMSnapshotVO vmSnapshot = mockDeleteOrRevertSnapshot();
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.RevertRequested);
        Map<String, String> poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.VOLUME_NAME, "fv-name");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails);
        VMSnapshotDetailsVO restoreDetail = flexDetail(1L, "flex-1::snap-1::snap-name::file.qcow2::401::NFS3");
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                .thenReturn(Collections.singletonList(restoreDetail));
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot")).thenReturn(Collections.emptyList());
        when(storageStrategy.revertSnapshotForCloudStackVolume(any(), any(), any(), any(), any(), any()))
                .thenThrow(new CloudRuntimeException("restore failed"));
        doThrow(new NoTransitionException("stuck")).when(vmSnapshotHelper)
                .vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.revertVMSnapshot(vmSnapshot));
        assertTrue(ex.getMessage().contains("vm-snap-1"));
        verify(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.OperationFailed);
    }

    @Test
    void testRevertVMSnapshot_restoreFailsAndOperationFailedSucceeds() throws Exception {
        VMSnapshotVO vmSnapshot = mockDeleteOrRevertSnapshot();
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.RevertRequested);
        Map<String, String> poolDetails = new HashMap<>();
        poolDetails.put(OntapStorageConstants.VOLUME_NAME, "fv-name");
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID_1)).thenReturn(poolDetails);
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT))
                .thenReturn(null);
        when(vmSnapshotDetailsDao.findDetails(SNAPSHOT_ID, "kvmStorageSnapshot"))
                .thenReturn(Collections.singletonList(new VMSnapshotDetailsVO(SNAPSHOT_ID, "kvmStorageSnapshot", "9", true)));
        doThrow(new CloudRuntimeException("legacy revert failed")).when(strategy).revertDiskSnapshot(any());
        doReturn(true).when(vmSnapshotHelper).vmSnapshotStateTransitTo(any(), eq(VMSnapshot.Event.OperationFailed));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.revertVMSnapshot(vmSnapshot));
        assertTrue(ex.getMessage().contains("vm-snap-1"));
        verify(vmSnapshotHelper).vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.OperationFailed);
    }

    @Test
    void testResolveVolumePathOnOntap_missingAndEmptyPaths() {
        VolumeDetailVO emptyLun = mock(VolumeDetailVO.class);
        when(emptyLun.getValue()).thenReturn("");
        when(volumeDetailsDao.findDetail(VOLUME_ID_1, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(emptyLun);
        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveVolumePathOnOntap(VOLUME_ID_1, "iscsi", new HashMap<>()));

        VolumeDetailVO nullLun = mock(VolumeDetailVO.class);
        when(nullLun.getValue()).thenReturn(null);
        when(volumeDetailsDao.findDetail(VOLUME_ID_1, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(nullLun);
        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveVolumePathOnOntap(VOLUME_ID_1, "ISCSI", new HashMap<>()));

        VolumeVO emptyPath = mock(VolumeVO.class);
        when(emptyPath.getPath()).thenReturn("");
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(emptyPath);
        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveVolumePathOnOntap(VOLUME_ID_1, null, new HashMap<>()));

        VolumeVO nullPath = mock(VolumeVO.class);
        when(nullPath.getPath()).thenReturn(null);
        when(volumeDao.findById(VOLUME_ID_1)).thenReturn(nullPath);
        assertThrows(CloudRuntimeException.class,
                () -> strategy.resolveVolumePathOnOntap(VOLUME_ID_1, "NFS3", new HashMap<>()));
    }
}
