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
package org.apache.cloudstack.storage.motion;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.driver.OntapPrimaryDatastoreDriver;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.MigrateCommand.MigrateDiskInfo;
import com.cloud.agent.api.ModifyTargetsAnswer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OntapDataMotionStrategyTest {

    private static final long SRC_HOST_ID = 100L;
    private static final int LIVE_MIGRATION_CPU_SHARES = 256;
    private static final long DEST_HOST_ID = 200L;

    @InjectMocks
    private OntapDataMotionStrategy strategy;

    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VolumeDetailsDao volumeDetailsDao;
    @Mock
    private VolumeDataFactory volumeDataFactory;
    @Mock
    private VolumeService volumeService;
    @Mock
    private DataStoreManager dataStoreManager;
    @Mock
    private AgentManager agentManager;
    @Mock
    private HostDao hostDao;
    @Mock
    private ResourceManager resourceManager;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private GuestOSDao guestOsDao;
    @Mock
    private GuestOSCategoryDao guestOsCategoryDao;

    @Test
    public void canHandleMigratingVolumeOnOntapPrimary() {
        VolumeInfo srcVolume = Mockito.mock(VolumeInfo.class);
        VolumeInfo destVolume = Mockito.mock(VolumeInfo.class);
        PrimaryDataStore srcStore = Mockito.mock(PrimaryDataStore.class);
        PrimaryDataStore destStore = Mockito.mock(PrimaryDataStore.class);
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        Mockito.doReturn(srcStore).when(srcVolume).getDataStore();
        Mockito.doReturn(destStore).when(destVolume).getDataStore();
        Mockito.doReturn(DataStoreRole.Primary).when(srcStore).getRole();
        Mockito.doReturn(DataStoreRole.Primary).when(destStore).getRole();
        Mockito.doReturn(1L).when(srcStore).getId();
        Mockito.doReturn(2L).when(destStore).getId();
        Mockito.doReturn(Volume.State.Migrating).when(srcVolume).getState();
        Mockito.doReturn(HypervisorType.KVM).when(srcVolume).getHypervisorType();
        Mockito.doReturn(DataStoreProvider.ONTAP_PLUGIN_NAME).when(srcPool).getStorageProviderName();
        Mockito.doReturn(srcPool).when(primaryDataStoreDao).findById(1L);

        assertEquals(StrategyPriority.HIGHEST, strategy.canHandle(srcVolume, destVolume));

        Mockito.doReturn(Volume.State.Ready).when(srcVolume).getState();
        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(srcVolume, destVolume));
    }

    @Test
    public void canHandleLiveMigrationWithOntapPoolOnKvm() {
        VolumeInfo srcVolume = Mockito.mock(VolumeInfo.class);
        DataStore destStore = Mockito.mock(DataStore.class);
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        Host srcHost = Mockito.mock(Host.class);
        Mockito.doReturn(1L).when(srcVolume).getPoolId();
        Mockito.doReturn(DataStoreProvider.ONTAP_PLUGIN_NAME).when(srcPool).getStorageProviderName();
        Mockito.doReturn(srcPool).when(primaryDataStoreDao).findById(1L);
        Mockito.doReturn(HypervisorType.KVM).when(srcHost).getHypervisorType();

        Map<VolumeInfo, DataStore> volumeMap = Map.of(srcVolume, destStore);
        assertEquals(StrategyPriority.HIGHEST, strategy.canHandle(volumeMap, srcHost, Mockito.mock(Host.class)));

        Mockito.doReturn(HypervisorType.VMware).when(srcHost).getHypervisorType();
        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(volumeMap, srcHost, Mockito.mock(Host.class)));
    }

    @Test
    public void offlineMigrationBetweenSupportedOntapPoolsUsesCopyCommand() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        VolumeObjectTO copiedVolume = new VolumeObjectTO();
        copiedVolume.setPath("copied-volume-path");
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(copiedVolume));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class));
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class));
        Mockito.verify(context.destVolumeVO).setPath("copied-volume-path");
    }

    @Test
    public void offlineMigrationMatchesSvmByUuidBeforeName() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm2", null);
        Mockito.doReturn(Map.of("storageIP", "storage-ip", "svmUUID", "svm-uuid", "svmName", "svm1", "protocol", "NFS3"))
                .when(primaryDataStoreDao).getDetails(1L);
        Mockito.doReturn(Map.of("storageIP", "storage-ip", "svmUUID", "svm-uuid", "svmName", "svm2", "protocol", "NFS3"))
                .when(primaryDataStoreDao).getDetails(2L);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class));
    }

    @Test
    public void offlineMigrationFromNonOntapPoolUsesMigrateVolumeCommand() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(false, "NFS3", "svm1", null);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class)))
                .thenReturn(new MigrateVolumeAnswer(null, true, null, "migrated-volume-path"));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class));
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class));
        Mockito.verify(context.destVolumeVO).setPath("migrated-volume-path");
    }

    @Test
    // Fallback only. Same-SVM iSCSI is intercepted by OntapPrimaryDatastoreDriver.canCopy before this strategy runs.
    public void offlineMigrationBetweenSupportedOntapIscsiPoolsUsesMigrateVolumeCommand() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "ISCSI", "ISCSI", "svm1", null,
                StoragePoolType.OntapiSCSI, StoragePoolType.OntapiSCSI);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class)))
                .thenReturn(new MigrateVolumeAnswer(null, true, null, "migrated-volume-path"));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class));
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class));
    }

    @Test
    public void offlineIscsiMigrationReadsSourceIqnAfterGrantAccess() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "ISCSI", "ISCSI", "svm1", null,
                StoragePoolType.OntapiSCSI, StoragePoolType.OntapiSCSI);
        VolumeVO srcVolumeVO = volumeDao.findById(10L);
        AtomicBoolean srcGranted = new AtomicBoolean(false);
        Mockito.doAnswer(invocation -> srcGranted.get() ? "/iqn/2" : "/iqn/0").when(srcVolumeVO).get_iScsiName();
        Mockito.doAnswer(invocation -> {
            srcGranted.set(true);
            return true;
        }).when(volumeService).grantAccess(Mockito.eq(context.srcVolume), Mockito.any(), Mockito.any());
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class)))
                .thenReturn(new MigrateVolumeAnswer(null, true, null, "migrated-volume-path"));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        ArgumentCaptor<MigrateVolumeCommand> commandCaptor = ArgumentCaptor.forClass(MigrateVolumeCommand.class);
        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), commandCaptor.capture());
        assertEquals("/iqn/2", commandCaptor.getValue().getSrcDetails().get(DiskTO.IQN));
    }

    @Test
    public void offlineMigrationTagsDestinationBeforeGrantAndClearsItAfter() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        VolumeObjectTO copiedVolume = new VolumeObjectTO();
        copiedVolume.setPath("copied-volume-path");
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(copiedVolume));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        InOrder inOrder = Mockito.inOrder(volumeDetailsDao, volumeService, context.callback);
        inOrder.verify(volumeDetailsDao).addDetail(20L, OntapStorageConstants.MIGRATION_DESTINATION,
                OntapStorageConstants.TRUE, false);
        inOrder.verify(volumeService).grantAccess(Mockito.eq(context.destVolume), Mockito.any(Host.class), Mockito.any());
        // Revoked once after the copy command and once more by the outer finally.
        inOrder.verify(volumeService, Mockito.times(2)).revokeAccess(Mockito.eq(context.destVolume), Mockito.any(Host.class), Mockito.any());
        inOrder.verify(volumeDetailsDao).removeDetail(20L, OntapStorageConstants.MIGRATION_DESTINATION);
        inOrder.verify(context.callback).complete(Mockito.any());
    }

    @Test
    public void offlineMigrationClearsDestinationTagWhenMigrationFails() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Answer failedAnswer = Mockito.mock(Answer.class);
        Mockito.when(failedAnswer.getResult()).thenReturn(false);
        Mockito.when(failedAnswer.getDetails()).thenReturn("copy failed");
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(failedAnswer);

        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));

        Mockito.verify(volumeDetailsDao).addDetail(20L, OntapStorageConstants.MIGRATION_DESTINATION,
                OntapStorageConstants.TRUE, false);
        Mockito.verify(volumeDetailsDao).removeDetail(20L, OntapStorageConstants.MIGRATION_DESTINATION);
        Mockito.verify(context.callback).complete(Mockito.argThat(CopyCommandResult::isFailed));
    }

    @Test
    public void liveMigrationDoesNotTagDestination() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        Mockito.verify(volumeDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(),
                Mockito.eq(OntapStorageConstants.MIGRATION_DESTINATION), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    public void offlineMigrationBetweenOntapPoolsWithDifferentProtocolIsRejected() {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "ISCSI", "svm1", null);
        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));
        Mockito.verify(context.callback).complete(Mockito.argThat(result ->
                result != null && result.isFailed()
                        && result.getResult() != null
                        && result.getResult().contains("Cross-protocol")));
        Mockito.verify(context.destVolume.getDataStore().getDriver(), Mockito.never())
                .createAsync(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void offlineMigrationFromOntapIscsiToNfsIsRejected() {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "ISCSI", "NFS3", "svm1", null,
                StoragePoolType.OntapiSCSI, StoragePoolType.NetworkFilesystem);
        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));
        Mockito.verify(context.destVolume.getDataStore().getDriver(), Mockito.never())
                .createAsync(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void offlineMigrationBetweenOntapPoolsWithDifferentSvmUsesMigrateVolumeCommand() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm2", null);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class)))
                .thenReturn(new MigrateVolumeAnswer(null, true, null, "migrated-volume-path"));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class));
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class));
    }

    @Test
    public void offlineMigrationAttachedToStoppedVmUsesCopyCommand() throws Exception {
        VirtualMachine vm = Mockito.mock(VirtualMachine.class);
        Mockito.doReturn(VirtualMachine.State.Stopped).when(vm).getState();
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", vm);
        VolumeObjectTO copiedVolume = new VolumeObjectTO();
        copiedVolume.setPath("copied-volume-path");
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(copiedVolume));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class));
    }

    private VolumeVO captureDuplicatedVolume() {
        ArgumentCaptor<VolumeVO> captor = ArgumentCaptor.forClass(VolumeVO.class);
        Mockito.verify(volumeDao).persist(captor.capture());
        return captor.getValue();
    }

    @Test
    public void liveMigrationToOntapNfsUsesFileDiskAndUuidPath() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        MigrateCommand migrateCommand = captureMigrateCommand();
        assertEquals(LIVE_MIGRATION_CPU_SHARES, migrateCommand.getNewVmCpuShares());
        assertTrue(migrateCommand.getMigrateDiskInfoList().isEmpty());
        MigrateDiskInfo diskInfo = migrateCommand.getMigrateStorage().get("src-path");
        assertEquals(MigrateDiskInfo.DiskType.FILE, diskInfo.getDiskType());
        assertEquals(MigrateDiskInfo.DriverType.QCOW2, diskInfo.getDriverType());
        assertEquals("dest-connected", diskInfo.getSourceText());
        Mockito.verify(context.destVolumeVO).setPath("dest-uuid");
        Mockito.verify(context.destVolumeVO).setFormat(Storage.ImageFormat.QCOW2);
        assertEquals(Storage.ImageFormat.QCOW2, captureDuplicatedVolume().getFormat());
        assertSame(context.destVolumeTO, context.vmDisk.getData());
        Mockito.verify(volumeService).copyPoliciesBetweenVolumesAndDestroySourceVolumeAfterMigration(Mockito.any(), Mockito.isNull(),
                Mockito.eq(context.srcVolume), Mockito.eq(context.destVolume), Mockito.eq(false));
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void liveMigrationToOntapIscsiUsesBlockDiskAndLunPath() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("ISCSI", StoragePoolType.OntapiSCSI);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        MigrateCommand migrateCommand = captureMigrateCommand();
        assertEquals(1, migrateCommand.getMigrateDiskInfoList().size());
        MigrateDiskInfo diskInfo = migrateCommand.getMigrateDiskInfoList().get(0);
        assertEquals(MigrateDiskInfo.DiskType.BLOCK, diskInfo.getDiskType());
        assertEquals(MigrateDiskInfo.DriverType.RAW, diskInfo.getDriverType());
        assertEquals("src-connected", diskInfo.getSerialNumber());
        assertEquals("dest-connected", diskInfo.getSourceText());
        assertSame(diskInfo, migrateCommand.getMigrateStorage().get("src-path"));
        assertSame(diskInfo, migrateCommand.getMigrateStorage().get("src-connected"));
        Mockito.verify(context.destVolumeVO).setPath("/iqn/dest/1");
        Mockito.verify(context.destVolumeVO).setFormat(Storage.ImageFormat.RAW);
        assertEquals(Storage.ImageFormat.RAW, captureDuplicatedVolume().getFormat());
        Mockito.verify(volumeService).grantAccess(context.srcVolume, context.srcHost, context.srcVolume.getDataStore());
        ArgumentCaptor<ModifyTargetsCommand> targetsCaptor = ArgumentCaptor.forClass(ModifyTargetsCommand.class);
        Mockito.verify(agentManager, Mockito.atLeastOnce()).easySend(Mockito.eq(SRC_HOST_ID), targetsCaptor.capture());
        assertTrue(targetsCaptor.getAllValues().stream().anyMatch(command -> !command.getAdd()
                && command.getTargets().stream().anyMatch(target -> "/iqn/dest/1".equals(target.get(ModifyTargetsCommand.IQN)))));
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void liveMigrationFromManagedPoolToDifferentSvmIsRejected() {
        LiveMigrationTestContext context = configureLiveMigration("ISCSI", StoragePoolType.OntapiSCSI);
        Mockito.doReturn(Map.of("storageIP", "storage-ip", "svmName", "svm2", "protocol", "ISCSI")).when(primaryDataStoreDao).getDetails(2L);

        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));
        Mockito.verify(volumeDao, Mockito.never()).persist(Mockito.any(VolumeVO.class));
        Mockito.verify(context.callback).complete(Mockito.argThat(CopyCommandResult::isFailed));
    }

    @Test
    public void liveMigrationExpungesDestinationWhenGrantAccessFails() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doThrow(new CloudRuntimeException("grant failed")).when(volumeService)
                .grantAccess(Mockito.eq(context.destVolume), Mockito.eq(context.destHost), Mockito.any());

        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        Mockito.verify(volumeService).expungeVolumeAsync(context.destVolume);
        Mockito.verify(context.callback).complete(Mockito.argThat(CopyCommandResult::isFailed));
    }

    @Test
    public void liveMigrationUnexpectedExceptionReportsFailureAndRollsBack() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        IllegalStateException unexpected = new IllegalStateException("unexpected state");
        Mockito.doThrow(unexpected).when(volumeService)
                .grantAccess(Mockito.eq(context.destVolume), Mockito.eq(context.destHost), Mockito.any());

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        assertSame(unexpected, thrown.getCause());
        assertTrue(thrown.getMessage().contains("Unexpected error while migrating"));
        assertTrue(thrown.getMessage().contains("unexpected state"));
        Mockito.verify(volumeService).expungeVolumeAsync(context.destVolume);
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result.isFailed() && result.getResult() != null
                && result.getResult().contains("unexpected state")));
    }

    @Test
    public void liveMigrationUnexpectedExceptionWithoutMessageStillReportsFailure() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doThrow(new NullPointerException()).when(volumeService)
                .grantAccess(Mockito.eq(context.destVolume), Mockito.eq(context.destHost), Mockito.any());

        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result.isFailed()
                && result.getResult().contains("NullPointerException")));
    }

    @Test
    public void liveMigrationTimeoutKeepsDestinationWhenVmIsRunningThere() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        VMInstanceVO vm = vmDao.findById(5L);
        Mockito.doReturn("i-5-VM").when(vm).getInstanceName();
        Mockito.doThrow(new OperationTimedoutException(null, SRC_HOST_ID, 0, 0, false)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));
        CheckVirtualMachineCommand checkCommand = new CheckVirtualMachineCommand("i-5-VM");
        Mockito.doReturn(new CheckVirtualMachineAnswer(checkCommand, VirtualMachine.PowerState.PowerOn, null)).when(agentManager)
                .send(Mockito.eq(DEST_HOST_ID), Mockito.any(CheckVirtualMachineCommand.class));

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        Mockito.verify(volumeService, Mockito.never()).expungeVolumeAsync(Mockito.any());
        Mockito.verify(volumeService).copyPoliciesBetweenVolumesAndDestroySourceVolumeAfterMigration(Mockito.any(), Mockito.isNull(),
                Mockito.eq(context.srcVolume), Mockito.eq(context.destVolume), Mockito.eq(false));
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void liveMigrationContinuesWhenQosReapplyFails() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        OntapPrimaryDatastoreDriver ontapDriver = Mockito.mock(OntapPrimaryDatastoreDriver.class);
        DataStore destStore = context.destVolume.getDataStore();
        Mockito.doReturn(ontapDriver).when(destStore).getDriver();
        Mockito.doThrow(new CloudRuntimeException("qos patch failed")).when(ontapDriver)
                .applyQosPolicyToVolume(context.destVolume);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        Mockito.verify(ontapDriver, Mockito.times(3)).applyQosPolicyToVolume(context.destVolume);
        Mockito.verify(volumeService, Mockito.never()).expungeVolumeAsync(Mockito.any());
        Mockito.verify(volumeService).copyPoliciesBetweenVolumesAndDestroySourceVolumeAfterMigration(Mockito.any(), Mockito.isNull(),
                Mockito.eq(context.srcVolume), Mockito.eq(context.destVolume), Mockito.eq(false));
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void offlineMigrationUsesSourceClusterHost() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Scope srcScope = context.srcVolume.getDataStore().getScope();
        Mockito.doReturn(ScopeType.CLUSTER).when(srcScope).getScopeType();
        DataStore destStore = context.destVolume.getDataStore();
        Scope destScope = Mockito.mock(Scope.class);
        Mockito.doReturn(destScope).when(destStore).getScope();
        Mockito.doReturn(ScopeType.CLUSTER).when(destScope).getScopeType();

        StoragePoolVO srcPool = primaryDataStoreDao.findById(1L);
        StoragePoolVO destPool = primaryDataStoreDao.findById(2L);
        Mockito.doReturn(11L).when(srcPool).getClusterId();
        Mockito.doReturn(22L).when(destPool).getClusterId();
        PrimaryDataStore srcStoreInfo = Mockito.mock(PrimaryDataStore.class);
        Mockito.doReturn(srcStoreInfo).when(dataStoreManager).getPrimaryDataStore(1L);

        HostVO sourceHost = Mockito.mock(HostVO.class);
        Mockito.doReturn(8L).when(sourceHost).getId();
        Mockito.doReturn(ResourceState.Enabled).when(sourceHost).getResourceState();
        Mockito.doReturn(List.of(sourceHost)).when(resourceManager)
                .getEligibleUpAndEnabledHostsInClusterForStorageConnection(srcStoreInfo);
        VolumeObjectTO copiedVolume = new VolumeObjectTO();
        copiedVolume.setPath("copied-volume-path");
        Mockito.when(agentManager.send(Mockito.eq(8L), Mockito.any(CopyCommand.class))).thenReturn(new CopyCmdAnswer(copiedVolume));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(8L), Mockito.any(CopyCommand.class));
        Mockito.verify(dataStoreManager, Mockito.never()).getPrimaryDataStore(2L);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // canHandle(DataObject, DataObject) – rejection paths
    // ══════════════════════════════════════════════════════════════════════════

    private VolumeInfo volumeOnStore(long storeId, DataStoreRole role, Volume.State state, HypervisorType hypervisor) {
        VolumeInfo volume = Mockito.mock(VolumeInfo.class);
        PrimaryDataStore store = Mockito.mock(PrimaryDataStore.class);
        Mockito.doReturn(store).when(volume).getDataStore();
        Mockito.doReturn(role).when(store).getRole();
        Mockito.doReturn(storeId).when(store).getId();
        Mockito.doReturn(state).when(volume).getState();
        Mockito.doReturn(hypervisor).when(volume).getHypervisorType();
        return volume;
    }

    private void poolOnStore(long storeId, String provider) {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.doReturn(provider).when(pool).getStorageProviderName();
        Mockito.doReturn(pool).when(primaryDataStoreDao).findById(storeId);
    }

    @Test
    public void canHandleRejectsObjectsThatAreNotVolumes() {
        org.apache.cloudstack.engine.subsystem.api.storage.DataObject snapshot =
                Mockito.mock(org.apache.cloudstack.engine.subsystem.api.storage.DataObject.class);
        VolumeInfo volume = Mockito.mock(VolumeInfo.class);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(snapshot, volume));
        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(volume, snapshot));
    }

    @Test
    public void canHandleAcceptsMigrationWhenOnlyDestinationIsOntap() {
        VolumeInfo src = volumeOnStore(1L, DataStoreRole.Primary, Volume.State.Migrating, HypervisorType.KVM);
        VolumeInfo dest = volumeOnStore(2L, DataStoreRole.Primary, Volume.State.Allocated, HypervisorType.KVM);
        poolOnStore(1L, DataStoreProvider.DEFAULT_PRIMARY);
        poolOnStore(2L, DataStoreProvider.ONTAP_PLUGIN_NAME);

        assertEquals(StrategyPriority.HIGHEST, strategy.canHandle(src, dest));
    }

    @Test
    public void canHandleRejectsMigrationBetweenNonOntapPools() {
        VolumeInfo src = volumeOnStore(1L, DataStoreRole.Primary, Volume.State.Migrating, HypervisorType.KVM);
        VolumeInfo dest = volumeOnStore(2L, DataStoreRole.Primary, Volume.State.Allocated, HypervisorType.KVM);
        poolOnStore(1L, DataStoreProvider.DEFAULT_PRIMARY);
        poolOnStore(2L, DataStoreProvider.DEFAULT_PRIMARY);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(src, dest));
    }

    @Test
    public void canHandleRejectsMigrationWhenPoolsAreUnknown() {
        VolumeInfo src = volumeOnStore(1L, DataStoreRole.Primary, Volume.State.Migrating, HypervisorType.KVM);
        VolumeInfo dest = volumeOnStore(2L, DataStoreRole.Primary, Volume.State.Allocated, HypervisorType.KVM);
        Mockito.doReturn(null).when(primaryDataStoreDao).findById(1L);
        Mockito.doReturn(null).when(primaryDataStoreDao).findById(2L);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(src, dest));
    }

    @Test
    public void canHandleRejectsNonPrimaryRolesAndNonKvmHypervisors() {
        poolOnStore(1L, DataStoreProvider.ONTAP_PLUGIN_NAME);
        poolOnStore(2L, DataStoreProvider.ONTAP_PLUGIN_NAME);

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(
                volumeOnStore(1L, DataStoreRole.Image, Volume.State.Migrating, HypervisorType.KVM),
                volumeOnStore(2L, DataStoreRole.Primary, Volume.State.Allocated, HypervisorType.KVM)));
        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(
                volumeOnStore(1L, DataStoreRole.Primary, Volume.State.Migrating, HypervisorType.KVM),
                volumeOnStore(2L, DataStoreRole.Image, Volume.State.Allocated, HypervisorType.KVM)));
        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(
                volumeOnStore(1L, DataStoreRole.Primary, Volume.State.Migrating, HypervisorType.VMware),
                volumeOnStore(2L, DataStoreRole.Primary, Volume.State.Allocated, HypervisorType.VMware)));
    }

    @Test
    public void canHandleLiveMigrationAcceptsWhenOnlyDestinationPoolIsOntap() {
        VolumeInfo srcVolume = Mockito.mock(VolumeInfo.class);
        Mockito.doReturn(1L).when(srcVolume).getPoolId();
        DataStore destStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(2L).when(destStore).getId();
        poolOnStore(1L, DataStoreProvider.DEFAULT_PRIMARY);
        poolOnStore(2L, DataStoreProvider.ONTAP_PLUGIN_NAME);
        Host srcHost = Mockito.mock(Host.class);
        Mockito.doReturn(HypervisorType.KVM).when(srcHost).getHypervisorType();

        assertEquals(StrategyPriority.HIGHEST, strategy.canHandle(Map.of(srcVolume, destStore), srcHost, Mockito.mock(Host.class)));
    }

    @Test
    public void canHandleLiveMigrationRejectsWhenNoPoolIsOntap() {
        VolumeInfo srcVolume = Mockito.mock(VolumeInfo.class);
        Mockito.doReturn(1L).when(srcVolume).getPoolId();
        DataStore destStore = Mockito.mock(DataStore.class);
        Mockito.doReturn(2L).when(destStore).getId();
        poolOnStore(1L, DataStoreProvider.DEFAULT_PRIMARY);
        Mockito.doReturn(null).when(primaryDataStoreDao).findById(2L);
        Host srcHost = Mockito.mock(Host.class);
        Mockito.doReturn(HypervisorType.KVM).when(srcHost).getHypervisorType();

        assertEquals(StrategyPriority.CANT_HANDLE, strategy.canHandle(Map.of(srcVolume, destStore), srcHost, Mockito.mock(Host.class)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Offline (single volume) migration – host selection and failure handling
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    public void offlineMigrationOfVolumeAttachedToRunningVmIsRejected() {
        VirtualMachine vm = Mockito.mock(VirtualMachine.class);
        Mockito.doReturn(VirtualMachine.State.Running).when(vm).getState();
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", vm);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));

        assertTrue(thrown.getMessage().contains("can only be migrated offline"), thrown.getMessage());
        Mockito.verify(context.destVolume.getDataStore().getDriver(), Mockito.never())
                .createAsync(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(context.callback).complete(Mockito.argThat(CopyCommandResult::isFailed));
    }

    @Test
    public void offlineMigrationOfVolumeAttachedToMigratingVmIsAccepted() throws Exception {
        VirtualMachine vm = Mockito.mock(VirtualMachine.class);
        Mockito.doReturn(VirtualMachine.State.Migrating).when(vm).getState();
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", vm);
        VolumeObjectTO copied = new VolumeObjectTO();
        copied.setPath("copied");
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(copied));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(context.destVolumeVO).setPath("copied");
    }

    @Test
    public void offlineMigrationUsesIscsiNameAsDestinationPathWhenPresent() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Mockito.doReturn("iqn.dest").when(context.destVolumeVO).get_iScsiName();
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(context.destVolumeVO).setPath("iqn.dest");
        Mockito.verify(volumeDao, Mockito.atLeastOnce()).update(Mockito.anyLong(), Mockito.eq(context.destVolumeVO));
    }

    @Test
    public void offlineMigrationDefaultsDestinationFormatToQcow2WhenMissing() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Mockito.doReturn(null).when(context.destVolumeVO).getFormat();
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(context.destVolumeVO).setFormat(Storage.ImageFormat.QCOW2);
    }

    @Test
    public void offlineMigrationSucceedsEvenWhenCleanupStepsFail() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));
        Mockito.doThrow(new CloudRuntimeException("revoke failed")).when(volumeService)
                .revokeAccess(Mockito.any(VolumeInfo.class), Mockito.any(Host.class), Mockito.any());
        Mockito.doThrow(new CloudRuntimeException("detail cleanup failed")).when(volumeDetailsDao)
                .removeDetail(Mockito.anyLong(), Mockito.eq(OntapStorageConstants.MIGRATION_DESTINATION));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void offlineMigrationUsesDestinationHostScopeWhenSourceIsNotHostScoped() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Scope srcScope = context.srcVolume.getDataStore().getScope();
        Mockito.doReturn(ScopeType.CLUSTER).when(srcScope).getScopeType();
        Scope destScope = Mockito.mock(Scope.class);
        Mockito.doReturn(ScopeType.HOST).when(destScope).getScopeType();
        Mockito.doReturn(3L).when(destScope).getScopeId();
        DataStore destStore = context.destVolume.getDataStore();
        Mockito.doReturn(destScope).when(destStore).getScope();
        Mockito.when(agentManager.send(Mockito.eq(3L), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(hostDao, Mockito.atLeastOnce()).findById(3L);
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    private OfflineMigrationTestContext configureZoneScopedOfflineMigration() {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Scope srcScope = context.srcVolume.getDataStore().getScope();
        Mockito.doReturn(ScopeType.ZONE).when(srcScope).getScopeType();
        Scope destScope = Mockito.mock(Scope.class);
        Mockito.doReturn(ScopeType.ZONE).when(destScope).getScopeType();
        DataStore destStore = context.destVolume.getDataStore();
        Mockito.doReturn(destScope).when(destStore).getScope();
        // a Long-returning mock method answers 0, so the cluster must be nulled explicitly
        StoragePoolVO srcPool = primaryDataStoreDao.findById(1L);
        Mockito.doReturn(null).when(srcPool).getClusterId();
        return context;
    }

    @Test
    public void offlineMigrationFromPoolWithoutClusterUsesEnabledHostInDestinationZone() throws Exception {
        OfflineMigrationTestContext context = configureZoneScopedOfflineMigration();
        HostVO disabled = Mockito.mock(HostVO.class);
        Mockito.doReturn(ResourceState.Disabled).when(disabled).getResourceState();
        HostVO enabled = Mockito.mock(HostVO.class);
        Mockito.doReturn(9L).when(enabled).getId();
        Mockito.doReturn(ResourceState.Enabled).when(enabled).getResourceState();
        Mockito.doReturn(new java.util.ArrayList<>(List.of(disabled, enabled))).when(resourceManager)
                .getEligibleUpAndEnabledHostsInZoneForStorageConnection(Mockito.any(), Mockito.anyLong(), Mockito.eq(HypervisorType.KVM));
        Mockito.when(agentManager.send(Mockito.eq(9L), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(agentManager).send(Mockito.eq(9L), Mockito.any(CopyCommand.class));
    }

    @Test
    public void offlineMigrationFailsWhenNoEnabledHostCanBeFound() {
        OfflineMigrationTestContext context = configureZoneScopedOfflineMigration();
        HostVO disabled = Mockito.mock(HostVO.class);
        Mockito.doReturn(ResourceState.Disabled).when(disabled).getResourceState();
        Mockito.doReturn(new java.util.ArrayList<>(List.of(disabled))).when(resourceManager)
                .getEligibleUpAndEnabledHostsInZoneForStorageConnection(Mockito.any(), Mockito.anyLong(), Mockito.eq(HypervisorType.KVM));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));
        assertTrue(thrown.getMessage().contains("Unable to locate a host"), thrown.getMessage());

        Mockito.doReturn(null).when(resourceManager)
                .getEligibleUpAndEnabledHostsInZoneForStorageConnection(Mockito.any(), Mockito.anyLong(), Mockito.eq(HypervisorType.KVM));
        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));
    }

    @Test
    public void offlineMigrationReportsDefaultMessageWhenAgentGivesNoAnswer() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class))).thenReturn(null);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));

        assertEquals("Unable to copy the volume between storage pools", thrown.getMessage());
    }

    @Test
    public void offlineMigrationReportsDefaultMessageWhenFailedAnswerHasNoDetails() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(false, "NFS3", "svm1", null);
        Answer failed = Mockito.mock(Answer.class);
        Mockito.when(failed.getResult()).thenReturn(false);
        Mockito.when(failed.getDetails()).thenReturn("");
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class))).thenReturn(failed);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));

        assertEquals("Unable to migrate the volume between storage pools", thrown.getMessage());
    }

    @Test
    public void offlineMigrationWrapsUnexpectedAgentErrors() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        long hostId = context.host.getId();
        com.cloud.exception.AgentUnavailableException unavailable =
                new com.cloud.exception.AgentUnavailableException("agent gone", hostId);
        Mockito.when(agentManager.send(Mockito.eq(hostId), Mockito.any(CopyCommand.class))).thenThrow(unavailable);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));

        assertTrue(thrown.getMessage().contains("Unexpected error during volume migration"), thrown.getMessage());
        assertTrue(thrown.getCause() instanceof com.cloud.exception.AgentUnavailableException);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Offline migration – decision between CopyCommand and MigrateVolumeCommand
    // ══════════════════════════════════════════════════════════════════════════

    /** Runs an offline NFS migration between two ONTAP pools and reports whether a CopyCommand was used. */
    private boolean offlineMigrationUsesCopyCommand(Map<String, String> srcDetails, Map<String, String> destDetails,
            StoragePoolType destPoolType) throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "NFS3", "svm1", null,
                StoragePoolType.NetworkFilesystem, destPoolType);
        Mockito.doReturn(srcDetails).when(primaryDataStoreDao).getDetails(1L);
        Mockito.doReturn(destDetails).when(primaryDataStoreDao).getDetails(2L);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class)))
                .thenReturn(new MigrateVolumeAnswer(null, true, null, "migrated"));
        Mockito.clearInvocations(agentManager);

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        return Mockito.mockingDetails(agentManager).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("send")
                        && invocation.getArguments()[1] instanceof CopyCommand);
    }

    private static Map<String, String> ontapDetails(String ip, String svmName, String svmUuid, String protocol) {
        Map<String, String> details = new java.util.HashMap<>();
        if (ip != null) {
            details.put("storageIP", ip);
        }
        if (svmName != null) {
            details.put("svmName", svmName);
        }
        if (svmUuid != null) {
            details.put("svmUUID", svmUuid);
        }
        if (protocol != null) {
            details.put("protocol", protocol);
        }
        return details;
    }

    @Test
    public void copyCommandIsUsedOnlyWhenBothPoolsShareClusterSvmProtocolAndType() throws Exception {
        Map<String, String> base = ontapDetails("10.0.0.1", "svm1", null, "NFS3");

        assertTrue(offlineMigrationUsesCopyCommand(base, ontapDetails("10.0.0.1", "svm1", null, "NFS3"), StoragePoolType.NetworkFilesystem));
        // protocol comparison ignores case
        assertTrue(offlineMigrationUsesCopyCommand(base, ontapDetails("10.0.0.1", "svm1", null, "nfs3"), StoragePoolType.NetworkFilesystem));
        // matching SVM UUIDs win even when the SVM names differ
        assertTrue(offlineMigrationUsesCopyCommand(ontapDetails("10.0.0.1", "a", "uuid-1", "NFS3"),
                ontapDetails("10.0.0.1", "b", "uuid-1", "NFS3"), StoragePoolType.NetworkFilesystem));
    }

    @Test
    public void migrateVolumeCommandIsUsedWhenPoolsDifferByClusterSvmOrType() throws Exception {
        Map<String, String> base = ontapDetails("10.0.0.1", "svm1", null, "NFS3");

        assertFalse(offlineMigrationUsesCopyCommand(base, ontapDetails("10.0.0.2", "svm1", null, "NFS3"), StoragePoolType.NetworkFilesystem));
        assertFalse(offlineMigrationUsesCopyCommand(base, ontapDetails("10.0.0.1", "svm2", null, "NFS3"), StoragePoolType.NetworkFilesystem));
        // UUID on one side only cannot be compared to a name
        assertFalse(offlineMigrationUsesCopyCommand(ontapDetails("10.0.0.1", "svm1", "uuid-1", "NFS3"),
                ontapDetails("10.0.0.1", "svm1", null, "NFS3"), StoragePoolType.NetworkFilesystem));
        assertFalse(offlineMigrationUsesCopyCommand(ontapDetails("10.0.0.1", "svm1", null, "NFS3"),
                ontapDetails("10.0.0.1", "svm1", "uuid-1", "NFS3"), StoragePoolType.NetworkFilesystem));
        // different UUIDs
        assertFalse(offlineMigrationUsesCopyCommand(ontapDetails("10.0.0.1", "svm1", "uuid-1", "NFS3"),
                ontapDetails("10.0.0.1", "svm1", "uuid-2", "NFS3"), StoragePoolType.NetworkFilesystem));
        // no SVM identity at all
        assertFalse(offlineMigrationUsesCopyCommand(ontapDetails("10.0.0.1", null, null, "NFS3"),
                ontapDetails("10.0.0.1", null, null, "NFS3"), StoragePoolType.NetworkFilesystem));
        // missing storage IP
        assertFalse(offlineMigrationUsesCopyCommand(ontapDetails(null, "svm1", null, "NFS3"),
                ontapDetails(null, "svm1", null, "NFS3"), StoragePoolType.NetworkFilesystem));
        // missing protocol on the source
        assertFalse(offlineMigrationUsesCopyCommand(ontapDetails("10.0.0.1", "svm1", null, null),
                ontapDetails("10.0.0.1", "svm1", null, "NFS3"), StoragePoolType.NetworkFilesystem));
        // different pool types, same protocol string
        assertFalse(offlineMigrationUsesCopyCommand(base, ontapDetails("10.0.0.1", "svm1", null, "NFS3"), StoragePoolType.OntapiSCSI));
    }

    @Test
    public void migrateVolumeCommandIsUsedWhenPoolDetailsAreUnavailable() throws Exception {
        Map<String, String> base = ontapDetails("10.0.0.1", "svm1", null, "NFS3");

        assertFalse(offlineMigrationUsesCopyCommand(null, base, StoragePoolType.NetworkFilesystem));
        assertFalse(offlineMigrationUsesCopyCommand(base, null, StoragePoolType.NetworkFilesystem));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Live migration – validation failures
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    public void liveMigrationFromNonKvmSourceHostIsRejected() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(HypervisorType.VMware).when(context.srcHost).getHypervisorType();

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        assertTrue(thrown.getMessage().contains("Only KVM is supported"), thrown.getMessage());
        Mockito.verify(volumeDao, Mockito.never()).persist(Mockito.any(VolumeVO.class));
        Mockito.verify(context.callback).complete(Mockito.argThat(CopyCommandResult::isFailed));
    }

    @Test
    public void liveMigrationFailsWhenSourceOrDestinationPoolIsUnknown() {
        LiveMigrationTestContext missingSource = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(null).when(primaryDataStoreDao).findById(1L);
        CloudRuntimeException noSource = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                missingSource.volumeMap, missingSource.vmTO, missingSource.srcHost, missingSource.destHost, missingSource.callback));
        assertTrue(noSource.getMessage().contains("is not associated with a storage pool"), noSource.getMessage());

        LiveMigrationTestContext missingDest = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(null).when(primaryDataStoreDao).findById(2L);
        CloudRuntimeException noDest = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                missingDest.volumeMap, missingDest.vmTO, missingDest.srcHost, missingDest.destHost, missingDest.callback));
        assertTrue(noDest.getMessage().contains("was not located"), noDest.getMessage());
    }

    @Test
    public void liveMigrationFromManagedPoolToNonOntapPoolIsRejected() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        StoragePoolVO destPool = primaryDataStoreDao.findById(2L);
        Mockito.doReturn(DataStoreProvider.DEFAULT_PRIMARY).when(destPool).getStorageProviderName();

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        assertTrue(thrown.getMessage().contains("from managed storage is not currently supported"), thrown.getMessage());
    }

    @Test
    public void liveMigrationBetweenOntapPoolsWithUnreadableDetailsIsRejectedWithMatchingRulesMessage() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(null).when(primaryDataStoreDao).getDetails(1L);
        Mockito.doReturn(null).when(primaryDataStoreDao).getDetails(2L);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        assertTrue(thrown.getMessage().contains("ONTAP migration requires matching storageIP, SVM, protocol, and pool type"),
                thrown.getMessage());
    }

    @Test
    public void liveMigrationRejectsDestinationPoolsThatMixManagedAndUnmanaged() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        StoragePoolVO firstSrcPool = primaryDataStoreDao.findById(1L);
        Mockito.doReturn(false).when(firstSrcPool).isManaged();

        VolumeInfo secondVolume = Mockito.mock(VolumeInfo.class);
        Mockito.doReturn(11L).when(secondVolume).getId();
        Mockito.doReturn(3L).when(secondVolume).getPoolId();
        PrimaryDataStore secondDest = Mockito.mock(PrimaryDataStore.class);
        Mockito.doReturn(4L).when(secondDest).getId();
        StoragePoolVO secondSrcPool = Mockito.mock(StoragePoolVO.class);
        Mockito.doReturn(3L).when(secondSrcPool).getId();
        Mockito.doReturn(false).when(secondSrcPool).isManaged();
        StoragePoolVO secondDestPool = Mockito.mock(StoragePoolVO.class);
        Mockito.doReturn(4L).when(secondDestPool).getId();
        Mockito.doReturn(false).when(secondDestPool).isManaged();
        Mockito.doReturn(secondSrcPool).when(primaryDataStoreDao).findById(3L);
        Mockito.doReturn(secondDestPool).when(primaryDataStoreDao).findById(4L);
        Map<VolumeInfo, DataStore> volumes = new java.util.HashMap<>(context.volumeMap);
        volumes.put(secondVolume, secondDest);

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(volumes, context.vmTO, context.srcHost, context.destHost, context.callback));

        assertTrue(thrown.getMessage().contains("all managed or all not managed"), thrown.getMessage());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Live migration – flow variations
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    public void liveMigrationSkipsVolumesAlreadyOnTheDestinationPool() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        DataStore onlyDest = context.volumeMap.values().iterator().next();
        Mockito.doReturn(1L).when(onlyDest).getId();

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        Mockito.verify(volumeDao, Mockito.never()).persist(Mockito.any(VolumeVO.class));
        MigrateCommand migrateCommand = captureMigrateCommand();
        assertTrue(migrateCommand.getMigrateStorage().isEmpty());
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void liveMigrationWithoutDisksOnVmLeavesDiskListUntouched() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(null).when(context.vmTO).getDisks();

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        assertTrue(context.vmDisk.getData() != context.destVolumeTO);
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void liveMigrationFromUnmanagedSourceToOntapIscsiUsesSourcePathDirectly() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("ISCSI", StoragePoolType.OntapiSCSI);
        StoragePoolVO srcPool = primaryDataStoreDao.findById(1L);
        Mockito.doReturn(false).when(srcPool).isManaged();
        Mockito.doReturn(StoragePoolType.Filesystem).when(srcPool).getPoolType();
        Mockito.doReturn(DataStoreProvider.DEFAULT_PRIMARY).when(srcPool).getStorageProviderName();

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        MigrateCommand migrateCommand = captureMigrateCommand();
        MigrateDiskInfo diskInfo = migrateCommand.getMigrateDiskInfoList().get(0);
        assertEquals("src-path", diskInfo.getSerialNumber());
        assertTrue(diskInfo.isSourceDiskOnStorageFileSystem());
        assertEquals(1, migrateCommand.getMigrateStorage().size());
        Mockito.verify(volumeService, Mockito.never()).grantAccess(Mockito.eq(context.srcVolume), Mockito.any(), Mockito.any());
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void liveMigrationWithoutCpuSharesInPrepareAnswerLeavesDefault() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(new PrepareForMigrationAnswer(new PrepareForMigrationCommand(context.vmTO))).when(agentManager)
                .send(Mockito.eq(DEST_HOST_ID), Mockito.any(PrepareForMigrationCommand.class));

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        assertEquals(0, captureMigrateCommand().getNewVmCpuShares());
    }

    @Test
    public void liveMigrationOfWindowsGuestIsFlaggedOnTheMigrateCommand() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        GuestOSCategoryVO windows = Mockito.mock(GuestOSCategoryVO.class);
        Mockito.doReturn("windows").when(windows).getName();
        Mockito.doReturn(windows).when(guestOsCategoryDao).findById(8L);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        assertTrue(captureMigrateCommand().isWindows());
    }

    @Test
    public void liveMigrationFailsWhenDestinationHostCannotPrepare() throws Exception {
        LiveMigrationTestContext rejected = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Answer notPrepared = Mockito.mock(Answer.class);
        Mockito.doReturn(false).when(notPrepared).getResult();
        Mockito.doReturn("no capacity").when(notPrepared).getDetails();
        Mockito.doReturn(notPrepared).when(agentManager).send(Mockito.eq(DEST_HOST_ID), Mockito.any(PrepareForMigrationCommand.class));
        CloudRuntimeException rejectedEx = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                rejected.volumeMap, rejected.vmTO, rejected.srcHost, rejected.destHost, rejected.callback));
        assertTrue(rejectedEx.getMessage().contains("no capacity"), rejectedEx.getMessage());
        Mockito.verify(volumeService).expungeVolumeAsync(rejected.destVolume);

        LiveMigrationTestContext noAnswer = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(null).when(agentManager).send(Mockito.eq(DEST_HOST_ID), Mockito.any(PrepareForMigrationCommand.class));
        CloudRuntimeException noAnswerEx = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                noAnswer.volumeMap, noAnswer.vmTO, noAnswer.srcHost, noAnswer.destHost, noAnswer.callback));
        assertTrue(noAnswerEx.getMessage().contains("null answer returned"), noAnswerEx.getMessage());

        LiveMigrationTestContext timedOut = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doThrow(new OperationTimedoutException(null, DEST_HOST_ID, 0, 0, false)).when(agentManager)
                .send(Mockito.eq(DEST_HOST_ID), Mockito.any(PrepareForMigrationCommand.class));
        CloudRuntimeException timedOutEx = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                timedOut.volumeMap, timedOut.vmTO, timedOut.srcHost, timedOut.destHost, timedOut.callback));
        assertTrue(timedOutEx.getMessage().contains("Operation timed out"), timedOutEx.getMessage());
    }

    @Test
    public void liveMigrationFailsWhenMigrateCommandGetsNoAnswerAndRollsBackPreparation() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(null).when(agentManager).send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        assertTrue(thrown.getMessage().contains("Unable to get an answer to the migrate command"), thrown.getMessage());
        ArgumentCaptor<PrepareForMigrationCommand> prepare = ArgumentCaptor.forClass(PrepareForMigrationCommand.class);
        Mockito.verify(agentManager, Mockito.times(2)).send(Mockito.eq(DEST_HOST_ID), prepare.capture());
        assertFalse(prepare.getAllValues().get(0).isRollback());
        assertTrue(prepare.getAllValues().get(1).isRollback());
        Mockito.verify(volumeService).expungeVolumeAsync(context.destVolume);
    }

    @Test
    public void liveMigrationFailsWhenMigrateAnswerReportsFailure() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(new MigrateAnswer(null, false, "libvirt exploded", null)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        assertTrue(thrown.getMessage().contains("libvirt exploded"), thrown.getMessage());
        Mockito.verify(volumeService).expungeVolumeAsync(context.destVolume);
        Mockito.verify(volumeService, Mockito.never()).copyPoliciesBetweenVolumesAndDestroySourceVolumeAfterMigration(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    public void liveMigrationStillCleansUpWhenRollbackOfPreparationFails() throws Exception {
        LiveMigrationTestContext failedAnswer = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        PrepareForMigrationAnswer prepared = new PrepareForMigrationAnswer(new PrepareForMigrationCommand(failedAnswer.vmTO));
        Answer rollbackRefused = Mockito.mock(Answer.class);
        Mockito.doReturn(false).when(rollbackRefused).getResult();
        Mockito.doReturn(prepared).doReturn(rollbackRefused).when(agentManager)
                .send(Mockito.eq(DEST_HOST_ID), Mockito.any(PrepareForMigrationCommand.class));
        Mockito.doReturn(new MigrateAnswer(null, false, "boom", null)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));
        assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                failedAnswer.volumeMap, failedAnswer.vmTO, failedAnswer.srcHost, failedAnswer.destHost, failedAnswer.callback));
        Mockito.verify(volumeService).expungeVolumeAsync(failedAnswer.destVolume);

        LiveMigrationTestContext throwing = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        PrepareForMigrationAnswer prepared2 = new PrepareForMigrationAnswer(new PrepareForMigrationCommand(throwing.vmTO));
        Mockito.doReturn(prepared2).doThrow(new IllegalStateException("rollback unreachable")).when(agentManager)
                .send(Mockito.eq(DEST_HOST_ID), Mockito.any(PrepareForMigrationCommand.class));
        Mockito.doReturn(new MigrateAnswer(null, false, "boom", null)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));
        assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                throwing.volumeMap, throwing.vmTO, throwing.srcHost, throwing.destHost, throwing.callback));
        Mockito.verify(volumeService).expungeVolumeAsync(throwing.destVolume);
    }

    @Test
    public void liveMigrationFailureCleanupToleratesRevokeFailure() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doThrow(new CloudRuntimeException("grant failed")).when(volumeService)
                .grantAccess(Mockito.eq(context.destVolume), Mockito.eq(context.destHost), Mockito.any());
        Mockito.doThrow(new CloudRuntimeException("revoke failed")).when(volumeService)
                .revokeAccess(Mockito.eq(context.destVolume), Mockito.eq(context.destHost), Mockito.any());

        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        Mockito.verify(volumeService).expungeVolumeAsync(context.destVolume);
    }

    @Test
    public void liveMigrationTimeoutIsFatalWhenVmIsNotRunningOnDestination() throws Exception {
        LiveMigrationTestContext notOn = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        VMInstanceVO vmRowA = vmDao.findById(5L);
        Mockito.doReturn("i-5-VM").when(vmRowA).getInstanceName();
        Mockito.doThrow(new OperationTimedoutException(null, SRC_HOST_ID, 0, 0, false)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));
        Mockito.doReturn(new CheckVirtualMachineAnswer(new CheckVirtualMachineCommand("i-5-VM"), VirtualMachine.PowerState.PowerOff, null))
                .when(agentManager).send(Mockito.eq(DEST_HOST_ID), Mockito.any(CheckVirtualMachineCommand.class));
        assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                notOn.volumeMap, notOn.vmTO, notOn.srcHost, notOn.destHost, notOn.callback));
        Mockito.verify(volumeService).expungeVolumeAsync(notOn.destVolume);

        LiveMigrationTestContext unknown = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        VMInstanceVO vmRowB = vmDao.findById(5L);
        Mockito.doReturn("i-5-VM").when(vmRowB).getInstanceName();
        Mockito.doThrow(new OperationTimedoutException(null, SRC_HOST_ID, 0, 0, false)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));
        Mockito.doThrow(new IllegalStateException("cannot reach destination")).when(agentManager)
                .send(Mockito.eq(DEST_HOST_ID), Mockito.any(CheckVirtualMachineCommand.class));
        assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                unknown.volumeMap, unknown.vmTO, unknown.srcHost, unknown.destHost, unknown.callback));
        Mockito.verify(volumeService).expungeVolumeAsync(unknown.destVolume);

        LiveMigrationTestContext notAnAnswer = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        VMInstanceVO vmRowC = vmDao.findById(5L);
        Mockito.doReturn("i-5-VM").when(vmRowC).getInstanceName();
        Mockito.doThrow(new OperationTimedoutException(null, SRC_HOST_ID, 0, 0, false)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));
        Mockito.doReturn(new Answer(null, true, "ok")).when(agentManager)
                .send(Mockito.eq(DEST_HOST_ID), Mockito.any(CheckVirtualMachineCommand.class));
        assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                notAnAnswer.volumeMap, notAnAnswer.vmTO, notAnAnswer.srcHost, notAnAnswer.destHost, notAnAnswer.callback));
    }

    @Test
    public void liveMigrationFailsWhenTargetsCannotBeConnectedOnDestinationHost() {
        LiveMigrationTestContext noAnswer = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(null).when(agentManager).easySend(Mockito.eq(DEST_HOST_ID), Mockito.any(ModifyTargetsCommand.class));
        CloudRuntimeException noAnswerEx = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                noAnswer.volumeMap, noAnswer.vmTO, noAnswer.srcHost, noAnswer.destHost, noAnswer.callback));
        assertTrue(noAnswerEx.getMessage().contains("Unable to get an answer to the modify targets command"), noAnswerEx.getMessage());
        Mockito.verify(volumeService).expungeVolumeAsync(noAnswer.destVolume);

        LiveMigrationTestContext refused = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Answer refusal = Mockito.mock(Answer.class);
        Mockito.doReturn(false).when(refusal).getResult();
        Mockito.doReturn("target busy").when(refusal).getDetails();
        Mockito.doReturn(refusal).when(agentManager).easySend(Mockito.eq(DEST_HOST_ID), Mockito.any(ModifyTargetsCommand.class));
        CloudRuntimeException refusedEx = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                refused.volumeMap, refused.vmTO, refused.srcHost, refused.destHost, refused.callback));
        assertTrue(refusedEx.getMessage().contains("Unable to modify targets on host"), refusedEx.getMessage());
        assertTrue(refusedEx.getMessage().contains("target busy"), refusedEx.getMessage());

        LiveMigrationTestContext wrongType = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doReturn(new Answer(null, true, "ok")).when(agentManager)
                .easySend(Mockito.eq(DEST_HOST_ID), Mockito.any(ModifyTargetsCommand.class));
        CloudRuntimeException wrongTypeEx = assertThrows(CloudRuntimeException.class, () -> strategy.copyAsync(
                wrongType.volumeMap, wrongType.vmTO, wrongType.srcHost, wrongType.destHost, wrongType.callback));
        assertTrue(wrongTypeEx.getMessage().contains("Unexpected answer type"), wrongTypeEx.getMessage());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ONTAP QoS re-apply after content rewrite
    // ══════════════════════════════════════════════════════════════════════════

    private OntapPrimaryDatastoreDriver configureOntapNfsDriver(LiveMigrationTestContext context) {
        OntapPrimaryDatastoreDriver ontapDriver = Mockito.mock(OntapPrimaryDatastoreDriver.class);
        DataStore destStore = context.destVolume.getDataStore();
        Mockito.doReturn(ontapDriver).when(destStore).getDriver();
        return ontapDriver;
    }

    @Test
    public void liveMigrationReappliesQosOnceWhenFirstAttemptSucceeds() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        OntapPrimaryDatastoreDriver ontapDriver = configureOntapNfsDriver(context);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        Mockito.verify(ontapDriver, Mockito.times(1)).applyQosPolicyToVolume(context.destVolume);
    }

    @Test
    public void liveMigrationRetriesQosReapplyAfterTransientFailure() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        OntapPrimaryDatastoreDriver ontapDriver = configureOntapNfsDriver(context);
        Mockito.doThrow(new CloudRuntimeException("transient")).doNothing().when(ontapDriver)
                .applyQosPolicyToVolume(context.destVolume);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        Mockito.verify(ontapDriver, Mockito.times(2)).applyQosPolicyToVolume(context.destVolume);
        Mockito.verify(context.callback).complete(Mockito.argThat(result -> result != null && !result.isFailed()));
    }

    @Test
    public void liveMigrationStopsQosRetriesWhenInterrupted() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        OntapPrimaryDatastoreDriver ontapDriver = configureOntapNfsDriver(context);
        Mockito.doThrow(new CloudRuntimeException("transient")).when(ontapDriver).applyQosPolicyToVolume(context.destVolume);
        Thread.currentThread().interrupt();
        try {
            strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

            Mockito.verify(ontapDriver, Mockito.times(1)).applyQosPolicyToVolume(context.destVolume);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private MigrateCommand captureMigrateCommand() throws Exception {
        ArgumentCaptor<MigrateCommand> captor = ArgumentCaptor.forClass(MigrateCommand.class);
        Mockito.verify(agentManager).send(Mockito.eq(SRC_HOST_ID), captor.capture());
        assertFalse(captor.getAllValues().isEmpty());
        return captor.getValue();
    }

    private LiveMigrationTestContext configureLiveMigration(String protocol, StoragePoolType poolType) {
        VolumeInfo srcVolume = Mockito.mock(VolumeInfo.class);
        VolumeInfo destVolume = Mockito.mock(VolumeInfo.class);
        PrimaryDataStore srcStore = Mockito.mock(PrimaryDataStore.class);
        PrimaryDataStore destStore = Mockito.mock(PrimaryDataStore.class);
        PrimaryDataStoreDriver destDriver = Mockito.mock(PrimaryDataStoreDriver.class);
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool = Mockito.mock(StoragePoolVO.class);
        VolumeVO destVolumeVO = Mockito.mock(VolumeVO.class);
        VolumeObjectTO destVolumeTO = new VolumeObjectTO();
        HostVO srcHost = Mockito.mock(HostVO.class);
        HostVO destHost = Mockito.mock(HostVO.class);
        VirtualMachineTO vmTO = Mockito.mock(VirtualMachineTO.class);
        AsyncCompletionCallback<CopyCommandResult> callback = Mockito.mock(AsyncCompletionCallback.class);

        Mockito.doReturn(SRC_HOST_ID).when(srcHost).getId();
        Mockito.doReturn(DEST_HOST_ID).when(destHost).getId();
        Mockito.doReturn(HypervisorType.KVM).when(srcHost).getHypervisorType();

        VolumeObjectTO srcVolumeTO = new VolumeObjectTO();
        srcVolumeTO.setId(10L);
        DiskTO vmDisk = new DiskTO();
        vmDisk.setData(srcVolumeTO);
        Mockito.doReturn(5L).when(vmTO).getId();
        Mockito.doReturn("vm-name").when(vmTO).getName();
        Mockito.doReturn(new DiskTO[] {vmDisk}).when(vmTO).getDisks();

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        GuestOSVO guestOs = Mockito.mock(GuestOSVO.class);
        GuestOSCategoryVO guestOsCategory = Mockito.mock(GuestOSCategoryVO.class);
        Mockito.doReturn(VirtualMachine.State.Running).when(vm).getState();
        Mockito.doReturn(7L).when(vm).getGuestOSId();
        Mockito.doReturn(vm).when(vmDao).findById(5L);
        Mockito.doReturn(8L).when(guestOs).getCategoryId();
        Mockito.doReturn(guestOs).when(guestOsDao).findById(7L);
        Mockito.doReturn("Linux").when(guestOsCategory).getName();
        Mockito.doReturn(guestOsCategory).when(guestOsCategoryDao).findById(8L);

        Mockito.doReturn(10L).when(srcVolume).getId();
        Mockito.doReturn(1L).when(srcVolume).getPoolId();
        Mockito.doReturn("src-path").when(srcVolume).getPath();
        Mockito.doReturn("/iqn/src/0").when(srcVolume).get_iScsiName();
        Mockito.doReturn(srcStore).when(srcVolume).getDataStore();
        Mockito.doReturn(srcVolume).when(volumeDataFactory).getVolume(10L, srcStore);

        Mockito.doReturn(20L).when(destVolume).getId();
        Mockito.doReturn(2L).when(destVolume).getPoolId();
        Mockito.doReturn("dest-uuid").when(destVolume).getUuid();
        Mockito.doReturn("/iqn/dest/1").when(destVolume).get_iScsiName();
        Mockito.doReturn(poolType).when(destVolume).getStoragePoolType();
        Mockito.doReturn(destVolumeTO).when(destVolume).getTO();
        Mockito.doReturn(destStore).when(destVolume).getDataStore();
        Mockito.doReturn(2L).when(destStore).getId();
        Mockito.doReturn(destDriver).when(destStore).getDriver();

        configureOntapPool(srcPool, 1L, poolType);
        configureOntapPool(destPool, 2L, poolType);
        Mockito.doReturn(Map.of("storageIP", "storage-ip", "svmName", "svm1", "protocol", protocol)).when(primaryDataStoreDao).getDetails(1L);
        Mockito.doReturn(Map.of("storageIP", "storage-ip", "svmName", "svm1", "protocol", protocol)).when(primaryDataStoreDao).getDetails(2L);

        VolumeVO srcVolumeVO = new VolumeVO("name", 0L, 0L, 0L, 0L, 0L, "folder", "src-path",
                Storage.ProvisioningType.THIN, 0L, Volume.Type.ROOT);
        Mockito.doReturn(srcVolumeVO).when(volumeDao).findById(10L);
        Mockito.doReturn(20L).when(destVolumeVO).getId();
        Mockito.doReturn(destVolumeVO).when(volumeDao).persist(Mockito.any(VolumeVO.class));
        Mockito.doReturn(destVolumeVO).when(volumeDao).findById(20L);
        Mockito.doReturn(destVolume).when(volumeDataFactory).getVolume(20L, destStore);

        configureModifyTargets(SRC_HOST_ID, "src-connected");
        configureModifyTargets(DEST_HOST_ID, "dest-connected");
        try {
            PrepareForMigrationAnswer prepareAnswer = new PrepareForMigrationAnswer(new PrepareForMigrationCommand(vmTO));
            prepareAnswer.setNewVmCpuShares(LIVE_MIGRATION_CPU_SHARES);
            Mockito.doReturn(prepareAnswer).when(agentManager)
                    .send(Mockito.eq(DEST_HOST_ID), Mockito.any(PrepareForMigrationCommand.class));
            Mockito.doReturn(new MigrateAnswer(null, true, null, null)).when(agentManager)
                    .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        return new LiveMigrationTestContext(Map.of(srcVolume, destStore), vmTO, vmDisk, srcHost, destHost, srcVolume, destVolume,
                destVolumeVO, destVolumeTO, callback);
    }

    private void configureOntapPool(StoragePoolVO pool, long id, StoragePoolType poolType) {
        Mockito.doReturn(id).when(pool).getId();
        Mockito.doReturn(true).when(pool).isManaged();
        Mockito.doReturn(poolType).when(pool).getPoolType();
        Mockito.doReturn("pool-uuid-" + id).when(pool).getUuid();
        Mockito.doReturn(DataStoreProvider.ONTAP_PLUGIN_NAME).when(pool).getStorageProviderName();
        Mockito.doReturn(pool).when(primaryDataStoreDao).findById(id);
    }

    private void configureModifyTargets(long hostId, String connectedPath) {
        ModifyTargetsAnswer answer = Mockito.mock(ModifyTargetsAnswer.class);
        Mockito.doReturn(true).when(answer).getResult();
        Mockito.doReturn(List.of(connectedPath)).when(answer).getConnectedPaths();
        Mockito.doReturn(answer).when(agentManager).easySend(Mockito.eq(hostId), Mockito.any(ModifyTargetsCommand.class));
    }

    private OfflineMigrationTestContext configureOfflineMigration(boolean isSourceOntap, String destProtocol,
            String destSvmName, VirtualMachine vm) {
        return configureOfflineMigration(isSourceOntap, "NFS3", destProtocol, destSvmName, vm,
                StoragePoolType.NetworkFilesystem, StoragePoolType.NetworkFilesystem);
    }

    private OfflineMigrationTestContext configureOfflineMigration(boolean isSourceOntap, String srcProtocol,
            String destProtocol, String destSvmName, VirtualMachine vm, StoragePoolType srcPoolType,
            StoragePoolType destPoolType) {
        VolumeInfo srcVolume = Mockito.mock(VolumeInfo.class);
        VolumeInfo destVolume = Mockito.mock(VolumeInfo.class);
        PrimaryDataStore srcStore = Mockito.mock(PrimaryDataStore.class);
        PrimaryDataStore destStore = Mockito.mock(PrimaryDataStore.class);
        PrimaryDataStoreDriver destDriver = Mockito.mock(PrimaryDataStoreDriver.class);
        Scope srcScope = Mockito.mock(Scope.class);
        HostVO host = Mockito.mock(HostVO.class);
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool = Mockito.mock(StoragePoolVO.class);
        VolumeVO srcVolumeVO = Mockito.mock(VolumeVO.class);
        VolumeVO destVolumeVO = Mockito.mock(VolumeVO.class);
        AsyncCompletionCallback<CopyCommandResult> callback = Mockito.mock(AsyncCompletionCallback.class);

        Mockito.doReturn(3L).when(host).getId();
        Mockito.doReturn(Volume.State.Migrating).when(srcVolume).getState();
        Mockito.doReturn(HypervisorType.KVM).when(srcVolume).getHypervisorType();
        Mockito.doReturn(vm).when(srcVolume).getAttachedVM();
        Mockito.doReturn(10L).when(srcVolume).getId();
        Mockito.doReturn(20L).when(destVolume).getId();
        Mockito.doReturn(1L).when(srcVolume).getPoolId();
        Mockito.doReturn(2L).when(destVolume).getPoolId();
        Mockito.doReturn(srcStore).when(srcVolume).getDataStore();
        Mockito.doReturn(destStore).when(destVolume).getDataStore();
        Mockito.doReturn(new VolumeObjectTO()).when(srcVolume).getTO();
        Mockito.doReturn(new VolumeObjectTO()).when(destVolume).getTO();

        Mockito.doReturn(DataStoreRole.Primary).when(srcStore).getRole();
        Mockito.doReturn(DataStoreRole.Primary).when(destStore).getRole();
        Mockito.doReturn(1L).when(srcStore).getId();
        Mockito.doReturn(2L).when(destStore).getId();
        Mockito.doReturn(destDriver).when(destStore).getDriver();
        Mockito.doReturn(srcScope).when(srcStore).getScope();
        Mockito.doReturn(ScopeType.HOST).when(srcScope).getScopeType();
        Mockito.doReturn(host.getId()).when(srcScope).getScopeId();

        Mockito.doReturn(isSourceOntap).when(srcPool).isManaged();
        Mockito.doReturn(true).when(destPool).isManaged();
        Mockito.doReturn(1L).when(srcPool).getId();
        Mockito.doReturn(2L).when(destPool).getId();
        Mockito.doReturn(srcPoolType).when(srcPool).getPoolType();
        Mockito.doReturn(destPoolType).when(destPool).getPoolType();
        Mockito.doReturn(isSourceOntap ? DataStoreProvider.ONTAP_PLUGIN_NAME : DataStoreProvider.DEFAULT_PRIMARY)
                .when(srcPool).getStorageProviderName();
        Mockito.doReturn(DataStoreProvider.ONTAP_PLUGIN_NAME).when(destPool).getStorageProviderName();
        Mockito.doReturn(Map.of("storageIP", "storage-ip", "svmName", "svm1", "protocol", srcProtocol))
                .when(primaryDataStoreDao).getDetails(1L);
        Mockito.doReturn(Map.of("storageIP", "storage-ip", "svmName", destSvmName, "protocol", destProtocol))
                .when(primaryDataStoreDao).getDetails(2L);
        Mockito.doReturn(srcPool).when(primaryDataStoreDao).findById(1L);
        Mockito.doReturn(destPool).when(primaryDataStoreDao).findById(2L);

        if (isSourceOntap) {
            Mockito.doReturn(srcPoolType).when(srcVolumeVO).getPoolType();
            Mockito.doReturn(srcVolumeVO).when(volumeDao).findById(10L);
        }
        Mockito.doReturn(destPoolType).when(destVolumeVO).getPoolType();
        Mockito.doReturn(Storage.ImageFormat.QCOW2).when(destVolumeVO).getFormat();
        Mockito.doReturn(destVolumeVO).when(volumeDao).findById(20L);
        Mockito.doReturn(destVolume).when(volumeDataFactory).getVolume(20L, destStore);
        Mockito.doReturn(host).when(hostDao).findById(3L);

        return new OfflineMigrationTestContext(srcVolume, destVolume, destVolumeVO, host, callback);
    }

    private static class OfflineMigrationTestContext {
        private final VolumeInfo srcVolume;
        private final VolumeInfo destVolume;
        private final VolumeVO destVolumeVO;
        private final HostVO host;
        private final AsyncCompletionCallback<CopyCommandResult> callback;

        OfflineMigrationTestContext(VolumeInfo srcVolume, VolumeInfo destVolume, VolumeVO destVolumeVO, HostVO host,
                AsyncCompletionCallback<CopyCommandResult> callback) {
            this.srcVolume = srcVolume;
            this.destVolume = destVolume;
            this.destVolumeVO = destVolumeVO;
            this.host = host;
            this.callback = callback;
        }
    }

    private static class LiveMigrationTestContext {
        private final Map<VolumeInfo, DataStore> volumeMap;
        private final VirtualMachineTO vmTO;
        private final DiskTO vmDisk;
        private final HostVO srcHost;
        private final HostVO destHost;
        private final VolumeInfo srcVolume;
        private final VolumeInfo destVolume;
        private final VolumeVO destVolumeVO;
        private final VolumeObjectTO destVolumeTO;
        private final AsyncCompletionCallback<CopyCommandResult> callback;

        LiveMigrationTestContext(Map<VolumeInfo, DataStore> volumeMap, VirtualMachineTO vmTO, DiskTO vmDisk, HostVO srcHost, HostVO destHost,
                VolumeInfo srcVolume, VolumeInfo destVolume, VolumeVO destVolumeVO, VolumeObjectTO destVolumeTO,
                AsyncCompletionCallback<CopyCommandResult> callback) {
            this.volumeMap = volumeMap;
            this.vmTO = vmTO;
            this.vmDisk = vmDisk;
            this.srcHost = srcHost;
            this.destHost = destHost;
            this.srcVolume = srcVolume;
            this.destVolume = destVolume;
            this.destVolumeVO = destVolumeVO;
            this.destVolumeTO = destVolumeTO;
            this.callback = callback;
        }
    }

    // ===================================================================================
    // Assertions on the exact content of what the strategy sends, persists and cleans up.
    // ===================================================================================

    private StoragePoolVO poolRow(long id) {
        return primaryDataStoreDao.findById(id);
    }

    private List<ModifyTargetsCommand> modifyTargetsSentTo(long hostId, boolean add) {
        ArgumentCaptor<ModifyTargetsCommand> captor = ArgumentCaptor.forClass(ModifyTargetsCommand.class);
        Mockito.verify(agentManager, Mockito.atLeast(0)).easySend(Mockito.eq(hostId), captor.capture());
        return captor.getAllValues().stream().filter(command -> command.getAdd() == add).collect(Collectors.toList());
    }

    private static Set<String> iqnsOf(List<ModifyTargetsCommand> commands) {
        return commands.stream().flatMap(command -> command.getTargets().stream())
                .map(target -> target.get(ModifyTargetsCommand.IQN)).collect(Collectors.toSet());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void liveMigrationMarksVmStateAndDrivesDestinationVolumeThroughMigrationEvents() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        Mockito.verify(context.vmTO).setState(VirtualMachine.State.Running);
        InOrder order = Mockito.inOrder(context.destVolume);
        order.verify(context.destVolume).processEvent(ObjectInDataStoreStateMachine.Event.MigrationCopyRequested);
        order.verify(context.destVolume).processEvent(ObjectInDataStoreStateMachine.Event.MigrationCopySucceeded);
        order.verify(context.destVolume).processEvent(ObjectInDataStoreStateMachine.Event.MigrationRequested);
        DataStore destStore = context.destVolume.getDataStore();
        Mockito.verify(destStore.getDriver()).createAsync(Mockito.eq(destStore), Mockito.eq(context.destVolume), Mockito.isNull());
        Mockito.verify(context.destVolume, Mockito.never()).processEvent(ObjectInDataStoreStateMachine.Event.OperationFailed);
        Mockito.verify(volumeService, Mockito.never()).expungeVolumeAsync(Mockito.any());
    }

    @Test
    public void liveMigrationPersistsDestinationRowWithOnlyDestinationPoolIdentity() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        VolumeVO sourceRow = volumeDao.findById(10L);
        setField(sourceRow, "id", 10L);
        sourceRow.setInstanceId(77L);
        sourceRow.setChainInfo("chain-info");
        sourceRow.set_iScsiName("src-iqn");
        sourceRow.setPodId(4L);
        sourceRow.setPoolId(1L);
        sourceRow.setPoolType(StoragePoolType.Filesystem);
        StoragePoolVO destPoolRow = poolRow(2L);
        Mockito.doReturn(9L).when(destPoolRow).getPodId();

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        VolumeVO persisted = captureDuplicatedVolume();
        assertNull(persisted.getInstanceId());
        assertNull(persisted.getChainInfo());
        assertNull(persisted.getPath());
        assertNull(persisted.get_iScsiName());
        assertNull(persisted.getFolder());
        assertEquals(9L, persisted.getPodId());
        assertEquals(2L, persisted.getPoolId());
        assertEquals(StoragePoolType.NetworkFilesystem, persisted.getPoolType());
        assertEquals(1L, persisted.getLastPoolId());
        assertEquals(10L, persisted.getLastId());
    }

    @Test
    public void liveMigrationRecordsPoolTypesOnDiskInfoAndFlagsTheCommandAsManaged() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        MigrateCommand command = captureMigrateCommand();
        MigrateDiskInfo diskInfo = command.getMigrateStorage().get("src-path");
        assertEquals(StoragePoolType.NetworkFilesystem, diskInfo.getSourcePoolType());
        assertEquals(StoragePoolType.NetworkFilesystem, diskInfo.getDestPoolType());
        assertEquals(86400, command.getWait());
        assertTrue(command.isMigrateStorageManaged());
        assertEquals("vm-name", command.getVmName());
    }

    @Test
    public void liveMigrationToIscsiRecordsPoolTypesAndDisconnectsBothLunsFromSourceHostAfterSuccess() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("ISCSI", StoragePoolType.OntapiSCSI);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        MigrateDiskInfo diskInfo = captureMigrateCommand().getMigrateDiskInfoList().get(0);
        assertEquals(StoragePoolType.OntapiSCSI, diskInfo.getSourcePoolType());
        assertEquals(StoragePoolType.OntapiSCSI, diskInfo.getDestPoolType());
        assertEquals(Set.of("/iqn/src/0", "/iqn/dest/1"), iqnsOf(modifyTargetsSentTo(SRC_HOST_ID, false)));
        Mockito.verify(context.destVolumeVO).setLastId(10L);
        Mockito.verify(context.destVolumeVO).setFormat(Storage.ImageFormat.RAW);
        Mockito.verify(volumeDao, Mockito.atLeastOnce()).update(20L, context.destVolumeVO);
    }

    @Test
    public void liveMigrationToNfsNeverDisconnectsAnythingFromTheSourceHost() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        assertTrue(modifyTargetsSentTo(SRC_HOST_ID, false).isEmpty());
        Mockito.verify(context.destVolumeVO).setLastId(10L);
    }

    @Test
    public void connectCommandForNfsDestinationCarriesPoolIdentityAndVolumeUuid() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        StoragePoolVO destPoolRow = poolRow(2L);
        Mockito.doReturn("10.1.1.2").when(destPoolRow).getHostAddress();
        Mockito.doReturn(2049).when(destPoolRow).getPort();

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        List<ModifyTargetsCommand> connects = modifyTargetsSentTo(DEST_HOST_ID, true);
        assertEquals(1, connects.size());
        ModifyTargetsCommand connect = connects.get(0);
        assertTrue(connect.getApplyToAllHostsInCluster());
        assertEquals(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC, connect.getTargetTypeToRemove());
        assertEquals(1, connect.getTargets().size());
        Map<String, String> target = connect.getTargets().get(0);
        assertEquals("dest-uuid", target.get(ModifyTargetsCommand.IQN));
        assertEquals("NetworkFilesystem", target.get(ModifyTargetsCommand.STORAGE_TYPE));
        assertEquals("pool-uuid-2", target.get(ModifyTargetsCommand.STORAGE_UUID));
        assertEquals("10.1.1.2", target.get(ModifyTargetsCommand.STORAGE_HOST));
        assertEquals("2049", target.get(ModifyTargetsCommand.STORAGE_PORT));
    }

    @Test
    public void connectCommandForIscsiDestinationUsesTheLunIqn() {
        LiveMigrationTestContext context = configureLiveMigration("ISCSI", StoragePoolType.OntapiSCSI);

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        List<ModifyTargetsCommand> connects = modifyTargetsSentTo(DEST_HOST_ID, true);
        assertEquals(Set.of("/iqn/dest/1"), iqnsOf(connects));
        assertEquals("OntapiSCSI", connects.get(0).getTargets().get(0).get(ModifyTargetsCommand.STORAGE_TYPE));
    }

    @Test
    public void liveMigrationOnlyRewritesTheDiskMatchingTheMigratedVolume() {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        VolumeObjectTO otherVolumeTO = new VolumeObjectTO();
        otherVolumeTO.setId(99L);
        DiskTO otherDisk = new DiskTO();
        otherDisk.setData(otherVolumeTO);
        DiskTO diskWithoutData = new DiskTO();
        Mockito.doReturn(new DiskTO[] {otherDisk, diskWithoutData, context.vmDisk}).when(context.vmTO).getDisks();

        strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback);

        assertSame(otherVolumeTO, otherDisk.getData());
        assertNull(diskWithoutData.getData());
        assertSame(context.destVolumeTO, context.vmDisk.getData());
    }

    @Test
    public void failedLiveMigrationRollsBackPreparationDisconnectsRevokesAndExpungesDestination() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("ISCSI", StoragePoolType.OntapiSCSI);
        Mockito.doReturn(new MigrateAnswer(null, false, "libvirt refused", null)).when(agentManager)
                .send(Mockito.eq(SRC_HOST_ID), Mockito.any(MigrateCommand.class));

        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        ArgumentCaptor<PrepareForMigrationCommand> prepare = ArgumentCaptor.forClass(PrepareForMigrationCommand.class);
        Mockito.verify(agentManager, Mockito.times(2)).send(Mockito.eq(DEST_HOST_ID), prepare.capture());
        assertFalse(prepare.getAllValues().get(0).isRollback());
        assertTrue(prepare.getAllValues().get(1).isRollback());
        assertEquals(Set.of("/iqn/dest/1"), iqnsOf(modifyTargetsSentTo(DEST_HOST_ID, false)));
        DataStore destStore = context.destVolume.getDataStore();
        InOrder order = Mockito.inOrder(volumeService, context.destVolume);
        order.verify(volumeService).revokeAccess(context.destVolume, context.destHost, destStore);
        order.verify(context.destVolume).processEvent(ObjectInDataStoreStateMachine.Event.OperationFailed);
        order.verify(context.destVolume).processEvent(ObjectInDataStoreStateMachine.Event.DestroyRequested);
        order.verify(volumeService).expungeVolumeAsync(context.destVolume);
        Mockito.verify(volumeService, Mockito.never()).copyPoliciesBetweenVolumesAndDestroySourceVolumeAfterMigration(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        ArgumentCaptor<CopyCommandResult> result = ArgumentCaptor.forClass(CopyCommandResult.class);
        Mockito.verify(context.callback).complete(result.capture());
        assertTrue(result.getValue().isFailed());
        assertTrue(result.getValue().getResult().contains("libvirt refused"), result.getValue().getResult());
    }

    @Test
    public void failureBeforeDestinationIsPreparedDoesNotSendARollbackToTheHost() throws Exception {
        LiveMigrationTestContext context = configureLiveMigration("NFS3", StoragePoolType.NetworkFilesystem);
        Mockito.doThrow(new CloudRuntimeException("grant failed")).when(volumeService)
                .grantAccess(Mockito.eq(context.destVolume), Mockito.eq(context.destHost), Mockito.any());

        assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.volumeMap, context.vmTO, context.srcHost, context.destHost, context.callback));

        Mockito.verify(agentManager, Mockito.never()).send(Mockito.anyLong(), Mockito.any(PrepareForMigrationCommand.class));
        Mockito.verify(context.destVolume).processEvent(ObjectInDataStoreStateMachine.Event.OperationFailed);
        Mockito.verify(context.destVolume).processEvent(ObjectInDataStoreStateMachine.Event.DestroyRequested);
        Mockito.verify(volumeService).expungeVolumeAsync(context.destVolume);
    }

    // ------------------------------------------------------------------ offline migration

    @Test
    public void offlineMigrationCreatesDestinationOnItsOwnStoreAndRevokesBothSides() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        DataStore destStore = context.destVolume.getDataStore();
        Mockito.verify(destStore.getDriver()).createAsync(Mockito.eq(destStore), Mockito.eq(context.destVolume), Mockito.isNull());
        Mockito.verify(volumeService).revokeAccess(context.srcVolume, context.host, context.srcVolume.getDataStore());
        // once after the copy command and once more when the whole migration finishes
        Mockito.verify(volumeService, Mockito.times(2)).revokeAccess(context.destVolume, context.host, destStore);
        Mockito.verify(volumeService, Mockito.times(2)).grantAccess(Mockito.eq(context.destVolume), Mockito.eq(context.host), Mockito.any());
        Mockito.verify(volumeService).grantAccess(Mockito.eq(context.srcVolume), Mockito.eq(context.host), Mockito.any());
    }

    @Test
    public void offlineCopyCommandCarriesManagedVolumeDetailsForBothSides() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        StoragePoolVO srcPool = poolRow(1L);
        StoragePoolVO destPool = poolRow(2L);
        Mockito.doReturn("10.0.0.1").when(srcPool).getHostAddress();
        Mockito.doReturn(2049).when(srcPool).getPort();
        Mockito.doReturn("10.0.0.2").when(destPool).getHostAddress();
        Mockito.doReturn(3260).when(destPool).getPort();
        VolumeVO srcRow = volumeDao.findById(10L);
        VolumeVO destRow = volumeDao.findById(20L);
        Mockito.doReturn("src-iqn").when(srcRow).get_iScsiName();
        Mockito.doReturn(1000L).when(srcRow).getSize();
        Mockito.doReturn(2000L).when(destRow).getSize();
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        ArgumentCaptor<CopyCommand> captor = ArgumentCaptor.forClass(CopyCommand.class);
        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), captor.capture());
        Map<String, String> src = captor.getValue().getOptions();
        Map<String, String> dest = captor.getValue().getOptions2();
        assertNotNull(src);
        assertNotNull(dest);
        assertEquals("10.0.0.1", src.get(DiskTO.STORAGE_HOST));
        assertEquals("2049", src.get(DiskTO.STORAGE_PORT));
        assertEquals("src-iqn", src.get(DiskTO.IQN));
        assertEquals("NetworkFilesystem", src.get(DiskTO.PROTOCOL_TYPE));
        assertEquals("1000", src.get(DiskTO.VOLUME_SIZE));
        assertEquals("10.0.0.2", dest.get(DiskTO.STORAGE_HOST));
        assertEquals("3260", dest.get(DiskTO.STORAGE_PORT));
        assertEquals("2000", dest.get(DiskTO.VOLUME_SIZE));
        assertEquals(10800, captor.getValue().getWait());
    }

    @Test
    public void offlineMigrateVolumeCommandHasNoSourceDetailsWhenSourceIsNotManaged() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(false, "NFS3", "svm1", null);
        StoragePoolVO destPoolRow = poolRow(2L);
        Mockito.doReturn("10.0.0.2").when(destPoolRow).getHostAddress();
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(MigrateVolumeCommand.class)))
                .thenReturn(new MigrateVolumeAnswer(null, true, null, "migrated"));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        ArgumentCaptor<MigrateVolumeCommand> captor = ArgumentCaptor.forClass(MigrateVolumeCommand.class);
        Mockito.verify(agentManager).send(Mockito.eq(context.host.getId()), captor.capture());
        assertNull(captor.getValue().getSrcDetails());
        assertNotNull(captor.getValue().getDestDetails());
        assertEquals("10.0.0.2", captor.getValue().getDestDetails().get(DiskTO.STORAGE_HOST));
        assertEquals(10800, captor.getValue().getWaitInMillSeconds() / 1000);
    }

    @Test
    public void offlineMigrationReappliesQosOnDestinationNfsVolume() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        OntapPrimaryDatastoreDriver ontapDriver = Mockito.mock(OntapPrimaryDatastoreDriver.class);
        DataStore destStoreRow = context.destVolume.getDataStore();
        Mockito.doReturn(ontapDriver).when(destStoreRow).getDriver();
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        Mockito.verify(ontapDriver).applyQosPolicyToVolume(context.destVolume);
    }

    @Test
    public void offlineMigrationSuccessReportsDestinationDataThroughTheCallback() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer(new VolumeObjectTO()));

        strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback);

        ArgumentCaptor<CopyCommandResult> captor = ArgumentCaptor.forClass(CopyCommandResult.class);
        Mockito.verify(context.callback).complete(captor.capture());
        CopyCommandResult result = captor.getValue();
        assertFalse(result.isFailed());
        assertNull(result.getResult());
        assertTrue(result.getAnswer().getResult());
        assertSame(context.destVolume.getTO(), ((CopyCmdAnswer)result.getAnswer()).getNewData());
    }

    @Test
    public void offlineMigrationFailureReportsTheErrorThroughTheCallbackAndRethrows() throws Exception {
        OfflineMigrationTestContext context = configureOfflineMigration(true, "NFS3", "svm1", null);
        Mockito.when(agentManager.send(Mockito.eq(context.host.getId()), Mockito.any(CopyCommand.class)))
                .thenReturn(new CopyCmdAnswer("copy blew up"));

        CloudRuntimeException thrown = assertThrows(CloudRuntimeException.class, () ->
                strategy.copyAsync(context.srcVolume, context.destVolume, (Host) null, context.callback));

        assertEquals("copy blew up", thrown.getMessage());
        ArgumentCaptor<CopyCommandResult> captor = ArgumentCaptor.forClass(CopyCommandResult.class);
        Mockito.verify(context.callback).complete(captor.capture());
        CopyCommandResult result = captor.getValue();
        assertTrue(result.isFailed());
        assertEquals("Primary storage migration failed due to an unexpected error: copy blew up", result.getResult());
        assertFalse(result.getAnswer().getResult());
        Mockito.verify(volumeService).revokeAccess(context.srcVolume, context.host, context.srcVolume.getDataStore());
        Mockito.verify(volumeDao, Mockito.never()).update(Mockito.eq(20L), Mockito.argThat(row -> "copied".equals(row.getPath())));
    }
}
