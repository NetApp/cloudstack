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
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ResizeVolumePayload;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.StorageManager;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.FileInfo;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.VolumeSpace;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.VolumeQosPolicy;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.UnifiedNASStrategy;
import org.apache.cloudstack.storage.service.UnifiedSANStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
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
import java.util.List;
import java.util.Map;

import static com.cloud.agent.api.to.DataObjectType.SNAPSHOT;
import static com.cloud.agent.api.to.DataObjectType.TEMPLATE;
import static com.cloud.agent.api.to.DataObjectType.VOLUME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Nested;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DiskTO;
import static org.mockito.Mockito.spy;
import java.util.function.Consumer;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import java.util.function.Supplier;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import java.util.Collections;
import org.apache.cloudstack.storage.feign.client.SnapshotFeignClient;
import org.apache.cloudstack.storage.feign.model.FlexVolSnapshot;
import org.apache.cloudstack.storage.feign.model.Job;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import feign.FeignException;

@ExtendWith(MockitoExtension.class)
class OntapPrimaryDatastoreDriverTest {

    /**
     * A real serial number taken from an ONTAP array, and the WWID NetApp derives from it as its
     * OUI plus the serial's ASCII bytes in hex. The serial deliberately contains a '/', which is
     * why volume paths carry the WWID: the raw serial would break the '/targetIQN/LUN' parsing that
     * every hypervisor resource applies to the path.
     */
    private static final String LUN_SERIAL_NUMBER = "x0M-8?/kEsJT";
    private static final String LUN_WWID = "600a098078304d2d383f2f6b45734a54";

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
    private SnapshotDao snapshotDao;

    @Mock
    private VMTemplatePoolDao vmTemplatePoolDao;

    @Mock
    private VMTemplateStoragePoolVO templatePoolRef;

    @Mock
    private StorageManager storageManager;

    @Mock
    private DataStore dataStore;

    @Mock
    private VolumeInfo volumeInfo;

    @Mock
    private TemplateInfo templateInfo;

    @Mock
    private StoragePoolVO storagePool;

    @Mock
    private VolumeVO volumeVO;

    @Mock
    private Host host;

    @Mock
    private UnifiedSANStrategy sanStrategy;

    @Mock
    private UnifiedNASStrategy nasStrategy;

    @Mock
    private AsyncCompletionCallback<CreateCmdResult> createCallback;

    @Mock
    private AsyncCompletionCallback<CommandResult> commandCallback;

    @Mock
    private AsyncCompletionCallback<CopyCommandResult> copyCallback;

    @InjectMocks
    private OntapPrimaryDatastoreDriver driver;

    private Map<String, String> storagePoolDetails;

    @BeforeEach
    void setUp() throws Exception {
        storagePoolDetails = new HashMap<>();
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());
        storagePoolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
        lenient().when(storageManager.connectHostToSharedPool(any(), anyLong())).thenReturn(true);
        storagePoolDetails.put(OntapStorageConstants.IS_AFF, "true");
        lenient().when(volumeInfo.getName()).thenReturn("test-volume");
        lenient().when(volumeInfo.getSize()).thenReturn(1073741824L);
        lenient().when(storagePool.getName()).thenReturn("vol1");
        lenient().when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
    }

    @Test
    void testGetCapabilities() {
        Map<String, String> capabilities = driver.getCapabilities();

        assertNotNull(capabilities);
        // With SIS clone approach, driver advertises storage system snapshot capability
        // so StorageSystemSnapshotStrategy handles snapshot backup to secondary storage
        assertEquals(Boolean.TRUE.toString(), capabilities.get("STORAGE_SYSTEM_SNAPSHOT"));
        assertEquals(Boolean.TRUE.toString(), capabilities.get("CAN_CREATE_VOLUME_FROM_SNAPSHOT"));
        assertEquals(Boolean.TRUE.toString(), capabilities.get("CAN_REVERT_VOLUME_TO_SNAPSHOT"));
        assertEquals(Boolean.TRUE.toString(), capabilities.get("CAN_CREATE_VOLUME_FROM_VOLUME"));
        assertEquals(Boolean.TRUE.toString(), capabilities.get("CAN_CREATE_TEMPLATE_FROM_SNAPSHOT"));
        assertEquals(Boolean.FALSE.toString(), capabilities.get("CAN_DIRECT_ATTACH_SNAPSHOT"));
    }

    @Test
    void canCopyOnlyCompatibleOfflineIscsiVolumes() {
        VolumeInfo srcVolume = mock(VolumeInfo.class);
        VolumeInfo destVolume = mock(VolumeInfo.class);
        DataStore srcStore = mock(DataStore.class);
        DataStore destStore = mock(DataStore.class);
        StoragePoolVO srcPool = mock(StoragePoolVO.class);
        StoragePoolVO destPool = mock(StoragePoolVO.class);
        Map<String, String> srcDetails = new HashMap<>(Map.of(
                OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name(),
                OntapStorageConstants.STORAGE_IP, "10.0.0.1",
                OntapStorageConstants.SVM_UUID, "svm-uuid"));
        Map<String, String> destDetails = new HashMap<>(srcDetails);

        when(srcVolume.getDataStore()).thenReturn(srcStore);
        when(destVolume.getDataStore()).thenReturn(destStore);
        when(srcVolume.getState()).thenReturn(Volume.State.Migrating);
        when(srcVolume.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(srcVolume.getPoolId()).thenReturn(1L);
        when(destVolume.getPoolId()).thenReturn(2L);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool);
        when(storagePoolDao.findById(2L)).thenReturn(destPool);
        when(srcPool.getId()).thenReturn(1L);
        when(destPool.getId()).thenReturn(2L);
        when(srcPool.getStorageProviderName()).thenReturn(DataStoreProvider.ONTAP_PLUGIN_NAME);
        when(destPool.getStorageProviderName()).thenReturn(DataStoreProvider.ONTAP_PLUGIN_NAME);
        when(srcPool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        when(destPool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(srcDetails);
        when(storagePoolDetailsDao.listDetailsKeyPairs(2L)).thenReturn(destDetails);

        assertTrue(driver.canCopy(srcVolume, destVolume));

        destDetails.put(OntapStorageConstants.SVM_UUID, "different-svm");
        assertFalse(driver.canCopy(srcVolume, destVolume));

        destDetails.put(OntapStorageConstants.SVM_UUID, "svm-uuid");
        when(destPool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        assertFalse(driver.canCopy(srcVolume, destVolume));

        when(srcVolume.getState()).thenReturn(Volume.State.Ready);
        assertFalse(driver.canCopy(srcVolume, destVolume));
        assertFalse(driver.canCopy(templateInfo, destVolume));
    }

    @Test
    void copyAsyncUsesOntapLunCopyAndPersistsDestinationIdentity() {
        VolumeInfo srcVolume = mock(VolumeInfo.class);
        VolumeInfo destVolume = mock(VolumeInfo.class);
        StoragePoolVO srcPool = mock(StoragePoolVO.class);
        StoragePoolVO destPool = mock(StoragePoolVO.class);
        VolumeVO destVolumeVO = mock(VolumeVO.class);
        VolumeObjectTO destVolumeTO = new VolumeObjectTO();
        Map<String, String> srcDetails = Map.of(
                OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name(),
                OntapStorageConstants.STORAGE_IP, "10.0.0.1",
                OntapStorageConstants.SVM_NAME, "svm1");
        Map<String, String> destDetails = new HashMap<>(srcDetails);

        when(srcVolume.getState()).thenReturn(Volume.State.Migrating);
        when(srcVolume.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(srcVolume.getPoolId()).thenReturn(1L);
        when(destVolume.getPoolId()).thenReturn(2L);
        when(destVolume.getId()).thenReturn(20L);
        when(destVolume.getTO()).thenReturn(destVolumeTO);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool);
        when(storagePoolDao.findById(2L)).thenReturn(destPool);
        when(srcPool.getId()).thenReturn(1L);
        when(destPool.getId()).thenReturn(2L);
        when(destPool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(srcPool.getStorageProviderName()).thenReturn(DataStoreProvider.ONTAP_PLUGIN_NAME);
        when(destPool.getStorageProviderName()).thenReturn(DataStoreProvider.ONTAP_PLUGIN_NAME);
        when(srcPool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        when(destPool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(srcDetails);
        when(storagePoolDetailsDao.listDetailsKeyPairs(2L)).thenReturn(destDetails);
        when(volumeDao.findById(20L)).thenReturn(destVolumeVO);
        when(destVolumeVO.getId()).thenReturn(20L);

        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/destFlexVol/dest_volume");
        copiedLun.setUuid("copied-lun-uuid");
        CloudStackVolume copiedVolume = new CloudStackVolume();
        copiedVolume.setLun(copiedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(destDetails)).thenReturn(sanStrategy);
            when(sanStrategy.copyCloudStackVolume(eq(srcVolume), eq(destVolume), eq(destPool), eq(destDetails), anyInt()))
                    .thenReturn(copiedVolume);

            driver.copyAsync(srcVolume, destVolume, copyCallback);

            verify(sanStrategy).copyCloudStackVolume(eq(srcVolume), eq(destVolume), eq(destPool), eq(destDetails), anyInt());
            verify(destVolumeVO).setPath(copiedLun.getName());
            verify(destVolumeVO).setFolder("copied-lun-uuid");
            verify(destVolumeVO).setFormat(Storage.ImageFormat.RAW);
            verify(volumeDetailsDao).addDetail(20L, OntapStorageConstants.LUN_DOT_UUID, "copied-lun-uuid", false);
            verify(volumeDetailsDao).addDetail(20L, OntapStorageConstants.LUN_DOT_NAME, copiedLun.getName(), false);
            verify(volumeDao).update(20L, destVolumeVO);

            ArgumentCaptor<CopyCommandResult> resultCaptor = ArgumentCaptor.forClass(CopyCommandResult.class);
            verify(copyCallback).complete(resultCaptor.capture());
            assertNull(resultCaptor.getValue().getResult());
            assertTrue(resultCaptor.getValue().getAnswer() instanceof CopyCmdAnswer);
            assertEquals(copiedLun.getName(), ((VolumeObjectTO)((CopyCmdAnswer)resultCaptor.getValue().getAnswer()).getNewData()).getPath());
        }
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
    void testCreateAsync_NullCallback_IsSupportedForStorageMigration() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        CloudStackVolume mockCloudStackVolume = new CloudStackVolume();
        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);
            when(nasStrategy.createCloudStackVolume(any())).thenReturn(mockCloudStackVolume);

            driver.createAsync(dataStore, volumeInfo, null);
        }
    }

    @Test
    void testCreateAsync_NullCallback_ThrowsWhenCreateFails() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);
            when(nasStrategy.createCloudStackVolume(any())).thenThrow(new CloudRuntimeException("ontap create failed"));

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                    () -> driver.createAsync(dataStore, volumeInfo, null));
            assertTrue(thrown.getMessage().contains("ontap create failed"));
            assertTrue(thrown.getMessage().startsWith("createAsync failed for ["));
        }
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
        lenient().when(storagePool.getId()).thenReturn(1L);
        lenient().when(storagePool.getName()).thenReturn("vol1");

        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        Lun mockLun = new Lun();
        mockLun.setName("/vol/vol1/lun1");
        mockLun.setUuid("lun-uuid-123");
        CloudStackVolume responseVolume = new CloudStackVolume();
        responseVolume.setLun(mockLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);
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
            verify(volumeVO).setFormat(Storage.ImageFormat.RAW);
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
        when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        CloudStackVolume mockCloudStackVolume = new CloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);

            when(nasStrategy.createCloudStackVolume(any())).thenReturn(mockCloudStackVolume);

            // Execute
            driver.createAsync(dataStore, volumeInfo, createCallback);

            // Verify
            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());

            CreateCmdResult result = resultCaptor.getValue();
            assertNotNull(result);
            assertTrue(result.isSuccess());
            verify(volumeVO).setFormat(Storage.ImageFormat.QCOW2);
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
        }
    }

    @Test
    void testCreateAsync_UnsupportedHypervisor_FailsWithError() {
        // Use NFS so LUN OS-type resolution does not fail earlier in getOSTypeFromHypervisor;
        // the failure under test is image-format resolution for non-KVM hypervisors.
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());

        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.VMware);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);

        CloudStackVolume mockCloudStackVolume = new CloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(nasStrategy);
            when(nasStrategy.createCloudStackVolume(any())).thenReturn(mockCloudStackVolume);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains("Unsupported hypervisor [VMware]"));
        }
    }

    @Test
    void testCreateAsync_KvmUnsupportedProtocol_FailsWithError() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, "FC");

        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        driver.createAsync(dataStore, volumeInfo, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Unsupported protocol [FC]"));
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
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
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
            verify(volumeDao, never()).remove(anyLong());
            verify(volumeDetailsDao, never()).removeDetails(anyLong());
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
    void testDeleteAsync_Template_DeletesCacheLun() {
        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getLocalDownloadPath()).thenReturn("template-lun-uuid");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);

            driver.deleteAsync(dataStore, templateInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).deleteCloudStackVolume(requestCaptor.capture());
            assertEquals("template-lun-uuid", requestCaptor.getValue().getLun().getUuid());
        }
    }


    @Test
    void testDeleteAsync_Template_ResolvesLunByNameWhenUuidMissing() {
        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getLocalDownloadPath()).thenReturn(null);

        Lun templateLun = new Lun();
        templateLun.setName("/vol/vol1/cs_tmpl_50");
        templateLun.setUuid("resolved-lun-uuid");
        CloudStackVolume existing = new CloudStackVolume();
        existing.setLun(templateLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);
            when(sanStrategy.getCloudStackVolume(argThat(map ->
                    map != null && "/vol/vol1/cs_tmpl_50".equals(map.get("name")))))
                    .thenReturn(existing);
            doNothing().when(sanStrategy).deleteCloudStackVolume(any());

            driver.deleteAsync(dataStore, templateInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> deleteCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).deleteCloudStackVolume(deleteCaptor.capture());
            assertEquals("resolved-lun-uuid", deleteCaptor.getValue().getLun().getUuid());
            assertEquals("/vol/vol1/cs_tmpl_50", deleteCaptor.getValue().getLun().getName());
        }
    }

    @Test
    void testDeleteAsync_Template_NoCachedLun_SucceedsWithoutCallingOntap() {
        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(null);

        driver.deleteAsync(dataStore, templateInfo, commandCallback);

        ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
        verify(commandCallback).complete(resultCaptor.capture());
        assertTrue(resultCaptor.getValue().isSuccess());
        verify(sanStrategy, never()).deleteCloudStackVolume(any());
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
    void testMigrationCapabilities() {
        assertTrue(driver.requiresAccessForMigration(volumeInfo));
        assertTrue(driver.zoneWideVolumesAvailableWithoutClusterMotion());
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
        when(host.getUuid()).thenReturn("host-uuid-1");

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);

        // Mock AccessGroup with existing igroup
        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("7");
            when(sanStrategy.getLunSerialNumber(anyString(), anyString())).thenReturn(LUN_SERIAL_NUMBER);

            // Execute
            boolean result = driver.grantAccess(volumeInfo, host, dataStore);

            // Verify
            assertTrue(result);
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
            verify(sanStrategy).getAccessGroup(any());
            verify(sanStrategy).ensureLunMapped(anyString(), anyString(), anyString());
            verify(volumeVO).setPath("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID);
            verify(volumeVO).set_iScsiName("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID);
            verify(sanStrategy, never()).validateInitiatorInAccessGroup(anyString(), anyString(), any(Igroup.class));
        }
    }

    @Test
    void testGrantAccess_UpdatesPathWhenOntapAssignsDifferentLunId() {
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
        when(host.getUuid()).thenReturn("host-uuid-1");

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);

        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("1");
            when(sanStrategy.getLunSerialNumber(anyString(), anyString())).thenReturn(LUN_SERIAL_NUMBER);

            boolean result = driver.grantAccess(volumeInfo, host, dataStore);

            assertTrue(result);
            verify(sanStrategy).ensureLunMapped(anyString(), anyString(), anyString());
            verify(volumeVO).setPath("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID);
            verify(volumeVO).set_iScsiName("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID);
        }
    }

    @Test
    void testGrantAccess_IgroupNotFound_CreatesNewIgroup() {
        // Setup - use HostVO mock since production code casts Host to HostVO
        HostVO hostVO = mock(HostVO.class);
        when(hostVO.getName()).thenReturn("host1");
        when(hostVO.getUuid()).thenReturn("host-uuid-1");

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

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(null);
            when(sanStrategy.createAccessGroup(any())).thenReturn(createdAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("0");
            when(sanStrategy.getLunSerialNumber(anyString(), anyString())).thenReturn(LUN_SERIAL_NUMBER);

            // Execute
            boolean result = driver.grantAccess(volumeInfo, hostVO, dataStore);

            // Verify
            assertTrue(result);
            verify(sanStrategy).getAccessGroup(any());
            verify(sanStrategy).createAccessGroup(any());
            verify(sanStrategy).ensureLunMapped(anyString(), anyString(), anyString());
            verify(volumeVO).setPath("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID);
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
        }
    }

    @Test
    void testGrantAccess_IscsiMigrating_ConnectsHostToPool() throws Exception {
        HostVO hostVO = mock(HostVO.class);
        when(hostVO.getUuid()).thenReturn("host-uuid-1");
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getState()).thenReturn(Volume.State.Migrating);

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);

        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("0");
            when(sanStrategy.getLunSerialNumber(anyString(), anyString())).thenReturn(LUN_SERIAL_NUMBER);

            assertTrue(driver.grantAccess(volumeInfo, hostVO, dataStore));
            verify(sanStrategy).ensureLunMapped(anyString(), anyString(), anyString());
            verify(storageManager).connectHostToSharedPool(hostVO, 1L);
        }
    }

    @Test
    void testGrantAccess_IscsiRemap_UpdatesCallerVolumePath() throws Exception {
        HostVO hostVO = mock(HostVO.class);
        when(hostVO.getUuid()).thenReturn("host-uuid-1");
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        VolumeVO dbVolumeVO = new VolumeVO(Volume.Type.DATADISK, "vol1", 1L, 1L, 1L, 1L, Storage.ProvisioningType.THIN, 1024L, null, null, null);
        dbVolumeVO.setPath("/iqn.1992-08.com.netapp:sn.123456/0");
        when(volumeDao.findById(100L)).thenReturn(dbVolumeVO);
        VolumeVO callerVolumeVO = new VolumeVO(Volume.Type.DATADISK, "vol1", 1L, 1L, 1L, 1L, Storage.ProvisioningType.THIN, 1024L, null, null, null);
        callerVolumeVO.setPath("/iqn.1992-08.com.netapp:sn.123456/0");
        when(volumeInfo.getVolume()).thenReturn(callerVolumeVO);

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(dbVolumeVO.getId(), OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        when(volumeDetailsDao.findDetail(dbVolumeVO.getId(), OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);

        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("2");
            when(sanStrategy.getLunSerialNumber(anyString(), anyString())).thenReturn(LUN_SERIAL_NUMBER);

            assertTrue(driver.grantAccess(volumeInfo, hostVO, dataStore));
            assertEquals("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID, callerVolumeVO.getPath());
            assertEquals("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID, callerVolumeVO.get_iScsiName());
        }
    }

    @Test
    void testGrantAccess_NFSReady_DoesNotUpdateExportPolicy() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getUuid()).thenReturn("vol-uuid");
        when(volumeVO.getState()).thenReturn(Volume.State.Ready);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);

            assertTrue(driver.grantAccess(volumeInfo, host, dataStore));
            verify(nasStrategy, never()).updateAccessGroup(any());
        }
    }

    @Test
    void testGrantAccess_NFSTaggedMigrationDest_AddsHostToExportPolicy() throws Exception {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        HostVO hostVO = mock(HostVO.class);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getState()).thenReturn(Volume.State.Ready);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.MIGRATION_DESTINATION))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.MIGRATION_DESTINATION, OntapStorageConstants.TRUE, false));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);

            assertTrue(driver.grantAccess(volumeInfo, hostVO, dataStore));
            verify(nasStrategy).updateAccessGroup(argThat(ag -> ag.getHostRuleAction() == AccessGroup.HostRuleAction.ADD));
            verify(storageManager).connectHostToSharedPool(hostVO, 1L);
        }
    }

    @Test
    void testGrantAccess_NFSUntaggedUnattachedVolume_DoesNotUpdateExportPolicy() throws Exception {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        HostVO hostVO = mock(HostVO.class);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getUuid()).thenReturn("vol-uuid");
        when(volumeVO.getState()).thenReturn(Volume.State.Ready);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.MIGRATION_DESTINATION)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);

            assertTrue(driver.grantAccess(volumeInfo, hostVO, dataStore));
            verify(nasStrategy, never()).updateAccessGroup(any());
            verify(storageManager, never()).connectHostToSharedPool(any(), anyLong());
        }
    }

    @Test
    void testGrantAccess_IscsiTaggedMigrationDest_ConnectsHostToPool() throws Exception {
        verifyIscsiGrantConnectsHost(true);
    }

    @Test
    void testGrantAccess_IscsiUntaggedUnattachedVolume_DoesNotConnectHostToPool() throws Exception {
        verifyIscsiGrantConnectsHost(false);
    }

    private void verifyIscsiGrantConnectsHost(boolean tagged) throws Exception {
        HostVO hostVO = mock(HostVO.class);
        when(hostVO.getUuid()).thenReturn("host-uuid-1");
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getState()).thenReturn(Volume.State.Ready);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false));
        lenient().when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.MIGRATION_DESTINATION))
                .thenReturn(tagged ? new VolumeDetailVO(100L, OntapStorageConstants.MIGRATION_DESTINATION,
                        OntapStorageConstants.TRUE, false) : null);

        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString()))
                    .thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("0");
            when(sanStrategy.getLunSerialNumber(anyString(), anyString())).thenReturn(LUN_SERIAL_NUMBER);

            assertTrue(driver.grantAccess(volumeInfo, hostVO, dataStore));
            verify(sanStrategy).ensureLunMapped(anyString(), anyString(), anyString());
            if (tagged) {
                verify(storageManager).connectHostToSharedPool(hostVO, 1L);
            } else {
                verify(storageManager, never()).connectHostToSharedPool(any(), anyLong());
            }
        }
    }

    @Test
    void testGrantAccess_NFSMigrating_AddsHostToExportPolicy() throws Exception {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        HostVO hostVO = mock(HostVO.class);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getState()).thenReturn(Volume.State.Migrating);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);

            assertTrue(driver.grantAccess(volumeInfo, hostVO, dataStore));

            ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
            verify(nasStrategy).updateAccessGroup(captor.capture());
            AccessGroup accessGroup = captor.getValue();
            assertEquals(1L, accessGroup.getStoragePoolId());
            assertEquals(AccessGroup.HostRuleAction.ADD, accessGroup.getHostRuleAction());
            assertEquals(hostVO, accessGroup.getHostsToConnect().get(0));
            verify(storageManager).connectHostToSharedPool(hostVO, 1L);
        }
    }

    @Test
    void testRevokeAccess_NFSSameCluster_DoesNotRemove() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        HostVO hostVO = mock(HostVO.class);
        when(hostVO.getName()).thenReturn("host1");
        when(hostVO.getClusterId()).thenReturn(10L);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getName()).thenReturn("test-volume");
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getClusterId()).thenReturn(10L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);

            driver.revokeAccess(volumeInfo, hostVO, dataStore);
            verify(nasStrategy, never()).updateAccessGroup(any());
        }
    }

    @Test
    void testRevokeAccess_NFSForeignCluster_RemovesHost() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        HostVO hostVO = mock(HostVO.class);
        when(hostVO.getName()).thenReturn("host2");
        when(hostVO.getClusterId()).thenReturn(20L);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getName()).thenReturn("test-volume");
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getClusterId()).thenReturn(10L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);

            driver.revokeAccess(volumeInfo, hostVO, dataStore);

            ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
            verify(nasStrategy).updateAccessGroup(captor.capture());
            AccessGroup accessGroup = captor.getValue();
            assertEquals(1L, accessGroup.getStoragePoolId());
            assertEquals(AccessGroup.HostRuleAction.REMOVE, accessGroup.getHostRuleAction());
            assertEquals(hostVO, accessGroup.getHostsToConnect().get(0));
        }
    }

    @Test
    void testRevokeAccess_NFSVolume_SkipsRevoke() {
        // Setup - NFS volumes have no LUN mapping, so revokeAccess is a no-op
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);

        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(host.getName()).thenReturn("host1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
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
        when(host.getUuid()).thenReturn("host-uuid-1");

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

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
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
    void testGetDataObjectSizeIncludingHypervisorSnapshotReserve_NoReserveAdded() {
        when(templateInfo.getSize()).thenReturn(5368709120L);

        assertEquals(5368709120L, driver.getDataObjectSizeIncludingHypervisorSnapshotReserve(templateInfo, storagePool));
    }

    @Test
    void testGetBytesRequiredForTemplate_AlreadyCached_ReturnsZero() {
        when(storagePool.getId()).thenReturn(1L);
        when(templateInfo.getId()).thenReturn(50L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Ready);
        when(templatePoolRef.getLocalDownloadPath()).thenReturn("template-lun-uuid");

        assertEquals(0L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));
    }

    @Test
    void testGetBytesRequiredForTemplate_NotCached_ReturnsVirtualSize() {
        when(storagePool.getId()).thenReturn(1L);
        when(templateInfo.getId()).thenReturn(50L);
        when(templateInfo.getSize()).thenReturn(5368709120L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(null);

        assertEquals(5368709120L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));
    }

    @Test
    void testGetBytesRequiredForTemplate_SpoolRefNotReady_ReturnsVirtualSize() {
        when(storagePool.getId()).thenReturn(1L);
        when(templateInfo.getId()).thenReturn(50L);
        when(templateInfo.getSize()).thenReturn(5368709120L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Allocated);

        assertEquals(5368709120L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));
    }

    @Test
    void testGetBytesRequiredForTemplate_ReadyWithoutBackendIdentity_ReturnsVirtualSize() {
        when(storagePool.getId()).thenReturn(1L);
        when(templateInfo.getId()).thenReturn(50L);
        when(templateInfo.getSize()).thenReturn(5368709120L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Ready);
        when(templatePoolRef.getLocalDownloadPath()).thenReturn(null);

        assertEquals(5368709120L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));
    }

    @Test
    void testGetBytesRequiredForTemplate_NfsCached_ReturnsZero() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(storagePool.getId()).thenReturn(1L);
        when(templateInfo.getId()).thenReturn(50L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Ready);
        when(templatePoolRef.getInstallPath()).thenReturn("/mnt/pool/template-uuid");

        assertEquals(0L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));
    }

    @Test
    void testCreateAsync_TemplateWithISCSI_CreatesLunAndRecordsCloneSource() {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);
        when(templateInfo.getSize()).thenReturn(5368709120L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getId()).thenReturn(7L);

        Lun templateLun = new Lun();
        templateLun.setName("/vol/vol1/cs_tmpl_50");
        templateLun.setUuid("template-lun-uuid");
        CloudStackVolume created = new CloudStackVolume();
        created.setLun(templateLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.createTemplateCache(any(), any(), any(), anyLong())).thenReturn(created);

            driver.createAsync(dataStore, templateInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            // local_download_path carries the clone source; install_path is left for grantAccess
            verify(templatePoolRef).setLocalDownloadPath("template-lun-uuid");
            verify(templatePoolRef).setTemplateSize(5368709120L);
            verify(templatePoolRef, never()).setInstallPath(any());
            verify(vmTemplatePoolDao).update(eq(7L), any(VMTemplateStoragePoolVO.class));
        }
    }


    @Test
    void testCreateAsync_TemplateWithISCSI_RollsBackLunWhenDbUpdateFails() {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);
        when(templateInfo.getSize()).thenReturn(5368709120L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getId()).thenReturn(7L);
        doThrow(new RuntimeException("db update failed")).when(vmTemplatePoolDao)
                .update(eq(7L), any(VMTemplateStoragePoolVO.class));

        Lun templateLun = new Lun();
        templateLun.setName("/vol/vol1/cs_tmpl_50");
        templateLun.setUuid("template-lun-uuid");
        CloudStackVolume created = new CloudStackVolume();
        created.setLun(templateLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.createTemplateCache(any(), any(), any(), anyLong())).thenReturn(created);
            doNothing().when(sanStrategy).deleteCloudStackVolume(any());

            driver.createAsync(dataStore, templateInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> deleteCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).deleteCloudStackVolume(deleteCaptor.capture());
            assertEquals("template-lun-uuid", deleteCaptor.getValue().getLun().getUuid());
            assertEquals("/vol/vol1/cs_tmpl_50", deleteCaptor.getValue().getLun().getName());
        }
    }

    @Test
    void testCreateAsync_TemplateWithISCSI_UnknownSize_Fails() {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getSize()).thenReturn(0L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.createTemplateCache(any(), any(), any(), anyLong()))
                    .thenThrow(new CloudRuntimeException("Unknown virtual size for template [50]"));

            driver.createAsync(dataStore, templateInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
        }
    }

    @Test
    void testCreateAsync_TemplateWithNFS_IsMetadataOnly() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());

        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getUuid()).thenReturn("template-uuid");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(nasStrategy);
            when(nasStrategy.createTemplateCache(any(), any(), any(), anyLong())).thenReturn(null);

            driver.createAsync(dataStore, templateInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(vmTemplatePoolDao, never()).update(any(Long.class), any(VMTemplateStoragePoolVO.class));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplate_ClonesWithoutGrowing() {
        stubVolumeCloneFromTemplate(5368709120L, 5368709120L);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertEquals("/vol/vol1/cs_tmpl_50", requestCaptor.getValue().getLun().getClone().getSource().getName());
            assertEquals("template-lun-uuid", requestCaptor.getValue().getLun().getClone().getSource().getUuid());
            assertSame(volumeInfo, requestCaptor.getValue().getVolumeInfo());
            verify(sanStrategy, never()).createCloudStackVolume(any());
            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
            verify(sanStrategy, never()).updateCloudStackVolume(any());
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.LUN_DOT_UUID), eq("cloned-lun-uuid"), eq(false));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplate_GrowsWhenOfferingIsLarger() {
        stubVolumeCloneFromTemplate(5368709120L, 21474836480L);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            verify(sanStrategy).resizeCloudStackVolume(eq(cloned), eq(21474836480L));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_IscsiSuccessWithoutGrow() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("snap-cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(requestCaptor.capture());
            Lun lunRequest = requestCaptor.getValue().getLun();
            assertEquals("/vol/vol1/.snapshot/snap_cs200/source_lun", lunRequest.getClone().getSource().getName());
            assertNull(lunRequest.getClone().getSource().getUuid());
            assertEquals("/vol/vol1/test_volume", lunRequest.getName());
            assertEquals("svm1", lunRequest.getSvm().getName());
            assertSame(volumeInfo, requestCaptor.getValue().getVolumeInfo());
            verify(sanStrategy, never()).createCloudStackVolume(any());
            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.LUN_DOT_UUID), eq("snap-cloned-lun-uuid"), eq(false));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_IscsiWithIops_SetsQosOnLunCreate() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        lenient().when(storagePool.getCapacityIops()).thenReturn(null);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("snap-cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);
        VolumeQosPolicy qosPolicy = qosPolicy("qos-snap-uuid", "cs_100_to_200_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloned, qosPolicy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertTrue(isQosPolicyReference(requestCaptor.getValue().getLun().getQosPolicy(),
                    "qos-snap-uuid", "cs_100_to_200_iops_svm1"));
            verify(sanStrategy, never()).updateCloudStackVolume(any());
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.QOS_POLICY_UUID),
                    eq("qos-snap-uuid"), eq(false));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_NfsWithIops_AttachesQosAfterClone() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flex-uuid");
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(volumeInfo.getUuid()).thenReturn("new-volume-uuid");
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        lenient().when(storagePool.getCapacityIops()).thenReturn(null);

        CloudStackVolume cloned = new CloudStackVolume();
        VolumeQosPolicy qosPolicy = lookedUpQosPolicy("qos-nfs-snap-uuid", "cs_100_to_200_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, nasStrategy, cloned, qosPolicy);
            when(nasStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);
            when(nasStrategy.updateCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(nasStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
            ArgumentCaptor<CloudStackVolume> cloneCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).cloneCloudStackVolume(cloneCaptor.capture());
            assertNull(cloneCaptor.getValue().getFile().getQosPolicy());
            verify(nasStrategy).updateCloudStackVolume(argThat(request ->
                    "1".equals(request.getDatastoreId())
                            && "flex-uuid".equals(request.getFlexVolumeUuid())
                            && request.getVolumeInfo() == volumeInfo
                            && request.getFile() != null
                            && isQosPolicyReference(request.getFile().getQosPolicy(),
                                    "qos-nfs-snap-uuid", "cs_100_to_200_iops_svm1")));
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.QOS_POLICY_UUID),
                    eq("qos-nfs-snap-uuid"), eq(false));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_NoIops_DoesNotCreatePolicy() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(null);
        when(volumeInfo.getMaxIops()).thenReturn(null);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("snap-cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            verify(sanStrategy, never()).createVolumeQosPolicy(nullable(String.class), nullable(Long.class), nullable(Long.class));
            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertNull(requestCaptor.getValue().getLun().getQosPolicy());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_CloneFails_DeletesNewPolicy() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        lenient().when(storagePool.getCapacityIops()).thenReturn(null);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-snap-uuid", "cs_100_to_200_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, new CloudStackVolume(), qosPolicy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenThrow(new CloudRuntimeException("clone failed"));

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).deleteVolumeQosPolicy("qos-snap-uuid");
            verify(volumeDetailsDao, never()).addDetail(eq(100L), eq(OntapStorageConstants.QOS_POLICY_UUID),
                    anyString(), eq(false));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_GrowsWhenOfferingIsLarger() {
        stubVolumeCloneFromSnapshot(5368709120L, 21474836480L, ProtocolType.ISCSI.name());

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("snap-cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            verify(sanStrategy).resizeCloudStackVolume(eq(cloned), eq(21474836480L));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_ResizeFails_DeletesOrphanedClone() {
        stubVolumeCloneFromSnapshot(5368709120L, 21474836480L, ProtocolType.ISCSI.name());

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("snap-cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);
            doThrow(new CloudRuntimeException("resize failed"))
                    .when(sanStrategy).resizeCloudStackVolume(eq(cloned), eq(21474836480L));
            doNothing().when(sanStrategy).deleteCloudStackVolume(eq(cloned));

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).deleteCloudStackVolume(eq(cloned));
            verify(volumeDetailsDao, never()).addDetail(eq(100L), eq(OntapStorageConstants.LUN_DOT_UUID), anyString(), anyBoolean());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_NfsSuccess() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(volumeInfo.getUuid()).thenReturn("new-volume-uuid");

        CloudStackVolume cloned = new CloudStackVolume();
        FileInfo file = new FileInfo();
        file.setPath("new-volume-uuid");
        cloned.setFile(file);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(nasStrategy);
            when(nasStrategy.cloneCloudStackVolume(any()))
                    .thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).cloneCloudStackVolume(requestCaptor.capture());
            CloudStackVolume request = requestCaptor.getValue();
            assertEquals("./.snapshot/snap_cs200/source-file-uuid", request.getFile().getPath());
            assertEquals("new-volume-uuid", request.getDestinationPath());
            assertEquals("1", request.getDatastoreId());
            verify(nasStrategy, never()).createCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_PoolMismatch_Fails() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());
        when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.PRIMARY_POOL_ID))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.PRIMARY_POOL_ID, "999", false));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy, never()).cloneCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_MissingDetail_Fails() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());
        when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.ONTAP_SNAP_NAME)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy, never()).cloneCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_ProtocolMismatch_Fails() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());
        // Pool is iSCSI (default stub details) but snapshot was taken on NFS.
        when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.PROTOCOL))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name(), false));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy, never()).cloneCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_NullStrategyResult_Fails() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenReturn(null);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_StrategyThrows_Fails() {
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenThrow(new CloudRuntimeException("ONTAP clone failed"));

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_PrefersSnapshotOverTemplate() {
        // Corner: when both details are present, the snapshot clone wins over the template clone.
        stubVolumeCloneFromSnapshot(5368709120L, 5368709120L, ProtocolType.ISCSI.name());
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.CLONE_OF_TEMPLATE))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.CLONE_OF_TEMPLATE, "50", false));

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("snap-cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertEquals("/vol/vol1/.snapshot/snap_cs200/source_lun",
                    requestCaptor.getValue().getLun().getClone().getSource().getName());
            verify(vmTemplatePoolDao, never()).findByPoolTemplate(anyLong(), anyLong(), any());
            verify(sanStrategy, never()).createCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_NfsGrowsWhenOfferingIsLarger() {
        stubVolumeCloneFromSnapshot(5368709120L, 21474836480L, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(volumeInfo.getUuid()).thenReturn("new-volume-uuid");

        CloudStackVolume cloned = new CloudStackVolume();
        FileInfo file = new FileInfo();
        file.setPath("new-volume-uuid");
        cloned.setFile(file);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(nasStrategy);
            when(nasStrategy.cloneCloudStackVolume(any()))
                    .thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            verify(nasStrategy).resizeCloudStackVolume(eq(cloned), eq(21474836480L));
            verify(sanStrategy, never()).cloneCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromSnapshot_SkipsGrowWhenSnapshotSizeUnknown() {
        stubVolumeCloneFromSnapshot(0L, 21474836480L, ProtocolType.ISCSI.name());

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("snap-cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
        }
    }

    /**
     * Sets up a volume create that the orchestrator has marked as a clone of a CloudStack snapshot.
     */
    private void stubVolumeCloneFromSnapshot(long snapshotSize, long volumeSize, String protocol) {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");
        lenient().when(volumeInfo.getSize()).thenReturn(volumeSize);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        // getId is only used after snapshot_details validation succeeds; lenient for early-fail tests.
        lenient().when(storagePool.getId()).thenReturn(1L);
        lenient().when(storagePool.getName()).thenReturn("vol1");
        lenient().when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        lenient().when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        lenient().when(volumeVO.getId()).thenReturn(100L);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.CLONE_OF_SNAPSHOT))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.CLONE_OF_SNAPSHOT, "200", false));

        String volumePath = ProtocolType.NFS3.name().equalsIgnoreCase(protocol)
                ? "source-file-uuid"
                : "/vol/vol1/source_lun";
        if (!ProtocolType.NFS3.name().equalsIgnoreCase(protocol)) {
            storagePoolDetails.put(OntapStorageConstants.VOLUME_NAME, "vol1");
        }
        // Lenient so early-fail tests can override/null individual keys without STRICT_STUBS noise.
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.ONTAP_SNAP_NAME))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.ONTAP_SNAP_NAME, "snap_cs200", false));
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.VOLUME_PATH))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.VOLUME_PATH, volumePath, false));
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.PRIMARY_POOL_ID))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.PRIMARY_POOL_ID, "1", false));
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.PROTOCOL))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.PROTOCOL, protocol, false));

        SnapshotVO snapshotVO = mock(SnapshotVO.class);
        lenient().when(snapshotDao.findById(200L)).thenReturn(snapshotVO);
        lenient().when(snapshotVO.getSize()).thenReturn(snapshotSize);
    }

    /**
     * Sets up a volume create that the orchestrator has marked as a clone of a cached template.
     */
    private void stubVolumeCloneFromTemplate(long templateSize, long volumeSize) {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");
        lenient().when(volumeInfo.getSize()).thenReturn(volumeSize);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        lenient().when(storagePool.getName()).thenReturn("vol1");
        lenient().when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        lenient().when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(storagePool.getCapacityIops()).thenReturn(null);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        lenient().when(volumeVO.getId()).thenReturn(100L);
        // createAsync checks cloneOfSnapshot before cloneOfTemplate; under STRICT_STUBS an
        // unstubbed alternate key on the same method is treated as an argument mismatch.
        lenient().when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.CLONE_OF_SNAPSHOT)).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.CLONE_OF_TEMPLATE))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.CLONE_OF_TEMPLATE, "50", false));

        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        lenient().when(templatePoolRef.getLocalDownloadPath()).thenReturn("template-lun-uuid");
        lenient().when(templatePoolRef.getTemplateSize()).thenReturn(templateSize);
    }

    @Test
    void testGrantAccess_Template_WritesInstallPathAndRefreshesStoreTarget() {
        PrimaryDataStore primaryDataStore = mock(PrimaryDataStore.class);
        Map<String, String> dataStoreDetails = new HashMap<>();
        dataStoreDetails.put(PrimaryDataStore.MANAGED_STORE_TARGET, "stale-value");

        when(primaryDataStore.getId()).thenReturn(1L);
        when(primaryDataStore.getDetails()).thenReturn(dataStoreDetails);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getId()).thenReturn(7L);

        when(host.getName()).thenReturn("host1");
        when(host.getUuid()).thenReturn("host-uuid-1");

        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString())).thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(eq("svm1"), eq("/vol/vol1/cs_tmpl_50"), eq("igroup1"))).thenReturn("3");
            when(sanStrategy.getLunSerialNumber(eq("svm1"), eq("/vol/vol1/cs_tmpl_50"))).thenReturn(LUN_SERIAL_NUMBER);

            assertTrue(driver.grantAccess(templateInfo, host, primaryDataStore));

            String expectedPath = "/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID;
            verify(templatePoolRef).setInstallPath(expectedPath);
            verify(vmTemplatePoolDao).update(eq(7L), any(VMTemplateStoragePoolVO.class));

            ArgumentCaptor<Map<String, String>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(primaryDataStore).setDetails(detailsCaptor.capture());
            assertEquals(expectedPath, detailsCaptor.getValue().get(PrimaryDataStore.MANAGED_STORE_TARGET));
        }
    }


    @Test
    void testGrantAccess_Template_UnmapsWhenInstallPathUpdateFails() {
        PrimaryDataStore primaryDataStore = mock(PrimaryDataStore.class);

        when(primaryDataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getId()).thenReturn(7L);
        doThrow(new RuntimeException("install path update failed")).when(vmTemplatePoolDao)
                .update(eq(7L), any(VMTemplateStoragePoolVO.class));

        when(host.getName()).thenReturn("host1");
        when(host.getUuid()).thenReturn("host-uuid-1");
        when(host.getStorageUrl()).thenReturn("iqn.1993-08.org.debian:01:host1");

        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = mock(Igroup.class);
        when(existingIgroup.getName()).thenReturn("igroup1");
        when(existingIgroup.getUuid()).thenReturn("igroup-uuid-123");
        existingAccessGroup.setIgroup(existingIgroup);

        Lun templateLun = new Lun();
        templateLun.setName("/vol/vol1/cs_tmpl_50");
        templateLun.setUuid("template-lun-uuid");
        CloudStackVolume cachedTemplate = new CloudStackVolume();
        cachedTemplate.setLun(templateLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString())).thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(eq("svm1"), eq("/vol/vol1/cs_tmpl_50"), eq("igroup1"))).thenReturn("3");
            when(sanStrategy.getLunSerialNumber(eq("svm1"), eq("/vol/vol1/cs_tmpl_50"))).thenReturn(LUN_SERIAL_NUMBER);
            when(sanStrategy.getCloudStackVolume(argThat(map ->
                    map != null && "/vol/vol1/cs_tmpl_50".equals(map.get("name")))))
                    .thenReturn(cachedTemplate);
            when(sanStrategy.validateInitiatorInAccessGroup(anyString(), anyString(), any(Igroup.class))).thenReturn(true);
            doNothing().when(sanStrategy).disableLogicalAccess(any());

            assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(templateInfo, host, primaryDataStore));

            verify(sanStrategy).disableLogicalAccess(argThat(map ->
                    map != null && "template-lun-uuid".equals(map.get("lun.uuid"))
                            && "igroup-uuid-123".equals(map.get("igroup.uuid"))));
        }
    }

    @Test
    void testGrantAccess_TemplateOnNFS_SkipsMapping() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());

        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getUuid()).thenReturn("template-uuid");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        assertTrue(driver.grantAccess(templateInfo, host, dataStore));
        verify(vmTemplatePoolDao, never()).update(any(Long.class), any(VMTemplateStoragePoolVO.class));
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplateNFS_ClonesFile() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        stubVolumeCloneFromTemplate(5368709120L, 5368709120L);
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(volumeInfo.getUuid()).thenReturn("volume-uuid");
        when(templatePoolRef.getInstallPath()).thenReturn("template-uuid");

        CloudStackVolume cloned = new CloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(nasStrategy);
            when(nasStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertEquals("template-uuid", requestCaptor.getValue().getFile().getPath());
            assertEquals("volume-uuid", requestCaptor.getValue().getDestinationPath());
            verify(nasStrategy, never()).resizeCloudStackVolume(any(), anyLong());
            verify(nasStrategy, never()).updateCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplate_RootWithIops_SetsQosOnLunCreate() {
        stubVolumeCloneFromTemplate(5368709120L, 5368709120L);
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/test_volume");
        clonedLun.setUuid("cloned-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);
        VolumeQosPolicy qosPolicy = qosPolicy("qos-root-uuid", "cs_100_to_200_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloned, qosPolicy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
            verify(sanStrategy).cloneCloudStackVolume(argThat(request ->
                    request.getLun() != null && request.getLun().getQosPolicy() != null
                            && "qos-root-uuid".equals(request.getLun().getQosPolicy().getUuid())));
            verify(sanStrategy, never()).updateCloudStackVolume(any());
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.QOS_POLICY_UUID),
                    eq("qos-root-uuid"), eq(false));
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplateNFS_RootWithIops_AttachesQosToFile() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flex-uuid");
        stubVolumeCloneFromTemplate(5368709120L, 5368709120L);
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(volumeInfo.getUuid()).thenReturn("volume-uuid");
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        when(templatePoolRef.getInstallPath()).thenReturn("template-uuid");

        CloudStackVolume cloned = new CloudStackVolume();
        VolumeQosPolicy qosPolicy = lookedUpQosPolicy("qos-nfs-root-uuid", "cs_100_to_200_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, nasStrategy, cloned, qosPolicy);
            when(nasStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);
            when(nasStrategy.updateCloudStackVolume(any())).thenReturn(cloned);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(nasStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
            verify(nasStrategy, never()).resizeCloudStackVolume(any(), anyLong());
            verify(nasStrategy).updateCloudStackVolume(argThat(request ->
                    "1".equals(request.getDatastoreId())
                            && "flex-uuid".equals(request.getFlexVolumeUuid())
                            && request.getVolumeInfo() == volumeInfo
                            && request.getFile() != null
                            && isQosPolicyReference(request.getFile().getQosPolicy(),
                                    "qos-nfs-root-uuid", "cs_100_to_200_iops_svm1")));
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.QOS_POLICY_UUID),
                    eq("qos-nfs-root-uuid"), eq(false));
        }
    }

    @Test
    void testDeleteAsync_Template_NFS_DeletesCachedFile() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid");

        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getInstallPath()).thenReturn("template-uuid");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(nasStrategy);

            driver.deleteAsync(dataStore, templateInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(nasStrategy).deleteFileByPath("flexvol-uuid", "template-uuid");
        }
    }

    @Test
    void testDeleteAsync_Template_NFS_FailsWhenFlexVolUuidMissing() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.remove(OntapStorageConstants.VOLUME_UUID);

        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getInstallPath()).thenReturn("template-uuid");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(nasStrategy);

            driver.deleteAsync(dataStore, templateInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains("volumeUUID"));
            verify(nasStrategy, never()).deleteFileByPath(any(), any());
        }
    }

    @Test
    void testRevokeAccess_Template_UnmapsCacheLun() {
        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        when(host.getStorageUrl()).thenReturn("iqn.1993-08.org.debian:01:host1");
        when(host.getName()).thenReturn("host1");
        when(host.getUuid()).thenReturn("host-uuid-1");

        Lun templateLun = new Lun();
        templateLun.setName("/vol/vol1/cs_tmpl_50");
        templateLun.setUuid("template-lun-uuid");
        CloudStackVolume cachedTemplate = new CloudStackVolume();
        cachedTemplate.setLun(templateLun);

        Igroup igroup = mock(Igroup.class);
        when(igroup.getName()).thenReturn("igroup1");
        when(igroup.getUuid()).thenReturn("igroup-uuid-123");
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setIgroup(igroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString())).thenReturn("igroup1");

            when(sanStrategy.getCloudStackVolume(argThat(map ->
                    map != null && "/vol/vol1/cs_tmpl_50".equals(map.get("name")))))
                    .thenReturn(cachedTemplate);
            when(sanStrategy.getAccessGroup(any())).thenReturn(accessGroup);
            when(sanStrategy.validateInitiatorInAccessGroup(anyString(), anyString(), any(Igroup.class))).thenReturn(true);

            driver.revokeAccess(templateInfo, host, dataStore);

            verify(sanStrategy).disableLogicalAccess(argThat(map ->
                    map != null && "template-lun-uuid".equals(map.get("lun.uuid"))
                            && "igroup-uuid-123".equals(map.get("igroup.uuid"))));
        }
    }

    @Test
    void testGetUsedIops_SumsMinIopsIncludingCreatingVolumes() {
        VolumeVO readyVolume = mock(VolumeVO.class);
        VolumeVO creatingVolume = mock(VolumeVO.class);
        VolumeVO volumeWithoutMinIops = mock(VolumeVO.class);

        when(storagePool.getId()).thenReturn(1L);
        when(readyVolume.getMinIops()).thenReturn(700L);
        when(creatingVolume.getMinIops()).thenReturn(300L);
        when(volumeWithoutMinIops.getMinIops()).thenReturn(null);
        when(volumeDao.findNonDestroyedVolumesByPoolId(1L, null))
                .thenReturn(List.of(readyVolume, creatingVolume, volumeWithoutMinIops));

        assertEquals(1000L, driver.getUsedIops(storagePool));
    }

    @Test
    void testResize_IopsUpdate_AttachesQosPolicy() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        stubResizeVolumeRecord(currentSize);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 0L, 5000L, null, false, null, null, true));
        when(storagePool.getCapacityIops()).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false));

        VolumeQosPolicy qosPolicy = lookedUpQosPolicy("qos-uuid", "cs_0_to_5000_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, iscsiCloudStackVolume(), qosPolicy);

            driver.resize(volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).resizeCloudStackVolume(argThat(request ->
                    request.getLun() != null
                            && "lun-uuid-123".equals(request.getLun().getUuid())
                            && isQosPolicyReference(request.getLun().getQosPolicy(), "qos-uuid", "cs_0_to_5000_iops_svm1")),
                    eq(currentSize));
            verify(sanStrategy, never()).updateCloudStackVolume(any());
            verify(volumeDetailsDao).addDetail(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false);
        }
    }

    @Test
    void testResize_IopsUpdate_NfsAttachesQosPolicy() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flex-uuid");
        stubResizeVolumeRecord(currentSize);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 100L, 200L, null, false, null, null, true));
        when(storagePool.getCapacityIops()).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(null);

        VolumeQosPolicy qosPolicy = lookedUpQosPolicy("qos-uuid", "cs_100_to_200_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, nasStrategy, nfsCloudStackVolume(), qosPolicy);

            driver.resize(volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(nasStrategy).resizeCloudStackVolume(argThat(request ->
                    "flex-uuid".equals(request.getFlexVolumeUuid())
                            && request.getFile() != null
                            && isQosPolicyReference(request.getFile().getQosPolicy(), "qos-uuid", "cs_100_to_200_iops_svm1")),
                    eq(currentSize));
            verify(nasStrategy, never()).updateCloudStackVolume(any());
            verify(volumeDetailsDao).addDetail(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false);
        }
    }

    @Test
    void testResize_SameQosPolicy_SkipsAttach() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        stubResizeVolumeRecord(currentSize);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 0L, 3333L, null, false, null, null, true));
        when(storagePool.getCapacityIops()).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));

        VolumeQosPolicy qosPolicy = qosPolicy("qos-uuid", "cs_0_to_3333_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, iscsiCloudStackVolume(), qosPolicy);

            driver.resize(volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).resizeCloudStackVolume(argThat(request -> request.getLun() == null), eq(currentSize));
            verify(sanStrategy, never()).updateCloudStackVolume(any());
        }
    }

    @Test
    void testResize_ClearIops_DetachesQosPolicy() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        stubResizeVolumeRecord(currentSize);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 0L, 0L, null, false, null, null, true));
        when(storagePool.getCapacityIops()).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);

            driver.resize(volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).resizeCloudStackVolume(argThat(request ->
                    request.getLun() != null
                            && isQosPolicyReference(request.getLun().getQosPolicy(), null, OntapStorageConstants.QOS_POLICY_NONE)),
                    eq(currentSize));
            verify(sanStrategy, never()).updateCloudStackVolume(any());
            verify(sanStrategy).deleteVolumeQosPolicy("qos-uuid");
        }
    }

    @Test
    void testResize_SwitchIops_OldPolicyDeleteFails_StillSucceeds() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        stubResizeVolumeRecord(currentSize);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 0L, 5000L, null, false, null, null, true));
        when(storagePool.getCapacityIops()).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "old-qos-uuid", false));
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false));

        VolumeQosPolicy qosPolicy = lookedUpQosPolicy("new-qos-uuid", "cs_0_to_5000_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, iscsiCloudStackVolume(), qosPolicy);
            doThrow(new CloudRuntimeException("ONTAP QoS delete failed")).when(sanStrategy)
                    .deleteVolumeQosPolicy("old-qos-uuid");

            driver.resize(volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).resizeCloudStackVolume(argThat(request ->
                    request.getLun() != null
                            && isQosPolicyReference(request.getLun().getQosPolicy(), "new-qos-uuid", "cs_0_to_5000_iops_svm1")),
                    eq(currentSize));
            verify(volumeDetailsDao).addDetail(100L, OntapStorageConstants.QOS_POLICY_UUID, "new-qos-uuid", false);
            verify(sanStrategy).deleteVolumeQosPolicy("old-qos-uuid");
            verify(sanStrategy, never()).deleteVolumeQosPolicy("new-qos-uuid");
        }
    }

    @Test
    void testResize_ClearIops_OldPolicyDeleteFails_StillSucceeds() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        stubResizeVolumeRecord(currentSize);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 0L, 0L, null, false, null, null, true));
        when(storagePool.getCapacityIops()).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);
            doThrow(new CloudRuntimeException("ONTAP QoS delete failed")).when(sanStrategy)
                    .deleteVolumeQosPolicy("qos-uuid");

            driver.resize(volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).deleteVolumeQosPolicy("qos-uuid");
        }
    }

    @Test
    void testResize_MinIopsBeyondPoolCapacity_Fails() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        VolumeVO otherVolume = mock(VolumeVO.class);
        when(otherVolume.getId()).thenReturn(50L);
        when(otherVolume.getMinIops()).thenReturn(800L);

        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getDataStore()).thenReturn(dataStore);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 300L, 1000L, null, false, null, null, true));
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getSize()).thenReturn(currentSize);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("ontap-pool");
        when(storagePool.getCapacityIops()).thenReturn(1000L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(1L, null)).thenReturn(List.of(otherVolume, volumeVO));

        driver.resize(volumeInfo, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains(
                "requested total of 1100 IOPS exceeds the pool IOPS capacity of 1000"));
        verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
        verify(sanStrategy, never()).updateCloudStackVolume(any());
    }

    @Test
    void testResize_MinIopsEqualToMaxIops_AttachesQosPolicy() {
        long currentSize = 4L * 1024 * 1024 * 1024;
        stubResizeVolumeRecord(currentSize);
        when(volumeInfo.getpayload()).thenReturn(
                new ResizeVolumePayload(currentSize, 100L, 100L, null, false, null, null, true));
        when(storagePool.getCapacityIops()).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false));

        VolumeQosPolicy qosPolicy = lookedUpQosPolicy("qos-uuid", "cs_100_to_100_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, iscsiCloudStackVolume(), qosPolicy);

            driver.resize(volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_100_to_100_iops_svm1"), eq(100L), eq(100L));
            verify(sanStrategy).resizeCloudStackVolume(argThat(request ->
                    request.getLun() != null
                            && "lun-uuid-123".equals(request.getLun().getUuid())
                            && isQosPolicyReference(request.getLun().getQosPolicy(), "qos-uuid", "cs_100_to_100_iops_svm1")),
                    eq(currentSize));
        }
    }

    @Test
    void testCreateAsync_MinIopsBeyondPoolCapacity_FailsWithCapacityDetails() {
        VolumeVO allocatedVolume = mock(VolumeVO.class);
        when(allocatedVolume.getId()).thenReturn(50L);
        when(allocatedVolume.getMinIops()).thenReturn(800L);

        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");
        when(volumeInfo.getMinIops()).thenReturn(300L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("ontap-pool");
        when(storagePool.getCapacityIops()).thenReturn(1000L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.findNonDestroyedVolumesByPoolId(1L, null)).thenReturn(List.of(allocatedVolume));

        driver.createAsync(dataStore, volumeInfo, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains(
                "storage pool ontap-pool: requested total of 1100 IOPS exceeds the pool IOPS capacity of 1000"));
        verify(sanStrategy, never()).createCloudStackVolume(any());
    }

    @Test
    void testCreateAsync_OtherCreatingVolumeCountsAgainstCapacity() {
        VolumeVO readyVolume = mock(VolumeVO.class);
        when(readyVolume.getId()).thenReturn(50L);
        when(readyVolume.getMinIops()).thenReturn(500L);

        VolumeVO otherCreatingVolume = mock(VolumeVO.class);
        when(otherCreatingVolume.getId()).thenReturn(60L);
        when(otherCreatingVolume.getMinIops()).thenReturn(300L);

        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");
        when(volumeInfo.getMinIops()).thenReturn(300L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("ontap-pool");
        when(storagePool.getCapacityIops()).thenReturn(1000L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.findNonDestroyedVolumesByPoolId(1L, null))
                .thenReturn(List.of(readyVolume, otherCreatingVolume));

        driver.createAsync(dataStore, volumeInfo, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains(
                "storage pool ontap-pool: requested total of 1100 IOPS exceeds the pool IOPS capacity of 1000"));
        verify(sanStrategy, never()).createCloudStackVolume(any());
    }

    @Test
    void testCreateAsync_DoesNotDoubleCountSelfWhenAlreadyCreating() {
        VolumeVO readyVolume = mock(VolumeVO.class);
        when(readyVolume.getId()).thenReturn(50L);
        when(readyVolume.getMinIops()).thenReturn(500L);

        VolumeVO selfCreatingVolume = mock(VolumeVO.class);
        when(selfCreatingVolume.getId()).thenReturn(100L);

        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");
        when(volumeInfo.getMinIops()).thenReturn(300L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        lenient().when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(storagePool.getCapacityIops()).thenReturn(1000L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(1L, null))
                .thenReturn(List.of(readyVolume, selfCreatingVolume));

        Lun mockLun = new Lun();
        mockLun.setName("/vol/vol1/lun1");
        mockLun.setUuid("lun-uuid-123");
        CloudStackVolume cloudStackVolume = new CloudStackVolume();
        cloudStackVolume.setLun(mockLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(cloudStackVolume);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).createCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_DataDiskFixedIops_CreatesAndPersistsQosPolicy() {
        stubIscsiVolumeCreate();
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-uuid", "cs_100_to_200_iops_svm1");
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloudStackVolume, qosPolicy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
            verify(sanStrategy).createCloudStackVolume(argThat(request ->
                    request.getLun() != null && request.getLun().getQosPolicy() != null
                            && "qos-uuid".equals(request.getLun().getQosPolicy().getUuid())));
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.QOS_POLICY_UUID),
                    eq("qos-uuid"), eq(false));
            verify(sanStrategy, never()).isAff();
        }
    }

    @Test
    void testCreateAsync_FasPoolWithMinIops_FailsWithoutCallingOntap() {
        stubIscsiVolumeCreate();
        storagePoolDetails.put(OntapStorageConstants.IS_AFF, "false");
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains(OntapStorageConstants.MIN_IOPS_NOT_SUPPORTED_ON_FAS));
            verify(sanStrategy, never()).isAff();
            verify(sanStrategy, never()).createVolumeQosPolicy(any(), any(), any());
        }
    }

    @Test
    void testCreateAsync_FasPoolWithMaxIopsOnly_CreatesQosPolicyWithoutNodeCall() {
        stubIscsiVolumeCreate();
        storagePoolDetails.put(OntapStorageConstants.IS_AFF, "false");
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(0L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-uuid", "cs_0_to_200_iops_svm1");
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloudStackVolume, qosPolicy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy, never()).isAff();
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_0_to_200_iops_svm1"), eq(0L), eq(200L));
        }
    }

    @Test
    void testCreateAsync_MissingAffDetail_FetchesFromNodesAndPersists() {
        stubIscsiVolumeCreate();
        storagePoolDetails.remove(OntapStorageConstants.IS_AFF);
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        when(sanStrategy.isAff()).thenReturn(true);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-uuid", "cs_100_to_200_iops_svm1");
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloudStackVolume, qosPolicy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).isAff();
            verify(storagePoolDetailsDao).addDetail(1L, OntapStorageConstants.IS_AFF, "true", false);
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
        }
    }

    @Test
    void testCreateAsync_MissingAffDetailOnFas_PersistsFalseAndRejectsMinIops() {
        stubIscsiVolumeCreate();
        storagePoolDetails.remove(OntapStorageConstants.IS_AFF);
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        when(sanStrategy.isAff()).thenReturn(false);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains(OntapStorageConstants.MIN_IOPS_NOT_SUPPORTED_ON_FAS));
            verify(sanStrategy).isAff();
            verify(storagePoolDetailsDao).addDetail(1L, OntapStorageConstants.IS_AFF, "false", false);
            verify(sanStrategy, never()).createVolumeQosPolicy(any(), any(), any());
        }
    }

    @Test
    void testCreateAsync_MissingAffDetail_IsAffThrows_StillCreatesWithMinIops() {
        stubIscsiVolumeCreate();
        storagePoolDetails.remove(OntapStorageConstants.IS_AFF);
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        when(sanStrategy.isAff()).thenThrow(new CloudRuntimeException(
                "Unable to determine whether the ONTAP cluster is AFF or FAS"));

        VolumeQosPolicy qosPolicy = qosPolicy("qos-uuid", "cs_100_to_200_iops_svm1");
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloudStackVolume, qosPolicy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).isAff();
            verify(storagePoolDetailsDao, never()).addDetail(eq(1L), eq(OntapStorageConstants.IS_AFF),
                    anyString(), eq(false));
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
            verify(sanStrategy).createCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_RootDiskCustomIops_CreatesAndPersistsQosPolicy() {
        stubIscsiVolumeCreate();
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(volumeInfo.getMinIops()).thenReturn(111L);
        when(volumeInfo.getMaxIops()).thenReturn(999L);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-root-uuid", "cs_111_to_999_iops_svm1");
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloudStackVolume, qosPolicy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_111_to_999_iops_svm1"), eq(111L), eq(999L));
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.QOS_POLICY_UUID),
                    eq("qos-root-uuid"), eq(false));
        }
    }

    @Test
    void testCreateAsync_NfsDataDiskWithIops_CreatesQosPolicy() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(storagePool.getCapacityIops()).thenReturn(null);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-nfs-uuid", "cs_100_to_200_iops_svm1");
        CloudStackVolume cloudStackVolume = new CloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, nasStrategy, cloudStackVolume, qosPolicy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(nasStrategy).createVolumeQosPolicy(eq("cs_100_to_200_iops_svm1"), eq(100L), eq(200L));
            verify(nasStrategy).createCloudStackVolume(argThat(request ->
                    request.getFile() != null && request.getFile().getQosPolicy() != null
                            && "qos-nfs-uuid".equals(request.getFile().getQosPolicy().getUuid())));
        }
    }

    @Test
    void testCreateAsync_MinIopsGreaterThanMaxIops_Fails() {
        stubIscsiVolumeCreate();
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(200L);
        when(volumeInfo.getMaxIops()).thenReturn(100L);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains(
                    "Minimum IOPS cannot be greater than maximum IOPS"));
            verify(sanStrategy, never()).createVolumeQosPolicy(any(), any(), any());
            verify(sanStrategy, never()).createCloudStackVolume(any());
        }
    }

    @Test
    void testCreateAsync_MinIopsEqualToMaxIops_CreatesQosPolicy() {
        stubIscsiVolumeCreate();
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(100L);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-uuid", "cs_100_to_100_iops_svm1");
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            stubQosCreateMocks(utilityMock, sanStrategy, cloudStackVolume, qosPolicy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).createVolumeQosPolicy(eq("cs_100_to_100_iops_svm1"), eq(100L), eq(100L));
            verify(sanStrategy).createCloudStackVolume(argThat(request ->
                    request.getLun() != null && request.getLun().getQosPolicy() != null
                            && "qos-uuid".equals(request.getLun().getQosPolicy().getUuid())));
        }
    }

    @Test
    void testCreateAsync_NoIops_DoesNotCreateQosPolicy() {
        stubIscsiVolumeCreate();
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(cloudStackVolume);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            verify(sanStrategy, never()).createVolumeQosPolicy(any(), any(), any());
            verify(sanStrategy).createCloudStackVolume(argThat(request ->
                    request.getLun() != null && request.getLun().getQosPolicy() == null));
        }
    }

    @Test
    void testCreateAsync_SwapVolumeWithIops_DoesNotCreateQosPolicy() {
        stubIscsiVolumeCreate();
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.SWAP);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        CloudStackVolume cloudStackVolume = iscsiCloudStackVolume();

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(cloudStackVolume);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            verify(sanStrategy, never()).createVolumeQosPolicy(any(), any(), any());
        }
    }

    @Test
    void testCreateAsync_QosCreateThenLunCreateFails_DeletesUnusedPolicy() {
        stubIscsiVolumeCreate();
        when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volumeInfo.getMinIops()).thenReturn(100L);
        when(volumeInfo.getMaxIops()).thenReturn(200L);

        VolumeQosPolicy qosPolicy = qosPolicy("qos-uuid", "cs_100_to_200_iops_svm1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(sanStrategy);
            when(sanStrategy.createVolumeQosPolicy(nullable(String.class), nullable(Long.class), nullable(Long.class)))
                    .thenReturn(qosPolicy);
            when(sanStrategy.createCloudStackVolume(any())).thenThrow(new CloudRuntimeException(
                    "Failed to create Lun: {\"error\":{\"code\":\"8454269\"}}"));

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy).deleteVolumeQosPolicy("qos-uuid");
        }
    }

    @Test
    void testDeleteAsync_DeletesUnusedQosPolicy() {
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        VolumeDetailVO lunUuidDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false);
        VolumeDetailVO qosDetail = new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false);

        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID)).thenReturn(lunUuidDetail);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(qosDetail);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            doNothing().when(sanStrategy).deleteCloudStackVolume(any());

            driver.deleteAsync(dataStore, volumeInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(volumeDetailsDao, never()).removeDetail(100L, OntapStorageConstants.QOS_POLICY_UUID);
            verify(sanStrategy).deleteVolumeQosPolicy("qos-uuid");
        }
    }

    @Test
    void testDeleteAsync_NfsClearsQosBeforeFileDelete() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flex-uuid");
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);
            when(nasStrategy.updateCloudStackVolume(any())).thenAnswer(invocation -> invocation.getArgument(0));
            doNothing().when(nasStrategy).deleteCloudStackVolume(any());

            driver.deleteAsync(dataStore, volumeInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(nasStrategy).updateCloudStackVolume(argThat(request ->
                    "flex-uuid".equals(request.getFlexVolumeUuid())
                            && request.getFile() != null
                            && isQosPolicyReference(request.getFile().getQosPolicy(), null, OntapStorageConstants.QOS_POLICY_NONE)));
            verify(nasStrategy).deleteCloudStackVolume(any());
            verify(nasStrategy).deleteVolumeQosPolicy("qos-uuid");
        }
    }

    @Test
    void testDeleteAsync_NfsRestoresQosWhenFileDeleteFails() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flex-uuid");
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);
            when(nasStrategy.updateCloudStackVolume(any())).thenAnswer(invocation -> invocation.getArgument(0));
            doThrow(new CloudRuntimeException("file delete failed")).when(nasStrategy).deleteCloudStackVolume(any());

            driver.deleteAsync(dataStore, volumeInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            ArgumentCaptor<CloudStackVolume> qosCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy, times(2)).updateCloudStackVolume(qosCaptor.capture());
            assertTrue(isQosPolicyReference(qosCaptor.getAllValues().get(0).getFile().getQosPolicy(),
                    null, OntapStorageConstants.QOS_POLICY_NONE));
            VolumeQosPolicy restored = qosCaptor.getAllValues().get(1).getFile().getQosPolicy();
            assertEquals("qos-uuid", restored.getUuid());
            assertNull(restored.getName());
            assertNull(restored.getSvm());
            assertNull(restored.getFixed());
            verify(nasStrategy, never()).deleteVolumeQosPolicy(any());
        }
    }

    @Test
    void testDeleteAsync_QosPolicyDeleteFails_VolumeDeleteStillSucceeds() {
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        VolumeDetailVO lunNameDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1", false);
        VolumeDetailVO lunUuidDetail = new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false);
        VolumeDetailVO qosDetail = new VolumeDetailVO(100L, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false);

        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(lunNameDetail);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID)).thenReturn(lunUuidDetail);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(qosDetail);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            doNothing().when(sanStrategy).deleteCloudStackVolume(any());
            doThrow(new CloudRuntimeException("ONTAP QoS delete failed")).when(sanStrategy)
                    .deleteVolumeQosPolicy("qos-uuid");

            driver.deleteAsync(dataStore, volumeInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(volumeDetailsDao, never()).removeDetail(100L, OntapStorageConstants.QOS_POLICY_UUID);
            verify(sanStrategy).deleteVolumeQosPolicy("qos-uuid");
        }
    }

    private void stubIscsiVolumeCreate() {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        lenient().when(storagePool.getId()).thenReturn(1L);
        lenient().when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        lenient().when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(storagePool.getCapacityIops()).thenReturn(null);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        lenient().when(volumeVO.getId()).thenReturn(100L);
    }

    private CloudStackVolume iscsiCloudStackVolume() {
        Lun mockLun = new Lun();
        mockLun.setName("/vol/vol1/lun1");
        mockLun.setUuid("lun-uuid-123");
        CloudStackVolume volume = new CloudStackVolume();
        volume.setLun(mockLun);
        return volume;
    }

    private CloudStackVolume nfsCloudStackVolume() {
        FileInfo fileInfo = new FileInfo();
        CloudStackVolume volume = new CloudStackVolume();
        volume.setFlexVolumeUuid("flex-uuid");
        volume.setFile(fileInfo);
        volume.setVolumeInfo(volumeInfo);
        return volume;
    }

    private VolumeQosPolicy qosPolicy(String uuid, String name) {
        VolumeQosPolicy policy = new VolumeQosPolicy();
        policy.setUuid(uuid);
        policy.setName(name);
        return policy;
    }

    /**
     * Policy as returned by an ONTAP lookup: name and uuid plus fields that must not be sent on a resize.
     */
    private VolumeQosPolicy lookedUpQosPolicy(String uuid, String name) {
        VolumeQosPolicy policy = qosPolicy(uuid, name);
        Svm svm = new Svm();
        svm.setName("svm1");
        policy.setSvm(svm);
        VolumeQosPolicy.Fixed fixed = new VolumeQosPolicy.Fixed();
        fixed.setMinThroughputIops(100L);
        fixed.setMaxThroughputIops(5000L);
        policy.setFixed(fixed);
        policy.setObjectCount(2);
        return policy;
    }

    private boolean isQosPolicyReference(VolumeQosPolicy sent, String uuid, String name) {
        return sent != null
                && name.equals(sent.getName())
                && (uuid == null ? sent.getUuid() == null : uuid.equals(sent.getUuid()))
                && sent.getSvm() == null
                && sent.getFixed() == null
                && sent.getObjectCount() == null;
    }

    private void stubQosCreateMocks(MockedStatic<OntapStorageUtils> utilityMock,
                                    StorageStrategy strategy,
                                    CloudStackVolume cloudStackVolume, VolumeQosPolicy qosPolicy) {
        utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                .thenReturn(strategy);
        when(strategy.createVolumeQosPolicy(nullable(String.class), nullable(Long.class), nullable(Long.class)))
                .thenReturn(qosPolicy);
        lenient().when(strategy.createCloudStackVolume(any())).thenReturn(cloudStackVolume);
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
    void testGetBytesRequiredForTemplate_NullArgs_ReturnsZero() {
        assertEquals(0L, driver.getBytesRequiredForTemplate(null, storagePool));
        assertEquals(0L, driver.getBytesRequiredForTemplate(templateInfo, null));
    }

    @Test
    void testCreateAsync_Template_StrategyThrows_Fails() {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);
        when(templateInfo.getSize()).thenReturn(5368709120L);
        when(templateInfo.getName()).thenReturn("tmpl");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.createTemplateCache(any(), any(), any(), anyLong()))
                    .thenThrow(new RuntimeException("unexpected"));

            driver.createAsync(dataStore, templateInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplate_NullCloneResult_Fails() {
        stubVolumeCloneFromTemplate(5368709120L, 5368709120L);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(null);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplateNFS_MissingInstallPath_Fails() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        stubVolumeCloneFromTemplate(5368709120L, 5368709120L);
        when(templatePoolRef.getInstallPath()).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(nasStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(nasStrategy, never()).cloneCloudStackVolume(any());
        }
    }

    @Test
    void testDeleteAsync_Template_NoSpoolRef_Succeeds() {
        when(dataStore.getId()).thenReturn(1L);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);

            driver.deleteAsync(dataStore, templateInfo, commandCallback);

            ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
            verify(sanStrategy, never()).deleteCloudStackVolume(any());
        }
    }

    @Test
    void testGrantAccess_Template_CreatesIgroupWhenMissing() {
        PrimaryDataStore primaryDataStore = mock(PrimaryDataStore.class);
        Map<String, String> dataStoreDetails = new HashMap<>();
        dataStoreDetails.put(PrimaryDataStore.MANAGED_STORE_TARGET, "stale-value");

        when(primaryDataStore.getId()).thenReturn(1L);
        when(primaryDataStore.getDetails()).thenReturn(dataStoreDetails);
        when(templateInfo.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(templatePoolRef);
        when(templatePoolRef.getId()).thenReturn(7L);

        HostVO hostVo = mock(HostVO.class);
        when(hostVo.getName()).thenReturn("host1");
        when(hostVo.getUuid()).thenReturn("host-uuid-1");

        AccessGroup createdAccessGroup = new AccessGroup();
        Igroup createdIgroup = new Igroup();
        createdIgroup.setName("igroup1");
        createdAccessGroup.setIgroup(createdIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString())).thenReturn("igroup1");

            when(sanStrategy.getAccessGroup(any())).thenReturn(null);
            when(sanStrategy.createAccessGroup(any())).thenReturn(createdAccessGroup);
            when(sanStrategy.ensureLunMapped(eq("svm1"), eq("/vol/vol1/cs_tmpl_50"), eq("igroup1"))).thenReturn("3");
            when(sanStrategy.getLunSerialNumber(eq("svm1"), eq("/vol/vol1/cs_tmpl_50"))).thenReturn(LUN_SERIAL_NUMBER);

            assertTrue(driver.grantAccess(templateInfo, hostVo, primaryDataStore));

            verify(sanStrategy).createAccessGroup(any());
            verify(templatePoolRef).setInstallPath("/iqn.1992-08.com.netapp:sn.123456/" + LUN_WWID);
        }
    }

    @Test
    void testCreateAsync_VolumeClonedFromTemplate_MissingSpoolRef_Fails() {
        when(dataStore.getId()).thenReturn(1L);
        when(dataStore.getName()).thenReturn("ontap-pool");
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getName()).thenReturn("test-volume");

        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        lenient().when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.CLONE_OF_SNAPSHOT)).thenReturn(null);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.CLONE_OF_TEMPLATE))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.CLONE_OF_TEMPLATE, "50", false));
        when(vmTemplatePoolDao.findByPoolTemplate(1L, 50L, null)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);

            driver.createAsync(dataStore, volumeInfo, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            verify(sanStrategy, never()).cloneCloudStackVolume(any());
        }
    }

    // =========================================================================
    // resize() tests
    // =========================================================================

    private void stubResizeVolumeRecord(long currentSize) {
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getDataStore()).thenReturn(dataStore);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeVO.getSize()).thenReturn(currentSize);
        lenient().when(volumeVO.getPath()).thenReturn("/vol/vol1/lun1");
        lenient().when(volumeDao.update(100L, volumeVO)).thenReturn(true);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
    }

    private DataObject stubResizeCommon(long currentSize, long newSize) {
        ResizeVolumePayload payload = new ResizeVolumePayload(newSize, null, null, null, false, "i-2-VM", null, false);
        DataObject data = volumeInfo;
        when(data.getType()).thenReturn(VOLUME);
        when(volumeInfo.getDataStore()).thenReturn(dataStore);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getpayload()).thenReturn(payload);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        // storagePool.getName() is only needed for the shrink-error message path
        lenient().when(storagePool.getName()).thenReturn("test-pool");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getSize()).thenReturn(currentSize);
        // volumeVO.getId() is only needed in the success path (volumeDao.update call)
        lenient().when(volumeVO.getId()).thenReturn(100L);
        lenient().when(volumeVO.getPath()).thenReturn("/vol/vol1/lun1");
        lenient().when(volumeDao.update(100L, volumeVO)).thenReturn(true);
        return data;
    }

    @Test
    void testResize_iSCSI_Success() {
        long currentSize = 10737418240L; // 10 GB
        long newSize = 21474836480L;     // 20 GB
        DataObject data = stubResizeCommon(currentSize, newSize);
        assertEquals(VOLUME, data.getType());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            doNothing().when(sanStrategy).resizeCloudStackVolume(any(), eq(newSize));

            driver.resize(data, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            // Driver only wires VolumeInfo; LUN UUID lookup is inside UnifiedSANStrategy
            ArgumentCaptor<CloudStackVolume> volumeCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).resizeCloudStackVolume(volumeCaptor.capture(), eq(newSize));
            assertEquals(volumeInfo, volumeCaptor.getValue().getVolumeInfo());
            assertNull(volumeCaptor.getValue().getLun());
            verify(volumeDetailsDao, never()).findDetail(anyLong(), eq(OntapStorageConstants.LUN_DOT_UUID));

            verify(volumeVO).setSize(newSize);
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
        }
    }

    @Test
    void testResize_NFS_Success() {
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        long currentSize = 10737418240L;
        long newSize = 21474836480L;
        DataObject data = stubResizeCommon(currentSize, newSize);
        assertEquals(VOLUME, data.getType());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(nasStrategy);
            doNothing().when(nasStrategy).resizeCloudStackVolume(any(), eq(newSize));

            driver.resize(data, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());

            ArgumentCaptor<CloudStackVolume> volumeCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).resizeCloudStackVolume(volumeCaptor.capture(), eq(newSize));
            assertEquals(volumeInfo, volumeCaptor.getValue().getVolumeInfo());
            // NFS has no LUN – driver must not look up a LUN UUID
            verify(volumeDetailsDao, never()).findDetail(anyLong(), eq(OntapStorageConstants.LUN_DOT_UUID));
            verify(volumeVO).setSize(newSize);
            verify(volumeDao).update(eq(100L), any(VolumeVO.class));
        }
    }

    @Test
    void testResize_NullPayload_Fails() {
        DataObject data = volumeInfo;
        when(data.getType()).thenReturn(VOLUME);
        assertEquals(VOLUME, data.getType());
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getpayload()).thenReturn(null);

        driver.resize(data, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Invalid resize payload"));
    }

    @Test
    void testResize_NullData_FailsAndCompletesCallbackOnce() {
        driver.resize(null, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback, times(1)).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Expected a VOLUME DataObject"));
    }

    @Test
    void testResize_NonVolumeDataType_FailsAndCompletesCallbackOnce() {
        DataObject data = templateInfo;
        when(data.getType()).thenReturn(TEMPLATE);
        when(templateInfo.getId()).thenReturn(50L);

        driver.resize(data, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback, times(1)).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Expected a VOLUME DataObject but received TEMPLATE"));
    }

    @Test
    void testResize_SnapshotDataType_FailsAndCompletesCallbackOnce() {
        DataObject data = mock(DataObject.class);
        when(data.getType()).thenReturn(SNAPSHOT);
        when(data.getId()).thenReturn(75L);

        driver.resize(data, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback, times(1)).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Expected a VOLUME DataObject but received SNAPSHOT"));
    }

    @Test
    void testResize_VolumeTypeWithoutVolumeDetails_FailsWithoutInternalTypeName() {
        DataObject data = mock(DataObject.class);
        when(data.getType()).thenReturn(VOLUME);

        driver.resize(data, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback, times(1)).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        String message = resultCaptor.getValue().getResult();
        assertTrue(message.contains("Invalid DataObjectType (VOLUME) passed to resize"));
        assertFalse(message.contains("VolumeInfo"));
    }

    @Test
    void testResize_NullNewSize_Fails() {
        // payload.newSize is null
        ResizeVolumePayload payload = new ResizeVolumePayload(null, null, null, null, false, "i-2-VM", null, false);
        DataObject data = volumeInfo;
        when(data.getType()).thenReturn(VOLUME);
        assertEquals(VOLUME, data.getType());
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getpayload()).thenReturn(payload);

        driver.resize(data, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Invalid resize payload"));
    }

    @Test
    void testResize_StoragePoolNotFound_Fails() {
        ResizeVolumePayload payload = new ResizeVolumePayload(21474836480L, null, null, null, false, "i-2-VM", null, false);
        DataObject data = volumeInfo;
        when(data.getType()).thenReturn(VOLUME);
        assertEquals(VOLUME, data.getType());
        when(volumeInfo.getDataStore()).thenReturn(dataStore);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getpayload()).thenReturn(payload);
        when(storagePoolDao.findById(1L)).thenReturn(null);

        driver.resize(data, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Storage pool not found"));
    }

    @Test
    void testResize_MissingDataStore_Fails() {
        ResizeVolumePayload payload = new ResizeVolumePayload(21474836480L, null, null, null, false, "none", null, false);
        DataObject data = volumeInfo;
        when(data.getType()).thenReturn(VOLUME);
        assertEquals(VOLUME, data.getType());
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getpayload()).thenReturn(payload);
        when(volumeInfo.getDataStore()).thenReturn(null);

        driver.resize(data, createCallback);

        ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(createCallback).complete(resultCaptor.capture());
        assertFalse(resultCaptor.getValue().isSuccess());
        assertTrue(resultCaptor.getValue().getResult().contains("Data store not found"));
    }

    @Test
    void testResize_VolumeVONotFound_Fails() {
        long newSize = 21474836480L;
        ResizeVolumePayload payload = new ResizeVolumePayload(newSize, null, null, null, false, "i-2-VM", null, false);
        DataObject data = volumeInfo;
        when(data.getType()).thenReturn(VOLUME);
        assertEquals(VOLUME, data.getType());
        when(volumeInfo.getDataStore()).thenReturn(dataStore);
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getpayload()).thenReturn(payload);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);

            driver.resize(data, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains("Volume not found"));
        }
    }

    @Test
    void testResize_MissingStoragePoolDetails_Fails() {
        long currentSize = 10737418240L;
        long newSize = 21474836480L;
        DataObject data = stubResizeCommon(currentSize, newSize);
        assertEquals(VOLUME, data.getType());
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(null))
                    .thenThrow(new CloudRuntimeException("Storage pool details are missing"));

            driver.resize(data, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback, times(1)).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains("Storage pool details are missing"));
            verify(volumeVO, never()).setSize(anyLong());
        }
    }

    @Test
    void testResize_ShrinkAttempt_Fails() {
        long currentSize = 21474836480L; // 20 GB
        long newSize = 10737418240L;     // 10 GB – smaller than current
        DataObject data = stubResizeCommon(currentSize, newSize);
        assertEquals(VOLUME, data.getType());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);

            driver.resize(data, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains("Unable to shrink volume"));
        }
    }

    private static VolumeVO temporarySnapshotCopyVolume() {
        return new VolumeVO(Volume.Type.DATADISK, "ROOT-5_20260924.TMP", 1L, 1L, 2L, 0L,
                Storage.ProvisioningType.THIN, 5368709120L, 0L, 0L, "");
    }

    private SnapshotInfo stubSnapshotToVolumeCopy(VolumeVO destVolume) {
        SnapshotInfo snapshotInfo = mock(SnapshotInfo.class);
        lenient().when(snapshotInfo.getType()).thenReturn(SNAPSHOT);
        lenient().when(snapshotInfo.getDataStore()).thenReturn(dataStore);
        lenient().when(snapshotInfo.getId()).thenReturn(200L);
        lenient().when(volumeInfo.getType()).thenReturn(VOLUME);
        lenient().when(volumeInfo.getDataStore()).thenReturn(dataStore);
        lenient().when(volumeInfo.getId()).thenReturn(100L);
        // VolumeObject.getName() reads the VolumeVO, which copyAsync renames before cloning.
        lenient().when(volumeInfo.getName()).thenReturn("cs_tmp_snap_200_100");
        lenient().when(dataStore.getId()).thenReturn(1L);
        lenient().when(dataStore.getRole()).thenReturn(DataStoreRole.Primary);
        lenient().when(volumeDao.findById(100L)).thenReturn(destVolume);
        return snapshotInfo;
    }

    private void stubSnapshotCloneSource(String protocol) {
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        lenient().when(storagePool.getId()).thenReturn(1L);
        lenient().when(storagePool.getName()).thenReturn("vol1");
        lenient().when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        storagePoolDetails.put(OntapStorageConstants.PROTOCOL, protocol);
        storagePoolDetails.put(OntapStorageConstants.VOLUME_NAME, "vol1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        lenient().when(volumeInfo.getSize()).thenReturn(5368709120L);

        String volumePath = ProtocolType.NFS3.name().equals(protocol) ? "source-file-uuid" : "/vol/vol1/source_lun";
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.ONTAP_SNAP_NAME))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.ONTAP_SNAP_NAME, "snap_cs200", false));
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.VOLUME_PATH))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.VOLUME_PATH, volumePath, false));
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.PRIMARY_POOL_ID))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.PRIMARY_POOL_ID, "1", false));
        lenient().when(snapshotDetailsDao.findDetail(200L, OntapStorageConstants.PROTOCOL))
                .thenReturn(new SnapshotDetailsVO(200L, OntapStorageConstants.PROTOCOL, protocol, false));
        SnapshotVO snapshotVO = mock(SnapshotVO.class);
        lenient().when(snapshotDao.findById(200L)).thenReturn(snapshotVO);
        lenient().when(snapshotVO.getSize()).thenReturn(5368709120L);
    }

    @Test
    void testCanCopy_TemporarySnapshotCopyOnSamePool_ReturnsTrue() {
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(temporarySnapshotCopyVolume());

        assertTrue(driver.canCopy(snapshotInfo, volumeInfo));
    }

    @Test
    void testCanCopy_CreateVolumeFromSnapshot_ReturnsFalse() {
        VolumeVO userVolume = temporarySnapshotCopyVolume();
        userVolume.setDiskOfferingId(5L);
        userVolume.setState(Volume.State.Creating);
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(userVolume);

        assertFalse(driver.canCopy(snapshotInfo, volumeInfo));
    }

    @Test
    void testCanCopy_AllocatedVolumeWithDiskOffering_ReturnsFalse() {
        VolumeVO userVolume = temporarySnapshotCopyVolume();
        userVolume.setDiskOfferingId(5L);
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(userVolume);

        assertFalse(driver.canCopy(snapshotInfo, volumeInfo));
    }

    @Test
    void testCanCopy_DifferentPool_ReturnsFalse() {
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(temporarySnapshotCopyVolume());
        DataStore otherStore = mock(DataStore.class);
        when(otherStore.getId()).thenReturn(2L);
        when(volumeInfo.getDataStore()).thenReturn(otherStore);

        assertFalse(driver.canCopy(snapshotInfo, volumeInfo));
        verify(volumeDao, never()).findById(anyLong());
    }

    @Test
    void testCanCopy_TemplateCacheCopies_ReturnFalse() {
        TemplateInfo templateOnPrimary = mock(TemplateInfo.class);
        lenient().when(templateInfo.getType()).thenReturn(TEMPLATE);
        lenient().when(templateInfo.getDataStore()).thenReturn(dataStore);
        lenient().when(templateOnPrimary.getType()).thenReturn(TEMPLATE);
        lenient().when(templateOnPrimary.getDataStore()).thenReturn(dataStore);
        lenient().when(volumeInfo.getType()).thenReturn(VOLUME);
        lenient().when(volumeInfo.getDataStore()).thenReturn(dataStore);

        assertFalse(driver.canCopy(templateInfo, templateOnPrimary));
        assertFalse(driver.canCopy(templateOnPrimary, volumeInfo));
        verify(volumeDao, never()).findById(anyLong());
    }

    @Test
    void testCanCopy_VolumeMigration_ReturnsFalse() {
        VolumeInfo destVolumeInfo = mock(VolumeInfo.class);
        DataStore destStore = mock(DataStore.class);
        lenient().when(volumeInfo.getType()).thenReturn(VOLUME);
        lenient().when(volumeInfo.getDataStore()).thenReturn(dataStore);
        lenient().when(destVolumeInfo.getType()).thenReturn(VOLUME);
        lenient().when(destVolumeInfo.getDataStore()).thenReturn(destStore);

        assertFalse(driver.canCopy(volumeInfo, destVolumeInfo));
        verify(volumeDao, never()).findById(anyLong());
    }

    @Test
    void testCopyAsync_VolumeMigration_Throws() {
        VolumeInfo destVolumeInfo = mock(VolumeInfo.class);
        DataStore destStore = mock(DataStore.class);
        lenient().when(volumeInfo.getType()).thenReturn(VOLUME);
        lenient().when(volumeInfo.getDataStore()).thenReturn(dataStore);
        lenient().when(destVolumeInfo.getType()).thenReturn(VOLUME);
        lenient().when(destVolumeInfo.getDataStore()).thenReturn(destStore);

        assertThrows(UnsupportedOperationException.class,
                () -> driver.copyAsync(volumeInfo, destVolumeInfo, null, null));
        verify(sanStrategy, never()).cloneCloudStackVolume(any());
        verify(nasStrategy, never()).cloneCloudStackVolume(any());
    }

    @Test
    void testCopyAsync_UnsupportedPair_Throws() {
        lenient().when(templateInfo.getType()).thenReturn(TEMPLATE);
        lenient().when(templateInfo.getDataStore()).thenReturn(dataStore);
        lenient().when(volumeInfo.getType()).thenReturn(VOLUME);
        lenient().when(volumeInfo.getDataStore()).thenReturn(dataStore);

        assertThrows(UnsupportedOperationException.class, () -> driver.copyAsync(templateInfo, volumeInfo, null, null));
    }

    @Test
    void testCopyAsync_IscsiTemporarySnapshotCopy_ClonesLunAndRecordsIdentity() {
        VolumeVO dbVolume = temporarySnapshotCopyVolume();
        VolumeVO inMemoryVolume = temporarySnapshotCopyVolume();
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(dbVolume);
        when(volumeInfo.getVolume()).thenReturn(inMemoryVolume);
        stubSnapshotCloneSource(ProtocolType.ISCSI.name());
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/cs_tmp_snap_200_100");
        clonedLun.setUuid("tmp-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.copyAsync(snapshotInfo, volumeInfo, null, null);

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertEquals("/vol/vol1/.snapshot/snap_cs200/source_lun",
                    requestCaptor.getValue().getLun().getClone().getSource().getName());
            assertEquals("/vol/vol1/cs_tmp_snap_200_100", requestCaptor.getValue().getLun().getName());
            assertSame(volumeInfo, requestCaptor.getValue().getVolumeInfo());

            assertEquals("cs_tmp_snap_200_100", dbVolume.getName());
            assertEquals("cs_tmp_snap_200_100", inMemoryVolume.getName());
            assertEquals(Storage.ImageFormat.RAW, dbVolume.getFormat());
            assertEquals("tmp-lun-uuid", dbVolume.getFolder());
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.LUN_DOT_UUID), eq("tmp-lun-uuid"), eq(false));
            verify(volumeDetailsDao).addDetail(eq(100L), eq(OntapStorageConstants.LUN_DOT_NAME), eq("/vol/vol1/cs_tmp_snap_200_100"), eq(false));
            verify(volumeDao).update(anyLong(), eq(dbVolume));
            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
            verify(sanStrategy, never()).createVolumeQosPolicy(anyString(), any(), any());
        }
    }

    @Test
    void testCopyAsync_IscsiTemporarySnapshotCopy_IgnoresVolumeIops() {
        VolumeVO dbVolume = temporarySnapshotCopyVolume();
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(dbVolume);
        when(volumeInfo.getVolume()).thenReturn(temporarySnapshotCopyVolume());
        stubSnapshotCloneSource(ProtocolType.ISCSI.name());
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        lenient().when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        lenient().when(volumeInfo.getMinIops()).thenReturn(100L);
        lenient().when(volumeInfo.getMaxIops()).thenReturn(200L);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/cs_tmp_snap_200_100");
        clonedLun.setUuid("tmp-lun-uuid");
        CloudStackVolume cloned = new CloudStackVolume();
        cloned.setLun(clonedLun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.copyAsync(snapshotInfo, volumeInfo, null, null);

            verify(sanStrategy, never()).createVolumeQosPolicy(nullable(String.class), nullable(Long.class), nullable(Long.class));
            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertNull(requestCaptor.getValue().getLun().getQosPolicy());
        }
    }

    @Test
    void testResize_StrategyThrows_Fails() {
        long currentSize = 10737418240L;
        long newSize = 21474836480L;
        DataObject data = stubResizeCommon(currentSize, newSize);
        assertEquals(VOLUME, data.getType());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            doThrow(new com.cloud.utils.exception.CloudRuntimeException("ONTAP resize failed"))
                    .when(sanStrategy).resizeCloudStackVolume(any(), eq(newSize));

            driver.resize(data, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains("ONTAP resize failed"));
            // volumeVO size must NOT be updated on failure
            verify(volumeVO, never()).setSize(anyLong());
        }
    }

    @Test
    void testCopyAsync_NfsTemporarySnapshotCopy_SetsFilePath() {
        VolumeVO dbVolume = temporarySnapshotCopyVolume();
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(dbVolume);
        when(volumeInfo.getVolume()).thenReturn(temporarySnapshotCopyVolume());
        when(volumeInfo.getUuid()).thenReturn("tmp-volume-uuid");
        stubSnapshotCloneSource(ProtocolType.NFS3.name());
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        CloudStackVolume cloned = new CloudStackVolume();
        FileInfo file = new FileInfo();
        file.setPath("tmp-volume-uuid");
        cloned.setFile(file);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(nasStrategy);
            when(nasStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            driver.copyAsync(snapshotInfo, volumeInfo, null, null);

            ArgumentCaptor<CloudStackVolume> requestCaptor = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).cloneCloudStackVolume(requestCaptor.capture());
            assertEquals("./.snapshot/snap_cs200/source-file-uuid",
                    requestCaptor.getValue().getFile().getPath());
            assertEquals("tmp-volume-uuid", requestCaptor.getValue().getDestinationPath());

            assertEquals("tmp-volume-uuid", dbVolume.getPath());
            assertEquals(Storage.ImageFormat.QCOW2, dbVolume.getFormat());
            verify(volumeDao).update(anyLong(), eq(dbVolume));
        }
    }

    @Test
    void testResize_EqualSize_InvokesStrategyAndSucceeds() {
        long size = 10737418240L;
        DataObject data = stubResizeCommon(size, size);
        assertEquals(VOLUME, data.getType());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);

            driver.resize(data, createCallback);

            verify(sanStrategy).resizeCloudStackVolume(any(), eq(size));
            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(resultCaptor.capture());
            assertTrue(resultCaptor.getValue().isSuccess());
        }
    }

    @Test
    void testCopyAsync_CloneFailsWithoutCallback_Throws() {
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(temporarySnapshotCopyVolume());
        when(volumeInfo.getVolume()).thenReturn(temporarySnapshotCopyVolume());
        stubSnapshotCloneSource(ProtocolType.ISCSI.name());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenThrow(new CloudRuntimeException("clone failed"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.copyAsync(snapshotInfo, volumeInfo, null, null));
            assertTrue(ex.getMessage().contains("clone failed"));
            verify(volumeDetailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
        }
    }

    @Test
    void testResize_DatabaseUpdateFailure_ReturnsFailure() {
        long currentSize = 10737418240L;
        long newSize = 21474836480L;
        DataObject data = stubResizeCommon(currentSize, newSize);
        assertEquals(VOLUME, data.getType());
        when(volumeDao.update(100L, volumeVO)).thenReturn(false);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);

            driver.resize(data, createCallback);

            ArgumentCaptor<CreateCmdResult> resultCaptor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback, times(1)).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
            assertTrue(resultCaptor.getValue().getResult().contains("Failed to update volume"));
        }
    }

    // =========================================================================
    // getUsedBytes() tests
    // =========================================================================

    @Test
    void testGetUsedBytes_NullPool_ThrowsException() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> driver.getUsedBytes(null));
        assertTrue(ex.getMessage().contains("storagePool is null, ensure the pool exists and is fully initialised before querying used bytes"));
    }

    @Test
    void testGetUsedBytes_NoFlexVolUuid_ThrowsException() {
        // VOLUME_UUID key is absent from pool details
        storagePoolDetails.remove(OntapStorageConstants.VOLUME_UUID);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> driver.getUsedBytes(storagePool));
        assertTrue(ex.getMessage().contains("FlexVolume UUID not found in pool details for pool 1"));
    }

    @Test
    void testGetUsedBytes_BlankFlexVolUuid_ThrowsException() {
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "   ");
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> driver.getUsedBytes(storagePool));
        assertTrue(ex.getMessage().contains("FlexVolume UUID not found in pool details for pool 1"));
    }

    @Test
    void testGetUsedBytes_NullPoolDetails_ThrowsException() {
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(null);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> driver.getUsedBytes(storagePool));
        assertTrue(ex.getMessage().contains("FlexVolume UUID not found in pool details for pool 1"));
    }

    @Test
    void testGetUsedBytes_Success_ReturnsUsedBytes() {
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-123");
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        var flexVol = new org.apache.cloudstack.storage.feign.model.Volume();
        VolumeSpace space = new VolumeSpace();
        space.setUsed(10737418240L); // 10 GB
        flexVol.setSpace(space);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            Map<String, Object> queryParams = Map.of(OntapStorageConstants.FIELDS, OntapStorageConstants.SPACE_USED);
            when(sanStrategy.getStorageVolume(eq("flexvol-uuid-123"), eq(queryParams))).thenReturn(flexVol);

            assertEquals(10737418240L, driver.getUsedBytes(storagePool));
            verify(sanStrategy).getStorageVolume(eq("flexvol-uuid-123"), eq(queryParams));
        }
    }

    @Test
    void testGetUsedBytes_FlexVolNotFound_ThrowsException() {
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-123");
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            when(sanStrategy.getStorageVolume(eq("flexvol-uuid-123"), any())).thenReturn(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.getUsedBytes(storagePool));
            assertTrue(ex.getMessage().contains("was not found on ONTAP"));
        }
    }

    @Test
    void testCopyAsync_CloneFailsWithCallback_CompletesWithFailure() {
        SnapshotInfo snapshotInfo = stubSnapshotToVolumeCopy(temporarySnapshotCopyVolume());
        when(volumeInfo.getVolume()).thenReturn(temporarySnapshotCopyVolume());
        stubSnapshotCloneSource(ProtocolType.ISCSI.name());
        @SuppressWarnings("unchecked")
        AsyncCompletionCallback<CopyCommandResult> copyCallback = mock(AsyncCompletionCallback.class);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(sanStrategy);
            when(sanStrategy.cloneCloudStackVolume(any()))
                    .thenThrow(new CloudRuntimeException("clone failed"));

            driver.copyAsync(snapshotInfo, volumeInfo, null, copyCallback);

            ArgumentCaptor<CopyCommandResult> resultCaptor = ArgumentCaptor.forClass(CopyCommandResult.class);
            verify(copyCallback).complete(resultCaptor.capture());
            assertFalse(resultCaptor.getValue().isSuccess());
        }
    }

    @Test
    void testDeleteAsync_TemporarySnapshotCopy_NullCallbackRemovesRecord() {
        VolumeVO tempVolume = temporarySnapshotCopyVolume();
        tempVolume.setName("cs_tmp_snap_200_100");
        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(tempVolume);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/cs_tmp_snap_200_100", false));
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "tmp-lun-uuid", false));
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);

            driver.deleteAsync(dataStore, volumeInfo, null);

            verify(sanStrategy).deleteCloudStackVolume(any(CloudStackVolume.class));
            verify(volumeDetailsDao).removeDetails(100L);
            verify(volumeDao).remove(100L);
        }
    }

    @Test
    void testGetUsedBytes_FlexVolNullSpace_ThrowsException() {
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-123");
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        var flexVol = new org.apache.cloudstack.storage.feign.model.Volume();
        // space is intentionally left null

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            when(sanStrategy.getStorageVolume(eq("flexvol-uuid-123"), any())).thenReturn(flexVol);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.getUsedBytes(storagePool));
            assertTrue(ex.getMessage().contains("no space information"));
        }
    }

    @Test
    void testGetUsedBytes_OntapException_PropagatesException() {
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-123");
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            when(sanStrategy.getStorageVolume(eq("flexvol-uuid-123"), any()))
                    .thenThrow(new com.cloud.utils.exception.CloudRuntimeException("ONTAP unreachable"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.getUsedBytes(storagePool));
            assertEquals("ONTAP unreachable", ex.getMessage());
        }
    }

    @Test
    void testGetUsedBytes_UnexpectedException_IsWrapped() {
        storagePoolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-123");
        when(storagePool.getId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails))
                    .thenReturn(sanStrategy);
            when(sanStrategy.getStorageVolume(eq("flexvol-uuid-123"), any()))
                    .thenThrow(new IllegalStateException("invalid ONTAP response"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.getUsedBytes(storagePool));
            assertTrue(ex.getMessage().contains("Could not read used space"));
            assertTrue(ex.getMessage().contains("invalid ONTAP response"));
            assertTrue(ex.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void testGrantAccess_TemporarySnapshotCopy_SyncsIscsiPathOnInMemoryVolume() {
        String iscsiPath = "/iqn.1992-08.com.netapp:sn.123456/0";
        VolumeVO dbVolume = mock(VolumeVO.class);
        when(dbVolume.getId()).thenReturn(100L);
        when(dbVolume.getState()).thenReturn(Volume.State.Allocated);
        when(dbVolume.getDiskOfferingId()).thenReturn(0L);
        when(dbVolume.getPath()).thenReturn(iscsiPath);
        when(dbVolume.get_iScsiName()).thenReturn(iscsiPath);
        VolumeVO inMemoryVolume = temporarySnapshotCopyVolume();

        when(dataStore.getId()).thenReturn(1L);
        when(volumeInfo.getType()).thenReturn(VOLUME);
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getVolume()).thenReturn(inMemoryVolume);
        when(storagePoolDao.findById(1L)).thenReturn(storagePool);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePool.getPath()).thenReturn("iqn.1992-08.com.netapp:sn.123456");
        when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(storagePoolDetails);
        when(volumeDao.findById(100L)).thenReturn(dbVolume);
        when(host.getUuid()).thenReturn("host-uuid-1");
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_NAME))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/cs_tmp_snap_200_100", false));

        AccessGroup existingAccessGroup = new AccessGroup();
        Igroup existingIgroup = new Igroup();
        existingIgroup.setName("igroup1");
        existingAccessGroup.setIgroup(existingIgroup);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(storagePoolDetails)).thenReturn(sanStrategy);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName(anyString(), anyString())).thenReturn("igroup1");
            when(sanStrategy.getAccessGroup(any())).thenReturn(existingAccessGroup);
            when(sanStrategy.ensureLunMapped(anyString(), anyString(), anyString())).thenReturn("0");
            when(sanStrategy.getLunSerialNumber(anyString(), anyString())).thenReturn(LUN_SERIAL_NUMBER);

            assertTrue(driver.grantAccess(volumeInfo, host, dataStore));

            assertEquals(iscsiPath, inMemoryVolume.getPath());
            assertEquals(iscsiPath, inMemoryVolume.get_iScsiName());
        }
    }

    /**
     * Host access (grant/revoke), QoS policy application and volume-delete failure handling of
     * {@link OntapPrimaryDatastoreDriver}.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class OntapPrimaryDatastoreDriverAccessAndQosTest {

        private static final long POOL_ID = 5L;
        private static final long VOLUME_ID = 10L;
        private static final long TEMPLATE_ID = 31L;
        private static final String SVM = "svm1";
        private static final String LUN_NAME = "/vol/flexvol/lun1";
        private static final String LUN_UUID = "lun-uuid";
        private static final String IGROUP_UUID = "igroup-uuid";
        private static final String TARGET_IQN = "iqn.1992-08.com.netapp:sn.1";
        private static final String WWID = "600a098078304d2d383f2f6b45734a54";
        private static final String SERIAL = "x0M-8?/kEsJT";

        @Mock
        private StoragePoolDetailsDao storagePoolDetailsDao;
        @Mock
        private PrimaryDataStoreDao storagePoolDao;
        @Mock
        private VolumeDao volumeDao;
        @Mock
        private VolumeDetailsDao volumeDetailsDao;
        @Mock
        private StorageManager storageManager;

        @Mock
        private DataStore dataStore;
        @Mock
        private StoragePoolVO storagePool;
        @Mock
        private VolumeVO volumeVO;
        @Mock
        private VolumeInfo volumeInfo;
        @Mock
        private TemplateInfo templateInfo;
        @Mock
        private HostVO host;
        @Mock
        private UnifiedSANStrategy sanStrategy;
        @Mock
        private StorageStrategy nasStrategy;
        @Mock
        private AsyncCompletionCallback<CommandResult> commandCallback;

        @InjectMocks
        private OntapPrimaryDatastoreDriver driver;

        private Map<String, String> poolDetails;

        @BeforeEach
        void setUp() throws Exception {
            poolDetails = new HashMap<>();
            poolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());
            poolDetails.put(OntapStorageConstants.SVM_NAME, SVM);
            poolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid");
            poolDetails.put(OntapStorageConstants.IS_AFF, "true");

            when(dataStore.getId()).thenReturn(POOL_ID);
            when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePool);
            when(storagePool.getId()).thenReturn(POOL_ID);
            when(storagePool.getName()).thenReturn("flexvol");
            when(storagePool.getPath()).thenReturn(TARGET_IQN);
            when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
            when(storagePool.getClusterId()).thenReturn(1L);
            when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
            when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
            when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(poolDetails);

            when(volumeInfo.getId()).thenReturn(VOLUME_ID);
            when(volumeInfo.getType()).thenReturn(DataObjectType.VOLUME);
            when(volumeInfo.getName()).thenReturn("data-volume");
            when(volumeInfo.getSize()).thenReturn(1073741824L);
            when(volumeInfo.getPoolId()).thenReturn(POOL_ID);
            when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
            when(volumeDao.findById(VOLUME_ID)).thenReturn(volumeVO);
            when(volumeVO.getId()).thenReturn(VOLUME_ID);
            when(volumeVO.getName()).thenReturn("data-volume");

            when(host.getUuid()).thenReturn("host-uuid");
            when(host.getName()).thenReturn("kvm-1");
            when(host.getStorageUrl()).thenReturn("iqn.host");
            when(host.getClusterId()).thenReturn(1L);

            when(templateInfo.getType()).thenReturn(DataObjectType.TEMPLATE);
            when(templateInfo.getId()).thenReturn(TEMPLATE_ID);
            when(templateInfo.getUuid()).thenReturn("template-uuid");

            when(storageManager.connectHostToSharedPool(any(), anyLong())).thenReturn(true);
        }

        // ---------------------------------------------------------------- helpers

        private <T> T withStrategy(StorageStrategy strategy, java.util.function.Supplier<T> action) {
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails)).thenReturn(strategy);
                return action.get();
            }
        }

        private void withStrategy(StorageStrategy strategy, Runnable action) {
            withStrategy(strategy, () -> {
                action.run();
                return null;
            });
        }

        private void givenIscsiVolume() {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, LUN_NAME, false));
            when(volumeDetailsDao.findDetail(VOLUME_ID, DiskTO.SCSI_NAA_DEVICE_ID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, DiskTO.SCSI_NAA_DEVICE_ID, WWID, false));
            AccessGroup existing = new AccessGroup();
            Igroup igroup = new Igroup();
            igroup.setName("cs_host-uuid_svm1");
            existing.setIgroup(igroup);
            when(sanStrategy.getAccessGroup(anyMap())).thenReturn(existing);
            when(sanStrategy.ensureLunMapped(eq(SVM), eq(LUN_NAME), anyString())).thenReturn("3");
        }

        private void givenNfsPool() {
            poolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        }

        // ------------------------------------------------- trivial driver contract

        @Test
        void trivialCapabilities_reportExpectedStaticValues() throws Exception {
            assertFalse(driver.canProvideStorageStats());
            assertFalse(driver.canProvideVolumeStats());
            assertNull(driver.getStorageStats(storagePool));
            assertNull(driver.getVolumeStats(storagePool, "vol"));
            assertTrue(driver.canHostAccessStoragePool(host, storagePool));
            assertTrue(driver.isVmInfoNeeded());
            assertTrue(driver.isVmTagsNeeded("any"));
            assertTrue(driver.isStorageSupportHA(Storage.StoragePoolType.NetworkFilesystem));
            assertTrue(driver.volumesRequireGrantAccessWhenUsed());
            assertNull(driver.getChapInfo(volumeInfo));
            assertNull(driver.getTO(volumeInfo));
            assertNull(driver.getStoreTO(dataStore));
        }

        @Test
        void noOpCallbacks_doNotInteractWithDependencies() throws Exception {
            driver.provideVmInfo(1L, 2L);
            driver.provideVmTags(1L, 2L, "tag");
            driver.detachVolumeFromAllStorageNodes(volumeVO);
            driver.handleQualityOfServiceForVolumeMigration(volumeInfo, null);

            verifyNoInteractions(volumeDao);
        }

        // ------------------------------------------------- grantAccess: negative

        @Test
        void grantAccess_throws_whenDataStoreIsNull() throws Exception {
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(volumeInfo, host, null));

            assertTrue(ex.getMessage().contains("dataStore should not be null"), ex.getMessage());
        }

        @Test
        void grantAccess_throws_whenDataObjectIsNull() throws Exception {
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(null, host, dataStore));

            assertTrue(ex.getMessage().contains("dataObject should not be null"), ex.getMessage());
        }

        @Test
        void grantAccess_throws_whenHostIsNull() throws Exception {
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(volumeInfo, null, dataStore));

            assertTrue(ex.getMessage().contains("host should not be null"), ex.getMessage());
        }

        @Test
        void grantAccess_throws_whenStoragePoolNotFound() throws Exception {
            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(volumeInfo, host, dataStore));

            assertTrue(ex.getMessage().contains("Storage Pool not found"), ex.getMessage());
        }

        @Test
        void grantAccess_throws_whenPoolScopeIsHost() throws Exception {
            when(storagePool.getScope()).thenReturn(ScopeType.HOST);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(volumeInfo, host, dataStore));

            assertTrue(ex.getMessage().contains("Only Cluster and Zone scoped"), ex.getMessage());
        }

        @Test
        void grantAccess_throws_whenVolumeNotFound() throws Exception {
            when(volumeDao.findById(VOLUME_ID)).thenReturn(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(volumeInfo, host, dataStore));

            assertTrue(ex.getMessage().contains("CloudStack Volume not found"), ex.getMessage());
        }

        @Test
        void grantAccess_throws_whenDataObjectTypeIsUnsupported() throws Exception {
            DataObject snapshot = mock(DataObject.class);
            when(snapshot.getType()).thenReturn(DataObjectType.SNAPSHOT);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.grantAccess(snapshot, host, dataStore));

            assertTrue(ex.getMessage().contains("Invalid DataObjectType"), ex.getMessage());
        }

        // ------------------------------------------------- grantAccess: iSCSI

        @Test
        void grantAccess_mapsLunAndUpdatesVolumePath_forIscsiVolume() throws Exception {
            givenIscsiVolume();

            boolean granted = withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore));

            assertTrue(granted);
            String expectedPath = "/" + TARGET_IQN + "/" + WWID;
            verify(volumeVO).setPath(expectedPath);
            verify(volumeVO).set_iScsiName(expectedPath);
            verify(volumeVO).setPoolType(Storage.StoragePoolType.NetworkFilesystem);
            verify(volumeVO).setPoolId(POOL_ID);
            verify(volumeDao).update(VOLUME_ID, volumeVO);
            verify(sanStrategy, never()).createAccessGroup(any(AccessGroup.class));
            verify(storageManager, never()).connectHostToSharedPool(any(), anyLong());
        }

        @Test
        void grantAccess_createsIgroup_whenHostIgroupIsMissing() throws Exception {
            givenIscsiVolume();
            when(sanStrategy.getAccessGroup(anyMap())).thenReturn(null);
            AccessGroup created = new AccessGroup();
            Igroup igroup = new Igroup();
            igroup.setName("cs_host-uuid_svm1");
            created.setIgroup(igroup);
            when(sanStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(created);

            assertTrue(withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
            verify(sanStrategy).createAccessGroup(captor.capture());
            assertEquals(POOL_ID, captor.getValue().getStoragePoolId());
            assertEquals(1, captor.getValue().getHostsToConnect().size());
        }

        @Test
        void grantAccess_resolvesAndCachesWwid_whenNotCachedYet() throws Exception {
            givenIscsiVolume();
            when(volumeDetailsDao.findDetail(VOLUME_ID, DiskTO.SCSI_NAA_DEVICE_ID)).thenReturn(null);
            when(sanStrategy.getLunSerialNumber(SVM, LUN_NAME)).thenReturn(SERIAL);

            assertTrue(withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            verify(volumeDetailsDao).addDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_SERIAL_NUMBER, SERIAL, false);
            verify(volumeDetailsDao).addDetail(VOLUME_ID, DiskTO.SCSI_NAA_DEVICE_ID, WWID, false);
        }

        @Test
        void grantAccess_throws_whenOntapReportsNoSerialNumber() throws Exception {
            givenIscsiVolume();
            when(volumeDetailsDao.findDetail(VOLUME_ID, DiskTO.SCSI_NAA_DEVICE_ID)).thenReturn(null);
            when(sanStrategy.getLunSerialNumber(SVM, LUN_NAME)).thenReturn("");

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            assertTrue(ex.getMessage().contains("did not report a serial number"), ex.getMessage());
            verify(volumeDao, never()).update(anyLong(), any(VolumeVO.class));
        }

        @Test
        void grantAccess_connectsHostToPool_whenIscsiVolumeIsMigrating() throws Exception {
            givenIscsiVolume();
            when(volumeVO.getState()).thenReturn(Volume.State.Migrating);

            assertTrue(withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            verify(storageManager).connectHostToSharedPool(host, POOL_ID);
        }

        @Test
        void grantAccess_connectsHostToPool_whenIscsiVolumeIsTaggedMigrationDestination() throws Exception {
            givenIscsiVolume();
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.MIGRATION_DESTINATION))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.MIGRATION_DESTINATION, "true", false));

            assertTrue(withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            verify(storageManager).connectHostToSharedPool(host, POOL_ID);
        }

        @Test
        void grantAccess_throws_whenHostCannotConnectToIscsiPool() throws Exception {
            givenIscsiVolume();
            when(volumeVO.getState()).thenReturn(Volume.State.Migrating);
            when(storageManager.connectHostToSharedPool(any(), anyLong())).thenReturn(false);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            assertTrue(ex.getMessage().contains("Failed to connect host"), ex.getMessage());
        }

        @Test
        void grantAccess_copiesPathToCallerVolume_whenDataObjectCarriesVolumeVO() throws Exception {
            givenIscsiVolume();
            VolumeVO callerVolume = mock(VolumeVO.class);
            when(volumeInfo.getVolume()).thenReturn(callerVolume);
            when(volumeVO.getPath()).thenReturn("/server/side/path");

            assertTrue(withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            verify(callerVolume).setPath(any());
            verify(callerVolume).set_iScsiName(any());
        }

        // --------------------------------------------------- grantAccess: NFS

        @Test
        void grantAccess_skipsExportPolicyUpdate_whenNfsVolumeIsNotMigrating() throws Exception {
            givenNfsPool();

            assertTrue(withStrategy(nasStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            verify(nasStrategy, never()).updateAccessGroup(any(AccessGroup.class));
            verify(storageManager, never()).connectHostToSharedPool(any(), anyLong());
        }

        @Test
        void grantAccess_addsHostToExportPolicyAndConnects_whenNfsVolumeIsMigrating() throws Exception {
            givenNfsPool();
            when(volumeVO.getState()).thenReturn(Volume.State.Migrating);

            assertTrue(withStrategy(nasStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
            verify(nasStrategy).updateAccessGroup(captor.capture());
            assertEquals(AccessGroup.HostRuleAction.ADD, captor.getValue().getHostRuleAction());
            assertEquals(POOL_ID, captor.getValue().getStoragePoolId());
            verify(storageManager).connectHostToSharedPool(host, POOL_ID);
        }

        @Test
        void grantAccess_throws_whenHostCannotConnectToNfsPool() throws Exception {
            givenNfsPool();
            when(volumeVO.getState()).thenReturn(Volume.State.Migrating);
            when(storageManager.connectHostToSharedPool(any(), anyLong())).thenReturn(false);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> withStrategy(nasStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            assertTrue(ex.getMessage().contains("Failed to connect host"), ex.getMessage());
        }

        // ------------------------------------------------- revokeAccess: negative

        @Test
        void revokeAccess_throws_whenDataStoreIsNull() throws Exception {
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.revokeAccess(volumeInfo, host, null));

            assertTrue(ex.getMessage().contains("dataStore should not be null"), ex.getMessage());
        }

        @Test
        void revokeAccess_throws_whenDataObjectIsNull() throws Exception {
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.revokeAccess(null, host, dataStore));

            assertTrue(ex.getMessage().contains("dataObject should not be null"), ex.getMessage());
        }

        @Test
        void revokeAccess_throws_whenHostIsNull() throws Exception {
            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.revokeAccess(volumeInfo, null, dataStore));

            assertTrue(ex.getMessage().contains("host should not be null"), ex.getMessage());
        }

        @Test
        void revokeAccess_throws_whenStoragePoolNotFound() throws Exception {
            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.revokeAccess(volumeInfo, host, dataStore));

            assertTrue(ex.getMessage().contains("Storage Pool not found"), ex.getMessage());
        }

        @Test
        void revokeAccess_throws_whenPoolScopeIsHost() throws Exception {
            when(storagePool.getScope()).thenReturn(ScopeType.HOST);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.revokeAccess(volumeInfo, host, dataStore));

            assertTrue(ex.getMessage().contains("Only Cluster and Zone scoped"), ex.getMessage());
        }

        @Test
        void revokeAccess_throws_whenVolumeNotFound() throws Exception {
            when(volumeDao.findById(VOLUME_ID)).thenReturn(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.revokeAccess(volumeInfo, host, dataStore));

            assertTrue(ex.getMessage().contains("CloudStack Volume not found"), ex.getMessage());
        }

        @Test
        void revokeAccess_throws_whenDataObjectTypeIsUnsupported() throws Exception {
            DataObject snapshot = mock(DataObject.class);
            when(snapshot.getType()).thenReturn(DataObjectType.SNAPSHOT);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> driver.revokeAccess(snapshot, host, dataStore));

            assertTrue(ex.getMessage().contains("Invalid DataObjectType"), ex.getMessage());
        }

        // ------------------------------------------------ revokeAccess: iSCSI

        private void givenLunAndIgroupOnOntap() {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, LUN_NAME, false));
            Lun lun = new Lun();
            lun.setName(LUN_NAME);
            lun.setUuid(LUN_UUID);
            CloudStackVolume csVolume = new CloudStackVolume();
            csVolume.setLun(lun);
            when(sanStrategy.getCloudStackVolume(anyMap())).thenReturn(csVolume);
            AccessGroup accessGroup = new AccessGroup();
            // Igroup.uuid is only populated by JSON deserialization, so a mock supplies it here.
            Igroup igroup = mock(Igroup.class);
            when(igroup.getName()).thenReturn("cs_host-uuid_svm1");
            when(igroup.getUuid()).thenReturn(IGROUP_UUID);
            accessGroup.setIgroup(igroup);
            when(sanStrategy.getAccessGroup(anyMap())).thenReturn(accessGroup);
            when(sanStrategy.validateInitiatorInAccessGroup(eq("iqn.host"), eq(SVM), any(Igroup.class))).thenReturn(true);
        }

        @Test
        void revokeAccess_unmapsLun_whenLunIgroupAndInitiatorExist() throws Exception {
            givenLunAndIgroupOnOntap();

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
            verify(sanStrategy).disableLogicalAccess(captor.capture());
            assertEquals(LUN_UUID, captor.getValue().get(OntapStorageConstants.LUN_DOT_UUID));
            assertEquals(IGROUP_UUID, captor.getValue().get(OntapStorageConstants.IGROUP_DOT_UUID));
        }

        @Test
        void revokeAccess_skips_whenLunNameDetailMissing() throws Exception {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(null);

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        @Test
        void revokeAccess_skips_whenLunNameDetailHasNullValue() throws Exception {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, null, false));

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        @Test
        void revokeAccess_skips_whenLunNoLongerExistsOnOntap() throws Exception {
            givenLunAndIgroupOnOntap();
            when(sanStrategy.getCloudStackVolume(anyMap())).thenReturn(null);

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        @Test
        void revokeAccess_skips_whenLunHasNoName() throws Exception {
            givenLunAndIgroupOnOntap();
            CloudStackVolume unnamed = new CloudStackVolume();
            unnamed.setLun(new Lun());
            when(sanStrategy.getCloudStackVolume(anyMap())).thenReturn(unnamed);

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        @Test
        void revokeAccess_skips_whenLunHasNoUuid() throws Exception {
            givenLunAndIgroupOnOntap();
            Lun named = new Lun();
            named.setName(LUN_NAME);
            CloudStackVolume noUuid = new CloudStackVolume();
            noUuid.setLun(named);
            when(sanStrategy.getCloudStackVolume(anyMap())).thenReturn(noUuid);

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        @Test
        void revokeAccess_skips_whenIgroupNoLongerExistsOnOntap() throws Exception {
            givenLunAndIgroupOnOntap();
            when(sanStrategy.getAccessGroup(anyMap())).thenReturn(null);

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        @Test
        void revokeAccess_skips_whenIgroupHasNoUuid() throws Exception {
            givenLunAndIgroupOnOntap();
            AccessGroup noUuid = new AccessGroup();
            noUuid.setIgroup(new Igroup());
            when(sanStrategy.getAccessGroup(anyMap())).thenReturn(noUuid);

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        @Test
        void revokeAccess_skips_whenHostInitiatorIsNotInIgroup() throws Exception {
            givenLunAndIgroupOnOntap();
            when(sanStrategy.validateInitiatorInAccessGroup(anyString(), anyString(), any(Igroup.class))).thenReturn(false);

            withStrategy(sanStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(sanStrategy, never()).disableLogicalAccess(anyMap());
        }

        // -------------------------------------------------- revokeAccess: NFS

        @Test
        void revokeAccess_removesHostFromExportPolicy_whenHostIsInDifferentCluster() throws Exception {
            givenNfsPool();
            when(host.getClusterId()).thenReturn(2L);

            withStrategy(nasStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
            verify(nasStrategy).updateAccessGroup(captor.capture());
            assertEquals(AccessGroup.HostRuleAction.REMOVE, captor.getValue().getHostRuleAction());
        }

        @Test
        void revokeAccess_keepsExportPolicy_whenHostIsInPoolCluster() throws Exception {
            givenNfsPool();

            withStrategy(nasStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(nasStrategy, never()).updateAccessGroup(any(AccessGroup.class));
        }

        @Test
        void revokeAccess_keepsExportPolicy_whenHostHasNoCluster() throws Exception {
            givenNfsPool();
            when(host.getClusterId()).thenReturn(null);

            withStrategy(nasStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(nasStrategy, never()).updateAccessGroup(any(AccessGroup.class));
        }

        @Test
        void revokeAccess_keepsExportPolicy_whenPoolHasNoCluster() throws Exception {
            givenNfsPool();
            when(host.getClusterId()).thenReturn(2L);
            when(storagePool.getClusterId()).thenReturn(null);

            withStrategy(nasStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(nasStrategy, never()).updateAccessGroup(any(AccessGroup.class));
        }

        @Test
        void revokeAccess_keepsExportPolicy_whenPoolIsZoneScoped() throws Exception {
            givenNfsPool();
            when(storagePool.getScope()).thenReturn(ScopeType.ZONE);
            when(host.getClusterId()).thenReturn(2L);

            withStrategy(nasStrategy, () -> driver.revokeAccess(volumeInfo, host, dataStore));

            verify(nasStrategy, never()).updateAccessGroup(any(AccessGroup.class));
        }

        // ------------------------------------------------ revokeAccess: template

        @Test
        void revokeAccess_isNoOp_forNfsTemplate() throws Exception {
            givenNfsPool();

            withStrategy(nasStrategy, () -> driver.revokeAccess(templateInfo, host, dataStore));

            verifyNoInteractions(nasStrategy);
        }

        @Test
        void revokeAccess_unmapsTemplateCacheLun_forIscsiTemplate() throws Exception {
            givenLunAndIgroupOnOntap();

            withStrategy(sanStrategy, () -> driver.revokeAccess(templateInfo, host, dataStore));

            ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
            verify(sanStrategy).disableLogicalAccess(captor.capture());
            assertEquals(LUN_UUID, captor.getValue().get(OntapStorageConstants.LUN_DOT_UUID));
        }

        // ------------------------------------------- applyQosPolicyToVolume

        private VolumeQosPolicy qosPolicy() {
            VolumeQosPolicy policy = new VolumeQosPolicy();
            policy.setName("cs_100_to_500_iops_svm1");
            policy.setUuid("qos-uuid");
            return policy;
        }

        @Test
        void applyQosPolicy_doesNothing_whenVolumeIsNull() throws Exception {
            driver.applyQosPolicyToVolume(null);

            verifyNoInteractions(storagePoolDao);
        }

        @Test
        void applyQosPolicy_doesNothing_whenPoolNotFound() throws Exception {
            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

            driver.applyQosPolicyToVolume(volumeInfo);

            verifyNoInteractions(storagePoolDetailsDao);
        }

        @Test
        void applyQosPolicy_doesNothing_whenVolumeTypeIsNotRootOrData() throws Exception {
            when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.ISO);

            driver.applyQosPolicyToVolume(volumeInfo);

            verifyNoInteractions(storagePoolDetailsDao);
        }

        @Test
        void applyQosPolicy_attachesPolicyToLunAndPersistsDetail_forIscsiVolume() throws Exception {
            when(volumeInfo.getMinIops()).thenReturn(100L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);
            when(sanStrategy.createVolumeQosPolicy("cs_100_to_500_iops_svm1", 100L, 500L)).thenReturn(qosPolicy());
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID, LUN_UUID, false));

            withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).updateCloudStackVolume(request.capture());
            assertEquals(LUN_UUID, request.getValue().getLun().getUuid());
            assertSame(volumeInfo, request.getValue().getVolumeInfo());
            assertEquals("qos-uuid", request.getValue().getLun().getQosPolicy().getUuid());
            verify(volumeDetailsDao).removeDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID);
            verify(volumeDetailsDao).addDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false);
        }

        @Test
        void applyQosPolicy_attachesPolicyToFile_forNfsVolume() throws Exception {
            givenNfsPool();
            when(volumeInfo.getMinIops()).thenReturn(0L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);
            when(nasStrategy.createVolumeQosPolicy("cs_0_to_500_iops_svm1", 0L, 500L)).thenReturn(qosPolicy());

            withStrategy(nasStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).updateCloudStackVolume(request.capture());
            assertEquals("qos-uuid", request.getValue().getFile().getQosPolicy().getUuid());
            assertEquals("flexvol-uuid", request.getValue().getFlexVolumeUuid());
        }

        @Test
        void applyQosPolicy_usesStoredVolumeIops_whenInfoReportsNone() throws Exception {
            when(volumeInfo.getMinIops()).thenReturn(null);
            when(volumeInfo.getMaxIops()).thenReturn(null);
            VolumeVO stored = mock(VolumeVO.class);
            when(stored.getMinIops()).thenReturn(100L);
            when(stored.getMaxIops()).thenReturn(500L);
            when(volumeDao.findById(VOLUME_ID, true)).thenReturn(stored);
            when(sanStrategy.createVolumeQosPolicy("cs_100_to_500_iops_svm1", 100L, 500L)).thenReturn(qosPolicy());

            withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            verify(sanStrategy).updateCloudStackVolume(any(CloudStackVolume.class));
        }

        @Test
        void applyQosPolicy_leavesQosUntouched_whenNoIopsAnywhere() throws Exception {
            when(volumeInfo.getMinIops()).thenReturn(null);
            when(volumeInfo.getMaxIops()).thenReturn(null);
            when(volumeDao.findById(VOLUME_ID, true)).thenReturn(null);

            withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            verify(sanStrategy, never()).createVolumeQosPolicy(anyString(), anyLong(), anyLong());
            verify(sanStrategy, never()).updateCloudStackVolume(any(CloudStackVolume.class));
        }

        @Test
        void applyQosPolicy_leavesQosUntouched_whenStoredVolumeAlsoHasNoIops() throws Exception {
            when(volumeInfo.getMinIops()).thenReturn(0L);
            when(volumeInfo.getMaxIops()).thenReturn(0L);
            VolumeVO stored = mock(VolumeVO.class);
            when(volumeDao.findById(VOLUME_ID, true)).thenReturn(stored);

            withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            verify(sanStrategy, never()).updateCloudStackVolume(any(CloudStackVolume.class));
        }

        @Test
        void applyQosPolicy_throws_whenMinIopsExceedsMaxIops() throws Exception {
            when(volumeInfo.getMinIops()).thenReturn(900L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo)));

            assertTrue(ex.getMessage().contains("Minimum IOPS cannot be greater than maximum IOPS"), ex.getMessage());
            verify(sanStrategy, never()).updateCloudStackVolume(any(CloudStackVolume.class));
        }

        @Test
        void applyQosPolicy_throws_whenMinIopsRequestedOnFasSystem() throws Exception {
            poolDetails.put(OntapStorageConstants.IS_AFF, "false");
            when(volumeInfo.getMinIops()).thenReturn(100L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo)));

            assertEquals(OntapStorageConstants.MIN_IOPS_NOT_SUPPORTED_ON_FAS, ex.getMessage());
        }

        @Test
        void applyQosPolicy_detectsAndStoresAffFlag_whenPoolDetailMissing() throws Exception {
            poolDetails.remove(OntapStorageConstants.IS_AFF);
            when(volumeInfo.getMinIops()).thenReturn(100L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);
            when(sanStrategy.isAff()).thenReturn(true);
            when(sanStrategy.createVolumeQosPolicy(anyString(), anyLong(), anyLong())).thenReturn(qosPolicy());

            withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            verify(storagePoolDetailsDao).addDetail(POOL_ID, OntapStorageConstants.IS_AFF, "true", false);
            verify(sanStrategy).updateCloudStackVolume(any(CloudStackVolume.class));
        }

        @Test
        void applyQosPolicy_rejectsMinIops_whenDetectedSystemIsFas() throws Exception {
            poolDetails.remove(OntapStorageConstants.IS_AFF);
            when(volumeInfo.getMinIops()).thenReturn(100L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);
            when(sanStrategy.isAff()).thenReturn(false);

            assertThrows(CloudRuntimeException.class,
                    () -> withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo)));
        }

        @Test
        void applyQosPolicy_proceeds_whenAffDetectionFails() throws Exception {
            poolDetails.remove(OntapStorageConstants.IS_AFF);
            when(volumeInfo.getMinIops()).thenReturn(100L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);
            when(sanStrategy.isAff()).thenThrow(new RuntimeException("cluster unreachable"));
            when(sanStrategy.createVolumeQosPolicy(anyString(), anyLong(), anyLong())).thenReturn(qosPolicy());

            withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            verify(sanStrategy).updateCloudStackVolume(any(CloudStackVolume.class));
            verify(storagePoolDetailsDao, never()).addDetail(anyLong(), eq(OntapStorageConstants.IS_AFF), anyString(), anyBoolean());
        }

        @Test
        void applyQosPolicy_sanitizesSvmNameDotsInPolicyName() throws Exception {
            poolDetails.put(OntapStorageConstants.SVM_NAME, "svm.with.dots");
            when(volumeInfo.getMinIops()).thenReturn(100L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);
            when(sanStrategy.createVolumeQosPolicy("cs_100_to_500_iops_svm_with_dots", 100L, 500L)).thenReturn(qosPolicy());

            withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo));

            verify(sanStrategy).createVolumeQosPolicy("cs_100_to_500_iops_svm_with_dots", 100L, 500L);
        }

        @Test
        void applyQosPolicy_rejectsVolumeName_thatIsNotValidForOntap() throws Exception {
            when(volumeInfo.getName()).thenReturn("1-starts-with-digit");
            when(volumeInfo.getMinIops()).thenReturn(100L);
            when(volumeInfo.getMaxIops()).thenReturn(500L);
            when(sanStrategy.createVolumeQosPolicy(anyString(), anyLong(), anyLong())).thenReturn(qosPolicy());

            assertThrows(InvalidParameterValueException.class,
                    () -> withStrategy(sanStrategy, () -> driver.applyQosPolicyToVolume(volumeInfo)));
        }

        // ----------------------------------- deleteAsync: volume failure handling

        private CommandResult deleteVolume(StorageStrategy strategy) {
            withStrategy(strategy, () -> driver.deleteAsync(dataStore, volumeInfo, commandCallback));
            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(captor.capture());
            return captor.getValue();
        }

        @Test
        void deleteAsync_reportsFailure_whenPoolNotFoundForVolume() throws Exception {
            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

            CommandResult result = deleteVolume(sanStrategy);

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Storage Pool not found"), result.getResult());
        }

        @Test
        void deleteAsync_reportsFailure_forUnsupportedDataObjectType() throws Exception {
            DataObject archive = mock(DataObject.class);
            when(archive.getType()).thenReturn(DataObjectType.ARCHIVE);

            withStrategy(sanStrategy, () -> driver.deleteAsync(dataStore, archive, commandCallback));

            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(captor.capture());
            assertFalse(captor.getValue().isSuccess());
            assertTrue(captor.getValue().getResult().contains("Unsupported data object type"));
        }

        @Test
        void deleteAsync_continuesDelete_whenClearingNfsQosPolicyFails() throws Exception {
            givenNfsPool();
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));
            doThrow(new RuntimeException("qos update rejected")).when(nasStrategy).updateCloudStackVolume(any(CloudStackVolume.class));

            CommandResult result = deleteVolume(nasStrategy);

            assertTrue(result.isSuccess());
            verify(nasStrategy).deleteCloudStackVolume(any(CloudStackVolume.class));
            verify(nasStrategy).deleteVolumeQosPolicy("qos-uuid");
        }

        @Test
        void deleteAsync_restoresNfsQosPolicy_whenDeleteFailsAfterPolicyWasCleared() throws Exception {
            givenNfsPool();
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));
            doThrow(new CloudRuntimeException("file busy")).when(nasStrategy).deleteCloudStackVolume(any(CloudStackVolume.class));

            CommandResult result = deleteVolume(nasStrategy);

            assertFalse(result.isSuccess());
            assertEquals("file busy", result.getResult());
            verify(nasStrategy, times(2)).updateCloudStackVolume(any(CloudStackVolume.class));
            verify(nasStrategy, never()).deleteVolumeQosPolicy(anyString());
        }

        @Test
        void deleteAsync_stillReportsDeleteFailure_whenRestoringNfsQosPolicyAlsoFails() throws Exception {
            givenNfsPool();
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));
            doThrow(new CloudRuntimeException("file busy")).when(nasStrategy).deleteCloudStackVolume(any(CloudStackVolume.class));
            doReturn(null).doThrow(new RuntimeException("restore failed"))
                    .when(nasStrategy).updateCloudStackVolume(any(CloudStackVolume.class));

            CommandResult result = deleteVolume(nasStrategy);

            assertFalse(result.isSuccess());
            assertEquals("file busy", result.getResult());
        }

        @Test
        void deleteAsync_ignoresFailure_whenDeletingUnusedQosPolicyFails() throws Exception {
            givenNfsPool();
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "qos-uuid", false));
            doThrow(new RuntimeException("policy in use")).when(nasStrategy).deleteVolumeQosPolicy("qos-uuid");

            CommandResult result = deleteVolume(nasStrategy);

            assertTrue(result.isSuccess());
        }

        @Test
        void deleteAsync_skipsQosHandling_whenNfsVolumeHasNoQosPolicy() throws Exception {
            givenNfsPool();
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID)).thenReturn(null);

            CommandResult result = deleteVolume(nasStrategy);

            assertTrue(result.isSuccess());
            verify(nasStrategy, never()).updateCloudStackVolume(any(CloudStackVolume.class));
            verify(nasStrategy, never()).deleteVolumeQosPolicy(anyString());
        }

        // ------------------------------------------- temporary snapshot copy path sync

        /**
         * {@code getPath} and {@code get_iScsiName} return whatever this test last stored, so a later
         * read sees the path grantAccess computed rather than a value stubbed in advance.
         */
        private void rememberPathWrites(VolumeVO volume) {
            AtomicReference<String> path = new AtomicReference<>();
            AtomicReference<String> iscsiName = new AtomicReference<>();
            doAnswer(invocation -> {
                path.set(invocation.getArgument(0));
                return null;
            }).when(volume).setPath(anyString());
            doAnswer(invocation -> {
                iscsiName.set(invocation.getArgument(0));
                return null;
            }).when(volume).set_iScsiName(anyString());
            when(volume.getPath()).thenAnswer(invocation -> path.get());
            when(volume.get_iScsiName()).thenAnswer(invocation -> iscsiName.get());
        }

        /** Path the KVM agent is given: {@code /<target IQN>/<LUN WWID>}. */
        private String computedIscsiPath() {
            return "/" + TARGET_IQN + "/" + WWID;
        }

        @Test
        void grantAccess_syncsLunPathToCallerVolume_forTemporarySnapshotCopy() throws Exception {
            givenIscsiVolume();
            rememberPathWrites(volumeVO);
            VolumeVO callerVolume = mock(VolumeVO.class);
            when(volumeInfo.getVolume()).thenReturn(callerVolume);
            when(volumeVO.getState()).thenReturn(Volume.State.Allocated);
            when(volumeVO.getDiskOfferingId()).thenReturn(null);
            String iscsiPath = computedIscsiPath();

            assertTrue(withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            assertEquals(iscsiPath, volumeVO.getPath());
            assertEquals(iscsiPath, volumeVO.get_iScsiName());
            verify(volumeVO).setPath(iscsiPath);
            verify(volumeVO).set_iScsiName(iscsiPath);
            // The mapping writes the path on the volume row. grantAccess copies it onto the caller's
            // volume, and a temporary snapshot copy copies that same computed path a second time.
            verify(callerVolume, times(2)).setPath(iscsiPath);
            verify(callerVolume, times(2)).set_iScsiName(iscsiPath);
        }

        @Test
        void grantAccess_doesNotResyncPath_forRegularVolume() throws Exception {
            givenIscsiVolume();
            rememberPathWrites(volumeVO);
            VolumeVO callerVolume = mock(VolumeVO.class);
            when(volumeInfo.getVolume()).thenReturn(callerVolume);
            when(volumeVO.getState()).thenReturn(Volume.State.Allocated);
            when(volumeVO.getDiskOfferingId()).thenReturn(15L);
            String iscsiPath = computedIscsiPath();

            assertTrue(withStrategy(sanStrategy, () -> driver.grantAccess(volumeInfo, host, dataStore)));

            assertEquals(iscsiPath, volumeVO.getPath());
            assertEquals(iscsiPath, volumeVO.get_iScsiName());
            verify(callerVolume, times(1)).setPath(iscsiPath);
            verify(callerVolume, times(1)).set_iScsiName(iscsiPath);
        }
    }

    /**
     * Offline iSCSI volume copy between ONTAP pools and snapshot-to-temporary-volume detection in
     * {@link OntapPrimaryDatastoreDriver}: which object pairs the driver accepts, and how a copy
     * persists its result or cleans up after a failure.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class OntapPrimaryDatastoreDriverCopyTest {

        private static final long SRC_POOL_ID = 1L;
        private static final long DEST_POOL_ID = 2L;
        private static final long SRC_VOLUME_ID = 10L;
        private static final long DEST_VOLUME_ID = 20L;

        @Mock private StoragePoolDetailsDao storagePoolDetailsDao;
        @Mock private PrimaryDataStoreDao storagePoolDao;
        @Mock private VolumeDao volumeDao;
        @Mock private VolumeDetailsDao volumeDetailsDao;
        @Mock private StorageStrategy strategy;
        @Mock private AsyncCompletionCallback<CopyCommandResult> callback;

        @InjectMocks
        private OntapPrimaryDatastoreDriver driver;

        private OntapPrimaryDatastoreDriver spied;
        private VolumeInfo src;
        private VolumeInfo dest;
        private StoragePoolVO srcPool;
        private StoragePoolVO destPool;
        private VolumeVO destVolumeVO;
        private Map<String, String> srcDetails;
        private Map<String, String> destDetails;

        @BeforeEach
        void setUp() {
            srcDetails = iscsiDetails("10.0.0.1", "svm-uuid-1", "svm1");
            destDetails = iscsiDetails("10.0.0.1", "svm-uuid-1", "svm1");

            srcPool = pool(SRC_POOL_ID);
            destPool = pool(DEST_POOL_ID);
            when(storagePoolDao.findById(SRC_POOL_ID)).thenReturn(srcPool);
            when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destPool);
            when(storagePoolDetailsDao.listDetailsKeyPairs(SRC_POOL_ID)).thenReturn(srcDetails);
            when(storagePoolDetailsDao.listDetailsKeyPairs(DEST_POOL_ID)).thenReturn(destDetails);

            src = volume(SRC_VOLUME_ID, SRC_POOL_ID);
            when(src.getState()).thenReturn(Volume.State.Migrating);
            when(src.getHypervisorType()).thenReturn(HypervisorType.KVM);
            dest = volume(DEST_VOLUME_ID, DEST_POOL_ID);

            destVolumeVO = mock(VolumeVO.class);
            when(destVolumeVO.getId()).thenReturn(DEST_VOLUME_ID);
            when(destVolumeVO.getPath()).thenReturn("old-path");
            when(volumeDao.findById(DEST_VOLUME_ID)).thenReturn(destVolumeVO);

            spied = spy(driver);
        }

        // ---------------------------------------------------------------- fixtures

        private Map<String, String> iscsiDetails(String ip, String svmUuid, String svmName) {
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.PROTOCOL, "ISCSI");
            details.put(OntapStorageConstants.STORAGE_IP, ip);
            if (svmUuid != null) {
                details.put(OntapStorageConstants.SVM_UUID, svmUuid);
            }
            if (svmName != null) {
                details.put(OntapStorageConstants.SVM_NAME, svmName);
            }
            return details;
        }

        private StoragePoolVO pool(long id) {
            StoragePoolVO pool = mock(StoragePoolVO.class);
            when(pool.getId()).thenReturn(id);
            when(pool.getName()).thenReturn("pool-" + id);
            when(pool.getStorageProviderName()).thenReturn("NetApp ONTAP");
            when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
            when(pool.getHypervisor()).thenReturn(HypervisorType.KVM);
            return pool;
        }

        private VolumeInfo volume(long id, long poolId) {
            VolumeInfo volume = mock(VolumeInfo.class);
            DataStore store = mock(DataStore.class);
            when(store.getId()).thenReturn(poolId);
            when(store.getRole()).thenReturn(DataStoreRole.Primary);
            when(volume.getId()).thenReturn(id);
            when(volume.getPoolId()).thenReturn(poolId);
            when(volume.getDataStore()).thenReturn(store);
            when(volume.getType()).thenReturn(DataObjectType.VOLUME);
            when(volume.getTO()).thenReturn(new VolumeObjectTO());
            return volume;
        }

        private Lun lun(String name, String uuid) {
            Lun lun = new Lun();
            lun.setName(name);
            lun.setUuid(uuid);
            return lun;
        }

        private CloudStackVolume copiedLun() {
            CloudStackVolume copied = new CloudStackVolume();
            copied.setLun(lun("/vol/dest/copied", "copied-uuid"));
            return copied;
        }

        private void whenCopied(CloudStackVolume copied) {
            when(strategy.copyCloudStackVolume(any(), any(), any(), any(), anyInt())).thenReturn(copied);
        }

        private void copy(DataObject source, DataObject destination, AsyncCompletionCallback<CopyCommandResult> cb) {
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(strategy);
                spied.copyAsync(source, destination, null, cb);
            }
        }

        private CopyCommandResult copyAndCaptureResult() {
            doNothing().when(spied).applyQosPolicyToVolume(any(VolumeInfo.class));
            copy(src, dest, callback);
            ArgumentCaptor<CopyCommandResult> captor = ArgumentCaptor.forClass(CopyCommandResult.class);
            verify(callback).complete(captor.capture());
            return captor.getValue();
        }

        // ---------------------------------------------------------------- canCopy: compatible iSCSI pools

        @Test
        void canCopyAcceptsMigratingKvmVolumeBetweenCompatibleIscsiPools() {
            assertTrue(driver.canCopy(src, dest));
        }

        @Test
        void canCopyRejectsMissingObjectsOrDataStores() {
            assertFalse(driver.canCopy(null, dest));
            assertFalse(driver.canCopy(src, null));

            VolumeInfo noStore = mock(VolumeInfo.class);
            when(noStore.getDataStore()).thenReturn(null);
            assertFalse(driver.canCopy(noStore, dest));
            assertFalse(driver.canCopy(src, noStore));
        }

        @Test
        void canCopyRejectsObjectsThatAreNotVolumes() {
            DataObject template = mock(DataObject.class);
            DataStore store = mock(DataStore.class);
            when(template.getDataStore()).thenReturn(store);
            when(template.getType()).thenReturn(DataObjectType.TEMPLATE);

            assertFalse(driver.canCopy(template, dest));
            assertFalse(driver.canCopy(src, template));
        }

        @Test
        void canCopyRejectsVolumeThatIsNotMigratingOrNotKvm() {
            when(src.getState()).thenReturn(Volume.State.Ready);
            assertFalse(driver.canCopy(src, dest));

            when(src.getState()).thenReturn(Volume.State.Migrating);
            when(src.getHypervisorType()).thenReturn(HypervisorType.VMware);
            assertFalse(driver.canCopy(src, dest));
        }

        @Test
        void canCopyRejectsUnknownSamePoolNonOntapOrNonIscsiPools() {
            when(storagePoolDao.findById(SRC_POOL_ID)).thenReturn(null);
            assertFalse(driver.canCopy(src, dest));
            when(storagePoolDao.findById(SRC_POOL_ID)).thenReturn(srcPool);

            when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(null);
            assertFalse(driver.canCopy(src, dest));
            when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destPool);

            when(dest.getPoolId()).thenReturn(SRC_POOL_ID);
            assertFalse(driver.canCopy(src, dest));
            when(dest.getPoolId()).thenReturn(DEST_POOL_ID);

            when(srcPool.getStorageProviderName()).thenReturn("DefaultPrimary");
            assertFalse(driver.canCopy(src, dest));
            when(srcPool.getStorageProviderName()).thenReturn("NetApp ONTAP");

            when(destPool.getStorageProviderName()).thenReturn("DefaultPrimary");
            assertFalse(driver.canCopy(src, dest));
            when(destPool.getStorageProviderName()).thenReturn("NetApp ONTAP");

            when(srcPool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
            assertFalse(driver.canCopy(src, dest));
            when(srcPool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);

            when(destPool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
            assertFalse(driver.canCopy(src, dest));
        }

        private boolean canCopyWithDetails(Consumer<Map<String, String>> srcChange, Consumer<Map<String, String>> destChange) {
            srcChange.accept(srcDetails);
            destChange.accept(destDetails);
            return driver.canCopy(src, dest);
        }

        @Test
        void canCopyRequiresSameClusterAndSameSvmIdentity() {
            // identical SVM UUID wins even when names differ
            assertTrue(canCopyWithDetails(d -> d.put(OntapStorageConstants.SVM_NAME, "a"), d -> d.put(OntapStorageConstants.SVM_NAME, "b")));
            // different storage IP
            assertFalse(canCopyWithDetails(d -> { }, d -> d.put(OntapStorageConstants.STORAGE_IP, "10.0.0.2")));
            assertFalse(canCopyWithDetails(d -> d.remove(OntapStorageConstants.STORAGE_IP), d -> { }));
            // UUID present on only one side, or different on both
            assertFalse(canCopyWithDetails(d -> { }, d -> d.remove(OntapStorageConstants.SVM_UUID)));
            assertFalse(canCopyWithDetails(d -> d.remove(OntapStorageConstants.SVM_UUID), d -> d.put(OntapStorageConstants.SVM_UUID, "uuid-2")));
            assertFalse(canCopyWithDetails(d -> d.put(OntapStorageConstants.SVM_UUID, "uuid-3"), d -> { }));
        }

        @Test
        void canCopyFallsBackToSvmNamesWhenNoUuidsAreKnown() {
            srcDetails.remove(OntapStorageConstants.SVM_UUID);
            destDetails.remove(OntapStorageConstants.SVM_UUID);
            assertTrue(driver.canCopy(src, dest));

            destDetails.put(OntapStorageConstants.SVM_NAME, "other-svm");
            assertFalse(driver.canCopy(src, dest));

            destDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
            srcDetails.remove(OntapStorageConstants.SVM_NAME);
            assertFalse(driver.canCopy(src, dest));

            srcDetails.put(OntapStorageConstants.SVM_NAME, " ");
            destDetails.put(OntapStorageConstants.SVM_NAME, " ");
            assertFalse(driver.canCopy(src, dest));
        }

        @Test
        void canCopyRequiresIscsiProtocolOnBothPoolsAndReadableDetails() {
            srcDetails.put(OntapStorageConstants.PROTOCOL, "NFS3");
            assertFalse(driver.canCopy(src, dest));
            srcDetails.put(OntapStorageConstants.PROTOCOL, "iscsi");
            assertTrue(driver.canCopy(src, dest));

            destDetails.put(OntapStorageConstants.PROTOCOL, "NFS3");
            assertFalse(driver.canCopy(src, dest));
            destDetails.put(OntapStorageConstants.PROTOCOL, "ISCSI");

            when(storagePoolDetailsDao.listDetailsKeyPairs(SRC_POOL_ID)).thenReturn(null);
            assertFalse(driver.canCopy(src, dest));
            when(storagePoolDetailsDao.listDetailsKeyPairs(SRC_POOL_ID)).thenReturn(srcDetails);

            when(storagePoolDetailsDao.listDetailsKeyPairs(DEST_POOL_ID)).thenReturn(null);
            assertFalse(driver.canCopy(src, dest));
        }

        // ---------------------------------------------------------------- canCopy: snapshot to temporary volume

        private DataObject snapshot(long storePoolId, DataStoreRole role) {
            DataObject snapshot = mock(DataObject.class);
            DataStore store = mock(DataStore.class);
            when(store.getId()).thenReturn(storePoolId);
            when(store.getRole()).thenReturn(role);
            when(snapshot.getDataStore()).thenReturn(store);
            when(snapshot.getType()).thenReturn(DataObjectType.SNAPSHOT);
            when(snapshot.getId()).thenReturn(77L);
            return snapshot;
        }

        private VolumeVO temporaryVolumeRow(Volume.State state, Long diskOfferingId) {
            VolumeVO row = mock(VolumeVO.class);
            when(row.getState()).thenReturn(state);
            when(row.getDiskOfferingId()).thenReturn(diskOfferingId);
            return row;
        }

        private void stubTemporaryVolumeRow(Volume.State state, Long diskOfferingId) {
            VolumeVO row = temporaryVolumeRow(state, diskOfferingId);
            when(volumeDao.findById(DEST_VOLUME_ID)).thenReturn(row);
        }

        @Test
        void canCopyAcceptsSnapshotCopiedToTemporaryVolumeOnTheSameStore() {
            DataObject snapshot = snapshot(DEST_POOL_ID, DataStoreRole.Primary);

            stubTemporaryVolumeRow(Volume.State.Allocated, null);
            assertTrue(driver.canCopy(snapshot, dest));

            stubTemporaryVolumeRow(Volume.State.Allocated, 0L);
            assertTrue(driver.canCopy(snapshot, dest));
        }

        @Test
        void canCopyRejectsSnapshotCopiesThatDoNotTargetATemporaryVolume() {
            DataObject snapshot = snapshot(DEST_POOL_ID, DataStoreRole.Primary);

            when(volumeDao.findById(DEST_VOLUME_ID)).thenReturn(null);
            assertFalse(driver.canCopy(snapshot, dest));

            stubTemporaryVolumeRow(Volume.State.Ready, null);
            assertFalse(driver.canCopy(snapshot, dest));

            stubTemporaryVolumeRow(Volume.State.Allocated, 15L);
            assertFalse(driver.canCopy(snapshot, dest));

            stubTemporaryVolumeRow(Volume.State.Allocated, null);
            assertFalse(driver.canCopy(snapshot(DEST_POOL_ID, DataStoreRole.Image), dest));
            assertFalse(driver.canCopy(snapshot(SRC_POOL_ID, DataStoreRole.Primary), dest));

            DataObject notSnapshot = snapshot(DEST_POOL_ID, DataStoreRole.Primary);
            when(notSnapshot.getType()).thenReturn(DataObjectType.TEMPLATE);
            assertFalse(driver.canCopy(notSnapshot, dest));

            DataObject destNotVolume = mock(DataObject.class);
            when(destNotVolume.getDataStore()).thenReturn(mock(DataStore.class));
            when(destNotVolume.getType()).thenReturn(DataObjectType.SNAPSHOT);
            assertFalse(driver.canCopy(snapshot, destNotVolume));
        }

        @Test
        void snapshotCopyFailureIsThrownWhenNoCallbackAndReportedWhenCallbackGiven() {
            DataObject snapshot = snapshot(DEST_POOL_ID, DataStoreRole.Primary);
            stubTemporaryVolumeRow(Volume.State.Allocated, null);
            when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(null);

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> spied.copyAsync(snapshot, dest, null, null));
            assertTrue(thrown.getMessage().contains("Failed to copy"), thrown.getMessage());

            spied.copyAsync(snapshot, dest, null, callback);
            ArgumentCaptor<CopyCommandResult> captor = ArgumentCaptor.forClass(CopyCommandResult.class);
            verify(callback).complete(captor.capture());
            assertFalse(captor.getValue().isSuccess());
            assertTrue(captor.getValue().getResult().contains("Storage Pool not found"), captor.getValue().getResult());
        }

        // ---------------------------------------------------------------- copyAsync

        @Test
        void copyAsyncRejectsPairsTheDriverDoesNotHandle() {
            when(src.getState()).thenReturn(Volume.State.Ready);

            UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                    () -> spied.copyAsync(src, dest, null, callback));
            assertTrue(thrown.getMessage().contains("VOLUME [10]"), thrown.getMessage());

            UnsupportedOperationException nullDest = assertThrows(UnsupportedOperationException.class,
                    () -> spied.copyAsync(src, null, null, callback));
            assertTrue(nullDest.getMessage().contains("null"), nullDest.getMessage());
            verify(callback, never()).complete(any());
        }

        @Test
        void copyAsyncPersistsCopiedLunAndReportsSuccess() {
            whenCopied(copiedLun());

            CopyCommandResult result = copyAndCaptureResult();

            assertTrue(result.isSuccess());
            VolumeObjectTO copiedTO = (VolumeObjectTO) ((CopyCmdAnswer) result.getAnswer()).getNewData();
            assertEquals("/vol/dest/copied", copiedTO.getPath());
            assertEquals(Storage.ImageFormat.RAW, copiedTO.getFormat());
            verify(destVolumeVO).setPoolId(DEST_POOL_ID);
            verify(destVolumeVO).setPoolType(Storage.StoragePoolType.OntapiSCSI);
            verify(destVolumeVO).setFormat(Storage.ImageFormat.RAW);
            verify(destVolumeVO).setFolder("copied-uuid");
            verify(destVolumeVO).set_iScsiName(null);
            verify(destVolumeVO).setPath("/vol/dest/copied");
            verify(volumeDetailsDao).addDetail(DEST_VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID, "copied-uuid", false);
            verify(volumeDetailsDao).addDetail(DEST_VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, "/vol/dest/copied", false);
            verify(volumeDao).update(DEST_VOLUME_ID, destVolumeVO);
            verify(spied).applyQosPolicyToVolume(dest);
        }

        @Test
        void copyAsyncWorksWithoutCallback() {
            whenCopied(copiedLun());
            doNothing().when(spied).applyQosPolicyToVolume(any(VolumeInfo.class));

            copy(src, dest, null);

            verify(volumeDao).update(DEST_VOLUME_ID, destVolumeVO);
        }

        @Test
        void copyAsyncUsesFilePathWhenCopyResultIsAFile() {
            CloudStackVolume copied = new CloudStackVolume();
            FileInfo file = new FileInfo();
            file.setPath("dir/file.qcow2");
            copied.setFile(file);
            whenCopied(copied);

            copyAndCaptureResult();

            verify(destVolumeVO).setPath("dir/file.qcow2");
            verify(volumeDetailsDao, never()).addDetail(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        void copyAsyncKeepsExistingPathWhenCopyResultCarriesNoLunOrFilePath() {
            CloudStackVolume copied = new CloudStackVolume();
            copied.setFile(new FileInfo());
            whenCopied(copied);

            copyAndCaptureResult();

            verify(destVolumeVO).setPath("old-path");
        }

        @Test
        void copyAsyncReportsFailureWithoutCleanupWhenStorageCopyFails() {
            when(strategy.copyCloudStackVolume(any(), any(), any(), any(), anyInt())).thenThrow(new CloudRuntimeException("copy refused"));

            CopyCommandResult result = copyAndCaptureResult();

            assertFalse(result.isSuccess());
            assertEquals("copy refused", result.getResult());
            verify(strategy, never()).deleteCloudStackVolume(any(CloudStackVolume.class));
            verify(volumeDao, never()).update(anyLong(), any(VolumeVO.class));
        }

        @Test
        void copyAsyncRemovesCopiedLunWhenDestinationVolumeRecordIsMissing() {
            CloudStackVolume copied = copiedLun();
            whenCopied(copied);
            when(volumeDao.findById(DEST_VOLUME_ID)).thenReturn(null);

            CopyCommandResult result = copyAndCaptureResult();

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("was not found"), result.getResult());
            verify(strategy).deleteCloudStackVolume(copied);
        }

        @Test
        void copyAsyncStillReportsOriginalFailureWhenCleanupAlsoFails() {
            CloudStackVolume copied = copiedLun();
            whenCopied(copied);
            when(volumeDao.findById(DEST_VOLUME_ID)).thenReturn(null);
            doThrow(new CloudRuntimeException("cleanup failed")).when(strategy).deleteCloudStackVolume(any(CloudStackVolume.class));

            CopyCommandResult result = copyAndCaptureResult();

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("was not found"), result.getResult());
        }

        @Test
        void copyAsyncFailsForUnsupportedHypervisorOfDestinationPool() {
            CloudStackVolume copied = copiedLun();
            whenCopied(copied);
            when(destPool.getHypervisor()).thenReturn(HypervisorType.VMware);

            CopyCommandResult result = copyAndCaptureResult();

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Unsupported hypervisor"), result.getResult());
            verify(strategy).deleteCloudStackVolume(copied);
        }

        // ---------------------------------------------------------------- QoS carried over from the source volume

        private void srcHasIops(Long min, Long max) {
            when(src.getMinIops()).thenReturn(min);
            when(src.getMaxIops()).thenReturn(max);
        }

        @Test
        void copyAsyncCopiesSourceIopsToDestinationRowWhenDestinationHasNone() {
            whenCopied(copiedLun());
            srcHasIops(100L, 200L);

            copyAndCaptureResult();

            verify(destVolumeVO).setMinIops(100L);
            verify(destVolumeVO).setMaxIops(200L);
            verify(volumeDao, org.mockito.Mockito.atLeastOnce()).update(DEST_VOLUME_ID, destVolumeVO);
        }

        @Test
        void copyAsyncKeepsDestinationIopsWhenAlreadySet() {
            whenCopied(copiedLun());
            srcHasIops(100L, 200L);
            when(dest.getMaxIops()).thenReturn(500L);

            copyAndCaptureResult();

            verify(destVolumeVO, never()).setMinIops(any());
            verify(destVolumeVO, never()).setMaxIops(any());
        }

        @Test
        void copyAsyncKeepsDestinationRowIopsWhenRowAlreadyHasThem() {
            whenCopied(copiedLun());
            srcHasIops(100L, 200L);
            when(destVolumeVO.getMinIops()).thenReturn(50L);

            copyAndCaptureResult();

            verify(destVolumeVO, never()).setMinIops(any());
        }

        @Test
        void copyAsyncLeavesDestinationWithoutIopsWhenSourceHasNone() {
            whenCopied(copiedLun());
            srcHasIops(null, 0L);

            copyAndCaptureResult();

            verify(destVolumeVO, never()).setMinIops(any());
            verify(destVolumeVO, never()).setMaxIops(any());
        }

        // ---------------------------------------------------------------- applyQosPolicyToVolume early exits

        private void applyQos(VolumeInfo volume) {
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(strategy);
                driver.applyQosPolicyToVolume(volume);
            }
        }

        @Test
        void applyQosPolicyDoesNothingForNullVolumeMissingPoolOrNonDiskVolume() {
            applyQos(null);

            when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(null);
            applyQos(dest);
            when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destPool);

            when(dest.getVolumeType()).thenReturn(Volume.Type.ISO);
            applyQos(dest);

            verify(strategy, never()).updateCloudStackVolume(any());
        }

        @Test
        void applyQosPolicyKeepsCurrentQosWhenNeitherVolumeNorStoredRowHasIops() {
            when(dest.getVolumeType()).thenReturn(Volume.Type.ROOT);
            VolumeVO stored = mock(VolumeVO.class);
            when(volumeDao.findById(DEST_VOLUME_ID, true)).thenReturn(stored);

            applyQos(dest);

            verify(strategy, never()).updateCloudStackVolume(any());
            verify(volumeDetailsDao, never()).addDetail(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

            when(volumeDao.findById(DEST_VOLUME_ID, true)).thenReturn(null);
            applyQos(dest);
            verify(strategy, never()).updateCloudStackVolume(any());
        }

        @Test
        void snapshotCopyFailsBeforeCallingOntapWhenPoolProtocolIsUnsupported() {
            DataObject snapshot = snapshot(DEST_POOL_ID, DataStoreRole.Primary);
            stubTemporaryVolumeRow(Volume.State.Allocated, null);
            destDetails.put(OntapStorageConstants.PROTOCOL, "FC");

            spied.copyAsync(snapshot, dest, null, callback);

            ArgumentCaptor<CopyCommandResult> captor = ArgumentCaptor.forClass(CopyCommandResult.class);
            verify(callback).complete(captor.capture());
            assertFalse(captor.getValue().isSuccess());
            assertTrue(captor.getValue().getResult().contains("Unsupported protocol [FC]"), captor.getValue().getResult());
            verify(strategy, never()).cloneCloudStackVolume(any());
        }
    }

    /**
     * Checks the exact content of what {@link OntapPrimaryDatastoreDriver} sends to the ONTAP storage
     * strategy (LUN and file requests, clone sources, QoS references), what it records on the volume
     * and template rows, and the success flag and message it reports on delete.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class OntapPrimaryDatastoreDriverRequestContentTest {

        private static final long POOL_ID = 5L;
        private static final long VOLUME_ID = 10L;
        private static final long TEMPLATE_ID = 31L;
        private static final long SNAPSHOT_ID = 77L;
        private static final long ONE_GB = 1073741824L;

        @Mock private StoragePoolDetailsDao storagePoolDetailsDao;
        @Mock private PrimaryDataStoreDao storagePoolDao;
        @Mock private VolumeDao volumeDao;
        @Mock private VolumeDetailsDao volumeDetailsDao;
        @Mock private SnapshotDetailsDao snapshotDetailsDao;
        @Mock private SnapshotDao snapshotDao;
        @Mock private VMTemplatePoolDao vmTemplatePoolDao;
        @Mock private StorageManager storageManager;
        @Mock private DataStore dataStore;
        @Mock private StoragePoolVO storagePool;
        @Mock private VolumeVO volumeVO;
        @Mock private VolumeInfo volumeInfo;
        @Mock private TemplateInfo templateInfo;
        @Mock private VMTemplateStoragePoolVO templatePoolRef;
        @Mock private UnifiedSANStrategy sanStrategy;
        @Mock private StorageStrategy nasStrategy;
        @Mock private AsyncCompletionCallback<CreateCmdResult> createCallback;
        @Mock private AsyncCompletionCallback<CommandResult> commandCallback;

        @InjectMocks
        private OntapPrimaryDatastoreDriver driver;

        private Map<String, String> poolDetails;

        @BeforeEach
        void setUp() {
            poolDetails = new HashMap<>();
            poolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());
            poolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
            poolDetails.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid");
            poolDetails.put(OntapStorageConstants.VOLUME_NAME, "flexvol");
            poolDetails.put(OntapStorageConstants.IS_AFF, "true");

            when(dataStore.getId()).thenReturn(POOL_ID);
            when(dataStore.getName()).thenReturn("flexvol");
            when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePool);
            when(storagePool.getId()).thenReturn(POOL_ID);
            when(storagePool.getName()).thenReturn("flexvol");
            when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
            when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.OntapiSCSI);
            when(storagePool.getCapacityIops()).thenReturn(null);
            when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(poolDetails);

            when(volumeInfo.getId()).thenReturn(VOLUME_ID);
            when(volumeInfo.getType()).thenReturn(DataObjectType.VOLUME);
            when(volumeInfo.getName()).thenReturn("data-volume");
            when(volumeInfo.getUuid()).thenReturn("vol-uuid");
            when(volumeInfo.getSize()).thenReturn(ONE_GB);
            when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.DATADISK);
            when(volumeInfo.getMinIops()).thenReturn(null);
            when(volumeInfo.getMaxIops()).thenReturn(null);
            when(volumeInfo.getDataStore()).thenReturn(dataStore);
            when(volumeDao.findById(VOLUME_ID)).thenReturn(volumeVO);
            when(volumeVO.getId()).thenReturn(VOLUME_ID);
            when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);

            when(templateInfo.getType()).thenReturn(DataObjectType.TEMPLATE);
            when(templateInfo.getId()).thenReturn(TEMPLATE_ID);
            when(templateInfo.getUuid()).thenReturn("template-uuid");
            when(templateInfo.getSize()).thenReturn(4096L);
            when(vmTemplatePoolDao.findByPoolTemplate(POOL_ID, TEMPLATE_ID, null)).thenReturn(templatePoolRef);
            when(templatePoolRef.getId()).thenReturn(900L);
        }

        // ---------------------------------------------------------------- helpers

        private <T> T withStrategy(StorageStrategy strategy, Supplier<T> action) {
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails)).thenReturn(strategy);
                return action.get();
            }
        }

        private void givenNfs() {
            poolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
            when(storagePool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        }

        private CreateCmdResult create(StorageStrategy strategy, DataObject object) {
            withStrategy(strategy, () -> {
                driver.createAsync(dataStore, object, createCallback);
                return null;
            });
            ArgumentCaptor<CreateCmdResult> captor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(captor.capture());
            return captor.getValue();
        }

        private CommandResult delete(StorageStrategy strategy, DataStore store, DataObject object) {
            withStrategy(strategy, () -> {
                driver.deleteAsync(store, object, commandCallback);
                return null;
            });
            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(captor.capture());
            return captor.getValue();
        }

        private CloudStackVolume createdLun(String name, String uuid) {
            Lun lun = new Lun();
            lun.setName(name);
            lun.setUuid(uuid);
            CloudStackVolume created = new CloudStackVolume();
            created.setLun(lun);
            return created;
        }

        private VolumeQosPolicy policy(String name, String uuid) {
            VolumeQosPolicy policy = new VolumeQosPolicy();
            policy.setName(name);
            policy.setUuid(uuid);
            return policy;
        }

        private void volumeDetail(String key, String value) {
            when(volumeDetailsDao.findDetail(VOLUME_ID, key)).thenReturn(new VolumeDetailVO(VOLUME_ID, key, value, false));
        }

        private void snapshotDetail(String key, String value) {
            when(snapshotDetailsDao.findDetail(SNAPSHOT_ID, key)).thenReturn(new SnapshotDetailsVO(SNAPSHOT_ID, key, value, false));
        }

        private void givenCloneOfSnapshot(String protocol, String sourcePath, long snapshotSize) {
            volumeDetail(OntapStorageConstants.CLONE_OF_SNAPSHOT, String.valueOf(SNAPSHOT_ID));
            snapshotDetail(OntapStorageConstants.ONTAP_SNAP_NAME, "snap1");
            snapshotDetail(OntapStorageConstants.VOLUME_PATH, sourcePath);
            snapshotDetail(OntapStorageConstants.PRIMARY_POOL_ID, String.valueOf(POOL_ID));
            snapshotDetail(OntapStorageConstants.PROTOCOL, protocol);
            SnapshotVO snapshotVO = mock(SnapshotVO.class);
            when(snapshotVO.getSize()).thenReturn(snapshotSize);
            when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(snapshotVO);
        }

        private void givenCloneOfTemplate(String localLunUuid, String installPath, long cachedSize) {
            volumeDetail(OntapStorageConstants.CLONE_OF_TEMPLATE, String.valueOf(TEMPLATE_ID));
            when(templatePoolRef.getLocalDownloadPath()).thenReturn(localLunUuid);
            when(templatePoolRef.getInstallPath()).thenReturn(installPath);
            when(templatePoolRef.getTemplateSize()).thenReturn(cachedSize);
        }

        private void givenIops(Long min, Long max) {
            when(volumeInfo.getMinIops()).thenReturn(min);
            when(volumeInfo.getMaxIops()).thenReturn(max);
        }

        // ---------------------------------------------------------------- blank volume create

        @Test
        void createIscsiVolumeSendsLunRequestAndRecordsLunIdentity() {
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "lun-uuid"));

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).createCloudStackVolume(request.capture());
            Lun lun = request.getValue().getLun();
            assertEquals("svm1", lun.getSvm().getName());
            assertEquals("/vol/flexvol/data_volume", lun.getName());
            assertEquals(Long.valueOf(ONE_GB), lun.getSpace().getSize());
            assertEquals(Lun.OsTypeEnum.LINUX, lun.getOsType());
            assertNull(lun.getQosPolicy());
            assertSame(volumeInfo, request.getValue().getVolumeInfo());

            assertTrue(result.isSuccess());
            assertEquals("/vol/flexvol/data_volume", result.getPath());
            verify(volumeVO).setPoolType(Storage.StoragePoolType.OntapiSCSI);
            verify(volumeVO).setPoolId(POOL_ID);
            verify(volumeVO).setFormat(Storage.ImageFormat.RAW);
            verify(volumeVO).setFolder("lun-uuid");
            verify(volumeDetailsDao).addDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid", false);
            verify(volumeDetailsDao).addDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, "/vol/flexvol/data_volume", false);
            verify(volumeDao).update(VOLUME_ID, volumeVO);
        }

        @Test
        void createNfsVolumeSendsFlexVolReferenceAndReportsVolumeUuidAsPath() {
            givenNfs();
            when(nasStrategy.createCloudStackVolume(any())).thenReturn(new CloudStackVolume());

            CreateCmdResult result = create(nasStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).createCloudStackVolume(request.capture());
            assertEquals("5", request.getValue().getDatastoreId());
            assertEquals("flexvol-uuid", request.getValue().getFlexVolumeUuid());
            assertSame(volumeInfo, request.getValue().getVolumeInfo());
            assertNull(request.getValue().getFile());

            assertTrue(result.isSuccess());
            assertEquals("vol-uuid", result.getPath());
            verify(volumeVO).setPoolType(Storage.StoragePoolType.NetworkFilesystem);
            verify(volumeVO).setPoolId(POOL_ID);
            verify(volumeVO).setFormat(Storage.ImageFormat.QCOW2);
            verify(volumeDao).update(VOLUME_ID, volumeVO);
        }

        @Test
        void createVolumeWithIopsAttachesPolicyReferenceToLunAndRemembersItsUuid() {
            givenIops(100L, 200L);
            when(sanStrategy.createVolumeQosPolicy("cs_100_to_200_iops_svm1", 100L, 200L)).thenReturn(policy("cs_100_to_200_iops_svm1", "qos-1"));
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "lun-uuid"));

            create(sanStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).createCloudStackVolume(request.capture());
            assertEquals("cs_100_to_200_iops_svm1", request.getValue().getLun().getQosPolicy().getName());
            assertEquals("qos-1", request.getValue().getLun().getQosPolicy().getUuid());
            verify(volumeDetailsDao).addDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "qos-1", false);
        }

        @Test
        void createNfsVolumeWithIopsAttachesPolicyReferenceToFile() {
            givenNfs();
            givenIops(0L, 300L);
            when(nasStrategy.createVolumeQosPolicy("cs_0_to_300_iops_svm1", 0L, 300L)).thenReturn(policy("cs_0_to_300_iops_svm1", "qos-2"));
            when(nasStrategy.createCloudStackVolume(any())).thenReturn(new CloudStackVolume());

            create(nasStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).createCloudStackVolume(request.capture());
            assertEquals("qos-2", request.getValue().getFile().getQosPolicy().getUuid());
            assertEquals("cs_0_to_300_iops_svm1", request.getValue().getFile().getQosPolicy().getName());
        }

        @Test
        void createVolumeDeletesTheNewQosPolicyWhenOntapRejectsTheLun() {
            givenIops(100L, 200L);
            when(sanStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("p", "qos-1"));
            when(sanStrategy.createCloudStackVolume(any())).thenThrow(new CloudRuntimeException("lun refused"));

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            assertFalse(result.isSuccess());
            assertEquals("lun refused", result.getResult());
            verify(sanStrategy).deleteVolumeQosPolicy("qos-1");
        }

        @Test
        void createVolumeDoesNotCreateQosPolicyForVolumesThatAreNotRootOrData() {
            givenIops(100L, 200L);
            when(volumeInfo.getVolumeType()).thenReturn(Volume.Type.ISO);
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "lun-uuid"));

            create(sanStrategy, volumeInfo);

            verify(sanStrategy, never()).createVolumeQosPolicy(any(), any(), any());
        }

        @Test
        void createVolumeAcceptsIopsThatExactlyFillThePoolAndIgnoresItsOwnReservation() {
            when(storagePool.getCapacityIops()).thenReturn(1000L);
            VolumeVO other = mock(VolumeVO.class);
            when(other.getId()).thenReturn(99L);
            when(other.getMinIops()).thenReturn(500L);
            VolumeVO self = mock(VolumeVO.class);
            when(self.getId()).thenReturn(VOLUME_ID);
            when(self.getMinIops()).thenReturn(400L);
            when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(java.util.Arrays.asList(other, self));
            when(sanStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("p", "qos-1"));
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "lun-uuid"));

            givenIops(500L, 600L);
            assertTrue(create(sanStrategy, volumeInfo).isSuccess());
        }

        @Test
        void createVolumeRejectsIopsThatExceedPoolCapacityByOne() {
            when(storagePool.getCapacityIops()).thenReturn(1000L);
            VolumeVO other = mock(VolumeVO.class);
            when(other.getId()).thenReturn(99L);
            when(other.getMinIops()).thenReturn(500L);
            when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(java.util.Arrays.asList(other));
            givenIops(501L, 600L);

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Insufficient IOPS capacity"), result.getResult());
            verify(sanStrategy, never()).createCloudStackVolume(any());
        }

        @Test
        void createVolumeWithoutMinimumIopsIgnoresPoolIopsUsage() {
            when(storagePool.getCapacityIops()).thenReturn(1000L);
            VolumeVO other = mock(VolumeVO.class);
            when(other.getId()).thenReturn(99L);
            when(other.getMinIops()).thenReturn(5000L);
            when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(java.util.Arrays.asList(other));
            givenIops(0L, 0L);
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "lun-uuid"));

            assertTrue(create(sanStrategy, volumeInfo).isSuccess());
        }

        @Test
        void createVolumeAllowsEqualMinAndMaxIops() {
            givenIops(200L, 200L);
            when(sanStrategy.createVolumeQosPolicy("cs_200_to_200_iops_svm1", 200L, 200L)).thenReturn(policy("p", "qos-1"));
            when(sanStrategy.createCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "lun-uuid"));
            assertTrue(create(sanStrategy, volumeInfo).isSuccess());
        }

        @Test
        void createVolumeRejectsMinIopsGreaterThanMaxIops() {
            givenIops(201L, 200L);

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Minimum IOPS cannot be greater than maximum IOPS"), result.getResult());
        }

        // ---------------------------------------------------------------- template cache on primary

        @Test
        void createIscsiTemplateCacheRecordsLunUuidAndSizeAndReturnsLunName() {
            when(sanStrategy.createTemplateCache(any(), any(), anyMap(), anyLong()))
                    .thenReturn(createdLun("/vol/flexvol/cs_tmpl_31", "tmpl-lun-uuid"));

            CreateCmdResult result = create(sanStrategy, templateInfo);

            assertTrue(result.isSuccess());
            assertEquals("/vol/flexvol/cs_tmpl_31", result.getPath());
            verify(templatePoolRef).setLocalDownloadPath("tmpl-lun-uuid");
            verify(templatePoolRef).setTemplateSize(4096L);
            verify(vmTemplatePoolDao).update(900L, templatePoolRef);
        }

        @Test
        void createNfsTemplateCacheReturnsTemplateUuidAndLeavesSpoolRefUntouched() {
            givenNfs();
            when(nasStrategy.createTemplateCache(any(), any(), anyMap(), anyLong())).thenReturn(new CloudStackVolume());

            CreateCmdResult result = create(nasStrategy, templateInfo);

            assertTrue(result.isSuccess());
            assertEquals("template-uuid", result.getPath());
            verify(vmTemplatePoolDao, never()).update(anyLong(), any(VMTemplateStoragePoolVO.class));
        }

        @Test
        void createTemplateCacheReturnsTemplateUuidWhenStrategyReturnsNothing() {
            when(sanStrategy.createTemplateCache(any(), any(), anyMap(), anyLong())).thenReturn(null);

            CreateCmdResult result = create(sanStrategy, templateInfo);

            assertEquals("template-uuid", result.getPath());
        }

        // ---------------------------------------------------------------- required template size

        private void givenSpoolRef(ObjectInDataStoreStateMachine.State state, String lunUuid, String installPath) {
            when(templatePoolRef.getState()).thenReturn(state);
            when(templatePoolRef.getLocalDownloadPath()).thenReturn(lunUuid);
            when(templatePoolRef.getInstallPath()).thenReturn(installPath);
        }

        @Test
        void iscsiTemplateNeedsNoSpaceOnlyWhenReadyWithALunUuid() {
            givenSpoolRef(ObjectInDataStoreStateMachine.State.Ready, "lun-uuid", null);
            assertEquals(0L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));

            givenSpoolRef(ObjectInDataStoreStateMachine.State.Ready, " ", "install/path");
            assertEquals(4096L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));

            givenSpoolRef(ObjectInDataStoreStateMachine.State.Allocated, "lun-uuid", null);
            assertEquals(4096L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));

            when(vmTemplatePoolDao.findByPoolTemplate(POOL_ID, TEMPLATE_ID, null)).thenReturn(null);
            assertEquals(4096L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));
        }

        @Test
        void nfsTemplateNeedsNoSpaceOnlyWhenReadyWithAnInstallPath() {
            givenNfs();
            givenSpoolRef(ObjectInDataStoreStateMachine.State.Ready, null, "install/path");
            assertEquals(0L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));

            givenSpoolRef(ObjectInDataStoreStateMachine.State.Ready, "lun-uuid", "");
            assertEquals(4096L, driver.getBytesRequiredForTemplate(templateInfo, storagePool));
        }

        // ---------------------------------------------------------------- clone from template

        @Test
        void cloneIscsiFromTemplateSendsCloneSourceAndKeepsRequestSizeless() {
            givenCloneOfTemplate("tmpl-lun-uuid", null, ONE_GB);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "clone-uuid"));

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(request.capture());
            Lun lun = request.getValue().getLun();
            assertEquals("/vol/flexvol/cs_tmpl_31", lun.getClone().getSource().getName());
            assertEquals("tmpl-lun-uuid", lun.getClone().getSource().getUuid());
            assertEquals("svm1", lun.getSvm().getName());
            assertEquals("/vol/flexvol/data_volume", lun.getName());
            assertNull(lun.getSpace());
            assertSame(volumeInfo, request.getValue().getVolumeInfo());
            assertTrue(result.isSuccess());
            assertEquals("/vol/flexvol/data_volume", result.getPath());
            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
        }

        @Test
        void cloneFromTemplateGrowsTheCloneOnlyWhenVolumeIsLargerThanTheCachedTemplate() {
            CloudStackVolume cloned = createdLun("/vol/flexvol/data_volume", "clone-uuid");
            givenCloneOfTemplate("tmpl-lun-uuid", null, ONE_GB - 1);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            assertTrue(create(sanStrategy, volumeInfo).isSuccess());

            verify(sanStrategy).resizeCloudStackVolume(cloned, ONE_GB);
        }

        @Test
        void cloneFromTemplateAttachesQosToTheCloneRequestAndRemembersPolicy() {
            givenIops(100L, 200L);
            givenCloneOfTemplate("tmpl-lun-uuid", null, ONE_GB);
            when(sanStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("pol", "qos-9"));
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "clone-uuid"));

            create(sanStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(request.capture());
            assertEquals("pol", request.getValue().getLun().getQosPolicy().getName());
            assertEquals("qos-9", request.getValue().getLun().getQosPolicy().getUuid());
            verify(volumeDetailsDao).addDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "qos-9", false);
        }

        @Test
        void cloneFromTemplateDeletesNewQosPolicyWhenOntapReturnsNothing() {
            givenIops(100L, 200L);
            givenCloneOfTemplate("tmpl-lun-uuid", null, ONE_GB);
            when(sanStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("pol", "qos-9"));
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(null);

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("ONTAP returned nothing when cloning template"), result.getResult());
            verify(sanStrategy).deleteVolumeQosPolicy("qos-9");
        }

        @Test
        void cloneFromTemplateChecksPoolIopsCapacity() {
            when(storagePool.getCapacityIops()).thenReturn(100L);
            givenIops(200L, 300L);
            givenCloneOfTemplate("tmpl-lun-uuid", null, ONE_GB);

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Insufficient IOPS capacity"), result.getResult());
            verify(sanStrategy, never()).cloneCloudStackVolume(any());
        }

        @Test
        void cloneNfsFromTemplateSendsSourceFileAndDestinationNamedAfterVolume() {
            givenNfs();
            givenCloneOfTemplate(null, "tmpl/cached.qcow2", ONE_GB);
            when(nasStrategy.cloneCloudStackVolume(any())).thenReturn(new CloudStackVolume());

            CreateCmdResult result = create(nasStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).cloneCloudStackVolume(request.capture());
            assertEquals("5", request.getValue().getDatastoreId());
            assertEquals("tmpl/cached.qcow2", request.getValue().getFile().getPath());
            assertEquals("vol-uuid", request.getValue().getDestinationPath());
            assertSame(volumeInfo, request.getValue().getVolumeInfo());
            assertEquals("vol-uuid", result.getPath());
        }

        @Test
        void cloneNfsFromTemplateAppliesQosAfterTheFileExists() {
            givenNfs();
            givenIops(0L, 300L);
            givenCloneOfTemplate(null, "tmpl/cached.qcow2", ONE_GB);
            when(nasStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("pol", "qos-5"));
            when(nasStrategy.cloneCloudStackVolume(any())).thenReturn(new CloudStackVolume());

            create(nasStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> update = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).updateCloudStackVolume(update.capture());
            assertEquals("qos-5", update.getValue().getFile().getQosPolicy().getUuid());
            assertEquals("flexvol-uuid", update.getValue().getFlexVolumeUuid());
        }

        // ---------------------------------------------------------------- clone from snapshot

        @Test
        void cloneIscsiFromSnapshotSendsSnapshotResidentSourcePath() {
            givenCloneOfSnapshot("ISCSI", "/vol/flexvol/lun1", ONE_GB);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "clone-uuid"));

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(request.capture());
            Lun lun = request.getValue().getLun();
            assertEquals("/vol/flexvol/.snapshot/snap1/lun1", lun.getClone().getSource().getName());
            assertNull(lun.getClone().getSource().getUuid());
            assertEquals("svm1", lun.getSvm().getName());
            assertEquals("/vol/flexvol/data_volume", lun.getName());
            assertSame(volumeInfo, request.getValue().getVolumeInfo());
            assertTrue(result.isSuccess());
            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
        }

        @Test
        void cloneFromSnapshotUsesPoolNameWhenFlexVolNameIsNotRecorded() {
            poolDetails.remove(OntapStorageConstants.VOLUME_NAME);
            givenCloneOfSnapshot("ISCSI", "/vol/flexvol/lun1", ONE_GB);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "clone-uuid"));

            create(sanStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).cloneCloudStackVolume(request.capture());
            assertEquals("/vol/flexvol/.snapshot/snap1/lun1", request.getValue().getLun().getClone().getSource().getName());
        }

        @Test
        void cloneFromSnapshotGrowsOnlyWhenSnapshotIsKnownAndSmallerThanVolume() {
            CloudStackVolume cloned = createdLun("/vol/flexvol/data_volume", "clone-uuid");
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(cloned);

            givenCloneOfSnapshot("ISCSI", "/vol/flexvol/lun1", ONE_GB - 1);
            assertTrue(create(sanStrategy, volumeInfo).isSuccess());
            verify(sanStrategy).resizeCloudStackVolume(cloned, ONE_GB);
        }

        @Test
        void cloneFromSnapshotDoesNotGrowWhenSnapshotSizeIsUnknown() {
            givenCloneOfSnapshot("ISCSI", "/vol/flexvol/lun1", 0L);
            when(sanStrategy.cloneCloudStackVolume(any())).thenReturn(createdLun("/vol/flexvol/data_volume", "clone-uuid"));

            assertTrue(create(sanStrategy, volumeInfo).isSuccess());

            verify(sanStrategy, never()).resizeCloudStackVolume(any(), anyLong());
        }

        @Test
        void cloneNfsFromSnapshotSendsSnapshotRelativeFilePath() {
            givenNfs();
            poolDetails.put(OntapStorageConstants.VOLUME_NAME, "flexvol");
            givenCloneOfSnapshot("NFS3", "src-file-uuid", ONE_GB);
            when(nasStrategy.cloneCloudStackVolume(any())).thenReturn(new CloudStackVolume());

            CreateCmdResult result = create(nasStrategy, volumeInfo);

            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).cloneCloudStackVolume(request.capture());
            assertEquals("5", request.getValue().getDatastoreId());
            assertEquals("./.snapshot/snap1/src-file-uuid", request.getValue().getFile().getPath());
            assertEquals("vol-uuid", request.getValue().getDestinationPath());
            assertSame(volumeInfo, request.getValue().getVolumeInfo());
            assertEquals("vol-uuid", result.getPath());
        }

        @Test
        void cloneFromSnapshotChecksPoolIopsCapacityBeforeCallingOntap() {
            when(storagePool.getCapacityIops()).thenReturn(100L);
            givenIops(200L, 300L);
            givenCloneOfSnapshot("ISCSI", "/vol/flexvol/lun1", ONE_GB);

            CreateCmdResult result = create(sanStrategy, volumeInfo);

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Insufficient IOPS capacity"), result.getResult());
            verify(sanStrategy, never()).cloneCloudStackVolume(any());
        }

        // ---------------------------------------------------------------- delete

        @Test
        void deleteIscsiVolumeSendsLunIdentityAndReportsSuccess() {
            volumeDetail(OntapStorageConstants.LUN_DOT_NAME, "/vol/flexvol/data_volume");
            volumeDetail(OntapStorageConstants.LUN_DOT_UUID, "lun-uuid");

            CommandResult result = delete(sanStrategy, dataStore, volumeInfo);

            assertTrue(result.isSuccess());
            assertNull(result.getResult());
            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(sanStrategy).deleteCloudStackVolume(request.capture());
            assertEquals("/vol/flexvol/data_volume", request.getValue().getLun().getName());
            assertEquals("lun-uuid", request.getValue().getLun().getUuid());
            verify(sanStrategy, never()).updateCloudStackVolume(any());
        }

        @Test
        void deleteNfsVolumeClearsQosThenDeletesFileAndRemovesThePolicy() {
            givenNfs();
            volumeDetail(OntapStorageConstants.QOS_POLICY_UUID, "old-policy");

            CommandResult result = delete(nasStrategy, dataStore, volumeInfo);

            assertTrue(result.isSuccess());
            ArgumentCaptor<CloudStackVolume> clear = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).updateCloudStackVolume(clear.capture());
            assertEquals(OntapStorageConstants.QOS_POLICY_NONE, clear.getValue().getFile().getQosPolicy().getName());
            ArgumentCaptor<CloudStackVolume> delete = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).deleteCloudStackVolume(delete.capture());
            assertEquals("5", delete.getValue().getDatastoreId());
            assertSame(volumeInfo, delete.getValue().getVolumeInfo());
            verify(nasStrategy).deleteVolumeQosPolicy("old-policy");
        }

        @Test
        void deleteNfsVolumeRestoresPreviousPolicyWhenDeleteFailsAfterItWasCleared() {
            givenNfs();
            volumeDetail(OntapStorageConstants.QOS_POLICY_UUID, "old-policy");
            doThrow(new CloudRuntimeException("delete failed")).when(nasStrategy).deleteCloudStackVolume(any(CloudStackVolume.class));

            CommandResult result = delete(nasStrategy, dataStore, volumeInfo);

            assertFalse(result.isSuccess());
            assertEquals("delete failed", result.getResult());
            ArgumentCaptor<CloudStackVolume> updates = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy, times(2)).updateCloudStackVolume(updates.capture());
            assertEquals("old-policy", updates.getAllValues().get(1).getFile().getQosPolicy().getUuid());
            verify(nasStrategy, never()).deleteVolumeQosPolicy(any());
        }

        @Test
        void deleteNfsVolumeDoesNotRestorePolicyWhenItCouldNotBeCleared() {
            givenNfs();
            volumeDetail(OntapStorageConstants.QOS_POLICY_UUID, "old-policy");
            doThrow(new CloudRuntimeException("clear failed")).when(nasStrategy).updateCloudStackVolume(any(CloudStackVolume.class));
            doThrow(new CloudRuntimeException("delete failed")).when(nasStrategy).deleteCloudStackVolume(any(CloudStackVolume.class));

            CommandResult result = delete(nasStrategy, dataStore, volumeInfo);

            assertFalse(result.isSuccess());
            assertEquals("delete failed", result.getResult());
            verify(nasStrategy, times(1)).updateCloudStackVolume(any(CloudStackVolume.class));
        }

        @Test
        void deleteReportsFailureWhenStoreOrDataIsMissingOrTypeIsUnsupported() {
            CommandResult noStore = delete(sanStrategy, null, volumeInfo);
            assertFalse(noStore.isSuccess());
            assertEquals("store or data is null", noStore.getResult());

            DataObject iso = mock(DataObject.class);
            when(iso.getType()).thenReturn(DataObjectType.ARCHIVE);
            driver.deleteAsync(dataStore, iso, commandCallback);
            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback, times(2)).complete(captor.capture());
            CommandResult unsupported = captor.getAllValues().get(1);
            assertFalse(unsupported.isSuccess());
            assertTrue(unsupported.getResult().contains("Unsupported data object type"), unsupported.getResult());
        }

        private SnapshotInfo givenSnapshot() {
            SnapshotInfo snapshot = mock(SnapshotInfo.class);
            when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
            when(snapshot.getType()).thenReturn(DataObjectType.SNAPSHOT);
            snapshotDetail(OntapStorageConstants.BASE_ONTAP_FV_ID, "fv-uuid");
            snapshotDetail(OntapStorageConstants.ONTAP_SNAP_ID, "snap-uuid");
            snapshotDetail(OntapStorageConstants.ONTAP_SNAP_NAME, "snap1");
            snapshotDetail(OntapStorageConstants.PRIMARY_POOL_ID, String.valueOf(POOL_ID));
            return snapshot;
        }

        @Test
        void deleteSnapshotReportsFailureWithOntapMessageAndSuccessOtherwise() {
            SnapshotInfo snapshot = givenSnapshot();
            doThrow(new CloudRuntimeException("ontap boom")).when(sanStrategy)
                    .deleteFlexVolSnapshotForCloudStackVolume("fv-uuid", "snap-uuid", "snap1");

            CommandResult failed = delete(sanStrategy, dataStore, snapshot);

            assertFalse(failed.isSuccess());
            assertEquals("ontap boom", failed.getResult());
        }

        @Test
        void deleteSnapshotReportsSuccessAfterOntapDelete() {
            SnapshotInfo snapshot = givenSnapshot();

            CommandResult result = delete(sanStrategy, dataStore, snapshot);

            assertTrue(result.isSuccess());
            assertNull(result.getResult());
            verify(sanStrategy).deleteFlexVolSnapshotForCloudStackVolume("fv-uuid", "snap-uuid", "snap1");
        }

        // ---------------------------------------------------------------- resize with QoS

        private void givenResize(long newSize, Long minIops, Long maxIops) {
            when(volumeInfo.getpayload()).thenReturn(new ResizeVolumePayload(newSize, minIops, maxIops, null, false, null, null, true));
            when(volumeVO.getSize()).thenReturn(ONE_GB);
        }

        private CreateCmdResult resize() {
            withStrategy(nasStrategy, () -> {
                driver.resize(volumeInfo, createCallback);
                return null;
            });
            ArgumentCaptor<CreateCmdResult> captor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(captor.capture());
            return captor.getValue();
        }

        @Test
        void resizeDeletesTheNewQosPolicyWhenOntapRejectsTheResize() {
            givenNfs();
            givenResize(2 * ONE_GB, 0L, 400L);
            volumeDetail(OntapStorageConstants.QOS_POLICY_UUID, "old-policy");
            when(nasStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("new", "new-policy"));
            doThrow(new CloudRuntimeException("resize refused")).when(nasStrategy).resizeCloudStackVolume(any(), anyLong());

            CreateCmdResult result = resize();

            assertFalse(result.isSuccess());
            assertEquals("resize refused", result.getResult());
            verify(nasStrategy).deleteVolumeQosPolicy("new-policy");
            verify(nasStrategy, never()).deleteVolumeQosPolicy("old-policy");
        }

        @Test
        void resizeKeepsTheExistingPolicyWhenItIsStillTheSame() {
            givenNfs();
            givenResize(2 * ONE_GB, 0L, 400L);
            volumeDetail(OntapStorageConstants.QOS_POLICY_UUID, "same-policy");
            when(nasStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("same", "same-policy"));
            doThrow(new CloudRuntimeException("resize refused")).when(nasStrategy).resizeCloudStackVolume(any(), anyLong());

            resize();

            verify(nasStrategy, never()).deleteVolumeQosPolicy(any());
        }

        @Test
        void resizeSwitchesToNewPolicyAndRemovesTheOldOne() {
            givenNfs();
            givenResize(2 * ONE_GB, 0L, 400L);
            volumeDetail(OntapStorageConstants.QOS_POLICY_UUID, "old-policy");
            when(nasStrategy.createVolumeQosPolicy(any(), any(), any())).thenReturn(policy("new", "new-policy"));

            CreateCmdResult result = resize();

            assertTrue(result.isSuccess());
            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).resizeCloudStackVolume(request.capture(), org.mockito.ArgumentMatchers.eq(2 * ONE_GB));
            assertEquals("new-policy", request.getValue().getFile().getQosPolicy().getUuid());
            verify(volumeDetailsDao).addDetail(VOLUME_ID, OntapStorageConstants.QOS_POLICY_UUID, "new-policy", false);
            verify(nasStrategy).deleteVolumeQosPolicy("old-policy");
        }

        @Test
        void resizeClearsPolicyWhenNewIopsAreRemoved() {
            givenNfs();
            givenResize(2 * ONE_GB, 0L, 0L);
            volumeDetail(OntapStorageConstants.QOS_POLICY_UUID, "old-policy");

            CreateCmdResult result = resize();

            assertTrue(result.isSuccess());
            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).resizeCloudStackVolume(request.capture(), org.mockito.ArgumentMatchers.eq(2 * ONE_GB));
            assertEquals(OntapStorageConstants.QOS_POLICY_NONE, request.getValue().getFile().getQosPolicy().getName());
            verify(nasStrategy).deleteVolumeQosPolicy("old-policy");
        }

        @Test
        void resizeWithoutQosChangeSendsOnlyTheVolumeReference() {
            givenNfs();
            givenResize(2 * ONE_GB, 0L, 0L);

            CreateCmdResult result = resize();

            assertTrue(result.isSuccess());
            ArgumentCaptor<CloudStackVolume> request = ArgumentCaptor.forClass(CloudStackVolume.class);
            verify(nasStrategy).resizeCloudStackVolume(request.capture(), org.mockito.ArgumentMatchers.eq(2 * ONE_GB));
            assertSame(volumeInfo, request.getValue().getVolumeInfo());
            assertNull(request.getValue().getFile());
            verify(nasStrategy, never()).deleteVolumeQosPolicy(any());
        }
    }

    /**
     * Snapshot workflows of {@link OntapPrimaryDatastoreDriver}: take, revert and delete of a
     * CloudStack volume snapshot backed by an ONTAP FlexVol snapshot.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class OntapPrimaryDatastoreDriverSnapshotTest {

        private static final long SNAPSHOT_ID = 77L;
        private static final long VOLUME_ID = 10L;
        private static final long POOL_ID = 5L;
        private static final String FLEXVOL_UUID = "flexvol-uuid";
        private static final String FLEXVOL_NAME = "flexvol-name";
        private static final String ONTAP_SNAP_UUID = "ontap-snap-uuid";
        private static final String LUN_NAME = "/vol/flexvol-name/lun1";
        private static final String LUN_UUID = "lun-uuid";
        private static final String NFS_FILE_PATH = "vol-file-uuid";
        private static final String EXPECTED_SNAPSHOT_NAME = "daily_cs77";

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
        private SnapshotDao snapshotDao;

        @Mock
        private SnapshotInfo snapshotInfo;
        @Mock
        private SnapshotInfo primarySnapshotInfo;
        @Mock
        private VolumeInfo baseVolumeInfo;
        @Mock
        private VolumeVO volumeVO;
        @Mock
        private StoragePoolVO storagePool;
        @Mock
        private DataStore dataStore;
        @Mock
        private StorageStrategy strategy;
        @Mock
        private SnapshotFeignClient snapshotClient;

        @Mock
        private AsyncCompletionCallback<CreateCmdResult> createCallback;
        @Mock
        private AsyncCompletionCallback<CommandResult> commandCallback;

        @InjectMocks
        private OntapPrimaryDatastoreDriver driver;

        private Map<String, String> poolDetails;
        private SnapshotObjectTO snapshotTo;
        private Map<Long, Map<String, String>> snapshotDetailStore;

        @BeforeEach
        void setUp() {
            poolDetails = new HashMap<>();
            poolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());
            poolDetails.put(OntapStorageConstants.VOLUME_UUID, FLEXVOL_UUID);
            poolDetails.put(OntapStorageConstants.VOLUME_NAME, FLEXVOL_NAME);
            poolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");

            snapshotTo = new SnapshotObjectTO();
            when(snapshotInfo.getId()).thenReturn(SNAPSHOT_ID);
            when(snapshotInfo.getName()).thenReturn("daily");
            when(snapshotInfo.getBaseVolume()).thenReturn(baseVolumeInfo);
            when(snapshotInfo.getTO()).thenReturn(snapshotTo);
            when(snapshotInfo.getType()).thenReturn(DataObjectType.SNAPSHOT);
            when(baseVolumeInfo.getId()).thenReturn(VOLUME_ID);
            when(baseVolumeInfo.getName()).thenReturn("vol-name");

            when(volumeDao.findById(VOLUME_ID)).thenReturn(volumeVO);
            when(volumeVO.getId()).thenReturn(VOLUME_ID);
            when(volumeVO.getPoolId()).thenReturn(POOL_ID);
            when(volumeVO.getPath()).thenReturn(NFS_FILE_PATH);
            when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePool);
            when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(poolDetails);
            when(dataStore.getId()).thenReturn(POOL_ID);

            when(strategy.getSnapshotFeignClient()).thenReturn(snapshotClient);
            when(strategy.getAuthHeader()).thenReturn("Basic abc");

            snapshotDetailStore = new HashMap<>();
            when(snapshotDetailsDao.findDetail(anyLong(), anyString())).thenAnswer(inv -> {
                String value = snapshotDetailStore.getOrDefault(inv.getArgument(0, Long.class), Collections.emptyMap())
                        .get(inv.getArgument(1, String.class));
                return value == null ? null : new SnapshotDetailsVO(inv.getArgument(0), inv.getArgument(1), value, false);
            });
        }

        // ---------------------------------------------------------------- helpers

        private void givenIscsiVolumeDetails() {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID, LUN_UUID, false));
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, LUN_NAME, false));
        }

        private void givenSnapshotCreationSucceeds() {
            Job job = new Job();
            job.setUuid("job-uuid");
            JobResponse response = new JobResponse();
            response.setJob(job);
            when(snapshotClient.createSnapshot(eq("Basic abc"), eq(FLEXVOL_UUID), any(FlexVolSnapshot.class))).thenReturn(response);
            when(strategy.jobPollForSuccess(eq("job-uuid"), anyInt(), anyInt())).thenReturn(true);

            FlexVolSnapshot created = new FlexVolSnapshot();
            created.setUuid(ONTAP_SNAP_UUID);
            OntapResponse<FlexVolSnapshot> lookup = new OntapResponse<>();
            lookup.setRecords(List.of(created));
            when(snapshotClient.getSnapshots(eq("Basic abc"), eq(FLEXVOL_UUID), anyMap())).thenReturn(lookup);
        }

        private CreateCmdResult takeSnapshot() {
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails)).thenReturn(strategy);
                driver.takeSnapshot(snapshotInfo, createCallback);
            }
            ArgumentCaptor<CreateCmdResult> captor = ArgumentCaptor.forClass(CreateCmdResult.class);
            verify(createCallback).complete(captor.capture());
            return captor.getValue();
        }

        private Map<String, String> persistedSnapshotDetails() {
            ArgumentCaptor<SnapshotDetailsVO> captor = ArgumentCaptor.forClass(SnapshotDetailsVO.class);
            verify(snapshotDetailsDao, atLeast(0)).persist(captor.capture());
            Map<String, String> persisted = new HashMap<>();
            for (SnapshotDetailsVO detail : captor.getAllValues()) {
                persisted.put(detail.getName(), detail.getValue());
            }
            return persisted;
        }

        private void assertTakeSnapshotFailed(CreateCmdResult result, String expectedMessagePart) {
            assertNotNull(result.getResult(), "takeSnapshot should report a failure");
            assertTrue(result.getResult().contains(expectedMessagePart),
                    "Expected '" + expectedMessagePart + "' in: " + result.getResult());
            assertFalse(result.getAnswer().getResult());
            verify(snapshotDetailsDao, never()).persist(any(SnapshotDetailsVO.class));
        }

        // ------------------------------------------------- takeSnapshot: positive

        @Test
        void takeSnapshot_createsFlexVolSnapshotAndPersistsDetails_forIscsi() {
            givenIscsiVolumeDetails();
            givenSnapshotCreationSucceeds();
            when(volumeVO.getMinIops()).thenReturn(100L);
            when(volumeVO.getMaxIops()).thenReturn(500L);

            CreateCmdResult result = takeSnapshot();

            assertNull(result.getResult());
            assertTrue(result.getAnswer().getResult());
            assertEquals(OntapStorageConstants.ONTAP_SNAP_ID + "=" + ONTAP_SNAP_UUID, snapshotTo.getPath());

            ArgumentCaptor<FlexVolSnapshot> request = ArgumentCaptor.forClass(FlexVolSnapshot.class);
            verify(snapshotClient).createSnapshot(eq("Basic abc"), eq(FLEXVOL_UUID), request.capture());
            assertEquals(EXPECTED_SNAPSHOT_NAME, request.getValue().getName());

            Map<String, String> details = persistedSnapshotDetails();
            assertEquals(String.valueOf(VOLUME_ID), details.get(OntapStorageConstants.SRC_CS_VOLUME_ID));
            assertEquals(FLEXVOL_UUID, details.get(OntapStorageConstants.BASE_ONTAP_FV_ID));
            assertEquals(ONTAP_SNAP_UUID, details.get(OntapStorageConstants.ONTAP_SNAP_ID));
            assertEquals(EXPECTED_SNAPSHOT_NAME, details.get(OntapStorageConstants.ONTAP_SNAP_NAME));
            assertEquals(LUN_NAME, details.get(OntapStorageConstants.VOLUME_PATH));
            assertEquals(String.valueOf(POOL_ID), details.get(OntapStorageConstants.PRIMARY_POOL_ID));
            assertEquals(ProtocolType.ISCSI.name(), details.get(OntapStorageConstants.PROTOCOL));
            assertEquals(LUN_UUID, details.get(OntapStorageConstants.LUN_DOT_UUID));
            assertEquals("100", details.get(OntapStorageConstants.MIN_IOPS));
            assertEquals("500", details.get(OntapStorageConstants.MAX_IOPS));
        }

        @Test
        void takeSnapshot_usesFilePathAndOmitsLunAndIops_forNfs() {
            poolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
            givenSnapshotCreationSucceeds();
            when(volumeVO.getMinIops()).thenReturn(null);
            when(volumeVO.getMaxIops()).thenReturn(0L);

            CreateCmdResult result = takeSnapshot();

            assertNull(result.getResult());
            Map<String, String> details = persistedSnapshotDetails();
            assertEquals(NFS_FILE_PATH, details.get(OntapStorageConstants.VOLUME_PATH));
            assertFalse(details.containsKey(OntapStorageConstants.LUN_DOT_UUID));
            assertFalse(details.containsKey(OntapStorageConstants.MIN_IOPS));
            assertFalse(details.containsKey(OntapStorageConstants.MAX_IOPS));
        }

        // ------------------------------------------------- takeSnapshot: negative

        @Test
        void takeSnapshot_fails_whenVolumeNotFound() {
            when(volumeDao.findById(VOLUME_ID)).thenReturn(null);

            assertTakeSnapshotFailed(takeSnapshot(), "CloudStack Volume not found");
        }

        @Test
        void takeSnapshot_fails_whenStoragePoolNotFound() {
            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

            assertTakeSnapshotFailed(takeSnapshot(), "Storage Pool not found");
        }

        @Test
        void takeSnapshot_fails_whenFlexVolUuidMissing() {
            poolDetails.remove(OntapStorageConstants.VOLUME_UUID);

            assertTakeSnapshotFailed(takeSnapshot(), "FlexVolume UUID not found");
        }

        @Test
        void takeSnapshot_fails_whenFlexVolUuidEmpty() {
            poolDetails.put(OntapStorageConstants.VOLUME_UUID, "");

            assertTakeSnapshotFailed(takeSnapshot(), "FlexVolume UUID not found");
        }

        @Test
        void takeSnapshot_fails_whenIscsiLunNameDetailMissing() {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME)).thenReturn(null);

            assertTakeSnapshotFailed(takeSnapshot(), "Unsupported protocol");
        }

        @Test
        void takeSnapshot_fails_whenIscsiLunNameValueNull() {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, null, false));

            assertTakeSnapshotFailed(takeSnapshot(), "No LUN name found");
        }

        @Test
        void takeSnapshot_fails_whenIscsiLunUuidDetailMissing() {
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_NAME, LUN_NAME, false));
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID)).thenReturn(null);

            assertTakeSnapshotFailed(takeSnapshot(), "LUN UUID not found");
        }

        @Test
        void takeSnapshot_fails_whenIscsiLunUuidValueNull() {
            givenIscsiVolumeDetails();
            when(volumeDetailsDao.findDetail(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID))
                    .thenReturn(new VolumeDetailVO(VOLUME_ID, OntapStorageConstants.LUN_DOT_UUID, null, false));

            assertTakeSnapshotFailed(takeSnapshot(), "LUN UUID not found");
        }

        @Test
        void takeSnapshot_fails_whenProtocolUnsupported() {
            poolDetails.put(OntapStorageConstants.PROTOCOL, "FC");

            assertTakeSnapshotFailed(takeSnapshot(), "Unsupported protocol");
        }

        @Test
        void takeSnapshot_fails_whenOntapReturnsNoJobResponse() {
            givenIscsiVolumeDetails();
            when(snapshotClient.createSnapshot(anyString(), anyString(), any(FlexVolSnapshot.class))).thenReturn(null);

            assertTakeSnapshotFailed(takeSnapshot(), "Failed to initiate FlexVolume snapshot");
        }

        @Test
        void takeSnapshot_fails_whenJobResponseHasNoJob() {
            givenIscsiVolumeDetails();
            when(snapshotClient.createSnapshot(anyString(), anyString(), any(FlexVolSnapshot.class))).thenReturn(new JobResponse());

            assertTakeSnapshotFailed(takeSnapshot(), "Failed to initiate FlexVolume snapshot");
        }

        @Test
        void takeSnapshot_fails_whenSnapshotJobDoesNotSucceed() {
            givenIscsiVolumeDetails();
            givenSnapshotCreationSucceeds();
            when(strategy.jobPollForSuccess(anyString(), anyInt(), anyInt())).thenReturn(false);

            assertTakeSnapshotFailed(takeSnapshot(), "snapshot job failed");
        }

        @Test
        void takeSnapshot_fails_whenSnapshotLookupReturnsNoResponse() {
            givenIscsiVolumeDetails();
            givenSnapshotCreationSucceeds();
            when(snapshotClient.getSnapshots(anyString(), anyString(), anyMap())).thenReturn(null);

            assertTakeSnapshotFailed(takeSnapshot(), "Failed to resolve snapshot UUID");
        }

        @Test
        void takeSnapshot_fails_whenSnapshotLookupReturnsNoRecords() {
            givenIscsiVolumeDetails();
            givenSnapshotCreationSucceeds();
            OntapResponse<FlexVolSnapshot> empty = new OntapResponse<>();
            empty.setRecords(Collections.emptyList());
            when(snapshotClient.getSnapshots(anyString(), anyString(), anyMap())).thenReturn(empty);

            assertTakeSnapshotFailed(takeSnapshot(), "Failed to resolve snapshot UUID");
        }

        @Test
        void takeSnapshot_fails_whenSnapshotLookupReturnsNullRecords() {
            givenIscsiVolumeDetails();
            givenSnapshotCreationSucceeds();
            when(snapshotClient.getSnapshots(anyString(), anyString(), anyMap())).thenReturn(new OntapResponse<>());

            assertTakeSnapshotFailed(takeSnapshot(), "Failed to resolve snapshot UUID");
        }

        @Test
        void takeSnapshot_fails_whenSnapshotUuidIsEmpty() {
            givenIscsiVolumeDetails();
            givenSnapshotCreationSucceeds();
            FlexVolSnapshot blank = new FlexVolSnapshot();
            blank.setUuid("");
            OntapResponse<FlexVolSnapshot> lookup = new OntapResponse<>();
            lookup.setRecords(List.of(blank));
            when(snapshotClient.getSnapshots(anyString(), anyString(), anyMap())).thenReturn(lookup);

            assertTakeSnapshotFailed(takeSnapshot(), "Failed to resolve snapshot UUID");
        }

        @Test
        void takeSnapshot_reportsFailure_whenOntapCallThrows() {
            givenIscsiVolumeDetails();
            when(snapshotClient.createSnapshot(anyString(), anyString(), any(FlexVolSnapshot.class)))
                    .thenThrow(new RuntimeException("ONTAP unreachable"));

            assertTakeSnapshotFailed(takeSnapshot(), "ONTAP unreachable");
        }

        // ------------------------------------------------------ revertSnapshot

        private void givenSnapshotDetail(long snapshotId, String key, String value) {
            snapshotDetailStore.computeIfAbsent(snapshotId, id -> new HashMap<>()).put(key, value);
        }

        private void givenRevertableSnapshot(long snapshotId, String protocol) {
            givenSnapshotDetail(snapshotId, OntapStorageConstants.BASE_ONTAP_FV_ID, FLEXVOL_UUID);
            givenSnapshotDetail(snapshotId, OntapStorageConstants.ONTAP_SNAP_ID, ONTAP_SNAP_UUID);
            givenSnapshotDetail(snapshotId, OntapStorageConstants.ONTAP_SNAP_NAME, EXPECTED_SNAPSHOT_NAME);
            givenSnapshotDetail(snapshotId, OntapStorageConstants.VOLUME_PATH, LUN_NAME);
            givenSnapshotDetail(snapshotId, OntapStorageConstants.PRIMARY_POOL_ID, String.valueOf(POOL_ID));
            givenSnapshotDetail(snapshotId, OntapStorageConstants.PROTOCOL, protocol);
            givenSnapshotDetail(snapshotId, OntapStorageConstants.LUN_DOT_UUID, LUN_UUID);
        }

        private CommandResult revertSnapshot(SnapshotInfo onImageStore, SnapshotInfo onPrimaryStore) {
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails)).thenReturn(strategy);
                driver.revertSnapshot(onImageStore, onPrimaryStore, commandCallback);
            }
            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(captor.capture());
            return captor.getValue();
        }

        @Test
        void revertSnapshot_restoresLunWithLunUuid_forIscsi() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            JobResponse job = new JobResponse();
            when(strategy.revertSnapshotForCloudStackVolume(EXPECTED_SNAPSHOT_NAME, FLEXVOL_UUID, ONTAP_SNAP_UUID,
                    LUN_NAME, LUN_UUID, FLEXVOL_NAME)).thenReturn(job);

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertNull(result.getResult());
            verify(strategy).executeCliSfsrRestore(eq(job), contains("revert snapshot [" + EXPECTED_SNAPSHOT_NAME + "]"));
        }

        @Test
        void revertSnapshot_omitsLunUuid_forNfs() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.NFS3.name());
            poolDetails.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
            JobResponse job = new JobResponse();
            when(strategy.revertSnapshotForCloudStackVolume(EXPECTED_SNAPSHOT_NAME, FLEXVOL_UUID, ONTAP_SNAP_UUID,
                    LUN_NAME, null, FLEXVOL_NAME)).thenReturn(job);

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertNull(result.getResult());
            verify(strategy).executeCliSfsrRestore(eq(job), anyString());
        }

        @Test
        void revertSnapshot_prefersPrimaryStoreSnapshotDetails() {
            long primarySnapshotId = 88L;
            when(primarySnapshotInfo.getId()).thenReturn(primarySnapshotId);
            givenRevertableSnapshot(primarySnapshotId, ProtocolType.ISCSI.name());
            JobResponse job = new JobResponse();
            when(strategy.revertSnapshotForCloudStackVolume(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(job);

            CommandResult result = revertSnapshot(snapshotInfo, primarySnapshotInfo);

            assertNull(result.getResult());
            verify(strategy).executeCliSfsrRestore(eq(job), anyString());
        }

        @Test
        void revertSnapshot_fails_whenFlexVolUuidDetailMissing() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.BASE_ONTAP_FV_ID);

            assertMissingDetailFailure(revertSnapshot(snapshotInfo, null));
        }

        @Test
        void revertSnapshot_fails_whenSnapshotNameDetailMissing() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.ONTAP_SNAP_NAME);

            assertMissingDetailFailure(revertSnapshot(snapshotInfo, null));
        }

        @Test
        void revertSnapshot_fails_whenVolumePathDetailMissing() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.VOLUME_PATH);

            assertMissingDetailFailure(revertSnapshot(snapshotInfo, null));
        }

        @Test
        void revertSnapshot_fails_whenPoolIdDetailMissing() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.PRIMARY_POOL_ID);

            assertMissingDetailFailure(revertSnapshot(snapshotInfo, null));
        }

        private void assertMissingDetailFailure(CommandResult result) {
            assertNotNull(result.getResult());
            assertTrue(result.getResult().contains("Missing required snapshot details"), result.getResult());
            verify(strategy, never()).executeCliSfsrRestore(any(), anyString());
        }

        @Test
        void revertSnapshot_fails_whenFlexVolNameMissingFromPoolDetails() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            poolDetails.remove(OntapStorageConstants.VOLUME_NAME);

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertTrue(result.getResult().contains("FlexVolume name not found"), result.getResult());
            verify(strategy, never()).executeCliSfsrRestore(any(), anyString());
        }

        @Test
        void revertSnapshot_fails_whenFlexVolNameIsEmpty() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            poolDetails.put(OntapStorageConstants.VOLUME_NAME, "");

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertTrue(result.getResult().contains("FlexVolume name not found"), result.getResult());
        }

        @Test
        void revertSnapshot_fails_whenPoolIdIsNotNumeric() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            givenSnapshotDetail(SNAPSHOT_ID, OntapStorageConstants.PRIMARY_POOL_ID, "not-a-number");

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertNotNull(result.getResult());
            verify(strategy, never()).executeCliSfsrRestore(any(), anyString());
        }

        @Test
        void revertSnapshot_fails_whenOntapRestoreCallThrows() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            when(strategy.revertSnapshotForCloudStackVolume(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenThrow(new CloudRuntimeException("restore rejected"));

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertTrue(result.getResult().contains("restore rejected"), result.getResult());
            verify(strategy, never()).executeCliSfsrRestore(any(), anyString());
        }

        @Test
        void revertSnapshot_fails_whenCliRestoreFails() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            JobResponse job = new JobResponse();
            when(strategy.revertSnapshotForCloudStackVolume(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(job);
            doThrow(new CloudRuntimeException("sfsr failed")).when(strategy).executeCliSfsrRestore(eq(job), anyString());

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertTrue(result.getResult().contains("sfsr failed"), result.getResult());
        }

        // ------------------------------------- deleteAsync: volume snapshot branch

        private CommandResult deleteSnapshot() {
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails)).thenReturn(strategy);
                driver.deleteAsync(dataStore, snapshotInfo, commandCallback);
            }
            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(captor.capture());
            return captor.getValue();
        }

        @Test
        void deleteAsync_deletesFlexVolSnapshot_whenSnapshotDetailsPresent() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
            assertNull(result.getResult());
            verify(strategy).deleteFlexVolSnapshotForCloudStackVolume(FLEXVOL_UUID, ONTAP_SNAP_UUID, EXPECTED_SNAPSHOT_NAME);
        }

        @Test
        void deleteAsync_isNoOpSuccess_whenFlexVolUuidDetailMissing() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.BASE_ONTAP_FV_ID);

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
            verify(strategy, never()).deleteFlexVolSnapshotForCloudStackVolume(anyString(), anyString(), anyString());
        }

        @Test
        void deleteAsync_isNoOpSuccess_whenOntapSnapshotUuidDetailMissing() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.ONTAP_SNAP_ID);

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
            verify(strategy, never()).deleteFlexVolSnapshotForCloudStackVolume(anyString(), anyString(), anyString());
        }

        @Test
        void deleteAsync_resolvesPoolFromVolume_whenPoolIdDetailMissing() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.PRIMARY_POOL_ID);
            SnapshotVO snapshotVO = mock(SnapshotVO.class);
            when(snapshotVO.getVolumeId()).thenReturn(VOLUME_ID);
            when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(snapshotVO);
            when(volumeDao.findByIdIncludingRemoved(VOLUME_ID)).thenReturn(volumeVO);

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
            verify(strategy).deleteFlexVolSnapshotForCloudStackVolume(FLEXVOL_UUID, ONTAP_SNAP_UUID, EXPECTED_SNAPSHOT_NAME);
        }

        @Test
        void deleteAsync_fallsBackToLastPoolId_whenVolumeHasNoCurrentPool() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.PRIMARY_POOL_ID);
            SnapshotVO snapshotVO = mock(SnapshotVO.class);
            when(snapshotVO.getVolumeId()).thenReturn(VOLUME_ID);
            when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(snapshotVO);
            when(volumeDao.findByIdIncludingRemoved(VOLUME_ID)).thenReturn(volumeVO);
            when(volumeVO.getPoolId()).thenReturn(null);
            when(volumeVO.getLastPoolId()).thenReturn(POOL_ID);

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
        }

        /**
         * Documents current behaviour: the "Snapshot not found" message raised while resolving the pool
         * matches the generic "not found" check, so the delete is reported as an idempotent success and
         * no ONTAP call is made. Flagged for review, because a CloudStack-side lookup failure is masked.
         */
        @Test
        void deleteAsync_reportsSuccessWithoutOntapCall_whenPoolIdDetailMissingAndSnapshotNotFound() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.PRIMARY_POOL_ID);
            when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(null);

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
            verify(strategy, never()).deleteFlexVolSnapshotForCloudStackVolume(anyString(), anyString(), anyString());
        }

        /** Same masking as above, for the "CloudStack Volume not found" lookup failure. */
        @Test
        void deleteAsync_reportsSuccessWithoutOntapCall_whenPoolIdDetailMissingAndVolumeNotFound() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.PRIMARY_POOL_ID);
            SnapshotVO snapshotVO = mock(SnapshotVO.class);
            when(snapshotVO.getVolumeId()).thenReturn(VOLUME_ID);
            when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(snapshotVO);
            when(volumeDao.findByIdIncludingRemoved(VOLUME_ID)).thenReturn(null);

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
            verify(strategy, never()).deleteFlexVolSnapshotForCloudStackVolume(anyString(), anyString(), anyString());
        }

        @Test
        void deleteAsync_fails_whenNoPoolCanBeResolvedForSnapshot() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.PRIMARY_POOL_ID);
            SnapshotVO snapshotVO = mock(SnapshotVO.class);
            when(snapshotVO.getVolumeId()).thenReturn(VOLUME_ID);
            when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(snapshotVO);
            when(volumeDao.findByIdIncludingRemoved(VOLUME_ID)).thenReturn(volumeVO);
            when(volumeVO.getPoolId()).thenReturn(null);
            when(volumeVO.getLastPoolId()).thenReturn(null);

            CommandResult result = deleteSnapshot();

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Cannot resolve storage pool"), result.getResult());
        }

        @Test
        void deleteAsync_fails_whenResolvedPoolIdIsNotPositive() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.PRIMARY_POOL_ID);
            SnapshotVO snapshotVO = mock(SnapshotVO.class);
            when(snapshotVO.getVolumeId()).thenReturn(VOLUME_ID);
            when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(snapshotVO);
            when(volumeDao.findByIdIncludingRemoved(VOLUME_ID)).thenReturn(volumeVO);
            when(volumeVO.getPoolId()).thenReturn(0L);

            CommandResult result = deleteSnapshot();

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Cannot resolve storage pool"), result.getResult());
        }

        @Test
        void deleteAsync_treatsHttp404AsAlreadyDeleted() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            FeignException notFound = mock(FeignException.class);
            when(notFound.status()).thenReturn(404);
            doThrow(notFound).when(strategy).deleteFlexVolSnapshotForCloudStackVolume(anyString(), anyString(), anyString());

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
            assertNull(result.getResult());
        }

        @Test
        void deleteAsync_treatsNotFoundMessageAsAlreadyDeleted() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            doThrow(new CloudRuntimeException("Snapshot entry doesn't exist")).when(strategy)
                    .deleteFlexVolSnapshotForCloudStackVolume(anyString(), anyString(), anyString());

            CommandResult result = deleteSnapshot();

            assertTrue(result.isSuccess());
        }

        @Test
        void deleteAsync_reportsFailure_whenOntapDeleteFailsForOtherReasons() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            doThrow(new CloudRuntimeException("volume busy")).when(strategy)
                    .deleteFlexVolSnapshotForCloudStackVolume(anyString(), anyString(), anyString());

            CommandResult result = deleteSnapshot();

            assertFalse(result.isSuccess());
            assertEquals("volume busy", result.getResult());
        }

        @Test
        void deleteAsync_reportsFailure_whenStoreIsNull() {
            driver.deleteAsync(null, snapshotInfo, commandCallback);

            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(captor.capture());
            assertFalse(captor.getValue().isSuccess());
            assertEquals("store or data is null", captor.getValue().getResult());
        }

        @Test
        void deleteAsync_reportsFailure_whenDataIsNull() {
            driver.deleteAsync(dataStore, null, commandCallback);

            ArgumentCaptor<CommandResult> captor = ArgumentCaptor.forClass(CommandResult.class);
            verify(commandCallback).complete(captor.capture());
            assertFalse(captor.getValue().isSuccess());
        }

        @Test
        void deleteAsync_toleratesNullCallback() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class, CALLS_REAL_METHODS)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails)).thenReturn(strategy);

                driver.deleteAsync(dataStore, snapshotInfo, null);
            }

            verify(strategy).deleteFlexVolSnapshotForCloudStackVolume(FLEXVOL_UUID, ONTAP_SNAP_UUID, EXPECTED_SNAPSHOT_NAME);
        }

        @Test
        void revertSnapshot_passesNullLunUuid_whenIscsiLunUuidDetailAbsent() {
            givenRevertableSnapshot(SNAPSHOT_ID, ProtocolType.ISCSI.name());
            snapshotDetailStore.get(SNAPSHOT_ID).remove(OntapStorageConstants.LUN_DOT_UUID);
            when(strategy.revertSnapshotForCloudStackVolume(anyString(), anyString(), anyString(), anyString(), isNull(), anyString()))
                    .thenReturn(new JobResponse());

            CommandResult result = revertSnapshot(snapshotInfo, null);

            assertNull(result.getResult());
        }
    }
}
