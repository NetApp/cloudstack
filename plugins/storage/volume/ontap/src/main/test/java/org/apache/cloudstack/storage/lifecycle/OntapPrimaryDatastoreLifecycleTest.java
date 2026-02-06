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

}