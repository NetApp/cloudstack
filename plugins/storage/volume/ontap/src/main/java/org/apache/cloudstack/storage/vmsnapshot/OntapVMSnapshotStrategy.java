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
package org.apache.cloudstack.storage.vmsnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VMSnapshotOptions;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.CreateVMSnapshotAnswer;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteVMSnapshotAnswer;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.FreezeThawVMAnswer;
import com.cloud.agent.api.FreezeThawVMCommand;
import com.cloud.agent.api.RevertToVMSnapshotAnswer;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotTO;
import com.cloud.event.EventTypes;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import org.apache.cloudstack.storage.utils.OntapStorageConstants;

/**
 * VM Snapshot strategy for NetApp ONTAP managed storage using FlexVolume-level snapshots.
 *
 * <p>This strategy handles VM-level (instance) snapshots for VMs whose volumes
 * reside on ONTAP managed primary storage. Instead of creating per-file clones
 * (the old approach), it takes <b>ONTAP FlexVolume-level snapshots</b> via the
 * ONTAP REST API ({@code POST /api/storage/volumes/{uuid}/snapshots}).</p>
 *
 * <h3>Key Advantage:</h3>
 * <p>When multiple CloudStack disks (ROOT + DATA) reside on the same ONTAP
 * FlexVolume, a single FlexVolume snapshot atomically captures all of them.
 * This is both faster and more storage-efficient than per-file clones.</p>
 *
 * <h3>Flow:</h3>
 * <ol>
 *   <li>Group all VM volumes by their parent FlexVolume UUID</li>
 *   <li>Freeze the VM via QEMU guest agent ({@code fsfreeze}) — if quiesce requested</li>
 *   <li>For each unique FlexVolume, create one ONTAP snapshot</li>
 *   <li>Thaw the VM</li>
 *   <li>Record FlexVolume → snapshot UUID mappings in {@code vm_snapshot_details}</li>
 * </ol>
 *
 * <h3>Metadata in vm_snapshot_details:</h3>
 * <p>Each FlexVolume snapshot is stored as a detail row with:
 * <ul>
 *   <li>name = {@value OntapStorageConstants#ONTAP_FLEXVOL_SNAPSHOT}</li>
 *   <li>value = {@code "<flexVolUuid>::<snapshotUuid>::<snapshotName>::<volumePath>::<poolId>::<protocol>"}</li>
 * </ul>
 * One row is persisted per CloudStack volume (not per FlexVolume) so that the
 * revert operation can restore individual files/LUNs using the ONTAP Snapshot
 * File Restore API ({@code POST /api/storage/volumes/{vol}/snapshots/{snap}/files/{path}/restore}).</p>
 *
 * <h3>Strategy Selection:</h3>
 * <p>Returns {@code StrategyPriority.HIGHEST} when:</p>
 * <ul>
 *   <li>Hypervisor is KVM</li>
 *   <li>Snapshot type is Disk-only (no memory)</li>
 *   <li>All VM volumes are on ONTAP managed primary storage</li>
 * </ul>
 */
public class OntapVMSnapshotStrategy extends StorageVMSnapshotStrategy {

    private static final Logger logger = LogManager.getLogger(OntapVMSnapshotStrategy.class);

    /** Separator used in the vm_snapshot_details value to delimit FlexVol UUID, snapshot UUID, snapshot name, and pool ID. */
    static final String DETAIL_SEPARATOR = "::";

    @Inject
    private StoragePoolDetailsDao storagePoolDetailsDao;

    @Inject
    private VolumeDetailsDao volumeDetailsDao;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        return super.configure(name, params);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Strategy Selection
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public StrategyPriority canHandle(VMSnapshot vmSnapshot) {
        VMSnapshotVO vmSnapshotVO = (VMSnapshotVO) vmSnapshot;

        // For existing (non-Allocated) snapshots, check if we created them
        if (!VMSnapshot.State.Allocated.equals(vmSnapshotVO.getState())) {
            // Check for our FlexVolume snapshot details first
            List<VMSnapshotDetailsVO> flexVolDetails = vmSnapshotDetailsDao.findDetails(vmSnapshot.getId(), OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT);
            if (CollectionUtils.isNotEmpty(flexVolDetails)) {
                // Verify the volumes are still on ONTAP storage
                if (allVolumesOnOntapManagedStorage(vmSnapshot.getVmId())) {
                    return StrategyPriority.HIGHEST;
                }
                return StrategyPriority.CANT_HANDLE;
            }
            return StrategyPriority.CANT_HANDLE;
        }

        // For new snapshots (Allocated state), check if we can handle this VM
        // ONTAP only supports disk-only snapshots, not memory snapshots
        if (allVolumesOnOntapManagedStorage(vmSnapshot.getVmId())) {
            if (vmSnapshotVO.getType() == VMSnapshot.Type.DiskAndMemory) {
                logger.debug("canHandle: Memory snapshots (DiskAndMemory) are not supported for VMs on ONTAP storage. VMSnapshot [{}]", vmSnapshot.getId());
                return StrategyPriority.CANT_HANDLE;
            }
            return StrategyPriority.HIGHEST;
        }

        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public StrategyPriority canHandle(Long vmId, Long rootPoolId, boolean snapshotMemory) {
        // ONTAP FlexVolume snapshots only support disk-only (crash-consistent) snapshots.
        // Memory snapshots (snapshotMemory=true) are not supported because:
        // 1. ONTAP snapshots capture disk state only, not VM memory
        // 2. Allowing memory snapshots would require falling back to libvirt snapshots,
        //    creating mixed snapshot chains that would cause issues during revert
        // Return CANT_HANDLE so VMSnapshotManagerImpl can provide a clear error message.
        if (snapshotMemory) {
            logger.debug("canHandle: Memory snapshots (snapshotMemory=true) are not supported for VMs on ONTAP storage. VM [{}]", vmId);
            return StrategyPriority.CANT_HANDLE;
        }

        if (allVolumesOnOntapManagedStorage(vmId)) {
            return StrategyPriority.HIGHEST;
        }

        return StrategyPriority.CANT_HANDLE;
    }

    /**
     * Checks whether all volumes of a VM reside on ONTAP managed primary storage.
     */
    boolean allVolumesOnOntapManagedStorage(long vmId) {
        UserVm userVm = userVmDao.findById(vmId);
        if (userVm == null) {
            logger.debug("allVolumesOnOntapManagedStorage: VM with id [{}] not found", vmId);
            return false;
        }

        if (!Hypervisor.HypervisorType.KVM.equals(userVm.getHypervisorType())) {
            logger.debug("allVolumesOnOntapManagedStorage: ONTAP VM snapshot strategy only supports KVM hypervisor, VM [{}] uses [{}]",
                    vmId, userVm.getHypervisorType());
            return false;
        }

        // ONTAP VM snapshots work for both Running and Stopped VMs.
        // Running VMs may be frozen/thawed (if quiesce is requested).
        // Stopped VMs don't need freeze/thaw - just take the FlexVol snapshot directly.
        VirtualMachine.State vmState = userVm.getState();
        if (!VirtualMachine.State.Running.equals(vmState) && !VirtualMachine.State.Stopped.equals(vmState)) {
            logger.info("allVolumesOnOntapManagedStorage: ONTAP VM snapshot strategy requires VM to be Running or Stopped, VM [{}] is in state [{}], returning false",
                    vmId, vmState);
            return false;
        }

        List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
        if (volumes == null || volumes.isEmpty()) {
            logger.debug("allVolumesOnOntapManagedStorage: No volumes found for VM [{}]", vmId);
            return false;
        }

        for (VolumeVO volume : volumes) {
            if (volume.getPoolId() == null) {
                return false;
            }
            StoragePoolVO pool = storagePool.findById(volume.getPoolId());
            if (pool == null) {
                return false;
            }
            if (!pool.isManaged()) {
                logger.debug("allVolumesOnOntapManagedStorage: Volume [{}] is on non-managed storage pool [{}], not ONTAP",
                        volume.getId(), pool.getName());
                return false;
            }
            if (!DataStoreProvider.ONTAP_PLUGIN_NAME.equals(pool.getStorageProviderName())) {
                logger.debug("allVolumesOnOntapManagedStorage: Volume [{}] is on managed pool [{}] with provider [{}], not ONTAP",
                        volume.getId(), pool.getName(), pool.getStorageProviderName());
                return false;
            }
        }

        logger.debug("allVolumesOnOntapManagedStorage: All volumes of VM [{}] are on ONTAP managed storage, this strategy can handle", vmId);
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Take VM Snapshot (FlexVolume-level)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Takes a VM-level snapshot by freezing the VM, creating ONTAP FlexVolume-level
     * snapshots (one per unique FlexVolume), and then thawing the VM.
     *
     * <p>Volumes are grouped by their parent FlexVolume UUID (from storage pool details).
     * For each unique FlexVolume, exactly one ONTAP snapshot is created via
     * {@code POST /api/storage/volumes/{uuid}/snapshots}. This means if a VM has
     * ROOT and DATA disks on the same FlexVolume, only one snapshot is created.</p>
     *
     * <p><b>Memory Snapshots Not Supported:</b> This strategy only supports disk-only
     * (crash-consistent) snapshots. Memory snapshots (snapshotmemory=true) are rejected
     * with a clear error message. This is because ONTAP FlexVolume snapshots capture disk
     * state only, and allowing mixed snapshot chains (ONTAP disk + libvirt memory) would
     * cause issues during revert operations.</p>
     *
     * @throws CloudRuntimeException if memory snapshot is requested
     */
    @Override
    public VMSnapshot takeVMSnapshot(VMSnapshot vmSnapshot) {
        Long hostId = vmSnapshotHelper.pickRunningHost(vmSnapshot.getVmId());
        UserVm userVm = userVmDao.findById(vmSnapshot.getVmId());
        VMSnapshotVO vmSnapshotVO = (VMSnapshotVO) vmSnapshot;

        // Transition to Creating state FIRST - this is required so that the finally block
        // can properly transition to Error state via OperationFailed event if anything fails.
        // (OperationFailed can only transition FROM Creating state, not from Allocated)
        try {
            vmSnapshotHelper.vmSnapshotStateTransitTo(vmSnapshotVO, VMSnapshot.Event.CreateRequested);
        } catch (NoTransitionException e) {
            throw new CloudRuntimeException(e.getMessage());
        }

        FreezeThawVMAnswer freezeAnswer = null;
        FreezeThawVMCommand thawCmd = null;
        FreezeThawVMAnswer thawAnswer = null;
        long startFreeze = 0;

        // Track which FlexVolume snapshots were created (for rollback)
        List<FlexVolSnapshotDetail> createdSnapshots = new ArrayList<>();

        boolean result = false;
        try {
            GuestOSVO guestOS = guestOSDao.findById(userVm.getGuestOSId());
            List<VolumeObjectTO> volumeTOs = vmSnapshotHelper.getVolumeTOList(userVm.getId());

            long prevChainSize = 0;
            long virtualSize = 0;

            // Build snapshot parent chain
            VMSnapshotTO current = null;
            VMSnapshotVO currentSnapshot = vmSnapshotDao.findCurrentSnapshotByVmId(userVm.getId());
            if (currentSnapshot != null) {
                current = vmSnapshotHelper.getSnapshotWithParents(currentSnapshot);
            }

            // Respect the user's quiesce option from the VM snapshot request
            boolean quiesceVm = true; // default to true for safety
            VMSnapshotOptions options = vmSnapshotVO.getOptions();
            if (options != null) {
                quiesceVm = options.needQuiesceVM();
            }

            // Check if VM is actually running - freeze/thaw only makes sense for running VMs
            boolean vmIsRunning = VirtualMachine.State.Running.equals(userVm.getState());
            boolean shouldFreezeThaw = quiesceVm && vmIsRunning;

            if (!vmIsRunning) {
                logger.info("takeVMSnapshot: VM [{}] is in state [{}] (not Running). Skipping freeze/thaw - " +
                        "FlexVolume snapshot will be taken directly.", userVm.getInstanceName(), userVm.getState());
            } else if (quiesceVm) {
                logger.info("takeVMSnapshot: Quiesce option is enabled for ONTAP VM Snapshot of VM [{}]. " +
                        "VM file systems will be frozen/thawed for application-consistent snapshots.", userVm.getInstanceName());
            } else {
                logger.info("takeVMSnapshot: Quiesce option is disabled for ONTAP VM Snapshot of VM [{}]. " +
                        "Snapshots will be crash-consistent only.", userVm.getInstanceName());
            }

            VMSnapshotTO target = new VMSnapshotTO(vmSnapshot.getId(), vmSnapshot.getName(),
                    vmSnapshot.getType(), null, vmSnapshot.getDescription(), false, current, quiesceVm);

            if (current == null) {
                vmSnapshotVO.setParent(null);
            } else {
                vmSnapshotVO.setParent(current.getId());
            }

            CreateVMSnapshotCommand ccmd = new CreateVMSnapshotCommand(
                    userVm.getInstanceName(), userVm.getUuid(), target, volumeTOs, guestOS.getDisplayName());

            logger.info("takeVMSnapshot: Creating ONTAP FlexVolume VM Snapshot for VM [{}] with quiesce={}", userVm.getInstanceName(), quiesceVm);

            // Prepare volume info list and calculate sizes
            for (VolumeObjectTO volumeObjectTO : volumeTOs) {
                virtualSize += volumeObjectTO.getSize();
                VolumeVO volumeVO = volumeDao.findById(volumeObjectTO.getId());
                prevChainSize += volumeVO.getVmSnapshotChainSize() == null ? 0 : volumeVO.getVmSnapshotChainSize();
            }

            // ── Group volumes by FlexVolume UUID ──
            Map<String, FlexVolGroupInfo> flexVolGroups = groupVolumesByFlexVol(volumeTOs);


            // ── Step 1: Freeze the VM (only if quiescing is requested AND VM is running) ──
            if (shouldFreezeThaw) {
                FreezeThawVMCommand freezeCommand = new FreezeThawVMCommand(userVm.getInstanceName());
                freezeCommand.setOption(FreezeThawVMCommand.FREEZE);
                freezeAnswer = (FreezeThawVMAnswer) agentMgr.send(hostId, freezeCommand);
                startFreeze = System.nanoTime();

                thawCmd = new FreezeThawVMCommand(userVm.getInstanceName());
                thawCmd.setOption(FreezeThawVMCommand.THAW);

                if (freezeAnswer == null || !freezeAnswer.getResult()) {
                    String detail = (freezeAnswer != null) ? freezeAnswer.getDetails() : "no response from agent";
                    throw new CloudRuntimeException("Could not freeze VM [" + userVm.getInstanceName() +
                            "] for ONTAP snapshot. Ensure qemu-guest-agent is installed and running. Details: " + detail);
                }

                logger.info("takeVMSnapshot: VM [{}] frozen successfully via QEMU guest agent", userVm.getInstanceName());
            } else {
                logger.info("takeVMSnapshot: Skipping VM freeze for VM [{}] (quiesce={}, vmIsRunning={})",
                        userVm.getInstanceName(), quiesceVm, vmIsRunning);
            }

            // ── Step 2: Create clone-backed VM snapshot entries ──
            try {
                String snapshotNameBase = buildSnapshotName(vmSnapshot);

                for (Map.Entry<String, FlexVolGroupInfo> entry : flexVolGroups.entrySet()) {
                    String flexVolUuid = entry.getKey();
                    FlexVolGroupInfo groupInfo = entry.getValue();
                    long startSnapshot = System.nanoTime();

                    // Build storage strategy from pool details to get the feign client
                    StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(groupInfo.poolDetails);
                    String authHeader = storageStrategy.getAuthHeader();
                    String protocol = groupInfo.poolDetails.get(OntapStorageConstants.PROTOCOL);

                    // Create one clone per CloudStack volume and persist detail for protocol-specific revert.
                    for (Long volumeId : groupInfo.volumeIds) {
                        String volumePath = resolveVolumePathOnOntap(volumeId, protocol, groupInfo.poolDetails);
                        String cloneName = buildPerVolumeCloneName(snapshotNameBase, vmSnapshot.getId(), volumeId);
                        String cloneUuid = cloneName;
                        if (ProtocolType.NFS3.name().equalsIgnoreCase(protocol)) {
                            org.apache.cloudstack.storage.feign.model.FileCloneRequest cloneRequest = new org.apache.cloudstack.storage.feign.model.FileCloneRequest();
                            org.apache.cloudstack.storage.feign.model.FileCloneRequest.VolumeRef volumeRef = new org.apache.cloudstack.storage.feign.model.FileCloneRequest.VolumeRef();
                            volumeRef.setUuid(flexVolUuid);
                            volumeRef.setName(groupInfo.poolDetails.get(OntapStorageConstants.VOLUME_NAME));
                            cloneRequest.setVolume(volumeRef);
                            cloneRequest.setSourcePath(volumePath);
                            cloneRequest.setDestinationPath(cloneName);
                            cloneRequest.setIsOverride(Boolean.FALSE);
                            JobResponse fileJobResponse = storageStrategy.getNasFeignClient().cloneFile(authHeader, cloneRequest);
                            if (fileJobResponse == null || fileJobResponse.getJob() == null) {
                                throw new CloudRuntimeException("Failed to submit clone-backed VM snapshot for volume " + volumeId);
                            }
                            Boolean jobSucceeded = storageStrategy.jobPollForSuccess(fileJobResponse.getJob().getUuid(), 30, 2000);
                            if (!jobSucceeded) {
                                throw new CloudRuntimeException("Clone-backed VM snapshot job failed for volume " + volumeId);
                            }
                        } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
                            VolumeDetailVO lunDetail = volumeDetailsDao.findDetail(volumeId, OntapStorageConstants.LUN_DOT_UUID);
                            String sourceLunUuid = lunDetail != null ? lunDetail.getValue() : null;
                            if (sourceLunUuid == null || sourceLunUuid.isEmpty()) {
                                throw new CloudRuntimeException("Source LUN UUID missing for volume " + volumeId);
                            }
                            if (volumePath == null || volumePath.isEmpty()) {
                                throw new CloudRuntimeException("Source LUN path is missing for volume " + volumeId);
                            }
                            if (!volumePath.startsWith(OntapStorageConstants.VOLUME_PATH_PREFIX)) {
                                throw new CloudRuntimeException("Invalid source LUN path (must start with " +
                                        OntapStorageConstants.VOLUME_PATH_PREFIX + "): " + volumePath);
                            }
                            String cloneLunPath = OntapStorageUtils.getLunName(
                                    groupInfo.poolDetails.get(OntapStorageConstants.VOLUME_NAME), cloneName);
                            if (!cloneLunPath.startsWith(OntapStorageConstants.VOLUME_PATH_PREFIX)) {
                                throw new CloudRuntimeException("Invalid iSCSI clone LUN path generated: " + cloneLunPath);
                            }
                            String svmName = groupInfo.poolDetails.get(OntapStorageConstants.SVM_NAME);
                            String flexVolName = groupInfo.poolDetails.get(OntapStorageConstants.VOLUME_NAME);
                            if (svmName == null || svmName.isEmpty()) {
                                throw new CloudRuntimeException("SVM name is mandatory for iSCSI clone request");
                            }
                            if (flexVolName == null || flexVolName.isEmpty()) {
                                throw new CloudRuntimeException("FlexVolume name is mandatory for iSCSI clone request");
                            }
                            org.apache.cloudstack.storage.feign.model.Lun cloneRequest = new org.apache.cloudstack.storage.feign.model.Lun();
                            cloneRequest.setName(cloneLunPath);
                            org.apache.cloudstack.storage.feign.model.Svm svm = new org.apache.cloudstack.storage.feign.model.Svm();
                            svm.setName(svmName);
                            cloneRequest.setSvm(svm);
                            org.apache.cloudstack.storage.feign.model.Lun.Location location = new org.apache.cloudstack.storage.feign.model.Lun.Location();
                            org.apache.cloudstack.storage.feign.model.Lun.LocationVolume locationVolume = new org.apache.cloudstack.storage.feign.model.Lun.LocationVolume();
                            locationVolume.setName(flexVolName);
                            location.setVolume(locationVolume);
                            cloneRequest.setLocation(location);
                            org.apache.cloudstack.storage.feign.model.Lun.Clone clone = new org.apache.cloudstack.storage.feign.model.Lun.Clone();
                            org.apache.cloudstack.storage.feign.model.Lun.Source source = new org.apache.cloudstack.storage.feign.model.Lun.Source();
                            source.setName(volumePath);
                            source.setUuid(sourceLunUuid);
                            clone.setSource(source);
                            cloneRequest.setClone(clone);
                            logger.info("CloneRequest: {}", cloneRequest);
                            OntapResponse<Lun> createCloneResponse = storageStrategy.getSanFeignClient().createLun(authHeader, true, cloneRequest);
                            if (createCloneResponse == null || createCloneResponse.getRecords() == null || createCloneResponse.getRecords().isEmpty()) {
                                throw new CloudRuntimeException("Failed to create iSCSI clone LUN for volume " + volumeId);
                            }
                            cloneUuid = createCloneResponse.getRecords().get(0).getUuid();
                            if (cloneUuid == null || cloneUuid.isEmpty()) {
                                cloneUuid = resolveLunUuid(storageStrategy, authHeader, svmName, cloneLunPath);
                            }
                        } else {
                            throw new CloudRuntimeException("Unsupported protocol for VM snapshot clone: " + protocol);
                        }
                        FlexVolSnapshotDetail detail = new FlexVolSnapshotDetail(
                                flexVolUuid, cloneUuid, cloneName, volumePath, groupInfo.poolId, protocol);
                        createdSnapshots.add(detail);
                    }

                    logger.info("takeVMSnapshot: Clone-backed VM snapshot [{}] on FlexVol [{}] completed in {} ms. Covers volumes: {}",
                            snapshotNameBase, flexVolUuid,
                            TimeUnit.MILLISECONDS.convert(System.nanoTime() - startSnapshot, TimeUnit.NANOSECONDS),
                            groupInfo.volumeIds);
                }
            } finally {
                // ── Step 3: Thaw the VM (only if it was frozen, always even on error) ──
                if (quiesceVm && freezeAnswer != null && freezeAnswer.getResult()) {
                    try {
                        thawAnswer = (FreezeThawVMAnswer) agentMgr.send(hostId, thawCmd);
                        if (thawAnswer != null && thawAnswer.getResult()) {
                            logger.info("takeVMSnapshot: VM [{}] thawed successfully. Total freeze duration: {} ms",
                                    userVm.getInstanceName(),
                                    TimeUnit.MILLISECONDS.convert(System.nanoTime() - startFreeze, TimeUnit.NANOSECONDS));
                        } else {
                            logger.warn("takeVMSnapshot: Failed to thaw VM [{}]: {}", userVm.getInstanceName(),
                                    (thawAnswer != null) ? thawAnswer.getDetails() : "no response");
                        }
                    } catch (Exception thawEx) {
                        logger.error("takeVMSnapshot: Exception while thawing VM [{}]: {}", userVm.getInstanceName(), thawEx.getMessage(), thawEx);
                    }
                }
            }

            // ── Step 4: Persist FlexVolume snapshot details (one row per CloudStack volume) ──
            for (FlexVolSnapshotDetail detail : createdSnapshots) {
                vmSnapshotDetailsDao.persist(new VMSnapshotDetailsVO(
                        vmSnapshot.getId(), OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT, detail.toString(), true));
            }

            // ── Step 5: Finalize via parent processAnswer ──
            CreateVMSnapshotAnswer answer = new CreateVMSnapshotAnswer(ccmd, true, "");
            answer.setVolumeTOs(volumeTOs);

            processAnswer(vmSnapshotVO, userVm, answer, null);
            logger.info("takeVMSnapshot: ONTAP FlexVolume VM Snapshot [{}] created successfully for VM [{}] ({} FlexVol snapshot(s))",
                    vmSnapshot.getName(), userVm.getInstanceName(), createdSnapshots.size());

            long newChainSize = 0;
            for (VolumeObjectTO volumeTo : answer.getVolumeTOs()) {
                publishUsageEvent(EventTypes.EVENT_VM_SNAPSHOT_CREATE, vmSnapshot, userVm, volumeTo);
                newChainSize += volumeTo.getSize();
            }
            publishUsageEvent(EventTypes.EVENT_VM_SNAPSHOT_ON_PRIMARY, vmSnapshot, userVm,
                    newChainSize - prevChainSize, virtualSize);

            result = true;
            return vmSnapshot;

        } catch (OperationTimedoutException e) {
            logger.error("takeVMSnapshot: ONTAP VM Snapshot [{}] timed out: {}", vmSnapshot.getName(), e.getMessage());
            throw new CloudRuntimeException("Creating Instance Snapshot: " + vmSnapshot.getName() + " timed out: " + e.getMessage());
        } catch (AgentUnavailableException e) {
            logger.error("takeVMSnapshot: ONTAP VM Snapshot [{}] failed, agent unavailable: {}", vmSnapshot.getName(), e.getMessage());
            throw new CloudRuntimeException("Creating Instance Snapshot: " + vmSnapshot.getName() + " failed: " + e.getMessage());
        } catch (Exception e) {
            logger.error("takeVMSnapshot: ONTAP VM Snapshot [{}] failed, with exception: {}", vmSnapshot.getName(), e.getMessage());
            throw new CloudRuntimeException("Creating Instance Snapshot: " + vmSnapshot.getName() + " failed: " + e.getMessage());
        }
         finally {
            if (!result) {
                // Rollback all FlexVolume snapshots created so far (deduplicate by FlexVol+Snapshot)
                Map<String, Boolean> rolledBack = new HashMap<>();
                for (FlexVolSnapshotDetail detail : createdSnapshots) {
                    String dedupeKey = detail.flexVolUuid + "::" + detail.snapshotUuid;
                    if (!rolledBack.containsKey(dedupeKey)) {
                        try {
                            rollbackFlexVolSnapshot(detail);
                            rolledBack.put(dedupeKey, Boolean.TRUE);
                        } catch (Exception rollbackEx) {
                            logger.error("takeVMSnapshot: Failed to rollback FlexVol snapshot [{}] on FlexVol [{}]: {}",
                                    detail.snapshotUuid, detail.flexVolUuid, rollbackEx.getMessage());
                        }
                    }
                }

                // Ensure VM is thawed if we haven't done so
                if (thawAnswer == null && freezeAnswer != null && freezeAnswer.getResult()) {
                    try {
                        logger.info("takeVMSnapshot: Thawing VM [{}] during error cleanup", userVm.getInstanceName());
                        thawAnswer = (FreezeThawVMAnswer) agentMgr.send(hostId, thawCmd);
                    } catch (Exception ex) {
                        logger.error("takeVMSnapshot: Could not thaw VM during cleanup: {}", ex.getMessage());
                    }
                }

                // Clean up VM snapshot details and transition state
                try {
                    List<VMSnapshotDetailsVO> vmSnapshotDetails = vmSnapshotDetailsDao.listDetails(vmSnapshot.getId());
                    for (VMSnapshotDetailsVO detail : vmSnapshotDetails) {
                        if (OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT.equals(detail.getName())) {
                            vmSnapshotDetailsDao.remove(detail.getId());
                        }
                    }
                    vmSnapshotHelper.vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.OperationFailed);
                } catch (NoTransitionException e1) {
                    logger.error("takeVMSnapshot: Cannot set VM Snapshot state to OperationFailed: {}", e1.getMessage());
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delete VM Snapshot
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public boolean deleteVMSnapshot(VMSnapshot vmSnapshot) {
        VMSnapshotVO vmSnapshotVO = (VMSnapshotVO) vmSnapshot;
        UserVm userVm = userVmDao.findById(vmSnapshot.getVmId());

        try {
            vmSnapshotHelper.vmSnapshotStateTransitTo(vmSnapshotVO, VMSnapshot.Event.ExpungeRequested);
        } catch (NoTransitionException e) {
            throw new CloudRuntimeException(e.getMessage());
        }

        try {
            List<VolumeObjectTO> volumeTOs = vmSnapshotHelper.getVolumeTOList(userVm.getId());
            String vmInstanceName = userVm.getInstanceName();
            VMSnapshotTO parent = vmSnapshotHelper.getSnapshotWithParents(vmSnapshotVO).getParent();

            VMSnapshotTO vmSnapshotTO = new VMSnapshotTO(vmSnapshotVO.getId(), vmSnapshotVO.getName(), vmSnapshotVO.getType(),
                    vmSnapshotVO.getCreated().getTime(), vmSnapshotVO.getDescription(), vmSnapshotVO.getCurrent(), parent, true);
            GuestOSVO guestOS = guestOSDao.findById(userVm.getGuestOSId());
            DeleteVMSnapshotCommand deleteSnapshotCommand = new DeleteVMSnapshotCommand(vmInstanceName, vmSnapshotTO,
                    volumeTOs, guestOS.getDisplayName());

            // Check for FlexVolume snapshots (new approach)
            List<VMSnapshotDetailsVO> flexVolDetails = vmSnapshotDetailsDao.findDetails(vmSnapshot.getId(), OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT);
            if (CollectionUtils.isNotEmpty(flexVolDetails)) {
                deleteFlexVolSnapshots(flexVolDetails);
            }

            processAnswer(vmSnapshotVO, userVm, new DeleteVMSnapshotAnswer(deleteSnapshotCommand, volumeTOs), null);
            long fullChainSize = 0;
            for (VolumeObjectTO volumeTo : volumeTOs) {
                publishUsageEvent(EventTypes.EVENT_VM_SNAPSHOT_DELETE, vmSnapshot, userVm, volumeTo);
                fullChainSize += volumeTo.getSize();
            }
            publishUsageEvent(EventTypes.EVENT_VM_SNAPSHOT_OFF_PRIMARY, vmSnapshot, userVm, fullChainSize, 0L);
            return true;
        } catch (CloudRuntimeException err) {
            String errMsg = String.format("Delete of ONTAP VM Snapshot [%s] of VM [%s] failed: %s",
                    vmSnapshot.getName(), userVm.getInstanceName(), err.getMessage());
            logger.error(errMsg, err);
            throw new CloudRuntimeException(errMsg, err);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Revert VM Snapshot
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public boolean revertVMSnapshot(VMSnapshot vmSnapshot) {
        VMSnapshotVO vmSnapshotVO = (VMSnapshotVO) vmSnapshot;
        UserVm userVm = userVmDao.findById(vmSnapshot.getVmId());

        try {
            vmSnapshotHelper.vmSnapshotStateTransitTo(vmSnapshotVO, VMSnapshot.Event.RevertRequested);
        } catch (NoTransitionException e) {
            throw new CloudRuntimeException(e.getMessage());
        }

        boolean result = false;
        try {
            List<VolumeObjectTO> volumeTOs = vmSnapshotHelper.getVolumeTOList(userVm.getId());
            String vmInstanceName = userVm.getInstanceName();
            VMSnapshotTO parent = vmSnapshotHelper.getSnapshotWithParents(vmSnapshotVO).getParent();

            VMSnapshotTO vmSnapshotTO = new VMSnapshotTO(vmSnapshotVO.getId(), vmSnapshotVO.getName(), vmSnapshotVO.getType(),
                    vmSnapshotVO.getCreated().getTime(), vmSnapshotVO.getDescription(), vmSnapshotVO.getCurrent(), parent, true);
            GuestOSVO guestOS = guestOSDao.findById(userVm.getGuestOSId());
            RevertToVMSnapshotCommand revertToSnapshotCommand = new RevertToVMSnapshotCommand(vmInstanceName,
                    userVm.getUuid(), vmSnapshotTO, volumeTOs, guestOS.getDisplayName());

            // Revert clone-backed snapshot artifacts per volume:
            //  - NFS: cloneFile(source=clone, destination=live file, isOverride=true)
            //  - iSCSI: patch LUN (clone.source=clone LUN, destination=live LUN)
            List<VMSnapshotDetailsVO> cloneDetails = vmSnapshotDetailsDao.findDetails(vmSnapshot.getId(), OntapStorageConstants.ONTAP_FLEXVOL_SNAPSHOT);
            if (CollectionUtils.isNotEmpty(cloneDetails)) {
                revertCloneBackedSnapshots(cloneDetails);
            }

            RevertToVMSnapshotAnswer answer = new RevertToVMSnapshotAnswer(revertToSnapshotCommand, true, "");
            answer.setVolumeTOs(volumeTOs);
            processAnswer(vmSnapshotVO, userVm, answer, null);
            result = true;
        } catch (CloudRuntimeException e) {
            logger.error("revertVMSnapshot: Revert ONTAP VM Snapshot [{}] failed: {}", vmSnapshot.getName(), e.getMessage(), e);
            throw new CloudRuntimeException("Revert ONTAP VM Snapshot ["+ vmSnapshot.getName() +"] failed.");
        } finally {
            if (!result) {
                try {
                    vmSnapshotHelper.vmSnapshotStateTransitTo(vmSnapshot, VMSnapshot.Event.OperationFailed);
                } catch (NoTransitionException e1) {
                    logger.error("Cannot set Instance Snapshot state due to: " + e1.getMessage());
                }
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FlexVolume Snapshot Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Groups volumes by their parent FlexVolume UUID using storage pool details.
     *
     * @param volumeTOs list of volume transfer objects
     * @return map of FlexVolume UUID → group info (pool details, pool ID, volume IDs)
     */
    Map<String, FlexVolGroupInfo> groupVolumesByFlexVol(List<VolumeObjectTO> volumeTOs) {
        Map<String, FlexVolGroupInfo> groups = new HashMap<>();

        for (VolumeObjectTO volumeTO : volumeTOs) {
            VolumeVO volumeVO = volumeDao.findById(volumeTO.getId());
            if (volumeVO == null || volumeVO.getPoolId() == null) {
                throw new CloudRuntimeException("Volume [" + volumeTO.getId() + "] not found or has no pool assigned");
            }

            Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(volumeVO.getPoolId());
            String flexVolUuid = poolDetails.get(OntapStorageConstants.VOLUME_UUID);
            if (flexVolUuid == null || flexVolUuid.isEmpty()) {
                throw new CloudRuntimeException("FlexVolume UUID not found in pool details for pool [" + volumeVO.getPoolId() + "]");
            }

            FlexVolGroupInfo group = groups.get(flexVolUuid);
            if (group == null) {
                group = new FlexVolGroupInfo(poolDetails, volumeVO.getPoolId());
                groups.put(flexVolUuid, group);
            }
            group.volumeIds.add(volumeVO.getId());
        }

        return groups;
    }

    /**
     * Builds a deterministic, ONTAP-safe snapshot name for a VM snapshot.
     * Format: {@code vmsnap_<vmSnapshotId>_<timestamp>}
     */
    String buildSnapshotName(VMSnapshot vmSnapshot) {
        return OntapStorageUtils.getOntapCloneName(vmSnapshot.getName());
    }

    /**
     * Builds a deterministic per-volume clone name for VM snapshot workflows.
     * Keeps VM snapshot name as base while preventing collisions across ROOT/DATA volumes.
     */
    String buildPerVolumeCloneName(String snapshotNameBase, Long vmSnapshotId, Long volumeId) {
        return OntapStorageUtils.getOntapCloneName(snapshotNameBase + "_s" + vmSnapshotId + "_v" + volumeId);
    }

    String resolveLunUuid(StorageStrategy strategy, String authHeader, String svmName, String lunName) {
        OntapResponse<org.apache.cloudstack.storage.feign.model.Lun> response = strategy.getSanFeignClient()
                .getLunResponse(authHeader, Map.of(OntapStorageConstants.SVM_DOT_NAME, svmName, OntapStorageConstants.NAME, lunName));
        if (response == null || response.getRecords() == null || response.getRecords().isEmpty()) {
            throw new CloudRuntimeException("Could not resolve LUN UUID for clone " + lunName);
        }
        return response.getRecords().get(0).getUuid();
    }

    /**
     * Resolves the ONTAP-side path of a CloudStack volume within its FlexVolume.
     *
     * <ul>
     *   <li>For NFS volumes the path is the filename (e.g. {@code uuid.qcow2})
     *       retrieved via {@link VolumeVO#getPath()}.</li>
     *   <li>For iSCSI volumes the path is the LUN name within the FlexVolume
     *       (e.g. {@code /vol/vol1/lun_name}) stored in volume_details.</li>
     * </ul>
     *
     * @param volumeId   the CloudStack volume ID
     * @param protocol   the storage protocol (e.g. "NFS3", "ISCSI")
     * @param poolDetails storage pool detail map (used for fall-back lookups)
     * @return the volume path relative to the FlexVolume root
     */
    String resolveVolumePathOnOntap(Long volumeId, String protocol, Map<String, String> poolDetails) {
        if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
            // iSCSI – the LUN's ONTAP name is stored as a volume detail
            VolumeDetailVO lunDetail = volumeDetailsDao.findDetail(volumeId, OntapStorageConstants.LUN_DOT_NAME);
            if (lunDetail == null || lunDetail.getValue() == null || lunDetail.getValue().isEmpty()) {
                throw new CloudRuntimeException(
                        "LUN name (volume detail '" + OntapStorageConstants.LUN_DOT_NAME + "') not found for iSCSI volume [" + volumeId + "]");
            }
            return lunDetail.getValue();
        } else {
            // NFS – volumeVO.getPath() holds the file path (e.g. "uuid.qcow2")
            VolumeVO vol = volumeDao.findById(volumeId);
            if (vol == null || vol.getPath() == null || vol.getPath().isEmpty()) {
                throw new CloudRuntimeException("Volume path not found for NFS volume [" + volumeId + "]");
            }
            return vol.getPath();
        }
    }

    /**
     * Rolls back (deletes) a FlexVolume snapshot that was created during a failed takeVMSnapshot.
     */
    void rollbackFlexVolSnapshot(FlexVolSnapshotDetail detail) {
        try {
            Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(detail.poolId);
            StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails);
            String authHeader = storageStrategy.getAuthHeader();

            if (ProtocolType.NFS3.name().equalsIgnoreCase(detail.protocol)) {
                logger.info("rollbackFlexVolSnapshot: Deleting NFS clone file [{}] on FlexVol [{}]",
                        detail.snapshotName, detail.flexVolUuid);
                storageStrategy.getNasFeignClient().deleteFile(authHeader, detail.flexVolUuid, detail.snapshotName);
            } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(detail.protocol)) {
                logger.info("rollbackFlexVolSnapshot: Deleting iSCSI clone LUN [{}] (uuid={})",
                        detail.snapshotName, detail.snapshotUuid);
                String cloneUuid = detail.snapshotUuid;
                if (cloneUuid == null || cloneUuid.isEmpty()) {
                    String svmName = poolDetails.get(OntapStorageConstants.SVM_NAME);
                    String cloneLunPath = OntapStorageUtils.getLunName(poolDetails.get(OntapStorageConstants.VOLUME_NAME), detail.snapshotName);
                    cloneUuid = resolveLunUuid(storageStrategy, authHeader, svmName, cloneLunPath);
                }
                storageStrategy.getSanFeignClient().deleteLun(authHeader, cloneUuid, Map.of("allow_delete_while_mapped", "true"));
            }
        } catch (Exception e) {
            logger.error("rollbackFlexVolSnapshot: Rollback of FlexVol snapshot failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Deletes all FlexVolume snapshots associated with a VM snapshot.
     *
     * <p>Since there is one detail row per CloudStack volume, multiple rows may reference
     * the same FlexVol + snapshot combination. This method deduplicates to delete each
     * underlying ONTAP snapshot only once.</p>
     */
    void deleteFlexVolSnapshots(List<VMSnapshotDetailsVO> flexVolDetails) {
        // Track which FlexVol+Snapshot pairs have already been deleted
        Map<String, Boolean> deletedSnapshots = new HashMap<>();

        for (VMSnapshotDetailsVO detailVO : flexVolDetails) {
            FlexVolSnapshotDetail detail = FlexVolSnapshotDetail.parse(detailVO.getValue());
            String dedupeKey = detail.flexVolUuid + "::" + detail.snapshotUuid;

            // Only delete the ONTAP snapshot once per FlexVol+Snapshot pair
            if (!deletedSnapshots.containsKey(dedupeKey)) {
                Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(detail.poolId);
                StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails);
                String authHeader = storageStrategy.getAuthHeader();

                try {
                    if (ProtocolType.NFS3.name().equalsIgnoreCase(detail.protocol)) {
                        logger.info("deleteFlexVolSnapshots: Deleting NFS clone file [{}] on FlexVol [{}]",
                                detail.snapshotName, detail.flexVolUuid);
                        storageStrategy.getNasFeignClient().deleteFile(authHeader, detail.flexVolUuid, detail.snapshotName);
                    } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(detail.protocol)) {
                        logger.info("deleteFlexVolSnapshots: Deleting iSCSI clone LUN [{}] (uuid={})",
                                detail.snapshotName, detail.snapshotUuid);
                        String cloneUuid = detail.snapshotUuid;
                        if (cloneUuid == null || cloneUuid.isEmpty()) {
                            String svmName = poolDetails.get(OntapStorageConstants.SVM_NAME);
                            String cloneLunPath = OntapStorageUtils.getLunName(poolDetails.get(OntapStorageConstants.VOLUME_NAME), detail.snapshotName);
                            cloneUuid = resolveLunUuid(storageStrategy, authHeader, svmName, cloneLunPath);
                        }
                        storageStrategy.getSanFeignClient().deleteLun(authHeader, cloneUuid, Map.of("allow_delete_while_mapped", "true"));
                    }
                } catch (Exception e) {
                    if (isSnapshotAlreadyMissing(e)) {
                        logger.warn("deleteFlexVolSnapshots: Clone [{}] on FlexVol [{}] is already missing. " +
                                "Treating as success.", detail.snapshotName, detail.flexVolUuid);
                    } else {
                        throw e;
                    }
                }

                deletedSnapshots.put(dedupeKey, Boolean.TRUE);
                logger.info("deleteFlexVolSnapshots: Deleted clone [{}] on FlexVol [{}]", detail.snapshotName, detail.flexVolUuid);
            }

            // Always remove the DB detail row
            vmSnapshotDetailsDao.remove(detailVO.getId());
        }
    }

    private boolean isSnapshotAlreadyMissing(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("entry doesn't exist")
                || lower.contains("entry does not exist")
                || lower.contains("not found")
                || lower.contains("404");
    }

    /**
     * Reverts all volumes of a VM snapshot using clone-backed restore operations.
     *
     * <p>Each persisted detail row represents one volume and points to the clone artifact
     * created during VM snapshot creation. Revert copies from the clone artifact back to
     * the original volume object.</p>
     *
     * <ul>
     *   <li><b>NFS</b>: clone file from snapshot clone file path to original file path, with overwrite</li>
     *   <li><b>iSCSI</b>: patch destination LUN with clone source ({@code clone.source.name/uuid})</li>
     * </ul>
     */
    void revertCloneBackedSnapshots(List<VMSnapshotDetailsVO> cloneDetails) {
        for (VMSnapshotDetailsVO detailVO : cloneDetails) {
            FlexVolSnapshotDetail detail = FlexVolSnapshotDetail.parse(detailVO.getValue());

            if (detail.volumePath == null || detail.volumePath.isEmpty()) {
                // Legacy detail row without volumePath – cannot do single-file restore
                logger.warn("revertCloneBackedSnapshots: Snapshot detail for FlexVol [{}] has no volumePath (legacy format). " +
                        "Skipping single-file restore for this entry.", detail.flexVolUuid);
                continue;
            }

            Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(detail.poolId);
            StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails);
            String flexVolName = poolDetails.get(OntapStorageConstants.VOLUME_NAME);
            if (flexVolName == null || flexVolName.isEmpty()) {
                throw new CloudRuntimeException("FlexVolume name not found in pool details for pool [" + detail.poolId + "]");
            }

            logger.info("revertCloneBackedSnapshots: Reverting volume [{}] using clone source [{}] on FlexVol [{}] (protocol={})",
                    detail.volumePath, detail.snapshotName, flexVolName, detail.protocol);
            String lunUuid = ProtocolType.ISCSI.name().equalsIgnoreCase(detail.protocol) ? detail.snapshotUuid : null;
            JobResponse jobResponse = storageStrategy.revertSnapshotForCloudStackVolume(
                    detail.snapshotName, detail.flexVolUuid, detail.snapshotUuid, detail.volumePath, lunUuid, flexVolName);

            if (jobResponse != null && jobResponse.getJob() != null) {
                Boolean success = storageStrategy.jobPollForSuccess(jobResponse.getJob().getUuid(), 60, 2000);
                if (!success) {
                    throw new CloudRuntimeException("Clone-backed revert failed for volume path [" +
                            detail.volumePath + "] from clone [" + detail.snapshotName +
                            "] on FlexVol [" + flexVolName + "]");
                }
            }

            logger.info("revertCloneBackedSnapshots: Successfully reverted volume [{}] from clone [{}] on FlexVol [{}]",
                    detail.volumePath, detail.snapshotName, flexVolName);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inner classes for grouping & detail tracking
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Groups information about volumes that share the same FlexVolume.
     */
    static class FlexVolGroupInfo {
        final Map<String, String> poolDetails;
        final long poolId;
        final List<Long> volumeIds = new ArrayList<>();

        FlexVolGroupInfo(Map<String, String> poolDetails, long poolId) {
            this.poolDetails = poolDetails;
            this.poolId = poolId;
        }
    }

    /**
     * Holds the metadata for a single volume's FlexVolume snapshot entry (used during create and for
     * serialization/deserialization to/from vm_snapshot_details).
     *
     * <p>One row is persisted per CloudStack volume. Multiple volumes may share the same
     * FlexVol snapshot (if they reside on the same FlexVolume).</p>
     *
     * <p>Serialized format: {@code "<flexVolUuid>::<snapshotUuid>::<snapshotName>::<volumePath>::<poolId>::<protocol>"}</p>
     */
    static class FlexVolSnapshotDetail {
        final String flexVolUuid;
        final String snapshotUuid;
        final String snapshotName;
        /** The ONTAP-side path of the file or LUN within the FlexVolume (e.g. "uuid.qcow2" for NFS, "/vol/vol1/lun1" for iSCSI). */
        final String volumePath;
        final long poolId;
        /** Storage protocol: NFS3, ISCSI, etc. */
        final String protocol;

        FlexVolSnapshotDetail(String flexVolUuid, String snapshotUuid, String snapshotName,
                              String volumePath, long poolId, String protocol) {
            this.flexVolUuid = flexVolUuid;
            this.snapshotUuid = snapshotUuid;
            this.snapshotName = snapshotName;
            this.volumePath = volumePath;
            this.poolId = poolId;
            this.protocol = protocol;
        }

        /**
         * Parses a vm_snapshot_details value string back into a FlexVolSnapshotDetail.
         */
        static FlexVolSnapshotDetail parse(String value) {
            String[] parts = value.split(DETAIL_SEPARATOR);
            if (parts.length == 4) {
                // Legacy format without volumePath and protocol: flexVolUuid::snapshotUuid::snapshotName::poolId
                return new FlexVolSnapshotDetail(parts[0], parts[1], parts[2], null, Long.parseLong(parts[3]), null);
            }
            if (parts.length != 6) {
                throw new CloudRuntimeException("Invalid ONTAP FlexVol snapshot detail format: " + value);
            }
            return new FlexVolSnapshotDetail(parts[0], parts[1], parts[2], parts[3], Long.parseLong(parts[4]), parts[5]);
        }

        @Override
        public String toString() {
            return flexVolUuid + DETAIL_SEPARATOR + snapshotUuid + DETAIL_SEPARATOR + snapshotName +
                    DETAIL_SEPARATOR + volumePath + DETAIL_SEPARATOR + poolId + DETAIL_SEPARATOR + protocol;
        }
    }
}
