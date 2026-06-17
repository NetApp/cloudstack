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
package org.apache.cloudstack.storage.driver;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.client.NASFeignClient;
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.UnifiedSANStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
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

import java.util.HashMap;
import java.util.Map;

import static com.cloud.agent.api.to.DataObjectType.SNAPSHOT;
import static com.cloud.agent.api.to.DataObjectType.VOLUME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OntapPrimaryDatastoreDriverTest {

    @Mock
    private StoragePoolDetailsDao storagePoolDetailsDao;

    @Mock
    private PrimaryDataStoreDao storagePoolDao;

    @Mock
    private VolumeDao volumeDao;

    @Mock
    private VolumeDetailsDao volumeDetailsDao;

    @Mock
    private SnapshotDetailsDao snapshotDetailsDao;

    @Mock
    private DataStore dataStore;

    @Mock
    private VolumeInfo volumeInfo;

    @Mock
    private StoragePoolVO storagePool;

    @Mock
    private VolumeVO volumeVO;

    @Mock
    private Host host;

    @Mock
    private UnifiedSANStrategy sanStrategy;

    @Mock
    private StorageStrategy storageStrategy;

    @Mock
    private NASFeignClient nasFeignClient;

    @Mock
    private SANFeignClient sanFeignClient;

    @Mock
    private SnapshotInfo snapshotInfo;

    @Mock
    private AsyncCompletionCallback<CreateCmdResult> createCallback;

    @Mock
    private AsyncCompletionCallback<CommandResult> commandCallback;

    @InjectMocks
    private OntapPrimaryDatastoreDriver driver;

    private Map<String, String> storagePoolDetails;

    @BeforeEach
    void setUp() {
        storagePoolDetails = new HashMap<>();
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());
        storagePoolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
    }

    @Test
    void testGetCapabilities() {
        Map<String, String> capabilities = driver.getCapabilities();

        assertNotNull(capabilities);
        // With SIS clone approach, driver advertises storage system snapshot capability
        // so StorageSystemSnapshotStrategy handles snapshot backup to secondary storage
        assertEquals(Boolean.TRUE.toString(), capabilities.get("STORAGE_SYSTEM_SNAPSHOT"));
        assertEquals(Boolean.TRUE.toString(), capabilities.get("CAN_CREATE_VOLUME_FROM_SNAPSHOT"));
    }

    @Test
    void testCreateAsync_NullDataObject_ThrowsException() {
        assertThrows(InvalidParameterValueException.class,
            () -> driver.createAsync(dataStore, null, createCallback));
    }

    @Test
    void testCreateAsync_NullDataStore_ThrowsException() {
        assertThrows(InvalidParameterValueException.class,
            () -> driver.createAsync(null, volumeInfo, createCallback));
    }

    @Test
    void testCreateAsync_NullCallback_ThrowsException() {
        assertThrows(InvalidParameterValueException.class,
            () -> driver.createAsync(dataStore, volumeInfo, null));
    }

    @Test
    void testCreateAsync_VolumeWithISCSI_Success() {
        // Setup
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        Lun mockLun = new Lun();
        mockLun.setName("/vol/vol1/lun1");
        mockLun.setUuid("lun-uuid-123");
        // Create request volume (returned by Utility.createCloudStackVolumeRequestByProtocol)
        CloudStackVolume requestVolume = new CloudStackVolume();
        requestVolume.setLun(mockLun);
        // Create response volume (returned by sanStrategy.createCloudStackVolume)
        CloudStackVolume responseVolume = new CloudStackVolume();
        responseVolume.setLun(mockLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.createCloudStackVolumeRequestByProtocol(
                    any(), any(), any())).thenReturn(requestVolume);
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(responseVolume);

            // Execute
            driver.createAsync(dataStore, volumeInfo, createCallback);

            // Verify
            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());

            CreateCmdResult result = resultCaptor.getValue();
            assertNotNull(result);
            assertTrue(result.isSuccess());

            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.LUN_DOT_UUID), eq("lun-uuid-123"), eq(false));
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.LUN_DOT_NAME), eq("/vol/vol1/lun1"), eq(false));
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
        }
    }

    @Test
    void testCreateAsync_VolumeWithNFS_Success() {
        // Setup
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());

        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        CloudStackVolume mockCloudStackVolume = new CloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.createCloudStackVolumeRequestByProtocol(
                    any(), any(), any())).thenReturn(mockCloudStackVolume);

            when(sanStrategy.createCloudStackVolume(any())).thenReturn(mockCloudStackVolume);

            // Execute
            driver.createAsync(dataStore, volumeInfo, createCallback);

            // Verify
            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());

            CreateCmdResult result = resultCaptor.getValue();
            assertNotNull(result);
            assertTrue(result.isSuccess());
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
        }
    }

    @Test
    void testDeleteAsync_NullStore_ThrowsException() {
        ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);

        driver.deleteAsync(null, volumeInfo, commandCallback);

        verify(commandCallback).complete(resultCaptor.capture());
        CommandResult result = resultCaptor.getValue();
        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("store or data is null"));
    }

    @Test
    void testDeleteAsync_ISCSIVolume_Success() {
        // Setup
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        VolumeDetailVO lunUuidDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false);

        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID)).thenReturn(lunUuidDetail);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);

            doNothing().when(sanStrategy).deleteCloudStackVolume(any());

            // Execute
            driver.deleteAsync(dataStore, volumeInfo, commandCallback);

            // Verify
            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());

            CommandResult result = resultCaptor.getValue();
            assertNotNull(result);
            assertTrue(result.isSuccess());
            verify(sanStrategy).deleteCloudStackVolume(any(CloudStackVolume.class));
        }
    }

    @Test
    void testDeleteAsync_NFSVolume_Success() {
        // Setup
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());

        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        // Execute
        driver.deleteAsync(dataStore, volumeInfo, commandCallback);

        // Verify
        ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
        verify(commandCallback).complete(resultCaptor.capture());

        CommandResult result = resultCaptor.getValue();
        assertNotNull(result);
        // NFS deletion doesn't fail, handled by hypervisor
    }

    @Test
    void testGrantAccess_NullParameters_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> driver.grantAccess(null, host, dataStore));

        assertThrows(CloudRuntimeException.class,
            () -> driver.grantAccess(volumeInfo, null, dataStore));

        assertThrows(CloudRuntimeException.class,
            () -> driver.grantAccess(volumeInfo, host, null));
    }

    @Test
    void testGrantAccess_ClusterScope_Success() {
        // Setup
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        when(host.getName()).thenReturn("host1");

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);

        // Mock AccessGroup with existing igroup
        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("0");

            // Execute
            boolean result = driver.grantAccess(volumeInfo, host, dataStore);

            // Verify
            assertTrue(result);
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
            verify(sanStrategy).getAccessGroup(any());
            verify(sanStrategy).ensureLunMapped(anyString(), anyString(), anyString());
            verify(sanStrategy, never()).validateInitiatorInAccessGroup(anyString(), anyString(), any(Igroup.class));
        }
    }

    @Test
    void testGrantAccess_IgroupNotFound_CreatesNewIgroup() {
        // Setup - use HostVO mock since production code casts Host to HostVO
        HostVO hostVO = mock(HostVO.class);
        when(hostVO.getName()).thenReturn("host1");

        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);

        // Mock getAccessGroup returning null (igroup doesn't exist)
        AccessGroup createdAccessGroup = new AccessGroup();
        Igroup createdIgroup = new Igroup();
        createdIgroup.setName("igroup1");
        createdAccessGroup.setIgroup(createdIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(null);
            when(sanStrategy.createAccessGroup(any())).thenReturn(createdAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("0");

            // Execute
            boolean result = driver.grantAccess(volumeInfo, hostVO, dataStore);

            // Verify
            assertTrue(result);
            verify(sanStrategy).getAccessGroup(any());
            verify(sanStrategy).createAccessGroup(any());
            verify(sanStrategy).ensureLunMapped(anyString(), anyString(), anyString());
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
        }
    }

    @Test
    void testRevokeAccess_NFSVolume_SkipsRevoke() {
        // Setup - NFS volumes have no LUN mapping, so revokeAccess is a no-op
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);

        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(host.getName()).thenReturn("host1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);

            // Execute - NFS has no iSCSI protocol, so revokeAccessForVolume does nothing
            driver.revokeAccess(volumeInfo, host, dataStore);

            // Verify - no LUN unmap operations for NFS
            verify(sanStrategy, never()).disableLogicalAccess(any());
        }
    }

    @Test
    void testRevokeAccess_ISCSIVolume_Success() {
        // Setup
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);

        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        when(host.getStorageUrl()).thenReturn("iqn.1993-08.org.debian:01:host1");
        when(host.getName()).thenReturn("host1");

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);

        Lun mockLun = new Lun();
        mockLun.setName("/vol/vol1/lun1");
        mockLun.setUuid("lun-uuid-123");
        CloudStackVolume mockCloudStackVolume = new CloudStackVolume();
        mockCloudStackVolume.setLun(mockLun);

        org.apache.cloudstack.storage.feign.model.Igroup mockIgroup = mock(org.apache.cloudstack.storage.feign.model.Igroup.class);
        when(mockIgroup.getName()).thenReturn("igroup1");
        when(mockIgroup.getUuid()).thenReturn("igroup-uuid-123");
        AccessGroup mockAccessGroup = new AccessGroup();
        mockAccessGroup.setIgroup(mockIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            // Mock the methods called by getCloudStackVolumeByName and getAccessGroupByName
            when(sanStrategy.getCloudStackVolume(argThat(map ->
                map != null &&
                "/vol/vol1/lun1".equals(map.get("name")) &&
                "svm1".equals(map.get("svm.name"))
            ))).thenReturn(mockCloudStackVolume);

            when(sanStrategy.getAccessGroup(argThat(map ->
                map != null &&
                "igroup1".equals(map.get("name")) &&
                "svm1".equals(map.get("svm.name"))
            ))).thenReturn(mockAccessGroup);

            when(sanStrategy.validateInitiatorInAccessGroup(
                eq("iqn.1993-08.org.debian:01:host1"),
                eq("svm1"),
                any(Igroup.class)
            )).thenReturn(true);

            doNothing().when(sanStrategy).disableLogicalAccess(argThat(map ->
                map != null &&
                "lun-uuid-123".equals(map.get("lun.uuid")) &&
                "igroup-uuid-123".equals(map.get("igroup.uuid"))
            ));

            // Execute
            driver.revokeAccess(volumeInfo, host, dataStore);

            // Verify
            verify(sanStrategy).getCloudStackVolume(any());
            verify(sanStrategy).getAccessGroup(any());
            verify(sanStrategy).validateInitiatorInAccessGroup(anyString(), anyString(), any(Igroup.class));
            verify(sanStrategy).disableLogicalAccess(any());
        }
    }

    @Test
    void testCanHostAccessStoragePool_ReturnsTrue() {
        assertTrue(driver.canHostAccessStoragePool(host, storagePool));
    }

    @Test
    void testIsVmInfoNeeded_ReturnsTrue() {
        assertTrue(driver.isVmInfoNeeded());
    }

    @Test
    void testIsStorageSupportHA_ReturnsTrue() {
        assertTrue(driver.isStorageSupportHA(Storage.StoragePoolType.NetworkFilesystem));
    }

    @Test
    void testGetChapInfo_ReturnsNull() {
        assertNull(driver.getChapInfo(volumeInfo));
    }

    @Test
    void testCanProvideStorageStats_ReturnsFalse() {
        assertFalse(driver.canProvideStorageStats());
    }

    @Test
    void testCanProvideVolumeStats_ReturnsFalse() {
        assertFalse(driver.canProvideVolumeStats());
    }

    @Test
    void testTakeSnapshot_NfsCloneSuccess() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        storagePoolDetails.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        storagePoolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
        storagePoolDetails.put(OntapStorageConstants.USERNAME, "admin");
        storagePoolDetails.put(OntapStorageConstants.PASSWORD, "pass");
        storagePoolDetails.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");
        storagePoolDetails.put(OntapStorageConstants.SIZE, "1024");

        when(snapshotInfo.getId()).thenReturn(500L);
        when(snapshotInfo.getName()).thenReturn("UI Snapshot Name");
        when(snapshotInfo.getBaseVolume()).thenReturn(volumeInfo);
        SnapshotObjectTO snapshotObjectTO = mock(SnapshotObjectTO.class);
        when(snapshotInfo.getTO()).thenReturn(snapshotObjectTO);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(volumeVO.getPath()).thenReturn("vol-100.qcow2");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(storageStrategy.createSnapshotClone(eq("flexvol-uuid-1"), eq("flexvol1"),
                eq("vol-100.qcow2"), eq("UI_Snapshot_Name"), isNull())).thenReturn("UI_Snapshot_Name");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(storageStrategy);
            utilityMock.when(() -> OntapStorageUtils.getOntapCloneName("UI Snapshot Name"))
                    .thenReturn("UI_Snapshot_Name");

            driver.takeSnapshot(snapshotInfo, createCallback);

            verify(storageStrategy).createSnapshotClone(eq("flexvol-uuid-1"), eq("flexvol1"),
                    eq("vol-100.qcow2"), eq("UI_Snapshot_Name"), isNull());
            verify(snapshotDetailsDao, atLeastOnce()).persist(any(SnapshotDetailsVO.class));
            verify(createCallback).complete(any(CreateCmdResult.class));
        }
    }

    @Test
    void testRevertSnapshot_UsesCloneMetadata() {
        when(snapshotInfo.getId()).thenReturn(500L);
        when(snapshotDetailsDao.findDetail(500L, OntapStorageConstants.BASE_ONTAP_FV_ID))
                .thenReturn(new SnapshotDetailsVO(500L, OntapStorageConstants.BASE_ONTAP_FV_ID, "flexvol-uuid-1", false));
        when(snapshotDetailsDao.findDetail(500L, OntapStorageConstants.ONTAP_CLONE_ID))
                .thenReturn(new SnapshotDetailsVO(500L, OntapStorageConstants.ONTAP_CLONE_ID, "clone-lun-uuid-1", false));
        when(snapshotDetailsDao.findDetail(500L, OntapStorageConstants.ONTAP_CLONE_NAME))
                .thenReturn(new SnapshotDetailsVO(500L, OntapStorageConstants.ONTAP_CLONE_NAME, "UI_Snapshot_Name", false));
        when(snapshotDetailsDao.findDetail(500L, OntapStorageConstants.VOLUME_PATH))
                .thenReturn(new SnapshotDetailsVO(500L, OntapStorageConstants.VOLUME_PATH, "dest-lun-1", false));
        when(snapshotDetailsDao.findDetail(500L, OntapStorageConstants.PRIMARY_POOL_ID))
                .thenReturn(new SnapshotDetailsVO(500L, OntapStorageConstants.PRIMARY_POOL_ID, "1", false));
        when(snapshotDetailsDao.findDetail(500L, OntapStorageConstants.PROTOCOL))
                .thenReturn(new SnapshotDetailsVO(500L, OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name(), false));

        storagePoolDetails.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        doNothing().when(storageStrategy).revertSnapshotForCloudStackVolume(anyString(), anyString(), anyString(), anyString(), anyString());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(storageStrategy);

            driver.revertSnapshot(snapshotInfo, snapshotInfo, commandCallback);

            verify(storageStrategy).revertSnapshotForCloudStackVolume(
                    eq("UI_Snapshot_Name"), eq("flexvol-uuid-1"), eq("clone-lun-uuid-1"),
                    eq("dest-lun-1"), eq("flexvol1"));
            verify(commandCallback).complete(any(CommandResult.class));
        }
    }

    @Test
    void testRevertSnapshot_FallbacksToLegacySnapshotNameWhenCloneNameMissing() {
        when(snapshotInfo.getId()).thenReturn(501L);
        when(snapshotDetailsDao.findDetail(501L, OntapStorageConstants.BASE_ONTAP_FV_ID))
                .thenReturn(new SnapshotDetailsVO(501L, OntapStorageConstants.BASE_ONTAP_FV_ID, "flexvol-uuid-1", false));
        when(snapshotDetailsDao.findDetail(501L, OntapStorageConstants.ONTAP_CLONE_ID))
                .thenReturn(new SnapshotDetailsVO(501L, OntapStorageConstants.ONTAP_CLONE_ID, "clone-lun-uuid-2", false));
        when(snapshotDetailsDao.findDetail(501L, OntapStorageConstants.ONTAP_CLONE_NAME)).thenReturn(null);
        when(snapshotDetailsDao.findDetail(501L, OntapStorageConstants.ONTAP_SNAP_NAME))
                .thenReturn(new SnapshotDetailsVO(501L, OntapStorageConstants.ONTAP_SNAP_NAME, "Legacy_UI_Snapshot", false));
        when(snapshotDetailsDao.findDetail(501L, OntapStorageConstants.VOLUME_PATH))
                .thenReturn(new SnapshotDetailsVO(501L, OntapStorageConstants.VOLUME_PATH, "dest-lun-1", false));
        when(snapshotDetailsDao.findDetail(501L, OntapStorageConstants.PRIMARY_POOL_ID))
                .thenReturn(new SnapshotDetailsVO(501L, OntapStorageConstants.PRIMARY_POOL_ID, "1", false));
        when(snapshotDetailsDao.findDetail(501L, OntapStorageConstants.PROTOCOL))
                .thenReturn(new SnapshotDetailsVO(501L, OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name(), false));

        storagePoolDetails.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        doNothing().when(storageStrategy).revertSnapshotForCloudStackVolume(anyString(), anyString(), anyString(), anyString(), anyString());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(storageStrategy);

            driver.revertSnapshot(snapshotInfo, snapshotInfo, commandCallback);

            verify(storageStrategy).revertSnapshotForCloudStackVolume(
                    eq("Legacy_UI_Snapshot"), eq("flexvol-uuid-1"), eq("clone-lun-uuid-2"),
                    eq("dest-lun-1"), eq("flexvol1"));
            verify(commandCallback).complete(any(CommandResult.class));
        }
    }

    @Test
    void testDeleteAsync_SnapshotNfsClone_UsesDeleteFile() {
        when(snapshotInfo.getType()).thenReturn(SNAPSHOT);
        when(snapshotInfo.getId()).thenReturn(700L);

        when(snapshotDetailsDao.findDetail(700L, OntapStorageConstants.BASE_ONTAP_FV_ID))
                .thenReturn(new SnapshotDetailsVO(700L, OntapStorageConstants.BASE_ONTAP_FV_ID, "flexvol-uuid-nfs", false));
        when(snapshotDetailsDao.findDetail(700L, OntapStorageConstants.ONTAP_CLONE_ID))
                .thenReturn(new SnapshotDetailsVO(700L, OntapStorageConstants.ONTAP_CLONE_ID, "clone-id-nfs", false));
        when(snapshotDetailsDao.findDetail(700L, OntapStorageConstants.ONTAP_CLONE_NAME))
                .thenReturn(new SnapshotDetailsVO(700L, OntapStorageConstants.ONTAP_CLONE_NAME, "clone-file-nfs.qcow2", false));
        when(snapshotDetailsDao.findDetail(700L, OntapStorageConstants.PRIMARY_POOL_ID))
                .thenReturn(new SnapshotDetailsVO(700L, OntapStorageConstants.PRIMARY_POOL_ID, "1", false));
        when(snapshotDetailsDao.findDetail(700L, OntapStorageConstants.PROTOCOL))
                .thenReturn(new SnapshotDetailsVO(700L, OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name(), false));

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        doNothing().when(storageStrategy).deleteSnapshotClone("flexvol-uuid-nfs", null, "clone-file-nfs.qcow2", "clone-id-nfs");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(storageStrategy);

            driver.deleteAsync(dataStore, snapshotInfo, commandCallback);

            verify(storageStrategy).deleteSnapshotClone("flexvol-uuid-nfs", null, "clone-file-nfs.qcow2", "clone-id-nfs");
            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
        }
    }

    @Test
    void testDeleteAsync_SnapshotIscsiClone_ResolvesUuidAndUsesDeleteLun() {
        storagePoolDetails.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        storagePoolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");

        when(snapshotInfo.getType()).thenReturn(SNAPSHOT);
        when(snapshotInfo.getId()).thenReturn(701L);

        when(snapshotDetailsDao.findDetail(701L, OntapStorageConstants.BASE_ONTAP_FV_ID))
                .thenReturn(new SnapshotDetailsVO(701L, OntapStorageConstants.BASE_ONTAP_FV_ID, "flexvol-uuid-iscsi", false));
        when(snapshotDetailsDao.findDetail(701L, OntapStorageConstants.ONTAP_CLONE_ID)).thenReturn(null);
        when(snapshotDetailsDao.findDetail(701L, OntapStorageConstants.ONTAP_CLONE_NAME))
                .thenReturn(new SnapshotDetailsVO(701L, OntapStorageConstants.ONTAP_CLONE_NAME, "clone-lun-name", false));
        when(snapshotDetailsDao.findDetail(701L, OntapStorageConstants.PRIMARY_POOL_ID))
                .thenReturn(new SnapshotDetailsVO(701L, OntapStorageConstants.PRIMARY_POOL_ID, "1", false));
        when(snapshotDetailsDao.findDetail(701L, OntapStorageConstants.PROTOCOL))
                .thenReturn(new SnapshotDetailsVO(701L, OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name(), false));

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        doNothing().when(storageStrategy).deleteSnapshotClone("flexvol-uuid-iscsi", "flexvol1", "clone-lun-name", null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(storageStrategy);
            driver.deleteAsync(dataStore, snapshotInfo, commandCallback);

            verify(storageStrategy).deleteSnapshotClone("flexvol-uuid-iscsi", "flexvol1", "clone-lun-name", null);
            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
        }
    }
}
