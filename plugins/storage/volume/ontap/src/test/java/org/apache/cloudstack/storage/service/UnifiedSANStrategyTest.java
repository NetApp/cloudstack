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
package org.apache.cloudstack.storage.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.client.SnapshotFeignClient;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Initiator;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.LunMap;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.VolumeQosPolicy;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.apache.cloudstack.storage.volume.VolumeObject;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;

import com.fasterxml.jackson.databind.ObjectMapper;

import feign.FeignException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnifiedSANStrategyTest {

    @Mock
    private SANFeignClient sanFeignClient;

    @Mock
    private org.apache.cloudstack.storage.feign.client.NASFeignClient nasFeignClient;

    @Mock
    private OntapStorage ontapStorage;

    @Mock
    private PrimaryDataStoreInfo primaryDataStoreInfo;

    @Mock
    private Scope scope;

    @Mock
    private StoragePoolDetailsDao storagePoolDetailsDao;

    @Mock
    private VolumeDetailsDao volumeDetailsDao;

    @Mock
    private SnapshotFeignClient snapshotFeignClient;

    private static final String LUN_UUID = "lun-uuid";

    private UnifiedSANStrategy unifiedSANStrategy;
    private String authHeader;

    @BeforeEach
    void setUp() {
        lenient().when(ontapStorage.getStorageIP()).thenReturn("192.168.1.100");
        lenient().when(ontapStorage.getUsername()).thenReturn("admin");
        lenient().when(ontapStorage.getPassword()).thenReturn("password");
        lenient().when(ontapStorage.getSvmName()).thenReturn("svm1");

        unifiedSANStrategy = new UnifiedSANStrategy(ontapStorage);

        // Use reflection to inject the mock SANFeignClient (field is in parent StorageStrategy class)
        try {
            java.lang.reflect.Field sanFeignClientField = StorageStrategy.class.getDeclaredField("sanFeignClient");
            sanFeignClientField.setAccessible(true);
            sanFeignClientField.set(unifiedSANStrategy, sanFeignClient);

            java.lang.reflect.Field nasFeignClientField = StorageStrategy.class.getDeclaredField("nasFeignClient");
            nasFeignClientField.setAccessible(true);
            nasFeignClientField.set(unifiedSANStrategy, nasFeignClient);

            // Also inject the storage field from parent class to ensure proper mocking
            java.lang.reflect.Field storageField = StorageStrategy.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            storageField.set(unifiedSANStrategy, ontapStorage);

            // Inject storagePoolDetailsDao
            java.lang.reflect.Field storagePoolDetailsDaoField = UnifiedSANStrategy.class.getDeclaredField("storagePoolDetailsDao");
            storagePoolDetailsDaoField.setAccessible(true);
            storagePoolDetailsDaoField.set(unifiedSANStrategy, storagePoolDetailsDao);

            // Inject volumeDetailsDao, used to resolve the LUN UUID during resize
            java.lang.reflect.Field volumeDetailsDaoField = UnifiedSANStrategy.class.getDeclaredField("volumeDetailsDao");
            volumeDetailsDaoField.setAccessible(true);
            volumeDetailsDaoField.set(unifiedSANStrategy, volumeDetailsDao);
            unifiedSANStrategy.snapshotFeignClient = snapshotFeignClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        authHeader = "Basic YWRtaW46cGFzc3dvcmQ="; // Base64 encoded admin:password
    }

    @Test
    void testCreateCloudStackVolume_Success() {
        // Setup
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");

        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        Lun createdLun = new Lun();
        createdLun.setName("/vol/vol1/lun1");
        createdLun.setUuid("lun-uuid-123");

        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(createdLun));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(response);

            // Execute
            CloudStackVolume result = unifiedSANStrategy.createCloudStackVolume(request);

            // Verify
            assertNotNull(result);
            assertNotNull(result.getLun());
            assertEquals("lun-uuid-123", result.getLun().getUuid());
            assertEquals("/vol/vol1/lun1", result.getLun().getName());

            verify(sanFeignClient).createLun(eq(authHeader), eq(true), any(Lun.class));
        }
    }

    @Test
    void testCreateTemplateCache_Success() {
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO storagePool =
                mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getHypervisor()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);

        org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo templateInfo =
                mock(org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo.class);
        when(templateInfo.getId()).thenReturn(50L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");

        Lun createdLun = new Lun();
        createdLun.setName("/vol/vol1/cs_tmpl_50");
        createdLun.setUuid("template-lun-uuid");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(createdLun));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenReturn(response);

            CloudStackVolume result = unifiedSANStrategy.createTemplateCache(
                    storagePool, templateInfo, details, 5368709120L);

            assertNotNull(result);
            assertEquals("template-lun-uuid", result.getLun().getUuid());
            assertEquals("/vol/vol1/cs_tmpl_50", result.getLun().getName());

            ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
            verify(sanFeignClient).createLun(eq(authHeader), eq(true), lunCaptor.capture());
            assertEquals("/vol/vol1/cs_tmpl_50", lunCaptor.getValue().getName());
            assertEquals(5368709120L, lunCaptor.getValue().getSpace().getSize());
        }
    }

    @Test
    void testCreateTemplateCache_UnknownSize_ThrowsException() {
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO storagePool =
                mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(storagePool.getId()).thenReturn(1L);
        org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo templateInfo =
                mock(org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo.class);
        when(templateInfo.getId()).thenReturn(50L);

        assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createTemplateCache(storagePool, templateInfo, Map.of(), 0L));
        verify(sanFeignClient, never()).createLun(any(), anyBoolean(), any());
    }

    @Test
    void testCreateTemplateCache_CreateFails_BestEffortDeletesLeftoverLun() {
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO storagePool =
                mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getHypervisor()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);

        org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo templateInfo =
                mock(org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo.class);
        when(templateInfo.getId()).thenReturn(50L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");

        FeignException createEx = mock(FeignException.class);
        when(createEx.status()).thenReturn(500);
        when(createEx.getMessage()).thenReturn("create failed");

        Lun leftover = new Lun();
        leftover.setName("/vol/vol1/cs_tmpl_50");
        leftover.setUuid("leftover-uuid");
        OntapResponse<Lun> leftoverResponse = new OntapResponse<>();
        leftoverResponse.setRecords(List.of(leftover));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenThrow(createEx);
            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(leftoverResponse);

            assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.createTemplateCache(storagePool, templateInfo, details, 5368709120L));

            verify(sanFeignClient).deleteLun(eq(authHeader), eq("leftover-uuid"), anyMap());
        }
    }

    @Test
    void testCreateTemplateCache_CreateFails_NoLeftoverLun_SkipsDelete() {
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO storagePool =
                mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getHypervisor()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);

        org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo templateInfo =
                mock(org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo.class);
        when(templateInfo.getId()).thenReturn(50L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");

        FeignException createEx = mock(FeignException.class);
        when(createEx.status()).thenReturn(500);
        when(createEx.getMessage()).thenReturn("create failed");

        OntapResponse<Lun> empty = new OntapResponse<>();
        empty.setRecords(List.of());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenThrow(createEx);
            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(empty);

            assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.createTemplateCache(storagePool, templateInfo, details, 5368709120L));

            verify(sanFeignClient, never()).deleteLun(any(), any(), anyMap());
        }
    }

    @Test
    void testCreateTemplateCache_CleanupFailureDoesNotMaskCreateError() {
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO storagePool =
                mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(storagePool.getName()).thenReturn("vol1");
        when(storagePool.getHypervisor()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);

        org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo templateInfo =
                mock(org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo.class);
        when(templateInfo.getId()).thenReturn(50L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");

        FeignException createEx = mock(FeignException.class);
        when(createEx.status()).thenReturn(500);
        when(createEx.getMessage()).thenReturn("create failed");

        Lun leftover = new Lun();
        leftover.setName("/vol/vol1/cs_tmpl_50");
        leftover.setUuid("leftover-uuid");
        OntapResponse<Lun> leftoverResponse = new OntapResponse<>();
        leftoverResponse.setRecords(List.of(leftover));

        FeignException deleteEx = mock(FeignException.class);
        when(deleteEx.status()).thenReturn(500);
        when(deleteEx.getMessage()).thenReturn("delete failed");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenThrow(createEx);
            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(leftoverResponse);
            doThrow(deleteEx).when(sanFeignClient).deleteLun(eq(authHeader), eq("leftover-uuid"), anyMap());

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.createTemplateCache(storagePool, templateInfo, details, 5368709120L));
            assertTrue(thrown.getMessage().contains("create failed") || thrown.getMessage().contains("Failed to create"));
        }
    }

    @Test
    void testCreateCloudStackVolume_NullRequest_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createCloudStackVolume(null));
    }

    @Test
    void testCreateCloudStackVolume_FeignException_ThrowsCloudRuntimeException() {
        // Setup
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("Internal server error");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenThrow(feignException);

            // Execute & Verify
            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createCloudStackVolume(request));
        }
    }

    @Test
    void testCreateCloudStackVolume_MinThroughputRejected_PropagatesOntapError() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        FeignException feignException = mock(FeignException.class);
        when(feignException.getMessage()).thenReturn("Bad Request");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenThrow(feignException);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.createCloudStackVolume(request));
            assertTrue(ex.getMessage().contains("Failed to create Lun"));
        }
    }

    @Test
    void testDeleteCloudStackVolume_Success() {
        // Setup
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.deleteLun(eq(authHeader), eq("lun-uuid-123"), anyMap())).thenReturn(null);

            // Execute
            unifiedSANStrategy.deleteCloudStackVolume(request);

            // Verify
            verify(sanFeignClient).deleteLun(eq(authHeader), eq("lun-uuid-123"), anyMap());
        }
    }

    @Test
    void testDeleteCloudStackVolume_NotFound_SkipsDeletion() {
        // Setup
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            doThrow(feignException).when(sanFeignClient).deleteLun(eq(authHeader), eq("lun-uuid-123"), anyMap());

            // Execute - should not throw exception
            assertDoesNotThrow(() -> unifiedSANStrategy.deleteCloudStackVolume(request));
        }
    }

    @Test
    void testGetCloudStackVolume_Success() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "/vol/vol1/lun1");

        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        lun.setUuid("lun-uuid-123");

        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(lun));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(response);

            // Execute
            CloudStackVolume result = unifiedSANStrategy.getCloudStackVolume(values);

            // Verify
            assertNotNull(result);
            assertNotNull(result.getLun());
            assertEquals("lun-uuid-123", result.getLun().getUuid());
            assertEquals("/vol/vol1/lun1", result.getLun().getName());
        }
    }

    @Test
    void testGetCloudStackVolume_NotFound_ReturnsNull() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "/vol/vol1/lun1");

        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(response);

            // Execute
            CloudStackVolume result = unifiedSANStrategy.getCloudStackVolume(values);

            // Verify
            assertNull(result);
        }
    }

    @Test
    void testCreateAccessGroup_Success() {
        // Setup
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getUuid()).thenReturn("host1");
        when(host1.getStorageUrl()).thenReturn("iqn.1993-08.org.debian:01:host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        Igroup createdIgroup = new Igroup();
        createdIgroup.setName("igroup1");

        OntapResponse<Igroup> response = new OntapResponse<>();
        response.setRecords(List.of(createdIgroup));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.createIgroup(eq(authHeader), eq(true), any(Igroup.class)))
                    .thenReturn(response);

            // Execute
            AccessGroup result = unifiedSANStrategy.createAccessGroup(accessGroup);

            // Verify
            assertNotNull(result);
            assertNotNull(result.getIgroup());
            assertEquals("igroup1", result.getIgroup().getName());

            ArgumentCaptor<Igroup> igroupCaptor = ArgumentCaptor.forClass(Igroup.class);
            verify(sanFeignClient).createIgroup(eq(authHeader), eq(true), igroupCaptor.capture());

            Igroup capturedIgroup = igroupCaptor.getValue();
            assertEquals("igroup1", capturedIgroup.getName());
            assertNotNull(capturedIgroup.getInitiators());
            assertEquals(1, capturedIgroup.getInitiators().size());
        }
    }

    @Test
    void testCreateAccessGroup_AlreadyExists_ReturnsSuccessfully() {
        // Setup
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getUuid()).thenReturn("host1");
        when(host1.getStorageUrl()).thenReturn("iqn.1993-08.org.debian:01:host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(409);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.createIgroup(eq(authHeader), eq(true), any(Igroup.class)))
                    .thenThrow(feignException);
            Igroup existingIgroup = new Igroup();
            existingIgroup.setName("igroup1");
            OntapResponse<Igroup> existing = new OntapResponse<>();
            existing.setRecords(List.of(existingIgroup));
            when(sanFeignClient.getIgroupResponse(eq(authHeader), any()))
                    .thenReturn(existing);

            // Execute
            AccessGroup result = unifiedSANStrategy.createAccessGroup(accessGroup);

            assertNotNull(result);
            assertEquals("igroup1", result.getIgroup().getName());
        }
    }

    @Test
    void testDeleteAccessGroup_Success() {
        // Setup
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        lenient().when(host1.getUuid()).thenReturn("host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        Igroup igroup = new Igroup();
        igroup.setName("igroup1");
        // Use reflection to set UUID since there's no setter
        try {
            java.lang.reflect.Field uuidField = Igroup.class.getDeclaredField("uuid");
            uuidField.setAccessible(true);
            uuidField.set(igroup, "igroup-uuid-123");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        OntapResponse<Igroup> response = new OntapResponse<>();
        response.setRecords(List.of(igroup));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenReturn(response);

            // Execute
            unifiedSANStrategy.deleteAccessGroup(accessGroup);

            // Verify
            verify(sanFeignClient).deleteIgroup(eq(authHeader), eq("igroup-uuid-123"));
        }
    }

    @Test
    void testDeleteAccessGroup_NotFound_SkipsDeletion() {
        // Setup
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        lenient().when(host1.getUuid()).thenReturn("host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenThrow(feignException);

            // Execute - should not throw exception
            assertDoesNotThrow(() -> unifiedSANStrategy.deleteAccessGroup(accessGroup));
        }
    }

    @Test
    void testGetAccessGroup_Success() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "igroup1");

        Igroup igroup = new Igroup();
        igroup.setName("igroup1");

        OntapResponse<Igroup> response = new OntapResponse<>();
        response.setRecords(List.of(igroup));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenReturn(response);

            // Execute
            AccessGroup result = unifiedSANStrategy.getAccessGroup(values);

            // Verify
            assertNotNull(result);
            assertNotNull(result.getIgroup());
            assertEquals("igroup1", result.getIgroup().getName());
        }
    }

    @Test
    void testEnableLogicalAccess_Success() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.createLunMap(eq(authHeader), eq(false), any(LunMap.class)))
                    .thenReturn(new OntapResponse<>());

            String result = unifiedSANStrategy.enableLogicalAccess(values);

            assertEquals("", result);
            verify(sanFeignClient).createLunMap(eq(authHeader), eq(false), any(LunMap.class));
            verify(sanFeignClient, never()).getLunMapResponse(any(), anyMap());
        }
    }

    @Test
    void testEnableLogicalAccess_AlreadyMapped_ReturnsLunNumber() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            Exception exception = new RuntimeException("LUN already mapped to this group");
            doThrow(exception).when(sanFeignClient).createLunMap(eq(authHeader), eq(false), any(LunMap.class));

            String result = unifiedSANStrategy.enableLogicalAccess(values);

            assertEquals("", result);
            verify(sanFeignClient, never()).getLunMapResponse(any(), anyMap());
        }
    }

    @Test
    void testDisableLogicalAccess_Success() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123");
        values.put(OntapStorageConstants.IGROUP_DOT_UUID, "igroup-uuid-123");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            doNothing().when(sanFeignClient).deleteLunMap(eq(authHeader), eq("lun-uuid-123"), eq("igroup-uuid-123"));

            // Execute
            unifiedSANStrategy.disableLogicalAccess(values);

            // Verify
            verify(sanFeignClient).deleteLunMap(eq(authHeader), eq("lun-uuid-123"), eq("igroup-uuid-123"));
        }
    }

    @Test
    void testDisableLogicalAccess_NotFound_SkipsDeletion() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123");
        values.put(OntapStorageConstants.IGROUP_DOT_UUID, "igroup-uuid-123");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            doThrow(feignException).when(sanFeignClient).deleteLunMap(eq(authHeader), eq("lun-uuid-123"), eq("igroup-uuid-123"));

            // Execute - should not throw exception
            assertDoesNotThrow(() -> unifiedSANStrategy.disableLogicalAccess(values));
        }
    }

    @Test
    void testGetLogicalAccess_Success() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        LunMap lunMap = new LunMap();
        lunMap.setLogicalUnitNumber(3);

        OntapResponse<LunMap> response = new OntapResponse<>();
        response.setRecords(List.of(lunMap));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap())).thenReturn(response);

            // Execute
            String result = unifiedSANStrategy.getLogicalAccess(values);

            // Verify
            assertNotNull(result);
            assertEquals("3", result);
        }
    }

    @Test
    void testGetLogicalAccess_NotFound_ReturnsNull() {
        // Setup
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap()))
                    .thenThrow(new RuntimeException("Not found"));

            // Execute
            String result = unifiedSANStrategy.getLogicalAccess(values);

            // Verify
            assertNull(result);
        }
    }

    @Test
    void testEnsureLunMapped_ExistingMapping_ReturnsLunNumber() {
        // Setup
        String svmName = "svm1";
        String lunName = "/vol/vol1/lun1";
        String accessGroupName = "igroup1";

        LunMap lunMap = new LunMap();
        lunMap.setLogicalUnitNumber(2);

        OntapResponse<LunMap> response = new OntapResponse<>();
        response.setRecords(List.of(lunMap));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap())).thenReturn(response);

            // Execute
            String result = unifiedSANStrategy.ensureLunMapped(svmName, lunName, accessGroupName);

            // Verify
            assertEquals("2", result);
            verify(sanFeignClient, never()).createLunMap(any(), anyBoolean(), any());
        }
    }

    @Test
    void testEnsureLunMapped_CreatesNewMapping_ReturnsLunNumber() {
        // Setup
        String svmName = "svm1";
        String lunName = "/vol/vol1/lun1";
        String accessGroupName = "igroup1";

        LunMap lunMap = new LunMap();
        lunMap.setLogicalUnitNumber(4);

        OntapResponse<LunMap> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        OntapResponse<LunMap> response = new OntapResponse<>();
        response.setRecords(List.of(lunMap));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            // First call returns empty (no existing mapping), second call returns the new mapping
            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap()))
                    .thenReturn(emptyResponse)  // First call - no records
                    .thenReturn(response);      // Second call after creation

            when(sanFeignClient.createLunMap(eq(authHeader), eq(false), any(LunMap.class)))
                    .thenReturn(new OntapResponse<>());

            // Execute
            String result = unifiedSANStrategy.ensureLunMapped(svmName, lunName, accessGroupName);

            ArgumentCaptor<LunMap> requestCaptor = ArgumentCaptor.forClass(LunMap.class);
            verify(sanFeignClient).createLunMap(eq(authHeader), eq(false), requestCaptor.capture());
            assertNull(requestCaptor.getValue().getLogicalUnitNumber());
            assertEquals("", result);
        }
    }

    @Test
    void testValidateInitiatorInAccessGroup_InitiatorFound_ReturnsTrue() {
        // Setup
        String hostInitiator = "iqn.1993-08.org.debian:01:host1";
        String svmName = "svm1";
        String accessGroupName = "igroup1";

        Initiator initiator = new Initiator();
        initiator.setName(hostInitiator);

        Igroup igroup = new Igroup();
        igroup.setName(accessGroupName);
        igroup.setInitiators(List.of(initiator));

        // Execute
        boolean result = unifiedSANStrategy.validateInitiatorInAccessGroup(hostInitiator, svmName, igroup);

        // Verify
        assertTrue(result);
    }

    @Test
    void testValidateInitiatorInAccessGroup_InitiatorNotFound_ReturnsFalse() {
        // Setup
        String hostInitiator = "iqn.1993-08.org.debian:01:host1";
        String svmName = "svm1";
        String accessGroupName = "igroup1";

        Initiator differentInitiator = new Initiator();
        differentInitiator.setName("iqn.1993-08.org.debian:01:host2");

        Igroup igroup = new Igroup();
        igroup.setName(accessGroupName);
        igroup.setInitiators(List.of(differentInitiator));

        // Execute
        boolean result = unifiedSANStrategy.validateInitiatorInAccessGroup(hostInitiator, svmName, igroup);

        // Verify
        assertFalse(result);
    }

    @Test
    void testValidateInitiatorInAccessGroup_EmptyInitiator_ReturnsFalse() {
        Igroup igroup = new Igroup();
        String accessGroupName = "igroup1";
        igroup.setName(accessGroupName);
        Initiator differentInitiator = new Initiator();
        differentInitiator.setName("iqn.1993-08.org.debian:01:host2");
        igroup.setInitiators(List.of(differentInitiator));
        boolean result = unifiedSANStrategy.validateInitiatorInAccessGroup("", "svm1", igroup);
        assertFalse(result);

        result = unifiedSANStrategy.validateInitiatorInAccessGroup(null, "svm1", igroup);
        assertFalse(result);
    }

    @Test
    void testValidateInitiatorInAccessGroup_IgroupNotFound_ReturnsFalse() {
        // Setup
        String hostInitiator = "iqn.1993-08.org.debian:01:host1";

        Igroup emptyIgroup = new Igroup();
        emptyIgroup.setName("igroup1");
        emptyIgroup.setInitiators(null);

        // Execute
        boolean result = unifiedSANStrategy.validateInitiatorInAccessGroup(hostInitiator, "svm1", emptyIgroup);

        // Verify
        assertFalse(result);
    }

    @Test
    void testCloneCloudStackVolume_Success() {
        Lun.Source source = new Lun.Source();
        source.setUuid("source-lun-uuid");
        Lun.Clone clone = new Lun.Clone();
        clone.setSource(source);
        Lun lun = new Lun();
        lun.setName("/vol/vol1/cloned");
        lun.setClone(clone);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(volumeInfo);

        Lun clonedLun = new Lun();
        clonedLun.setName("/vol/vol1/cloned");
        clonedLun.setUuid("cloned-lun-uuid");

        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(clonedLun));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(response);
            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(response);

            CloudStackVolume result = unifiedSANStrategy.cloneCloudStackVolume(request);

            assertNotNull(result);
            assertEquals("cloned-lun-uuid", result.getLun().getUuid());
            assertSame(volumeInfo, result.getVolumeInfo());
            verify(sanFeignClient).createLun(eq(authHeader), eq(true), any(Lun.class));
            verify(sanFeignClient).getLunResponse(eq(authHeader), anyMap());
        }
    }

    private CloudStackVolume copyDestLun(int timeoutSeconds) {
        VolumeInfo srcVolumeInfo = mock(VolumeInfo.class);
        VolumeInfo destVolumeInfo = mock(VolumeInfo.class);
        StoragePoolVO destStoragePool = mock(StoragePoolVO.class);
        when(srcVolumeInfo.getId()).thenReturn(11L);
        when(destVolumeInfo.getName()).thenReturn("lun");
        when(destStoragePool.getName()).thenReturn("dest");
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(11L, OntapStorageConstants.LUN_DOT_UUID, "source-lun-uuid", false));
        return unifiedSANStrategy.copyCloudStackVolume(srcVolumeInfo, destVolumeInfo, destStoragePool,
                Map.of(OntapStorageConstants.SVM_NAME, "svm1"), timeoutSeconds);
    }

    private void markLunCopyComplete(Lun lun) {
        Lun.Progress progress = new Lun.Progress();
        progress.setPercentComplete(100);
        Lun.Source source = new Lun.Source();
        source.setProgress(progress);
        Lun.Copy copy = new Lun.Copy();
        copy.setSource(source);
        lun.setCopy(copy);
    }

    @Test
    void testCopyCloudStackVolume_UsesCopySourceAndWaitsForCompletion() {
        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/dest/lun");
        copiedLun.setUuid("copied-lun-uuid");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(copiedLun));
        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenReturn(response);
        markLunCopyComplete(copiedLun);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any())).thenReturn(copiedLun);

        CloudStackVolume result = copyDestLun(10);

        assertEquals("copied-lun-uuid", result.getLun().getUuid());
        ArgumentCaptor<Lun> requestCaptor = ArgumentCaptor.forClass(Lun.class);
        verify(sanFeignClient).createLun(eq(authHeader), eq(true), requestCaptor.capture());
        assertNull(requestCaptor.getValue().getClone());
        assertEquals("source-lun-uuid", requestCaptor.getValue().getCopy().getSource().getUuid());
        assertEquals("/vol/dest/lun", requestCaptor.getValue().getName());
        assertEquals("svm1", requestCaptor.getValue().getSvm().getName());
        verify(sanFeignClient).getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any());
    }

    @Test
    void testCopyCloudStackVolume_RetriesWhenSourceLunIsBusy() {
        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/dest/lun");
        copiedLun.setUuid("copied-lun-uuid");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(copiedLun));

        FeignException sourceBusyException = mock(FeignException.class);
        when(sourceBusyException.status()).thenReturn(500);
        when(sourceBusyException.contentUTF8()).thenReturn("{\"error\":{\"code\":\"5375060\"}}");
        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                .thenThrow(sourceBusyException)
                .thenReturn(response);
        markLunCopyComplete(copiedLun);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any())).thenReturn(copiedLun);

        CloudStackVolume result = copyDestLun(3);

        assertEquals("copied-lun-uuid", result.getLun().getUuid());
        verify(sanFeignClient, times(2)).createLun(eq(authHeader), eq(true), any(Lun.class));
    }

    @Test
    void testCopyCloudStackVolume_ResumesExistingCopyAfterSourceBusyRetry() {
        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/dest/lun");
        copiedLun.setUuid("copied-lun-uuid");
        OntapResponse<Lun> existingCopyResponse = new OntapResponse<>();
        existingCopyResponse.setRecords(List.of(copiedLun));

        FeignException sourceBusyException = mock(FeignException.class);
        when(sourceBusyException.status()).thenReturn(500);
        when(sourceBusyException.contentUTF8()).thenReturn("{\"error\":{\"code\":\"5375060\"}}");
        FeignException copyAlreadyExistsException = mock(FeignException.class);
        when(copyAlreadyExistsException.status()).thenReturn(409);
        when(copyAlreadyExistsException.contentUTF8()).thenReturn("{\"error\":{\"code\":\"7018919\"}}");
        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                .thenThrow(sourceBusyException)
                .thenThrow(copyAlreadyExistsException);
        when(sanFeignClient.getLunResponse(authHeader, Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1",
                OntapStorageConstants.NAME, "/vol/dest/lun"))).thenReturn(existingCopyResponse);
        markLunCopyComplete(copiedLun);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any())).thenReturn(copiedLun);

        CloudStackVolume result = copyDestLun(3);

        assertEquals("copied-lun-uuid", result.getLun().getUuid());
        verify(sanFeignClient, times(2)).createLun(eq(authHeader), eq(true), any(Lun.class));
        verify(sanFeignClient).getLunResponse(authHeader, Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1",
                OntapStorageConstants.NAME, "/vol/dest/lun"));
        verify(sanFeignClient).getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any());
    }

    @Test
    void testCopyCloudStackVolume_RetriesCreateWhenExistingCopyDestinationIsAbsent() {
        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/dest/lun");
        copiedLun.setUuid("copied-lun-uuid");
        OntapResponse<Lun> createdCopyResponse = new OntapResponse<>();
        createdCopyResponse.setRecords(List.of(copiedLun));
        OntapResponse<Lun> absentDestinationResponse = new OntapResponse<>();

        FeignException sourceBusyException = mock(FeignException.class);
        when(sourceBusyException.status()).thenReturn(500);
        when(sourceBusyException.contentUTF8()).thenReturn("{\"error\":{\"code\":\"5375060\"}}");
        FeignException copyAlreadyExistsException = mock(FeignException.class);
        when(copyAlreadyExistsException.status()).thenReturn(409);
        when(copyAlreadyExistsException.contentUTF8()).thenReturn("{\"error\":{\"code\":\"7018919\"}}");
        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                .thenThrow(sourceBusyException)
                .thenThrow(copyAlreadyExistsException)
                .thenReturn(createdCopyResponse);
        when(sanFeignClient.getLunResponse(authHeader, Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1",
                OntapStorageConstants.NAME, "/vol/dest/lun"))).thenReturn(absentDestinationResponse);
        markLunCopyComplete(copiedLun);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any())).thenReturn(copiedLun);

        CloudStackVolume result = copyDestLun(3);

        assertEquals("copied-lun-uuid", result.getLun().getUuid());
        verify(sanFeignClient, times(3)).createLun(eq(authHeader), eq(true), any(Lun.class));
        verify(sanFeignClient).getLunResponse(authHeader, Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1",
                OntapStorageConstants.NAME, "/vol/dest/lun"));
        verify(sanFeignClient).getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any());
    }

    @Test
    void testCopyCloudStackVolume_DoesNotResumeUnrelatedExistingCopy() {
        FeignException copyAlreadyExistsException = mock(FeignException.class);
        when(copyAlreadyExistsException.status()).thenReturn(409);
        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenThrow(copyAlreadyExistsException);

        assertThrows(CloudRuntimeException.class, () -> copyDestLun(3));

        verify(sanFeignClient, never()).getLunResponse(eq(authHeader), anyMap());
    }

    @Test
    void testCopyCloudStackVolume_TimeoutDeletesPartialLun() {
        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/dest/lun");
        copiedLun.setUuid("copied-lun-uuid");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(copiedLun));

        Lun.Progress progress = new Lun.Progress();
        progress.setPercentComplete(50);
        progress.setState("copying");
        Lun.Source activeSource = new Lun.Source();
        activeSource.setProgress(progress);
        Lun.Copy activeCopy = new Lun.Copy();
        activeCopy.setSource(activeSource);
        Lun activeLun = new Lun();
        activeLun.setCopy(activeCopy);

        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenReturn(response);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any())).thenReturn(activeLun);

        assertThrows(CloudRuntimeException.class, () -> copyDestLun(0));

        verify(sanFeignClient).deleteLun(eq(authHeader), eq("copied-lun-uuid"), anyMap());
    }

    @Test
    void testCopyCloudStackVolume_NullProgressTimesOut() {
        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/dest/lun");
        copiedLun.setUuid("copied-lun-uuid");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(copiedLun));

        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenReturn(response);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any())).thenReturn(copiedLun);

        assertThrows(CloudRuntimeException.class, () -> copyDestLun(0));

        verify(sanFeignClient).deleteLun(eq(authHeader), eq("copied-lun-uuid"), anyMap());
    }

    private Lun lunWithCopyProgress(Integer percent, String state) {
        Lun.Progress progress = new Lun.Progress();
        progress.setPercentComplete(percent);
        progress.setState(state);
        Lun.Source source = new Lun.Source();
        source.setProgress(progress);
        Lun.Copy copy = new Lun.Copy();
        copy.setSource(source);
        Lun lun = new Lun();
        lun.setCopy(copy);
        return lun;
    }

    private long farDeadline() {
        return System.currentTimeMillis() + 60_000L;
    }

    private FeignException feignExceptionWithStatus(int status) {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(status);
        return exception;
    }

    @Test
    void testWaitForLunCopy_NullLunThrows() {
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("lun-uuid"), any())).thenReturn(null);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.waitForLunCopy(authHeader, "lun-uuid", farDeadline()));

        assertTrue(ex.getMessage().contains("Unable to retrieve ONTAP LUN copy status for Lun [lun-uuid]"));
    }

    @Test
    void testWaitForLunCopy_NotFoundWithinGraceTimeIsRetried() {
        FeignException notFound = feignExceptionWithStatus(404);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("lun-uuid"), any()))
                .thenThrow(notFound)
                .thenReturn(lunWithCopyProgress(100, "complete"));

        assertDoesNotThrow(() -> unifiedSANStrategy.waitForLunCopy(authHeader, "lun-uuid", farDeadline()));

        verify(sanFeignClient, times(2)).getLunByUUID(eq(authHeader), eq("lun-uuid"), any());
    }

    @Test
    void testWaitForLunCopy_NotFoundAfterGraceTimeThrowsWithCause() {
        FeignException notFound = feignExceptionWithStatus(404);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("lun-uuid"), any())).thenThrow(notFound);
        unifiedSANStrategy.lunCopyStatusGraceMs = 0L;

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.waitForLunCopy(authHeader, "lun-uuid", farDeadline()));

        assertSame(notFound, ex.getCause());
    }

    @Test
    void testWaitForLunCopy_NonNotFoundFeignErrorIsNotRetried() {
        FeignException serverError = feignExceptionWithStatus(500);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("lun-uuid"), any())).thenThrow(serverError);

        assertSame(serverError, assertThrows(FeignException.class,
                () -> unifiedSANStrategy.waitForLunCopy(authHeader, "lun-uuid", farDeadline())));

        verify(sanFeignClient, times(1)).getLunByUUID(eq(authHeader), eq("lun-uuid"), any());
    }

    @Test
    void testWaitForLunCopy_MissingCopyWithinGraceTimeIsRetried() {
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("lun-uuid"), any()))
                .thenReturn(new Lun())
                .thenReturn(lunWithCopyProgress(100, "complete"));

        assertDoesNotThrow(() -> unifiedSANStrategy.waitForLunCopy(authHeader, "lun-uuid", farDeadline()));

        verify(sanFeignClient, times(2)).getLunByUUID(eq(authHeader), eq("lun-uuid"), any());
    }

    @Test
    void testWaitForLunCopy_MissingCopyAfterGraceTimeThrows() {
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("lun-uuid"), any())).thenReturn(new Lun());
        unifiedSANStrategy.lunCopyStatusGraceMs = 0L;

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.waitForLunCopy(authHeader, "lun-uuid", farDeadline()));

        assertTrue(ex.getMessage().contains("did not return copy details for Lun [lun-uuid]"));
    }

    @Test
    void testWaitForLunCopy_FailedStateThrows() {
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("lun-uuid"), any()))
                .thenReturn(lunWithCopyProgress(40, "failed"));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.waitForLunCopy(authHeader, "lun-uuid", farDeadline()));

        assertTrue(ex.getMessage().contains("ONTAP LUN copy failed for Lun [lun-uuid]"));
    }

    @Test
    void testCopyCloudStackVolume_BuildsLunCopyRequestFromVolumes() {
        VolumeInfo srcVolumeInfo = mock(VolumeInfo.class);
        VolumeInfo destVolumeInfo = mock(VolumeInfo.class);
        StoragePoolVO destStoragePool = mock(StoragePoolVO.class);
        when(srcVolumeInfo.getId()).thenReturn(11L);
        when(destVolumeInfo.getName()).thenReturn("ROOT-12");
        when(destStoragePool.getName()).thenReturn("destPool");
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(11L, OntapStorageConstants.LUN_DOT_UUID, "source-lun-uuid", false));
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_NAME))
                .thenReturn(new VolumeDetailVO(11L, OntapStorageConstants.LUN_DOT_NAME, "/vol/srcPool/ROOT_11", false));

        Lun copiedLun = new Lun();
        copiedLun.setName("/vol/destPool/ROOT_12");
        copiedLun.setUuid("copied-lun-uuid");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(copiedLun));
        when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenReturn(response);
        markLunCopyComplete(copiedLun);
        when(sanFeignClient.getLunByUUID(eq(authHeader), eq("copied-lun-uuid"), any())).thenReturn(copiedLun);

        CloudStackVolume result = unifiedSANStrategy.copyCloudStackVolume(srcVolumeInfo, destVolumeInfo, destStoragePool,
                Map.of(OntapStorageConstants.SVM_NAME, "svm1"), 10);

        assertEquals("copied-lun-uuid", result.getLun().getUuid());
        ArgumentCaptor<Lun> requestCaptor = ArgumentCaptor.forClass(Lun.class);
        verify(sanFeignClient).createLun(eq(authHeader), eq(true), requestCaptor.capture());
        Lun request = requestCaptor.getValue();
        assertEquals("/vol/destPool/ROOT_12", request.getName());
        assertEquals("svm1", request.getSvm().getName());
        assertEquals("source-lun-uuid", request.getCopy().getSource().getUuid());
        assertEquals("/vol/srcPool/ROOT_11", request.getCopy().getSource().getName());
        assertNull(request.getClone());
    }

    @Test
    void testCopyCloudStackVolume_MissingSourceLunUuid_ThrowsException() {
        VolumeInfo srcVolumeInfo = mock(VolumeInfo.class);
        when(srcVolumeInfo.getId()).thenReturn(11L);
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_UUID)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.copyCloudStackVolume(srcVolumeInfo,
                mock(VolumeInfo.class), mock(StoragePoolVO.class), Map.of(OntapStorageConstants.SVM_NAME, "svm1"), 10));
        verify(sanFeignClient, never()).createLun(any(), anyBoolean(), any(Lun.class));
    }

    @Test
    void testCloneCloudStackVolume_NullRequest_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.cloneCloudStackVolume(null));
    }

    @Test
    void testCloneCloudStackVolume_MissingSource_ThrowsException() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/cloned");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.cloneCloudStackVolume(request));
    }

    @Test
    void testCloneCloudStackVolume_SnapshotSource_SendsSnapshotQualifiedName() {
        Lun.Source source = new Lun.Source();
        source.setName("/vol/vol1/.snapshot/snap_cs200/source_lun");
        Lun.Clone clone = new Lun.Clone();
        clone.setSource(source);
        Lun lun = new Lun();
        lun.setName("/vol/vol1/new_lun");
        lun.setClone(clone);
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        Lun createdLun = new Lun();
        createdLun.setName("/vol/vol1/new_lun");
        createdLun.setUuid("new-lun-uuid");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(createdLun));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenReturn(response);
            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(response);

            CloudStackVolume result = unifiedSANStrategy.cloneCloudStackVolume(request);

            assertEquals("new-lun-uuid", result.getLun().getUuid());
            ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
            verify(sanFeignClient).createLun(eq(authHeader), eq(true), lunCaptor.capture());
            assertEquals("/vol/vol1/.snapshot/snap_cs200/source_lun",
                    lunCaptor.getValue().getClone().getSource().getName());
            assertNull(lunCaptor.getValue().getClone().getSource().getUuid());
        }
    }

    @Test
    void testResizeCloudStackVolume_ValidRequest_PatchesSize() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(mock(VolumeInfo.class));

        unifiedSANStrategy.resizeCloudStackVolume(request, 21474836480L);

        ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
        verify(sanFeignClient).updateLun(any(), eq("lun-uuid-123"), lunCaptor.capture());
        assertEquals(21474836480L, lunCaptor.getValue().getSpace().getSize());
        assertNull(lunCaptor.getValue().getQosPolicy());
    }

    @Test
    void testResizeCloudStackVolume_PatchesSizeAndQosTogether() {
        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_0_to_5000_iops_svm1");
        qosPolicy.setUuid("qos-uuid");
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        lun.setQosPolicy(qosPolicy);
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(mock(VolumeInfo.class));

        unifiedSANStrategy.resizeCloudStackVolume(request, 21474836480L);

        ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
        verify(sanFeignClient).updateLun(any(), eq("lun-uuid-123"), lunCaptor.capture());
        assertEquals(21474836480L, lunCaptor.getValue().getSpace().getSize());
        assertEquals("cs_0_to_5000_iops_svm1", lunCaptor.getValue().getQosPolicy().getName());
        assertEquals("qos-uuid", lunCaptor.getValue().getQosPolicy().getUuid());
    }

    @Test
    void testResizeCloudStackVolume_NoUuid_Throws() {
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getId()).thenReturn(100L);
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(new Lun());
        request.setVolumeInfo(volumeInfo);

        // LUN UUID is absent on the request and cannot be resolved from volume details
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 100L));
        verify(sanFeignClient, never()).updateLun(any(), any(), any());
    }

    @Test
    void testResizeCloudStackVolume_BlankUuidDetail_Throws() {
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getId()).thenReturn(100L);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeInfo);
        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "   ", false));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.resizeCloudStackVolume(request, 100L));

        assertTrue(exception.getMessage().contains("LUN UUID not found"));
        verify(sanFeignClient, never()).updateLun(any(), any(), any());
    }

    @Test
    void testResizeCloudStackVolume_NoVolumeInfo_Throws() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 100L));
        verify(sanFeignClient, never()).updateLun(any(), any(), any());
    }

    @Test
    void testResizeCloudStackVolume_UuidResolvedFromVolumeDetails() {
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getId()).thenReturn(100L);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeInfo);

        when(volumeDetailsDao.findDetail(100L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(100L, OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123", false));

        unifiedSANStrategy.resizeCloudStackVolume(request, 21474836480L);

        ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
        verify(sanFeignClient).updateLun(any(), eq("lun-uuid-123"), lunCaptor.capture());
        assertEquals(21474836480L, lunCaptor.getValue().getSpace().getSize());
    }

    @Test
    void testResizeCloudStackVolume_InvalidSize_Throws() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(mock(VolumeInfo.class));

        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 0L));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(null, 100L));
        verify(sanFeignClient, never()).updateLun(any(), any(), any());
    }

    @Test
    void testResizeCloudStackVolume_FeignExceptions_Throw() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(mock(VolumeInfo.class));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            for (int status : new int[] {401, 403, 409, 500, -1}) {
                FeignException feignException = mock(FeignException.class);
                when(feignException.status()).thenReturn(status);
                when(feignException.getMessage()).thenReturn(
                        status == -1 ? "connection timed out" : "resize failed");
                doThrow(feignException).when(sanFeignClient)
                        .updateLun(eq(authHeader), eq("lun-uuid-123"), any(Lun.class));

                assertThrows(CloudRuntimeException.class,
                        () -> unifiedSANStrategy.resizeCloudStackVolume(request, 21474836480L));
            }
        }
    }

    @Test
    void testResizeCloudStackVolume_DeletedLun_ThrowsSpecificMessage() {
        Lun lun = new Lun();
        lun.setUuid("deleted-lun-uuid");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(mock(VolumeInfo.class));
        FeignException notFound = mock(FeignException.class);
        when(notFound.status()).thenReturn(404);
        when(notFound.getMessage()).thenReturn("LUN not found");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.isOntapObjectNotFoundError(notFound))
                    .thenReturn(true);
            doThrow(notFound).when(sanFeignClient)
                    .updateLun(eq(authHeader), eq("deleted-lun-uuid"), any(Lun.class));

            CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.resizeCloudStackVolume(request, 21474836480L));

            assertTrue(exception.getMessage().contains("no longer exists on ONTAP"));
            assertTrue(exception.getMessage().contains("deleted-lun-uuid"));
        }
    }

    @Test
    void testResizeCloudStackVolume_UnexpectedException_IsWrapped() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(mock(VolumeInfo.class));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            doThrow(new IllegalStateException("unexpected failure")).when(sanFeignClient)
                    .updateLun(eq(authHeader), eq("lun-uuid-123"), any(Lun.class));

            CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.resizeCloudStackVolume(request, 21474836480L));

            assertTrue(exception.getMessage().contains("Unexpected error"));
            assertTrue(exception.getMessage().contains("unexpected failure"));
        }
    }

    @Test
    void testResizeCloudStackVolume_SameSizePatchIsIdempotent() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);
        request.setVolumeInfo(mock(VolumeInfo.class));

        unifiedSANStrategy.resizeCloudStackVolume(request, 10737418240L);

        ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
        verify(sanFeignClient).updateLun(any(), eq("lun-uuid-123"), lunCaptor.capture());
        assertEquals(10737418240L, lunCaptor.getValue().getSpace().getSize());
    }

    @Test
    void testCloneCloudStackVolume_EmptyRecords_ThrowsException() {
        Lun.Source source = new Lun.Source();
        source.setUuid("source-lun-uuid");
        Lun.Clone clone = new Lun.Clone();
        clone.setSource(source);
        Lun lun = new Lun();
        lun.setName("/vol/vol1/cloned");
        lun.setClone(clone);

        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenReturn(response);

            assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.cloneCloudStackVolume(request));
        }
    }

    @Test
    void testCloneCloudStackVolume_FeignException_ThrowsException() {
        Lun.Source source = new Lun.Source();
        source.setUuid("source-lun-uuid");
        Lun.Clone clone = new Lun.Clone();
        clone.setSource(source);
        Lun lun = new Lun();
        lun.setName("/vol/vol1/cloned");
        lun.setClone(clone);

        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("clone failed");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class))).thenThrow(feignException);

            assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.cloneCloudStackVolume(request));
            verify(sanFeignClient, never()).deleteLun(any(), any(), anyMap());
        }
    }

    @Test
    void testSetOntapStorage() {
        OntapStorage newStorage = mock(OntapStorage.class);
        assertDoesNotThrow(() -> unifiedSANStrategy.setOntapStorage(newStorage));
    }

    @Test
    void testUpdateCloudStackVolume_AppliesQosPolicy() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_0_to_5000_iops_svm1");
        qosPolicy.setUuid("qos-uuid");
        lun.setQosPolicy(qosPolicy);
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.updateLun(eq(authHeader), eq("lun-uuid-123"), any(Lun.class)))
                    .thenReturn(null);

            CloudStackVolume result = unifiedSANStrategy.updateCloudStackVolume(request);

            assertSame(request, result);
            ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
            verify(sanFeignClient).updateLun(eq(authHeader), eq("lun-uuid-123"), lunCaptor.capture());
            assertEquals("cs_0_to_5000_iops_svm1", lunCaptor.getValue().getQosPolicy().getName());
            assertEquals("qos-uuid", lunCaptor.getValue().getQosPolicy().getUuid());
        }
    }

    @Test
    void testUpdateCloudStackVolume_UnexpectedException_IsWrapped() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_0_to_500_iops_svm1");
        qosPolicy.setUuid("qos-uuid");
        lun.setQosPolicy(qosPolicy);
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.updateLun(eq(authHeader), eq("lun-uuid-123"), any(Lun.class)))
                    .thenThrow(new IllegalStateException("decoder failed"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.updateCloudStackVolume(request));
            assertTrue(ex.getMessage().contains("Failed to update LUN"));
            assertTrue(ex.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void testUpdateCloudStackVolume_NoQosPolicy_SkipsPatch() {
        Lun lun = new Lun();
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        CloudStackVolume result = unifiedSANStrategy.updateCloudStackVolume(request);

        assertSame(request, result);
        verify(sanFeignClient, never()).updateLun(any(), any(), any());
    }

    @Test
    void testUpdateCloudStackVolume_InvalidRequest_ThrowsException() {
        CloudStackVolume lunWithoutUuid = new CloudStackVolume();
        lunWithoutUuid.setLun(new Lun());

        assertInvalidIscsiVolumeUpdate(null);
        assertInvalidIscsiVolumeUpdate(new CloudStackVolume());
        assertInvalidIscsiVolumeUpdate(lunWithoutUuid);

        verify(sanFeignClient, never()).updateLun(any(), any(), any());
    }

    private void assertInvalidIscsiVolumeUpdate(CloudStackVolume request) {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.updateCloudStackVolume(request));
        assertEquals("Invalid iSCSI volume request", ex.getMessage());
    }

    @Test
    void testUpdateAccessGroup_ReturnsNull() {
        AccessGroup accessGroup = new AccessGroup();
        AccessGroup result = unifiedSANStrategy.updateAccessGroup(accessGroup);
        assertNull(result);
    }

    @Test
    void testCreateAccessGroup_NullPrimaryDataStoreInfo_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createAccessGroup(accessGroup));
    }

    @Test
    void testCreateAccessGroup_NullDetails_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createAccessGroup(accessGroup));
    }

    @Test
    void testCreateAccessGroup_EmptyDetails_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(new HashMap<>());

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createAccessGroup(accessGroup));
    }

    @Test
    void testCreateAccessGroup_NullHostsToConnect_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        accessGroup.setHostsToConnect(null);

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createAccessGroup(accessGroup));
    }

    @Test
    void testCreateAccessGroup_EmptyHostsToConnect_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        accessGroup.setHostsToConnect(new ArrayList<>());

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createAccessGroup(accessGroup));
    }

    @Test
    void testCreateAccessGroup_HostWithoutIQN_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        lenient().when(scope.getScopeType()).thenReturn(com.cloud.storage.ScopeType.CLUSTER);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageUrl()).thenReturn("invalid-storage-url");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createAccessGroup(accessGroup));
    }

    @Test
    void testCreateAccessGroup_HostWithNullStorageUrl_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        lenient().when(scope.getScopeType()).thenReturn(com.cloud.storage.ScopeType.CLUSTER);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageUrl()).thenReturn(null);
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createAccessGroup(accessGroup));
    }

    @Test
    void testCreateAccessGroup_FeignExceptionNon409_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageUrl()).thenReturn("iqn.1993-08.org.debian:01:host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("Internal server error");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.createIgroup(eq(authHeader), eq(true), any(Igroup.class)))
                    .thenThrow(feignException);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createAccessGroup(accessGroup));
        }
    }

    @Test
    void testCreateAccessGroup_EmptyResponseRecords_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setScope(scope);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageUrl()).thenReturn("iqn.1993-08.org.debian:01:host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        OntapResponse<Igroup> response = new OntapResponse<>();
        response.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.createIgroup(eq(authHeader), eq(true), any(Igroup.class)))
                    .thenReturn(response);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createAccessGroup(accessGroup));
        }
    }

    @Test
    void testDeleteAccessGroup_NullAccessGroup_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.deleteAccessGroup(null));
    }

    @Test
    void testDeleteAccessGroup_NullPrimaryDataStoreInfo_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.deleteAccessGroup(accessGroup));
    }

    @Test
    void testDeleteAccessGroup_EmptyIgroupUuid_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getUuid()).thenReturn("host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        Igroup igroup = new Igroup();
        igroup.setName("igroup1");
        // UUID is null

        OntapResponse<Igroup> response = new OntapResponse<>();
        response.setRecords(List.of(igroup));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenReturn(response);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.deleteAccessGroup(accessGroup));
        }
    }

    @Test
    void testDeleteAccessGroup_FeignExceptionNon404_ThrowsException() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());

        lenient().when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getUuid()).thenReturn("host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("Internal server error");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenThrow(feignException);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.deleteAccessGroup(accessGroup));
        }
    }

    @Test
    void testGetAccessGroup_NullValues_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getAccessGroup(null));
    }

    @Test
    void testGetAccessGroup_EmptyValues_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getAccessGroup(new HashMap<>()));
    }

    @Test
    void testGetAccessGroup_NullSvmName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.NAME, "igroup1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getAccessGroup(values));
    }

    @Test
    void testGetAccessGroup_NullIgroupName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getAccessGroup(values));
    }

    @Test
    void testGetAccessGroup_FeignExceptionNon404_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "igroup1");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("Internal server error");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenThrow(feignException);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.getAccessGroup(values));
        }
    }

    @Test
    void testGetCloudStackVolume_NullValues_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getCloudStackVolume(null));
    }

    @Test
    void testGetCloudStackVolume_EmptyValues_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getCloudStackVolume(new HashMap<>()));
    }

    @Test
    void testGetCloudStackVolume_NullSvmName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.NAME, "/vol/vol1/lun1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getCloudStackVolume(values));
    }

    @Test
    void testGetCloudStackVolume_NullLunName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getCloudStackVolume(values));
    }

    @Test
    void testGetCloudStackVolume_FeignExceptionNon404_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "/vol/vol1/lun1");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("Internal server error");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenThrow(feignException);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.getCloudStackVolume(values));
        }
    }

    @Test
    void testEnableLogicalAccess_NullValues_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.enableLogicalAccess(null));
    }

    @Test
    void testEnableLogicalAccess_MissingSvmName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.enableLogicalAccess(values));
    }

    @Test
    void testEnableLogicalAccess_MissingLunName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.enableLogicalAccess(values));
    }

    @Test
    void testEnableLogicalAccess_MissingIgroupName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.enableLogicalAccess(values));
    }

    @Test
    void testEnableLogicalAccess_FetchLunMapFails_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            doThrow(new RuntimeException("ONTAP unavailable")).when(sanFeignClient)
                    .createLunMap(eq(authHeader), eq(false), any(LunMap.class));

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.enableLogicalAccess(values));
        }
    }

    @Test
    void testDisableLogicalAccess_NullValues_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.disableLogicalAccess(null));
    }

    @Test
    void testDisableLogicalAccess_MissingLunUuid_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.IGROUP_DOT_UUID, "igroup-uuid-123");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.disableLogicalAccess(values));
    }

    @Test
    void testDisableLogicalAccess_MissingIgroupUuid_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.disableLogicalAccess(values));
    }

    @Test
    void testDisableLogicalAccess_FeignExceptionNon404_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123");
        values.put(OntapStorageConstants.IGROUP_DOT_UUID, "igroup-uuid-123");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("Internal server error");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            doThrow(feignException).when(sanFeignClient).deleteLunMap(eq(authHeader), eq("lun-uuid-123"), eq("igroup-uuid-123"));

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.disableLogicalAccess(values));
        }
    }

    @Test
    void testGetLogicalAccess_NullValues_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getLogicalAccess(null));
    }

    @Test
    void testGetLogicalAccess_MissingSvmName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getLogicalAccess(values));
    }

    @Test
    void testEnsureLunMapped_CreateNewMapping_Success() {
        String svmName = "svm1";
        String lunName = "/vol/vol1/lun1";
        String accessGroupName = "igroup1";

        OntapResponse<LunMap> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap()))
                    .thenReturn(emptyResponse);

            when(sanFeignClient.createLunMap(eq(authHeader), eq(false), any(LunMap.class)))
                    .thenReturn(new OntapResponse<>());

            String result = unifiedSANStrategy.ensureLunMapped(svmName, lunName, accessGroupName);

            assertEquals("", result);
            verify(sanFeignClient).createLunMap(eq(authHeader), eq(false), any(LunMap.class));
        }
    }

    @Test
    void testEnsureLunMapped_FailedToMap_ThrowsException() {
        String svmName = "svm1";
        String lunName = "/vol/vol1/lun1";
        String accessGroupName = "igroup1";

        OntapResponse<LunMap> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap()))
                    .thenReturn(emptyResponse);

            doThrow(new RuntimeException("ONTAP unavailable")).when(sanFeignClient)
                    .createLunMap(eq(authHeader), eq(false), any(LunMap.class));

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.ensureLunMapped(svmName, lunName, accessGroupName));
        }
    }

    @Test
    void testValidateInitiatorInAccessGroup_NullIgroupInitiators_ReturnsFalse() {
        String hostInitiator = "iqn.1993-08.org.debian:01:host1";
        String svmName = "svm1";
        String accessGroupName = "igroup1";

        Igroup igroup = new Igroup();
        igroup.setName(accessGroupName);
        igroup.setInitiators(null);

        // Execute
        boolean result = unifiedSANStrategy.validateInitiatorInAccessGroup(hostInitiator, svmName, igroup);

        // Verify
        assertFalse(result);
    }

    // ============= Additional Test Cases for Complete Coverage =============

    @Test
    void testCreateCloudStackVolume_NullLun_ThrowsException() {
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(null);

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.createCloudStackVolume(request));
    }

    @Test
    void testCreateCloudStackVolume_EmptyResponse_ThrowsException() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        OntapResponse<Lun> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(emptyResponse);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createCloudStackVolume(request));
        }
    }


    @Test
    void testCreateCloudStackVolume_IncompleteLunMissingName_ThrowsException() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        Lun incomplete = new Lun();
        incomplete.setUuid("lun-uuid-123");
        // name intentionally null
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(incomplete));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(response);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.createCloudStackVolume(request));
            assertTrue(ex.getMessage().contains("incomplete LUN"));
        }
    }

    @Test
    void testCreateCloudStackVolume_IncompleteLunMissingUuid_ThrowsException() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        Lun incomplete = new Lun();
        incomplete.setName("/vol/vol1/lun1");
        // uuid intentionally null
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(incomplete));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(response);

            assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.createCloudStackVolume(request));
        }
    }

    @Test
    void testCloneCloudStackVolume_IncompleteLunMissingUuid_ThrowsException() {
        Lun.Source source = new Lun.Source();
        source.setUuid("source-lun-uuid");
        Lun.Clone clone = new Lun.Clone();
        clone.setSource(source);
        Lun lun = new Lun();
        lun.setName("/vol/vol1/cloned");
        lun.setClone(clone);

        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        Lun incomplete = new Lun();
        incomplete.setName("/vol/vol1/cloned");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(incomplete));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(response);

            assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.cloneCloudStackVolume(request));
        }
    }

    @Test
    void testCreateCloudStackVolume_NullResponse_ThrowsException() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(null);

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createCloudStackVolume(request));
        }
    }

    @Test
    void testDeleteCloudStackVolume_NullRequest_ThrowsException() {
        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.deleteCloudStackVolume(null));
    }

    @Test
    void testDeleteCloudStackVolume_NullLun_ThrowsException() {
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(null);

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.deleteCloudStackVolume(request));
    }

    @Test
    void testDeleteCloudStackVolume_FeignException_ThrowsCloudRuntimeException() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        lun.setUuid("lun-uuid-123");
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("Internal server error");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            doThrow(feignException).when(sanFeignClient).deleteLun(eq(authHeader), eq("lun-uuid-123"), anyMap());

            assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.deleteCloudStackVolume(request));
        }
    }

    @Test
    void testGetCloudStackVolume_FeignException404_ReturnsNull() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "/vol/vol1/lun1");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenThrow(feignException);

            CloudStackVolume result = unifiedSANStrategy.getCloudStackVolume(values);

            assertNull(result);
        }
    }

    @Test
    void testGetCloudStackVolume_EmptyResponse_ReturnsNull() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "/vol/vol1/lun1");

        OntapResponse<Lun> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunResponse(eq(authHeader), anyMap())).thenReturn(emptyResponse);

            CloudStackVolume result = unifiedSANStrategy.getCloudStackVolume(values);

            assertNull(result);
        }
    }

    @Test
    void testGetAccessGroup_FeignException404_ReturnsNull() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "igroup1");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenThrow(feignException);

            AccessGroup result = unifiedSANStrategy.getAccessGroup(values);

            assertNull(result);
        }
    }

    @Test
    void testGetAccessGroup_EmptyResponse_ReturnsNull() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.NAME, "igroup1");

        OntapResponse<Igroup> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenReturn(emptyResponse);

            AccessGroup result = unifiedSANStrategy.getAccessGroup(values);

            assertNull(result);
        }
    }

    @Test
    void testEnableLogicalAccess_EmptySvmName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.enableLogicalAccess(values));
    }

    @Test
    void testEnableLogicalAccess_EmptyLunName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.enableLogicalAccess(values));
    }

    @Test
    void testEnableLogicalAccess_EmptyIgroupName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "");

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.enableLogicalAccess(values));
    }

    @Test
    void testDisableLogicalAccess_EmptyLunUuid_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_UUID, "");
        values.put(OntapStorageConstants.IGROUP_DOT_UUID, "igroup-uuid-123");

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.disableLogicalAccess(values));
    }

    @Test
    void testDisableLogicalAccess_EmptyIgroupUuid_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123");
        values.put(OntapStorageConstants.IGROUP_DOT_UUID, "");

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.disableLogicalAccess(values));
    }

    @Test
    void testDisableLogicalAccess_FeignException404_SkipsDeletion() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.LUN_DOT_UUID, "lun-uuid-123");
        values.put(OntapStorageConstants.IGROUP_DOT_UUID, "igroup-uuid-123");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            doThrow(feignException).when(sanFeignClient).deleteLunMap(eq(authHeader), eq("lun-uuid-123"), eq("igroup-uuid-123"));

            // Should not throw exception for 404
            assertDoesNotThrow(() -> unifiedSANStrategy.disableLogicalAccess(values));
        }
    }

    @Test
    void testGetLogicalAccess_MissingLunName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getLogicalAccess(values));
    }

    @Test
    void testGetLogicalAccess_MissingIgroupName_ThrowsException() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");

        assertThrows(CloudRuntimeException.class,
            () -> unifiedSANStrategy.getLogicalAccess(values));
    }

    @Test
    void testGetLogicalAccess_EmptyResponse_ReturnsNull() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        OntapResponse<LunMap> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap())).thenReturn(emptyResponse);

            String result = unifiedSANStrategy.getLogicalAccess(values);

            assertNull(result);
        }
    }

    @Test
    void testGetLogicalAccess_ExceptionThrown_ReturnsNull() {
        Map<String, String> values = new HashMap<>();
        values.put(OntapStorageConstants.SVM_DOT_NAME, "svm1");
        values.put(OntapStorageConstants.LUN_DOT_NAME, "/vol/vol1/lun1");
        values.put(OntapStorageConstants.IGROUP_DOT_NAME, "igroup1");

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap()))
                    .thenThrow(new RuntimeException("Connection failed"));

            String result = unifiedSANStrategy.getLogicalAccess(values);

            assertNull(result);
        }
    }

    @Test
    void testValidateInitiatorInAccessGroup_NullInitiator_ReturnsFalse() {
        Igroup igroup = new Igroup();
        igroup.setName("igroup1");
        boolean result = unifiedSANStrategy.validateInitiatorInAccessGroup(null, "svm1", igroup);
        assertFalse(result);
    }

    @Test
    void testValidateInitiatorInAccessGroup_AccessGroupNotFound_ReturnsFalse() {
        String hostInitiator = "iqn.1993-08.org.debian:01:host1";
        String svmName = "svm1";

        Igroup igroup = new Igroup();
        igroup.setName("igroup1");
        igroup.setInitiators(null);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            boolean result = unifiedSANStrategy.validateInitiatorInAccessGroup(hostInitiator, svmName, igroup);

            assertFalse(result);
        }
    }

    @Test
    void testDeleteAccessGroup_FeignException404_SkipsDeletion() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getUuid()).thenReturn("host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenThrow(feignException);

            // Should not throw exception for 404
            assertDoesNotThrow(() -> unifiedSANStrategy.deleteAccessGroup(accessGroup));
        }
    }

    @Test
    void testDeleteAccessGroup_NotFoundInResponse_SkipsDeletion() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getUuid()).thenReturn("host1");
        hosts.add(host1);
        accessGroup.setHostsToConnect(hosts);

        OntapResponse<Igroup> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            utilityMock.when(() -> OntapStorageUtils.getIgroupName("svm1", "host1"))
                    .thenReturn("igroup1");

            when(sanFeignClient.getIgroupResponse(eq(authHeader), anyMap())).thenReturn(emptyResponse);

            // Should not throw exception when not found
            assertDoesNotThrow(() -> unifiedSANStrategy.deleteAccessGroup(accessGroup));
        }
    }

    @Test
    void testEnsureLunMapped_ExistingMapping_ReturnsExistingNumber() {
        // Setup
        String svmName = "svm1";
        String lunName = "/vol/vol1/lun1";
        String accessGroupName = "igroup1";

        LunMap lunMap = new LunMap();
        lunMap.setLogicalUnitNumber(3);

        OntapResponse<LunMap> response = new OntapResponse<>();
        response.setRecords(List.of(lunMap));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);

            when(sanFeignClient.getLunMapResponse(eq(authHeader), anyMap())).thenReturn(response);

            // Execute
            String result = unifiedSANStrategy.ensureLunMapped(svmName, lunName, accessGroupName);

            // Verify
            assertEquals("3", result);
            // Verify createLunMap was NOT called
            verify(sanFeignClient, never()).createLunMap(any(), anyBoolean(), any(LunMap.class));
        }
    }

    @Test
    void testCreateCloudStackVolume_PassesQosPolicyOnLunCreate() {
        Lun lun = new Lun();
        lun.setName("/vol/vol1/lun1");
        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to200_iops_svm1");
        lun.setQosPolicy(qosPolicy);
        CloudStackVolume request = new CloudStackVolume();
        request.setLun(lun);

        Lun createdLun = new Lun();
        createdLun.setName("/vol/vol1/lun1");
        createdLun.setUuid("lun-uuid-123");
        OntapResponse<Lun> response = new OntapResponse<>();
        response.setRecords(List.of(createdLun));

        try (MockedStatic<OntapStorageUtils> utilityMock = mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.generateAuthHeader("admin", "password"))
                    .thenReturn(authHeader);
            when(sanFeignClient.createLun(eq(authHeader), eq(true), any(Lun.class)))
                    .thenReturn(response);

            unifiedSANStrategy.createCloudStackVolume(request);

            ArgumentCaptor<Lun> lunCaptor = ArgumentCaptor.forClass(Lun.class);
            verify(sanFeignClient).createLun(eq(authHeader), eq(true), lunCaptor.capture());
            assertNotNull(lunCaptor.getValue().getQosPolicy());
            assertEquals("cs_100_to200_iops_svm1", lunCaptor.getValue().getQosPolicy().getName());
        }
    }

    /** A real FeignException (mocks have no stack trace, which breaks logging of the cause). */
    private static FeignException feignException(int status, String body) {
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.GET, "http://ontap/api",
                new HashMap<>(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        feign.Response response = feign.Response.builder().status(status).reason("reason").request(request)
                .headers(new HashMap<>()).body(body, java.nio.charset.StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("ontapCall", response);
    }

    private static Lun lun(String name, String uuid) {
        Lun lun = new Lun();
        lun.setName(name);
        lun.setUuid(uuid);
        return lun;
    }

    private static CloudStackVolume lunVolume(Lun lun) {
        CloudStackVolume volume = new CloudStackVolume();
        volume.setLun(lun);
        return volume;
    }

    private static Lun cloneRequestLun() {
        Lun.Source source = new Lun.Source();
        source.setName("/vol/v/src");
        Lun.Clone clone = new Lun.Clone();
        clone.setSource(source);
        Lun lun = lun("/vol/v/dst", null);
        lun.setClone(clone);
        return lun;
    }

    private static Igroup igroup(String uuid, String name) throws Exception {
        return new ObjectMapper().readValue("{\"uuid\":\"" + uuid + "\",\"name\":\"" + name + "\"}", Igroup.class);
    }

    private static HostVO host(String uuid, String storageUrl) {
        HostVO host = mock(HostVO.class);
        when(host.getUuid()).thenReturn(uuid);
        when(host.getStorageUrl()).thenReturn(storageUrl);
        return host;
    }

    private static AccessGroup accessGroup(HostVO... hosts) {
        AccessGroup group = new AccessGroup();
        group.setStoragePoolId(5L);
        group.setHostsToConnect(new ArrayList<>(java.util.Arrays.asList(hosts)));
        return group;
    }

    private void poolDetails(String svmName, String protocol) {
        Map<String, String> details = new HashMap<>();
        if (svmName != null) {
            details.put(OntapStorageConstants.SVM_NAME, svmName);
        }
        details.put(OntapStorageConstants.PROTOCOL, protocol);
        when(storagePoolDetailsDao.listDetailsKeyPairs(5L)).thenReturn(details);
    }

    private static Lun withCopyProgress(Lun lun, Integer percent, String state) {
        Lun.Progress progress = new Lun.Progress();
        progress.setPercentComplete(percent);
        progress.setState(state);
        Lun.Source source = new Lun.Source();
        source.setProgress(progress);
        Lun.Copy copy = new Lun.Copy();
        copy.setSource(source);
        lun.setCopy(copy);
        return lun;
    }

    // -------------------------------------------------------- createCloudStackVolume

    @Test
    public void createVolumeRejectsMissingRequestOrLun() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createCloudStackVolume(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createCloudStackVolume(new CloudStackVolume()));
        verify(sanFeignClient, never()).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void createVolumeFailsWhenOntapReturnsNoRecords() {
        CloudStackVolume request = lunVolume(lun("/vol/v/l", null));

        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createCloudStackVolume(request));

        OntapResponse<Lun> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(nullRecords);
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createCloudStackVolume(request));

        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(new OntapResponse<>(new ArrayList<>()));
        CloudRuntimeException empty = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createCloudStackVolume(request));
        assertTrue(empty.getMessage().contains("Failed to create Lun: /vol/v/l"), empty.getMessage());
    }

    @Test
    public void createVolumeRejectsIncompleteLunFromOntap() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenReturn(new OntapResponse<>(List.of(lun("/vol/v/l", null))));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createCloudStackVolume(lunVolume(lun("/vol/v/l", null))));

        assertTrue(thrown.getMessage().contains("incomplete LUN"), thrown.getMessage());
    }

    @Test
    public void createVolumeWrapsFeignAndUnexpectedErrors() {
        CloudStackVolume request = lunVolume(lun("/vol/v/l", null));
        FeignException feign = feignException(500, "ontap down");
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenThrow(feign);
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createCloudStackVolume(request));
        assertSame(feign, feignFailure.getCause());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).createLun(anyString(), anyBoolean(), any(Lun.class));
        CloudRuntimeException generic = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createCloudStackVolume(request));
        assertTrue(generic.getMessage().contains("boom"), generic.getMessage());
    }

    // -------------------------------------------------------- createTemplateCache

    private StoragePoolVO pool() {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(5L);
        when(pool.getName()).thenReturn("flexvol");
        when(pool.getHypervisor()).thenReturn(HypervisorType.KVM);
        return pool;
    }

    private static TemplateInfo template() {
        TemplateInfo template = mock(TemplateInfo.class);
        when(template.getId()).thenReturn(42L);
        return template;
    }

    private static Map<String, String> svmDetails() {
        return Map.of(OntapStorageConstants.SVM_NAME, "svm1");
    }

    @Test
    public void createTemplateCacheRejectsUnknownTemplateSize() {
        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createTemplateCache(pool(), template(), svmDetails(), 0L));

        assertTrue(thrown.getMessage().contains("Unknown virtual size for template [42]"), thrown.getMessage());
        verify(sanFeignClient, never()).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void createTemplateCacheCreatesLunSizedToTheTemplate() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenReturn(new OntapResponse<>(List.of(lun("/vol/flexvol/template_42", "created-uuid"))));

        CloudStackVolume created = unifiedSANStrategy.createTemplateCache(pool(), template(), svmDetails(), 1024L);

        assertEquals("created-uuid", created.getLun().getUuid());
        verify(sanFeignClient).createLun(anyString(), eq(true), argThat(request ->
                request.getSpace().getSize() == 1024L && "svm1".equals(request.getSvm().getName())));
    }

    @Test
    public void createTemplateCacheRemovesLeftoverLunFoundOnOntapAfterCreateFailure() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenThrow(feignException(500, "timeout"));
        when(sanFeignClient.getLunResponse(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(lun("/vol/flexvol/template_42", "leftover-uuid"))));

        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createTemplateCache(pool(), template(), svmDetails(), 1024L));

        verify(sanFeignClient).deleteLun(anyString(), eq("leftover-uuid"), anyMap());
    }

    @Test
    public void createTemplateCacheDoesNotDeleteAnythingWhenNoLeftoverLunExists() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenThrow(feignException(500, "timeout"));
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(new ArrayList<>()));

        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createTemplateCache(pool(), template(), svmDetails(), 1024L));

        verify(sanFeignClient, never()).deleteLun(anyString(), anyString(), anyMap());
    }

    @Test
    public void createTemplateCacheKeepsOriginalErrorWhenCleanupFails() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenThrow(feignException(500, "timeout"));
        when(sanFeignClient.getLunResponse(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(lun("/vol/flexvol/template_42", "leftover-uuid"))));
        doThrow(feignException(500, "cannot delete")).when(sanFeignClient).deleteLun(anyString(), anyString(), anyMap());

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createTemplateCache(pool(), template(), svmDetails(), 1024L));

        assertTrue(thrown.getMessage().contains("Failed to create Lun"), thrown.getMessage());
    }

    @Test
    public void createTemplateCacheWrapsUnexpectedErrorsAndCleansUp() {
        UnifiedSANStrategy spied = spy(unifiedSANStrategy);
        // A result without a LUN makes the success logging fail with a NullPointerException.
        doReturn(new CloudStackVolume()).when(spied).createCloudStackVolume(any(CloudStackVolume.class));
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(null);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> spied.createTemplateCache(pool(), template(), svmDetails(), 1024L));

        assertTrue(thrown.getMessage().contains("Failed to create template cache LUN for template [42]"), thrown.getMessage());
        assertTrue(thrown.getCause() instanceof NullPointerException);
    }

    // -------------------------------------------------------- updateCloudStackVolume

    @Test
    public void updateVolumeRejectsRequestsWithoutLunUuid() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.updateCloudStackVolume(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.updateCloudStackVolume(new CloudStackVolume()));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.updateCloudStackVolume(lunVolume(lun("l", null))));
    }

    @Test
    public void updateVolumeWithoutQosPolicyDoesNothing() {
        CloudStackVolume request = lunVolume(lun("l", LUN_UUID));

        assertSame(request, unifiedSANStrategy.updateCloudStackVolume(request));
        verify(sanFeignClient, never()).updateLun(anyString(), anyString(), any(Lun.class));
    }

    @Test
    public void updateVolumeAppliesQosPolicyAndWrapsFailures() {
        Lun withQos = lun("l", LUN_UUID);
        VolumeQosPolicy qos = new VolumeQosPolicy();
        qos.setName("qos-1");
        withQos.setQosPolicy(qos);
        CloudStackVolume request = lunVolume(withQos);

        assertSame(request, unifiedSANStrategy.updateCloudStackVolume(request));
        verify(sanFeignClient).updateLun(anyString(), eq(LUN_UUID), argThat(update -> update.getQosPolicy() == qos));

        when(sanFeignClient.updateLun(anyString(), anyString(), any(Lun.class))).thenThrow(feignException(500, "ontap down"));
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.updateCloudStackVolume(request));
        assertTrue(feignFailure.getMessage().startsWith("Failed to update LUN"), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).updateLun(anyString(), anyString(), any(Lun.class));
        CloudRuntimeException generic = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.updateCloudStackVolume(request));
        assertTrue(generic.getMessage().contains("boom"), generic.getMessage());
    }

    // -------------------------------------------------------- cloneCloudStackVolume

    @Test
    public void cloneRejectsInvalidRequests() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(new CloudStackVolume()));

        CloudStackVolume noClone = lunVolume(lun("/vol/v/dst", null));
        CloudRuntimeException noCloneEx = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(noClone));
        assertTrue(noCloneEx.getMessage().contains("no clone source"), noCloneEx.getMessage());

        Lun emptyClone = lun("/vol/v/dst", null);
        emptyClone.setClone(new Lun.Clone());
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(lunVolume(emptyClone)));
        verify(sanFeignClient, never()).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void cloneFailsWhenOntapReturnsNothingOrAnIncompleteLun() {
        CloudStackVolume request = lunVolume(cloneRequestLun());

        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(null);
        CloudRuntimeException missing = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(request));
        assertTrue(missing.getMessage().contains("Failed to clone Lun: /vol/v/dst"), missing.getMessage());

        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(new OntapResponse<>(new ArrayList<>()));
        CloudRuntimeException empty = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(request));
        assertTrue(empty.getMessage().contains("Failed to clone Lun: /vol/v/dst"), empty.getMessage());

        // A create record with a uuid and no name is not rejected immediately. Clone then reads that uuid back.
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenReturn(new OntapResponse<>(List.of(lun(null, "uuid"))));
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenThrow(feignException(500, "get failed"));
        CloudRuntimeException unread = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(request));
        assertTrue(unread.getMessage().contains("Failed to fetch cloned Lun"), unread.getMessage());
    }

    @Test
    public void cloneWrapsFeignAndUnexpectedErrors() {
        CloudStackVolume request = lunVolume(cloneRequestLun());
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenThrow(feignException(500, "ontap down"));
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(request));
        assertTrue(feignFailure.getMessage().startsWith("Failed to clone Lun: "), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).createLun(anyString(), anyBoolean(), any(Lun.class));
        CloudRuntimeException generic = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.cloneCloudStackVolume(request));
        assertTrue(generic.getMessage().contains("boom"), generic.getMessage());
    }

    // -------------------------------------------------------- copyCloudStackVolume

    private CloudStackVolume copy(int timeoutSeconds, String srcLunName, String destVolumeName) {
        VolumeInfo src = mock(VolumeInfo.class);
        VolumeInfo dest = mock(VolumeInfo.class);
        StoragePoolVO destPool = mock(StoragePoolVO.class);
        when(src.getId()).thenReturn(11L);
        when(dest.getName()).thenReturn(destVolumeName);
        when(destPool.getName()).thenReturn("dest");
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(11L, OntapStorageConstants.LUN_DOT_UUID, "source-uuid", false));
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_NAME))
                .thenReturn(srcLunName == null ? null : new VolumeDetailVO(11L, OntapStorageConstants.LUN_DOT_NAME, srcLunName, false));
        return unifiedSANStrategy.copyCloudStackVolume(src, dest, destPool, Map.of(OntapStorageConstants.SVM_NAME, "svm1"), timeoutSeconds);
    }

    private static Lun createdCopy() {
        return lun("/vol/dest/lun", "copied-uuid");
    }

    @Test
    public void copyWithoutSourceLunNameStillCopiesBySourceUuid() {
        Lun created = createdCopy();
        withCopyProgress(created, 100, null);
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(new OntapResponse<>(List.of(created)));
        when(sanFeignClient.getLunByUUID(anyString(), eq("copied-uuid"), anyMap())).thenReturn(created);

        CloudStackVolume result = copy(10, null, "lun");

        assertSame(created, result.getLun());
        verify(sanFeignClient).createLun(anyString(), eq(true), argThat(request ->
                "source-uuid".equals(request.getCopy().getSource().getUuid()) && request.getCopy().getSource().getName() == null));
    }

    @Test
    public void copyRejectsInvalidDestinationLunName() {
        InvalidParameterValueException thrown = assertThrows(InvalidParameterValueException.class, () -> copy(10, null, "1 bad name!"));

        assertTrue(thrown.getMessage().contains("Invalid dataObject name"), thrown.getMessage());
        verify(sanFeignClient, never()).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void copyFailsWhenSourceVolumeHasNoLunUuid() {
        VolumeInfo src = mock(VolumeInfo.class);
        when(src.getId()).thenReturn(11L);
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(11L, OntapStorageConstants.LUN_DOT_UUID, "  ", false));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.copyCloudStackVolume(
                src, mock(VolumeInfo.class), mock(StoragePoolVO.class), svmDetails(), 5));

        assertTrue(thrown.getMessage().contains("has no ONTAP LUN UUID"), thrown.getMessage());
    }

    @Test
    public void copyFailsWhenOntapReturnsNoRecords() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(null);
        CloudRuntimeException noResponse = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));
        assertTrue(noResponse.getMessage().contains("Failed to copy Lun"), noResponse.getMessage());

        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(new OntapResponse<>(new ArrayList<>()));
        assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));
    }

    @Test
    public void copyFailsFastOnNonRetryableCreateError() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenThrow(feignException(400, "bad request"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));

        assertTrue(thrown.getMessage().startsWith("Failed to copy Lun: "), thrown.getMessage());
        verify(sanFeignClient).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void copyGivesUpWhenSourceStaysBusyPastTheDeadline() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenThrow(feignException(500, "{\"code\":\"5375060\"}"));

        assertThrows(CloudRuntimeException.class, () -> copy(0, "src", "lun"));

        verify(sanFeignClient).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void copyRetriesWhenSourceIsBusyAndThenSucceeds() {
        Lun created = withCopyProgress(createdCopy(), 100, null);
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenThrow(feignException(500, "{\"code\":\"5375060\"}"))
                .thenReturn(new OntapResponse<>(List.of(created)));
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(created);

        CloudStackVolume result = copy(30, "src", "lun");

        assertSame(created, result.getLun());
        verify(sanFeignClient, org.mockito.Mockito.times(2)).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void copyResumesExistingCopyWhenOntapReportsItAlreadyStarted() {
        Lun existing = withCopyProgress(createdCopy(), 100, null);
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenThrow(feignException(500, "{\"code\":\"5375060\"}"))
                .thenThrow(feignException(409, "{\"code\":\"7018919\"}"));
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(existing)));
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(existing);

        CloudStackVolume result = copy(30, "src", "lun");

        assertSame(existing, result.getLun());
    }

    @Test
    public void copyKeepsRetryingWhenAlreadyStartedCopyCannotBeFound() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenThrow(feignException(500, "{\"code\":\"5375060\"}"))
                .thenThrow(feignException(409, "{\"code\":\"7018919\"}"));
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(null);

        // A 1s deadline lets exactly one retry sleep happen before giving up on the conflict.
        assertThrows(CloudRuntimeException.class, () -> copy(1, "src", "lun"));

        verify(sanFeignClient, org.mockito.Mockito.times(2)).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void copyStopsWhenInterruptedWhileWaitingToRetryCreate() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenThrow(feignException(500, "{\"code\":\"5375060\"}"));
        Thread.currentThread().interrupt();
        try {
            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(30, "src", "lun"));

            assertTrue(thrown.getMessage().contains("Interrupted while retrying ONTAP LUN copy"), thrown.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private void createReturns(Lun created) {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class))).thenReturn(new OntapResponse<>(List.of(created)));
    }

    @Test
    public void copyFailsAndRemovesPartialLunWhenOntapReportsCopyFailure() {
        for (String state : new String[] {"failed", "FAILURE", "Error"}) {
            Lun created = withCopyProgress(createdCopy(), 40, state);
            createReturns(created);
            when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(created);

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));

            assertTrue(thrown.getMessage().contains("ONTAP LUN copy failed"), thrown.getMessage());
        }
        verify(sanFeignClient, org.mockito.Mockito.times(3)).deleteLun(anyString(), eq("copied-uuid"), anyMap());
    }

    @Test
    public void copyTimesOutWhileProgressIsIncomplete() {
        Lun created = withCopyProgress(createdCopy(), 50, "running");
        createReturns(created);
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(created);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(0, "src", "lun"));

        assertTrue(thrown.getMessage().contains("Timed out waiting for ONTAP LUN copy"), thrown.getMessage());
        verify(sanFeignClient).deleteLun(anyString(), eq("copied-uuid"), anyMap());
    }

    @Test
    public void copyKeepsGoingWhenCleanupOfPartialLunFails() {
        Lun created = withCopyProgress(createdCopy(), 50, "running");
        createReturns(created);
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(created);
        doThrow(feignException(500, "cannot delete")).when(sanFeignClient).deleteLun(anyString(), anyString(), anyMap());

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(0, "src", "lun"));

        assertTrue(thrown.getMessage().contains("Timed out waiting"), thrown.getMessage());
    }

    @Test
    public void copyStopsWhenInterruptedWhileWaitingForProgress() {
        Lun created = withCopyProgress(createdCopy(), 50, "running");
        createReturns(created);
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(created);
        Thread.currentThread().interrupt();
        try {
            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(30, "src", "lun"));

            assertTrue(thrown.getMessage().contains("Interrupted while waiting for ONTAP LUN copy"), thrown.getMessage());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void copyFailsWhenCopyStatusCannotBeRead() {
        createReturns(createdCopy());

        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(null);
        CloudRuntimeException nullStatus = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));
        assertTrue(nullStatus.getMessage().contains("Unable to retrieve ONTAP LUN copy status"), nullStatus.getMessage());

        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenThrow(feignException(500, "ontap down"));
        CloudRuntimeException serverError = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));
        assertTrue(serverError.getMessage().startsWith("Failed to copy Lun: "), serverError.getMessage());
    }

    @Test
    public void copyFailsWhenOntapNeverExposesCopyDetailsWithinGracePeriod() {
        unifiedSANStrategy.lunCopyStatusGraceMs = 0L;
        createReturns(createdCopy());

        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenThrow(feignException(404, "not yet"));
        CloudRuntimeException notFound = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));
        assertTrue(notFound.getMessage().contains("did not return copy details"), notFound.getMessage());
        assertNotNull(notFound.getCause());

        doReturn(createdCopy()).when(sanFeignClient).getLunByUUID(anyString(), anyString(), anyMap());
        CloudRuntimeException noCopyObject = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));
        assertTrue(noCopyObject.getMessage().contains("did not return copy details"), noCopyObject.getMessage());
        assertNull(noCopyObject.getCause());
    }

    // -------------------------------------------------------- resizeCloudStackVolume (extra)

    @Test
    public void resizeResolvesLunUuidFromVolumeDetailsAndKeepsExistingQos() {
        VolumeObject volumeInfo = mock(VolumeObject.class);
        when(volumeInfo.getId()).thenReturn(77L);
        when(volumeDetailsDao.findDetail(77L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(77L, OntapStorageConstants.LUN_DOT_UUID, "resolved-uuid", false));
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeInfo);

        unifiedSANStrategy.resizeCloudStackVolume(request, 4096L);

        assertEquals("resolved-uuid", request.getLun().getUuid());
        verify(sanFeignClient).updateLun(anyString(), eq("resolved-uuid"), argThat(patch -> patch.getSpace().getSize() == 4096L));
    }

    @Test
    public void resizeFailsWhenLunUuidCannotBeResolved() {
        VolumeObject volumeInfo = mock(VolumeObject.class);
        when(volumeInfo.getId()).thenReturn(77L);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeInfo);

        when(volumeDetailsDao.findDetail(77L, OntapStorageConstants.LUN_DOT_UUID)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 4096L));

        when(volumeDetailsDao.findDetail(77L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(77L, OntapStorageConstants.LUN_DOT_UUID, "", false));
        CloudRuntimeException blank = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 4096L));
        assertTrue(blank.getMessage().contains("LUN UUID not found"), blank.getMessage());
    }

    @Test
    public void resizeReportsMissingLunAndOtherFailuresDistinctly() {
        VolumeObject volumeInfo = mock(VolumeObject.class);
        CloudStackVolume request = lunVolume(lun("l", LUN_UUID));
        request.setVolumeInfo(volumeInfo);

        when(sanFeignClient.updateLun(anyString(), anyString(), any(Lun.class))).thenThrow(feignException(404, "gone"));
        CloudRuntimeException gone = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 4096L));
        assertTrue(gone.getMessage().contains("no longer exists on ONTAP"), gone.getMessage());

        doThrow(feignException(500, "ontap down")).when(sanFeignClient).updateLun(anyString(), anyString(), any(Lun.class));
        CloudRuntimeException failed = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 4096L));
        assertTrue(failed.getMessage().contains("Failed to resize LUN"), failed.getMessage());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).updateLun(anyString(), anyString(), any(Lun.class));
        CloudRuntimeException unexpected = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.resizeCloudStackVolume(request, 4096L));
        assertTrue(unexpected.getMessage().contains("Unexpected error while resizing LUN"), unexpected.getMessage());
    }

    // -------------------------------------------------------- getCloudStackVolume / serial number

    @Test
    public void getVolumeRejectsInvalidLookups() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getCloudStackVolume(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getCloudStackVolume(Map.of()));
        for (Map<String, String> values : List.of(
                Map.of(OntapStorageConstants.NAME, "lun"),
                Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1"),
                Map.of(OntapStorageConstants.SVM_DOT_NAME, "", OntapStorageConstants.NAME, "lun"),
                Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.NAME, ""))) {
            assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getCloudStackVolume(values));
        }
    }

    @Test
    public void getVolumeReturnsNullWhenLunIsNotFound() {
        Map<String, String> lookup = Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.NAME, "lun");

        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(null);
        assertNull(unifiedSANStrategy.getCloudStackVolume(lookup));

        OntapResponse<Lun> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(nullRecords);
        assertNull(unifiedSANStrategy.getCloudStackVolume(lookup));

        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(new ArrayList<>()));
        assertNull(unifiedSANStrategy.getCloudStackVolume(lookup));

        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenThrow(feignException(404, "gone"));
        assertNull(unifiedSANStrategy.getCloudStackVolume(lookup));
    }

    @Test
    public void getVolumeWrapsOtherErrors() {
        Map<String, String> lookup = Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.NAME, "lun");
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenThrow(feignException(500, "ontap down"));
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getCloudStackVolume(lookup));
        assertTrue(feignFailure.getMessage().startsWith("Failed to fetch Lun poolDetails"), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).getLunResponse(anyString(), anyMap());
        CloudRuntimeException generic = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getCloudStackVolume(lookup));
        assertTrue(generic.getMessage().contains("boom"), generic.getMessage());
    }

    @Test
    public void getLunSerialNumberReadsSerialFromOntap() throws Exception {
        Lun lun = new ObjectMapper().readValue("{\"name\":\"lun\",\"serial_number\":\"serial-1\"}", Lun.class);
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(lun)));

        assertEquals("serial-1", unifiedSANStrategy.getLunSerialNumber("svm1", "lun"));
    }

    @Test
    public void getLunSerialNumberRejectsInvalidNames() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLunSerialNumber(null, "lun"));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLunSerialNumber("", "lun"));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLunSerialNumber("svm1", null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLunSerialNumber("svm1", ""));
    }

    @Test
    public void getLunSerialNumberReturnsNullWhenLunIsMissing() {
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(null);
        assertNull(unifiedSANStrategy.getLunSerialNumber("svm1", "lun"));

        OntapResponse<Lun> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(nullRecords);
        assertNull(unifiedSANStrategy.getLunSerialNumber("svm1", "lun"));

        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(new ArrayList<>()));
        assertNull(unifiedSANStrategy.getLunSerialNumber("svm1", "lun"));

        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenThrow(feignException(404, "gone"));
        assertNull(unifiedSANStrategy.getLunSerialNumber("svm1", "lun"));
    }

    @Test
    public void getLunSerialNumberWrapsOtherErrors() {
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenThrow(feignException(500, "ontap down"));
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLunSerialNumber("svm1", "lun"));
        assertTrue(feignFailure.getMessage().startsWith("Failed to fetch LUN serial number"), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).getLunResponse(anyString(), anyMap());
        CloudRuntimeException generic = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLunSerialNumber("svm1", "lun"));
        assertTrue(generic.getMessage().contains("boom"), generic.getMessage());
    }

    // -------------------------------------------------------- createAccessGroup

    @Test
    public void createAccessGroupRejectsInvalidRequests() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createAccessGroup(null));

        AccessGroup noPool = accessGroup(host("h1", "iqn.1"));
        noPool.setStoragePoolId(null);
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createAccessGroup(noPool));

        AccessGroup nullHosts = accessGroup();
        nullHosts.setHostsToConnect(null);
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createAccessGroup(nullHosts));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createAccessGroup(accessGroup()));
        verify(sanFeignClient, never()).createIgroup(anyString(), anyBoolean(), any(Igroup.class));
    }

    @Test
    public void createAccessGroupRejectsHostsWithoutIscsiInitiator() {
        poolDetails("svm1", "ISCSI");
        for (String storageUrl : new String[] {null, "", "   ", "nfs-host"}) {
            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                    () -> unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", storageUrl))));
            assertTrue(thrown.getMessage().contains("support the protocol"), thrown.getMessage());
        }
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.createAccessGroup(accessGroup((HostVO) null)));
        verify(sanFeignClient, never()).createIgroup(anyString(), anyBoolean(), any(Igroup.class));
    }

    @Test
    public void createAccessGroupWithoutSvmNameStillCreatesIgroup() throws Exception {
        poolDetails(null, "ISCSI");
        Igroup created = igroup("ig-1", "cs_h1_null");
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class))).thenReturn(new OntapResponse<>(List.of(created)));

        AccessGroup result = unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1994-05.com.example:host1")));

        assertSame(created, result.getIgroup());
        verify(sanFeignClient).createIgroup(anyString(), eq(true), argThat(request -> request.getSvm() == null));
    }

    @Test
    public void createAccessGroupFailsWhenOntapReturnsNoIgroup() {
        poolDetails("svm1", "ISCSI");
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class))).thenReturn(new OntapResponse<>(new ArrayList<>()));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1"))));

        assertTrue(thrown.getMessage().contains("Failed to create Igroup"), thrown.getMessage());
    }

    @Test
    public void createAccessGroupReusesIgroupThatAlreadyExists() throws Exception {
        poolDetails("svm1", "ISCSI");
        Igroup existing = igroup("ig-1", "existing");
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class))).thenThrow(feignException(409, "duplicate"));
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(existing)));

        AccessGroup result = unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1")));

        assertSame(existing, result.getIgroup());
    }

    @Test
    public void createAccessGroupReportsConflictWhenExistingIgroupCannotBeFetched() {
        poolDetails("svm1", "ISCSI");
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class))).thenThrow(feignException(409, "duplicate igroup"));

        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(null);
        CloudRuntimeException withBody = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1"))));
        assertTrue(withBody.getMessage().contains("already exists but was not returned"), withBody.getMessage());
        assertTrue(withBody.getMessage().contains("duplicate igroup"), withBody.getMessage());

        doThrow(feignException(409, "")).when(sanFeignClient).createIgroup(anyString(), anyBoolean(), any(Igroup.class));
        OntapResponse<Igroup> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(nullRecords);
        CloudRuntimeException blankBody = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1"))));
        assertTrue(blankBody.getMessage().contains("already exists but was not returned"), blankBody.getMessage());
    }

    @Test
    public void createAccessGroupWrapsOtherOntapErrors() {
        poolDetails("svm1", "ISCSI");
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class))).thenThrow(feignException(500, "ontap down"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1"))));

        assertTrue(thrown.getMessage().startsWith("Failed to create Igroup: "), thrown.getMessage());
    }

    // -------------------------------------------------------- deleteAccessGroup

    @Test
    public void deleteAccessGroupRejectsInvalidRequests() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.deleteAccessGroup(null));
        AccessGroup noPool = accessGroup(host("h1", "iqn.1"));
        noPool.setStoragePoolId(null);
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.deleteAccessGroup(noPool));
    }

    @Test
    public void deleteAccessGroupWithoutHostsDoesNothing() {
        AccessGroup group = accessGroup();
        group.setHostsToConnect(null);

        unifiedSANStrategy.deleteAccessGroup(group);
        unifiedSANStrategy.deleteAccessGroup(accessGroup());

        verify(sanFeignClient, never()).getIgroupResponse(anyString(), anyMap());
    }

    @Test
    public void deleteAccessGroupDeletesIgroupByUuid() throws Exception {
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(igroup("ig-1", "g"))));

        unifiedSANStrategy.deleteAccessGroup(accessGroup(host("h1", "iqn.1")));

        verify(sanFeignClient).deleteIgroup(anyString(), eq("ig-1"));
    }

    @Test
    public void deleteAccessGroupIsNoOpWhenIgroupIsAlreadyGone() {
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(null);
        unifiedSANStrategy.deleteAccessGroup(accessGroup(host("h1", "iqn.1")));

        OntapResponse<Igroup> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(nullRecords);
        unifiedSANStrategy.deleteAccessGroup(accessGroup(host("h1", "iqn.1")));

        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(new ArrayList<>()));
        unifiedSANStrategy.deleteAccessGroup(accessGroup(host("h1", "iqn.1")));

        verify(sanFeignClient, never()).deleteIgroup(anyString(), anyString());
    }

    @Test
    public void deleteAccessGroupToleratesIgroupDisappearingDuringDelete() throws Exception {
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(igroup("ig-1", "g"))));
        doThrow(feignException(404, "gone")).when(sanFeignClient).deleteIgroup(anyString(), anyString());

        unifiedSANStrategy.deleteAccessGroup(accessGroup(host("h1", "iqn.1")));

        verify(sanFeignClient).deleteIgroup(anyString(), eq("ig-1"));
    }

    @Test
    public void deleteAccessGroupFailsOnOtherErrorsAndMissingUuid() throws Exception {
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(igroup("ig-1", "g"))));
        doThrow(feignException(500, "ontap down")).when(sanFeignClient).deleteIgroup(anyString(), anyString());
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.deleteAccessGroup(accessGroup(host("h1", "iqn.1"))));
        assertTrue(feignFailure.getMessage().startsWith("Failed to delete iGroup"), feignFailure.getMessage());

        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(igroup("", "g"))));
        CloudRuntimeException noUuid = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.deleteAccessGroup(accessGroup(host("h1", "iqn.1"))));
        assertTrue(noUuid.getMessage().contains("iGroup UUID is null or empty"), noUuid.getMessage());
    }

    // -------------------------------------------------------- unsupported / getAccessGroup

    @Test
    public void updateAccessGroupIsNotSupportedForIscsi() {
        assertNull(unifiedSANStrategy.updateAccessGroup(new AccessGroup()));
    }

    @Test
    public void getAccessGroupRejectsInvalidLookups() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getAccessGroup(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getAccessGroup(Map.of()));
        for (Map<String, String> values : List.of(
                Map.of(OntapStorageConstants.NAME, "g"),
                Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1"),
                Map.of(OntapStorageConstants.SVM_DOT_NAME, "", OntapStorageConstants.NAME, "g"),
                Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.NAME, ""))) {
            assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getAccessGroup(values));
        }
    }

    @Test
    public void getAccessGroupReturnsNullWhenIgroupIsNotFound() {
        Map<String, String> lookup = Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.NAME, "g");

        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(null);
        assertNull(unifiedSANStrategy.getAccessGroup(lookup));

        OntapResponse<Igroup> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(nullRecords);
        assertNull(unifiedSANStrategy.getAccessGroup(lookup));

        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(new ArrayList<>()));
        assertNull(unifiedSANStrategy.getAccessGroup(lookup));

        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenThrow(feignException(404, "gone"));
        assertNull(unifiedSANStrategy.getAccessGroup(lookup));
    }

    @Test
    public void getAccessGroupReturnsIgroupAndWrapsOtherErrors() throws Exception {
        Map<String, String> lookup = Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.NAME, "g");
        Igroup found = igroup("ig-1", "g");
        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(found)));
        assertSame(found, unifiedSANStrategy.getAccessGroup(lookup).getIgroup());

        when(sanFeignClient.getIgroupResponse(anyString(), anyMap())).thenThrow(feignException(500, "ontap down"));
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getAccessGroup(lookup));
        assertTrue(feignFailure.getMessage().startsWith("Failed to fetch Igroup poolDetails"), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).getIgroupResponse(anyString(), anyMap());
        CloudRuntimeException generic = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getAccessGroup(lookup));
        assertTrue(generic.getMessage().contains("boom"), generic.getMessage());
    }

    // -------------------------------------------------------- LUN mapping

    private static Map<String, String> mapValues() {
        return Map.of(OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.LUN_DOT_NAME, "lun",
                OntapStorageConstants.IGROUP_DOT_NAME, "g");
    }

    @Test
    public void enableLogicalAccessRejectsInvalidValues() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.enableLogicalAccess(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.enableLogicalAccess(Map.of()));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.enableLogicalAccess(Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "", OntapStorageConstants.LUN_DOT_NAME, "lun", OntapStorageConstants.IGROUP_DOT_NAME, "g")));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.enableLogicalAccess(Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.LUN_DOT_NAME, "", OntapStorageConstants.IGROUP_DOT_NAME, "g")));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.enableLogicalAccess(Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.LUN_DOT_NAME, "lun", OntapStorageConstants.IGROUP_DOT_NAME, "")));
    }

    @Test
    public void enableLogicalAccessAcceptsAlreadyMappedLunAndFailsOnOtherErrors() {
        assertEquals("", unifiedSANStrategy.enableLogicalAccess(mapValues()));

        when(sanFeignClient.createLunMap(anyString(), anyBoolean(), any(LunMap.class)))
                .thenThrow(new IllegalStateException("LUN already mapped to this group"));
        assertEquals("", unifiedSANStrategy.enableLogicalAccess(mapValues()));

        doThrow(new IllegalStateException("ontap down")).when(sanFeignClient).createLunMap(anyString(), anyBoolean(), any(LunMap.class));
        CloudRuntimeException failed = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.enableLogicalAccess(mapValues()));
        assertTrue(failed.getMessage().contains("ontap down"), failed.getMessage());

        doThrow(new IllegalStateException((String) null)).when(sanFeignClient).createLunMap(anyString(), anyBoolean(), any(LunMap.class));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.enableLogicalAccess(mapValues()));
    }

    @Test
    public void disableLogicalAccessValidatesInputAndHandlesErrors() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.disableLogicalAccess(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.disableLogicalAccess(Map.of()));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.disableLogicalAccess(Map.of(
                OntapStorageConstants.LUN_DOT_UUID, "", OntapStorageConstants.IGROUP_DOT_UUID, "ig")));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.disableLogicalAccess(Map.of(
                OntapStorageConstants.LUN_DOT_UUID, "lun", OntapStorageConstants.IGROUP_DOT_UUID, "")));

        Map<String, String> values = Map.of(OntapStorageConstants.LUN_DOT_UUID, "lun-1", OntapStorageConstants.IGROUP_DOT_UUID, "ig-1");
        unifiedSANStrategy.disableLogicalAccess(values);
        verify(sanFeignClient).deleteLunMap(anyString(), eq("lun-1"), eq("ig-1"));

        doThrow(feignException(404, "gone")).when(sanFeignClient).deleteLunMap(anyString(), anyString(), anyString());
        unifiedSANStrategy.disableLogicalAccess(values);

        doThrow(feignException(500, "ontap down")).when(sanFeignClient).deleteLunMap(anyString(), anyString(), anyString());
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.disableLogicalAccess(values));
        assertTrue(feignFailure.getMessage().startsWith("Failed to delete LunMap"), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(sanFeignClient).deleteLunMap(anyString(), anyString(), anyString());
        CloudRuntimeException generic = assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.disableLogicalAccess(values));
        assertTrue(generic.getMessage().contains("boom"), generic.getMessage());
    }

    @Test
    public void getLogicalAccessValidatesInput() {
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLogicalAccess(null));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLogicalAccess(Map.of()));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLogicalAccess(Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "", OntapStorageConstants.LUN_DOT_NAME, "lun", OntapStorageConstants.IGROUP_DOT_NAME, "g")));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLogicalAccess(Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.LUN_DOT_NAME, "", OntapStorageConstants.IGROUP_DOT_NAME, "g")));
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.getLogicalAccess(Map.of(
                OntapStorageConstants.SVM_DOT_NAME, "svm1", OntapStorageConstants.LUN_DOT_NAME, "lun", OntapStorageConstants.IGROUP_DOT_NAME, "")));
    }

    @Test
    public void getLogicalAccessReturnsLunNumberOrNull() {
        LunMap withNumber = new LunMap();
        withNumber.setLogicalUnitNumber(3);
        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(withNumber)));
        assertEquals("3", unifiedSANStrategy.getLogicalAccess(mapValues()));

        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(new LunMap())));
        assertNull(unifiedSANStrategy.getLogicalAccess(mapValues()));

        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenReturn(null);
        assertNull(unifiedSANStrategy.getLogicalAccess(mapValues()));

        OntapResponse<LunMap> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenReturn(nullRecords);
        assertNull(unifiedSANStrategy.getLogicalAccess(mapValues()));

        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(new ArrayList<>()));
        assertNull(unifiedSANStrategy.getLogicalAccess(mapValues()));

        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenThrow(feignException(500, "ontap down"));
        assertNull(unifiedSANStrategy.getLogicalAccess(mapValues()));
    }

    @Test
    public void ensureLunMappedReusesExistingMapOrCreatesOne() {
        LunMap existing = new LunMap();
        existing.setLogicalUnitNumber(5);
        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(existing)));
        assertEquals("5", unifiedSANStrategy.ensureLunMapped("svm1", "lun", "g"));
        verify(sanFeignClient, never()).createLunMap(anyString(), anyBoolean(), any(LunMap.class));

        when(sanFeignClient.getLunMapResponse(anyString(), anyMap())).thenReturn(null);
        assertEquals("", unifiedSANStrategy.ensureLunMapped("svm1", "lun", "g"));
        verify(sanFeignClient).createLunMap(anyString(), eq(false), any(LunMap.class));
    }

    @Test
    public void ensureLunMappedFailsWhenMappingReturnsNothing() {
        UnifiedSANStrategy spied = spy(unifiedSANStrategy);
        doReturn(null).when(spied).getLogicalAccess(anyMap());
        doReturn(null).when(spied).enableLogicalAccess(anyMap());

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> spied.ensureLunMapped("svm1", "lun", "g"));

        assertTrue(thrown.getMessage().contains("Failed to map LUN [lun] to iGroup [g]"), thrown.getMessage());
    }

    // -------------------------------------------------------- revertSnapshotForCloudStackVolume

    @Test
    public void revertSnapshotCallsCliRestoreWithNormalisedLunPath() {
        JobResponse expected = new JobResponse();
        when(snapshotFeignClient.restoreFileFromSnapshotCli(anyString(), any())).thenReturn(expected);

        assertSame(expected, unifiedSANStrategy.revertSnapshotForCloudStackVolume("snap", "flex-uuid", "snap-uuid", "vol/lun1", LUN_UUID, "flexvol"));
        verify(snapshotFeignClient).restoreFileFromSnapshotCli(anyString(), argThat(request ->
                "/vol/lun1".equals(request.getPath()) && "svm1".equals(request.getVserver())
                        && "flexvol".equals(request.getVolume()) && "snap".equals(request.getSnapshot())));

        unifiedSANStrategy.revertSnapshotForCloudStackVolume("snap", "flex-uuid", "snap-uuid", "/vol/lun2", LUN_UUID, "flexvol");
        verify(snapshotFeignClient).restoreFileFromSnapshotCli(anyString(), argThat(request -> "/vol/lun2".equals(request.getPath())));
    }

    @Test
    public void revertSnapshotRejectsMissingArguments() {
        for (String[] args : new String[][] {
                {null, "flexvol", "lun"}, {"", "flexvol", "lun"},
                {"snap", null, "lun"}, {"snap", "", "lun"},
                {"snap", "flexvol", null}, {"snap", "flexvol", ""}}) {
            assertThrows(CloudRuntimeException.class, () ->
                    unifiedSANStrategy.revertSnapshotForCloudStackVolume(args[0], "flex-uuid", "snap-uuid", args[2], LUN_UUID, args[1]));
        }
        verify(snapshotFeignClient, never()).restoreFileFromSnapshotCli(anyString(), any());
    }

    @Test
    public void deleteVolumeIgnoresMissingLunButFailsOnOtherErrors() {
        CloudStackVolume request = lunVolume(lun("/vol/v/l", LUN_UUID));
        doThrow(feignException(404, "gone")).when(sanFeignClient).deleteLun(anyString(), anyString(), anyMap());
        unifiedSANStrategy.deleteCloudStackVolume(request);

        doThrow(feignException(500, "ontap down")).when(sanFeignClient).deleteLun(anyString(), anyString(), anyMap());
        assertThrows(CloudRuntimeException.class, () -> unifiedSANStrategy.deleteCloudStackVolume(request));
        assertFalse(request.getLun().getUuid().isEmpty());
    }

    // ===================================================================================
    // Assertions on the exact requests the SAN strategy sends to ONTAP
    // ===================================================================================

    private static <T> org.mockito.ArgumentCaptor<T> captor(Class<T> type) {
        return org.mockito.ArgumentCaptor.forClass(type);
    }

    @Test
    public void createAccessGroupSendsIgroupWithSvmOsTypeInitiatorProtocolAndDeleteOnUnmap() throws Exception {
        poolDetails("svm1", "ISCSI");
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class)))
                .thenReturn(new OntapResponse<>(List.of(igroup("ig-1", "cs_h1_svm1"))));

        unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1994-05.com.example:host1")));

        org.mockito.ArgumentCaptor<Igroup> request = captor(Igroup.class);
        verify(sanFeignClient).createIgroup(anyString(), eq(true), request.capture());
        Igroup sent = request.getValue();
        assertEquals("svm1", sent.getSvm().getName());
        assertEquals(Igroup.OsTypeEnum.Linux, sent.getOsType());
        assertEquals("cs_h1_svm1", sent.getName());
        assertEquals(1, sent.getInitiators().size());
        assertEquals("iqn.1994-05.com.example:host1", sent.getInitiators().get(0).getName());
        assertEquals(Boolean.TRUE, sent.isDeleteOnUnmap());
        assertEquals(Igroup.ProtocolEnum.iscsi, sent.getProtocol());
    }

    @Test
    public void createAccessGroupOmitsSvmWhenPoolHasEmptySvmName() throws Exception {
        poolDetails("", "ISCSI");
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class)))
                .thenReturn(new OntapResponse<>(List.of(igroup("ig-1", "cs_h1_"))));

        unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1")));

        verify(sanFeignClient).createIgroup(anyString(), eq(true), argThat(request -> request.getSvm() == null));
    }

    @Test
    public void createAccessGroupFailsWhenOntapReturnsNullRecords() {
        poolDetails("svm1", "ISCSI");
        OntapResponse<Igroup> nullRecords = new OntapResponse<>();
        nullRecords.setRecords(null);
        when(sanFeignClient.createIgroup(anyString(), anyBoolean(), any(Igroup.class))).thenReturn(nullRecords);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> unifiedSANStrategy.createAccessGroup(accessGroup(host("h1", "iqn.1"))));

        assertTrue(thrown.getMessage().contains("Failed to create Igroup"), thrown.getMessage());
    }

    @Test
    public void enableLogicalAccessSendsLunMapWithSvmLunAndIgroupNames() {
        unifiedSANStrategy.enableLogicalAccess(mapValues());

        org.mockito.ArgumentCaptor<LunMap> request = captor(LunMap.class);
        verify(sanFeignClient).createLunMap(anyString(), eq(false), request.capture());
        Map<String, String> expected = mapValues();
        assertEquals(expected.get(OntapStorageConstants.SVM_DOT_NAME), request.getValue().getSvm().getName());
        assertEquals(expected.get(OntapStorageConstants.LUN_DOT_NAME), request.getValue().getLun().getName());
        assertEquals(expected.get(OntapStorageConstants.IGROUP_DOT_NAME), request.getValue().getIgroup().getName());
    }

    @Test
    public void createTemplateCacheSendsLunNamedAfterPoolAndTemplateWithLinuxOsType() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenReturn(new OntapResponse<>(List.of(lun("/vol/flexvol/template_42", "created-uuid"))));

        unifiedSANStrategy.createTemplateCache(pool(), template(), svmDetails(), 1024L);

        org.mockito.ArgumentCaptor<Lun> request = captor(Lun.class);
        verify(sanFeignClient).createLun(anyString(), eq(true), request.capture());
        assertEquals("/vol/flexvol/" + OntapStorageConstants.TEMPLATE_LUN_PREFIX + "42", request.getValue().getName());
        assertEquals(Lun.OsTypeEnum.LINUX, request.getValue().getOsType());
        assertEquals(1024L, request.getValue().getSpace().getSize());
    }

    @Test
    public void deleteVolumePollsTheDeleteJobNamingTheLun() {
        UnifiedSANStrategy spied = spy(unifiedSANStrategy);
        JobResponse job = new JobResponse();
        when(sanFeignClient.deleteLun(anyString(), eq("lun-1"), anyMap())).thenReturn(job);

        spied.deleteCloudStackVolume(lunVolume(lun("/vol/v/l", "lun-1")));

        verify(spied).pollJobIfPresent(job, "delete Lun [/vol/v/l]");
        org.mockito.ArgumentCaptor<Map<String, Object>> query = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sanFeignClient).deleteLun(anyString(), eq("lun-1"), query.capture());
        assertEquals("true", query.getValue().get("allow_delete_while_mapped"));
    }

    @Test
    public void updateVolumePollsTheUpdateJobAndSendsOnlyTheQosPolicy() {
        UnifiedSANStrategy spied = spy(unifiedSANStrategy);
        JobResponse job = new JobResponse();
        when(sanFeignClient.updateLun(anyString(), eq("lun-1"), any(Lun.class))).thenReturn(job);
        Lun target = lun("/vol/v/l", "lun-1");
        org.apache.cloudstack.storage.feign.model.VolumeQosPolicy policy = new org.apache.cloudstack.storage.feign.model.VolumeQosPolicy();
        policy.setName("qos-1");
        target.setQosPolicy(policy);

        spied.updateCloudStackVolume(lunVolume(target));

        verify(spied).pollJobIfPresent(job, "update LUN [lun-1]");
        verify(sanFeignClient).updateLun(anyString(), eq("lun-1"), argThat(patch ->
                patch.getQosPolicy() == policy && patch.getName() == null && patch.getSpace() == null));
    }

    @Test
    public void resizePollsTheResizeJobAndSendsTheNewSize() {
        UnifiedSANStrategy spied = spy(unifiedSANStrategy);
        JobResponse job = new JobResponse();
        when(sanFeignClient.updateLun(anyString(), eq("lun-1"), any(Lun.class))).thenReturn(job);
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getId()).thenReturn(10L);
        CloudStackVolume volume = lunVolume(lun("/vol/v/l", "lun-1"));
        volume.setVolumeInfo(volumeInfo);

        spied.resizeCloudStackVolume(volume, 4096L);

        verify(spied).pollJobIfPresent(job, "resize Lun [lun-1]");
        verify(sanFeignClient).updateLun(anyString(), eq("lun-1"), argThat(patch ->
                patch.getSpace().getSize() == 4096L && patch.getQosPolicy() == null));
    }

    @Test
    public void copyRejectsAnIncompleteLunReturnedByOntap() {
        createReturns(lun("/vol/dest/lun", null));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));

        assertTrue(thrown.getMessage().contains("incomplete LUN"), thrown.getMessage());
        verify(sanFeignClient, never()).getLunByUUID(anyString(), anyString(), anyMap());
    }

    @Test
    public void copyRemovesPartialLunWhenOntapStatusCallFails() {
        createReturns(createdCopy());
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenThrow(feignException(500, "ontap down"));

        assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));

        verify(sanFeignClient).deleteLun(anyString(), eq("copied-uuid"), anyMap());
    }

    @Test
    public void copyDoesNotResumeWhenConflictIsNotTheAlreadyStartedCopy() {
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenThrow(feignException(500, "{\"code\":\"5375060\"}"))
                .thenThrow(feignException(409, "{\"code\":\"1234567\"}"));

        assertThrows(RuntimeException.class, () -> copy(30, "src", "lun"));

        verify(sanFeignClient, never()).getLunResponse(anyString(), anyMap());
        verify(sanFeignClient, org.mockito.Mockito.times(2)).createLun(anyString(), anyBoolean(), any(Lun.class));
    }

    @Test
    public void copyLooksUpAnAlreadyStartedCopyInTheDestinationSvm() {
        Lun existing = withCopyProgress(createdCopy(), 100, null);
        when(sanFeignClient.createLun(anyString(), anyBoolean(), any(Lun.class)))
                .thenThrow(feignException(500, "{\"code\":\"5375060\"}"))
                .thenThrow(feignException(409, "{\"code\":\"7018919\"}"));
        when(sanFeignClient.getLunResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(existing)));
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(existing);
        VolumeInfo src = mock(VolumeInfo.class);
        VolumeInfo dest = mock(VolumeInfo.class);
        StoragePoolVO destPool = mock(StoragePoolVO.class);
        when(src.getId()).thenReturn(11L);
        when(dest.getName()).thenReturn("lun");
        when(destPool.getName()).thenReturn("dest");
        when(volumeDetailsDao.findDetail(11L, OntapStorageConstants.LUN_DOT_UUID))
                .thenReturn(new VolumeDetailVO(11L, OntapStorageConstants.LUN_DOT_UUID, "source-uuid", false));

        unifiedSANStrategy.copyCloudStackVolume(src, dest, destPool, Map.of(OntapStorageConstants.SVM_NAME, "other-svm"), 30);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> query = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sanFeignClient).getLunResponse(anyString(), query.capture());
        assertEquals("other-svm", query.getValue().get(OntapStorageConstants.SVM_DOT_NAME));
        assertEquals("/vol/dest/lun", query.getValue().get(OntapStorageConstants.NAME));
    }

    @Test
    public void copyKeepsInterruptFlagAfterBeingInterruptedWhileWaitingForProgress() {
        Lun created = withCopyProgress(createdCopy(), 50, "running");
        createReturns(created);
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenReturn(created);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CloudRuntimeException.class, () -> copy(30, "src", "lun"));

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void copyReportsTheGracePeriodInSecondsWhenCopyDetailsNeverAppear() {
        unifiedSANStrategy.lunCopyStatusGraceMs = 1000L;
        createReturns(createdCopy());
        when(sanFeignClient.getLunByUUID(anyString(), anyString(), anyMap())).thenThrow(feignException(404, "not yet"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> copy(10, "src", "lun"));

        assertTrue(thrown.getMessage().contains("within 1s"), thrown.getMessage());
    }
}
