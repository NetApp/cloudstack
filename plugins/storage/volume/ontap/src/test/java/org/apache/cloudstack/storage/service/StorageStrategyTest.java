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

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.feign.client.AggregateFeignClient;
import org.apache.cloudstack.storage.feign.client.ClusterFeignClient;
import org.apache.cloudstack.storage.feign.client.JobFeignClient;
import org.apache.cloudstack.storage.feign.client.NetworkFeignClient;
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.client.SnapshotFeignClient;
import org.apache.cloudstack.storage.feign.client.SvmFeignClient;
import org.apache.cloudstack.storage.feign.client.VolumeFeignClient;
import org.apache.cloudstack.storage.feign.model.Aggregate;
import org.apache.cloudstack.storage.feign.model.Cluster;
import org.apache.cloudstack.storage.feign.model.ClusterNode;
import org.apache.cloudstack.storage.feign.model.IpInterface;
import org.apache.cloudstack.storage.feign.model.IscsiService;
import org.apache.cloudstack.storage.feign.model.Job;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cloud.utils.exception.CloudRuntimeException;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Nested;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.Arrays;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.client.EmsFeignClient;
import org.apache.cloudstack.storage.feign.client.NASFeignClient;
import org.apache.cloudstack.storage.feign.client.QosFeignClient;
import org.apache.cloudstack.storage.feign.model.EmsApplicationLog;
import org.apache.cloudstack.storage.feign.model.Version;
import org.apache.cloudstack.storage.feign.model.VolumeQosPolicy;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class StorageStrategyTest {

    @Mock
    private AggregateFeignClient aggregateFeignClient;

    @Mock
    private VolumeFeignClient volumeFeignClient;

    @Mock
    private SvmFeignClient svmFeignClient;

    @Mock
    private JobFeignClient jobFeignClient;

    @Mock
    private NetworkFeignClient networkFeignClient;

    @Mock
    private SANFeignClient sanFeignClient;

    @Mock
    private SnapshotFeignClient snapshotFeignClient;

    @Mock
    private ClusterFeignClient clusterFeignClient;

    private TestableStorageStrategy storageStrategy;

    // Concrete implementation for testing abstract class
    private static class TestableStorageStrategy extends StorageStrategy {
        public TestableStorageStrategy(OntapStorage ontapStorage,
                                       AggregateFeignClient aggregateFeignClient,
                                       VolumeFeignClient volumeFeignClient,
                                       SvmFeignClient svmFeignClient,
                                       JobFeignClient jobFeignClient,
                                       NetworkFeignClient networkFeignClient,
                                       SANFeignClient sanFeignClient,
                                       SnapshotFeignClient snapshotFeignClient,
                                       ClusterFeignClient clusterFeignClient) {
            super(ontapStorage);
            // Use reflection to replace the private Feign client fields with mocked ones
            injectMockedClient("aggregateFeignClient", aggregateFeignClient);
            injectMockedClient("volumeFeignClient", volumeFeignClient);
            injectMockedClient("svmFeignClient", svmFeignClient);
            injectMockedClient("jobFeignClient", jobFeignClient);
            injectMockedClient("networkFeignClient", networkFeignClient);
            injectMockedClient("sanFeignClient", sanFeignClient);
            injectMockedClient("snapshotFeignClient", snapshotFeignClient);
            injectMockedClient("clusterFeignClient", clusterFeignClient);
        }

        private void injectMockedClient(String fieldName, Object mockedClient) {
            try {
                Field field = StorageStrategy.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(this, mockedClient);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Failed to inject mocked client: " + fieldName, e);
            }
        }

        @Override
        public CloudStackVolume createCloudStackVolume(CloudStackVolume cloudstackVolume) {
            return null;
        }

        @Override
        public CloudStackVolume createTemplateCache(org.apache.cloudstack.storage.datastore.db.StoragePoolVO storagePool,
                org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo templateInfo,
                Map<String, String> details, long sizeInBytes) {
            return null;
        }

        @Override
        public CloudStackVolume updateCloudStackVolume(CloudStackVolume cloudstackVolume) {
            return null;
        }

        @Override
        public void deleteCloudStackVolume(CloudStackVolume cloudstackVolume) {
        }

        @Override
        public CloudStackVolume cloneCloudStackVolume(CloudStackVolume cloudstackVolume) {
            return null;
        }

        @Override
        public CloudStackVolume copyCloudStackVolume(org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo srcVolumeInfo,
                org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo destVolumeInfo,
                org.apache.cloudstack.storage.datastore.db.StoragePoolVO destStoragePool,
                Map<String, String> destDetails, int timeoutSeconds) {
            return null;
        }

        @Override
        public void resizeCloudStackVolume(CloudStackVolume cloudstackVolume, long sizeInBytes) {
        }

        @Override
        public CloudStackVolume getCloudStackVolume(Map<String, String> cloudStackVolumeMap) {
            return null;
        }

        @Override
        public JobResponse revertSnapshotForCloudStackVolume(String snapshotName, String flexVolUuid, String snapshotUuid, String volumePath, String lunUuid, String flexVolName) {
            return null;
        }

        @Override
        public AccessGroup createAccessGroup(AccessGroup accessGroup) {
            return null;
        }

        @Override
        public void deleteAccessGroup(AccessGroup accessGroup) {
        }

        @Override
        public AccessGroup updateAccessGroup(AccessGroup accessGroup) {
            return null;
        }

        @Override
        public AccessGroup getAccessGroup(Map<String, String> values) {
            return null;
        }

        @Override
        public String enableLogicalAccess(Map<String, String> values) {
            return null;
        }

        @Override
        public void disableLogicalAccess(Map<String, String> values) {
        }

        @Override
        public String getLogicalAccess(Map<String, String> values) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        // Create OntapStorage using constructor (immutable object)
        OntapStorage ontapStorage = new OntapStorage("admin", "password", "192.168.1.100",
                "svm1", 5000000000L, ProtocolType.NFS3);

        // Note: In real implementation, StorageStrategy constructor creates Feign clients
        // For testing, we'll need to mock the FeignClientFactory behavior
        storageStrategy = new TestableStorageStrategy(ontapStorage,
                aggregateFeignClient, volumeFeignClient, svmFeignClient,
                jobFeignClient, networkFeignClient, sanFeignClient, snapshotFeignClient,
                clusterFeignClient);
    }

    // ========== connect() Tests ==========

    @Test
    public void testConnect_positive() {
        // Setup
        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState(OntapStorageConstants.RUNNING);
        svm.setNfsEnabled(true);

        Aggregate aggregate = new Aggregate();
        aggregate.setName("aggr1");
        aggregate.setUuid("aggr-uuid-1");
        svm.setAggregates(List.of(aggregate));

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);
        Aggregate aggregateDetail = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0);
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap())).thenReturn(aggregateDetail);

        // Execute
        boolean result = storageStrategy.connect();

        // Verify
        assertTrue(result, "connect() should return true on success");
        verify(svmFeignClient, times(1)).getSvmResponse(anyMap(), anyString());
    }

    @Test
    public void testConnect_collectsAllEligibleAggregates() {
        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState(OntapStorageConstants.RUNNING);
        svm.setNfsEnabled(true);

        Aggregate aggregate1 = new Aggregate();
        aggregate1.setName("aggr1");
        aggregate1.setUuid("aggr-uuid-1");
        Aggregate aggregate2 = new Aggregate();
        aggregate2.setName("aggr2");
        aggregate2.setUuid("aggr-uuid-2");
        svm.setAggregates(List.of(aggregate1, aggregate2));

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap()))
                .thenReturn(buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0));
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-2"), anyMap()))
                .thenReturn(buildAggregate("aggr2", "aggr-uuid-2", 20000000000.0));

        assertTrue(storageStrategy.connect());

        List<Aggregate> aggregates = storageStrategy.getAggregates();
        assertNotNull(aggregates);
        assertEquals(2, aggregates.size());
        assertEquals("aggr-uuid-1", aggregates.get(0).getUuid());
        assertEquals("aggr-uuid-2", aggregates.get(1).getUuid());
    }

    @Test
    public void testConnect_operationsOnly_skipsAggregateValidation() {
        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState(OntapStorageConstants.RUNNING);
        svm.setNfsEnabled(true);

        Aggregate aggregate = new Aggregate();
        aggregate.setName("aggr1");
        aggregate.setUuid("aggr-uuid-1");
        svm.setAggregates(List.of(aggregate));

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);

        // Aggregate is ONLINE but has far less free space than the configured pool size (5GB).
        Aggregate aggregateDetail = buildAggregate("aggr1", "aggr-uuid-1", 1000000.0); // only 1MB free
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap())).thenReturn(aggregateDetail);

        // Execute & Verify - connect(false) should succeed regardless of available space.
        boolean result = storageStrategy.connect(false);
        assertTrue(result, "connect() should succeed for an online aggregate even when its free space is below the pool capacity");
    }

    @Test
    public void testGetClusterInfo_dedupesIdenticalNodeModels() {
        Cluster cluster = stubClusterGet();
        ClusterNode node1 = new ClusterNode();
        node1.setModel("AFF-A400");
        ClusterNode node2 = new ClusterNode();
        node2.setModel("AFF-A400");
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(node1, node2)));

        Cluster result = storageStrategy.getClusterInfo();
        assertEquals(cluster, result);
        assertEquals("AFF-A400", result.getModel());
    }

    @Test
    public void testGetClusterInfo_joinsDistinctModels() {
        stubClusterGet();
        ClusterNode node1 = new ClusterNode();
        node1.setModel("AFF-A400");
        ClusterNode node2 = new ClusterNode();
        node2.setModel("FAS8300");
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(node1, node2)));

        assertEquals("AFF-A400,FAS8300", storageStrategy.getClusterInfo().getModel());
    }

    @Test
    public void testGetClusterInfo_allFlashPerformance() {
        stubClusterGet();
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-A400", true, true, false),
                        clusterNode("AFF-A400", true, true, false))));

        Cluster result = storageStrategy.getClusterInfo();
        assertEquals("AFF-A400", result.getModel());
        assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_PERFORMANCE, result.getPlatformType());
    }

    @Test
    public void testGetClusterInfo_allFlashCapacity() {
        stubClusterGet();
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-C800", true, false, true),
                        clusterNode("AFF-C800", true, false, true))));

        Cluster result = storageStrategy.getClusterInfo();
        assertEquals("AFF-C800", result.getModel());
        assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_CAPACITY, result.getPlatformType());
    }

    @Test
    public void testGetClusterInfo_notAllFlashIsFas() {
        stubClusterGet();
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("FAS8300", false, false, false),
                        clusterNode("FAS8300", false, false, false))));

        Cluster result = storageStrategy.getClusterInfo();
        assertEquals("FAS8300", result.getModel());
        assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_FAS, result.getPlatformType());
    }

    @Test
    public void testGetClusterInfo_mixedPersonalitiesIsComposite() {
        stubClusterGet();
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-A400", true, true, false),
                        clusterNode("AFF-A400", true, true, false),
                        clusterNode("FAS8300", false, false, false),
                        clusterNode("FAS8300", false, false, false))));

        Cluster result = storageStrategy.getClusterInfo();
        assertEquals("AFF-A400,FAS8300", result.getModel());
        assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_COMPOSITE, result.getPlatformType());
    }

    @Test
    public void testGetClusterInfo_missingAllFlashFlagLeavesPlatformUnset() {
        stubClusterGet();
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-A400", null, null, null))));

        Cluster result = storageStrategy.getClusterInfo();
        assertEquals("AFF-A400", result.getModel());
        assertNull(result.getPlatformType());
    }

    @Test
    public void testGetClusterInfo_missingFlagDoesNotChangeKnownPlatform() {
        stubClusterGet();
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-A400", true, true, false),
                        clusterNode("AFF-A400", null, null, null))));

        Cluster result = storageStrategy.getClusterInfo();
        assertEquals("AFF-A400", result.getModel());
        assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_PERFORMANCE, result.getPlatformType());
    }

    @Test
    public void testGetClusterInfo_nodesGetFailureLeavesModelUnset() {
        stubClusterGet();
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenThrow(new RuntimeException("connection refused"));

        Cluster result = storageStrategy.getClusterInfo();
        assertNotNull(result);
        assertNull(result.getModel());
        assertNull(result.getPlatformType());
    }

    @Test
    public void testIsAff_allNodesAllFlash_returnsTrue() {
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-A400", true, true, false),
                        clusterNode("AFF-A400", true, true, false))));

        assertTrue(storageStrategy.isAff());
    }

    @Test
    public void testIsAff_anyNodeNotAllFlash_returnsFalse() {
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-A400", true, true, false),
                        clusterNode("FAS8300", false, false, false))));

        assertFalse(storageStrategy.isAff());
    }

    @Test
    public void testIsAff_nullAllFlashOptimized_returnsFalse() {
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of(
                        clusterNode("AFF-A400", true, true, false),
                        clusterNode("unknown", null, null, null))));

        assertFalse(storageStrategy.isAff());
    }

    @Test
    public void testIsAff_noNodes_throws() {
        when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                .thenReturn(new OntapResponse<>(List.of()));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.isAff());
        assertTrue(ex.getMessage().contains("Unable to determine whether the ONTAP cluster is AFF or FAS"));
    }

    private Cluster stubClusterGet() {
        Cluster cluster = new Cluster();
        when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
        return cluster;
    }

    private static ClusterNode clusterNode(String model, Boolean allFlash, Boolean performance, Boolean capacity) {
        ClusterNode node = new ClusterNode();
        node.setModel(model);
        node.setAllFlashOptimized(allFlash);
        node.setPerformanceOptimized(performance);
        node.setCapacityOptimized(capacity);
        return node;
    }

    @Test
    public void testConnect_noOnlineAggregates() {
        // Setup - aggregate assigned to the SVM exists but is not ONLINE
        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState(OntapStorageConstants.RUNNING);
        svm.setNfsEnabled(true);

        Aggregate aggregate = new Aggregate();
        aggregate.setName("aggr1");
        aggregate.setUuid("aggr-uuid-1");
        svm.setAggregates(List.of(aggregate));

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);

        Aggregate aggregateDetail = new Aggregate();
        aggregateDetail.setName("aggr1");
        aggregateDetail.setUuid("aggr-uuid-1");
        aggregateDetail.setState(null); // not online
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap())).thenReturn(aggregateDetail);

        // Execute & Verify
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("No suitable aggregates found"));
        boolean result = storageStrategy.connect(false);

        assertTrue(result);
        // connect(true) called getAggregateByUUID once; connect(false) must not add more calls
        verify(aggregateFeignClient, times(1)).getAggregateByUUID(anyString(), anyString(), anyMap());
    }

    @Test
    public void testConnect_svmNotFound() {
        // Setup
        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(new ArrayList<>());

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);

        // Execute & Verify
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("No SVM found"));
    }

    @Test
    public void testConnect_svmNotRunning() {
        // Setup
        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState("stopped");
        svm.setNfsEnabled(true);

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);

        // Execute & Verify
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("not in running state"));
    }

    @Test
    public void testConnect_nfsNotEnabled() {
        // Setup
        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState(OntapStorageConstants.RUNNING);
        svm.setNfsEnabled(false);

        Aggregate aggregate = new Aggregate();
        aggregate.setName("aggr1");
        aggregate.setUuid("aggr-uuid-1");
        svm.setAggregates(List.of(aggregate));

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);

        // Execute & Verify
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("NFS protocol is not enabled"));
    }

    @Test
    public void testConnect_iscsiNotEnabled() {
        // Setup - recreate with iSCSI protocol
        OntapStorage iscsiStorage = new OntapStorage("admin", "password", "192.168.1.100",
                "svm1", 5000000000L, ProtocolType.ISCSI);
        storageStrategy = new TestableStorageStrategy(iscsiStorage,
                aggregateFeignClient, volumeFeignClient, svmFeignClient,
                jobFeignClient, networkFeignClient, sanFeignClient, snapshotFeignClient,
                clusterFeignClient);

        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState(OntapStorageConstants.RUNNING);
        svm.setIscsiEnabled(false);

        Aggregate aggregate = new Aggregate();
        aggregate.setName("aggr1");
        aggregate.setUuid("aggr-uuid-1");
        svm.setAggregates(List.of(aggregate));

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);

        // Execute & Verify
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("ISCSI protocol is not enabled"));
    }

    @Test
    public void testConnect_noAggregates() {
        // Setup
        Svm svm = new Svm();
        svm.setName("svm1");
        svm.setState(OntapStorageConstants.RUNNING);
        svm.setNfsEnabled(true);
        svm.setAggregates(new ArrayList<>());

        OntapResponse<Svm> svmResponse = new OntapResponse<>();
        svmResponse.setRecords(List.of(svm));

        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(svmResponse);

        // Execute & Verify
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("No aggregates"));
    }

    @Test
    public void testConnect_nullSvmResponse() {
        // Setup
        when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(null);

        // Execute & Verify
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("No SVM found"));
    }

    @Test
    public void testConnect_invalidCredentials() {
        // Setup - ONTAP rejects the supplied username/password with HTTP 401 Unauthorized.
        Map<String, Collection<String>> emptyHeaders = Collections.emptyMap();
        Request dummyReq = Request.create(Request.HttpMethod.GET, "http://test", emptyHeaders, (byte[]) null, (Charset) null);
        when(svmFeignClient.getSvmResponse(anyMap(), anyString()))
                .thenThrow(new FeignException.Unauthorized("Unauthorized", dummyReq, null));

        // Execute & Verify - connect() must surface a clear "invalid credentials" error.
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> storageStrategy.connect());
        assertTrue(ex.getMessage().contains("Authentication failed: Invalid credentials"),
                "Expected an authentication failure message but got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Please verify the username and password"),
                "Expected the message to prompt verifying username/password but got: " + ex.getMessage());
    }

    // ========== chooseAggregate() Tests ==========

    @Test
    public void testChooseAggregate_positive() {
        Aggregate aggregateDetail = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap()))
                .thenReturn(aggregateDetail);

        Aggregate result = storageStrategy.chooseAggregate(candidateAggregates(), 5000000000L);

        assertNotNull(result);
        assertEquals("aggr1", result.getName());
        assertEquals("aggr-uuid-1", result.getUuid());
        assertEquals("node-a", result.getNode().getName());
    }

    @Test
    public void testChooseAggregate_picksLargestAvailable() {
        Aggregate candidate1 = new Aggregate();
        candidate1.setName("aggr1");
        candidate1.setUuid("aggr-uuid-1");
        Aggregate candidate2 = new Aggregate();
        candidate2.setName("aggr2");
        candidate2.setUuid("aggr-uuid-2");

        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap()))
                .thenReturn(buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a"));
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-2"), anyMap()))
                .thenReturn(buildAggregate("aggr2", "aggr-uuid-2", 20000000000.0, "node-b"));

        Aggregate result = storageStrategy.chooseAggregate(List.of(candidate1, candidate2), 5000000000L);

        assertEquals("aggr-uuid-2", result.getUuid());
        assertEquals("node-b", result.getNode().getName());
    }

    @Test
    public void testChooseAggregate_invalidSize() {
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.chooseAggregate(candidateAggregates(), -1L));
        assertTrue(ex.getMessage().contains("Invalid volume size"));
    }

    @Test
    public void testChooseAggregate_nullSize() {
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.chooseAggregate(candidateAggregates(), null));
        assertTrue(ex.getMessage().contains("Invalid volume size"));
    }

    @Test
    public void testChooseAggregate_noAggregates() {
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.chooseAggregate(null, 5000000000L));
        assertTrue(ex.getMessage().contains("No aggregates available"));

        ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.chooseAggregate(List.of(), 5000000000L));
        assertTrue(ex.getMessage().contains("No aggregates available"));
    }

    @Test
    public void testChooseAggregate_aggregateNotOnline() {
        Aggregate aggregateDetail = new Aggregate();
        aggregateDetail.setName("aggr1");
        aggregateDetail.setUuid("aggr-uuid-1");
        aggregateDetail.setState(null);

        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap()))
                .thenReturn(aggregateDetail);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.chooseAggregate(candidateAggregates(), 5000000000L));
        assertTrue(ex.getMessage().contains("No suitable aggregates found"));
    }

    @Test
    public void testChooseAggregate_insufficientSpace() {
        Aggregate aggregateDetail = buildAggregate("aggr1", "aggr-uuid-1", 1000000.0, "node-a");

        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap()))
                .thenReturn(aggregateDetail);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.chooseAggregate(candidateAggregates(), 5000000000L));
        assertTrue(ex.getMessage().contains("No suitable aggregates found"));
    }

    @Test
    public void testChooseAggregate_missingNode() {
        Aggregate aggregateDetail = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0);
        when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("aggr-uuid-1"), anyMap()))
                .thenReturn(aggregateDetail);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.chooseAggregate(candidateAggregates(), 5000000000L));
        assertTrue(ex.getMessage().contains("does not have a node name"));
    }

    private List<Aggregate> candidateAggregates() {
        Aggregate candidate = new Aggregate();
        candidate.setName("aggr1");
        candidate.setUuid("aggr-uuid-1");
        return List.of(candidate);
    }

    // ========== createStorageVolume() Tests ==========

    @Test
    public void testCreateStorageVolume_positive() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // Setup job response
        Job job = new Job();
        job.setUuid("job-uuid-1");
        JobResponse jobResponse = new JobResponse();
        jobResponse.setJob(job);

        when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class)))
                .thenReturn(jobResponse);

        // Setup job polling
        Job completedJob = new Job();
        completedJob.setUuid("job-uuid-1");
        completedJob.setState(OntapStorageConstants.JOB_SUCCESS);
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-uuid-1")))
                .thenReturn(completedJob);

        // Setup volume retrieval after creation
        Volume createdVolume = new Volume();
        createdVolume.setName("test-volume");
        createdVolume.setUuid("vol-uuid-1");
        OntapResponse<Volume> volumeResponse = new OntapResponse<>();
        volumeResponse.setRecords(List.of(createdVolume));

        when(volumeFeignClient.getAllVolumes(anyString(), anyMap()))
                .thenReturn(volumeResponse);
        when(volumeFeignClient.getVolume(anyString(), anyMap()))
                .thenReturn(volumeResponse);

        // Execute
        Volume result = storageStrategy.createStorageVolume("test-volume", 5000000000L, aggregate);

        // Verify
        assertNotNull(result);
        assertEquals("test-volume", result.getName());
        assertEquals("vol-uuid-1", result.getUuid());
        verify(volumeFeignClient, times(1)).createVolumeWithJob(anyString(), any(Volume.class));
        verify(jobFeignClient, atLeastOnce()).getJobByUUID(anyString(), eq("job-uuid-1"));
    }

    @Test
    public void testCreateStorageVolume_invalidSize() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.createStorageVolume("test-volume", -1L, aggregate));
        assertTrue(ex.getMessage().contains("Invalid volume size"));
    }

    @Test
    public void testCreateStorageVolume_nullSize() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.createStorageVolume("test-volume", null, aggregate));
        assertTrue(ex.getMessage().contains("Invalid volume size"));
    }

    @Test
    public void testCreateStorageVolume_nullAggregate() {
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.createStorageVolume("test-volume", 5000000000L, null));
        assertTrue(ex.getMessage().contains("Aggregate is required"));
    }

    @Test
    public void testCreateStorageVolume_jobFailed() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        Job job = new Job();
        job.setUuid("job-uuid-1");
        JobResponse jobResponse = new JobResponse();
        jobResponse.setJob(job);

        when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class)))
                .thenReturn(jobResponse);

        // Setup failed job
        Job failedJob = new Job();
        failedJob.setUuid("job-uuid-1");
        failedJob.setState(OntapStorageConstants.JOB_FAILURE);
        failedJob.setMessage("Volume creation failed");
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-uuid-1")))
                .thenReturn(failedJob);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.createStorageVolume("test-volume", 5000000000L, aggregate));
        assertTrue(ex.getMessage().contains("failed") || ex.getMessage().contains("Job failed"));
    }

    @Test
    public void testCreateStorageVolume_volumeNotFoundAfterCreation() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");
        setupSuccessfulJobCreation();

        // Setup empty volume response
        OntapResponse<Volume> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        when(volumeFeignClient.getAllVolumes(anyString(), anyMap()))
                .thenReturn(emptyResponse);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.createStorageVolume("test-volume", 5000000000L, aggregate));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("not found after creation"));
    }

    // ========== deleteStorageVolume() Tests ==========

    @Test
    public void testDeleteStorageVolume_positive() {
        // Setup
        Volume volume = new Volume();
        volume.setName("test-volume");
        volume.setUuid("vol-uuid-1");

        Job job = new Job();
        job.setUuid("job-uuid-1");
        JobResponse jobResponse = new JobResponse();
        jobResponse.setJob(job);

        when(volumeFeignClient.deleteVolume(anyString(), eq("vol-uuid-1")))
                .thenReturn(jobResponse);

        Job completedJob = new Job();
        completedJob.setUuid("job-uuid-1");
        completedJob.setState(OntapStorageConstants.JOB_SUCCESS);
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-uuid-1")))
                .thenReturn(completedJob);

        // Execute
        storageStrategy.deleteStorageVolume(volume);

        // Verify
        verify(volumeFeignClient, times(1)).deleteVolume(anyString(), eq("vol-uuid-1"));
        verify(jobFeignClient, atLeastOnce()).getJobByUUID(anyString(), eq("job-uuid-1"));
    }

    @Test
    public void testDeleteStorageVolume_jobFailed() {
        // Setup
        Volume volume = new Volume();
        volume.setName("test-volume");
        volume.setUuid("vol-uuid-1");

        Job job = new Job();
        job.setUuid("job-uuid-1");
        JobResponse jobResponse = new JobResponse();
        jobResponse.setJob(job);

        when(volumeFeignClient.deleteVolume(anyString(), eq("vol-uuid-1")))
                .thenReturn(jobResponse);

        Job failedJob = new Job();
        failedJob.setUuid("job-uuid-1");
        failedJob.setState(OntapStorageConstants.JOB_FAILURE);
        failedJob.setMessage("Deletion failed");
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-uuid-1")))
                .thenReturn(failedJob);

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.deleteStorageVolume(volume));
        assertTrue(ex.getMessage().contains("Job failed"));
    }

    @Test
    public void testDeleteStorageVolume_feignException() {
        // Setup
        Volume volume = new Volume();
        volume.setName("test-volume");
        volume.setUuid("vol-uuid-1");

        Map<String, Collection<String>> emptyHeaders = Collections.emptyMap();
        Request dummyReq = Request.create(Request.HttpMethod.DELETE, "http://test", emptyHeaders, (byte[]) null, (Charset) null);
        when(volumeFeignClient.deleteVolume(anyString(), eq("vol-uuid-1")))
                .thenThrow(new FeignException.FeignClientException(500, "error", dummyReq, null));

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.deleteStorageVolume(volume));
        assertTrue(ex.getMessage().contains("Failed to delete volume"));
    }

    @Test
    public void testDeleteStorageVolume_notFound_404_returnsWithoutThrowing() {
        // Setup
        Volume volume = new Volume();
        volume.setName("test-volume");
        volume.setUuid("vol-uuid-1");

        FeignException feignEx = mock(FeignException.class);
        when(feignEx.status()).thenReturn(404);
        when(volumeFeignClient.deleteVolume(anyString(), eq("vol-uuid-1")))
                .thenThrow(feignEx);

        // Execute - 404 means volume already gone on ONTAP, treated as no-op
        storageStrategy.deleteStorageVolume(volume);

        // Verify the delete was attempted
        verify(volumeFeignClient).deleteVolume(anyString(), eq("vol-uuid-1"));
    }

    // ========== getStoragePath() Tests ==========

    @Test
    public void testGetStoragePath_iscsi() {
        // Setup - recreate with iSCSI protocol
        OntapStorage iscsiStorage = new OntapStorage("admin", "password", "192.168.1.100",
                "svm1", null, ProtocolType.ISCSI);
        storageStrategy = new TestableStorageStrategy(iscsiStorage,
                aggregateFeignClient, volumeFeignClient, svmFeignClient,
                jobFeignClient, networkFeignClient, sanFeignClient, snapshotFeignClient,
                clusterFeignClient);

        IscsiService.IscsiServiceTarget target = new IscsiService.IscsiServiceTarget();
        target.setName("iqn.1992-08.com.netapp:sn.123456:vs.1");

        IscsiService iscsiService = new IscsiService();
        iscsiService.setTarget(target);

        OntapResponse<IscsiService> iscsiResponse = new OntapResponse<>();
        iscsiResponse.setRecords(List.of(iscsiService));

        when(sanFeignClient.getIscsiServices(anyString(), anyMap()))
                .thenReturn(iscsiResponse);

        // Execute
        String result = storageStrategy.getStoragePath();

        // Verify
        assertNotNull(result);
        assertEquals("iqn.1992-08.com.netapp:sn.123456:vs.1", result);
        verify(sanFeignClient, times(1)).getIscsiServices(anyString(), anyMap());
    }

    @Test
    public void testGetStoragePath_iscsi_noService() {
        // Setup - recreate with iSCSI protocol
        OntapStorage iscsiStorage = new OntapStorage("admin", "password", "192.168.1.100",
                "svm1", null, ProtocolType.ISCSI);
        storageStrategy = new TestableStorageStrategy(iscsiStorage,
                aggregateFeignClient, volumeFeignClient, svmFeignClient,
                jobFeignClient, networkFeignClient, sanFeignClient, snapshotFeignClient,
                clusterFeignClient);

        OntapResponse<IscsiService> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        when(sanFeignClient.getIscsiServices(anyString(), anyMap()))
                .thenReturn(emptyResponse);

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getStoragePath());
        assertTrue(ex.getMessage().contains("No iSCSI service found"));
    }

    @Test
    public void testGetStoragePath_iscsi_noTargetIqn() {
        // Setup - recreate with iSCSI protocol
        OntapStorage iscsiStorage = new OntapStorage("admin", "password", "192.168.1.100",
                "svm1", null, ProtocolType.ISCSI);
        storageStrategy = new TestableStorageStrategy(iscsiStorage,
                aggregateFeignClient, volumeFeignClient, svmFeignClient,
                jobFeignClient, networkFeignClient, sanFeignClient, snapshotFeignClient,
                clusterFeignClient);

        IscsiService iscsiService = new IscsiService();
        iscsiService.setTarget(null);

        OntapResponse<IscsiService> iscsiResponse = new OntapResponse<>();
        iscsiResponse.setRecords(List.of(iscsiService));

        when(sanFeignClient.getIscsiServices(anyString(), anyMap()))
                .thenReturn(iscsiResponse);

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getStoragePath());
        assertTrue(ex.getMessage().contains("iSCSI target IQN not found"));
    }

    // ========== getNetworkInterface() Tests ==========

    @Test
    public void testGetNetworkInterface_nfs() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // Setup
        IpInterface.IpInfo ipInfo = new IpInterface.IpInfo();
        ipInfo.setAddress("192.168.1.50");

        IpInterface ipInterface = new IpInterface();
        ipInterface.setIp(ipInfo);
        ipInterface.setState(OntapStorageConstants.LIF_STATE_UP);
        ipInterface.setEnabled(true);
        IpInterface.Node homeNode = new IpInterface.Node();
        homeNode.setName("node-a");
        IpInterface.Location location = new IpInterface.Location();
        location.setHomeNode(homeNode);
        location.setNode(homeNode);
        ipInterface.setLocation(location);

        OntapResponse<IpInterface> interfaceResponse = new OntapResponse<>();
        interfaceResponse.setRecords(List.of(ipInterface));

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(interfaceResponse);

        // Execute
        Map<String, String> result = storageStrategy.getNetworkInterface(aggregate);

        // Verify
        assertNotNull(result);
        assertEquals("192.168.1.50", result.get(OntapStorageConstants.DATA_LIF));
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING) == null,
                "Expect no warning when a suitable LIF is found");
        verify(networkFeignClient, times(1)).getNetworkIpInterfaces(anyString(), anyMap());
    }

    @Test
    public void testGetNetworkInterface_iscsi() {
        // Setup - recreate with iSCSI protocol
        OntapStorage iscsiStorage = new OntapStorage("admin", "password", "192.168.1.100",
                "svm1", null, ProtocolType.ISCSI);
        storageStrategy = new TestableStorageStrategy(iscsiStorage,
                aggregateFeignClient, volumeFeignClient, svmFeignClient,
                jobFeignClient, networkFeignClient, sanFeignClient, snapshotFeignClient,
                clusterFeignClient);

        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        IpInterface.IpInfo ipInfo = new IpInterface.IpInfo();
        ipInfo.setAddress("192.168.1.51");

        IpInterface ipInterface = new IpInterface();
        ipInterface.setIp(ipInfo);
        ipInterface.setState(OntapStorageConstants.LIF_STATE_UP);
        ipInterface.setEnabled(true);
        IpInterface.Node homeNode = new IpInterface.Node();
        homeNode.setName("node-a");
        IpInterface.Location location = new IpInterface.Location();
        location.setHomeNode(homeNode);
        location.setNode(homeNode);
        ipInterface.setLocation(location);

        OntapResponse<IpInterface> interfaceResponse = new OntapResponse<>();
        interfaceResponse.setRecords(List.of(ipInterface));

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(interfaceResponse);

        // Execute
        Map<String, String> result = storageStrategy.getNetworkInterface(aggregate);

        // Verify
        assertNotNull(result);
        assertEquals("192.168.1.51", result.get(OntapStorageConstants.DATA_LIF));
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING) == null,
                "Expect no warning when a suitable LIF is found");
    }

    @Test
    public void testGetNetworkInterface_nfs_lifDown() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // LIF exists but is operationally down — should fail
        IpInterface.IpInfo ipInfo = new IpInterface.IpInfo();
        ipInfo.setAddress("192.168.1.50");

        IpInterface ipInterface = new IpInterface();
        ipInterface.setIp(ipInfo);
        ipInterface.setState("down");
        ipInterface.setEnabled(true);

        OntapResponse<IpInterface> interfaceResponse = new OntapResponse<>();
        interfaceResponse.setRecords(List.of(ipInterface));

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(interfaceResponse);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getNetworkInterface(aggregate));
        assertTrue(ex.getMessage().contains("operationally UP and enabled"));
    }

    @Test
    public void testGetNetworkInterface_nfs_lifDisabled() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // LIF exists but is administratively disabled — should fail
        IpInterface.IpInfo ipInfo = new IpInterface.IpInfo();
        ipInfo.setAddress("192.168.1.50");

        IpInterface ipInterface = new IpInterface();
        ipInterface.setIp(ipInfo);
        ipInterface.setState(OntapStorageConstants.LIF_STATE_UP);
        ipInterface.setEnabled(false);

        OntapResponse<IpInterface> interfaceResponse = new OntapResponse<>();
        interfaceResponse.setRecords(List.of(ipInterface));

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(interfaceResponse);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getNetworkInterface(aggregate));
        assertTrue(ex.getMessage().contains("operationally UP and enabled"));
    }

    @Test
    public void testGetNetworkInterface_iscsi_lifDown() {
        // iSCSI LIF exists but is operationally down — should fail
        OntapStorage iscsiStorage = new OntapStorage("admin", "password", "192.168.1.100",
                "svm1", null, ProtocolType.ISCSI);
        storageStrategy = new TestableStorageStrategy(iscsiStorage,
                aggregateFeignClient, volumeFeignClient, svmFeignClient,
                jobFeignClient, networkFeignClient, sanFeignClient, snapshotFeignClient,
                clusterFeignClient);

        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        IpInterface.IpInfo ipInfo = new IpInterface.IpInfo();
        ipInfo.setAddress("192.168.1.51");

        IpInterface ipInterface = new IpInterface();
        ipInterface.setIp(ipInfo);
        ipInterface.setState("down");
        ipInterface.setEnabled(true);

        OntapResponse<IpInterface> interfaceResponse = new OntapResponse<>();
        interfaceResponse.setRecords(List.of(ipInterface));

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(interfaceResponse);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getNetworkInterface(aggregate));
        assertTrue(ex.getMessage().contains("operationally UP and enabled"));
    }

    @Test
    public void testGetNetworkInterface_noInterfaces() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // Setup
        OntapResponse<IpInterface> emptyResponse = new OntapResponse<>();
        emptyResponse.setRecords(new ArrayList<>());

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(emptyResponse);

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getNetworkInterface(aggregate));
        assertTrue(ex.getMessage().contains("No network interfaces found"));
    }

    @Test
    public void testGetNetworkInterface_feignException() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // Setup
        Map<String, Collection<String>> emptyHeaders = Collections.emptyMap();
        Request dummyReq = Request.create(Request.HttpMethod.GET, "http://test", emptyHeaders, (byte[]) null, (Charset) null);
        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenThrow(new FeignException.FeignClientException(500, "error", dummyReq, null));

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getNetworkInterface(aggregate));
        assertTrue(ex.getMessage().contains("Failed to retrieve network interfaces"));
    }

    // ========== getNetworkInterface() Node-Affinity Tests ==========

    /**
     * Tier 1: LIF homed on the same node as the chosen aggregate — selected without warning.
     */
    @Test
    public void testGetNetworkInterface_nfs_tier1_homeNodeMatch() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        IpInterface lif = buildLif("10.0.0.1", OntapStorageConstants.LIF_STATE_UP, true, "node-a", "node-a");
        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(wrapLifs(List.of(lif)));

        Map<String, String> result = storageStrategy.getNetworkInterface(aggregate);

        assertEquals("10.0.0.1", result.get(OntapStorageConstants.DATA_LIF));
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING) == null, "Tier 1 should produce no warning");
    }

    /**
     * Tier 2: No home-node match, but another UP LIF is currently running on the target node (failover).
     * The result should carry a warning.
     */
    @Test
    public void testGetNetworkInterface_nfs_tier2_currentNodeMatch() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // home node = node-b, currently running on node-a after failover
        IpInterface lif = buildLif("10.0.0.2", OntapStorageConstants.LIF_STATE_UP, true, "node-b", "node-a");
        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(wrapLifs(List.of(lif)));

        Map<String, String> result = storageStrategy.getNetworkInterface(aggregate);

        assertEquals("10.0.0.2", result.get(OntapStorageConstants.DATA_LIF));
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING) != null, "Tier 2 should produce a warning");
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING).contains("node-a"));
    }

    /**
     * Tier 3 fallback: No LIF matches the target node in either home_node or current node.
     * First UP/enabled LIF used; result carries a warning directing the user to create a
     * LIF on the correct node.
     */
    @Test
    public void testGetNetworkInterface_nfs_tier3_crossNodeFallback() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // Both home_node and current node are node-b — no affinity to node-a
        IpInterface lif = buildLif("10.0.0.3", OntapStorageConstants.LIF_STATE_UP, true, "node-b", "node-b");
        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(wrapLifs(List.of(lif)));

        Map<String, String> result = storageStrategy.getNetworkInterface(aggregate);

        assertEquals("10.0.0.3", result.get(OntapStorageConstants.DATA_LIF));
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING) != null, "Tier 3 fallback should produce a warning");
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING).contains("node-a"),
                "Warning should mention the expected node");
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING).contains("10.0.0.3"),
                "Warning should mention the fallback LIF IP");
    }

    /**
     * Null aggregate or missing node fails clearly — no silent unaffined LIF selection.
     */
    @Test
    public void testGetNetworkInterface_nullAggregate_fails() {
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getNetworkInterface(null));
        assertTrue(ex.getMessage().contains("Aggregate with a node name is required"));
    }

    @Test
    public void testGetNetworkInterface_missingNode_fails() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0);

        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.getNetworkInterface(aggregate));
        assertTrue(ex.getMessage().contains("Aggregate with a node name is required"));
    }

    /**
     * Tier-1 LIF is down; Tier-2 LIF matches the current node and should be selected with a warning.
     */
    @Test
    public void testGetNetworkInterface_nfs_tier1Down_tier2Used() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        // Tier 1 candidate: home_node = node-a but operationally DOWN
        IpInterface lifDown = buildLif("10.0.0.5", "down", true, "node-a", "node-a");
        // Tier 2 candidate: home_node = node-b, currently on node-a
        IpInterface lifFailover = buildLif("10.0.0.6", OntapStorageConstants.LIF_STATE_UP, true, "node-b", "node-a");

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(wrapLifs(List.of(lifDown, lifFailover)));

        Map<String, String> result = storageStrategy.getNetworkInterface(aggregate);

        assertEquals("10.0.0.6", result.get(OntapStorageConstants.DATA_LIF));
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING) != null,
                "Should warn that the home-node LIF is not in use");
    }

    /**
     * A LIF with no ip object is skipped. A later valid LIF on the same node is still selected.
     */
    @Test
    public void testGetNetworkInterface_nfs_nullIpSkipped() {
        Aggregate aggregate = buildAggregate("aggr1", "aggr-uuid-1", 10000000000.0, "node-a");

        IpInterface lifWithoutIp = buildLif("10.0.0.1", OntapStorageConstants.LIF_STATE_UP, true, "node-a", "node-a");
        lifWithoutIp.setIp(null);
        IpInterface lifWithIp = buildLif("10.0.0.2", OntapStorageConstants.LIF_STATE_UP, true, "node-a", "node-a");

        when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap()))
                .thenReturn(wrapLifs(List.of(lifWithoutIp, lifWithIp)));

        Map<String, String> result = storageStrategy.getNetworkInterface(aggregate);

        assertEquals("10.0.0.2", result.get(OntapStorageConstants.DATA_LIF));
        assertTrue(result.get(OntapStorageConstants.LIF_WARNING) == null,
                "The valid same-node LIF should be selected with no warning");
    }

    // ========== Helper Methods ==========

    private void setupSuccessfulJobCreation() {
        Job job = new Job();
        job.setUuid("job-uuid-1");
        JobResponse jobResponse = new JobResponse();
        jobResponse.setJob(job);

        when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class)))
                .thenReturn(jobResponse);

        Job completedJob = new Job();
        completedJob.setUuid("job-uuid-1");
        completedJob.setState(OntapStorageConstants.JOB_SUCCESS);
        when(jobFeignClient.getJobByUUID(anyString(), eq("job-uuid-1")))
                .thenReturn(completedJob);

        Volume createdVolume = new Volume();
        createdVolume.setName("test-volume");
        createdVolume.setUuid("vol-uuid-1");
        OntapResponse<Volume> volumeResponse = new OntapResponse<>();
        volumeResponse.setRecords(List.of(createdVolume));

        when(volumeFeignClient.getAllVolumes(anyString(), anyMap()))
                .thenReturn(volumeResponse);
        when(volumeFeignClient.getVolume(anyString(), anyMap()))
                .thenReturn(volumeResponse);
    }

    /**
     * Builds an {@link IpInterface} with all node-affinity fields populated.
     *
     * @param ip          the LIF's IP address (IPv4 for NFS3 selection to work)
     * @param state       operational state (e.g. "up" or "down")
     * @param enabled     administrative state
     * @param homeNode    name of the node the LIF is homed to
     * @param currentNode name of the node the LIF is currently running on
     */
    private static IpInterface buildLif(String ip, String state, boolean enabled,
                                        String homeNode, String currentNode) {
        IpInterface.IpInfo ipInfo = new IpInterface.IpInfo();
        ipInfo.setAddress(ip);

        IpInterface.Node homeNodeObj = new IpInterface.Node();
        homeNodeObj.setName(homeNode);

        IpInterface.Node currentNodeObj = new IpInterface.Node();
        currentNodeObj.setName(currentNode);

        IpInterface.Location location = new IpInterface.Location();
        location.setHomeNode(homeNodeObj);
        location.setNode(currentNodeObj);

        IpInterface lif = new IpInterface();
        lif.setIp(ipInfo);
        lif.setState(state);
        lif.setEnabled(enabled);
        lif.setLocation(location);
        return lif;
    }

    private static OntapResponse<IpInterface> wrapLifs(List<IpInterface> lifs) {
        OntapResponse<IpInterface> response = new OntapResponse<>();
        response.setRecords(lifs);
        return response;
    }

    /**
     * Creates a real {@link Aggregate} with nested space information so tests can avoid
     * {@code mock(Aggregate.class)} which fails on JDK 26+ due to Byte Buddy limitations.
     */
    private static Aggregate buildAggregate(String name, String uuid, double availableBytes) {
        return buildAggregate(name, uuid, availableBytes, null);
    }

    private static Aggregate buildAggregate(String name, String uuid, double availableBytes, String nodeName) {
        Aggregate.AggregateSpaceBlockStorage blockStorage = new Aggregate.AggregateSpaceBlockStorage();
        blockStorage.setAvailable(availableBytes);

        Aggregate.AggregateSpace space = new Aggregate.AggregateSpace();
        space.setBlockStorage(blockStorage);

        Aggregate agg = new Aggregate();
        agg.setName(name);
        agg.setUuid(uuid);
        agg.setState(Aggregate.StateEnum.ONLINE);
        agg.setSpace(space);
        if (nodeName != null) {
            Aggregate.Node node = new Aggregate.Node();
            node.setName(nodeName);
            agg.setNode(node);
        }
        return agg;
    }

    // ========== pollJobIfPresent / executeCliSfsrRestore Tests ==========

    @Test
    void testPollJobIfPresent_NoJob_DoesNotPoll() {
        storageStrategy.pollJobIfPresent(null, "test operation");
        storageStrategy.pollJobIfPresent(new JobResponse(), "test operation");
        verify(jobFeignClient, times(0)).getJobByUUID(anyString(), anyString());
    }

    @Test
    void testPollJobIfPresent_WithJob_PollsUntilSuccess() {
        Job job = new Job();
        job.setUuid("sfsr-job-1");
        JobResponse response = new JobResponse();
        response.setJob(job);

        Job completedJob = new Job();
        completedJob.setUuid("sfsr-job-1");
        completedJob.setState(OntapStorageConstants.JOB_SUCCESS);
        when(jobFeignClient.getJobByUUID(anyString(), eq("sfsr-job-1"))).thenReturn(completedJob);

        storageStrategy.executeCliSfsrRestore(response, "CLI SFSR restore");

        verify(jobFeignClient, atLeastOnce()).getJobByUUID(anyString(), eq("sfsr-job-1"));
    }

    @Test
    void testPollJobIfPresent_JobFailure_ThrowsCloudRuntimeException() {
        Job job = new Job();
        job.setUuid("sfsr-job-fail");
        JobResponse response = new JobResponse();
        response.setJob(job);

        Job failedJob = new Job();
        failedJob.setUuid("sfsr-job-fail");
        failedJob.setState(OntapStorageConstants.JOB_FAILURE);
        failedJob.setMessage("restore failed");
        when(jobFeignClient.getJobByUUID(anyString(), eq("sfsr-job-fail"))).thenReturn(failedJob);

        assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.executeCliSfsrRestore(response, "CLI SFSR restore"));
    }

    @Test
    void testDeleteFlexVolSnapshotForCloudStackVolume_PollsJobAndSucceeds() {
        Job job = new Job();
        job.setUuid("delete-job-1");
        JobResponse response = new JobResponse();
        response.setJob(job);
        when(snapshotFeignClient.deleteSnapshot(anyString(), eq("fv-uuid-1"), eq("snap-uuid-1")))
                .thenReturn(response);

        Job completedJob = new Job();
        completedJob.setUuid("delete-job-1");
        completedJob.setState(OntapStorageConstants.JOB_SUCCESS);
        when(jobFeignClient.getJobByUUID(anyString(), eq("delete-job-1"))).thenReturn(completedJob);

        storageStrategy.deleteFlexVolSnapshotForCloudStackVolume("fv-uuid-1", "snap-uuid-1", "snap-name-1");

        verify(snapshotFeignClient).deleteSnapshot(anyString(), eq("fv-uuid-1"), eq("snap-uuid-1"));
    }

    @Test
    void testDeleteFlexVolSnapshotForCloudStackVolume_AlreadyAbsentOnOntap() {
        Job job = new Job();
        job.setUuid("delete-job-missing");
        JobResponse response = new JobResponse();
        response.setJob(job);
        when(snapshotFeignClient.deleteSnapshot(anyString(), eq("fv-uuid-1"), eq("snap-uuid-1")))
                .thenReturn(response);

        Job failedJob = new Job();
        failedJob.setUuid("delete-job-missing");
        failedJob.setState(OntapStorageConstants.JOB_FAILURE);
        failedJob.setMessage("entry doesn't exist");
        when(jobFeignClient.getJobByUUID(anyString(), eq("delete-job-missing"))).thenReturn(failedJob);

        storageStrategy.deleteFlexVolSnapshotForCloudStackVolume("fv-uuid-1", "snap-uuid-1", "snap-name-1");

        verify(snapshotFeignClient).deleteSnapshot(anyString(), eq("fv-uuid-1"), eq("snap-uuid-1"));
    }

        @Test
        void testDeleteFlexVolSnapshotForCloudStackVolume_Feign404_TreatedAsSuccess() {
                FeignException notFoundException = mock(FeignException.class);
                when(notFoundException.status()).thenReturn(404);
                when(snapshotFeignClient.deleteSnapshot(anyString(), eq("fv-uuid-1"), eq("snap-uuid-1")))
                                .thenThrow(notFoundException);

                storageStrategy.deleteFlexVolSnapshotForCloudStackVolume("fv-uuid-1", "snap-uuid-1", "snap-name-1");

                verify(snapshotFeignClient).deleteSnapshot(anyString(), eq("fv-uuid-1"), eq("snap-uuid-1"));
                verify(jobFeignClient, never()).getJobByUUID(anyString(), anyString());
        }

    @Test
    void testGetStorageVolume_ByUuid_Success() {
        Volume expected = new Volume();
        expected.setUuid("flexvol-uuid-123");
        Map<String, Object> queryParams = Map.of(OntapStorageConstants.FIELDS, OntapStorageConstants.SPACE_USED);
        when(volumeFeignClient.getVolumeByUUID(anyString(), eq("flexvol-uuid-123"), eq(queryParams))).thenReturn(expected);

        Volume result = storageStrategy.getStorageVolume("flexvol-uuid-123", queryParams);

        assertNotNull(result);
        assertEquals("flexvol-uuid-123", result.getUuid());
        verify(volumeFeignClient).getVolumeByUUID(anyString(), eq("flexvol-uuid-123"), eq(queryParams));
    }

    @Test
    void testGetStorageVolume_ByUuid_NotFound_ReturnsNull() {
        FeignException notFound = mock(FeignException.class);
        when(notFound.status()).thenReturn(404);
        Map<String, Object> queryParams = Map.of(OntapStorageConstants.FIELDS, OntapStorageConstants.SPACE_USED);
        doThrow(notFound).when(volumeFeignClient).getVolumeByUUID(anyString(), eq("missing-uuid"), eq(queryParams));

        Volume result = storageStrategy.getStorageVolume("missing-uuid", queryParams);

        assertNull(result);
    }

    @Test
    void testGetStorageVolume_ByUuid_ServerError_Throws() {
        FeignException serverError = mock(FeignException.class);
        when(serverError.status()).thenReturn(500);
        when(serverError.getMessage()).thenReturn("Internal Server Error");
        Map<String, Object> queryParams = Map.of(OntapStorageConstants.FIELDS, OntapStorageConstants.SPACE_USED);
        doThrow(serverError).when(volumeFeignClient).getVolumeByUUID(anyString(), eq("flexvol-uuid-999"), eq(queryParams));

        assertThrows(CloudRuntimeException.class, () -> storageStrategy.getStorageVolume("flexvol-uuid-999", queryParams));
    }

    @Test
    void testGetStorageVolume_ByUuid_ForwardsQueryParams() {
        Volume expected = new Volume();
        expected.setUuid("flexvol-uuid-123");
        Map<String, Object> queryParams = Map.of(OntapStorageConstants.FIELDS, OntapStorageConstants.SPACE_USED);
        when(volumeFeignClient.getVolumeByUUID(anyString(), eq("flexvol-uuid-123"), eq(queryParams))).thenReturn(expected);

        Volume result = storageStrategy.getStorageVolume("flexvol-uuid-123", queryParams);

        assertNotNull(result);
        assertEquals("flexvol-uuid-123", result.getUuid());
        verify(volumeFeignClient).getVolumeByUUID(anyString(), eq("flexvol-uuid-123"), eq(queryParams));
    }

    // ========== updateStorageVolume() Tests ==========

    @Test
    public void testUpdateStorageVolume_positive() {
        // Setup
        Volume volume = new Volume();
        volume.setUuid("vol-uuid-resize");
        volume.setName("flexvol-resize");
        volume.setSize(5368709120L); // 5 GB

        Job job = new Job();
        job.setUuid("resize-job-uuid");
        JobResponse jobResponse = new JobResponse();
        jobResponse.setJob(job);

        when(volumeFeignClient.updateVolume(anyString(), eq("vol-uuid-resize"), any()))
                .thenReturn(jobResponse);

        Job completedJob = new Job();
        completedJob.setUuid("resize-job-uuid");
        completedJob.setState(OntapStorageConstants.JOB_SUCCESS);
        when(jobFeignClient.getJobByUUID(anyString(), eq("resize-job-uuid")))
                .thenReturn(completedJob);

        // Execute
        Volume result = storageStrategy.updateStorageVolume(volume);

        // Verify
        assertSame(volume, result);
        assertEquals(5368709120L, result.getSize());
        verify(volumeFeignClient, times(1)).updateVolume(anyString(), eq("vol-uuid-resize"), any());
        verify(volumeFeignClient, never()).getVolumeByUUID(anyString(), anyString(), any());
        verify(jobFeignClient, atLeastOnce()).getJobByUUID(anyString(), eq("resize-job-uuid"));
    }

    @Test
    public void testUpdateStorageVolume_jobFailed() {
        // Setup
        Volume volume = new Volume();
        volume.setUuid("vol-uuid-resize");
        volume.setName("flexvol-resize");
        volume.setSize(5368709120L);

        Job job = new Job();
        job.setUuid("resize-job-uuid");
        JobResponse jobResponse = new JobResponse();
        jobResponse.setJob(job);

        when(volumeFeignClient.updateVolume(anyString(), eq("vol-uuid-resize"), any()))
                .thenReturn(jobResponse);

        Job failedJob = new Job();
        failedJob.setUuid("resize-job-uuid");
        failedJob.setState(OntapStorageConstants.JOB_FAILURE);
        failedJob.setMessage("Resize failed");
        when(jobFeignClient.getJobByUUID(anyString(), eq("resize-job-uuid")))
                .thenReturn(failedJob);

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.updateStorageVolume(volume));
        assertTrue(ex.getMessage().contains("Job failed"));
    }

    @Test
    public void testUpdateStorageVolume_feignException() {
        // Setup
        Volume volume = new Volume();
        volume.setUuid("vol-uuid-fail");
        volume.setName("flexvol-fail");
        volume.setSize(3221225472L);

        FeignException feignException = mock(FeignException.class);
        when(feignException.status()).thenReturn(500);
        when(volumeFeignClient.updateVolume(anyString(), eq("vol-uuid-fail"), any()))
                .thenThrow(feignException);

        // Execute & Verify
        Exception ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.updateStorageVolume(volume));
        assertTrue(ex.getMessage().contains("Failed to resize ONTAP FlexVolume"));
    }

    @Test
    public void testUpdateStorageVolume_notFound_404_throwsCloudRuntimeException() {
        // Setup
        Volume volume = new Volume();
        volume.setUuid("vol-uuid-notfound");
        volume.setName("flexvol-notfound");
        volume.setSize(1073741824L);

        FeignException feignEx = mock(FeignException.class);
        when(feignEx.status()).thenReturn(404);
        when(volumeFeignClient.updateVolume(anyString(), eq("vol-uuid-notfound"), any()))
                .thenThrow(feignEx);

        // Execute & Verify — 404 means volume not found on ONTAP, should throw
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> storageStrategy.updateStorageVolume(volume));
        assertTrue(ex.getMessage().contains("not found on ONTAP"));
    }

    /**
     * Covers the {@link StorageStrategy} behaviour not exercised by {@link StorageStrategyTest}:
     * QoS policy management, ASUP/cluster info, job polling, FlexVol snapshot deletion and the
     * negative paths of volume/aggregate/iSCSI helpers.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class StorageStrategyQosAndOperationsTest {

        private static final String AUTH_USER = "admin";
        private static final String AUTH_PASSWORD = "password";

        @Mock private AggregateFeignClient aggregateFeignClient;
        @Mock private VolumeFeignClient volumeFeignClient;
        @Mock private SvmFeignClient svmFeignClient;
        @Mock private JobFeignClient jobFeignClient;
        @Mock private SANFeignClient sanFeignClient;
        @Mock private SnapshotFeignClient snapshotFeignClient;
        @Mock private ClusterFeignClient clusterFeignClient;
        @Mock private QosFeignClient qosFeignClient;
        @Mock private EmsFeignClient emsFeignClient;
        @Mock private NASFeignClient nasFeignClient;
        @Mock private NetworkFeignClient networkFeignClient;

        private StorageStrategy strategy;

        /** Minimal concrete strategy; all protocol-specific operations are irrelevant here. */
        private class TestStrategy extends StorageStrategy {
            TestStrategy(OntapStorage storage) {
                super(storage);
            }

            @Override public CloudStackVolume createCloudStackVolume(CloudStackVolume v) {
                return null;
            }

            @Override public CloudStackVolume createTemplateCache(StoragePoolVO pool, TemplateInfo template,
                    Map<String, String> details, long sizeInBytes) {
                return null;
            }

            @Override public CloudStackVolume updateCloudStackVolume(CloudStackVolume v) {
                return null;
            }

            @Override public void deleteCloudStackVolume(CloudStackVolume v) {
            }

            @Override public CloudStackVolume cloneCloudStackVolume(CloudStackVolume v) {
                return null;
            }

            @Override public CloudStackVolume copyCloudStackVolume(VolumeInfo src, VolumeInfo dest,
                    StoragePoolVO destPool, Map<String, String> destDetails, int timeoutSeconds) {
                return null;
            }

            @Override public void resizeCloudStackVolume(CloudStackVolume v, long sizeInBytes) {
            }

            @Override public CloudStackVolume getCloudStackVolume(Map<String, String> m) {
                return null;
            }

            @Override public JobResponse revertSnapshotForCloudStackVolume(String snapshotName, String flexVolUuid,
                    String snapshotUuid, String volumePath, String lunUuid, String flexVolName) {
                return null;
            }

            @Override public AccessGroup createAccessGroup(AccessGroup g) {
                return null;
            }

            @Override public void deleteAccessGroup(AccessGroup g) {
            }

            @Override public AccessGroup updateAccessGroup(AccessGroup g) {
                return null;
            }

            @Override public AccessGroup getAccessGroup(Map<String, String> m) {
                return null;
            }

            @Override public String enableLogicalAccess(Map<String, String> m) {
                return null;
            }

            @Override public void disableLogicalAccess(Map<String, String> m) {
            }

            @Override public String getLogicalAccess(Map<String, String> m) {
                return null;
            }
        }

        @BeforeEach
        void setUp() {
            strategy = newStrategy(ProtocolType.NFS3);
        }

        private StorageStrategy newStrategy(ProtocolType protocol) {
            StorageStrategy s = new TestStrategy(
                    new OntapStorage(AUTH_USER, AUTH_PASSWORD, "192.168.1.100", "svm1", 5_000_000_000L, protocol));
            s.aggregateFeignClient = aggregateFeignClient;
            s.volumeFeignClient = volumeFeignClient;
            s.svmFeignClient = svmFeignClient;
            s.jobFeignClient = jobFeignClient;
            s.sanFeignClient = sanFeignClient;
            s.snapshotFeignClient = snapshotFeignClient;
            s.clusterFeignClient = clusterFeignClient;
            s.qosFeignClient = qosFeignClient;
            s.emsFeignClient = emsFeignClient;
            s.nasFeignClient = nasFeignClient;
            s.networkFeignClient = networkFeignClient;
            return s;
        }

        // ------------------------------------------------------------------ helpers

        private FeignException feignError(int status, String message) {
            Request request = Request.create(Request.HttpMethod.GET, "http://test", Collections.emptyMap(),
                    (byte[]) null, (Charset) null);
            return new FeignException.FeignClientException(status, message, request, null);
        }

        private Version version(String json) throws Exception {
            return new ObjectMapper().readValue(json, Version.class);
        }

        private Job job(String uuid, String state, String message) {
            Job job = new Job();
            job.setUuid(uuid);
            job.setState(state);
            job.setMessage(message);
            return job;
        }

        private JobResponse jobResponse(String uuid) {
            JobResponse response = new JobResponse();
            response.setJob(job(uuid, OntapStorageConstants.JOB_RUNNING, null));
            return response;
        }

        private ClusterNode node(String model, Boolean allFlash, Boolean capacityOptimized) {
            ClusterNode node = new ClusterNode();
            node.setModel(model);
            node.setAllFlashOptimized(allFlash);
            node.setCapacityOptimized(capacityOptimized);
            return node;
        }

        private <T> OntapResponse<T> records(List<T> records) {
            OntapResponse<T> response = new OntapResponse<>();
            response.setRecords(records);
            return response;
        }

        private VolumeQosPolicy policy(String uuid, Integer objectCount) {
            VolumeQosPolicy policy = new VolumeQosPolicy();
            policy.setUuid(uuid);
            policy.setName("cs_policy");
            policy.setObjectCount(objectCount);
            return policy;
        }

        private Aggregate aggregate(String name, String uuid, Double availableBytes, String nodeName,
                Aggregate.StateEnum state) {
            Aggregate aggregate = new Aggregate();
            aggregate.setName(name);
            aggregate.setUuid(uuid);
            aggregate.setState(state);
            if (availableBytes != null) {
                Aggregate.AggregateSpaceBlockStorage blockStorage = new Aggregate.AggregateSpaceBlockStorage();
                blockStorage.setAvailable(availableBytes);
                Aggregate.AggregateSpace space = new Aggregate.AggregateSpace();
                space.setBlockStorage(blockStorage);
                aggregate.setSpace(space);
            }
            if (nodeName != null) {
                Aggregate.Node node = new Aggregate.Node();
                node.setName(nodeName);
                aggregate.setNode(node);
            }
            return aggregate;
        }

        // ------------------------------------------------------------- simple accessors

        @Test
        void accessors_exposeInjectedFeignClients() {
            assertSame(snapshotFeignClient, strategy.getSnapshotFeignClient());
            assertSame(nasFeignClient, strategy.getNasFeignClient());
        }

        @Test
        void getAuthHeader_matchesUtilsHeaderForConfiguredCredentials() {
            assertEquals(OntapStorageUtils.generateAuthHeader(AUTH_USER, AUTH_PASSWORD), strategy.getAuthHeader());
        }

        @Test
        void aggregates_roundTripThroughSetter() {
            List<Aggregate> aggregates = List.of(aggregate("a1", "u1", 1.0, "n1", Aggregate.StateEnum.ONLINE));

            strategy.setAggregates(aggregates);

            assertSame(aggregates, strategy.getAggregates());
        }

        // ------------------------------------------------------------- getClusterInfo

        @Test
        void getClusterInfo_returnsNull_whenClusterCallFails() {
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenThrow(feignError(500, "boom"));

            assertNull(strategy.getClusterInfo());
            verify(clusterFeignClient, never()).getClusterNodes(anyString(), anyMap());
        }

        @Test
        void getClusterInfo_returnsNull_whenClusterIsNotReturned() {
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(null);

            assertNull(strategy.getClusterInfo());
            verify(clusterFeignClient, never()).getClusterNodes(anyString(), anyMap());
        }

        @Test
        void getClusterInfo_rollsUpSingleFasNode() {
            Cluster cluster = new Cluster();
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
            when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                    .thenReturn(records(List.of(node(" FAS8300 ", false, null))));

            Cluster result = strategy.getClusterInfo();

            assertSame(cluster, result);
            assertEquals("FAS8300", result.getModel());
            assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_FAS, result.getPlatformType());
        }

        @Test
        void getClusterInfo_reportsPerformance_forAllFlashNode() {
            Cluster cluster = new Cluster();
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
            when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                    .thenReturn(records(List.of(node("AFF-A400", true, false))));

            assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_PERFORMANCE, strategy.getClusterInfo().getPlatformType());
        }

        @Test
        void getClusterInfo_reportsCapacity_forCapacityOptimizedNode() {
            Cluster cluster = new Cluster();
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
            when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                    .thenReturn(records(List.of(node("AFF-C250", true, true))));

            assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_CAPACITY, strategy.getClusterInfo().getPlatformType());
        }

        @Test
        void getClusterInfo_reportsComposite_andDeduplicatesModels_forMixedNodes() {
            Cluster cluster = new Cluster();
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
            when(clusterFeignClient.getClusterNodes(anyString(), anyMap())).thenReturn(records(Arrays.asList(
                    node("AFF-A400", true, false),
                    node("AFF-A400", true, false),
                    node("FAS8300", false, null),
                    null,
                    node("  ", null, null))));

            Cluster result = strategy.getClusterInfo();

            assertEquals("AFF-A400,FAS8300", result.getModel());
            assertEquals(OntapStorageConstants.ASUP_PLATFORM_TYPE_COMPOSITE, result.getPlatformType());
        }

        @Test
        void getClusterInfo_leavesPlatformTypeUnset_whenNodeOmitsAllFlashFlag() {
            Cluster cluster = new Cluster();
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
            when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                    .thenReturn(records(List.of(node("AFF-A400", null, null))));

            Cluster result = strategy.getClusterInfo();

            assertEquals("AFF-A400", result.getModel());
            assertNull(result.getPlatformType());
        }

        @Test
        void getClusterInfo_leavesHardwareUnset_whenNodeListIsEmptyOrMissing() {
            Cluster cluster = new Cluster();
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
            when(clusterFeignClient.getClusterNodes(anyString(), anyMap()))
                    .thenReturn(null)
                    .thenReturn(records(Collections.emptyList()))
                    .thenReturn(records(null));

            for (int i = 0; i < 3; i++) {
                Cluster result = strategy.getClusterInfo();
                assertNotNull(result);
                assertNull(result.getModel());
                assertNull(result.getPlatformType());
            }
        }

        @Test
        void getClusterInfo_stillReturnsCluster_whenNodeCallFails() {
            Cluster cluster = new Cluster();
            cluster.setName("cluster1");
            when(clusterFeignClient.getCluster(anyString(), eq(true))).thenReturn(cluster);
            when(clusterFeignClient.getClusterNodes(anyString(), anyMap())).thenThrow(feignError(500, "nodes down"));

            Cluster result = strategy.getClusterInfo();

            assertSame(cluster, result);
            assertNull(result.getModel());
        }

        // ------------------------------------------------------------- getClusterVersion

        @Test
        void getClusterVersion_returnsNull_forNullClusterOrVersion() {
            assertNull(strategy.getClusterVersion(null));
            assertNull(strategy.getClusterVersion(new Cluster()));
        }

        @Test
        void getClusterVersion_prefersNumericGenerationMajorMinor() throws Exception {
            Cluster cluster = new Cluster();
            cluster.setVersion(version("{\"generation\":9,\"major\":17,\"minor\":1,\"full\":\"NetApp Release 9.17.1: Mon\"}"));

            assertEquals("9.17.1", strategy.getClusterVersion(cluster));
        }

        @Test
        void getClusterVersion_fallsBackToFull_whenNumericPartsIncomplete() throws Exception {
            Cluster cluster = new Cluster();
            cluster.setVersion(version("{\"generation\":9,\"major\":17,\"full\":\"NetApp Release 9.17\"}"));

            assertEquals("NetApp Release 9.17", strategy.getClusterVersion(cluster));
        }

        @Test
        void getClusterVersion_returnsNull_whenNothingUsable() throws Exception {
            Cluster noFull = new Cluster();
            noFull.setVersion(version("{\"generation\":9}"));
            Cluster emptyFull = new Cluster();
            emptyFull.setVersion(version("{\"full\":\"\"}"));

            assertNull(strategy.getClusterVersion(noFull));
            assertNull(strategy.getClusterVersion(emptyFull));
        }

        // ------------------------------------------------------------- sendAsupMessage

        @Test
        void sendAsupMessage_ignoresNullMessage() {
            strategy.sendAsupMessage(null);

            verifyNoInteractions(emsFeignClient);
        }

        @Test
        void sendAsupMessage_postsMessageToEms() {
            EmsApplicationLog message = new EmsApplicationLog();
            message.setEventId("cloudstack.asup");

            strategy.sendAsupMessage(message);

            verify(emsFeignClient).sendEmsApplicationLog(eq(strategy.getAuthHeader()), eq(message));
        }

        @Test
        void sendAsupMessage_swallowsFailures() {
            EmsApplicationLog message = new EmsApplicationLog();
            message.setEventId("cloudstack.asup");
            org.mockito.Mockito.doThrow(feignError(503, "ems down")).when(emsFeignClient)
                    .sendEmsApplicationLog(anyString(), any(EmsApplicationLog.class));

            strategy.sendAsupMessage(message);

            verify(emsFeignClient, times(1)).sendEmsApplicationLog(anyString(), any(EmsApplicationLog.class));
        }

        // ------------------------------------------------------------- connect / svm

        private Svm svm(String state, Boolean nfs, Boolean iscsi) {
            Svm svm = new Svm();
            svm.setName("svm1");
            svm.setUuid("svm-uuid");
            svm.setState(state);
            svm.setNfsEnabled(nfs);
            svm.setIscsiEnabled(iscsi);
            return svm;
        }

        @Test
        void connect_recordsResolvedSvmUuid_whenAggregateValidationSkipped() {
            when(svmFeignClient.getSvmResponse(anyMap(), anyString()))
                    .thenReturn(records(List.of(svm(OntapStorageConstants.RUNNING, true, false))));

            assertNull(strategy.getResolvedSvmUuid());
            assertTrue(strategy.connect(false));

            assertEquals("svm-uuid", strategy.getResolvedSvmUuid());
            verifyNoInteractions(aggregateFeignClient);
        }

        @Test
        void connect_fails_whenSvmNotFound() {
            when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(records(Collections.emptyList()));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.connect(false));

            assertTrue(ex.getMessage().contains("No SVM found"), ex.getMessage());
        }

        @Test
        void connect_fails_whenSvmResponseIsNull() {
            when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.connect(false));

            assertEquals("No SVM found on the ONTAP cluster by the name svm1.", ex.getMessage());
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(svmFeignClient).getSvmResponse(query.capture(), anyString());
            assertEquals("svm1", query.getValue().get(OntapStorageConstants.NAME));
            assertEquals(OntapStorageConstants.AGGREGATES + OntapStorageConstants.COMMA + OntapStorageConstants.STATE
                    + OntapStorageConstants.COMMA + OntapStorageConstants.NFS_ENABLED + OntapStorageConstants.COMMA
                    + OntapStorageConstants.ISCSI_ENABLED, query.getValue().get(OntapStorageConstants.FIELDS));
        }

        @Test
        void connect_fails_whenSvmNotRunning() {
            when(svmFeignClient.getSvmResponse(anyMap(), anyString()))
                    .thenReturn(records(List.of(svm("stopped", true, true))));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.connect(false));

            assertTrue(ex.getMessage().contains("not in running state"), ex.getMessage());
        }

        @Test
        void connect_fails_whenNfsNotEnabledForNfsPool() {
            when(svmFeignClient.getSvmResponse(anyMap(), anyString()))
                    .thenReturn(records(List.of(svm(OntapStorageConstants.RUNNING, false, true))));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.connect(false));

            assertTrue(ex.getMessage().contains("NFS protocol is not enabled"), ex.getMessage());
        }

        @Test
        void connect_fails_whenIscsiNotEnabledForIscsiPool() {
            StorageStrategy iscsi = newStrategy(ProtocolType.ISCSI);
            when(svmFeignClient.getSvmResponse(anyMap(), anyString()))
                    .thenReturn(records(List.of(svm(OntapStorageConstants.RUNNING, true, false))));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> iscsi.connect(false));

            assertTrue(ex.getMessage().contains("ISCSI protocol is not enabled"), ex.getMessage());
        }

        @Test
        void connect_wrapsUnexpectedFailures() {
            when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenThrow(new IllegalStateException("socket closed"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.connect(false));

            assertTrue(ex.getMessage().contains("Failed to connect to ONTAP cluster"), ex.getMessage());
            assertTrue(ex.getCause() instanceof IllegalStateException);
        }

        @Test
        void connect_failsWithAggregateError_whenSvmHasNoAggregates() {
            Svm noAggregates = svm(OntapStorageConstants.RUNNING, true, true);
            noAggregates.setAggregates(Collections.emptyList());
            when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(records(List.of(noAggregates)));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.connect(true));

            assertTrue(ex.getMessage().contains("No aggregates are assigned"), ex.getMessage());
        }

        @Test
        void connect_failsWhenNoAggregateIsOnlineWithEnoughSpace() {
            Svm withAggregates = svm(OntapStorageConstants.RUNNING, true, true);
            withAggregates.setAggregates(List.of(
                    aggregate("offline", "u-offline", null, null, null),
                    aggregate("missing", "u-missing", null, null, null),
                    aggregate("small", "u-small", null, null, null)));
            when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(records(List.of(withAggregates)));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-offline"), anyMap()))
                    .thenReturn(aggregate("offline", "u-offline", 9e12, "n1", null));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-missing"), anyMap())).thenReturn(null);
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-small"), anyMap()))
                    .thenReturn(aggregate("small", "u-small", 1.0, "n1", Aggregate.StateEnum.ONLINE));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, () -> strategy.connect(true));

            assertTrue(ex.getMessage().contains("No suitable aggregates"), ex.getMessage());
        }

        // ------------------------------------------------------------- chooseAggregate

        @Test
        void chooseAggregate_rejectsEmptyCandidates() {
            CloudRuntimeException nullList = assertThrows(CloudRuntimeException.class,
                    () -> strategy.chooseAggregate(null, 10L));
            CloudRuntimeException emptyList = assertThrows(CloudRuntimeException.class,
                    () -> strategy.chooseAggregate(Collections.emptyList(), 10L));

            assertEquals("No aggregates available to create volume on SVM svm1", nullList.getMessage());
            assertEquals("No aggregates available to create volume on SVM svm1", emptyList.getMessage());
            verifyNoInteractions(aggregateFeignClient);
        }

        @Test
        void chooseAggregate_rejectsInvalidSize() {
            List<Aggregate> candidates = List.of(aggregate("a1", "u1", null, null, null));

            assertEquals("Invalid volume size provided: null",
                    assertThrows(CloudRuntimeException.class, () -> strategy.chooseAggregate(candidates, null)).getMessage());
            assertEquals("Invalid volume size provided: 0",
                    assertThrows(CloudRuntimeException.class, () -> strategy.chooseAggregate(candidates, 0L)).getMessage());
            assertEquals("Invalid volume size provided: -5",
                    assertThrows(CloudRuntimeException.class, () -> strategy.chooseAggregate(candidates, -5L)).getMessage());
            verifyNoInteractions(aggregateFeignClient);
        }

        @Test
        void chooseAggregate_picksLargestEligibleAggregate_andSkipsUnusableOnes() {
            List<Aggregate> candidates = Arrays.asList(
                    aggregate("gone", "u-gone", null, null, null),
                    aggregate("offline", "u-offline", null, null, null),
                    aggregate("nospace", "u-nospace", null, null, null),
                    aggregate("tiny", "u-tiny", null, null, null),
                    aggregate("small", "u-small", null, null, null),
                    aggregate("big", "u-big", null, null, null));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-gone"), anyMap())).thenReturn(null);
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-offline"), anyMap()))
                    .thenReturn(aggregate("offline", "u-offline", 9e12, "n1", null));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-nospace"), anyMap()))
                    .thenReturn(aggregate("nospace", "u-nospace", null, "n1", Aggregate.StateEnum.ONLINE));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-tiny"), anyMap()))
                    .thenReturn(aggregate("tiny", "u-tiny", 100.0, "n1", Aggregate.StateEnum.ONLINE));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-small"), anyMap()))
                    .thenReturn(aggregate("small", "u-small", 5000.0, "n2", Aggregate.StateEnum.ONLINE));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-big"), anyMap()))
                    .thenReturn(aggregate("big", "u-big", 90000.0, "n3", Aggregate.StateEnum.ONLINE));

            Aggregate chosen = strategy.chooseAggregate(candidates, 1000L);

            assertEquals("big", chosen.getName());
            assertEquals("n3", chosen.getNode().getName());
        }

        @Test
        void chooseAggregate_fails_whenNoAggregateFitsRequestedSize() {
            List<Aggregate> candidates = List.of(aggregate("a1", "u1", null, null, null));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u1"), anyMap()))
                    .thenReturn(aggregate("a1", "u1", 1000.0, "n1", Aggregate.StateEnum.ONLINE));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.chooseAggregate(candidates, 1000L));

            assertTrue(ex.getMessage().contains("No suitable aggregates"), ex.getMessage());
        }

        @Test
        void chooseAggregate_fails_whenChosenAggregateHasNoNodeName() {
            List<Aggregate> candidates = List.of(aggregate("a1", "u1", null, null, null));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u1"), anyMap()))
                    .thenReturn(aggregate("a1", "u1", 9000.0, null, Aggregate.StateEnum.ONLINE));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.chooseAggregate(candidates, 1000L));

            assertTrue(ex.getMessage().contains("node name"), ex.getMessage());
        }

        // ------------------------------------------------------------- createStorageVolume

        private StorageStrategy strategyWithInstantJobs() {
            StorageStrategy spied = spy(strategy);
            doReturn(true).when(spied).jobPollForSuccess(anyString(), anyInt(), anyInt());
            return spied;
        }

        private Volume namedVolume(String name, String uuid) {
            Volume volume = new Volume();
            volume.setName(name);
            volume.setUuid(uuid);
            return volume;
        }

        private Aggregate chosenAggregate() {
            return aggregate("aggr1", "aggr-uuid", null, "node1", Aggregate.StateEnum.ONLINE);
        }

        private void assertVol1WasCreated(int times, long size) {
            ArgumentCaptor<Volume> request = ArgumentCaptor.forClass(Volume.class);
            verify(volumeFeignClient, times(times)).createVolumeWithJob(anyString(), request.capture());
            for (Volume sent : request.getAllValues()) {
                assertEquals("vol1", sent.getName());
                assertEquals(size, sent.getSize());
                assertEquals("svm1", sent.getSvm().getName());
                assertEquals("/vol1", sent.getNas().getPath());
                assertEquals("aggr-uuid", sent.getAggregates().get(0).getUuid());
            }
        }

        @SuppressWarnings("unchecked")
        private void assertVolumesWereListedByName(int times) {
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(volumeFeignClient, times(times)).getAllVolumes(anyString(), query.capture());
            for (Map<String, Object> value : query.getAllValues()) {
                assertEquals("vol1", value.get(OntapStorageConstants.NAME));
            }
        }

        @SuppressWarnings("unchecked")
        private void assertVolumeDetailsWereReadByName(int times) {
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(volumeFeignClient, times(times)).getVolume(anyString(), query.capture());
            for (Map<String, Object> value : query.getAllValues()) {
                assertEquals("vol1", value.get(OntapStorageConstants.NAME));
            }
        }

        private void assertQosPolicyWasSent(int times) {
            ArgumentCaptor<VolumeQosPolicy> sent = ArgumentCaptor.forClass(VolumeQosPolicy.class);
            verify(qosFeignClient, times(times)).createPolicy(anyString(), sent.capture());
            for (VolumeQosPolicy policy : sent.getAllValues()) {
                assertEquals("cs_policy", policy.getName());
                assertEquals("svm1", policy.getSvm().getName());
                assertNull(policy.getFixed().getMinThroughputIops());
                assertEquals(100L, policy.getFixed().getMaxThroughputIops());
            }
        }

        @SuppressWarnings("unchecked")
        private void assertQosPolicyWasLookedUp(int times) {
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(qosFeignClient, times(times)).getPolicies(anyString(), query.capture());
            for (Map<String, Object> value : query.getAllValues()) {
                assertEquals("cs_policy", value.get(OntapStorageConstants.NAME));
                assertEquals("svm1", value.get(OntapStorageConstants.SVM_DOT_NAME));
            }
        }

        @SuppressWarnings("unchecked")
        private void assertIscsiServiceWasQueried(int times) {
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(sanFeignClient, times(times)).getIscsiServices(anyString(), query.capture());
            for (Map<String, Object> value : query.getAllValues()) {
                assertEquals("svm1", value.get(OntapStorageConstants.SVM_DOT_NAME));
                assertEquals("enabled,target", value.get(OntapStorageConstants.FIELDS));
                assertEquals("1", value.get("max_records"));
            }
        }

        @Test
        void createStorageVolume_rejectsInvalidSize() {
            assertThrows(CloudRuntimeException.class, () -> strategy.createStorageVolume("vol", null, chosenAggregate()));
            assertThrows(CloudRuntimeException.class, () -> strategy.createStorageVolume("vol", 0L, chosenAggregate()));
            verifyNoInteractions(volumeFeignClient);
        }

        @Test
        void createStorageVolume_rejectsIncompleteAggregate() {
            assertThrows(CloudRuntimeException.class, () -> strategy.createStorageVolume("vol", 10L, null));
            assertThrows(CloudRuntimeException.class,
                    () -> strategy.createStorageVolume("vol", 10L, aggregate(null, "u", null, null, null)));
            assertThrows(CloudRuntimeException.class,
                    () -> strategy.createStorageVolume("vol", 10L, aggregate("n", null, null, null, null)));
            verifyNoInteractions(volumeFeignClient);
        }

        @Test
        void createStorageVolume_createsThinNasVolumeOnChosenAggregate() {
            StorageStrategy spied = strategyWithInstantJobs();
            Volume created = namedVolume("vol1", "vol-uuid");
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));
            when(volumeFeignClient.getAllVolumes(anyString(), anyMap())).thenReturn(records(List.of(created)));
            when(volumeFeignClient.getVolume(anyString(), anyMap())).thenReturn(records(List.of(created)));

            Volume result = spied.createStorageVolume("vol1", 2048L, chosenAggregate());

            assertSame(created, result);
            ArgumentCaptor<Volume> request = ArgumentCaptor.forClass(Volume.class);
            verify(volumeFeignClient).createVolumeWithJob(anyString(), request.capture());
            assertEquals("vol1", request.getValue().getName());
            assertEquals(2048L, request.getValue().getSize());
            assertEquals("svm1", request.getValue().getSvm().getName());
            assertEquals("/vol1", request.getValue().getNas().getPath());
            assertEquals(Volume.Guarantee.TypeEnum.NONE, request.getValue().getGuarantee().getType());
            assertEquals("aggr-uuid", request.getValue().getAggregates().get(0).getUuid());
        }

        @Test
        void createStorageVolume_fails_whenCreateCallReturnsNoJob() {
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(new JobResponse());

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.createStorageVolume("vol1", 10L, chosenAggregate()));

            assertEquals("Failed to create volume: Failed to initiate volume creation for vol1", ex.getMessage());
            assertVol1WasCreated(1, 10L);
            verify(volumeFeignClient, never()).getAllVolumes(anyString(), anyMap());
        }

        @Test
        void createStorageVolume_fails_whenCreateCallReturnsNull() {
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.createStorageVolume("vol1", 10L, chosenAggregate()));

            assertEquals("Failed to create volume: Failed to initiate volume creation for vol1", ex.getMessage());
            ArgumentCaptor<Volume> request = ArgumentCaptor.forClass(Volume.class);
            verify(volumeFeignClient).createVolumeWithJob(anyString(), request.capture());
            assertEquals("vol1", request.getValue().getName());
            assertEquals(10L, request.getValue().getSize());
            assertEquals("svm1", request.getValue().getSvm().getName());
            assertEquals("aggr-uuid", request.getValue().getAggregates().get(0).getUuid());
            verify(volumeFeignClient, never()).getAllVolumes(anyString(), anyMap());
        }

        @Test
        void createStorageVolume_fails_whenJobDoesNotSucceed() {
            StorageStrategy spied = spy(strategy);
            doReturn(false).when(spied).jobPollForSuccess(eq("job-1"), eq(30), eq(2000));
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> spied.createStorageVolume("vol1", 10L, chosenAggregate()));

            assertEquals("Failed to create volume: Volume creation job failed for volume: vol1", ex.getMessage());
            assertVol1WasCreated(1, 10L);
            verify(spied).jobPollForSuccess("job-1", 30, 2000);
            verify(volumeFeignClient, never()).getAllVolumes(anyString(), anyMap());
        }

        @Test
        void createStorageVolume_wrapsFeignFailureOnCreate() {
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenThrow(feignError(400, "bad"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.createStorageVolume("vol1", 10L, chosenAggregate()));

            assertTrue(ex.getMessage().startsWith("Failed to create volume: "), ex.getMessage());
            assertTrue(ex.getMessage().contains("bad"), ex.getMessage());
            assertVol1WasCreated(1, 10L);
            verify(volumeFeignClient, never()).getAllVolumes(anyString(), anyMap());
        }

        @Test
        void createStorageVolume_fails_whenVolumeNotListedAfterCreation() {
            StorageStrategy spied = strategyWithInstantJobs();
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));
            when(volumeFeignClient.getAllVolumes(anyString(), anyMap()))
                    .thenReturn(null)
                    .thenReturn(records(null))
                    .thenReturn(records(Collections.emptyList()));

            for (int i = 0; i < 3; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> spied.createStorageVolume("vol1", 10L, chosenAggregate()));
                assertEquals("Volume vol1 not found after creation.", ex.getMessage());
            }
            assertVol1WasCreated(3, 10L);
            assertVolumesWereListedByName(3);
        }

        @Test
        void createStorageVolume_fails_whenListedRecordIsNull() {
            StorageStrategy spied = strategyWithInstantJobs();
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));
            when(volumeFeignClient.getAllVolumes(anyString(), anyMap()))
                    .thenReturn(records(Collections.singletonList((Volume) null)));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> spied.createStorageVolume("vol1", 10L, chosenAggregate()));

            assertEquals("Failed to retrieve details of the created volume vol1", ex.getMessage());
            assertVol1WasCreated(1, 10L);
            assertVolumesWereListedByName(1);
        }

        @Test
        void createStorageVolume_fails_whenListedVolumeNameDiffers() {
            StorageStrategy spied = strategyWithInstantJobs();
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));
            when(volumeFeignClient.getAllVolumes(anyString(), anyMap()))
                    .thenReturn(records(List.of(namedVolume("other", "u"))))
                    .thenReturn(records(List.of(namedVolume(null, "u"))));

            String[] expected = {
                    "Mismatch in created volume name. Expected: vol1, Found: other",
                    "Mismatch in created volume name. Expected: vol1, Found: null"
            };
            for (String message : expected) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> spied.createStorageVolume("vol1", 10L, chosenAggregate()));
                assertEquals(message, ex.getMessage());
            }
            assertVol1WasCreated(2, 10L);
            assertVolumesWereListedByName(2);
        }

        @Test
        void createStorageVolume_fails_whenVolumeDetailsCannotBeFetched() {
            StorageStrategy spied = strategyWithInstantJobs();
            Volume created = namedVolume("vol1", "vol-uuid");
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));
            when(volumeFeignClient.getAllVolumes(anyString(), anyMap())).thenReturn(records(List.of(created)));
            when(volumeFeignClient.getVolume(anyString(), anyMap()))
                    .thenReturn(null)
                    .thenReturn(records(null))
                    .thenReturn(records(Collections.emptyList()));

            String[] expected = {
                    "Failed to fetch volume: vol1. Error: Failed to fetch volume vol1: Response is null",
                    "Failed to fetch volume: vol1. Error: Failed to fetch volume vol1: Records list is null",
                    "Failed to fetch volume: vol1. Error: Failed to fetch volume vol1: No records found"
            };
            for (String message : expected) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> spied.createStorageVolume("vol1", 10L, chosenAggregate()));
                assertEquals(message, ex.getMessage());
            }
            assertVol1WasCreated(3, 10L);
            assertVolumesWereListedByName(3);
            assertVolumeDetailsWereReadByName(3);
        }

        // ------------------------------------------------------------- delete/get volume

        @Test
        void deleteStorageVolume_treatsMissingVolumeAsNoOp() {
            when(volumeFeignClient.deleteVolume(anyString(), eq("vol-uuid"))).thenThrow(feignError(404, "gone"));

            strategy.deleteStorageVolume(namedVolume("vol1", "vol-uuid"));

            verifyNoInteractions(jobFeignClient);
        }

        @Test
        void deleteStorageVolume_wrapsOtherFeignFailures() {
            when(volumeFeignClient.deleteVolume(anyString(), eq("vol-uuid"))).thenThrow(feignError(500, "server error"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteStorageVolume(namedVolume("vol1", "vol-uuid")));

            assertTrue(ex.getMessage().startsWith("Failed to delete volume"), ex.getMessage());
        }

        @Test
        void getStorageVolume_rejectsBlankUuid() {
            assertThrows(CloudRuntimeException.class, () -> strategy.getStorageVolume(null, null));
            assertThrows(CloudRuntimeException.class, () -> strategy.getStorageVolume("  ", null));
            verifyNoInteractions(volumeFeignClient);
        }

        @Test
        void getStorageVolume_usesEmptyQuery_whenNoneProvided() {
            Volume volume = namedVolume("vol1", "u1");
            when(volumeFeignClient.getVolumeByUUID(anyString(), eq("u1"), eq(Collections.emptyMap()))).thenReturn(volume);

            assertSame(volume, strategy.getStorageVolume("u1", null));
        }

        @Test
        void getStorageVolume_returnsNull_whenOntapReportsNotFound() {
            when(volumeFeignClient.getVolumeByUUID(anyString(), eq("u1"), anyMap())).thenThrow(feignError(404, "missing"));

            assertNull(strategy.getStorageVolume("u1", Map.of("fields", "space.used")));

            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(volumeFeignClient).getVolumeByUUID(anyString(), eq("u1"), query.capture());
            assertEquals("space.used", query.getValue().get("fields"));
        }

        @Test
        void getStorageVolume_wrapsOtherFailures() {
            when(volumeFeignClient.getVolumeByUUID(anyString(), eq("u1"), anyMap())).thenThrow(feignError(500, "oops"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.getStorageVolume("u1", Map.of()));

            assertTrue(ex.getMessage().contains("Failed to fetch volume"), ex.getMessage());
            assertTrue(ex.getMessage().contains("oops"), ex.getMessage());
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(volumeFeignClient).getVolumeByUUID(anyString(), eq("u1"), query.capture());
            assertTrue(query.getValue().isEmpty(), query.getValue().toString());
        }

        // ------------------------------------------------------------- getStoragePath

        private IscsiService iscsiService(String targetName) {
            IscsiService service = new IscsiService();
            if (targetName != null) {
                IscsiService.IscsiServiceTarget target = new IscsiService.IscsiServiceTarget();
                target.setName(targetName);
                service.setTarget(target);
            }
            return service;
        }

        @Test
        void getStoragePath_returnsTargetIqn_forIscsi() {
            StorageStrategy iscsi = newStrategy(ProtocolType.ISCSI);
            when(sanFeignClient.getIscsiServices(anyString(), anyMap()))
                    .thenReturn(records(List.of(iscsiService("iqn.1992-08.com.netapp:sn.1:vs.3"))));

            assertEquals("iqn.1992-08.com.netapp:sn.1:vs.3", iscsi.getStoragePath());

            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(sanFeignClient).getIscsiServices(anyString(), query.capture());
            assertEquals("svm1", query.getValue().get(OntapStorageConstants.SVM_DOT_NAME));
            assertEquals("enabled,target", query.getValue().get(OntapStorageConstants.FIELDS));
            assertEquals("1", query.getValue().get("max_records"));
        }

        @Test
        void getStoragePath_fails_whenIscsiServiceMissing() {
            StorageStrategy iscsi = newStrategy(ProtocolType.ISCSI);
            when(sanFeignClient.getIscsiServices(anyString(), anyMap()))
                    .thenReturn(null)
                    .thenReturn(records(null))
                    .thenReturn(records(Collections.emptyList()));

            for (int i = 0; i < 3; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, iscsi::getStoragePath);
                assertEquals("No iSCSI service found for SVM: svm1", ex.getMessage());
            }
            assertIscsiServiceWasQueried(3);
        }

        @Test
        void getStoragePath_fails_whenIscsiTargetHasNoName() {
            StorageStrategy iscsi = newStrategy(ProtocolType.ISCSI);
            IscsiService noName = new IscsiService();
            noName.setTarget(new IscsiService.IscsiServiceTarget());
            when(sanFeignClient.getIscsiServices(anyString(), anyMap()))
                    .thenReturn(records(List.of(iscsiService(null))))
                    .thenReturn(records(List.of(noName)));

            for (int i = 0; i < 2; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, iscsi::getStoragePath);
                assertEquals("iSCSI target IQN not found for SVM: svm1", ex.getMessage());
            }
            assertIscsiServiceWasQueried(2);
        }

        @Test
        void getStoragePath_wrapsFeignClientErrors() {
            StorageStrategy iscsi = newStrategy(ProtocolType.ISCSI);
            when(sanFeignClient.getIscsiServices(anyString(), anyMap())).thenThrow(feignError(401, "unauthorized"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class, iscsi::getStoragePath);

            assertTrue(ex.getMessage().startsWith("Failed to retrieve storage path: "), ex.getMessage());
            assertTrue(ex.getMessage().contains("unauthorized"), ex.getMessage());
            assertIscsiServiceWasQueried(1);
        }

        @Test
        void getStoragePath_returnsNull_forNfs() {
            assertNull(strategy.getStoragePath());
            verifyNoInteractions(sanFeignClient);
        }

        @Test
        void getStoragePath_rejectsUnsupportedProtocol() {
            StorageStrategy noProtocol = newStrategy(null);

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, noProtocol::getStoragePath);

            assertEquals("Unsupported protocol for path retrieval: null", thrown.getMessage());
            verifyNoInteractions(sanFeignClient);
        }

        // ------------------------------------------------------------- QoS: create

        @Test
        void createVolumeQosPolicy_buildsFixedPolicyForSvm_andReturnsLookedUpPolicy() {
            VolumeQosPolicy found = policy("policy-uuid", 0);
            when(qosFeignClient.createPolicy(anyString(), any(VolumeQosPolicy.class))).thenReturn(null);
            when(qosFeignClient.getPolicies(anyString(), anyMap())).thenReturn(records(List.of(found)));

            VolumeQosPolicy result = strategy.createVolumeQosPolicy("cs_policy", 100L, 500L);

            assertSame(found, result);
            ArgumentCaptor<VolumeQosPolicy> sent = ArgumentCaptor.forClass(VolumeQosPolicy.class);
            verify(qosFeignClient).createPolicy(anyString(), sent.capture());
            assertEquals("cs_policy", sent.getValue().getName());
            assertEquals("svm1", sent.getValue().getSvm().getName());
            assertEquals(100L, sent.getValue().getFixed().getMinThroughputIops());
            assertEquals(500L, sent.getValue().getFixed().getMaxThroughputIops());
            assertFalse(sent.getValue().getFixed().getCapacityShared());
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(qosFeignClient).getPolicies(anyString(), query.capture());
            assertEquals("cs_policy", query.getValue().get(OntapStorageConstants.NAME));
            assertEquals("svm1", query.getValue().get(OntapStorageConstants.SVM_DOT_NAME));
        }

        @Test
        void createVolumeQosPolicy_omitsNonPositiveAndMissingLimits() {
            when(qosFeignClient.getPolicies(anyString(), anyMap())).thenReturn(records(List.of(policy("u", 0))));

            strategy.createVolumeQosPolicy("p1", 0L, null);
            strategy.createVolumeQosPolicy("p2", null, -1L);

            ArgumentCaptor<VolumeQosPolicy> sent = ArgumentCaptor.forClass(VolumeQosPolicy.class);
            verify(qosFeignClient, times(2)).createPolicy(anyString(), sent.capture());
            for (VolumeQosPolicy policy : sent.getAllValues()) {
                assertNull(policy.getFixed().getMinThroughputIops());
                assertNull(policy.getFixed().getMaxThroughputIops());
            }
        }

        @Test
        void createVolumeQosPolicy_pollsAsyncJobWhenReturned() {
            StorageStrategy spied = spy(strategy);
            JobResponse response = jobResponse("qos-job");
            doNothing().when(spied).pollJobIfPresent(any(JobResponse.class), anyString());
            when(qosFeignClient.createPolicy(anyString(), any(VolumeQosPolicy.class))).thenReturn(response);
            when(qosFeignClient.getPolicies(anyString(), anyMap())).thenReturn(records(List.of(policy("u", 0))));

            spied.createVolumeQosPolicy("p1", null, 100L);

            verify(spied).pollJobIfPresent(eq(response), anyString());
        }

        @Test
        void createVolumeQosPolicy_reusesExistingPolicy_onConflict() {
            VolumeQosPolicy existing = policy("existing-uuid", 3);
            when(qosFeignClient.createPolicy(anyString(), any(VolumeQosPolicy.class))).thenThrow(feignError(409, "exists"));
            when(qosFeignClient.getPolicies(anyString(), anyMap())).thenReturn(records(List.of(existing)));

            assertSame(existing, strategy.createVolumeQosPolicy("cs_policy", null, 100L));

            ArgumentCaptor<VolumeQosPolicy> sent = ArgumentCaptor.forClass(VolumeQosPolicy.class);
            verify(qosFeignClient).createPolicy(anyString(), sent.capture());
            assertEquals("cs_policy", sent.getValue().getName());
            assertEquals("svm1", sent.getValue().getSvm().getName());
            assertNull(sent.getValue().getFixed().getMinThroughputIops());
            assertEquals(100L, sent.getValue().getFixed().getMaxThroughputIops());
            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(qosFeignClient).getPolicies(anyString(), query.capture());
            assertEquals("cs_policy", query.getValue().get(OntapStorageConstants.NAME));
            assertEquals("svm1", query.getValue().get(OntapStorageConstants.SVM_DOT_NAME));
        }

        @Test
        void createVolumeQosPolicy_failsOnOtherCreateErrors_withoutLookup() {
            when(qosFeignClient.createPolicy(anyString(), any(VolumeQosPolicy.class))).thenThrow(feignError(500, "bad"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.createVolumeQosPolicy("cs_policy", null, 100L));

            assertTrue(ex.getMessage().contains("Failed to create ONTAP QoS policy [cs_policy]"), ex.getMessage());
            assertTrue(ex.getMessage().contains("bad"), ex.getMessage());
            assertQosPolicyWasSent(1);
            verify(qosFeignClient, never()).getPolicies(anyString(), anyMap());
        }

        @Test
        void createVolumeQosPolicy_failsWhenPolicyCannotBeFoundAfterCreate() {
            when(qosFeignClient.getPolicies(anyString(), anyMap()))
                    .thenReturn(null)
                    .thenReturn(records(null))
                    .thenReturn(records(Collections.emptyList()));

            for (int i = 0; i < 3; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> strategy.createVolumeQosPolicy("cs_policy", null, 100L));
                assertEquals("Unable to get ONTAP QoS policy [cs_policy] after creation", ex.getMessage());
            }
            assertQosPolicyWasSent(3);
            assertQosPolicyWasLookedUp(3);
        }

        @Test
        void createVolumeQosPolicy_failsWhenLookedUpPolicyHasNoUuid() {
            when(qosFeignClient.getPolicies(anyString(), anyMap()))
                    .thenReturn(records(Collections.singletonList((VolumeQosPolicy) null)))
                    .thenReturn(records(List.of(policy("", 0))));

            for (int i = 0; i < 2; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> strategy.createVolumeQosPolicy("cs_policy", null, 100L));
                assertEquals("Failed to fetch ONTAP QoS policy [cs_policy]: empty response", ex.getMessage());
            }
            assertQosPolicyWasSent(2);
            assertQosPolicyWasLookedUp(2);
        }

        @Test
        void createVolumeQosPolicy_failsWhenLookupThrows() {
            when(qosFeignClient.getPolicies(anyString(), anyMap())).thenThrow(feignError(500, "lookup failed"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.createVolumeQosPolicy("cs_policy", null, 100L));

            assertTrue(ex.getMessage().contains("Failed to fetch ONTAP QoS policy [cs_policy]"), ex.getMessage());
            assertTrue(ex.getMessage().contains("lookup failed"), ex.getMessage());
            assertQosPolicyWasSent(1);
            assertQosPolicyWasLookedUp(1);
        }

        // ------------------------------------------------------------- QoS: delete

        @Test
        void deleteVolumeQosPolicy_ignoresNullOrEmptyUuid() {
            strategy.deleteVolumeQosPolicy(null);
            strategy.deleteVolumeQosPolicy("");

            verifyNoInteractions(qosFeignClient);
        }

        @Test
        void deleteVolumeQosPolicy_skips_whenPolicyAlreadyGone() {
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap())).thenThrow(feignError(404, "not found"));

            strategy.deleteVolumeQosPolicy("p-uuid");

            verify(qosFeignClient, never()).deletePolicy(anyString(), anyString());
        }

        @Test
        void deleteVolumeQosPolicy_failsWhenLookupFailsForOtherReasons() {
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap())).thenThrow(feignError(500, "server error"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteVolumeQosPolicy("p-uuid"));

            assertTrue(ex.getMessage().contains("Failed to fetch ONTAP QoS policy"), ex.getMessage());
            verify(qosFeignClient, never()).deletePolicy(anyString(), anyString());
        }

        @Test
        void deleteVolumeQosPolicy_failsWhenLookupReturnsEmptyPolicy() {
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap()))
                    .thenReturn(null)
                    .thenReturn(policy("", 0));

            for (int i = 0; i < 2; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> strategy.deleteVolumeQosPolicy("p-uuid"));
                assertTrue(ex.getMessage().contains("empty response"), ex.getMessage());
            }
            verify(qosFeignClient, never()).deletePolicy(anyString(), anyString());
        }

        @Test
        void deleteVolumeQosPolicy_keepsPolicyThatIsStillInUse() {
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap())).thenReturn(policy("p-uuid", 2));

            strategy.deleteVolumeQosPolicy("p-uuid");

            verify(qosFeignClient, never()).deletePolicy(anyString(), anyString());
        }

        @Test
        void deleteVolumeQosPolicy_deletesUnusedPolicy() {
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap())).thenReturn(policy("p-uuid", 0));

            strategy.deleteVolumeQosPolicy("p-uuid");

            verify(qosFeignClient).deletePolicy(anyString(), eq("p-uuid"));
        }

        @Test
        void deleteVolumeQosPolicy_deletesPolicyWithUnknownObjectCount_andPollsJob() {
            StorageStrategy spied = spy(strategy);
            JobResponse response = jobResponse("del-job");
            doNothing().when(spied).pollJobIfPresent(any(JobResponse.class), anyString());
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap())).thenReturn(policy("p-uuid", null));
            when(qosFeignClient.deletePolicy(anyString(), eq("p-uuid"))).thenReturn(response);

            spied.deleteVolumeQosPolicy("p-uuid");

            verify(spied).pollJobIfPresent(eq(response), anyString());
        }

        @Test
        void deleteVolumeQosPolicy_toleratesConflictAndMissingOnDelete() {
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap())).thenReturn(policy("p-uuid", 0));
            when(qosFeignClient.deletePolicy(anyString(), eq("p-uuid")))
                    .thenThrow(feignError(409, "in use"))
                    .thenThrow(feignError(404, "gone"))
                    .thenThrow(new IllegalStateException("entry doesn't exist"));

            strategy.deleteVolumeQosPolicy("p-uuid");
            strategy.deleteVolumeQosPolicy("p-uuid");
            strategy.deleteVolumeQosPolicy("p-uuid");

            verify(qosFeignClient, times(3)).deletePolicy(anyString(), eq("p-uuid"));
        }

        @Test
        void deleteVolumeQosPolicy_failsOnUnexpectedDeleteErrors() {
            when(qosFeignClient.getPolicy(anyString(), eq("p-uuid"), anyMap())).thenReturn(policy("p-uuid", 0));
            when(qosFeignClient.deletePolicy(anyString(), eq("p-uuid")))
                    .thenThrow(feignError(500, "server error"))
                    .thenThrow(new IllegalStateException("boom"));

            for (int i = 0; i < 2; i++) {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> strategy.deleteVolumeQosPolicy("p-uuid"));
                assertTrue(ex.getMessage().startsWith("Failed to delete ONTAP QoS policy"), ex.getMessage());
            }
        }

        // ------------------------------------------------------------- job polling

        @Test
        void jobPollUntilSuccess_returnsCompletedJob() {
            Job done = job("j1", OntapStorageConstants.JOB_SUCCESS, "ok");
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1"))).thenReturn(done);

            assertSame(done, strategy.jobPollUntilSuccess("j1", 3, 0));
            assertTrue(strategy.jobPollForSuccess("j1", 3, 0));
        }

        @Test
        void jobPollUntilSuccess_keepsPolling_untilJobSucceeds() {
            Job done = job("j1", OntapStorageConstants.JOB_SUCCESS, "ok");
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1")))
                    .thenReturn(null)
                    .thenReturn(job("j1", OntapStorageConstants.JOB_RUNNING, null))
                    .thenReturn(done);

            assertSame(done, strategy.jobPollUntilSuccess("j1", 5, 0));
            verify(jobFeignClient, times(3)).getJobByUUID(anyString(), eq("j1"));
        }

        @Test
        void jobPollUntilSuccess_failsWhenJobReportsFailure() {
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1")))
                    .thenReturn(job("j1", OntapStorageConstants.JOB_FAILURE, "disk full"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.jobPollUntilSuccess("j1", 3, 0));

            assertTrue(ex.getMessage().contains("disk full"), ex.getMessage());
        }

        @Test
        void jobPollUntilSuccess_failsWhenRetriesExhausted() {
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1")))
                    .thenReturn(job("j1", OntapStorageConstants.JOB_RUNNING, null));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.jobPollUntilSuccess("j1", 2, 0));

            assertTrue(ex.getMessage().contains("did not complete"), ex.getMessage());
            verify(jobFeignClient, times(2)).getJobByUUID(anyString(), eq("j1"));
        }

        @Test
        void jobPollUntilSuccess_failsWhenJobStatusCannotBeFetched() {
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1"))).thenThrow(feignError(404, "no such job"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.jobPollUntilSuccess("j1", 3, 0));

            assertTrue(ex.getMessage().contains("Failed to fetch job status"), ex.getMessage());
        }

        @Test
        void jobPollUntilSuccess_restoresInterruptFlag_whenInterrupted() {
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1")))
                    .thenReturn(job("j1", OntapStorageConstants.JOB_RUNNING, null));
            Thread.currentThread().interrupt();
            try {
                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> strategy.jobPollUntilSuccess("j1", 5, 10));

                assertTrue(ex.getMessage().contains("Interrupted"), ex.getMessage());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        void pollJobIfPresentAndGetCompletedJob_returnsNull_whenNoJobPresent() {
            assertNull(strategy.pollJobIfPresentAndGetCompletedJob(null, "op"));
            assertNull(strategy.pollJobIfPresentAndGetCompletedJob(new JobResponse(), "op"));
            JobResponse noUuid = new JobResponse();
            noUuid.setJob(new Job());
            assertNull(strategy.pollJobIfPresentAndGetCompletedJob(noUuid, "op", 3, 0));
            verifyNoInteractions(jobFeignClient);
        }

        @Test
        void pollJobIfPresentAndGetCompletedJob_returnsCompletedJob() {
            Job done = job("j1", OntapStorageConstants.JOB_SUCCESS, "ok");
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1"))).thenReturn(done);

            assertSame(done, strategy.pollJobIfPresentAndGetCompletedJob(jobResponse("j1"), "op", 3, 0));
        }

        // ------------------------------------------------------------- FlexVol snapshot delete

        @Test
        void deleteFlexVolSnapshot_requiresBothUuids() {
            assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshotForCloudStackVolume(null, "snap-uuid", "snap"));
            assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshotForCloudStackVolume("", "snap-uuid", "snap"));
            assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshotForCloudStackVolume("vol-uuid", null, "snap"));
            assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshotForCloudStackVolume("vol-uuid", "", "snap"));
            verifyNoInteractions(snapshotFeignClient);
        }

        @Test
        void deleteFlexVolSnapshot_succeedsWithoutJob() {
            when(snapshotFeignClient.deleteSnapshot(anyString(), eq("vol-uuid"), eq("snap-uuid")))
                    .thenReturn(null);

            strategy.deleteFlexVolSnapshotForCloudStackVolume("vol-uuid", "snap-uuid", "snap");

            verify(snapshotFeignClient).deleteSnapshot(anyString(), eq("vol-uuid"), eq("snap-uuid"));
            verifyNoInteractions(jobFeignClient);
        }

        @Test
        void deleteFlexVolSnapshot_pollsDeleteJobWhenReturned() {
            StorageStrategy spied = spy(strategy);
            JobResponse response = jobResponse("snap-del-job");
            doNothing().when(spied).pollJobIfPresent(any(JobResponse.class), anyString(), anyInt(), anyInt());
            when(snapshotFeignClient.deleteSnapshot(anyString(), eq("vol-uuid"), eq("snap-uuid"))).thenReturn(response);

            spied.deleteFlexVolSnapshotForCloudStackVolume("vol-uuid", "snap-uuid", "snap");

            verify(spied).pollJobIfPresent(eq(response), anyString(),
                    eq(OntapStorageConstants.ONTAP_SNAPSHOT_DELETE_JOB_MAX_RETRIES),
                    eq(OntapStorageConstants.ONTAP_SNAPSHOT_DELETE_JOB_POLL_INTERVAL_MS));
        }

        @Test
        void deleteFlexVolSnapshot_treatsAlreadyDeletedSnapshotAsSuccess() {
            when(snapshotFeignClient.deleteSnapshot(anyString(), eq("vol-uuid"), eq("snap-uuid")))
                    .thenThrow(feignError(404, "snapshot not found"));

            strategy.deleteFlexVolSnapshotForCloudStackVolume("vol-uuid", "snap-uuid", "snap");

            verify(snapshotFeignClient, times(1)).deleteSnapshot(anyString(), eq("vol-uuid"), eq("snap-uuid"));
        }

        @Test
        void deleteFlexVolSnapshot_propagatesCloudRuntimeErrorsUnchanged() {
            CloudRuntimeException original = new CloudRuntimeException("job failed");
            when(snapshotFeignClient.deleteSnapshot(anyString(), eq("vol-uuid"), eq("snap-uuid"))).thenThrow(original);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshotForCloudStackVolume("vol-uuid", "snap-uuid", "snap"));

            assertSame(original, ex);
        }

        @Test
        void deleteFlexVolSnapshot_wrapsOtherFailures() {
            when(snapshotFeignClient.deleteSnapshot(anyString(), eq("vol-uuid"), eq("snap-uuid")))
                    .thenThrow(feignError(500, "internal"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.deleteFlexVolSnapshotForCloudStackVolume("vol-uuid", "snap-uuid", "snap"));

            assertTrue(ex.getMessage().startsWith("Failed to delete ONTAP FlexVol snapshot"), ex.getMessage());
            assertNotNull(ex.getCause());
        }

        // =====================================================================================
        // Mutation-hardening: exact requests, boundaries and LIF selection tiers
        // =====================================================================================

        private IpInterface lif(String address, String homeNode, String currentNode) {
            IpInterface.IpInfo ip = new IpInterface.IpInfo();
            ip.setAddress(address);
            IpInterface iface = new IpInterface();
            iface.setIp(ip);
            iface.setState(OntapStorageConstants.LIF_STATE_UP);
            iface.setEnabled(true);
            IpInterface.Location location = new IpInterface.Location();
            if (homeNode != null) {
                IpInterface.Node home = new IpInterface.Node();
                home.setName(homeNode);
                location.setHomeNode(home);
            }
            if (currentNode != null) {
                IpInterface.Node current = new IpInterface.Node();
                current.setName(currentNode);
                location.setNode(current);
            }
            iface.setLocation(location);
            return iface;
        }

        private Map<String, String> selectLif(StorageStrategy target, IpInterface... lifs) {
            when(networkFeignClient.getNetworkIpInterfaces(anyString(), anyMap())).thenReturn(records(Arrays.asList(lifs)));
            return target.getNetworkInterface(aggregate("aggr1", "u1", null, "node-a", Aggregate.StateEnum.ONLINE));
        }

        @Test
        void getNetworkInterface_filtersByServiceForTheConfiguredProtocolOnly() {
            selectLif(newStrategy(ProtocolType.NFS3), lif("10.0.0.1", "node-a", "node-a"));
            selectLif(newStrategy(ProtocolType.ISCSI), lif("10.0.0.1", "node-a", "node-a"));
            selectLif(newStrategy(null), lif("10.0.0.1", "node-a", "node-a"));

            ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
            verify(networkFeignClient, times(3)).getNetworkIpInterfaces(anyString(), query.capture());
            assertEquals(OntapStorageConstants.DATA_NFS, query.getAllValues().get(0).get(OntapStorageConstants.SERVICES));
            assertEquals(OntapStorageConstants.DATA_ISCSI, query.getAllValues().get(1).get(OntapStorageConstants.SERVICES));
            assertFalse(query.getAllValues().get(2).containsKey(OntapStorageConstants.SERVICES));
            assertEquals("svm1", query.getAllValues().get(0).get(OntapStorageConstants.SVM_DOT_NAME));
            assertEquals(OntapStorageConstants.TRUE, query.getAllValues().get(0).get(OntapStorageConstants.RETURN_RECORDS));
        }

        @Test
        void getNetworkInterface_neverSelectsIpv6Lifs() {
            Map<String, String> result = selectLif(strategy, lif("fe80::1", "node-a", "node-a"), lif("10.0.0.7", "node-a", "node-a"));

            assertEquals("10.0.0.7", result.get(OntapStorageConstants.DATA_LIF));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> selectLif(strategy, lif("fe80::1", "node-a", "node-a")));
            assertTrue(ex.getMessage().contains("No operationally UP and enabled LIF"), ex.getMessage());
        }

        @Test
        void getNetworkInterface_prefersHomeNodeLifOverEarlierFailoverAndFallbackLifs() {
            Map<String, String> result = selectLif(strategy,
                    lif("10.0.0.1", "node-b", "node-b"),
                    lif("10.0.0.2", "node-b", "node-a"),
                    lif("10.0.0.3", "node-a", "node-a"));

            assertEquals("10.0.0.3", result.get(OntapStorageConstants.DATA_LIF));
            assertNull(result.get(OntapStorageConstants.LIF_WARNING));
        }

        @Test
        void getNetworkInterface_usesFirstFailoverLifRunningOnTheAggregateNode() {
            Map<String, String> result = selectLif(strategy,
                    lif("10.0.0.1", "node-b", "node-b"),
                    lif("10.0.0.2", "node-b", "node-a"),
                    lif("10.0.0.3", "node-c", "node-a"));

            assertEquals("10.0.0.2", result.get(OntapStorageConstants.DATA_LIF));
            assertTrue(result.get(OntapStorageConstants.LIF_WARNING).contains("currently running on that node"),
                    result.get(OntapStorageConstants.LIF_WARNING));
        }

        @Test
        void getNetworkInterface_fallsBackToFirstUsableLifOnAnotherNodeWithWarning() {
            IpInterface noLocation = lif("10.0.0.1", null, null);
            noLocation.setLocation(null);
            Map<String, String> result = selectLif(strategy, noLocation, lif("10.0.0.2", "node-b", "node-b"),
                    lif("10.0.0.3", "node-c", null));

            assertEquals("10.0.0.1", result.get(OntapStorageConstants.DATA_LIF));
            assertTrue(result.get(OntapStorageConstants.LIF_WARNING).contains("No operational LIF found on aggregate's home node"),
                    result.get(OntapStorageConstants.LIF_WARNING));
            assertTrue(result.get(OntapStorageConstants.LIF_WARNING).contains("10.0.0.1"));
        }

        @Test
        void getNetworkInterface_ignoresDisabledAndDownLifs() {
            IpInterface disabled = lif("10.0.0.1", "node-a", "node-a");
            disabled.setEnabled(false);
            IpInterface down = lif("10.0.0.2", "node-a", "node-a");
            down.setState("down");
            IpInterface unknownEnabled = lif("10.0.0.4", "node-a", "node-a");
            unknownEnabled.setEnabled(null);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> selectLif(strategy, disabled, down, unknownEnabled));
            assertTrue(ex.getMessage().contains("all 3 LIF(s)"), ex.getMessage());

            assertEquals("10.0.0.5", selectLif(strategy, disabled, down, lif("10.0.0.5", "node-a", "node-a"))
                    .get(OntapStorageConstants.DATA_LIF));
        }

        @Test
        void chooseAggregate_keepsFirstOfTwoAggregatesWithEqualAvailableSpace() {
            List<Aggregate> candidates = Arrays.asList(
                    aggregate("first", "u-first", null, null, null),
                    aggregate("second", "u-second", null, null, null));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-first"), anyMap()))
                    .thenReturn(aggregate("first", "u-first", 9000.0, "n1", Aggregate.StateEnum.ONLINE));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-second"), anyMap()))
                    .thenReturn(aggregate("second", "u-second", 9000.0, "n2", Aggregate.StateEnum.ONLINE));

            assertEquals("first", strategy.chooseAggregate(candidates, 1000L).getName());
        }

        @Test
        void chooseAggregate_acceptsAggregateWithOneByteMoreThanRequested() {
            List<Aggregate> candidates = List.of(aggregate("a1", "u1", null, null, null));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u1"), anyMap()))
                    .thenReturn(aggregate("a1", "u1", 1001.0, "n1", Aggregate.StateEnum.ONLINE));

            assertEquals("a1", strategy.chooseAggregate(candidates, 1000L).getName());
        }

        @Test
        void connect_requiresAggregateStrictlyLargerThanPoolSize() {
            Svm exact = svm(OntapStorageConstants.RUNNING, true, true);
            exact.setAggregates(List.of(aggregate("exact", "u-exact", null, null, null)));
            when(svmFeignClient.getSvmResponse(anyMap(), anyString())).thenReturn(records(List.of(exact)));
            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-exact"), anyMap()))
                    .thenReturn(aggregate("exact", "u-exact", 5_000_000_000.0, "n1", Aggregate.StateEnum.ONLINE));

            assertThrows(CloudRuntimeException.class, () -> strategy.connect(true));
            assertTrue(strategy.getAggregates() == null || strategy.getAggregates().isEmpty());

            when(aggregateFeignClient.getAggregateByUUID(anyString(), eq("u-exact"), anyMap()))
                    .thenReturn(aggregate("exact", "u-exact", 5_000_000_001.0, "n1", Aggregate.StateEnum.ONLINE));
            assertTrue(strategy.connect(true));
            assertEquals(1, strategy.getAggregates().size());
            assertEquals("exact", strategy.getAggregates().get(0).getName());
        }

        @Test
        void createStorageVolume_sendsChosenAggregateNameAndUuid() {
            StorageStrategy spied = strategyWithInstantJobs();
            Volume created = namedVolume("vol1", "vol-uuid");
            when(volumeFeignClient.createVolumeWithJob(anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));
            when(volumeFeignClient.getAllVolumes(anyString(), anyMap())).thenReturn(records(List.of(created)));
            when(volumeFeignClient.getVolume(anyString(), anyMap())).thenReturn(records(List.of(created)));

            spied.createStorageVolume("vol1", 2048L, chosenAggregate());

            ArgumentCaptor<Volume> request = ArgumentCaptor.forClass(Volume.class);
            verify(volumeFeignClient).createVolumeWithJob(anyString(), request.capture());
            assertEquals("aggr1", request.getValue().getAggregates().get(0).getName());
            assertEquals("aggr-uuid", request.getValue().getAggregates().get(0).getUuid());
        }

        @Test
        void updateStorageVolume_sendsOnlyTheNewSizeForTheVolumeUuid() {
            StorageStrategy spied = strategyWithInstantJobs();
            when(volumeFeignClient.updateVolume(anyString(), anyString(), any(Volume.class))).thenReturn(jobResponse("job-1"));
            Volume resize = namedVolume("vol1", "vol-uuid");
            resize.setSize(4096L);

            spied.updateStorageVolume(resize);

            ArgumentCaptor<Volume> body = ArgumentCaptor.forClass(Volume.class);
            verify(volumeFeignClient).updateVolume(anyString(), eq("vol-uuid"), body.capture());
            assertEquals(4096L, body.getValue().getSize());
            assertNull(body.getValue().getName());
        }

        @Test
        void createVolumeQosPolicy_treatsZeroAndOneIopsAsMissingAndPresentRespectively() {
            when(qosFeignClient.getPolicies(anyString(), anyMap())).thenReturn(records(List.of(policy("u", 0))));

            strategy.createVolumeQosPolicy("zero", 0L, 0L);
            strategy.createVolumeQosPolicy("one", 1L, 1L);

            ArgumentCaptor<VolumeQosPolicy> sent = ArgumentCaptor.forClass(VolumeQosPolicy.class);
            verify(qosFeignClient, times(2)).createPolicy(anyString(), sent.capture());
            VolumeQosPolicy zero = sent.getAllValues().get(0);
            VolumeQosPolicy one = sent.getAllValues().get(1);
            assertNull(zero.getFixed().getMinThroughputIops());
            assertNull(zero.getFixed().getMaxThroughputIops());
            assertEquals(1L, one.getFixed().getMinThroughputIops());
            assertEquals(1L, one.getFixed().getMaxThroughputIops());
        }

        @Test
        void pollJobIfPresent_withDefaultSettingsPollsTheJobUntilItSucceeds() {
            Job done = job("j1", OntapStorageConstants.JOB_SUCCESS, "ok");
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1"))).thenReturn(done);

            strategy.pollJobIfPresent(jobResponse("j1"), "op");

            verify(jobFeignClient).getJobByUUID(anyString(), eq("j1"));
        }

        @Test
        void pollJobIfPresent_withDefaultSettingsPropagatesJobFailure() {
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1")))
                    .thenReturn(job("j1", OntapStorageConstants.JOB_FAILURE, "disk full"));

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> strategy.pollJobIfPresent(jobResponse("j1"), "op"));

            assertTrue(ex.getMessage().contains("disk full"), ex.getMessage());
        }

        @Test
        void pollJobIfPresentAndGetCompletedJob_withDefaultSettingsReturnsTheCompletedJob() {
            Job done = job("j1", OntapStorageConstants.JOB_SUCCESS, "ok");
            when(jobFeignClient.getJobByUUID(anyString(), eq("j1"))).thenReturn(done);

            assertSame(done, strategy.pollJobIfPresentAndGetCompletedJob(jobResponse("j1"), "op"));
        }
    }
}
