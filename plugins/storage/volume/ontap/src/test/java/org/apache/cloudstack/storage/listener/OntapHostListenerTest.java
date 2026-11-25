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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
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
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OntapHostListenerTest {

    private static final long HOST_ID = 11L;
    private static final long POOL_ID = 22L;
    private static final String LOCAL_PATH = "/mnt/ontap-pool";
    private static final long GB = 1024L * 1024L * 1024L;

    @Mock
    private AgentManager agentMgr;
    @Mock
    private AlertManager alertMgr;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private StoragePoolHostDao storagePoolHostDao;
    @Mock
    private StoragePoolDetailsDao storagePoolDetailsDao;

    @Mock
    private HostVO host;
    @Mock
    private StoragePoolVO pool;
    @Mock
    private StorageStrategy strategy;

    @InjectMocks
    private OntapHostListener listener;

    // ------------------------------------------------------------ helpers

    private void givenKvmHost() {
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getName()).thenReturn("kvm-host-1");
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getStorageIpAddress()).thenReturn("10.0.0.5");
        when(host.getPrivateIpAddress()).thenReturn("192.168.0.5");
    }

    private void givenPool() {
        when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);
        when(pool.getId()).thenReturn(POOL_ID);
        when(pool.getName()).thenReturn("ontap-pool");
        when(pool.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);
    }

    private Map<String, String> details(String protocol) {
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        if (protocol != null) {
            details.put(OntapStorageConstants.PROTOCOL, protocol);
        }
        return details;
    }

    private void givenDetails(String protocol) {
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(details(protocol));
    }

    private ModifyStoragePoolAnswer givenSuccessfulAnswer(long capacityBytes, long availableBytes) {
        ModifyStoragePoolAnswer answer = mock(ModifyStoragePoolAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getPoolInfo()).thenReturn(new StoragePoolInfo("uuid", "host", "/path", LOCAL_PATH,
                StoragePoolType.NetworkFilesystem, capacityBytes, availableBytes));
        when(agentMgr.easySend(eq(HOST_ID), any(ModifyStoragePoolCommand.class))).thenReturn(answer);
        return answer;
    }

    private void givenReadyToConnect(String protocol) {
        givenKvmHost();
        givenPool();
        givenDetails(protocol);
    }

    // ------------------------------------------------- hostConnect: negative

    @Test
    public void hostConnect_returnsFalse_whenHostNotFound() {
        when(hostDao.findById(HOST_ID)).thenReturn(null);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verify(storagePoolDao, never()).findById(anyLong());
        verifyNoInteractions(agentMgr);
    }

    @Test
    public void hostConnect_returnsFalse_whenHostIsNotKvm() {
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verify(storagePoolDao, never()).findById(anyLong());
        verifyNoInteractions(agentMgr);
    }

    @Test
    public void hostConnect_returnsFalse_whenPoolNotFound() {
        givenKvmHost();
        when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verifyNoInteractions(agentMgr);
        verifyNoInteractions(storagePoolDetailsDao);
    }

    @Test
    public void hostConnect_returnsFalse_whenPoolDetailsAreNull() {
        givenKvmHost();
        givenPool();
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(null);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verifyNoInteractions(agentMgr);
    }

    @Test
    public void hostConnect_returnsFalse_whenPoolDetailsAreEmpty() {
        givenKvmHost();
        givenPool();
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(Collections.emptyMap());

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verifyNoInteractions(agentMgr);
    }

    @Test
    public void hostConnect_returnsFalse_whenProtocolDetailMissing() {
        givenReadyToConnect(null);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verifyNoInteractions(agentMgr);
    }

    @Test
    public void hostConnect_returnsFalse_whenAgentReturnsNoAnswer() {
        givenReadyToConnect("ISCSI");
        when(agentMgr.easySend(eq(HOST_ID), any(ModifyStoragePoolCommand.class))).thenReturn(null);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verify(storagePoolHostDao, never()).persist(any(StoragePoolHostVO.class));
    }

    @Test
    public void hostConnect_returnsFalseAndRaisesAlert_whenAgentAnswerFails() {
        givenReadyToConnect("ISCSI");
        Answer failed = mock(Answer.class);
        when(failed.getResult()).thenReturn(false);
        when(failed.getDetails()).thenReturn("mount failed");
        when(agentMgr.easySend(eq(HOST_ID), any(ModifyStoragePoolCommand.class))).thenReturn(failed);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verify(alertMgr).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_HOST), anyLong(), any(), anyString(), anyString());
        verify(storagePoolHostDao, never()).persist(any(StoragePoolHostVO.class));
    }

    @Test
    public void hostConnect_returnsFalse_whenAnswerIsNotModifyStoragePoolAnswer() {
        givenReadyToConnect("ISCSI");
        Answer unexpected = mock(Answer.class);
        when(unexpected.getResult()).thenReturn(true);
        when(agentMgr.easySend(eq(HOST_ID), any(ModifyStoragePoolCommand.class))).thenReturn(unexpected);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verify(storagePoolHostDao, never()).persist(any(StoragePoolHostVO.class));
    }

    @Test
    public void hostConnect_returnsFalse_whenPoolInfoIsNull() {
        givenReadyToConnect("ISCSI");
        ModifyStoragePoolAnswer answer = mock(ModifyStoragePoolAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getPoolInfo()).thenReturn(null);
        when(agentMgr.easySend(eq(HOST_ID), any(ModifyStoragePoolCommand.class))).thenReturn(answer);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verify(storagePoolHostDao, never()).persist(any(StoragePoolHostVO.class));
    }

    @Test
    public void hostConnect_returnsFalse_whenDaoThrows() {
        givenReadyToConnect("ISCSI");
        givenSuccessfulAnswer(100 * GB, 40 * GB);
        when(storagePoolHostDao.findByPoolHost(POOL_ID, HOST_ID)).thenThrow(new RuntimeException("db down"));

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
    }

    // ------------------------------------------------- hostConnect: positive

    @Test
    public void hostConnect_createsHostReferenceAndUpdatesCapacity_whenNoReferenceExists() {
        givenReadyToConnect("ISCSI");
        givenSuccessfulAnswer(100 * GB, 40 * GB);
        when(storagePoolHostDao.findByPoolHost(POOL_ID, HOST_ID)).thenReturn(null);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        ArgumentCaptor<StoragePoolHostVO> created = ArgumentCaptor.forClass(StoragePoolHostVO.class);
        verify(storagePoolHostDao).persist(created.capture());
        assertEquals(POOL_ID, created.getValue().getPoolId());
        assertEquals(HOST_ID, created.getValue().getHostId());
        assertEquals(LOCAL_PATH, created.getValue().getLocalPath());
        verify(storagePoolHostDao, never()).update(anyLong(), any(StoragePoolHostVO.class));
        verify(pool).setCapacityBytes(100 * GB);
        verify(pool).setUsedBytes(60 * GB);
        verify(storagePoolDao).update(POOL_ID, pool);
    }

    @Test
    public void hostConnect_updatesExistingHostReference_whenReferenceExists() {
        givenReadyToConnect("ISCSI");
        givenSuccessfulAnswer(100 * GB, 40 * GB);
        StoragePoolHostVO existing = mock(StoragePoolHostVO.class);
        when(existing.getId()).thenReturn(5L);
        when(storagePoolHostDao.findByPoolHost(POOL_ID, HOST_ID)).thenReturn(existing);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        verify(existing).setLocalPath(LOCAL_PATH);
        verify(storagePoolHostDao).update(5L, existing);
        verify(storagePoolHostDao, never()).persist(any(StoragePoolHostVO.class));
    }

    @Test
    public void hostConnect_sendsAddCommandWithPoolDetails() {
        givenReadyToConnect("ISCSI");
        givenSuccessfulAnswer(100 * GB, 40 * GB);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        ArgumentCaptor<ModifyStoragePoolCommand> cmd = ArgumentCaptor.forClass(ModifyStoragePoolCommand.class);
        verify(agentMgr).easySend(eq(HOST_ID), cmd.capture());
        assertTrue(cmd.getValue().getAdd());
        assertEquals("ISCSI", cmd.getValue().getDetails().get(OntapStorageConstants.PROTOCOL));
    }

    @Test
    public void hostConnect_doesNotUpdateCapacity_whenReportedCapacityIsZero() {
        givenReadyToConnect("ISCSI");
        givenSuccessfulAnswer(0L, 0L);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        verify(storagePoolHostDao).persist(any(StoragePoolHostVO.class));
        verify(storagePoolDao, never()).update(anyLong(), any(StoragePoolVO.class));
    }

    @Test
    public void hostConnect_clampsUsedBytesToZero_whenAvailableExceedsCapacity() {
        givenReadyToConnect("ISCSI");
        givenSuccessfulAnswer(10 * GB, 20 * GB);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        verify(pool).setUsedBytes(0L);
    }

    @Test
    public void hostConnect_skipsCapacityUpdate_whenPoolDisappearsAfterAnswer() {
        givenKvmHost();
        givenDetails("ISCSI");
        when(pool.getName()).thenReturn("ontap-pool");
        when(pool.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(pool, (StoragePoolVO) null);
        givenSuccessfulAnswer(100 * GB, 40 * GB);

        assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

        verify(storagePoolDao, never()).update(anyLong(), any(StoragePoolVO.class));
    }

    // ------------------------------------------- hostConnect: NFS3 export policy

    @Test
    public void hostConnect_updatesExportPolicy_whenProtocolIsNfs3() {
        givenReadyToConnect("NFS3");
        givenSuccessfulAnswer(100 * GB, 40 * GB);

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(strategy);

            assertTrue(listener.hostConnect(HOST_ID, POOL_ID));
        }

        ArgumentCaptor<AccessGroup> group = ArgumentCaptor.forClass(AccessGroup.class);
        verify(strategy).updateAccessGroup(group.capture());
        assertEquals(POOL_ID, group.getValue().getStoragePoolId());
        assertEquals(List.of(host), group.getValue().getHostsToConnect());
        assertEquals(AccessGroup.HostRuleAction.ADD, group.getValue().getHostRuleAction());
    }

    @Test
    public void hostConnect_matchesNfs3ProtocolCaseInsensitively() {
        givenReadyToConnect("nfs3");
        givenSuccessfulAnswer(100 * GB, 40 * GB);

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(strategy);

            assertTrue(listener.hostConnect(HOST_ID, POOL_ID));
        }

        verify(strategy).updateAccessGroup(any(AccessGroup.class));
    }

    @Test
    public void hostConnect_doesNotTouchExportPolicy_whenProtocolIsIscsi() {
        givenReadyToConnect("ISCSI");
        givenSuccessfulAnswer(100 * GB, 40 * GB);

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            assertTrue(listener.hostConnect(HOST_ID, POOL_ID));

            utils.verifyNoInteractions();
        }
        verifyNoInteractions(strategy);
    }

    @Test
    public void hostConnect_returnsFalse_whenNfs3HostHasNoUsableIpAddress() {
        givenReadyToConnect("NFS3");
        when(host.getStorageIpAddress()).thenReturn("  ");
        when(host.getPrivateIpAddress()).thenReturn(" ");

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

            utils.verifyNoInteractions();
        }
        verifyNoInteractions(agentMgr);
    }

    @Test
    public void hostConnect_returnsFalse_whenNfs3HostIpAddressesAreNull() {
        givenReadyToConnect("NFS3");
        when(host.getStorageIpAddress()).thenReturn(null);
        when(host.getPrivateIpAddress()).thenReturn(null);

        assertFalse(listener.hostConnect(HOST_ID, POOL_ID));

        verifyNoInteractions(agentMgr);
    }

    @Test
    public void hostConnect_continues_whenNfs3HostOnlyHasPrivateIp() {
        givenReadyToConnect("NFS3");
        when(host.getStorageIpAddress()).thenReturn(null);
        when(host.getPrivateIpAddress()).thenReturn("192.168.0.5");
        givenSuccessfulAnswer(100 * GB, 40 * GB);

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(strategy);

            assertTrue(listener.hostConnect(HOST_ID, POOL_ID));
        }

        ArgumentCaptor<AccessGroup> group = ArgumentCaptor.forClass(AccessGroup.class);
        verify(strategy).updateAccessGroup(group.capture());
        assertEquals(POOL_ID, group.getValue().getStoragePoolId());
        assertEquals(List.of(host), group.getValue().getHostsToConnect());
        assertEquals(AccessGroup.HostRuleAction.ADD, group.getValue().getHostRuleAction());
        assertEquals("192.168.0.5", host.getPrivateIpAddress());
    }

    @Test
    public void hostConnect_returnsFalse_whenExportPolicyUpdateFails() {
        givenReadyToConnect("NFS3");
        givenSuccessfulAnswer(100 * GB, 40 * GB);
        when(strategy.updateAccessGroup(any(AccessGroup.class))).thenThrow(new RuntimeException("ONTAP unreachable"));

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(strategy);

            assertFalse(listener.hostConnect(HOST_ID, POOL_ID));
        }

        ArgumentCaptor<AccessGroup> group = ArgumentCaptor.forClass(AccessGroup.class);
        verify(strategy).updateAccessGroup(group.capture());
        assertEquals(POOL_ID, group.getValue().getStoragePoolId());
        assertEquals(List.of(host), group.getValue().getHostsToConnect());
        assertEquals(AccessGroup.HostRuleAction.ADD, group.getValue().getHostRuleAction());
        verifyNoInteractions(agentMgr);
    }

    @Test
    public void isNfs3EnabledOnHost_returnsFalse_whenHostIsNull() throws Exception {
        Method method = OntapHostListener.class.getDeclaredMethod("isNfs3EnabledOnHost", com.cloud.host.Host.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(listener, (Object) null));
    }

    // ----------------------------------------------------- trivial callbacks

    @Test
    public void hostDisconnected_returnsFalse() {
        assertFalse(listener.hostDisconnected(HOST_ID, POOL_ID));
    }

    @Test
    public void hostRemoved_returnsFalse() {
        assertFalse(listener.hostRemoved(HOST_ID, 1L));
    }

    @Test
    public void hostEnabled_returnsFalse() {
        assertFalse(listener.hostEnabled(HOST_ID));
    }

    @Test
    public void hostAdded_returnsFalse() {
        assertFalse(listener.hostAdded(HOST_ID));
    }

    // ----------------------------------------------- hostAboutToBeRemoved

    private StoragePoolHostVO poolRef(long poolId) {
        StoragePoolHostVO ref = mock(StoragePoolHostVO.class);
        when(ref.getPoolId()).thenReturn(poolId);
        return ref;
    }

    private void givenHostWithPoolRefs(StoragePoolHostVO... refs) {
        givenKvmHost();
        when(storagePoolHostDao.listByHostId(HOST_ID)).thenReturn(List.of(refs));
    }

    @Test
    public void hostAboutToBeRemoved_returnsTrue_whenHostNotFound() {
        when(hostDao.findById(HOST_ID)).thenReturn(null);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

        verify(storagePoolHostDao, never()).listByHostId(anyLong());
    }

    @Test
    public void hostAboutToBeRemoved_returnsTrue_whenPoolReferencesAreNull() {
        givenKvmHost();
        when(storagePoolHostDao.listByHostId(HOST_ID)).thenReturn(null);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

        verifyNoInteractions(storagePoolDao);
    }

    @Test
    public void hostAboutToBeRemoved_returnsTrue_whenNoPoolReferences() {
        givenKvmHost();
        when(storagePoolHostDao.listByHostId(HOST_ID)).thenReturn(Collections.emptyList());

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

        verifyNoInteractions(storagePoolDao);
    }

    @Test
    public void hostAboutToBeRemoved_skipsReference_whenPoolNoLongerExists() {
        givenHostWithPoolRefs(poolRef(POOL_ID));
        when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

        verifyNoInteractions(storagePoolDetailsDao);
    }

    @Test
    public void hostAboutToBeRemoved_removesHostFromExportPolicy_whenPoolIsNfs3() {
        givenHostWithPoolRefs(poolRef(POOL_ID));
        givenPool();
        givenDetails("NFS3");

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(strategy);

            assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
        }

        ArgumentCaptor<AccessGroup> group = ArgumentCaptor.forClass(AccessGroup.class);
        verify(strategy).updateAccessGroup(group.capture());
        assertEquals(POOL_ID, group.getValue().getStoragePoolId());
        assertSame(host, group.getValue().getHostsToConnect().get(0));
        assertEquals(AccessGroup.HostRuleAction.REMOVE, group.getValue().getHostRuleAction());
    }

    @Test
    public void hostAboutToBeRemoved_skipsPool_whenPoolIsIscsi() {
        givenHostWithPoolRefs(poolRef(POOL_ID));
        givenPool();
        givenDetails("ISCSI");

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

            utils.verifyNoInteractions();
        }
        verifyNoInteractions(strategy);
    }

    @Test
    public void hostAboutToBeRemoved_skipsPool_whenPoolDetailsAreNull() {
        givenHostWithPoolRefs(poolRef(POOL_ID));
        givenPool();
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(null);

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

        verifyNoInteractions(strategy);
    }

    @Test
    public void hostAboutToBeRemoved_skipsPool_whenPoolDetailsAreEmpty() {
        givenHostWithPoolRefs(poolRef(POOL_ID));
        givenPool();
        when(storagePoolDetailsDao.listDetailsKeyPairs(POOL_ID)).thenReturn(Collections.emptyMap());

        assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

        verifyNoInteractions(strategy);
    }

    @Test
    public void hostAboutToBeRemoved_skipsPool_whenHostIsNotNfsEnabled() {
        givenHostWithPoolRefs(poolRef(POOL_ID));
        givenPool();
        givenDetails("NFS3");
        when(host.getStorageIpAddress()).thenReturn(null);
        when(host.getPrivateIpAddress()).thenReturn("");

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            assertTrue(listener.hostAboutToBeRemoved(HOST_ID));

            utils.verifyNoInteractions();
        }
        verifyNoInteractions(strategy);
    }

    @Test
    public void hostAboutToBeRemoved_returnsTrueAndContinues_whenOnePoolFails() {
        long secondPoolId = POOL_ID + 1;
        StoragePoolVO secondPool = mock(StoragePoolVO.class);
        when(secondPool.getId()).thenReturn(secondPoolId);
        when(secondPool.getName()).thenReturn("ontap-pool-2");
        givenHostWithPoolRefs(poolRef(POOL_ID), poolRef(secondPoolId));
        givenPool();
        when(storagePoolDao.findById(secondPoolId)).thenReturn(secondPool);
        givenDetails("NFS3");
        when(storagePoolDetailsDao.listDetailsKeyPairs(secondPoolId)).thenReturn(details("NFS3"));
        StorageStrategy failing = mock(StorageStrategy.class);
        when(failing.updateAccessGroup(any(AccessGroup.class))).thenThrow(new RuntimeException("boom"));

        try (MockedStatic<OntapStorageUtils> utils = mockStatic(OntapStorageUtils.class)) {
            utils.when(() -> OntapStorageUtils.getStrategyByStoragePoolDetails(anyMap())).thenReturn(failing, strategy);

            assertTrue(listener.hostAboutToBeRemoved(HOST_ID));
        }

        verify(failing).updateAccessGroup(any(AccessGroup.class));
        verify(strategy, times(1)).updateAccessGroup(any(AccessGroup.class));
    }
}
