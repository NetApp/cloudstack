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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.cloudstack.storage.feign.model.Volume;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.dc.ClusterVO;
import com.cloud.host.HostVO;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.StorageManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import com.cloud.hypervisor.Hypervisor;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.withSettings;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.HashMap;
import org.apache.cloudstack.storage.provider.StorageProviderFactory;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.volume.datastore.PrimaryDataStoreHelper;
import org.apache.cloudstack.storage.utils.Utility;


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
        when(storageStrategy.getNetworkInterface()).thenReturn("testNetworkInterface");

        Volume volume = new Volume();
        volume.setUuid("test-volume-uuid");
        volume.setName("testVolume");
        when(storageStrategy.createStorageVolume(any(), any())).thenReturn(volume);

        // Setup for attachCluster tests
        // Configure dataStore mock with necessary methods (works for both DataStore and PrimaryDataStoreInfo)
        when(dataStore.getId()).thenReturn(1L);
        when(((PrimaryDataStoreInfo) dataStore).getClusterId()).thenReturn(1L);
        mockHosts = new ArrayList<>();
        HostVO host1 = new HostVO("host1-guid");
        host1.setPrivateIpAddress("192.168.1.10");
        host1.setClusterId(1L);
        HostVO host2 = new HostVO("host2-guid");
        host2.setPrivateIpAddress("192.168.1.11");
        host2.setClusterId(1L);
        mockHosts.add(host1);
        mockHosts.add(host2);
        poolDetails = new HashMap<>();
        poolDetails.put("username", "admin");
        poolDetails.put("password", "password");
        poolDetails.put("svmName", "svm1");
        poolDetails.put("protocol", "NFS3");
        poolDetails.put("managementLIF", "192.168.1.100");
        poolDetails.put("isDisaggregated", "false");
    }

    @Test
    public void testInitialize_positive() {

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("username", "testUser");
        dsInfos.put("password", "testPassword");
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",200000L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", new HashMap<String, String>());

        try(MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
        }
    }

    @Test
    public void testInitialize_null_Arg() {
        Exception ex = assertThrows(CloudRuntimeException.class,() ->
                ontapPrimaryDatastoreLifecycle.initialize(null));
        assertTrue(ex.getMessage().contains("Datastore info map is null, cannot create primary storage"));
    }

    @Test
    public void testInitialize_missingRequiredDetailKey() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",200000L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", new HashMap<String, String>());

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            Exception ex = assertThrows(CloudRuntimeException.class, () -> ontapPrimaryDatastoreLifecycle.initialize(dsInfos));
            assertTrue(ex.getMessage().contains("missing detail"));
        }
    }

    @Test
    public void testInitialize_invalidCapacityBytes() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",-1L);
        dsInfos.put("managed",true);
        dsInfos.put("tags", "testTag");
        dsInfos.put("isTagARule", false);
        dsInfos.put("details", new HashMap<String, String>());

        try (MockedStatic<StorageProviderFactory> storageProviderFactory = Mockito.mockStatic(StorageProviderFactory.class)) {
            storageProviderFactory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(storageStrategy);
            ontapPrimaryDatastoreLifecycle.initialize(dsInfos);
        }
    }

    @Test
    public void testInitialize_unmanagedStorage() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",200000L);
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
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", null);
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",200000L);
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
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", null);
        dsInfos.put("capacityBytes",200000L);
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
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false");
        dsInfos.put("zoneId",null);
        dsInfos.put("podId",null);
        dsInfos.put("clusterId", null);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",200000L);
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

        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 2L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",200000L);
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
        assertTrue(ex.getMessage().contains("ONTAP primary storage is supported only for KVM hypervisor"));
    }

    @Test
    @Ig
    public void testInitialize_unexpectedDetailKey() {
        Map<String, Object> dsInfos = new HashMap<>();
        dsInfos.put("url", "username=testUser;password=testPassword;svmName=testSVM;protocol=NFS3;managementLIF=192.168.1.1;isDisaggregated=false;unexpectedKey=unexpectedValue");
        dsInfos.put("zoneId",1L);
        dsInfos.put("podId",1L);
        dsInfos.put("clusterId", 1L);
        dsInfos.put("name", "testStoragePool");
        dsInfos.put("providerName", "testProvider");
        dsInfos.put("capacityBytes",200000L);
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
        assertTrue(ex.getMessage().contains("Unexpected ONTAP detail key in URL"));
    }

}