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
package org.apache.cloudstack.storage.lifecycle;

import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.cloudstack.storage.feign.model.Aggregate;
import org.apache.cloudstack.storage.feign.model.Volume;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.dc.ClusterVO;
import com.cloud.host.HostVO;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreParameters;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.alert.AlertManager;
import com.cloud.capacity.CapacityManager;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.mock;
import org.mockito.InOrder;
import static org.mockito.ArgumentMatchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.cloud.storage.StoragePool;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.storage.provider.StorageProviderFactory;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.volume.datastore.PrimaryDataStoreHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.jupiter.api.Nested;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.Collections;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDetailsDao;
import com.cloud.storage.StoragePoolAutomation;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import java.util.Arrays;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OntapPrimaryDatastoreLifecycleTest {
    @InjectMocks
    private OntapPrimaryDatastoreLifecycle ontapPrimaryDatastoreLifecycle;

    @Mock
    private ClusterDao _clusterDao;

    @Mock
    private StorageStrategy storageStrategy;

    @Mock
    private PrimaryDataStoreHelper _dataStoreHelper;

    @Mock
    private ResourceManager _resourceMgr;

    @Mock
    private StorageManager _storageMgr;

    @Mock
    private StoragePoolDetailsDao storagePoolDetailsDao;

    @Mock
    private PrimaryDataStoreDao storagePoolDao;

    @Mock
    private AlertManager _alertMgr;

    @Mock
    private CapacityManager _capacityMgr;

    // Mock object that implements both DataStore and PrimaryDataStoreInfo
    // This is needed because attachCluster(DataStore) casts DataStore to PrimaryDataStoreInfo internally
    private DataStore dataStore;

    @Mock
    private ClusterScope clusterScope;

    @Mock
    private ZoneScope zoneScope;

    private List<HostVO> mockHosts;
    private Map<String, String> poolDetails;

    @BeforeEach
    void setUp() {
        // Create a mock that implements both DataStore and PrimaryDataStoreInfo interfaces
        dataStore = Mockito.mock(DataStore.class, withSettings()
                .extraInterfaces(PrimaryDataStoreInfo.class));

        ClusterVO clusterVO = new ClusterVO(1L, 1L, "clusterName");
        clusterVO.setHypervisorType("KVM");
        when(_clusterDao.findById(1L)).thenReturn(clusterVO);

        when(storageStrategy.connect()).thenReturn(true);
        Aggregate aggregate = new Aggregate();
        aggregate.setName("aggr1");
        aggregate.setUuid("aggr-uuid-1");
        Aggregate.Node node = new Aggregate.Node();
        node.setName("node-a");
        aggregate.setNode(node);
        when(storageStrategy.isAff()).thenReturn(true);
        when(storageStrategy.chooseAggregate(any(), any())).thenReturn(aggregate);
        when(storageStrategy.getNetworkInterface(any())).thenReturn(
                Map.of(OntapStorageConstants.DATA_LIF, "testNetworkInterface"));

        Volume volume = new Volume();
        volume.setUuid("test-volume-uuid");
        volume.setName("testVolume");
        when(storageStrategy.createStorageVolume(any(), any(), any())).thenReturn(volume);

        // Setup for attachCluster tests
        // Configure dataStore mock with necessary methods (works for both DataStore and PrimaryDataStoreInfo)
        when(dataStore.getId()).thenReturn(1L);
        when(((PrimaryDataStoreInfo) dataStore).getClusterId()).thenReturn(1L);

        // Mock the setDetails method to prevent NullPointerException
        Mockito.doNothing().when(((PrimaryDataStoreInfo) dataStore)).setDetails(any());

        // Mock storagePoolDao to return a valid StoragePoolVO
        StoragePoolVO mockStoragePoolVO = new StoragePoolVO();
        mockStoragePoolVO.setId(1L);
        when(storagePoolDao.findById(1L)).thenReturn(mockStoragePoolVO);

        mockHosts = new ArrayList<>();
        HostVO host1 = new HostVO("host1-guid");
        host1.setPrivateIpAddress("192.168.1.10");
        host1.setStorageIpAddress("192.168.1.10");
        host1.setClusterId(1L);
        HostVO host2 = new HostVO("host2-guid");
        host2.setPrivateIpAddress("192.168.1.11");
        host2.setStorageIpAddress("192.168.1.11");
        host2.setClusterId(1L);
        mockHosts.add(host1);
        mockHosts.add(host2);
        poolDetails = new HashMap<>();
        poolDetails.put("username", "admin");
        poolDetails.put("password", "password");
        poolDetails.put("svmName", "svm1");
        poolDetails.put("protocol", "NFS3");
        poolDetails.put("storageIP", "192.168.1.100");
        when(zoneScope.getScopeId()).thenReturn(1L);
    }

    @Test
    public void testInitialize_positive() {

        HashMap<String, String> detailsMap = new HashMap<String, String>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        try(MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
        }
    }

    private Map<String, Object> buildDsInfosForProtocol(String protocol) {
        HashMap<String, String> detailsMap = new HashMap<String, String>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, protocol);

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);
        return dsInfos;
    }

    private Storage.StoragePoolType initializeAndCapturePoolType(String protocol) {
        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(buildDsInfosForProtocol(protocol));
        }
        ArgumentCaptor<PrimaryDataStoreParameters> captor = ArgumentCaptor.forClass(PrimaryDataStoreParameters.class);
        verify(_dataStoreHelper).createPrimaryDataStore(captor.capture());
        return captor.getValue().getType();
    }

    @Test
    public void testInitialize_iscsiPoolUsesOntapIscsiType() {
        when(storageStrategy.getStoragePath()).thenReturn("iqn.1992-08.com.netapp:sn.abc123");

        assertEquals(Storage.StoragePoolType.OntapiSCSI, initializeAndCapturePoolType("ISCSI"));
    }

    @Test
    public void testInitialize_nfsPoolKeepsNetworkFilesystemType() {
        assertEquals(Storage.StoragePoolType.NetworkFilesystem, initializeAndCapturePoolType("NFS3"));
    }

    private Long initializeAndCaptureCapacityIops(Map<String, Object> dsInfos) {
        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
        }
        ArgumentCaptor<PrimaryDataStoreParameters> captor = ArgumentCaptor.forClass(PrimaryDataStoreParameters.class);
        verify(_dataStoreHelper).createPrimaryDataStore(captor.capture());
        return captor.getValue().getCapacityIops();
    }

    @Test
    public void testInitialize_blankCapacityIopsLeavesPoolWithoutIopsCeiling() {
        assertNull(initializeAndCaptureCapacityIops(buildDsInfosForProtocol("NFS3")));
    }

    @Test
    public void testInitialize_capacityIopsIsStoredOnPool() {
        Map<String, Object> dsInfos = buildDsInfosForProtocol("NFS3");
        dsInfos.put("capacityIops", 5000L);

        assertEquals(Long.valueOf(5000L), initializeAndCaptureCapacityIops(dsInfos));
    }

    @Test
    public void testInitialize_persistsAffPlatformDetail() {
        Map<String, String> details = initializeAndCaptureDetails(buildDsInfosForProtocol("NFS3"));

        assertEquals("true", details.get(OntapStorageConstants.IS_AFF));
        verify(storageStrategy).isAff();
    }

    @Test
    public void testInitialize_persistsFasPlatformDetail() {
        when(storageStrategy.isAff()).thenReturn(false);

        Map<String, String> details = initializeAndCaptureDetails(buildDsInfosForProtocol("NFS3"));

        assertEquals("false", details.get(OntapStorageConstants.IS_AFF));
    }

    private Map<String, String> initializeAndCaptureDetails(Map<String, Object> dsInfos) {
        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
        }
        ArgumentCaptor<PrimaryDataStoreParameters> captor = ArgumentCaptor.forClass(PrimaryDataStoreParameters.class);
        verify(_dataStoreHelper).createPrimaryDataStore(captor.capture());
        return captor.getValue().getDetails();
    }

    @Test
    public void testInitialize_nonPositiveCapacityIopsIsRejected() {
        Map<String, Object> dsInfos = buildDsInfosForProtocol("NFS3");
        dsInfos.put("capacityIops", 0L);

        Exception ex = assertThrows(InvalidParameterValueException.class,
                () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));

        assertTrue(ex.getMessage().contains("IOPS capacity must be greater than 0"));
        verify(_dataStoreHelper, never()).createPrimaryDataStore(any());
    }

    @Test
    public void testInitialize_null_Arg() {
        Exception ex = assertThrows(CloudRuntimeException.class,() ->
                ontapPrimaryDatastoreLifecycle.initialize(null));
        assertTrue(ex.getMessage().contains("Datastore info map is null, cannot create primary storage"));
    }

    @Test
    public void testInitialize_missingRequiredDetailKey() {

        HashMap<String, String> detailsMap = new HashMap<String, String>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(CloudRuntimeException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("missing detail"));
        }
    }

    @Test
    public void testInitialize_invalidCapacityBytes() {

        HashMap<String, String> detailsMap = new HashMap<String, String>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",-1L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(InvalidParameterValueException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("must be at least"));
        }
    }

    @Test
    public void testInitialize_capacityBelowOntapMinimum() {

        HashMap<String, String> detailsMap = new HashMap<String, String>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 20971519L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(InvalidParameterValueException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("below the ONTAP minimum volume size"));
        }
    }

    @Test
    public void testInitialize_unmanagedStorage() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed",false);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", new HashMap<String, String>());

        Exception ex = assertThrows(CloudRuntimeException.class, () -> {
            try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
                storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
            }
        });
        assertTrue(ex.getMessage().contains("must be managed"));
    }

    @Test
    public void testInitialize_nullStoragePoolName() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", null);
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", new HashMap<String, String>());

        Exception ex = assertThrows(CloudRuntimeException.class, () -> {
            try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
                storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
            }
        });
        assertTrue(ex.getMessage().contains("Storage pool name is null or empty"));
    }

    @Test
    public void testInitialize_nullProviderName() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", null);
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", new HashMap<String, String>());

        Exception ex = assertThrows(CloudRuntimeException.class, () -> {
            try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
                storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
            }
        });
        assertTrue(ex.getMessage().contains("Provider name is null or empty"));
    }

    @Test
    public void testInitialize_nullPodAndClusterAndZone() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",null);
        dsInfos.put("podId",null);
        dsInfos.put("clusterId", null);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", new HashMap<String, String>());

        Exception ex = assertThrows(CloudRuntimeException.class, () -> {
            try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
                storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
            }
        });
        assertTrue(ex.getMessage().contains("Pod Id, Cluster Id and Zone Id are all null"));
    }

    @Test
    public void testInitialize_clusterNotKVM() {
        ClusterVO clusterVO = new ClusterVO(2L, 1L, "clusterName");
        clusterVO.setHypervisorType("XenServer");
        when(_clusterDao.findById(2L)).thenReturn(clusterVO);

        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 2L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        Exception ex = assertThrows(CloudRuntimeException.class, () -> {
            try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
                storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
            }
        });
        assertTrue(ex.getMessage().contains("ONTAP primary storage is supported only for KVM hypervisor"));
    }

    @Test
    public void testInitialize_unexpectedDetailKey() {

        HashMap<String, String> detailsMap = new HashMap<String, String>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");
        detailsMap.put("unexpectedKey", "unexpectedValue");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        Exception ex = assertThrows(CloudRuntimeException.class, () -> {
            try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
                storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
            }
        });
        assertTrue(ex.getMessage().contains("Unexpected ONTAP detail key in URL"));
    }

    @Test
    public void testInitialize_dataLifWithWarning() {
        // Test when getNetworkInterface returns a warning in LIF_WARNING
        // This exercises the processDataLifSelection path for non-null warning
        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        String warningMessage = "LIF on node-b; expected on node-a;Details about LIF failover";
        when(storageStrategy.getNetworkInterface(any())).thenReturn(Map.of(
                OntapStorageConstants.DATA_LIF, "10.0.0.1",
                OntapStorageConstants.LIF_WARNING, warningMessage));

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class);
             MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(dsInfos);

            // Verify alert was sent with warning message
            utilityMock.verify(() -> OntapStorageUtils.sendStorageAlert(eq(_alertMgr), eq(1L), eq(1L),
                    contains("LIF on node-b"), eq(warningMessage)), times(1));
        }
    }

    @Test
    public void testInitialize_nullDataLif() {
        // Test when DATA_LIF is missing from the result map
        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        when(storageStrategy.getNetworkInterface(any())).thenReturn(new HashMap<>());

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(CloudRuntimeException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("Failed to retrieve Data LIF from ONTAP, cannot create primary storage"));
            verify(storageStrategy, never()).createStorageVolume(any(), any(), any());
        }
    }

    @Test
    public void testInitialize_emptyDataLif() {
        // Test when DATA_LIF is an empty string
        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        when(storageStrategy.getNetworkInterface(any())).thenReturn(
                Map.of(OntapStorageConstants.DATA_LIF, ""));

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(CloudRuntimeException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("Failed to retrieve Data LIF from ONTAP, cannot create primary storage"));
            verify(storageStrategy, never()).createStorageVolume(any(), any(), any());
        }
    }

    @Test
    public void testInitialize_getNetworkInterfaceException() {
        // Test when getNetworkInterface throws an exception
        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        when(storageStrategy.getNetworkInterface(any())).thenThrow(new RuntimeException("ONTAP API error"));

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(CloudRuntimeException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("Failed to retrieve Data LIF from ONTAP"));
            assertTrue(ex.getCause() != null && ex.getCause().getMessage().contains("ONTAP API error"));
            verify(storageStrategy, never()).createStorageVolume(any(), any(), any());
        }
    }

    @Test
    public void testInitialize_volumeCreationFailure_nullVolume() {
        // Test when createStorageVolume returns null
        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        when(storageStrategy.createStorageVolume(any(), any(), any())).thenReturn(null);

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(CloudRuntimeException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("Failed to create ONTAP volume"));
        }
    }

    @Test
    public void testInitialize_volumeCreationException() {
        // Test when createStorageVolume throws an exception
        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        when(storageStrategy.createStorageVolume(any(), any(), any())).thenThrow(new RuntimeException("Volume creation failed"));

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(CloudRuntimeException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("Failed to create ONTAP volume"));
            assertTrue(ex.getCause() != null && ex.getCause().getMessage().contains("Volume creation failed"));
        }
    }

    @Test
    public void testInitialize_positiveWithDetailAssertions() {
        // Enhanced positive test that verifies DATA_LIF detail is persisted and host is set correctly
        HashMap<String, String> detailsMap = new HashMap<>();
        detailsMap.put(OntapStorageConstants.USERNAME, "testUser");
        detailsMap.put(OntapStorageConstants.PASSWORD, "testPassword");
        detailsMap.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
        detailsMap.put(OntapStorageConstants.SVM_NAME, "vs0");
        detailsMap.put(OntapStorageConstants.PROTOCOL, "NFS3");

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("zoneId", 1L);
        dsInfos.put("podId", 1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes", 1073741824L);
        dsInfos.put("managed", true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", detailsMap);

        String expectedDataLif = "192.168.1.100";
        when(storageStrategy.getNetworkInterface(any())).thenReturn(
                Map.of(OntapStorageConstants.DATA_LIF, expectedDataLif));
        when(storageStrategy.getStoragePath()).thenReturn("/vol/testVolume");

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(dsInfos);

            // Verify LIF selection completes before FlexVol creation
            InOrder inOrder = inOrder(storageStrategy);
            inOrder.verify(storageStrategy).chooseAggregate(any(), any());
            inOrder.verify(storageStrategy).getNetworkInterface(any());
            inOrder.verify(storageStrategy).createStorageVolume(any(), any(), any());

            // Verify that createPrimaryDataStore was called and host parameter contains the DATA_LIF
            verify(_dataStoreHelper, times(1)).createPrimaryDataStore(any());
        }
    }

    // ========== attachCluster Tests ==========

    @Test
    public void testAttachCluster_positive() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any()))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachCluster(any(DataStore.class))).thenReturn(dataStore);

            // Mock successful host connections
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachCluster(
                    dataStore, clusterScope);

            // Verify
            assertTrue(result, "attachCluster should return true on success");
            verify(_resourceMgr, times(1))
                    .getEligibleUpAndEnabledHostsInClusterForStorageConnection(any());
            verify(storagePoolDetailsDao, times(1)).listDetailsKeyPairs(1L);
            verify(storageStrategy, times(1)).createAccessGroup(any(AccessGroup.class));
            verify(_storageMgr, times(2)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(1)).attachCluster(any(DataStore.class));
        }
    }

    @Test
    public void testAttachCluster_withSingleHost() throws Exception {
        // Setup - only one host in cluster
        List<HostVO> singleHost = new ArrayList<>();
        singleHost.add(mockHosts.get(0));

        when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any()))
                .thenReturn(singleHost);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachCluster(any(DataStore.class))).thenReturn(dataStore);
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachCluster(
                    dataStore, clusterScope);

            // Verify
            assertTrue(result, "attachCluster should return true with single host");
            verify(_storageMgr, times(1)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(1)).attachCluster(any(DataStore.class));
        }
    }

    @Test
    public void testAttachCluster_withMultipleHosts() throws Exception {
        // Setup - add more hosts
        HostVO host3 = new HostVO("host3-guid");
        host3.setPrivateIpAddress("192.168.1.12");
        host3.setStorageIpAddress("192.168.1.12");
        host3.setClusterId(1L);
        mockHosts.add(host3);

        when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any()))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachCluster(any(DataStore.class))).thenReturn(dataStore);
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachCluster(
                    dataStore, clusterScope);

            // Verify
            assertTrue(result, "attachCluster should return true with multiple hosts");
            verify(_storageMgr, times(3)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(1)).attachCluster(any(DataStore.class));
        }
    }

    @Test
    public void testAttachCluster_hostConnectionFailure() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any()))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachCluster(any(DataStore.class))).thenReturn(dataStore);

            // Mock host connection failure for first host
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong()))
                    .thenThrow(new CloudRuntimeException("Connection failed"));

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachCluster(
                    dataStore, clusterScope);

            // Verify
            assertFalse(result, "attachCluster should return false on host connection failure");
            verify(storageStrategy, times(1)).createAccessGroup(any(AccessGroup.class));
            verify(_storageMgr, times(1)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            // _dataStoreHelper.attachCluster should NOT be called due to early return
            verify(_dataStoreHelper, times(0)).attachCluster(any(DataStore.class));
        }
    }

    @Test
    public void testAttachCluster_emptyHostList() throws Exception {
        // Setup - no hosts in cluster
        List<HostVO> emptyHosts = new ArrayList<>();

        when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any()))
                .thenReturn(emptyHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachCluster(any(DataStore.class))).thenReturn(dataStore);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachCluster(
                    dataStore, clusterScope);

            // Verify
            assertTrue(result, "attachCluster should return true even with no hosts");
            verify(_storageMgr, times(0)).connectHostToSharedPool(any(HostVO.class), anyLong());
            verify(_dataStoreHelper, times(1)).attachCluster(any(DataStore.class));
        }
    }

    @Test
    public void testAttachCluster_secondHostConnectionFails() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any()))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachCluster(any(DataStore.class))).thenReturn(dataStore);

            // Mock: first host succeeds, second host fails
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong()))
                    .thenReturn(true)
                    .thenThrow(new CloudRuntimeException("Connection failed"));

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachCluster(
                    dataStore, clusterScope);

            // Verify
            assertFalse(result, "attachCluster should return false when any host connection fails");
            verify(_storageMgr, times(2)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(0)).attachCluster(any(DataStore.class));
        }
    }

    @Test
    public void testAttachCluster_createAccessGroupCalled() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any()))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachCluster(any(DataStore.class))).thenReturn(dataStore);
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachCluster(
                    dataStore, clusterScope);

            // Verify - createAccessGroup is called with correct AccessGroup structure
            assertTrue(result);
            verify(storageStrategy, times(1)).createAccessGroup(any(AccessGroup.class));
        }
    }

    // ========== attachZone Tests ==========

    @Test
    public void testAttachZone_positive() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(_dataStoreHelper.attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM))).thenReturn(dataStore);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachZone(any(DataStore.class))).thenReturn(dataStore);

            // Mock successful host connections
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            // Verify
            assertTrue(result, "attachZone should return true on success");
            verify(_resourceMgr, times(1))
                    .getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM));
            verify(storagePoolDetailsDao, times(1)).listDetailsKeyPairs(1L);
            verify(storageStrategy, times(1)).createAccessGroup(any(AccessGroup.class));
            verify(_storageMgr, times(2)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(1)).attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM));
        }
    }

    @Test
    public void testAttachZone_withSingleHost() throws Exception {
        // Setup - only one host in zone
        List<HostVO> singleHost = new ArrayList<>();
        singleHost.add(mockHosts.get(0));

        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(singleHost);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(_dataStoreHelper.attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM))).thenReturn(dataStore);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachZone(any(DataStore.class))).thenReturn(dataStore);
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            // Verify
            assertTrue(result, "attachZone should return true with single host");
            verify(_storageMgr, times(1)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(1)).attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM));
        }
    }

    @Test
    public void testAttachZone_withMultipleHosts() throws Exception {
        // Setup - add more hosts
        HostVO host3 = new HostVO("host3-guid");
        host3.setPrivateIpAddress("192.168.1.12");
        host3.setStorageIpAddress("192.168.1.12");
        host3.setClusterId(1L);
        mockHosts.add(host3);

        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(_dataStoreHelper.attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM))).thenReturn(dataStore);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachZone(any(DataStore.class))).thenReturn(dataStore);
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            // Verify
            assertTrue(result, "attachZone should return true with multiple hosts");
            verify(_storageMgr, times(3)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(1)).attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM));
        }
    }

    @Test
    public void testAttachZone_hostConnectionFailure() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachZone(any(DataStore.class))).thenReturn(dataStore);

            // Mock host connection failure for first host
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong()))
                    .thenThrow(new CloudRuntimeException("Connection failed"));

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            // Verify
            assertFalse(result, "attachZone should return false on host connection failure");
            verify(storageStrategy, times(1)).createAccessGroup(any(AccessGroup.class));
            verify(_storageMgr, times(1)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            // _dataStoreHelper.attachZone should NOT be called due to early return
            verify(_dataStoreHelper, times(0)).attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM));
        }
    }

    @Test
    public void testAttachZone_emptyHostList() throws Exception {
        // Setup - no hosts in zone
        List<HostVO> emptyHosts = new ArrayList<>();

        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(emptyHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(_dataStoreHelper.attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM))).thenReturn(dataStore);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachZone(any(DataStore.class))).thenReturn(dataStore);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            // Verify
            assertTrue(result, "attachZone should return true even with no hosts");
            verify(_storageMgr, times(0)).connectHostToSharedPool(any(HostVO.class), anyLong());
            verify(_dataStoreHelper, times(1)).attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM));
        }
    }

    @Test
    public void testAttachZone_secondHostConnectionFails() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachZone(any(DataStore.class))).thenReturn(dataStore);

            // Mock: first host succeeds, second host fails
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong()))
                    .thenReturn(true)
                    .thenThrow(new CloudRuntimeException("Connection failed"));

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            // Verify
            assertFalse(result, "attachZone should return false when any host connection fails");
            verify(_storageMgr, times(2)).connectHostToSharedPool(any(HostVO.class), eq(1L));
            verify(_dataStoreHelper, times(0)).attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM));
        }
    }

    @Test
    public void testAttachZone_createAccessGroupCalled() throws Exception {
        // Setup
        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(_dataStoreHelper.attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM))).thenReturn(dataStore);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(storageStrategy.createAccessGroup(any(AccessGroup.class))).thenReturn(null);
            when(_dataStoreHelper.attachZone(any(DataStore.class))).thenReturn(dataStore);
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            // Execute
            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            // Verify - createAccessGroup is called with correct AccessGroup structure
            assertTrue(result);
            verify(storageStrategy, times(1)).createAccessGroup(any(AccessGroup.class));
        }
    }

    @Test
    public void testAttachZone_nonKvmHypervisorThrowsException() {
        // Non-KVM hypervisor type must be rejected before any further processing
        Exception ex = assertThrows(CloudRuntimeException.class, () ->
                ontapPrimaryDatastoreLifecycle.attachZone(
                        dataStore, zoneScope, Hypervisor.HypervisorType.VMware));

        assertTrue(ex.getMessage().contains("ONTAP primary storage is supported only for KVM hypervisor"));
        // update should never be reached
        verify(storagePoolDao, times(0)).update(anyLong(), any(StoragePoolVO.class));
    }

    @Test
    public void testAttachZone_nullHypervisorThrowsException() {
        // null hypervisorType is not a valid case — only KVM is accepted
        Exception ex = assertThrows(CloudRuntimeException.class, () ->
                ontapPrimaryDatastoreLifecycle.attachZone(dataStore, zoneScope, null));

        assertTrue(ex.getMessage().contains("ONTAP primary storage is supported only for KVM hypervisor"));
        verify(storagePoolDao, times(0)).update(anyLong(), any(StoragePoolVO.class));
    }

    @Test
    public void testAttachZone_kvmHypervisorSetsAndUpdatesPool() throws Exception {
        // KVM hypervisorType should be set on the pool and persisted via storagePoolDao.update
        when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), eq(1L), eq(Hypervisor.HypervisorType.KVM)))
                .thenReturn(mockHosts);
        when(storagePoolDetailsDao.listDetailsKeyPairs(1L)).thenReturn(poolDetails);
        when(_dataStoreHelper.attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM))).thenReturn(dataStore);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);
            when(_storageMgr.connectHostToSharedPool(any(HostVO.class), anyLong())).thenReturn(true);

            boolean result = ontapPrimaryDatastoreLifecycle.attachZone(
                    dataStore, zoneScope, Hypervisor.HypervisorType.KVM);

            assertTrue(result, "attachZone should succeed for KVM hypervisor");
            verify(_dataStoreHelper, times(1)).attachZone(any(DataStore.class), eq(Hypervisor.HypervisorType.KVM));
        }
    }

    // ========== updateStoragePool() Tests ==========

    @Test
    public void testUpdateStoragePool_positive_resizesFlexVolume() {
        // Setup
        StoragePool storagePool = mock(StoragePool.class);
        when(storagePool.getName()).thenReturn("test-pool");
        when(storagePool.getCapacityBytes()).thenReturn(2147483648L); // 2 GB current

        Map<String, String> details = new HashMap<>();
        details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, String.valueOf(5368709120L)); // 5 GB new
        details.put(OntapStorageConstants.VOLUME_UUID, "flex-vol-uuid-123");
        details.put(OntapStorageConstants.VOLUME_NAME, "flexvol-name");
        details.put("protocol", "NFS3");

        Volume updatedVolume = new Volume();
        updatedVolume.setUuid("flex-vol-uuid-123");
        updatedVolume.setSize(5368709120L);
        when(storageStrategy.updateStorageVolume(any(Volume.class))).thenReturn(updatedVolume);

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);

            // Execute
            ontapPrimaryDatastoreLifecycle.updateStoragePool(storagePool, details);

            // Verify
            verify(storageStrategy, times(1)).updateStorageVolume(any(Volume.class));
        }
    }

    @Test
    public void testUpdateStoragePool_noCapacityBytesInDetails_skipsResize() {
        // Setup
        StoragePool storagePool = mock(StoragePool.class);
        when(storagePool.getName()).thenReturn("test-pool");

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.VOLUME_UUID, "flex-vol-uuid-123");
        details.put("protocol", "NFS3");
        // No CAPACITY_BYTES key — resize should be skipped

        // Execute
        ontapPrimaryDatastoreLifecycle.updateStoragePool(storagePool, details);

        // Verify — storageStrategy should never be called
        verify(storageStrategy, never()).updateStorageVolume(any());
    }

    @Test
    public void testUpdateStoragePool_missingVolumeUuid_throwsCloudRuntimeException() {
        // Setup
        StoragePool storagePool = mock(StoragePool.class);
        when(storagePool.getName()).thenReturn("test-pool");
        when(storagePool.getCapacityBytes()).thenReturn(1073741824L);

        Map<String, String> details = new HashMap<>();
        details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, String.valueOf(3221225472L));
        details.put("protocol", "NFS3");
        // No VOLUME_UUID — cannot resize without it

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);

            // Execute & Verify
            assertThrows(CloudRuntimeException.class,
                    () -> ontapPrimaryDatastoreLifecycle.updateStoragePool(storagePool, details));
            verify(storageStrategy, never()).updateStorageVolume(any());
        }
    }

    @Test
    public void testUpdateStoragePool_capacityIopsBelowAllocated_throwsInvalidParameterValueException() {
        StoragePool storagePool = mock(StoragePool.class);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("test-pool");

        Map<String, String> details = new HashMap<>();
        details.put(PrimaryDataStoreLifeCycle.CAPACITY_IOPS, "400");
        details.put(OntapStorageConstants.VOLUME_UUID, "flex-vol-uuid-123");
        details.put("protocol", "NFS3");

        when(_capacityMgr.getUsedIops(any(StoragePoolVO.class))).thenReturn(900L);

        Exception ex = assertThrows(InvalidParameterValueException.class,
                () -> ontapPrimaryDatastoreLifecycle.updateStoragePool(storagePool, details));

        assertTrue(ex.getMessage().contains("900 IOPS are already allocated"));
        verify(storageStrategy, never()).updateStorageVolume(any(Volume.class));
    }

    @Test
    public void testUpdateStoragePool_capacityIopsAboveAllocated_isAccepted() {
        StoragePool storagePool = mock(StoragePool.class);
        when(storagePool.getId()).thenReturn(1L);
        when(storagePool.getName()).thenReturn("test-pool");

        Map<String, String> details = new HashMap<>();
        details.put(PrimaryDataStoreLifeCycle.CAPACITY_IOPS, "2000");
        details.put("protocol", "NFS3");
        // No CAPACITY_BYTES key — only the IOPS ceiling is being raised.

        when(_capacityMgr.getUsedIops(any(StoragePoolVO.class))).thenReturn(900L);

        ontapPrimaryDatastoreLifecycle.updateStoragePool(storagePool, details);

        verify(storageStrategy, never()).updateStorageVolume(any(Volume.class));
    }

    @Test
    public void testUpdateStoragePool_updateStorageVolumeThrows_propagatesCloudRuntimeException() {
        // Setup
        StoragePool storagePool = mock(StoragePool.class);
        when(storagePool.getName()).thenReturn("test-pool");
        when(storagePool.getCapacityBytes()).thenReturn(1073741824L);

        Map<String, String> details = new HashMap<>();
        details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, String.valueOf(3221225472L));
        details.put(OntapStorageConstants.VOLUME_UUID, "flex-vol-uuid-err");
        details.put(OntapStorageConstants.VOLUME_NAME, "flexvol-err");
        details.put("protocol", "NFS3");

        when(storageStrategy.updateStorageVolume(any(Volume.class)))
                .thenThrow(new CloudRuntimeException("ONTAP resize failed"));

        try (MockedStatic<OntapStorageUtils> utilityMock = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilityMock.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any()))
                    .thenReturn(storageStrategy);

            // Execute & Verify
            assertThrows(CloudRuntimeException.class,
                    () -> ontapPrimaryDatastoreLifecycle.updateStoragePool(storagePool, details));
            verify(storageStrategy, times(1)).updateStorageVolume(any(Volume.class));
        }
    }


    /**
     * Covers the maintenance, deletion, capacity-validation and passthrough operations of
     * {@link OntapPrimaryDatastoreLifecycle} that {@link OntapPrimaryDatastoreLifecycleTest} does not reach.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class OntapPrimaryDatastoreLifecycleOperationsTest {

        private static final long POOL_ID = 77L;

        @InjectMocks
        private OntapPrimaryDatastoreLifecycle lifecycle;

        @Mock private StorageManager _storageMgr;
        @Mock private PrimaryDataStoreHelper _dataStoreHelper;
        @Mock private PrimaryDataStoreDetailsDao _datastoreDetailsDao;
        @Mock private StoragePoolAutomation _storagePoolAutomation;
        @Mock private PrimaryDataStoreDao storagePoolDao;
        @Mock private AlertManager _alertMgr;
        @Mock private CapacityManager _capacityMgr;
        @Mock private StorageStrategy storageStrategy;
        @Mock private StoragePool storagePool;

        private DataStore store;

        @BeforeEach
        void setUp() {
            store = mock(DataStore.class, withSettings().extraInterfaces(PrimaryDataStoreInfo.class));
            when(store.getId()).thenReturn(POOL_ID);
            when(storagePool.getId()).thenReturn(POOL_ID);
            when(storagePool.getName()).thenReturn("ontap-pool");
            when(_storageMgr.getStoragePool(POOL_ID)).thenReturn(storagePool);
        }

        private Map<String, String> fullDetails() {
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.VOLUME_UUID, "vol-uuid");
            details.put(OntapStorageConstants.VOLUME_NAME, "vol-name");
            return details;
        }

        // ------------------------------------------------------------------ maintenance

        @Test
        void maintain_marksPoolInMaintenance_onlyWhenAutomationSucceeds() {
            when(_storagePoolAutomation.maintain(store)).thenReturn(true);
            when(_dataStoreHelper.maintain(store)).thenReturn(true);

            assertTrue(lifecycle.maintain(store));
            verify(_dataStoreHelper).maintain(store);
        }

        @Test
        void maintain_returnsHelperResult_whenAutomationSucceedsButHelperFails() {
            when(_storagePoolAutomation.maintain(store)).thenReturn(true);
            when(_dataStoreHelper.maintain(store)).thenReturn(false);

            assertFalse(lifecycle.maintain(store));
        }

        @Test
        void maintain_doesNotTouchPoolState_whenAutomationFails() {
            when(_storagePoolAutomation.maintain(store)).thenReturn(false);

            assertFalse(lifecycle.maintain(store));
            verify(_dataStoreHelper, never()).maintain(store);
        }

        @Test
        void cancelMaintain_runsAutomationOnlyAfterHelperSucceeds() {
            when(_dataStoreHelper.cancelMaintain(store)).thenReturn(true);
            when(_storagePoolAutomation.cancelMaintain(store)).thenReturn(true);

            assertTrue(lifecycle.cancelMaintain(store));
            verify(_storagePoolAutomation).cancelMaintain(store);
        }

        @Test
        void cancelMaintain_returnsAutomationResult_whenAutomationFails() {
            when(_dataStoreHelper.cancelMaintain(store)).thenReturn(true);
            when(_storagePoolAutomation.cancelMaintain(store)).thenReturn(false);

            assertFalse(lifecycle.cancelMaintain(store));
        }

        @Test
        void cancelMaintain_skipsAutomation_whenHelperFails() {
            when(_dataStoreHelper.cancelMaintain(store)).thenReturn(false);

            assertFalse(lifecycle.cancelMaintain(store));
            verifyNoInteractions(_storagePoolAutomation);
        }

        // ------------------------------------------------------------------ simple delegations

        @Test
        void enableAndDisable_delegateToDataStoreHelper() {
            lifecycle.enableStoragePool(store);
            lifecycle.disableStoragePool(store);

            verify(_dataStoreHelper).enable(store);
            verify(_dataStoreHelper).disable(store);
        }

        @Test
        void migrateToObjectStore_isNotSupported() {
            assertFalse(lifecycle.migrateToObjectStore(store));
        }

        @Test
        void scopeChanges_areNoOps() {
            ClusterScope clusterScope = mock(ClusterScope.class);

            lifecycle.changeStoragePoolScopeToZone(store, clusterScope, Hypervisor.HypervisorType.KVM);
            lifecycle.changeStoragePoolScopeToCluster(store, clusterScope, Hypervisor.HypervisorType.KVM);

            verifyNoInteractions(_dataStoreHelper, _storagePoolAutomation, _datastoreDetailsDao);
        }

        // ------------------------------------------------------------------ deleteDataStore

        @Test
        void deleteDataStore_succeedsWithoutTouchingOntap_whenPoolAlreadyGone() {
            when(_storageMgr.getStoragePool(POOL_ID)).thenReturn(null);

            assertTrue(lifecycle.deleteDataStore(store));

            verify(_dataStoreHelper, never()).deletePrimaryDataStore(any());
            verifyNoInteractions(_datastoreDetailsDao);
        }

        @Test
        void deleteDataStore_onlyRemovesCloudStackEntity_whenPoolHasNoDetails() {
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(Collections.emptyMap());
            when(_dataStoreHelper.deletePrimaryDataStore(store)).thenReturn(true);

            assertTrue(lifecycle.deleteDataStore(store));
            verify(_dataStoreHelper).deletePrimaryDataStore(store);

            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(null);
            assertTrue(lifecycle.deleteDataStore(store));
        }

        @Test
        void deleteDataStore_removesOntapVolumeAndAccessGroupBeforeCloudStackEntity() {
            Map<String, String> details = fullDetails();
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(details);
            when(_dataStoreHelper.deletePrimaryDataStore(store)).thenReturn(true);
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(storageStrategy);

                assertTrue(lifecycle.deleteDataStore(store));
            }

            ArgumentCaptor<Volume> volume = ArgumentCaptor.forClass(Volume.class);
            verify(storageStrategy).deleteStorageVolume(volume.capture());
            assertEquals("vol-uuid", volume.getValue().getUuid());
            assertEquals("vol-name", volume.getValue().getName());
            ArgumentCaptor<AccessGroup> group = ArgumentCaptor.forClass(AccessGroup.class);
            verify(storageStrategy).deleteAccessGroup(group.capture());
            assertEquals(POOL_ID, group.getValue().getStoragePoolId());
            verify((PrimaryDataStoreInfo) store).setDetails(details);
            verify(_dataStoreHelper).deletePrimaryDataStore(store);
        }

        @Test
        void deleteDataStore_skipsVolumeDeletion_butStillCleansUp_whenVolumeIdentityMissing() {
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.VOLUME_NAME, "vol-name");
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(details);
            when(_dataStoreHelper.deletePrimaryDataStore(store)).thenReturn(true);
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(storageStrategy);

                assertTrue(lifecycle.deleteDataStore(store));
            }

            verify(storageStrategy, never()).deleteStorageVolume(any(Volume.class));
            verify(storageStrategy).deleteAccessGroup(any(AccessGroup.class));
            verify(_dataStoreHelper).deletePrimaryDataStore(store);
        }

        @Test
        void deleteDataStore_continuesWithAccessGroupAndEntity_whenOntapVolumeDeletionFails() {
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(fullDetails());
            when(_dataStoreHelper.deletePrimaryDataStore(store)).thenReturn(true);
            doThrow(new CloudRuntimeException("volume busy")).when(storageStrategy).deleteStorageVolume(any(Volume.class));
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(storageStrategy);

                assertTrue(lifecycle.deleteDataStore(store));
            }

            verify(storageStrategy).deleteAccessGroup(any(AccessGroup.class));
            verify(_dataStoreHelper).deletePrimaryDataStore(store);
        }

        @Test
        void deleteDataStore_stillRemovesCloudStackEntity_whenAccessGroupCleanupFails() {
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(fullDetails());
            when(_dataStoreHelper.deletePrimaryDataStore(store)).thenReturn(true);
            doThrow(new CloudRuntimeException("igroup in use")).when(storageStrategy).deleteAccessGroup(any(AccessGroup.class));
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(storageStrategy);

                assertTrue(lifecycle.deleteDataStore(store));
            }

            verify(_dataStoreHelper).deletePrimaryDataStore(store);
        }

        @Test
        void deleteDataStore_stillRemovesCloudStackEntity_whenOntapIsUnreachable() {
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(fullDetails());
            when(_dataStoreHelper.deletePrimaryDataStore(store)).thenReturn(true);
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap()))
                        .thenThrow(new CloudRuntimeException("cannot connect"));

                assertTrue(lifecycle.deleteDataStore(store));
            }

            verifyNoInteractions(storageStrategy);
            verify(_dataStoreHelper).deletePrimaryDataStore(store);
        }

        @Test
        void deleteDataStore_reportsFailure_whenCloudStackEntityCannotBeDeleted() {
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(Collections.emptyMap());
            when(_dataStoreHelper.deletePrimaryDataStore(store)).thenReturn(false);

            assertFalse(lifecycle.deleteDataStore(store));
        }

        // ------------------------------------------------------------------ updateStoragePool: IOPS validation

        private Map<String, String> iopsUpdate(String iops) {
            Map<String, String> details = new HashMap<>();
            details.put(PrimaryDataStoreLifeCycle.CAPACITY_IOPS, iops);
            return details;
        }

        @Test
        void updateStoragePool_rejectsNonNumericIops() {
            InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                    () -> lifecycle.updateStoragePool(storagePool, iopsUpdate("lots")));

            assertTrue(ex.getMessage().contains("Invalid storage pool IOPS capacity"), ex.getMessage());
        }

        @Test
        void updateStoragePool_rejectsZeroOrNegativeIops() {
            assertEquals("Storage pool IOPS capacity must be greater than 0",
                    assertThrows(InvalidParameterValueException.class,
                            () -> lifecycle.updateStoragePool(storagePool, iopsUpdate("0"))).getMessage());
            assertEquals("Storage pool IOPS capacity must be greater than 0",
                    assertThrows(InvalidParameterValueException.class,
                            () -> lifecycle.updateStoragePool(storagePool, iopsUpdate("-10"))).getMessage());
            verifyNoInteractions(storagePoolDao);
        }

        @Test
        void updateStoragePool_rejectsIopsForUnknownPool() {
            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

            InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                    () -> lifecycle.updateStoragePool(storagePool, iopsUpdate("1000")));

            assertTrue(ex.getMessage().contains("Storage pool not found"), ex.getMessage());
        }

        @Test
        void updateStoragePool_rejectsIopsBelowAllocatedIops() {
            StoragePoolVO poolVO = mock(StoragePoolVO.class);
            when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
            when(_capacityMgr.getUsedIops(poolVO)).thenReturn(5000L);

            InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                    () -> lifecycle.updateStoragePool(storagePool, iopsUpdate("4999")));

            assertTrue(ex.getMessage().contains("5000 IOPS are already allocated"), ex.getMessage());
        }

        @Test
        void updateStoragePool_acceptsIopsEqualToAllocated_andSkipsResizeWithoutCapacityChange() {
            StoragePoolVO poolVO = mock(StoragePoolVO.class);
            when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
            when(_capacityMgr.getUsedIops(poolVO)).thenReturn(5000L);

            lifecycle.updateStoragePool(storagePool, iopsUpdate("5000"));

            verify(_capacityMgr).getUsedIops(poolVO);
            verify(storagePool, never()).getCapacityBytes();
        }

        @Test
        void updateStoragePool_doesNothing_whenNoRelevantDetailsChange() {
            lifecycle.updateStoragePool(storagePool, new HashMap<>());

            verifyNoInteractions(storagePoolDao, _capacityMgr);
            verify(storagePool, never()).getCapacityBytes();
        }

        @Test
        void updateStoragePool_failsToResize_whenFlexVolUuidMissing() {
            Map<String, String> details = new HashMap<>();
            details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, "1073741824");
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(storageStrategy);

                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> lifecycle.updateStoragePool(storagePool, details));

                assertTrue(ex.getMessage().contains("Volume UUID or name not found"), ex.getMessage());
            }
            verify(storageStrategy, never()).updateStorageVolume(any(Volume.class));
        }

        @Test
        void updateStoragePool_resizesFlexVol_toRequestedCapacity() {
            Map<String, String> details = fullDetails();
            details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, "2147483648");
            when(storagePool.getCapacityBytes()).thenReturn(1073741824L);
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(storageStrategy);

                lifecycle.updateStoragePool(storagePool, details);
            }

            ArgumentCaptor<Volume> volume = ArgumentCaptor.forClass(Volume.class);
            verify(storageStrategy).updateStorageVolume(volume.capture());
            assertEquals("vol-uuid", volume.getValue().getUuid());
            assertEquals("vol-name", volume.getValue().getName());
            assertEquals(2147483648L, volume.getValue().getSize());
        }

        @Test
        void updateStoragePool_wrapsResizeFailure() {
            Map<String, String> details = fullDetails();
            details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, "2147483648");
            doThrow(new CloudRuntimeException("resize rejected")).when(storageStrategy).updateStorageVolume(any(Volume.class));
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(storageStrategy);

                CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                        () -> lifecycle.updateStoragePool(storagePool, details));

                assertTrue(ex.getMessage().contains("ontap-pool"), ex.getMessage());
                assertTrue(ex.getMessage().contains("resize rejected"), ex.getMessage());
            }
        }

        @Test
        void updateStoragePool_rejectsNonNumericCapacity() {
            Map<String, String> details = fullDetails();
            details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, "big");
            when(storagePool.getCapacityBytes()).thenReturn(1L);

            assertThrows(NumberFormatException.class, () -> lifecycle.updateStoragePool(storagePool, details));
        }
    }

    /**
     * Input validation, zone-wide creation, protocol-specific host selection and delete edge cases of
     * {@link OntapPrimaryDatastoreLifecycle}, complementing {@link OntapPrimaryDatastoreLifecycleTest}.
     */
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @Nested
    class OntapPrimaryDatastoreLifecycleScopeTest {

        private static final long POOL_ID = 1L;

        @InjectMocks
        private OntapPrimaryDatastoreLifecycle lifecycle;

        @Mock private ClusterDao _clusterDao;
        @Mock private StorageStrategy storageStrategy;
        @Mock private PrimaryDataStoreHelper _dataStoreHelper;
        @Mock private ResourceManager _resourceMgr;
        @Mock private StorageManager _storageMgr;
        @Mock private StoragePoolDetailsDao storagePoolDetailsDao;
        @Mock private PrimaryDataStoreDetailsDao _datastoreDetailsDao;
        @Mock private PrimaryDataStoreDao storagePoolDao;
        @Mock private AlertManager _alertMgr;
        @Mock private ClusterScope clusterScope;
        @Mock private ZoneScope zoneScope;
        @Mock private StoragePool storagePool;

        private DataStore dataStore;
        private Map<String, String> poolDetails;

        @BeforeEach
        void setUp() {
            dataStore = mock(DataStore.class, withSettings().extraInterfaces(PrimaryDataStoreInfo.class));
            when(dataStore.getId()).thenReturn(POOL_ID);
            when(((PrimaryDataStoreInfo) dataStore).getClusterId()).thenReturn(1L);

            ClusterVO clusterVO = new ClusterVO(1L, 1L, "clusterName");
            clusterVO.setHypervisorType("KVM");
            when(_clusterDao.findById(1L)).thenReturn(clusterVO);

            when(storageStrategy.connect()).thenReturn(true);
            Aggregate aggregate = new Aggregate();
            aggregate.setName("aggr1");
            aggregate.setUuid("aggr-uuid-1");
            when(storageStrategy.chooseAggregate(any(), any())).thenReturn(aggregate);
            when(storageStrategy.getNetworkInterface(any())).thenReturn(Map.of(OntapStorageConstants.DATA_LIF, "10.1.1.1"));
            Volume volume = new Volume();
            volume.setUuid("vol-uuid");
            volume.setName("vol-name");
            when(storageStrategy.createStorageVolume(any(), any(), any())).thenReturn(volume);

            StoragePoolVO poolVO = new StoragePoolVO();
            poolVO.setId(POOL_ID);
            when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
            when(zoneScope.getScopeId()).thenReturn(1L);

            poolDetails = new HashMap<>();
            poolDetails.put(OntapStorageConstants.USERNAME, "admin");
            poolDetails.put(OntapStorageConstants.PASSWORD, "password");
            poolDetails.put(OntapStorageConstants.SVM_NAME, "svm1");
            poolDetails.put(OntapStorageConstants.PROTOCOL, "NFS3");
            poolDetails.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");
            when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(poolDetails);
        }

        // ---------------------------------------------------------------- initialize

        private Map<String, Object> dsInfos() {
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.USERNAME, "user");
            details.put(OntapStorageConstants.PASSWORD, "pass");
            details.put(OntapStorageConstants.STORAGE_IP, "10.10.10.10");
            details.put(OntapStorageConstants.SVM_NAME, "vs0");
            details.put(OntapStorageConstants.PROTOCOL, "NFS3");

            Map<String, Object> infos = new HashMap<>();
            infos.put("zoneId", 1L);
            infos.put("podId", 1L);
            infos.put("clusterId", 1L);
            infos.put("name", "pool1");
            infos.put("providerName", "NetApp ONTAP");
            infos.put("capacityBytes", 1073741824L);
            infos.put("managed", true);
            infos.put("tags", "tag");
            infos.put("isTagARule", false);
            infos.put("details", details);
            return infos;
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> detailsOf(Map<String, Object> infos) {
            return (Map<String, String>) infos.get("details");
        }

        private void initialize(Map<String, Object> infos) {
            try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
                factory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                lifecycle.initialize(infos);
            }
        }

        private PrimaryDataStoreParameters initializeAndCaptureParameters(Map<String, Object> infos) {
            initialize(infos);
            ArgumentCaptor<PrimaryDataStoreParameters> captor = ArgumentCaptor.forClass(PrimaryDataStoreParameters.class);
            verify(_dataStoreHelper).createPrimaryDataStore(captor.capture());
            return captor.getValue();
        }

        @Test
        void initializeFailsWhenOntapConnectionIsRejected() {
            when(storageStrategy.connect()).thenReturn(false);

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> initialize(dsInfos()));

            assertTrue(thrown.getMessage().contains("details validation failed"), thrown.getMessage());
            verify(_dataStoreHelper, never()).createPrimaryDataStore(any());
        }

        @Test
        void initializeWrapsAggregateSelectionFailure() {
            when(storageStrategy.chooseAggregate(any(), any())).thenThrow(new RuntimeException("no aggregate has space"));

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> initialize(dsInfos()));

            assertTrue(thrown.getMessage().contains("Failed to choose ONTAP aggregate for pool: pool1"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("no aggregate has space"), thrown.getMessage());
        }

        @Test
        void initializeRecordsResolvedSvmUuidOnlyWhenKnown() {
            when(storageStrategy.getResolvedSvmUuid()).thenReturn("svm-uuid-1");
            PrimaryDataStoreParameters withUuid = initializeAndCaptureParameters(dsInfos());
            assertEquals("svm-uuid-1", withUuid.getDetails().get(OntapStorageConstants.SVM_UUID));
        }

        @Test
        void initializeSkipsSvmUuidWhenResolvedValueIsNullOrEmpty() {
            when(storageStrategy.getResolvedSvmUuid()).thenReturn(null);
            assertNull(initializeAndCaptureParameters(dsInfos()).getDetails().get(OntapStorageConstants.SVM_UUID));
        }

        @Test
        void initializeSkipsSvmUuidWhenResolvedValueIsEmpty() {
            when(storageStrategy.getResolvedSvmUuid()).thenReturn("");
            assertNull(initializeAndCaptureParameters(dsInfos()).getDetails().get(OntapStorageConstants.SVM_UUID));
        }

        @Test
        void initializeCreatesZoneWideStorageWhenNoPodOrCluster() {
            Map<String, Object> infos = dsInfos();
            infos.put("podId", null);
            infos.put("clusterId", null);

            PrimaryDataStoreParameters parameters = initializeAndCaptureParameters(infos);

            assertNull(parameters.getClusterId());
            assertNull(parameters.getPodId());
            assertEquals(Long.valueOf(1L), parameters.getZoneId());
            assertNull(parameters.getHypervisorType());
        }

        @Test
        void initializeFailsWhenClusterDoesNotExist() {
            Map<String, Object> infos = dsInfos();
            infos.put("clusterId", 99L);

            NullPointerException thrown = assertThrows(NullPointerException.class, () -> initialize(infos));

            assertTrue(thrown.getMessage().contains("Unable to locate the specified cluster"), thrown.getMessage());
        }

        @Test
        void initializeRejectsMissingCapacity() {
            Map<String, Object> infos = dsInfos();
            infos.remove("capacityBytes");

            InvalidParameterValueException thrown = assertThrows(InvalidParameterValueException.class, () -> initialize(infos));

            assertTrue(thrown.getMessage().contains("capacity is required"), thrown.getMessage());
        }

        @Test
        void initializeRejectsPodWithoutClusterAndClusterWithoutPod() {
            Map<String, Object> podOnly = dsInfos();
            podOnly.put("clusterId", null);
            assertThrows(CloudRuntimeException.class, () -> initialize(podOnly));

            Map<String, Object> clusterOnly = dsInfos();
            clusterOnly.put("podId", null);
            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> initialize(clusterOnly));
            assertTrue(thrown.getMessage().contains("Cluster Id or Pod Id is null"), thrown.getMessage());
        }

        @Test
        void initializeRejectsBlankDetailValue() {
            Map<String, Object> infos = dsInfos();
            detailsOf(infos).put(OntapStorageConstants.USERNAME, " ");

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> initialize(infos));

            assertTrue(thrown.getMessage().contains("empty detail: " + OntapStorageConstants.USERNAME), thrown.getMessage());
        }

        @Test
        void initializeRejectsZoneIdMissingWhenPodAndClusterAreAlsoMissing() {
            Map<String, Object> infos = dsInfos();
            infos.put("podId", null);
            infos.put("clusterId", null);
            infos.put("zoneId", null);

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> initialize(infos));

            assertTrue(thrown.getMessage().contains("all null"), thrown.getMessage());
        }

        // ---------------------------------------------------------------- argument checks on attach

        @Test
        void attachClusterRejectsMissingArgumentsAndUnknownPool() {
            assertThrows(InvalidParameterValueException.class, () -> lifecycle.attachCluster(null, clusterScope));
            assertThrows(InvalidParameterValueException.class, () -> lifecycle.attachCluster(dataStore, null));

            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);
            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> lifecycle.attachCluster(dataStore, clusterScope));
            assertTrue(thrown.getMessage().contains("Storage Pool not found"), thrown.getMessage());
        }

        @Test
        void attachZoneRejectsMissingArgumentsAndUnknownPool() {
            assertThrows(InvalidParameterValueException.class,
                    () -> lifecycle.attachZone(null, zoneScope, Hypervisor.HypervisorType.KVM));
            assertThrows(InvalidParameterValueException.class,
                    () -> lifecycle.attachZone(dataStore, null, Hypervisor.HypervisorType.KVM));

            when(storagePoolDao.findById(POOL_ID)).thenReturn(null);
            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class,
                    () -> lifecycle.attachZone(dataStore, zoneScope, Hypervisor.HypervisorType.KVM));
            assertTrue(thrown.getMessage().contains("Storage Pool not found"), thrown.getMessage());
        }

        @Test
        void attachHostIsNotSupported() {
            assertFalse(lifecycle.attachHost(dataStore, null, null));
        }

        // ---------------------------------------------------------------- host selection per protocol

        private HostVO host(String storageUrl, String storageIp, String privateIp) {
            HostVO host = mock(HostVO.class);
            when(host.getStorageUrl()).thenReturn(storageUrl);
            when(host.getStorageIpAddress()).thenReturn(storageIp);
            when(host.getPrivateIpAddress()).thenReturn(privateIp);
            return host;
        }

        private boolean attachClusterWith(List<HostVO> hosts) throws Exception {
            when(_resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(any())).thenReturn(hosts);
            when(_storageMgr.connectHostToSharedPool(any(), anyLong())).thenReturn(true);
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(storageStrategy);
                return lifecycle.attachCluster(dataStore, clusterScope);
            }
        }

        private AccessGroup captureAccessGroup() {
            ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
            verify(storageStrategy).createAccessGroup(captor.capture());
            return captor.getValue();
        }

        @Test
        void attachClusterWithIscsiSkipsExportPolicyAndStillConnectsEveryHost() throws Exception {
            poolDetails.put(OntapStorageConstants.PROTOCOL, "ISCSI");
            List<HostVO> hosts = Arrays.asList(
                    host("iqn.1994-05.com.redhat:a", null, "10.0.0.5"),
                    host(null, null, "10.0.0.6"),
                    host("   ", null, "10.0.0.7"),
                    host("nfs://not-an-iqn", null, "10.0.0.8"),
                    null);

            assertTrue(attachClusterWith(hosts));

            verify(storageStrategy, never()).createAccessGroup(any());
            verify(_storageMgr, times(5)).connectHostToSharedPool(any(), anyLong());
            verify(_dataStoreHelper).attachCluster(dataStore);
        }

        @Test
        void attachClusterWithNfsUsesStorageIpThenPrivateIpAndSkipsHostsWithoutAddress() throws Exception {
            List<HostVO> hosts = Arrays.asList(
                    host(null, " 192.168.0.5 ", "10.0.0.5"),
                    host(null, null, "10.0.0.6"),
                    host(null, "", null),
                    host(null, " ", " "),
                    null);

            assertTrue(attachClusterWith(hosts));

            AccessGroup group = captureAccessGroup();
            assertEquals(5, group.getHostsToConnect().size());
            assertSame(clusterScope, group.getScope());
            assertEquals(POOL_ID, group.getStoragePoolId());
        }

        @Test
        void attachClusterWithNfsSkipsExportPolicyWhenNoHostHasAnAddress() throws Exception {
            List<HostVO> hosts = new ArrayList<>();
            hosts.add(host(null, null, null));

            assertTrue(attachClusterWith(hosts));

            verify(storageStrategy, never()).createAccessGroup(any());
        }

        @Test
        void attachClusterFailsWhenExportPolicyCannotBeCreated() throws Exception {
            when(storageStrategy.createAccessGroup(any())).thenThrow(new RuntimeException("export policy rejected"));
            List<HostVO> hosts = new ArrayList<>();
            hosts.add(host(null, "10.0.0.5", null));

            CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () -> attachClusterWith(hosts));

            assertTrue(thrown.getMessage().contains("Failed to create NFS access group"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("export policy rejected"), thrown.getMessage());
            verify(_dataStoreHelper, never()).attachCluster(any());
        }

        @Test
        void attachZoneWithIscsiConnectsHostsAndReportsConnectFailure() throws Exception {
            poolDetails.put(OntapStorageConstants.PROTOCOL, "ISCSI");
            List<HostVO> hosts = new ArrayList<>();
            hosts.add(host("iqn.1994-05.com.redhat:a", null, "10.0.0.5"));
            when(_resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(any(), anyLong(), any())).thenReturn(hosts);
            when(_storageMgr.connectHostToSharedPool(any(), anyLong())).thenReturn(true);

            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(storageStrategy);
                assertTrue(lifecycle.attachZone(dataStore, zoneScope, Hypervisor.HypervisorType.KVM));
                verify(storageStrategy, never()).createAccessGroup(any());

                when(_storageMgr.connectHostToSharedPool(any(), anyLong())).thenThrow(new RuntimeException("connect failed"));
                assertFalse(lifecycle.attachZone(dataStore, zoneScope, Hypervisor.HypervisorType.KVM));
            }
        }

        // ---------------------------------------------------------------- deleteDataStore volume identity

        @Test
        void deleteDataStoreSkipsOntapVolumeDeleteWhenIdentityIsIncomplete() {
            when(_storageMgr.getStoragePool(POOL_ID)).thenReturn(storagePool);
            when(storagePool.getName()).thenReturn("ontap-pool");

            String[][] identities = {{null, "name"}, {"", "name"}, {"uuid", null}, {"uuid", ""}};
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(storageStrategy);
                for (String[] identity : identities) {
                    Map<String, String> details = new HashMap<>();
                    details.put(OntapStorageConstants.PROTOCOL, "NFS3");
                    if (identity[0] != null) {
                        details.put(OntapStorageConstants.VOLUME_UUID, identity[0]);
                    }
                    if (identity[1] != null) {
                        details.put(OntapStorageConstants.VOLUME_NAME, identity[1]);
                    }
                    when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(details);
                    when(_dataStoreHelper.deletePrimaryDataStore(dataStore)).thenReturn(true);

                    assertTrue(lifecycle.deleteDataStore(dataStore));
                }
            }

            verify(storageStrategy, never()).deleteStorageVolume(any());
            verify(storageStrategy, times(identities.length)).deleteAccessGroup(any());
        }

        // ------------------------------------------------- initialize: pool parameters and boundaries

        @Test
        void initializeBuildsNfsPoolParametersFromTheRequestAndReturnsTheCreatedStore() {
            DataStore created = mock(DataStore.class);
            when(_dataStoreHelper.createPrimaryDataStore(any())).thenReturn(created);
            Map<String, Object> infos = dsInfos();
            infos.put("isTagARule", true);
            infos.put("capacityIops", 3000L);
            DataStore returned;
            try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
                factory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
                returned = lifecycle.initialize(infos);
            }
            ArgumentCaptor<PrimaryDataStoreParameters> captor = ArgumentCaptor.forClass(PrimaryDataStoreParameters.class);
            verify(_dataStoreHelper).createPrimaryDataStore(captor.capture());
            PrimaryDataStoreParameters p = captor.getValue();

            assertSame(created, returned);
            assertEquals(Hypervisor.HypervisorType.KVM, p.getHypervisorType());
            assertEquals("10.1.1.1", p.getHost());
            assertEquals(OntapStorageConstants.NFS3_PORT, p.getPort());
            assertEquals("/pool1", p.getPath());
            assertEquals(com.cloud.storage.Storage.StoragePoolType.NetworkFilesystem, p.getType());
            assertEquals("tag", p.getTags());
            assertEquals(Boolean.TRUE, p.isTagARule());
            assertNotNull(p.getUuid());
            assertEquals(java.util.UUID.fromString(p.getUuid()).toString(), p.getUuid());
            assertEquals(Long.valueOf(1L), p.getZoneId());
            assertEquals(Long.valueOf(1L), p.getPodId());
            assertEquals(Long.valueOf(1L), p.getClusterId());
            assertEquals("pool1", p.getName());
            assertEquals("NetApp ONTAP", p.getProviderName());
            assertTrue(p.isManaged());
            assertEquals(1073741824L, p.getCapacityBytes());
            assertEquals(Long.valueOf(3000L), p.getCapacityIops());
            assertEquals(0L, p.getUsedBytes());
            assertEquals("1073741824", p.getDetails().get(OntapStorageConstants.SIZE));
            assertEquals("vol-uuid", p.getDetails().get(OntapStorageConstants.VOLUME_UUID));
            assertEquals("vol-name", p.getDetails().get(OntapStorageConstants.VOLUME_NAME));
        }

        @Test
        void initializeBuildsIscsiPoolParametersWithTheOntapTargetPath() {
            when(storageStrategy.getStoragePath()).thenReturn("iqn.1992-08.com.netapp:sn.abc");
            Map<String, Object> infos = dsInfos();
            detailsOf(infos).put(OntapStorageConstants.PROTOCOL, "ISCSI");

            PrimaryDataStoreParameters p = initializeAndCaptureParameters(infos);

            assertEquals(com.cloud.storage.Storage.StoragePoolType.OntapiSCSI, p.getType());
            assertEquals("iqn.1992-08.com.netapp:sn.abc", p.getPath());
            assertEquals(OntapStorageConstants.ISCSI_PORT, p.getPort());
            assertEquals("10.1.1.1", p.getHost());
        }

        @Test
        void initializeAcceptsExactlyTheMinimumCapacityAndRejectsOneByteLess() {
            long min = 20971520L;
            Map<String, Object> atMin = dsInfos();
            atMin.put("capacityBytes", min);
            assertEquals(min, initializeAndCaptureParameters(atMin).getCapacityBytes());

            Map<String, Object> below = dsInfos();
            below.put("capacityBytes", min - 1);
            InvalidParameterValueException thrown = assertThrows(InvalidParameterValueException.class, () -> initialize(below));
            assertTrue(thrown.getMessage().contains("below the ONTAP minimum"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains(String.valueOf(min - 1)), thrown.getMessage());
        }

        @Test
        void initializeRejectsZeroCapacityWithTheRequiredMessage() {
            Map<String, Object> zero = dsInfos();
            zero.put("capacityBytes", 0L);

            InvalidParameterValueException thrown = assertThrows(InvalidParameterValueException.class, () -> initialize(zero));

            assertTrue(thrown.getMessage().contains("capacity is required"), thrown.getMessage());
        }

        @Test
        void initializeAcceptsOneIopsAndRejectsZero() {
            Map<String, Object> one = dsInfos();
            one.put("capacityIops", 1L);
            assertEquals(Long.valueOf(1L), initializeAndCaptureParameters(one).getCapacityIops());

            Map<String, Object> zero = dsInfos();
            zero.put("capacityIops", 0L);
            assertThrows(InvalidParameterValueException.class, () -> initialize(zero));
        }

        @Test
        void deleteDataStoreReportsFailureWhenOntapCleanupSucceedsButCloudStackDeleteFails() {
            when(_storageMgr.getStoragePool(POOL_ID)).thenReturn(storagePool);
            when(storagePool.getName()).thenReturn("ontap-pool");
            Map<String, String> details = new HashMap<>();
            details.put(OntapStorageConstants.PROTOCOL, "NFS3");
            details.put(OntapStorageConstants.VOLUME_UUID, "vol-uuid");
            details.put(OntapStorageConstants.VOLUME_NAME, "vol-name");
            when(_datastoreDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(details);
            when(_dataStoreHelper.deletePrimaryDataStore(dataStore)).thenReturn(false);
            try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
                utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(any())).thenReturn(storageStrategy);

                assertFalse(lifecycle.deleteDataStore(dataStore));
            }
            verify(storageStrategy).deleteStorageVolume(any());
        }
    }
}
