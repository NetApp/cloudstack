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

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.ResizeVolumeCommand;
import com.cloud.host.HostVO;
import com.cloud.storage.Storage;
import com.cloud.storage.ResizeVolumePayload;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.client.JobFeignClient;
import org.apache.cloudstack.storage.feign.client.NASFeignClient;
import org.apache.cloudstack.storage.feign.client.VolumeFeignClient;
import org.apache.cloudstack.storage.feign.client.AggregateFeignClient;
import org.apache.cloudstack.storage.feign.client.SvmFeignClient;
import org.apache.cloudstack.storage.feign.client.NetworkFeignClient;
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.client.SnapshotFeignClient;
import org.apache.cloudstack.storage.feign.model.ExportPolicy;
import org.apache.cloudstack.storage.feign.model.ExportRule;
import org.apache.cloudstack.storage.feign.model.FileCloneRequest;
import org.apache.cloudstack.storage.feign.model.FileInfo;
import org.apache.cloudstack.storage.feign.model.Job;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.feign.model.VolumeQosPolicy;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.volume.VolumeObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import feign.FeignException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class UnifiedNASStrategyTest {

    @Mock
    private NASFeignClient nasFeignClient;

    @Mock
    private VolumeFeignClient volumeFeignClient;

    @Mock
    private JobFeignClient jobFeignClient;

    @Mock
    private SnapshotFeignClient snapshotFeignClient;

    @Mock
    private AggregateFeignClient aggregateFeignClient;

    @Mock
    private SvmFeignClient svmFeignClient;

    @Mock
    private NetworkFeignClient networkFeignClient;

    @Mock
    private SANFeignClient sanFeignClient;

    @Mock
    private VolumeDao volumeDao;

    @Mock
    private EndPointSelector epSelector;

    @Mock
    private StoragePoolDetailsDao storagePoolDetailsDao;

    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;

    private TestableUnifiedNASStrategy strategy;

    private OntapStorage ontapStorage;

    private static final long POOL_ID = 7L;
    private static final String POLICY_ID = "policy-1";

    @BeforeEach
    public void setUp() throws Exception {
        ontapStorage = new OntapStorage(
            "admin",
            "password",
            "192.168.1.100",
            "svm1",
            100L,
            ProtocolType.NFS3
        );
        strategy = new TestableUnifiedNASStrategy(ontapStorage, nasFeignClient, volumeFeignClient, jobFeignClient, aggregateFeignClient, svmFeignClient, networkFeignClient, sanFeignClient);
        strategy.snapshotFeignClient = snapshotFeignClient;
        injectField("volumeDao", volumeDao);
        injectField("epSelector", epSelector);
        injectField("storagePoolDetailsDao", storagePoolDetailsDao);
        injectField("primaryDataStoreDao", primaryDataStoreDao);
    }

    private void injectField(String fieldName, Object mockedField) throws Exception {
        Field field = UnifiedNASStrategy.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(strategy, mockedField);
    }

    private class TestableUnifiedNASStrategy extends UnifiedNASStrategy {
        public TestableUnifiedNASStrategy(OntapStorage ontapStorage,
                                          NASFeignClient nasFeignClient,
                                          VolumeFeignClient volumeFeignClient,
                                          JobFeignClient jobFeignClient,
                                          AggregateFeignClient aggregateFeignClient,
                                          SvmFeignClient svmFeignClient,
                                          NetworkFeignClient networkFeignClient,
                                          SANFeignClient sanFeignClient) {
            super(ontapStorage);
            // All Feign clients are in StorageStrategy parent class
            injectParentMockedClient("nasFeignClient", nasFeignClient);
            injectParentMockedClient("volumeFeignClient", volumeFeignClient);
            injectParentMockedClient("jobFeignClient", jobFeignClient);
            injectParentMockedClient("aggregateFeignClient", aggregateFeignClient);
            injectParentMockedClient("svmFeignClient", svmFeignClient);
            injectParentMockedClient("networkFeignClient", networkFeignClient);
            injectParentMockedClient("sanFeignClient", sanFeignClient);
        }

        private void injectParentMockedClient(String fieldName, Object mockedClient) {
            try {
                Field field = StorageStrategy.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(this, mockedClient);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Failed to inject parent mocked client: " + fieldName, e);
            }
        }
    }

    // Test createCloudStackVolume - Success
    @Test
    public void testCreateCloudStackVolume_Success() throws Exception {
        // Setup CloudStackVolume
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        Answer answer = new Answer(null, true, "Success");

        when(cloudStackVolume.getDatastoreId()).thenReturn("1");
        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeObject);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid-123");
        VolumeObjectTO volumeObjectTO = new VolumeObjectTO();
        volumeObjectTO.setFormat(Storage.ImageFormat.QCOW2);
        when(volumeObject.getTO()).thenReturn(volumeObjectTO);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(answer);

        // Execute
        CloudStackVolume result = strategy.createCloudStackVolume(cloudStackVolume);

        // Verify
        assertNotNull(result);
        verify(volumeDao).update(anyLong(), any(VolumeVO.class));
        verify(epSelector).select(volumeObject);
        ArgumentCaptor<CreateObjectCommand> cmdCaptor = ArgumentCaptor.forClass(CreateObjectCommand.class);
        verify(endPoint).sendMessage(cmdCaptor.capture());
        VolumeObjectTO sentTO = (VolumeObjectTO) cmdCaptor.getValue().getData();
        // The strategy sends the TO as the driver prepared it. It no longer overrides the format.
        assertSame(volumeObjectTO, sentTO);
        assertEquals(Storage.ImageFormat.QCOW2, sentTO.getFormat());
    }

    @Test
    public void testCopyCloudStackVolume_NotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> strategy.copyCloudStackVolume(mock(VolumeInfo.class),
                mock(VolumeInfo.class), mock(StoragePoolVO.class), Map.of(), 0));
    }

    @Test
    public void testCreateTemplateCache_IsNoOp() {
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO storagePool =
                mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(storagePool.getId()).thenReturn(1L);
        org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo templateInfo =
                mock(org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo.class);
        when(templateInfo.getId()).thenReturn(50L);

        CloudStackVolume result = strategy.createTemplateCache(storagePool, templateInfo, Map.of(), 0L);

        // NFS seeds via host CopyCommand; strategy intentionally returns null (no LUN/file yet).
        assertNull(result);
    }


    @Test
    public void testCreateCloudStackVolume_AppliesQosPolicyToNfsFile() throws Exception {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        Answer answer = new Answer(null, true, "Success");

        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to200_iops_svm1");
        FileInfo fileInfo = new FileInfo();
        fileInfo.setQosPolicy(qosPolicy);

        when(cloudStackVolume.getDatastoreId()).thenReturn("1");
        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeObject);
        when(cloudStackVolume.getFlexVolumeUuid()).thenReturn("flex-uuid");
        when(cloudStackVolume.getFile()).thenReturn(fileInfo);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid-123");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(answer);

        CloudStackVolume result = strategy.createCloudStackVolume(cloudStackVolume);

        assertNotNull(result);
        verify(nasFeignClient).updateFile(anyString(), eq("flex-uuid"), eq("volume-uuid-123"),
                argThat(file -> file.getQosPolicy() != null
                        && "cs_100_to200_iops_svm1".equals(file.getQosPolicy().getName())));
    }

    @Test
    public void testCreateCloudStackVolume_QosAttachFails_DeletesLeftoverNfsFile() {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        Answer createAnswer = new Answer(null, true, "Success");
        Answer deleteAnswer = new Answer(null, true, "Deleted");

        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to200_iops_svm1");
        FileInfo fileInfo = new FileInfo();
        fileInfo.setQosPolicy(qosPolicy);

        when(cloudStackVolume.getDatastoreId()).thenReturn("1");
        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeObject);
        when(cloudStackVolume.getFlexVolumeUuid()).thenReturn("flex-uuid");
        when(cloudStackVolume.getFile()).thenReturn(fileInfo);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid-123");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(createAnswer);
        when(endPoint.sendMessage(any(DeleteCommand.class))).thenReturn(deleteAnswer);

        FeignException feignException = mock(FeignException.class);
        when(feignException.contentUTF8()).thenReturn(
                "{\"error\":{\"code\":\"8454269\",\"message\":\"Invalid QoS policy group specified\"}}");
        when(feignException.getMessage()).thenReturn("Bad Request");
        doThrow(feignException).when(nasFeignClient).updateFile(anyString(), eq("flex-uuid"),
                eq("volume-uuid-123"), any(FileInfo.class));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.createCloudStackVolume(cloudStackVolume));
        assertTrue(ex.getMessage().contains("Failed to update NFS volume file"));
        verify(endPoint).sendMessage(any(DeleteCommand.class));
    }

    @Test
    public void testUpdateCloudStackVolume_AppliesQosPolicy() {
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getUuid()).thenReturn("volume-uuid-123");

        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to_200_iops_svm1");
        qosPolicy.setUuid("qos-uuid");
        FileInfo fileInfo = new FileInfo();
        fileInfo.setQosPolicy(qosPolicy);

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeInfo);
        request.setFlexVolumeUuid("flex-uuid");
        request.setFile(fileInfo);

        CloudStackVolume result = strategy.updateCloudStackVolume(request);

        assertSame(request, result);
        verify(nasFeignClient).updateFile(anyString(), eq("flex-uuid"), eq("volume-uuid-123"),
                argThat(file -> file.getQosPolicy() != null
                        && "cs_100_to_200_iops_svm1".equals(file.getQosPolicy().getName())
                        && "qos-uuid".equals(file.getQosPolicy().getUuid())));
    }

    @Test
    public void testUpdateCloudStackVolume_UnexpectedException_IsWrapped() {
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getUuid()).thenReturn("volume-uuid-123");

        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to_200_iops_svm1");
        FileInfo fileInfo = new FileInfo();
        fileInfo.setQosPolicy(qosPolicy);

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeInfo);
        request.setFlexVolumeUuid("flex-uuid");
        request.setFile(fileInfo);

        doThrow(new IllegalStateException("decoder failed")).when(nasFeignClient)
                .updateFile(anyString(), eq("flex-uuid"), eq("volume-uuid-123"), any(FileInfo.class));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.updateCloudStackVolume(request));
        assertTrue(ex.getMessage().contains("Failed to update NFS volume file"));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    public void testUpdateCloudStackVolume_NoQosPolicy_SkipsPatch() {
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getUuid()).thenReturn("volume-uuid-123");

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeInfo);
        request.setFlexVolumeUuid("flex-uuid");
        request.setFile(new FileInfo());

        CloudStackVolume result = strategy.updateCloudStackVolume(request);

        assertSame(request, result);
        verify(nasFeignClient, never()).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
    }

    @Test
    public void testUpdateCloudStackVolume_InvalidRequest_ThrowsException() {
        CloudStackVolume missingFile = new CloudStackVolume();
        missingFile.setVolumeInfo(mock(VolumeInfo.class));
        missingFile.setFlexVolumeUuid("flex-uuid");

        assertInvalidNfsVolumeUpdate(null);
        assertInvalidNfsVolumeUpdate(new CloudStackVolume());
        assertInvalidNfsVolumeUpdate(missingFile);

        verify(nasFeignClient, never()).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
    }

    private void assertInvalidNfsVolumeUpdate(CloudStackVolume request) {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> strategy.updateCloudStackVolume(request));
        assertEquals("Invalid NFS volume request", ex.getMessage());
    }

    // Test createCloudStackVolume - Volume Not Found
    @Test
    public void testCreateCloudStackVolume_VolumeNotFound() {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeObject volumeObject = mock(VolumeObject.class);

        when(cloudStackVolume.getDatastoreId()).thenReturn("1");
        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeObject);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.createCloudStackVolume(cloudStackVolume);
        });
    }

    // Test createCloudStackVolume - KVM Host Creation Failed
    @Test
    public void testCreateCloudStackVolume_KVMHostFailed() {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        Answer answer = new Answer(null, false, "Failed to create volume");

        when(cloudStackVolume.getDatastoreId()).thenReturn("1");
        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeObject);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid-123");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(answer);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.createCloudStackVolume(cloudStackVolume);
        });
    }

    // Test createCloudStackVolume - No Endpoint
    @Test
    public void testCreateCloudStackVolume_NoEndpoint() {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);

        when(cloudStackVolume.getDatastoreId()).thenReturn("1");
        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeObject);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid-123");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);
        when(epSelector.select(volumeObject)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.createCloudStackVolume(cloudStackVolume);
        });
    }

    // Test createAccessGroup - Success
    @Test
    public void testCreateAccessGroup_Success() throws Exception {
        // Setup
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.VOLUME_UUID, "vol-uuid-123");
        details.put(OntapStorageConstants.VOLUME_NAME, "vol1");

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageIpAddress()).thenReturn("10.0.0.1");
        hosts.add(host1);

        ExportPolicy createdPolicy = mock(ExportPolicy.class);
        when(createdPolicy.getId()).thenReturn(java.math.BigInteger.ONE);
        when(createdPolicy.getName()).thenReturn("export-policy-1");

        OntapResponse<ExportPolicy> policyResponse = new OntapResponse<>();
        List<ExportPolicy> policies = new ArrayList<>();
        policies.add(createdPolicy);
        policyResponse.setRecords(policies);

        JobResponse jobResponse = new JobResponse();
        Job job = new Job();
        job.setUuid("job-uuid-123");
        job.setState(OntapStorageConstants.JOB_SUCCESS);
        jobResponse.setJob(job);

        // Removed primaryDataStoreInfo mock - using storage pool ID directly
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        when(accessGroup.getStoragePoolId()).thenReturn(1L);
        when(accessGroup.getHostsToConnect()).thenReturn(hosts);
        doNothing().when(nasFeignClient).createExportPolicy(anyString(), any(ExportPolicy.class));
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenReturn(policyResponse);
        when(volumeFeignClient.updateVolume(anyString(), anyString(), any())).thenReturn(jobResponse);
        when(jobFeignClient.getJobByUUID(anyString(), anyString())).thenReturn(job);
        doNothing().when(storagePoolDetailsDao).addDetail(anyLong(), anyString(), anyString(), eq(true));

        // Execute
        AccessGroup result = strategy.createAccessGroup(accessGroup);

        // Verify
        assertNotNull(result);
        verify(nasFeignClient).createExportPolicy(anyString(), any(ExportPolicy.class));
        verify(nasFeignClient).getExportPolicyResponse(anyString(), anyMap());
        verify(volumeFeignClient).updateVolume(anyString(), eq("vol-uuid-123"), any());
        verify(storagePoolDetailsDao, times(2)).addDetail(anyLong(), anyString(), anyString(), eq(true));
    }

    // Test createAccessGroup - Failed to Create Policy
    @Test
    public void testCreateAccessGroup_FailedToCreatePolicy() {
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.VOLUME_UUID, "vol-uuid-123");
        details.put(OntapStorageConstants.VOLUME_NAME, "vol1");

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageIpAddress()).thenReturn("10.0.0.1");
        hosts.add(host1);

        // Removed primaryDataStoreInfo mock - using storage pool ID directly
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        when(accessGroup.getHostsToConnect()).thenReturn(hosts);
        doThrow(new RuntimeException("Failed to create policy")).when(nasFeignClient)
            .createExportPolicy(anyString(), any(ExportPolicy.class));

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.createAccessGroup(accessGroup);
        });
    }

    // Test createAccessGroup - Failed to Verify Policy
    @Test
    public void testCreateAccessGroup_FailedToVerifyPolicy() {
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.VOLUME_UUID, "vol-uuid-123");
        details.put(OntapStorageConstants.VOLUME_NAME, "vol1");

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageIpAddress()).thenReturn("10.0.0.1");
        hosts.add(host1);

        OntapResponse<ExportPolicy> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        // Removed primaryDataStoreInfo mock - using storage pool ID directly
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        when(accessGroup.getHostsToConnect()).thenReturn(hosts);
        doNothing().when(nasFeignClient).createExportPolicy(anyString(), any(ExportPolicy.class));
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenReturn(emptyResponse);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.createAccessGroup(accessGroup);
        });
    }

    // Test createAccessGroup - Job Timeout
    // Note: This test is simplified to avoid 200 second wait time.
    // In reality, testing timeout would require mocking Thread.sleep() or refactoring the code.
    @Test
    public void testCreateAccessGroup_JobFailure() throws Exception {
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.VOLUME_UUID, "vol-uuid-123");
        details.put(OntapStorageConstants.VOLUME_NAME, "vol1");

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageIpAddress()).thenReturn("10.0.0.1");
        hosts.add(host1);

        ExportPolicy createdPolicy = mock(ExportPolicy.class);
        when(createdPolicy.getId()).thenReturn(java.math.BigInteger.ONE);
        when(createdPolicy.getName()).thenReturn("export-policy-1");

        OntapResponse<ExportPolicy> policyResponse = new OntapResponse<>();
        List<ExportPolicy> policies = new ArrayList<>();
        policies.add(createdPolicy);
        policyResponse.setRecords(policies);

        JobResponse jobResponse = new JobResponse();
        Job job = new Job();
        job.setUuid("job-uuid-123");
        job.setState(OntapStorageConstants.JOB_FAILURE); // Set to FAILURE instead of timeout
        job.setMessage("Job failed");
        jobResponse.setJob(job);

        // Removed primaryDataStoreInfo mock - using storage pool ID directly
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        when(accessGroup.getStoragePoolId()).thenReturn(1L);
        when(accessGroup.getHostsToConnect()).thenReturn(hosts);
        doNothing().when(nasFeignClient).createExportPolicy(anyString(), any(ExportPolicy.class));
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenReturn(policyResponse);
        when(volumeFeignClient.updateVolume(anyString(), anyString(), any())).thenReturn(jobResponse);
        when(jobFeignClient.getJobByUUID(anyString(), anyString())).thenReturn(job);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.createAccessGroup(accessGroup);
        });
    }

    // Test createAccessGroup - Host with Private IP
    @Test
    public void testCreateAccessGroup_HostWithPrivateIP() throws Exception {
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.VOLUME_UUID, "vol-uuid-123");
        details.put(OntapStorageConstants.VOLUME_NAME, "vol1");

        List<HostVO> hosts = new ArrayList<>();
        HostVO host1 = mock(HostVO.class);
        when(host1.getStorageIpAddress()).thenReturn(null);
        when(host1.getPrivateIpAddress()).thenReturn("192.168.1.10");
        hosts.add(host1);

        ExportPolicy createdPolicy = mock(ExportPolicy.class);
        when(createdPolicy.getId()).thenReturn(java.math.BigInteger.ONE);
        when(createdPolicy.getName()).thenReturn("export-policy-1");

        OntapResponse<ExportPolicy> policyResponse = new OntapResponse<>();
        List<ExportPolicy> policies = new ArrayList<>();
        policies.add(createdPolicy);
        policyResponse.setRecords(policies);

        JobResponse jobResponse = new JobResponse();
        Job job = new Job();
        job.setUuid("job-uuid-123");
        job.setState(OntapStorageConstants.JOB_SUCCESS);
        jobResponse.setJob(job);

        // Removed primaryDataStoreInfo mock - using storage pool ID directly
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        when(accessGroup.getStoragePoolId()).thenReturn(1L);
        when(accessGroup.getHostsToConnect()).thenReturn(hosts);
        doNothing().when(nasFeignClient).createExportPolicy(anyString(), any(ExportPolicy.class));
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenReturn(policyResponse);
        when(volumeFeignClient.updateVolume(anyString(), anyString(), any())).thenReturn(jobResponse);
        when(jobFeignClient.getJobByUUID(anyString(), anyString())).thenReturn(job);
        doNothing().when(storagePoolDetailsDao).addDetail(anyLong(), anyString(), anyString(), eq(true));

        // Execute
        AccessGroup result = strategy.createAccessGroup(accessGroup);

        // Verify
        assertNotNull(result);
        ArgumentCaptor<ExportPolicy> policyCaptor = ArgumentCaptor.forClass(ExportPolicy.class);
        verify(nasFeignClient).createExportPolicy(anyString(), policyCaptor.capture());
        ExportPolicy capturedPolicy = policyCaptor.getValue();
        assertEquals("192.168.1.10/32", capturedPolicy.getRules().get(0).getClients().get(0).getMatch());
    }

    // Test deleteAccessGroup - Success
    @Test
    public void testDeleteAccessGroup_Success() {
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.EXPORT_POLICY_NAME, "export-policy-1");
        details.put(OntapStorageConstants.EXPORT_POLICY_ID, "1");

        when(accessGroup.getStoragePoolId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        // Removed primaryDataStoreInfo.getName() - not used
        doNothing().when(nasFeignClient).deleteExportPolicyById(anyString(), anyString());

        // Execute
        strategy.deleteAccessGroup(accessGroup);

        // Verify
        verify(nasFeignClient).deleteExportPolicyById(anyString(), eq("1"));
    }

    // Test deleteAccessGroup - Null AccessGroup
    @Test
    public void testDeleteAccessGroup_NullAccessGroup() {
        assertThrows(CloudRuntimeException.class, () -> {
            strategy.deleteAccessGroup(null);
        });
    }

    // Test deleteAccessGroup - Null PrimaryDataStoreInfo
    @Test
    public void testDeleteAccessGroup_NullPrimaryDataStoreInfo() {
        AccessGroup accessGroup = mock(AccessGroup.class);
        when(accessGroup.getStoragePoolId()).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.deleteAccessGroup(accessGroup);
        });
    }

    // Test deleteAccessGroup - Failed to Delete
    @Test
    public void testDeleteAccessGroup_Failed() {
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.EXPORT_POLICY_NAME, "export-policy-1");
        details.put(OntapStorageConstants.EXPORT_POLICY_ID, "1");

        when(accessGroup.getStoragePoolId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        doThrow(new RuntimeException("Failed to delete")).when(nasFeignClient)
            .deleteExportPolicyById(anyString(), anyString());

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.deleteAccessGroup(accessGroup);
        });
    }

    // Test deleteAccessGroup - Export policy not found should be treated as no-op
    @Test
    public void testDeleteAccessGroup_NotFound404_NoThrow() {
        AccessGroup accessGroup = mock(AccessGroup.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.EXPORT_POLICY_NAME, "export-policy-1");
        details.put(OntapStorageConstants.EXPORT_POLICY_ID, "1");

        when(accessGroup.getStoragePoolId()).thenReturn(1L);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);
        doThrow(feignException).when(nasFeignClient).deleteExportPolicyById(anyString(), eq("1"));

        strategy.deleteAccessGroup(accessGroup);

        verify(nasFeignClient).deleteExportPolicyById(anyString(), eq("1"));
    }

    // Test deleteCloudStackVolume - Success
    @Test
    public void testDeleteCloudStackVolume_Success() throws Exception {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        EndPoint endpoint = mock(EndPoint.class);
        Answer answer = mock(Answer.class);

        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeInfo);
        when(epSelector.select(volumeInfo)).thenReturn(endpoint);
        when(endpoint.sendMessage(any())).thenReturn(answer);
        when(answer.getResult()).thenReturn(true);

        // Execute - should not throw exception
        strategy.deleteCloudStackVolume(cloudStackVolume);

        // Verify endpoint was selected and message sent
        verify(epSelector).select(volumeInfo);
        verify(endpoint).sendMessage(any());
    }

    // Test deleteCloudStackVolume - Endpoint Not Found
    @Test
    public void testDeleteCloudStackVolume_EndpointNotFound() {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeInfo volumeInfo = mock(VolumeInfo.class);

        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeInfo);
        when(epSelector.select(volumeInfo)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.deleteCloudStackVolume(cloudStackVolume);
        });
    }

    // Test deleteCloudStackVolume - Answer Result False
    @Test
    public void testDeleteCloudStackVolume_AnswerResultFalse() throws Exception {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        EndPoint endpoint = mock(EndPoint.class);
        Answer answer = mock(Answer.class);

        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeInfo);
        when(epSelector.select(volumeInfo)).thenReturn(endpoint);
        when(endpoint.sendMessage(any())).thenReturn(answer);
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("Failed to delete volume file");

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.deleteCloudStackVolume(cloudStackVolume);
        });
    }

    // Test deleteCloudStackVolume - Answer is Null
    @Test
    public void testDeleteCloudStackVolume_AnswerNull() throws Exception {
        CloudStackVolume cloudStackVolume = mock(CloudStackVolume.class);
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        EndPoint endpoint = mock(EndPoint.class);

        when(cloudStackVolume.getVolumeInfo()).thenReturn(volumeInfo);
        when(epSelector.select(volumeInfo)).thenReturn(endpoint);
        when(endpoint.sendMessage(any())).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> {
            strategy.deleteCloudStackVolume(cloudStackVolume);
        });
    }

    // -------------------------------------------------------------------------
    // updateAccessGroup tests
    // -------------------------------------------------------------------------

    private Map<String, String> detailsWithExportPolicyId() {
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.EXPORT_POLICY_ID, "policy-42");
        return details;
    }

    private ExportPolicy existingPolicyWithClients(String... matchIps) {
        ExportRule rule = new ExportRule();
        List<ExportRule.ExportClient> clients = new ArrayList<>();
        for (String ip : matchIps) {
            ExportRule.ExportClient client = new ExportRule.ExportClient();
            client.setMatch(ip);
            clients.add(client);
        }
        rule.setClients(clients);
        ExportPolicy policy = new ExportPolicy();
        policy.setName("test-policy");
        policy.setRules(new ArrayList<>(List.of(rule)));
        return policy;
    }

    // updateAccessGroup - null accessGroup
    @Test
    public void testUpdateAccessGroup_NullAccessGroup() {
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(null));
    }

    // updateAccessGroup - null storagePoolId
    @Test
    public void testUpdateAccessGroup_NullStoragePoolId() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setHostsToConnect(List.of(mock(HostVO.class)));
        // storagePoolId is null by default
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - null hostsToConnect
    @Test
    public void testUpdateAccessGroup_NullHostsToConnect() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        // hostsToConnect is null by default
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - empty hostsToConnect
    @Test
    public void testUpdateAccessGroup_EmptyHostsToConnect() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(new ArrayList<>());
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - storagePoolDetailsDao returns null
    @Test
    public void testUpdateAccessGroup_NoStoragePoolDetails() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(mock(HostVO.class)));
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - details missing EXPORT_POLICY_ID key
    @Test
    public void testUpdateAccessGroup_MissingExportPolicyId() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(mock(HostVO.class)));
        Map<String, String> details = new HashMap<>();
        details.put("someOtherKey", "someValue");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - getExportPolicyById returns null
    @Test
    public void testUpdateAccessGroup_ExportPolicyNotFound() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(mock(HostVO.class)));
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - existing policy has null rules
    @Test
    public void testUpdateAccessGroup_NullRules() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(mock(HostVO.class)));
        ExportPolicy policy = new ExportPolicy();
        policy.setName("test-policy");
        policy.setRules(null);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(policy);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - existing policy has empty rules list
    @Test
    public void testUpdateAccessGroup_EmptyRules() {
        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(mock(HostVO.class)));
        ExportPolicy policy = new ExportPolicy();
        policy.setName("test-policy");
        policy.setRules(new ArrayList<>());
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(policy);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }

    // updateAccessGroup - all hosts have no IP: returns early without ONTAP patch
    @Test
    public void testUpdateAccessGroup_AllHostsHaveNoIp_ReturnsEarly() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn(null);
        when(host.getPrivateIpAddress()).thenReturn(null);

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));

        ExportPolicy existingPolicy = existingPolicyWithClients("10.0.0.1/32");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        AccessGroup result = strategy.updateAccessGroup(accessGroup);

        assertNotNull(result);
        assertSame(existingPolicy, result.getPolicy());
        verify(nasFeignClient, never()).updateExportPolicy(anyString(), anyString(), any());
    }

    // updateAccessGroup - ADD: new host IP added to policy
    @Test
    public void testUpdateAccessGroup_Add_NewHost_Success() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn("10.0.0.2");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));
        // default action is ADD

        ExportPolicy existingPolicy = existingPolicyWithClients("10.0.0.1/32");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        AccessGroup result = strategy.updateAccessGroup(accessGroup);

        assertNotNull(result);
        assertSame(existingPolicy, result.getPolicy());
        // Existing client + new client = 2
        assertEquals(2, existingPolicy.getRules().get(0).getClients().size());
        verify(nasFeignClient).updateExportPolicy(anyString(), eq("policy-42"), any(ExportPolicy.class));
    }

    // updateAccessGroup - ADD: host uses private IP when storage IP is absent
    @Test
    public void testUpdateAccessGroup_Add_UsesPrivateIpWhenStorageIpAbsent() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn(null);
        when(host.getPrivateIpAddress()).thenReturn("192.168.1.50");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));

        ExportPolicy existingPolicy = existingPolicyWithClients();
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        AccessGroup result = strategy.updateAccessGroup(accessGroup);

        assertNotNull(result);
        List<ExportRule.ExportClient> clients = existingPolicy.getRules().get(0).getClients();
        assertEquals(1, clients.size());
        assertEquals("192.168.1.50/32", clients.get(0).getMatch());
        verify(nasFeignClient).updateExportPolicy(anyString(), eq("policy-42"), any(ExportPolicy.class));
    }

    // updateAccessGroup - ADD: host IP already present in policy (no-op)
    @Test
    public void testUpdateAccessGroup_Add_DuplicateHost_NoUpdate() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn("10.0.0.1");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));

        ExportPolicy existingPolicy = existingPolicyWithClients("10.0.0.1/32");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        AccessGroup result = strategy.updateAccessGroup(accessGroup);

        assertNotNull(result);
        assertSame(existingPolicy, result.getPolicy());
        // Client count must remain 1 (no duplicate inserted)
        assertEquals(1, existingPolicy.getRules().get(0).getClients().size());
        verify(nasFeignClient, never()).updateExportPolicy(anyString(), anyString(), any());
    }

    // updateAccessGroup - ADD: existing rule has null clients list
    @Test
    public void testUpdateAccessGroup_Add_NullClientsInRule() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn("10.0.0.5");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));

        ExportRule rule = new ExportRule();
        rule.setClients(null); // null clients list
        ExportPolicy policy = new ExportPolicy();
        policy.setName("test-policy");
        policy.setRules(new ArrayList<>(List.of(rule)));

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(policy);

        AccessGroup result = strategy.updateAccessGroup(accessGroup);

        assertNotNull(result);
        assertEquals(1, rule.getClients().size());
        assertEquals("10.0.0.5/32", rule.getClients().get(0).getMatch());
        verify(nasFeignClient).updateExportPolicy(anyString(), eq("policy-42"), any(ExportPolicy.class));
    }

    // updateAccessGroup - REMOVE: matching host IP removed from policy
    @Test
    public void testUpdateAccessGroup_Remove_MatchingHost_Success() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn("10.0.0.1");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));
        accessGroup.setHostRuleAction(AccessGroup.HostRuleAction.REMOVE);

        ExportPolicy existingPolicy = existingPolicyWithClients("10.0.0.1/32", "10.0.0.2/32");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        AccessGroup result = strategy.updateAccessGroup(accessGroup);

        assertNotNull(result);
        assertSame(existingPolicy, result.getPolicy());
        // Only 10.0.0.2/32 should remain
        List<ExportRule.ExportClient> clients = existingPolicy.getRules().get(0).getClients();
        assertEquals(1, clients.size());
        assertEquals("10.0.0.2/32", clients.get(0).getMatch());
        verify(nasFeignClient).updateExportPolicy(anyString(), eq("policy-42"), any(ExportPolicy.class));
    }

    // updateAccessGroup - REMOVE: IP not in policy (no-op)
    @Test
    public void testUpdateAccessGroup_Remove_IpNotPresent_NoUpdate() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn("10.0.0.99");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));
        accessGroup.setHostRuleAction(AccessGroup.HostRuleAction.REMOVE);

        ExportPolicy existingPolicy = existingPolicyWithClients("10.0.0.1/32");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        AccessGroup result = strategy.updateAccessGroup(accessGroup);

        assertNotNull(result);
        assertSame(existingPolicy, result.getPolicy());
        assertEquals(1, existingPolicy.getRules().get(0).getClients().size());
        verify(nasFeignClient, never()).updateExportPolicy(anyString(), anyString(), any());
    }

    // updateAccessGroup - FeignException from ONTAP wrapped in CloudRuntimeException
    @Test
    public void testUpdateAccessGroup_FeignExceptionWrapped() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn("10.0.0.1");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42")))
                .thenThrow(new RuntimeException("ONTAP unreachable"));

        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(accessGroup));
    }
    // updateAccessGroup - whitespace in storage IP is trimmed before building match
    @Test
    public void testUpdateAccessGroup_TrimsWhitespaceFromStorageIp() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn("  10.0.0.2  ");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));

        ExportPolicy existingPolicy = existingPolicyWithClients();
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        strategy.updateAccessGroup(accessGroup);

        List<ExportRule.ExportClient> clients = existingPolicy.getRules().get(0).getClients();
        assertEquals(1, clients.size());
        assertEquals("10.0.0.2/32", clients.get(0).getMatch());
    }

    // updateAccessGroup - whitespace in private IP is trimmed when storage IP absent
    @Test
    public void testUpdateAccessGroup_TrimsWhitespaceFromPrivateIp() {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn(null);
        when(host.getPrivateIpAddress()).thenReturn("  192.168.1.10  ");

        AccessGroup accessGroup = new AccessGroup();
        accessGroup.setStoragePoolId(1L);
        accessGroup.setHostsToConnect(List.of(host));

        ExportPolicy existingPolicy = existingPolicyWithClients();
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(detailsWithExportPolicyId());
        when(nasFeignClient.getExportPolicyById(anyString(), eq("policy-42"))).thenReturn(existingPolicy);

        strategy.updateAccessGroup(accessGroup);

        List<ExportRule.ExportClient> clients = existingPolicy.getRules().get(0).getClients();
        assertEquals(1, clients.size());
        assertEquals("192.168.1.10/32", clients.get(0).getMatch());
    }

    @Test
    public void testCloneCloudStackVolume_Success() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        details.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        FileInfo source = new FileInfo();
        source.setPath("template-uuid");
        CloudStackVolume request = new CloudStackVolume();
        request.setDatastoreId("1");
        request.setVolumeInfo(volumeObject);
        request.setFile(source);
        request.setDestinationPath("volume-uuid");

        when(nasFeignClient.cloneFile(anyString(), any(FileCloneRequest.class))).thenReturn(new JobResponse());

        CloudStackVolume result = strategy.cloneCloudStackVolume(request);

        assertNotNull(result);
        assertEquals("volume-uuid", result.getFile().getPath());
        ArgumentCaptor<FileCloneRequest> captor = ArgumentCaptor.forClass(FileCloneRequest.class);
        verify(nasFeignClient).cloneFile(anyString(), captor.capture());
        assertEquals("template-uuid", captor.getValue().getSourcePath());
        assertEquals("volume-uuid", captor.getValue().getDestinationPath());
        assertEquals("flexvol1", captor.getValue().getVolume().getName());
        assertEquals("flexvol-uuid-1", captor.getValue().getVolume().getUuid());
    }

    @Test
    public void testCloneCloudStackVolume_SnapshotSource_UsesSnapshotSourcePath() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        details.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        FileInfo source = new FileInfo();
        source.setPath("./.snapshot/snap_cs200/source-file-uuid");
        CloudStackVolume request = new CloudStackVolume();
        request.setDatastoreId("1");
        request.setVolumeInfo(volumeObject);
        request.setFile(source);
        request.setDestinationPath("new-volume-uuid");

        when(nasFeignClient.cloneFile(anyString(), any(FileCloneRequest.class))).thenReturn(new JobResponse());

        CloudStackVolume result = strategy.cloneCloudStackVolume(request);

        assertEquals("new-volume-uuid", result.getFile().getPath());
        ArgumentCaptor<FileCloneRequest> captor = ArgumentCaptor.forClass(FileCloneRequest.class);
        verify(nasFeignClient).cloneFile(anyString(), captor.capture());
        assertEquals("./.snapshot/snap_cs200/source-file-uuid", captor.getValue().getSourcePath());
        assertEquals("new-volume-uuid", captor.getValue().getDestinationPath());
    }

    @Test
    public void testCloneCloudStackVolume_InvalidRequest_ThrowsException() {
        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(null));
    }

    @Test
    public void testResizeCloudStackVolume_SendsResizeCommand() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        ResizeVolumePayload payload = new ResizeVolumePayload(
                21474836480L, null, null, null, false, "i-2-VM", null, false);

        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid");
        when(volumeObject.getpayload()).thenReturn(payload);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPath()).thenReturn("volume-uuid");
        when(volumeVO.getSize()).thenReturn(5368709120L);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class))).thenReturn(new Answer(null, true, "Success"));

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        strategy.resizeCloudStackVolume(request, 21474836480L);

        ArgumentCaptor<ResizeVolumeCommand> commandCaptor = ArgumentCaptor.forClass(ResizeVolumeCommand.class);
        verify(endPoint).sendMessage(commandCaptor.capture());
        ResizeVolumeCommand command = commandCaptor.getValue();
        assertEquals("volume-uuid", command.getPath());
        assertEquals(5368709120L, command.getCurrentSize());
        assertEquals(21474836480L, command.getNewSize());
        assertEquals("i-2-VM", command.getInstanceName());
        verify(nasFeignClient, never()).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
    }

    @Test
    public void testResizeCloudStackVolume_AppliesQosAfterFileGrow() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        ResizeVolumePayload payload = new ResizeVolumePayload(
                21474836480L, 100L, 200L, null, false, "i-2-VM", null, false);

        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid");
        when(volumeObject.getpayload()).thenReturn(payload);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPath()).thenReturn("volume-uuid");
        when(volumeVO.getSize()).thenReturn(5368709120L);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class))).thenReturn(new Answer(null, true, "Success"));

        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to_200_iops_svm1");
        qosPolicy.setUuid("qos-uuid");
        FileInfo fileInfo = new FileInfo();
        fileInfo.setQosPolicy(qosPolicy);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);
        request.setFlexVolumeUuid("flex-uuid");
        request.setFile(fileInfo);

        strategy.resizeCloudStackVolume(request, 21474836480L);

        verify(endPoint).sendMessage(any(ResizeVolumeCommand.class));
        verify(nasFeignClient).updateFile(anyString(), eq("flex-uuid"), eq("volume-uuid"),
                argThat(file -> file.getQosPolicy() != null
                        && "cs_100_to_200_iops_svm1".equals(file.getQosPolicy().getName())
                        && "qos-uuid".equals(file.getQosPolicy().getUuid())));
    }

    @Test
    public void testResizeCloudStackVolume_WithoutPayloadUsesDetachedInstanceName() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);

        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPath()).thenReturn("volume-uuid");
        when(volumeVO.getSize()).thenReturn(5368709120L);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class))).thenReturn(new Answer(null, true, "Success"));

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        strategy.resizeCloudStackVolume(request, 21474836480L);

        ArgumentCaptor<ResizeVolumeCommand> commandCaptor = ArgumentCaptor.forClass(ResizeVolumeCommand.class);
        verify(endPoint).sendMessage(commandCaptor.capture());
        assertEquals("none", commandCaptor.getValue().getInstanceName());
    }

    @Test
    public void testResizeCloudStackVolume_AgentFailureThrowsException() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);

        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class)))
                .thenReturn(new Answer(null, false, "qemu-img resize failed"));

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(request, 21474836480L));
        assertEquals("qemu-img resize failed", exception.getMessage());
    }

    @Test
    public void testResizeCloudStackVolume_InvalidSizeDoesNotSendCommand() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(request, 0L));

        verify(epSelector, never()).select(any(org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class));
    }

    @Test
    public void testResizeCloudStackVolume_NegativeSizeDoesNotSendCommand() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(request, -1L));

        verify(epSelector, never()).select(any(org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class));
    }

    @Test
    public void testDeleteFileByPath_Treats404AsSuccess() {
        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(404);
        doThrow(feignException).when(nasFeignClient).deleteFile(anyString(), eq("flexvol-uuid"), eq("template-uuid"));

        strategy.deleteFileByPath("flexvol-uuid", "template-uuid");

        verify(nasFeignClient).deleteFile(anyString(), eq("flexvol-uuid"), eq("template-uuid"));
    }

    @Test
    public void testDeleteFileByPath_Non404Feign_Throws() {
        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("server error");
        doThrow(feignException).when(nasFeignClient).deleteFile(anyString(), eq("flexvol-uuid"), eq("template-uuid"));

        assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteFileByPath("flexvol-uuid", "template-uuid"));
    }

    @Test
    public void testCloneCloudStackVolume_MissingFlexVolUuid_Throws() {
        FileInfo source = new FileInfo();
        source.setPath("template-uuid");
        CloudStackVolume request = new CloudStackVolume();
        request.setDatastoreId("1");
        request.setFile(source);
        request.setDestinationPath("volume-uuid");

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(request));
        verify(nasFeignClient, never()).cloneFile(anyString(), any(FileCloneRequest.class));
    }

    @Test
    public void testCloneCloudStackVolume_MissingDatastoreId_Throws() {
        FileInfo source = new FileInfo();
        source.setPath("template-uuid");
        CloudStackVolume request = new CloudStackVolume();
        request.setFile(source);
        request.setDestinationPath("volume-uuid");

        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(request));
    }

    @Test
    public void testCloneCloudStackVolume_FeignException_Throws() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.VOLUME_NAME, "flexvol1");
        details.put(OntapStorageConstants.VOLUME_UUID, "flexvol-uuid-1");
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        FileInfo source = new FileInfo();
        source.setPath("template-uuid");
        CloudStackVolume request = new CloudStackVolume();
        request.setDatastoreId("1");
        request.setVolumeInfo(volumeObject);
        request.setFile(source);
        request.setDestinationPath("volume-uuid");

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(feignException.getMessage()).thenReturn("clone failed");
        when(nasFeignClient.cloneFile(anyString(), any(FileCloneRequest.class))).thenThrow(feignException);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.cloneCloudStackVolume(request));

        assertEquals("Failed to clone file: clone failed", thrown.getMessage());
        ArgumentCaptor<FileCloneRequest> sent = ArgumentCaptor.forClass(FileCloneRequest.class);
        verify(nasFeignClient).cloneFile(anyString(), sent.capture());
        assertEquals("template-uuid", sent.getValue().getSourcePath());
        assertEquals("volume-uuid", sent.getValue().getDestinationPath());
        assertEquals("flexvol1", sent.getValue().getVolume().getName());
        assertEquals("flexvol-uuid-1", sent.getValue().getVolume().getUuid());
    }

    @Test
    public void testResizeCloudStackVolume_InvalidRequest_Throws() {
        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(null, 100L));
        CloudStackVolume empty = new CloudStackVolume();
        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(empty, 100L));

        VolumeObject volumeObject = mock(VolumeObject.class);
        CloudStackVolume withVol = new CloudStackVolume();
        withVol.setVolumeInfo(volumeObject);
        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(withVol, 0L));
    }

    @Test
    public void testResizeCloudStackVolume_KvmHostFails_Throws() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);

        when(volumeObject.getId()).thenReturn(100L);
        when(volumeObject.getUuid()).thenReturn("volume-uuid");
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPath()).thenReturn("volume-uuid");
        when(volumeVO.getSize()).thenReturn(5368709120L);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class))).thenReturn(new Answer(null, false, "resize failed"));

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(request, 21474836480L));
    }

    @Test
    public void testResizeCloudStackVolume_NoEndpoint_Throws() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);

        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPath()).thenReturn("volume-uuid");
        when(volumeVO.getSize()).thenReturn(5368709120L);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(null);

        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(request, 21474836480L));
    }

    @Test
    public void testResizeCloudStackVolume_VolumeNotFound_Throws() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(null);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(request, 21474836480L));

        assertTrue(exception.getMessage().contains("Volume not found"));
        verify(epSelector, never()).select(any(org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class));
    }

    @Test
    public void testResizeCloudStackVolume_StoragePoolNotFound_Throws() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(null);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(request, 21474836480L));

        assertTrue(exception.getMessage().contains("Storage Pool not found"));
        verify(epSelector, never()).select(any(org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class));
    }

    @Test
    public void testResizeCloudStackVolume_NullAnswer_Throws() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class))).thenReturn(null);
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(request, 21474836480L));

        assertTrue(exception.getMessage().contains("Failed to resize qcow2 on KVM host"));
    }

    @Test
    public void testResizeCloudStackVolume_AgentException_Propagates() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class)))
                .thenThrow(new CloudRuntimeException("KVM agent unreachable"));
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(request, 21474836480L));

        assertEquals("KVM agent unreachable", exception.getMessage());
    }

    @Test
    public void testResizeCloudStackVolume_DeletedFile_ThrowsAgentError() {
        VolumeObject volumeObject = mock(VolumeObject.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        EndPoint endPoint = mock(EndPoint.class);
        when(volumeObject.getId()).thenReturn(100L);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeVO.getPath()).thenReturn("deleted-volume.qcow2");
        when(volumeVO.getPoolId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(1L)).thenReturn(storagePool);
        when(epSelector.select(volumeObject)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(ResizeVolumeCommand.class)))
                .thenReturn(new Answer(null, false,
                        "qemu-img: Could not open 'deleted-volume.qcow2': No such file or directory"));
        CloudStackVolume request = new CloudStackVolume();
        request.setVolumeInfo(volumeObject);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(request, 21474836480L));

        assertTrue(exception.getMessage().contains("No such file or directory"));
        assertTrue(exception.getMessage().contains("deleted-volume.qcow2"));
    }

    /** A real FeignException (mocks have no stack trace, which breaks logging of the cause). */
    private static FeignException feignException(int status, String message) {
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.GET, "http://ontap/api",
                new HashMap<>(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        feign.Response response = feign.Response.builder().status(status).reason(message).request(request)
                .headers(new HashMap<>()).body(message, java.nio.charset.StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("ontapCall", response);
    }

    private VolumeObject volumeObject() {
        VolumeObject volume = mock(VolumeObject.class);
        VolumeObjectTO to = new VolumeObjectTO();
        to.setFormat(Storage.ImageFormat.QCOW2);
        when(volume.getId()).thenReturn(100L);
        when(volume.getUuid()).thenReturn("vol-uuid");
        when(volume.getTO()).thenReturn(to);
        VolumeVO volumeVO = mock(VolumeVO.class);
        when(volumeDao.findById(100L)).thenReturn(volumeVO);
        when(volumeDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);
        return volume;
    }

    private CloudStackVolume nfsVolume(VolumeObject volume, FileInfo file) {
        CloudStackVolume cloudStackVolume = new CloudStackVolume();
        cloudStackVolume.setDatastoreId("1");
        cloudStackVolume.setVolumeInfo(volume);
        cloudStackVolume.setFlexVolumeUuid("flex-uuid");
        cloudStackVolume.setFile(file);
        return cloudStackVolume;
    }

    private static FileInfo fileWithQos() {
        VolumeQosPolicy qos = new VolumeQosPolicy();
        qos.setName("qos-1");
        FileInfo file = new FileInfo();
        file.setQosPolicy(qos);
        return file;
    }

    private static HostVO host(String storageIp, String privateIp) {
        HostVO host = mock(HostVO.class);
        when(host.getStorageIpAddress()).thenReturn(storageIp);
        when(host.getPrivateIpAddress()).thenReturn(privateIp);
        when(host.getName()).thenReturn("host-" + storageIp + "-" + privateIp);
        return host;
    }

    private AccessGroup accessGroup(AccessGroup.HostRuleAction action, HostVO... hosts) {
        AccessGroup group = new AccessGroup();
        group.setStoragePoolId(POOL_ID);
        group.setHostsToConnect(new ArrayList<>(List.of(hosts)));
        group.setHostRuleAction(action);
        return group;
    }

    private static ExportRule.ExportClient client(String match) {
        ExportRule.ExportClient client = new ExportRule.ExportClient();
        client.setMatch(match);
        return client;
    }

    private ExportPolicy policyWithClients(List<ExportRule.ExportClient> clients) {
        ExportRule rule = new ExportRule();
        rule.setClients(clients);
        ExportPolicy policy = new ExportPolicy();
        policy.setName("policy-name");
        policy.setRules(new ArrayList<>(List.of(rule)));
        return policy;
    }

    private void poolHasExportPolicy() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(new HashMap<>(Map.of(
                OntapStorageConstants.EXPORT_POLICY_ID, POLICY_ID,
                OntapStorageConstants.EXPORT_POLICY_NAME, "policy-name")));
    }

    private void poolDetailsForCreate() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(new HashMap<>(Map.of(
                OntapStorageConstants.SVM_NAME, "svm1",
                OntapStorageConstants.VOLUME_UUID, "flex-uuid",
                OntapStorageConstants.VOLUME_NAME, "flexvol")));
    }

    private void exportPolicyIsCreated() {
        ExportPolicy created = new ExportPolicy();
        created.setName("created-policy");
        OntapResponse<ExportPolicy> response = new OntapResponse<>(List.of(created));
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenReturn(response);
    }

    private void assertExportPolicyWasCreated(int times) {
        ArgumentCaptor<ExportPolicy> policy = ArgumentCaptor.forClass(ExportPolicy.class);
        verify(nasFeignClient, times(times)).createExportPolicy(anyString(), policy.capture());
        for (ExportPolicy sent : policy.getAllValues()) {
            assertEquals("cs-svm1-flexvol", sent.getName());
            assertNotNull(sent.getSvm());
            assertEquals("svm1", sent.getSvm().getName());
            assertEquals("10.1.1.1/32", sent.getRules().get(0).getClients().get(0).getMatch());
        }
    }

    @SuppressWarnings("unchecked")
    private void assertExportPolicyWasLookedUp(int times) {
        ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
        verify(nasFeignClient, times(times)).getExportPolicyResponse(anyString(), query.capture());
        for (Map<String, Object> value : query.getAllValues()) {
            assertEquals("cs-svm1-flexvol", value.get(OntapStorageConstants.NAME));
        }
    }

    private static Volume exportPolicyUpdate(String policyName) {
        return argThat(update -> update != null
                && update.getNas() != null
                && update.getNas().getExportPolicy() != null
                && policyName.equals(update.getNas().getExportPolicy().getName()));
    }

    private static Job job(String state, String message) {
        Job job = new Job();
        job.setUuid("job-1");
        job.setState(state);
        job.setMessage(message);
        return job;
    }

    private static JobResponse jobResponse() {
        JobResponse response = new JobResponse();
        Job job = new Job();
        job.setUuid("job-1");
        response.setJob(job);
        return response;
    }

    // ------------------------------------------------- createCloudStackVolume

    @Test
    public void setOntapStorageReplacesTheCredentialsUsedForAuthHeader() {
        String before = strategy.getAuthHeader();
        strategy.setOntapStorage(new OntapStorage("other", "secret", "10.0.0.2", "svm2", 100L, ProtocolType.NFS3));

        assertNotEquals(before, strategy.getAuthHeader());
    }

    @Test
    public void createVolumeWithoutQosFileDoesNotTouchOntapFiles() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(new Answer(null, true, "ok"));

        CloudStackVolume request = nfsVolume(volume, new FileInfo());
        CloudStackVolume result = strategy.createCloudStackVolume(request);

        assertSame(request, result);
        verify(nasFeignClient, never()).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
    }

    @Test
    public void createVolumeFailsWithDefaultMessageWhenHostGivesNoAnswer() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(null);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createCloudStackVolume(nfsVolume(volume, new FileInfo())));

        assertEquals("Failed to create qcow2 on KVM host", thrown.getMessage());
    }

    @Test
    public void createVolumeFailsWithHostDetailsWhenHostRejectsTheCommand() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(new Answer(null, false, "disk full"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createCloudStackVolume(nfsVolume(volume, new FileInfo())));

        assertEquals("disk full", thrown.getMessage());
    }

    @Test
    public void createVolumeFailsWhenNoEndpointIsAvailable() {
        VolumeObject volume = volumeObject();
        when(epSelector.select(volume)).thenReturn(null);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createCloudStackVolume(nfsVolume(volume, new FileInfo())));

        assertTrue(thrown.getMessage().contains("No remote endpoint to send CreateObjectCommand"), thrown.getMessage());
    }

    @Test
    public void createVolumeReportsEndpointExceptionsAsFailedAnswer() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenThrow(new IllegalStateException("agent offline"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createCloudStackVolume(nfsVolume(volume, new FileInfo())));

        assertTrue(thrown.getMessage().contains("agent offline"), thrown.getMessage());
    }

    @Test
    public void createVolumeFailsWhenVolumeRecordIsMissing() {
        VolumeObject volume = volumeObject();
        when(volumeDao.findById(100L)).thenReturn(null);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createCloudStackVolume(nfsVolume(volume, new FileInfo())));

        assertTrue(thrown.getMessage().contains("Volume not found with id: 100"), thrown.getMessage());
        verify(epSelector, never()).select(any(VolumeObject.class));
    }

    @Test
    public void createVolumeWrapsUnexpectedFailuresInCloudRuntimeException() {
        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> strategy.createCloudStackVolume(null));

        assertTrue(thrown.getCause() instanceof NullPointerException);
    }

    @Test
    public void createVolumeLogsFailedLeftoverCleanupAndStillRethrowsQosError() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(new Answer(null, true, "ok"));
        when(endPoint.sendMessage(any(DeleteCommand.class))).thenReturn(new Answer(null, false, "cannot delete"));
        doThrow(new IllegalStateException("qos rejected")).when(nasFeignClient)
                .updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createCloudStackVolume(nfsVolume(volume, fileWithQos())));

        assertTrue(thrown.getMessage().contains("Failed to update NFS volume file"), thrown.getMessage());
        verify(endPoint).sendMessage(any(DeleteCommand.class));
    }

    @Test
    public void createVolumeToleratesNullCleanupAnswerAfterQosFailure() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(new Answer(null, true, "ok"));
        when(endPoint.sendMessage(any(DeleteCommand.class))).thenReturn(null);
        doThrow(new IllegalStateException("qos rejected")).when(nasFeignClient)
                .updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));

        assertThrows(CloudRuntimeException.class, () -> strategy.createCloudStackVolume(nfsVolume(volume, fileWithQos())));

        verify(endPoint).sendMessage(any(DeleteCommand.class));
    }

    // ------------------------------------------------- updateCloudStackVolume

    @Test
    public void updateVolumeRejectsIncompleteRequests() {
        VolumeObject volume = volumeObject();
        assertThrows(CloudRuntimeException.class, () -> strategy.updateCloudStackVolume(null));

        CloudStackVolume noVolumeInfo = nfsVolume(volume, fileWithQos());
        noVolumeInfo.setVolumeInfo(null);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateCloudStackVolume(noVolumeInfo));

        CloudStackVolume noFlexVol = nfsVolume(volume, fileWithQos());
        noFlexVol.setFlexVolumeUuid(null);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateCloudStackVolume(noFlexVol));

        assertThrows(CloudRuntimeException.class, () -> strategy.updateCloudStackVolume(nfsVolume(volume, null)));
        verify(nasFeignClient, never()).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
    }

    @Test
    public void updateVolumeWithoutQosPolicyIsANoOp() {
        CloudStackVolume request = nfsVolume(volumeObject(), new FileInfo());

        assertSame(request, strategy.updateCloudStackVolume(request));
        verify(nasFeignClient, never()).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
    }

    @Test
    public void updateVolumeWrapsFeignAndGenericFailures() {
        CloudStackVolume request = nfsVolume(volumeObject(), fileWithQos());
        FeignException feign = feignException(500, "ontap down");
        doThrow(feign).when(nasFeignClient).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> strategy.updateCloudStackVolume(request));
        assertTrue(feignFailure.getMessage().contains("ontap down"), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(nasFeignClient)
                .updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
        CloudRuntimeException genericFailure = assertThrows(CloudRuntimeException.class, () -> strategy.updateCloudStackVolume(request));
        assertTrue(genericFailure.getMessage().contains("boom"), genericFailure.getMessage());
    }

    // ------------------------------------------------- deleteCloudStackVolume

    @Test
    public void deleteVolumeSucceedsWhenHostDeletesTheFile() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(DeleteCommand.class))).thenReturn(new Answer(null, true, "ok"));

        strategy.deleteCloudStackVolume(nfsVolume(volume, new FileInfo()));

        verify(endPoint).sendMessage(any(DeleteCommand.class));
    }

    @Test
    public void deleteVolumeFailsForNullAnswerFailedAnswerAndMissingEndpoint() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);

        when(endPoint.sendMessage(any(DeleteCommand.class))).thenReturn(null);
        CloudRuntimeException noAnswer = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteCloudStackVolume(nfsVolume(volume, new FileInfo())));
        assertTrue(noAnswer.getMessage().contains("Failed to delete qcow2 on KVM host"), noAnswer.getMessage());

        when(endPoint.sendMessage(any(DeleteCommand.class))).thenReturn(new Answer(null, false, "busy"));
        CloudRuntimeException failed = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteCloudStackVolume(nfsVolume(volume, new FileInfo())));
        assertTrue(failed.getMessage().contains("busy"), failed.getMessage());

        when(epSelector.select(volume)).thenReturn(null);
        CloudRuntimeException noEndpoint = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteCloudStackVolume(nfsVolume(volume, new FileInfo())));
        assertTrue(noEndpoint.getMessage().contains("No remote endpoint to send DeleteCommand"), noEndpoint.getMessage());
    }

    @Test
    public void deleteVolumeReportsEndpointExceptions() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(DeleteCommand.class))).thenThrow(new IllegalStateException("agent offline"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteCloudStackVolume(nfsVolume(volume, new FileInfo())));

        assertTrue(thrown.getMessage().contains("agent offline"), thrown.getMessage());
    }

    // ------------------------------------------------- cloneCloudStackVolume

    private CloudStackVolume cloneRequest(String sourcePath, String destinationPath, String datastoreId) {
        FileInfo file = new FileInfo();
        file.setPath(sourcePath);
        CloudStackVolume request = nfsVolume(volumeObject(), file);
        request.setDestinationPath(destinationPath);
        request.setDatastoreId(datastoreId);
        return request;
    }

    @Test
    public void cloneRejectsInvalidRequests() {
        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(null));

        CloudStackVolume noFile = cloneRequest("src", "dst", "1");
        noFile.setFile(null);
        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(noFile));

        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(cloneRequest(null, "dst", "1")));
        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(cloneRequest("src", null, "1")));

        CloudRuntimeException noDatastore = assertThrows(CloudRuntimeException.class,
                () -> strategy.cloneCloudStackVolume(cloneRequest("src", "dst", null)));
        assertTrue(noDatastore.getMessage().contains("no datastore id"), noDatastore.getMessage());
        verify(nasFeignClient, never()).cloneFile(anyString(), any());
    }

    @Test
    public void cloneFailsWhenFlexVolumeUuidIsMissingOrEmpty() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(new HashMap<>());
        CloudRuntimeException missing = assertThrows(CloudRuntimeException.class,
                () -> strategy.cloneCloudStackVolume(cloneRequest("src", "dst", "1")));
        assertTrue(missing.getMessage().contains("FlexVolume uuid is missing"), missing.getMessage());

        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(new HashMap<>(Map.of(OntapStorageConstants.VOLUME_UUID, "")));
        assertThrows(CloudRuntimeException.class, () -> strategy.cloneCloudStackVolume(cloneRequest("src", "dst", "1")));
    }

    @Test
    public void cloneWrapsFeignAndGenericErrors() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(new HashMap<>(Map.of(
                OntapStorageConstants.VOLUME_UUID, "flex-uuid", OntapStorageConstants.VOLUME_NAME, "flexvol")));
        FeignException feign = feignException(500, "clone refused");
        when(nasFeignClient.cloneFile(anyString(), any())).thenThrow(feign);
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class,
                () -> strategy.cloneCloudStackVolume(cloneRequest("src", "dst", "1")));
        assertTrue(feignFailure.getMessage().startsWith("Failed to clone file: "), feignFailure.getMessage());
        assertTrue(feignFailure.getMessage().contains("clone refused"), feignFailure.getMessage());

        doThrow(new IllegalStateException("npe-ish")).when(nasFeignClient).cloneFile(anyString(), any());
        CloudRuntimeException genericFailure = assertThrows(CloudRuntimeException.class,
                () -> strategy.cloneCloudStackVolume(cloneRequest("src", "dst", "1")));
        assertTrue(genericFailure.getMessage().contains("npe-ish"), genericFailure.getMessage());

        ArgumentCaptor<FileCloneRequest> request = ArgumentCaptor.forClass(FileCloneRequest.class);
        verify(nasFeignClient, times(2)).cloneFile(anyString(), request.capture());
        for (FileCloneRequest sent : request.getAllValues()) {
            assertEquals("flex-uuid", sent.getVolume().getUuid());
            assertEquals("flexvol", sent.getVolume().getName());
            assertEquals("src", sent.getSourcePath());
            assertEquals("dst", sent.getDestinationPath());
        }
    }

    // ------------------------------------------------- resizeCloudStackVolume

    @Test
    public void resizeRejectsMissingVolumeAndNonPositiveSize() {
        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(null, 1024L));

        CloudStackVolume noInfo = new CloudStackVolume();
        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(noInfo, 1024L));

        CloudStackVolume valid = nfsVolume(volumeObject(), new FileInfo());
        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(valid, 0L));
        assertThrows(CloudRuntimeException.class, () -> strategy.resizeCloudStackVolume(valid, -5L));
    }

    @Test
    public void resizeWithQosPolicyReappliesQosOnTheFile() {
        VolumeObject volume = volumeObject();
        VolumeVO volumeVO = volumeDao.findById(100L);
        when(volumeVO.getPoolId()).thenReturn(5L);
        when(primaryDataStoreDao.findById(5L)).thenReturn(mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class));
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(com.cloud.agent.api.storage.ResizeVolumeCommand.class))).thenReturn(new Answer(null, true, "ok"));

        strategy.resizeCloudStackVolume(nfsVolume(volume, fileWithQos()), 2048L);

        ArgumentCaptor<FileInfo> updated = ArgumentCaptor.forClass(FileInfo.class);
        verify(nasFeignClient).updateFile(anyString(), eq("flex-uuid"), eq("vol-uuid"), updated.capture());
        assertEquals("qos-1", updated.getValue().getQosPolicy().getName());
    }

    // ------------------------------------------------- getCloudStackVolume / files

    private Map<String, String> fileLookup() {
        return Map.of(OntapStorageConstants.VOLUME_UUID, "flex-uuid", OntapStorageConstants.FILE_PATH, "dir/file.qcow2");
    }

    @Test
    public void getVolumeReturnsFileDetailsFromOntap() {
        FileInfo file = new FileInfo();
        file.setPath("dir/file.qcow2");
        when(nasFeignClient.getFileResponse(anyString(), eq("flex-uuid"), eq("dir/file.qcow2")))
                .thenReturn(new OntapResponse<>(List.of(file)));

        CloudStackVolume result = strategy.getCloudStackVolume(fileLookup());

        assertNotNull(result);
        assertSame(file, result.getFile());
        assertEquals("flex-uuid", result.getFlexVolumeUuid());
    }

    @Test
    public void getVolumeFailsWhenOntapKnowsNoSuchFile() {
        when(nasFeignClient.getFileResponse(anyString(), anyString(), anyString())).thenReturn(null);
        CloudRuntimeException nullResponse = assertThrows(CloudRuntimeException.class, () -> strategy.getCloudStackVolume(fileLookup()));
        assertTrue(nullResponse.getMessage().contains("not found on ONTAP"), nullResponse.getMessage());

        when(nasFeignClient.getFileResponse(anyString(), anyString(), anyString())).thenReturn(new OntapResponse<>(new ArrayList<>()));
        assertThrows(CloudRuntimeException.class, () -> strategy.getCloudStackVolume(fileLookup()));
    }

    @Test
    public void getVolumeWrapsFeignAndGenericFailures() {
        FeignException feign = feignException(500, "ontap down");
        when(nasFeignClient.getFileResponse(anyString(), anyString(), anyString())).thenThrow(feign);
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class, () -> strategy.getCloudStackVolume(fileLookup()));
        assertTrue(feignFailure.getMessage().startsWith("File not found: "), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(nasFeignClient).getFileResponse(anyString(), anyString(), anyString());
        CloudRuntimeException genericFailure = assertThrows(CloudRuntimeException.class, () -> strategy.getCloudStackVolume(fileLookup()));
        assertTrue(genericFailure.getMessage().contains("Failed to get the file: boom"), genericFailure.getMessage());
    }

    @Test
    public void deleteFileByPathDeletesExistingFile() {
        strategy.deleteFileByPath("flex-uuid", "dir/file.qcow2");

        verify(nasFeignClient).deleteFile(anyString(), eq("flex-uuid"), eq("dir/file.qcow2"));
    }

    @Test
    public void deleteFileByPathTreatsMissingFileAsDeleted() {
        FeignException notFound = feignException(404, "gone");
        doThrow(notFound).when(nasFeignClient).deleteFile(anyString(), anyString(), anyString());

        strategy.deleteFileByPath("flex-uuid", "dir/file.qcow2");

        verify(nasFeignClient).deleteFile(anyString(), eq("flex-uuid"), eq("dir/file.qcow2"));
    }

    @Test
    public void deleteFileByPathFailsOnOtherErrors() {
        FeignException serverError = feignException(500, "ontap down");
        doThrow(serverError).when(nasFeignClient).deleteFile(anyString(), eq("flex-uuid"), eq("f"));
        CloudRuntimeException feignFailure = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteFileByPath("flex-uuid", "f"));
        assertTrue(feignFailure.getMessage().startsWith("Failed to delete file: "), feignFailure.getMessage());
        assertTrue(feignFailure.getMessage().contains("ontap down"), feignFailure.getMessage());

        doThrow(new IllegalStateException("boom")).when(nasFeignClient).deleteFile(anyString(), eq("flex-uuid"), eq("f"));
        CloudRuntimeException genericFailure = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteFileByPath("flex-uuid", "f"));
        assertTrue(genericFailure.getMessage().contains("boom"), genericFailure.getMessage());
        verify(nasFeignClient, times(2)).deleteFile(anyString(), eq("flex-uuid"), eq("f"));
    }

    // ------------------------------------------------- unused access-group hooks

    @Test
    public void unsupportedAccessHooksReturnNothing() {
        assertNull(strategy.getAccessGroup(Map.of()));
        assertNull(strategy.enableLogicalAccess(Map.of()));
        assertNull(strategy.getLogicalAccess(Map.of()));
        strategy.disableLogicalAccess(Map.of());
    }

    // ------------------------------------------------- createAccessGroup failures

    @Test
    public void createAccessGroupFailsWhenPolicyCannotBeVerifiedOnOntap() {
        poolDetailsForCreate();
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenReturn(null);
        AccessGroup group = accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> strategy.createAccessGroup(group));

        assertTrue(thrown.getMessage().contains(
                "Export policy cs-svm1-flexvol was not created on ONTAP"), thrown.getMessage());
        verify(volumeFeignClient, never()).updateVolume(anyString(), anyString(), any());
        assertExportPolicyWasCreated(1);
        assertExportPolicyWasLookedUp(1);
    }

    @Test
    public void createAccessGroupFailsWhenVerificationCallFailsOnOntap() {
        poolDetailsForCreate();
        FeignException feign = feignException(500, "lookup failed");
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenThrow(feign);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("Export policy creation verification failed"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("lookup failed"), thrown.getMessage());
        assertExportPolicyWasCreated(1);
        assertExportPolicyWasLookedUp(1);
    }

    @Test
    public void createAccessGroupFailsWhenOntapRejectsPolicyCreation() {
        poolDetailsForCreate();
        FeignException feign = feignException(400, "bad policy");
        doThrow(feign).when(nasFeignClient).createExportPolicy(anyString(), any(ExportPolicy.class));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("Failed to create export policy: "), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("bad policy"), thrown.getMessage());
        assertExportPolicyWasCreated(1);
        verify(nasFeignClient, never()).getExportPolicyResponse(anyString(), anyMap());
    }

    @Test
    public void createAccessGroupFailsWhenAttachingPolicyToVolumeReturnsNoJob() {
        poolDetailsForCreate();
        exportPolicyIsCreated();
        when(volumeFeignClient.updateVolume(anyString(), anyString(), any())).thenReturn(null);
        AccessGroup group = accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null));
        CloudRuntimeException noResponse = assertThrows(CloudRuntimeException.class, () -> strategy.createAccessGroup(group));
        assertTrue(noResponse.getMessage().contains("Failed to attach policy created-policyto volume flex-uuid"), noResponse.getMessage());

        when(volumeFeignClient.updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"))).thenReturn(new JobResponse());
        CloudRuntimeException emptyJob = assertThrows(CloudRuntimeException.class, () -> strategy.createAccessGroup(group));
        assertTrue(emptyJob.getMessage().contains("Failed to attach policy created-policyto volume flex-uuid"), emptyJob.getMessage());
        verify(volumeFeignClient, times(2)).updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"));
        assertExportPolicyWasCreated(2);
        assertExportPolicyWasLookedUp(2);
        verify(storagePoolDetailsDao, never()).addDetail(anyLong(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    public void createAccessGroupFailsWhenAttachJobFails() {
        poolDetailsForCreate();
        exportPolicyIsCreated();
        when(volumeFeignClient.updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"))).thenReturn(jobResponse());
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-1"))).thenReturn(job(OntapStorageConstants.JOB_FAILURE, "volume busy"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("Job to update volume flex-uuid failed with error: volume busy"), thrown.getMessage());
        verify(volumeFeignClient).updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"));
        verify(jobFeignClient).getJobByUUID(anyString(), eq("job-1"));
        assertExportPolicyWasCreated(1);
        assertExportPolicyWasLookedUp(1);
    }

    @Test
    public void createAccessGroupFailsWhenJobStatusCannotBeFetched() {
        poolDetailsForCreate();
        exportPolicyIsCreated();
        when(volumeFeignClient.updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"))).thenReturn(jobResponse());
        FeignException clientException = feignException(403, "forbidden");
        assertTrue(clientException instanceof FeignException.FeignClientException);
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-1"))).thenThrow(clientException);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("Failed to fetch job status"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("forbidden"), thrown.getMessage());
        verify(volumeFeignClient).updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"));
        verify(jobFeignClient).getJobByUUID(anyString(), eq("job-1"));
        assertExportPolicyWasCreated(1);
        assertExportPolicyWasLookedUp(1);
    }

    @Test
    public void createAccessGroupRetriesWhenJobIsNotYetVisibleThenReportsItsFailure() {
        poolDetailsForCreate();
        exportPolicyIsCreated();
        when(volumeFeignClient.updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"))).thenReturn(jobResponse());
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-1")))
                .thenReturn(null)
                .thenReturn(job(OntapStorageConstants.JOB_FAILURE, "gave up"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.createAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("Job to update volume flex-uuid failed with error: gave up"), thrown.getMessage());
        verify(volumeFeignClient).updateVolume(anyString(), eq("flex-uuid"), exportPolicyUpdate("created-policy"));
        verify(jobFeignClient, times(2)).getJobByUUID(anyString(), eq("job-1"));
        assertExportPolicyWasCreated(1);
        assertExportPolicyWasLookedUp(1);
    }

    // ------------------------------------------------- createExportPolicyRequest

    private ExportPolicy buildExportPolicyRequest(AccessGroup group) throws Exception {
        Method method = UnifiedNASStrategy.class.getDeclaredMethod("createExportPolicyRequest",
                AccessGroup.class, String.class, String.class);
        method.setAccessible(true);
        return (ExportPolicy) method.invoke(strategy, group, "svm1", "flexvol");
    }

    @Test
    public void exportPolicyRequestUsesStorageIpThenPrivateIpForEachHost() throws Exception {
        AccessGroup group = accessGroup(AccessGroup.HostRuleAction.ADD,
                host(" 10.1.1.1 ", "192.168.0.1"),
                host("  ", "192.168.0.2"),
                host(null, " 192.168.0.3 "));

        ExportPolicy policy = buildExportPolicyRequest(group);

        List<String> matches = new ArrayList<>();
        for (ExportRule.ExportClient client : policy.getRules().get(0).getClients()) {
            matches.add(client.getMatch());
        }
        assertEquals(List.of("10.1.1.1/32", "192.168.0.2/32", "192.168.0.3/32"), matches);
        assertEquals("svm1", policy.getSvm().getName());
        assertEquals(List.of(ExportRule.ProtocolsEnum.NFS3), policy.getRules().get(0).getProtocols());
    }

    // ------------------------------------------------- deleteAccessGroup

    @Test
    public void deleteAccessGroupDeletesThePolicyRecordedOnThePool() {
        poolHasExportPolicy();

        strategy.deleteAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null)));

        verify(nasFeignClient).deleteExportPolicyById(anyString(), eq(POLICY_ID));
    }

    @Test
    public void deleteAccessGroupRejectsNullGroup() {
        assertThrows(CloudRuntimeException.class, () -> strategy.deleteAccessGroup(null));
    }

    @Test
    public void deleteAccessGroupTreatsPolicyAlreadyRemovedAsSuccess() {
        poolHasExportPolicy();
        FeignException notFound = feignException(404, "policy gone");
        doThrow(notFound).when(nasFeignClient).deleteExportPolicyById(anyString(), anyString());

        strategy.deleteAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null)));

        verify(nasFeignClient).deleteExportPolicyById(anyString(), eq(POLICY_ID));
    }

    @Test
    public void deleteAccessGroupFailsWhenOntapReportsOtherErrors() {
        poolHasExportPolicy();
        FeignException serverError = feignException(500, "ontap down");
        doThrow(serverError).when(nasFeignClient).deleteExportPolicyById(anyString(), anyString());

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("Failed to delete export policy"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("ontap down"), thrown.getMessage());
        verify(nasFeignClient).deleteExportPolicyById(anyString(), eq(POLICY_ID));
    }

    @Test
    public void deleteAccessGroupFailsWhenPoolDetailsCannotBeRead() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenThrow(new IllegalStateException("db down"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.deleteAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("db down"), thrown.getMessage());
    }

    // ------------------------------------------------- updateAccessGroup validation

    @Test
    public void updateAccessGroupRejectsInvalidInput() {
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(null));

        AccessGroup noPool = accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null));
        noPool.setStoragePoolId(null);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(noPool));

        AccessGroup nullHosts = accessGroup(AccessGroup.HostRuleAction.ADD);
        nullHosts.setHostsToConnect(null);
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(nullHosts));

        assertThrows(CloudRuntimeException.class,
                () -> strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD)));
    }

    @Test
    public void updateAccessGroupFailsWhenPoolHasNoDetailsOrNoPolicyId() {
        AccessGroup group = accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null));

        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(null);
        CloudRuntimeException nullDetails = assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(group));
        assertTrue(nullDetails.getMessage().contains("No storage pool poolDetails found"), nullDetails.getMessage());

        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(new HashMap<>());
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(group));

        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(new HashMap<>(Map.of(OntapStorageConstants.EXPORT_POLICY_ID, "")));
        CloudRuntimeException noPolicy = assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(group));
        assertTrue(noPolicy.getMessage().contains("No export policy found"), noPolicy.getMessage());
    }

    @Test
    public void updateAccessGroupFailsWhenPolicyIsMissingOrHasNoRules() {
        poolHasExportPolicy();
        AccessGroup group = accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null));

        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(null);
        CloudRuntimeException missing = assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(group));
        assertTrue(missing.getMessage().contains("Failed to fetch existing export policy"), missing.getMessage());

        ExportPolicy noRules = new ExportPolicy();
        noRules.setName("policy-name");
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(noRules);
        CloudRuntimeException emptyRules = assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(group));
        assertTrue(emptyRules.getMessage().contains("has no rules"), emptyRules.getMessage());

        noRules.setRules(new ArrayList<>());
        assertThrows(CloudRuntimeException.class, () -> strategy.updateAccessGroup(group));
    }

    // ------------------------------------------------- updateAccessGroup behaviour

    @Test
    public void updateAccessGroupSkipsOntapCallWhenNoHostHasAnIp() {
        poolHasExportPolicy();
        ExportPolicy existing = policyWithClients(new ArrayList<>());
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        AccessGroup result = strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD,
                host(null, null), host("   ", "  ")));

        assertSame(existing, result.getPolicy());
        verify(nasFeignClient, never()).updateExportPolicy(anyString(), anyString(), any(ExportPolicy.class));
    }

    @Test
    public void updateAccessGroupAddsNewHostsToRuleWithoutClients() {
        poolHasExportPolicy();
        ExportPolicy existing = policyWithClients(null);
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        AccessGroup result = strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null)));

        assertEquals("10.1.1.1/32", existing.getRules().get(0).getClients().get(0).getMatch());
        assertSame(existing, result.getPolicy());
        verify(nasFeignClient).updateExportPolicy(anyString(), eq(POLICY_ID), any(ExportPolicy.class));
    }

    @Test
    public void updateAccessGroupAddIgnoresMalformedClientsAndSkipsDuplicates() {
        poolHasExportPolicy();
        List<ExportRule.ExportClient> clients = new ArrayList<>();
        clients.add(null);
        clients.add(new ExportRule.ExportClient());
        clients.add(client("10.1.1.1/32"));
        ExportPolicy existing = policyWithClients(clients);
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null), host("10.1.1.2", null)));

        assertEquals(4, clients.size());
        assertEquals("10.1.1.2/32", clients.get(3).getMatch());
        verify(nasFeignClient).updateExportPolicy(anyString(), eq(POLICY_ID), any(ExportPolicy.class));
    }

    @Test
    public void updateAccessGroupAddOfAlreadyPresentHostsMakesNoOntapCall() {
        poolHasExportPolicy();
        ExportPolicy existing = policyWithClients(new ArrayList<>(List.of(client("10.1.1.1/32"))));
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        AccessGroup result = strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null)));

        assertSame(existing, result.getPolicy());
        verify(nasFeignClient, never()).updateExportPolicy(anyString(), anyString(), any(ExportPolicy.class));
    }

    @Test
    public void updateAccessGroupRemoveDropsMatchingHostsOnly() {
        poolHasExportPolicy();
        List<ExportRule.ExportClient> clients = new ArrayList<>();
        clients.add(null);
        clients.add(new ExportRule.ExportClient());
        clients.add(client("10.1.1.1/32"));
        clients.add(client("10.1.1.9/32"));
        ExportPolicy existing = policyWithClients(clients);
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.REMOVE, host("10.1.1.1", null)));

        assertEquals(3, clients.size());
        assertTrue(clients.stream().noneMatch(c -> c != null && "10.1.1.1/32".equals(c.getMatch())));
        verify(nasFeignClient).updateExportPolicy(anyString(), eq(POLICY_ID), any(ExportPolicy.class));
    }

    @Test
    public void updateAccessGroupRemoveOfUnknownHostMakesNoOntapCall() {
        poolHasExportPolicy();
        ExportPolicy existing = policyWithClients(new ArrayList<>(List.of(client("10.1.1.9/32"))));
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        AccessGroup result = strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.REMOVE, host("10.1.1.1", null)));

        assertSame(existing, result.getPolicy());
        verify(nasFeignClient, never()).updateExportPolicy(anyString(), anyString(), any(ExportPolicy.class));
    }

    @Test
    public void updateAccessGroupUsesPrivateIpWhenStorageIpIsBlank() {
        poolHasExportPolicy();
        ExportPolicy existing = policyWithClients(new ArrayList<>());
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("  ", "192.168.5.5")));

        ArgumentCaptor<ExportPolicy> sent = ArgumentCaptor.forClass(ExportPolicy.class);
        verify(nasFeignClient).updateExportPolicy(anyString(), eq(POLICY_ID), sent.capture());
        assertEquals("192.168.5.5/32", sent.getValue().getRules().get(0).getClients().get(0).getMatch());
    }

    @Test
    public void updateAccessGroupWrapsOntapUpdateFailure() {
        poolHasExportPolicy();
        ExportPolicy existing = policyWithClients(new ArrayList<>());
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);
        doThrow(new IllegalStateException("patch failed")).when(nasFeignClient)
                .updateExportPolicy(anyString(), anyString(), any(ExportPolicy.class));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null))));

        assertTrue(thrown.getMessage().contains("Failed to update export policy for NFS host connection"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("patch failed"), thrown.getMessage());
        ArgumentCaptor<ExportPolicy> sent = ArgumentCaptor.forClass(ExportPolicy.class);
        verify(nasFeignClient).updateExportPolicy(anyString(), eq(POLICY_ID), sent.capture());
        assertEquals("10.1.1.1/32", sent.getValue().getRules().get(0).getClients().get(0).getMatch());
    }

    // ------------------------------------------------- revertSnapshotForCloudStackVolume

    @Test
    public void revertSnapshotAddsLeadingSlashAndCallsCliRestore() {
        JobResponse expected = new JobResponse();
        when(snapshotFeignClient.restoreFileFromSnapshotCli(anyString(), any())).thenReturn(expected);

        JobResponse actual = strategy.revertSnapshotForCloudStackVolume("snap1", "flex-uuid", "snap-uuid", "dir/file.qcow2", null, "flexvol");

        assertSame(expected, actual);
        verify(snapshotFeignClient).restoreFileFromSnapshotCli(anyString(), org.mockito.ArgumentMatchers.argThat(
                request -> "/dir/file.qcow2".equals(request.getPath()) && "svm1".equals(request.getVserver())
                        && "flexvol".equals(request.getVolume()) && "snap1".equals(request.getSnapshot())));
    }

    @Test
    public void revertSnapshotKeepsExistingLeadingSlash() {
        strategy.revertSnapshotForCloudStackVolume("snap1", "flex-uuid", "snap-uuid", "/file.qcow2", null, "flexvol");

        verify(snapshotFeignClient).restoreFileFromSnapshotCli(anyString(), org.mockito.ArgumentMatchers.argThat(
                request -> "/file.qcow2".equals(request.getPath()) && "svm1".equals(request.getVserver())
                        && "flexvol".equals(request.getVolume()) && "snap1".equals(request.getSnapshot())));
    }

    @Test
    public void revertSnapshotRejectsMissingArguments() {
        for (String[] args : new String[][] {
                {null, "file", "flexvol"}, {"", "file", "flexvol"},
                {"snap", null, "flexvol"}, {"snap", "", "flexvol"},
                {"snap", "file", null}, {"snap", "file", ""}}) {
            assertThrows(CloudRuntimeException.class, () ->
                    strategy.revertSnapshotForCloudStackVolume(args[0], "flex-uuid", "snap-uuid", args[1], null, args[2]));
        }
        verify(snapshotFeignClient, never()).restoreFileFromSnapshotCli(anyString(), any());
    }

    // ===================================================================================
    // Assertions on the exact requests the NAS strategy sends to ONTAP and persists
    // ===================================================================================

    private static ExportPolicy createdPolicy(int id, String name) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"id\":" + id + ",\"name\":\"" + name + "\"}", ExportPolicy.class);
    }

    @Test
    public void createAccessGroupAttachesPolicyToTheFlexVolAndRecordsItOnThePool() throws Exception {
        poolDetailsForCreate();
        ExportPolicy created = createdPolicy(9, "created-policy");
        when(nasFeignClient.getExportPolicyResponse(anyString(), anyMap())).thenReturn(new OntapResponse<>(List.of(created)));
        when(volumeFeignClient.updateVolume(anyString(), anyString(), any())).thenReturn(jobResponse());
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-1"))).thenReturn(job(OntapStorageConstants.JOB_SUCCESS, "done"));
        AccessGroup group = accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null));

        AccessGroup result = strategy.createAccessGroup(group);

        org.mockito.ArgumentCaptor<org.apache.cloudstack.storage.feign.model.Volume> volumeUpdate =
                org.mockito.ArgumentCaptor.forClass(org.apache.cloudstack.storage.feign.model.Volume.class);
        verify(volumeFeignClient).updateVolume(anyString(), eq("flex-uuid"), volumeUpdate.capture());
        assertEquals("created-policy", volumeUpdate.getValue().getNas().getExportPolicy().getName());
        verify(storagePoolDetailsDao).addDetail(POOL_ID, OntapStorageConstants.EXPORT_POLICY_ID, "9", true);
        verify(storagePoolDetailsDao).addDetail(POOL_ID, OntapStorageConstants.EXPORT_POLICY_NAME, "created-policy", true);
        assertSame(group, result);
        assertEquals(org.apache.cloudstack.storage.utils.OntapStorageUtils.generateExportPolicyName("svm1", "flexvol"),
                result.getPolicy().getName());
        assertEquals("10.1.1.1/32", result.getPolicy().getRules().get(0).getClients().get(0).getMatch());
        org.mockito.ArgumentCaptor<ExportPolicy> sent = org.mockito.ArgumentCaptor.forClass(ExportPolicy.class);
        verify(nasFeignClient).createExportPolicy(anyString(), sent.capture());
        assertSame(result.getPolicy(), sent.getValue());
    }

    @Test
    public void exportPolicyRequestGrantsSysAuthenticationForReadWriteAndSuperuser() throws Exception {
        ExportPolicy policy = buildExportPolicyRequest(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null)));

        ExportRule rule = policy.getRules().get(0);
        assertEquals(List.of("sys"), rule.getRoRule());
        assertEquals(List.of("sys"), rule.getRwRule());
        assertEquals(List.of("sys"), rule.getSuperuser());
        assertEquals(org.apache.cloudstack.storage.utils.OntapStorageUtils.generateExportPolicyName("svm1", "flexvol"), policy.getName());
    }

    @Test
    public void createVolumeWithoutAnyFileDoesNotTouchOntapFiles() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(new Answer(null, true, "ok"));

        CloudStackVolume request = nfsVolume(volume, null);
        assertSame(request, strategy.createCloudStackVolume(request));

        verify(nasFeignClient, never()).updateFile(anyString(), anyString(), anyString(), any(FileInfo.class));
    }

    @Test
    public void createVolumeWithQosFileSendsTheQosPolicyForTheVolumeFile() {
        VolumeObject volume = volumeObject();
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(CreateObjectCommand.class))).thenReturn(new Answer(null, true, "ok"));
        FileInfo file = fileWithQos();

        strategy.createCloudStackVolume(nfsVolume(volume, file));

        verify(nasFeignClient).updateFile(anyString(), eq("flex-uuid"), eq("vol-uuid"),
                org.mockito.ArgumentMatchers.argThat(patch -> patch.getQosPolicy() == file.getQosPolicy()));
    }

    @Test
    public void cloneSendsRequestPollsJobPersistsVolumeRowAndReturnsTheClone() {
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(new HashMap<>(Map.of(
                OntapStorageConstants.VOLUME_UUID, "flex-uuid", OntapStorageConstants.VOLUME_NAME, "flexvol")));
        JobResponse job = jobResponse();
        when(nasFeignClient.cloneFile(anyString(), any())).thenReturn(job);
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-1")))
                .thenReturn(job(OntapStorageConstants.JOB_SUCCESS, "cloned"));
        UnifiedNASStrategy spied = org.mockito.Mockito.spy(strategy);
        CloudStackVolume request = cloneRequest("src.qcow2", "dst.qcow2", "1");
        VolumeVO volumeRow = volumeDao.findById(100L);

        CloudStackVolume clone = spied.cloneCloudStackVolume(request);

        verify(spied).pollJobIfPresent(job, "clone file [src.qcow2] to [dst.qcow2]");
        verify(jobFeignClient).getJobByUUID(anyString(), eq("job-1"));
        verify(nasFeignClient).cloneFile(anyString(), org.mockito.ArgumentMatchers.argThat(body ->
                "flex-uuid".equals(body.getVolume().getUuid()) && "flexvol".equals(body.getVolume().getName())
                        && "src.qcow2".equals(body.getSourcePath()) && "dst.qcow2".equals(body.getDestinationPath())));
        verify(volumeRow).setPoolType(Storage.StoragePoolType.NetworkFilesystem);
        verify(volumeRow).setPoolId(1L);
        verify(volumeRow).setPath("vol-uuid");
        verify(volumeDao).update(volumeRow.getId(), volumeRow);
        assertEquals("dst.qcow2", clone.getFile().getPath());
        assertEquals("1", clone.getDatastoreId());
        assertSame(request.getVolumeInfo(), clone.getVolumeInfo());
    }

    @Test
    public void volumeMetadataUpdateReturnsTheVolumeUuidUsedAsPath() throws Exception {
        VolumeObject volume = volumeObject();
        VolumeVO volumeRow = volumeDao.findById(100L);
        when(volumeRow.getId()).thenReturn(100L);
        Method method = UnifiedNASStrategy.class.getDeclaredMethod("updateCloudStackVolumeMetadata", String.class,
                org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class);
        method.setAccessible(true);

        assertEquals("vol-uuid", method.invoke(strategy, "1", volume));

        verify(volumeRow).setPoolType(Storage.StoragePoolType.NetworkFilesystem);
        verify(volumeRow).setPoolId(1L);
        verify(volumeRow).setPath("vol-uuid");
        verify(volumeDao).update(100L, volumeRow);
    }

    @Test
    public void resizeFailsWithTheEndpointMessageWhenNoHostIsAvailable() {
        VolumeObject volume = volumeObject();
        VolumeVO volumeVO = volumeDao.findById(100L);
        when(volumeVO.getPoolId()).thenReturn(5L);
        when(primaryDataStoreDao.findById(5L)).thenReturn(mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class));
        when(epSelector.select(volume)).thenReturn(null);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(nfsVolume(volume, new FileInfo()), 2048L));

        assertTrue(thrown.getMessage().contains("No remote endpoint to send ResizeVolumeCommand"), thrown.getMessage());
    }

    @Test
    public void resizeRejectsZeroSizeWithTheSizeInTheMessageButAcceptsOneByte() {
        VolumeObject volume = volumeObject();
        CloudRuntimeException zero = assertThrows(CloudRuntimeException.class,
                () -> strategy.resizeCloudStackVolume(nfsVolume(volume, new FileInfo()), 0L));
        assertTrue(zero.getMessage().contains("invalid size 0"), zero.getMessage());

        VolumeVO volumeVO = volumeDao.findById(100L);
        when(volumeVO.getPoolId()).thenReturn(5L);
        when(primaryDataStoreDao.findById(5L)).thenReturn(mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class));
        EndPoint endPoint = mock(EndPoint.class);
        when(epSelector.select(volume)).thenReturn(endPoint);
        when(endPoint.sendMessage(any(com.cloud.agent.api.storage.ResizeVolumeCommand.class))).thenReturn(new Answer(null, true, "ok"));

        strategy.resizeCloudStackVolume(nfsVolume(volume, new FileInfo()), 1L);

        verify(endPoint).sendMessage(org.mockito.ArgumentMatchers.argThat(command ->
                command instanceof com.cloud.agent.api.storage.ResizeVolumeCommand
                        && ((com.cloud.agent.api.storage.ResizeVolumeCommand) command).getNewSize() == 1L));
    }

    @Test
    public void updateAccessGroupPatchesThePolicyWithTheUpdatedRules() {
        poolHasExportPolicy();
        ExportPolicy existing = policyWithClients(new ArrayList<>(List.of(client("10.1.1.9/32"))));
        when(nasFeignClient.getExportPolicyById(anyString(), eq(POLICY_ID))).thenReturn(existing);

        strategy.updateAccessGroup(accessGroup(AccessGroup.HostRuleAction.ADD, host("10.1.1.1", null)));

        org.mockito.ArgumentCaptor<ExportPolicy> patch = org.mockito.ArgumentCaptor.forClass(ExportPolicy.class);
        verify(nasFeignClient).updateExportPolicy(anyString(), eq(POLICY_ID), patch.capture());
        assertEquals(1, patch.getValue().getRules().size());
        List<String> matches = new ArrayList<>();
        for (ExportRule.ExportClient c : patch.getValue().getRules().get(0).getClients()) {
            matches.add(c.getMatch());
        }
        assertEquals(List.of("10.1.1.9/32", "10.1.1.1/32"), matches);
    }
}
