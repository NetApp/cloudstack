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
package org.apache.cloudstack.storage.listener;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
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

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.alert.AlertManager;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OntapHostListenerTest {

    private static final long HOST_ID = 5L;
    private static final long POOL_ID = 9L;
    private static final String MOUNT_PATH = "/mnt/ontap";

    @InjectMocks
    private OntapHostListener listener;

    @Mock
    private AgentManager agentManager;
    @Mock
    private AlertManager alertManager;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private StoragePoolHostDao storagePoolHostDao;
    @Mock
    private StoragePoolDetailsDao storagePoolDetailsDao;

    @Test
    public void hostConnectReturnsFalseWhenHostIsMissing() {
        Mockito.doReturn(null).when(hostDao).findById(HOST_ID);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
    }

    @Test
    public void hostConnectReturnsFalseWhenHostIsNotKvm() {
        HostVO host = kvmHost();
        Mockito.doReturn(Hypervisor.HypervisorType.VMware).when(host).getHypervisorType();

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
        Mockito.verify(storagePoolDao, Mockito.never()).findById(Mockito.anyLong());
    }

    @Test
    public void hostConnectReturnsFalseWhenPoolIsMissing() {
        kvmHost();
        Mockito.doReturn(null).when(storagePoolDao).findById(POOL_ID);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
    }

    @Test
    public void hostConnectReturnsFalseWhenPoolDetailsAreMissing() {
        kvmHost();
        storagePool();
        Mockito.doReturn(null).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        Mockito.doReturn(Collections.emptyMap()).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
    }

    @Test
    public void hostConnectReturnsFalseWhenProtocolIsMissing() {
        kvmHost();
        storagePool();
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.PROTOCOL, null);
        Mockito.doReturn(details).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void hostConnectReturnsFalseWhenAgentAnswerIsNull() {
        prepareIscsiConnect();
        Mockito.doReturn(null).when(agentManager).easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
        Mockito.verify(storagePoolHostDao, Mockito.never()).persist(Mockito.any());
    }

    @Test
    public void hostConnectReturnsFalseAndAlertsWhenAgentAnswerFails() {
        prepareIscsiConnect();
        Mockito.doReturn(new Answer(null, false, "attach failed")).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
        Mockito.verify(alertManager).sendAlert(Mockito.eq(AlertManager.AlertType.ALERT_TYPE_HOST),
                Mockito.eq(1L), Mockito.eq(2L), Mockito.contains("Unable to attach"), Mockito.contains("Unable to attach"));
    }

    @Test
    public void hostConnectReturnsFalseWhenAnswerTypeIsUnexpected() {
        prepareIscsiConnect();
        Mockito.doReturn(new Answer(null, true, "ok")).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
    }

    @Test
    public void hostConnectReturnsFalseWhenPoolInfoIsNull() {
        prepareIscsiConnect();
        Mockito.doReturn(new ModifyStoragePoolAnswer(null, true, null)).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
    }

    @Test
    public void hostConnectReturnsFalseWhenAgentSendThrows() {
        prepareIscsiConnect();
        Mockito.doThrow(new IllegalStateException("agent down")).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
    }

    @Test
    public void hostConnectCreatesPoolHostRefForIscsi() {
        StoragePoolVO pool = prepareIscsiConnect();
        Mockito.doReturn(mountedAnswer(100L, 40L)).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));
        Mockito.doReturn(null).when(storagePoolHostDao).findByPoolHost(POOL_ID, HOST_ID);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        ArgumentCaptor<StoragePoolHostVO> captor = ArgumentCaptor.forClass(StoragePoolHostVO.class);
        Mockito.verify(storagePoolHostDao).persist(captor.capture());
        assertEquals(MOUNT_PATH, captor.getValue().getLocalPath());
        Mockito.verify(pool).setCapacityBytes(100L);
        Mockito.verify(pool).setUsedBytes(60L);
        Mockito.verify(storagePoolDao).update(POOL_ID, pool);
    }

    @Test
    public void hostConnectUpdatesPoolHostRefAndSkipsCapacityWhenBytesAreZero() {
        StoragePoolVO pool = prepareIscsiConnect();
        Mockito.doReturn(mountedAnswer(0L, 0L)).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));
        StoragePoolHostVO existing = Mockito.mock(StoragePoolHostVO.class);
        Mockito.doReturn(77L).when(existing).getId();
        Mockito.doReturn(existing).when(storagePoolHostDao).findByPoolHost(POOL_ID, HOST_ID);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        Mockito.verify(existing).setLocalPath(MOUNT_PATH);
        Mockito.verify(storagePoolHostDao).update(77L, existing);
        Mockito.verify(storagePoolHostDao, Mockito.never()).persist(Mockito.any());
        Mockito.verify(pool, Mockito.never()).setCapacityBytes(Mockito.anyLong());
    }

    @Test
    public void hostConnectClampsUsedBytesAndSkipsMissingPoolRecord() {
        prepareIscsiConnect();
        StoragePoolVO pool = storagePoolDao.findById(POOL_ID);
        Mockito.doReturn(pool, (Object) null).when(storagePoolDao).findById(POOL_ID);
        Mockito.doReturn(mountedAnswer(5L, 8L)).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));
        Mockito.verify(pool, Mockito.never()).setUsedBytes(Mockito.anyLong());

        Mockito.doReturn(pool).when(storagePoolDao).findById(POOL_ID);
        Mockito.doReturn(mountedAnswer(5L, 8L)).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));
        Mockito.verify(pool).setUsedBytes(0L);
        Mockito.verify(pool).setCapacityBytes(5L);
    }

    @Test
    public void hostConnectReturnsFalseWhenNfsIsNotEnabledOnHost() {
        HostVO host = kvmHost();
        Mockito.doReturn(null).when(host).getStorageIpAddress();
        Mockito.doReturn("  ").when(host).getPrivateIpAddress();
        storagePool();
        Mockito.doReturn(protocolDetails("nfs3")).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void hostConnectUpdatesNfsExportPolicyWhenPrivateIpIsPresent() {
        HostVO host = kvmHost();
        Mockito.doReturn("   ").when(host).getStorageIpAddress();
        Mockito.doReturn("10.2.2.2").when(host).getPrivateIpAddress();
        storagePool();
        Mockito.doReturn(protocolDetails(ProtocolType.NFS3.name())).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        Mockito.doReturn(mountedAnswer(10L, 4L)).when(agentManager)
                .easySend(Mockito.eq(HOST_ID), Mockito.any(ModifyStoragePoolCommand.class));
        StorageStrategy strategy = Mockito.mock(StorageStrategy.class);

        try (MockedStatic<OntapStorageUtils> utilities = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilities.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(Mockito.anyMap())).thenReturn(strategy);

            assertTrue(listener.hostConnect(HOST_ID, POOL_ID));
        }

        ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
        Mockito.verify(strategy).updateAccessGroup(captor.capture());
        assertEquals(POOL_ID, captor.getValue().getStoragePoolId());
        assertEquals(List.of(host), captor.getValue().getHostsToConnect());
    }

    @Test
    public void hostConnectReturnsFalseWhenNfsExportUpdateFails() {
        kvmHost();
        storagePool();
        Mockito.doReturn(protocolDetails(ProtocolType.NFS3.name())).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        StorageStrategy strategy = Mockito.mock(StorageStrategy.class);
        Mockito.doThrow(new IllegalStateException("export failed")).when(strategy).updateAccessGroup(Mockito.any());

        try (MockedStatic<OntapStorageUtils> utilities = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilities.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(Mockito.anyMap())).thenReturn(strategy);

            assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
        }
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void hostDisconnectedRemovedEnabledAndAddedReturnFalse() {
        assertFalse(listener.hostDisconnected(HOST_ID, POOL_ID));
        assertFalse(listener.hostRemoved(HOST_ID, 3L));
        assertFalse(listener.hostEnabled(HOST_ID));
        assertFalse(listener.hostAdded(HOST_ID));
    }

    @Test
    public void hostAboutToBeRemovedReturnsTrueWhenHostIsMissing() {
        Mockito.doReturn(null).when(hostDao).findById(HOST_ID);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
        Mockito.verify(storagePoolHostDao, Mockito.never()).listByHostId(Mockito.anyLong());
    }

    @Test
    public void hostAboutToBeRemovedReturnsTrueWhenHostHasNoPools() {
        kvmHost();
        Mockito.doReturn(null).when(storagePoolHostDao).listByHostId(HOST_ID);
        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

        Mockito.doReturn(Collections.emptyList()).when(storagePoolHostDao).listByHostId(HOST_ID);
        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
    }

    @Test
    public void hostAboutToBeRemovedSkipsMissingPoolsAndNonNfsDetails() {
        kvmHost();
        StoragePoolHostVO missingPoolRef = poolRef(100L);
        StoragePoolHostVO emptyDetailsRef = poolRef(101L);
        StoragePoolVO emptyDetailsPool = namedPool(101L);
        Mockito.doReturn(emptyDetailsPool).when(storagePoolDao).findById(101L);
        Mockito.doReturn(Collections.emptyMap()).when(storagePoolDetailsDao).listDetailsKeyPairs(101L);
        StoragePoolHostVO iscsiRef = poolRef(102L);
        StoragePoolVO iscsiPool = namedPool(102L);
        Mockito.doReturn(iscsiPool).when(storagePoolDao).findById(102L);
        Mockito.doReturn(protocolDetails(ProtocolType.ISCSI.name())).when(storagePoolDetailsDao).listDetailsKeyPairs(102L);
        Mockito.doReturn(null).when(storagePoolDao).findById(100L);
        Mockito.doReturn(List.of(missingPoolRef, emptyDetailsRef, iscsiRef)).when(storagePoolHostDao).listByHostId(HOST_ID);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
    }

    @Test
    public void hostAboutToBeRemovedRemovesNfsExportRule() {
        HostVO host = kvmHost();
        StoragePoolVO pool = namedPool(POOL_ID);
        Mockito.doReturn(pool).when(storagePoolDao).findById(POOL_ID);
        Mockito.doReturn(protocolDetails("NFS3")).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        Mockito.doReturn(List.of(poolRef(POOL_ID))).when(storagePoolHostDao).listByHostId(HOST_ID);
        StorageStrategy strategy = Mockito.mock(StorageStrategy.class);

        try (MockedStatic<OntapStorageUtils> utilities = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilities.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(Mockito.anyMap())).thenReturn(strategy);

            assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
        }

        ArgumentCaptor<AccessGroup> captor = ArgumentCaptor.forClass(AccessGroup.class);
        Mockito.verify(strategy).updateAccessGroup(captor.capture());
        assertEquals(AccessGroup.HostRuleAction.REMOVE, captor.getValue().getHostRuleAction());
        assertEquals(List.of(host), captor.getValue().getHostsToConnect());
        assertEquals(POOL_ID, captor.getValue().getStoragePoolId());
    }

    @Test
    public void hostAboutToBeRemovedSkipsNfsRemovalWhenHostHasNoIp() {
        HostVO host = kvmHost();
        Mockito.doReturn("").when(host).getStorageIpAddress();
        Mockito.doReturn(null).when(host).getPrivateIpAddress();
        StoragePoolVO pool = namedPool(POOL_ID);
        Mockito.doReturn(pool).when(storagePoolDao).findById(POOL_ID);
        Mockito.doReturn(protocolDetails(ProtocolType.NFS3.name())).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        Mockito.doReturn(List.of(poolRef(POOL_ID))).when(storagePoolHostDao).listByHostId(HOST_ID);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
    }

    @Test
    public void hostAboutToBeRemovedContinuesWhenExportRemovalFails() {
        kvmHost();
        StoragePoolVO pool = namedPool(POOL_ID);
        Mockito.doReturn(pool).when(storagePoolDao).findById(POOL_ID);
        Mockito.doReturn(protocolDetails(ProtocolType.NFS3.name())).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        Mockito.doReturn(List.of(poolRef(POOL_ID))).when(storagePoolHostDao).listByHostId(HOST_ID);
        StorageStrategy strategy = Mockito.mock(StorageStrategy.class);
        Mockito.doThrow(new IllegalStateException("remove failed")).when(strategy).updateAccessGroup(Mockito.any());

        try (MockedStatic<OntapStorageUtils> utilities = Mockito.mockStatic(OntapStorageUtils.class)) {
            utilities.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(Mockito.anyMap())).thenReturn(strategy);

            assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
        }
    }

    @Test
    public void hostAboutToBeRemovedContinuesWhenPoolDetailsLookupFails() {
        kvmHost();
        StoragePoolVO pool = namedPool(POOL_ID);
        Mockito.doReturn(pool).when(storagePoolDao).findById(POOL_ID);
        Mockito.doThrow(new IllegalStateException("details down")).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        Mockito.doReturn(List.of(poolRef(POOL_ID))).when(storagePoolHostDao).listByHostId(HOST_ID);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
    }

    private HostVO kvmHost() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.doReturn(HOST_ID).when(host).getId();
        Mockito.doReturn("kvm-host").when(host).getName();
        Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(host).getHypervisorType();
        Mockito.doReturn("10.1.1.5").when(host).getStorageIpAddress();
        Mockito.doReturn("10.1.1.6").when(host).getPrivateIpAddress();
        Mockito.doReturn(host).when(hostDao).findById(HOST_ID);
        return host;
    }

    private StoragePoolVO storagePool() {
        return namedPool(POOL_ID);
    }

    private StoragePoolVO namedPool(long poolId) {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.doReturn(poolId).when(pool).getId();
        Mockito.doReturn("ontap-pool-" + poolId).when(pool).getName();
        Mockito.doReturn(1L).when(pool).getDataCenterId();
        Mockito.doReturn(2L).when(pool).getPodId();
        Mockito.doReturn(pool).when(storagePoolDao).findById(poolId);
        return pool;
    }

    private StoragePoolVO prepareIscsiConnect() {
        kvmHost();
        StoragePoolVO pool = storagePool();
        Mockito.doReturn(protocolDetails(ProtocolType.ISCSI.name())).when(storagePoolDetailsDao).listDetailsKeyPairs(POOL_ID);
        return pool;
    }

    private Map<String, String> protocolDetails(String protocol) {
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.PROTOCOL, protocol);
        return details;
    }

    private ModifyStoragePoolAnswer mountedAnswer(long capacityBytes, long availableBytes) {
        ModifyStoragePoolAnswer answer = new ModifyStoragePoolAnswer(null, true, null);
        answer.setPoolInfo(new StoragePoolInfo("pool-uuid", "10.0.0.8", "/export", MOUNT_PATH,
                StoragePoolType.NetworkFilesystem, capacityBytes, availableBytes));
        return answer;
    }

    private StoragePoolHostVO poolRef(long poolId) {
        StoragePoolHostVO ref = Mockito.mock(StoragePoolHostVO.class);
        Mockito.doReturn(poolId).when(ref).getPoolId();
        return ref;
    }
}
