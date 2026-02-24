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
package org.apache.cloudstack.storage.driver;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.service.SANStrategy;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.UnifiedSANStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Primary datastore driver for NetApp ONTAP storage systems.
 * Handles volume lifecycle operations for iSCSI and NFS protocols.
 */
public class OntapPrimaryDatastoreDriver implements PrimaryDataStoreDriver {

    private static final Logger s_logger = LogManager.getLogger(OntapPrimaryDatastoreDriver.class);

    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject private PrimaryDataStoreDao storagePoolDao;
    @Inject private VMInstanceDao vmDao;
    @Inject private VolumeDao volumeDao;
    @Inject private VolumeDetailsDao volumeDetailsDao;
    @Inject private SnapshotDetailsDao snapshotDetailsDao;
    @Override
    public Map<String, String> getCapabilities() {
        s_logger.trace("OntapPrimaryDatastoreDriver: getCapabilities: Called");
        Map<String, String> mapCapabilities = new HashMap<>();
        // RAW managed initial implementation: snapshot features not yet supported
        // TODO Set it to false once we start supporting snapshot feature
        mapCapabilities.put(DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT.toString(), Boolean.TRUE.toString());
        mapCapabilities.put(DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_SNAPSHOT.toString(), Boolean.TRUE.toString());
        return mapCapabilities;
    }

    @Override
    public DataTO getTO(DataObject data) {
        return null;
    }

    @Override
    public DataStoreTO getStoreTO(DataStore store) {
        return null;
    }

    /**
     * Creates a volume on the ONTAP storage system.
     */
    @Override
    public void createAsync(DataStore dataStore, DataObject dataObject, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult createCmdResult = null;
        String errMsg;

        if (dataObject == null) {
            throw new InvalidParameterValueException("createAsync: dataObject should not be null");
        }
        if (dataStore == null) {
            throw new InvalidParameterValueException("createAsync: dataStore should not be null");
        }
        try {
            s_logger.info("createAsync: Started for data store name [{}] and data object name [{}] of type [{}]",
                    dataStore.getName(), dataObject.getName(), dataObject.getType());

            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                s_logger.error("createAsync: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("createAsync: Storage Pool not found for id: " + dataStore.getId());
            }
            String storagePoolUuid = dataStore.getUuid();

            Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(dataStore.getId());

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeInfo volInfo = (VolumeInfo) dataObject;

                // Create the backend storage object (LUN for iSCSI, no-op for NFS)
                CloudStackVolume created = createCloudStackVolume(dataStore, volInfo, details);

                // Update CloudStack volume record with storage pool association and protocol-specific details
                VolumeVO volumeVO = volumeDao.findById(volInfo.getId());
                if (volumeVO != null) {
                    volumeVO.setPoolType(storagePool.getPoolType());
                    volumeVO.setPoolId(storagePool.getId());

                    if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                        String svmName = details.get(Constants.SVM_NAME);
                        String lunName = created != null && created.getLun() != null ? created.getLun().getName() : null;
                        if (lunName == null) {
                            throw new CloudRuntimeException("createAsync: Missing LUN name for volume " + volInfo.getId());
                        }

                        // Persist LUN details for future operations (delete, grant/revoke access)
                        volumeDetailsDao.addDetail(volInfo.getId(), Constants.LUN_DOT_UUID, created.getLun().getUuid(), false);
                        volumeDetailsDao.addDetail(volInfo.getId(), Constants.LUN_DOT_NAME, lunName, false);
                        if (created.getLun().getUuid() != null) {
                            volumeVO.setFolder(created.getLun().getUuid());
                        }

                        // Create LUN-to-igroup mapping and retrieve the assigned LUN ID
                        UnifiedSANStrategy sanStrategy = (UnifiedSANStrategy) Utility.getStrategyByStoragePoolDetails(details);
                        String accessGroupName = Utility.getIgroupName(svmName, storagePoolUuid);
                        String lunNumber = sanStrategy.ensureLunMapped(svmName, lunName, accessGroupName);

                        // Construct iSCSI path: /<iqn>/<lun_id> format for KVM/libvirt attachment
                        String iscsiPath = Constants.SLASH + storagePool.getPath() + Constants.SLASH + lunNumber;
                        volumeVO.set_iScsiName(iscsiPath);
                        volumeVO.setPath(iscsiPath);
                        s_logger.info("createAsync: Volume [{}] iSCSI path set to {}", volumeVO.getId(), iscsiPath);
                        createCmdResult = new CreateCmdResult(null, new Answer(null, true, null));

                    } else if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                        createCmdResult = new CreateCmdResult(volInfo.getUuid(), new Answer(null, true, null));
                        s_logger.info("createAsync: Managed NFS volume [{}] associated with pool {}",
                                volumeVO.getId(), storagePool.getId());
                    }
                    volumeDao.update(volumeVO.getId(), volumeVO);
                }
            } else if (dataObject.getType() == DataObjectType.SNAPSHOT) {
                createTempVolume((SnapshotInfo)dataObject, dataStore.getId());
                // No-op: ONTAP's takeSnapshot() already creates a LUN clone that is directly accessible.
                // The framework calls createAsync(SNAPSHOT) via createVolumeFromSnapshot/deleteVolumeFromSnapshot,
                // but ONTAP doesn't need a separate temp volume — the cloned LUN is used as-is.
                s_logger.info("createAsync: SNAPSHOT type — no-op for ONTAP (LUN clone already exists from takeSnapshot)");
                createCmdResult = new CreateCmdResult(null, new Answer(null, true, null));
            } else {
                errMsg = "Invalid DataObjectType (" + dataObject.getType() + ") passed to createAsync";
                s_logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
        } catch (Exception e) {
            errMsg = e.getMessage();
            s_logger.error("createAsync: Failed for dataObject name [{}]: {}", dataObject.getName(), errMsg);
            createCmdResult = new CreateCmdResult(null, new Answer(null, false, errMsg));
            createCmdResult.setResult(e.toString());
        } finally {
            if (createCmdResult != null && createCmdResult.isSuccess()) {
                s_logger.info("createAsync: Operation completed successfully for {}", dataObject.getType());
            }
            if(callback != null) {
                callback.complete(createCmdResult);
            }
        }
    }

    private void createTempVolume(SnapshotInfo snapshotInfo, long storagePoolId) {
        s_logger.info("createTempVolume: Called for snapshot [{}] in storage pool [{}]", snapshotInfo.getSnapshotId(), storagePoolId);

        // The framework (StorageSystemDataMotionStrategy.handleSnapshotDetails) sets key "tempVolume"
        // with value "create" or "delete" — NOT Constants.ONTAP_SNAP_ID which holds the LUN UUID.
        String tempVolumeKey = "tempVolume";
        SnapshotDetailsVO snapshotDetails = snapshotDetailsDao.findDetail(snapshotInfo.getSnapshotId(), tempVolumeKey);

        if (snapshotDetails == null || snapshotDetails.getValue() == null) {
            s_logger.info("createTempVolume: No '{}' detail found for snapshot [{}], nothing to do", tempVolumeKey, snapshotInfo.getSnapshotId());
            return;
        }

        String action = snapshotDetails.getValue();

        if (Constants.CREATE.equalsIgnoreCase(action)) {
            // ONTAP's takeSnapshot() already created a LUN clone that is directly accessible.
            // No additional temp volume creation is needed — the clone is used as-is.
            s_logger.info("createTempVolume: 'create' signal — no-op for ONTAP (LUN clone already exists from takeSnapshot) for snapshot [{}]",
                    snapshotInfo.getSnapshotId());
        } else if (Constants.DELETE.equalsIgnoreCase(action)) {
            // Clean up: delete the cloned LUN and remove ONTAP-specific snapshot_details entries
            s_logger.info("createTempVolume: 'delete' signal — cleaning up cloned LUN and snapshot details for snapshot [{}]",
                    snapshotInfo.getSnapshotId());

            deleteSnapshotClone(snapshotInfo, snapshotInfo.getDataStore());

            // Remove ONTAP-specific details that were stored during takeSnapshot()
            removeSnapshotDetailIfPresent(snapshotInfo.getSnapshotId(), Constants.SRC_CS_VOLUME_ID);
            removeSnapshotDetailIfPresent(snapshotInfo.getSnapshotId(), Constants.BASE_ONTAP_FV_ID);
            removeSnapshotDetailIfPresent(snapshotInfo.getSnapshotId(), Constants.PRIMARY_POOL_ID);
        } else {
            s_logger.warn("createTempVolume: Unexpected tempVolume value '{}' for snapshot [{}], ignoring", action, snapshotInfo.getSnapshotId());
        }
    }

    /**
     * Safely removes a snapshot detail if it exists, avoiding NPE.
     */
    private void removeSnapshotDetailIfPresent(long snapshotId, String detailKey) {
        SnapshotDetailsVO detail = snapshotDetailsDao.findDetail(snapshotId, detailKey);
        if (detail != null) {
            snapshotDetailsDao.remove(detail.getId());
        }
    }

    /**
     * Creates a volume on the ONTAP backend.
     */
    private CloudStackVolume createCloudStackVolume(DataStore dataStore, DataObject dataObject, Map<String, String> details) {
        StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
        if (storagePool == null) {
            s_logger.error("createCloudStackVolume: Storage Pool not found for id: {}", dataStore.getId());
            throw new CloudRuntimeException("createCloudStackVolume: Storage Pool not found for id: " + dataStore.getId());
        }

        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);

        if (dataObject.getType() == DataObjectType.VOLUME) {
            VolumeInfo volumeObject = (VolumeInfo) dataObject;
            CloudStackVolume cloudStackVolumeRequest = Utility.createCloudStackVolumeRequestByProtocol(storagePool, details, volumeObject);
            return storageStrategy.createCloudStackVolume(cloudStackVolumeRequest);
        } else {
            throw new CloudRuntimeException("createCloudStackVolume: Unsupported DataObjectType: " + dataObject.getType());
        }
    }

    /**
     * Deletes a volume from the ONTAP storage system.
     */
    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {
        CommandResult commandResult = new CommandResult();
        try {
            if (store == null || data == null) {
                throw new CloudRuntimeException("deleteAsync: store or data is null");
            }

            if (data.getType() == DataObjectType.VOLUME) {
                StoragePoolVO storagePool = storagePoolDao.findById(store.getId());
                if (storagePool == null) {
                    s_logger.error("deleteAsync: Storage Pool not found for id: " + store.getId());
                    throw new CloudRuntimeException("deleteAsync: Storage Pool not found for id: " + store.getId());
                }
                Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(store.getId());
                StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
                s_logger.info("createCloudStackVolumeForTypeVolume: Connection to Ontap SVM [{}] successful, preparing CloudStackVolumeRequest", details.get(Constants.SVM_NAME));
                VolumeInfo volumeInfo = (VolumeInfo) data;
                CloudStackVolume cloudStackVolumeRequest = createDeleteCloudStackVolumeRequest(storagePool,details,volumeInfo);
                storageStrategy.deleteCloudStackVolume(cloudStackVolumeRequest);
                s_logger.error("deleteAsync : Volume deleted: " + volumeInfo.getId());
                commandResult.setResult(null);
                commandResult.setSuccess(true);
            } else if (data.getType() == DataObjectType.SNAPSHOT) {
                s_logger.info("deleteAsync: SNAPSHOT type — no-op for ONTAP (LUN clone already exists from takeSnapshot)");
                //deleteSnapshotClone((SnapshotInfo) data, store);
                commandResult.setResult(null);
                commandResult.setSuccess(true);
            }
        } catch (Exception e) {
            s_logger.error("deleteAsync: Failed for data object [{}]: {}", data, e.getMessage());
            commandResult.setSuccess(false);
            commandResult.setResult(e.getMessage());
        } finally {
            callback.complete(commandResult);
        }
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, AsyncCompletionCallback<CopyCommandResult> callback) {
        throw new UnsupportedOperationException("copyAsync not supported by ONTAP driver");
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        throw new UnsupportedOperationException("copyAsync not supported by ONTAP driver");
    }

    @Override
    public boolean canCopy(DataObject srcData, DataObject destData) {
        return false;
    }

    @Override
    public void resize(DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {

    }

    @Override
    public ChapInfo getChapInfo(DataObject dataObject) {
        return null;
    }

    /**
     * Grants a host access to a volume.
     */
    @Override
    public boolean grantAccess(DataObject dataObject, Host host, DataStore dataStore) {
        try {
            if (dataStore == null) {
                throw new InvalidParameterValueException("grantAccess: dataStore should not be null");
            }
            if (dataObject == null) {
                throw new InvalidParameterValueException("grantAccess: dataObject should not be null");
            }
            if (host == null) {
                throw new InvalidParameterValueException("grantAccess: host should not be null");
            }

            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                s_logger.error("grantAccess: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("grantAccess: Storage Pool not found for id: " + dataStore.getId());
            }
            String storagePoolUuid = dataStore.getUuid();

            // ONTAP managed storage only supports cluster and zone scoped pools
            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                s_logger.error("grantAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("grantAccess: Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if (volumeVO == null) {
                    s_logger.error("grantAccess: CloudStack Volume not found for id: " + dataObject.getId());
                    throw new CloudRuntimeException("grantAccess: CloudStack Volume not found for id: " + dataObject.getId());
                }

                Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
                String svmName = details.get(Constants.SVM_NAME);

                if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                    // Only retrieve LUN name for iSCSI volumes
                    String cloudStackVolumeName = volumeDetailsDao.findDetail(volumeVO.getId(), Constants.LUN_DOT_NAME).getValue();
                    UnifiedSANStrategy sanStrategy = (UnifiedSANStrategy) Utility.getStrategyByStoragePoolDetails(details);
                    String accessGroupName = Utility.getIgroupName(svmName, storagePoolUuid);

                    AccessGroup accessGroupRequest = getAccessGroupRequestByProtocol(details, storagePoolUuid);
                    AccessGroup accessGroup = sanStrategy.getAccessGroup(accessGroupRequest);

                    // Verify host initiator is registered in the igroup before allowing access
                    if (!sanStrategy.validateInitiatorInAccessGroup(host.getStorageUrl(), accessGroup)) {
                        throw new CloudRuntimeException("grantAccess: Host initiator [" + host.getStorageUrl() +
                                "] is not present in iGroup [" + accessGroupName + "]");
                    }

                    // Create or retrieve existing LUN mapping
                    String lunNumber = sanStrategy.ensureLunMapped(svmName, cloudStackVolumeName, accessGroupName);

                    // Update volume path if changed (e.g., after migration or re-mapping)
                    String iscsiPath = Constants.SLASH + storagePool.getPath() + Constants.SLASH + lunNumber;
                    if (volumeVO.getPath() == null || !volumeVO.getPath().equals(iscsiPath)) {
                        volumeVO.set_iScsiName(iscsiPath);
                        volumeVO.setPath(iscsiPath);
                    }
                } else if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                    // For NFS, no access grant needed - file is accessible via mount
                    s_logger.debug("grantAccess: NFS volume [{}], no igroup mapping required", volumeVO.getUuid());
                    return true;
                }
                volumeVO.setPoolType(storagePool.getPoolType());
                volumeVO.setPoolId(storagePool.getId());
                volumeDao.update(volumeVO.getId(), volumeVO);
            } else if (dataObject.getType() == DataObjectType.SNAPSHOT) {
                grantAccessForSnapshot((SnapshotInfo) dataObject, host, storagePool);
                return true;
            } else {
                s_logger.error("Invalid DataObjectType (" + dataObject.getType() + ") passed to grantAccess");
                throw new CloudRuntimeException("Invalid DataObjectType (" + dataObject.getType() + ") passed to grantAccess");
            }
            return true;
        } catch (Exception e) {
            s_logger.error("grantAccess: Failed for dataObject [{}]: {}", dataObject, e.getMessage());
            throw new CloudRuntimeException("grantAccess: Failed with error: " + e.getMessage(), e);
        }
    }

    /**
     * Revokes a host's access to a volume.
     */
    @Override
    public void revokeAccess(DataObject dataObject, Host host, DataStore dataStore) {
        try {
            if (dataStore == null) {
                throw new InvalidParameterValueException("revokeAccess: dataStore should not be null");
            }
            if (dataObject == null) {
                throw new InvalidParameterValueException("revokeAccess: dataObject should not be null");
            }
            if (host == null) {
                throw new InvalidParameterValueException("revokeAccess: host should not be null");
            }

            // Safety check: don't revoke access if volume is still attached to an active VM
            if (dataObject.getType() == DataObjectType.VOLUME) {
                Volume volume = volumeDao.findById(dataObject.getId());
                if (volume.getInstanceId() != null) {
                    VirtualMachine vm = vmDao.findById(volume.getInstanceId());
                    if (vm != null && !Arrays.asList(
                            VirtualMachine.State.Destroyed,
                            VirtualMachine.State.Expunging,
                            VirtualMachine.State.Error).contains(vm.getState())) {
                        s_logger.warn("revokeAccess: Volume [{}] is still attached to VM [{}] in state [{}], skipping revokeAccess",
                                dataObject.getId(), vm.getInstanceName(), vm.getState());
                        return;
                    }
                }
            }

            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                s_logger.error("revokeAccess: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("revokeAccess: Storage Pool not found for id: " + dataStore.getId());
            }

            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                s_logger.error("revokeAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("revokeAccess: Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if (volumeVO == null) {
                    s_logger.error("revokeAccess: CloudStack Volume not found for id: " + dataObject.getId());
                    throw new CloudRuntimeException("revokeAccess: CloudStack Volume not found for id: " + dataObject.getId());
                }
                revokeAccessForVolume(storagePool, volumeVO, host);
            } else if (dataObject.getType() == DataObjectType.SNAPSHOT) {
                revokeAccessForSnapshot((SnapshotInfo) dataObject, host, storagePool);
            } else {
                s_logger.error("revokeAccess: Invalid DataObjectType (" + dataObject.getType() + ") passed to revokeAccess");
                throw new CloudRuntimeException("Invalid DataObjectType (" + dataObject.getType() + ") passed to revokeAccess");
            }
        } catch (Exception e) {
            s_logger.error("revokeAccess: Failed for dataObject [{}]: {}", dataObject, e.getMessage());
            throw new CloudRuntimeException("revokeAccess: Failed with error: " + e.getMessage(), e);
        }
    }

    /**
     * Revokes volume access for the specified host.
     */
    private void revokeAccessForVolume(StoragePoolVO storagePool, VolumeVO volumeVO, Host host) {
        s_logger.info("revokeAccessForVolume: Revoking access to volume [{}] for host [{}]", volumeVO.getName(), host.getName());

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
        String svmName = details.get(Constants.SVM_NAME);
        String storagePoolUuid = storagePool.getUuid();

        if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
            CloudStackVolume cloudStackVolumeRequest = getCloudStackVolumeRequestByProtocol(details, volumeVO);
            CloudStackVolume cloudStackVolume = storageStrategy.getCloudStackVolume(cloudStackVolumeRequest);

            // Verify igroup still exists on ONTAP
            AccessGroup accessGroupRequest = getAccessGroupRequestByProtocol(details, storagePoolUuid);
            AccessGroup accessGroup = storageStrategy.getAccessGroup(accessGroupRequest);

            // Verify host initiator is in the igroup before attempting to remove mapping
            SANStrategy sanStrategy = (UnifiedSANStrategy) storageStrategy;
            if (!sanStrategy.validateInitiatorInAccessGroup(host.getStorageUrl(), accessGroup)) {
                s_logger.warn("revokeAccessForVolume: Initiator [{}] is not in iGroup [{}], skipping revoke",
                        host.getStorageUrl(), accessGroup.getIgroup().getName());
                return;
            }

            // Remove the LUN mapping from the igroup
            Map<String, String> disableLogicalAccessMap = new HashMap<>();
            disableLogicalAccessMap.put(Constants.LUN_DOT_UUID, cloudStackVolume.getLun().getUuid());
            disableLogicalAccessMap.put(Constants.IGROUP_DOT_UUID, accessGroup.getIgroup().getUuid());
            storageStrategy.disableLogicalAccess(disableLogicalAccessMap);

            s_logger.info("revokeAccessForVolume: Successfully revoked access to LUN [{}] for host [{}]",
                    cloudStackVolumeRequest.getLun().getName(), host.getName());
        }
    }

    /**
     * Grants host access to a snapshot's cloned LUN for copy-to-secondary-storage.
     * Maps the snapshot LUN to the host's igroup and stores the IQN in snapshot_details
     * so that the KVM agent can connect via iSCSI to copy the data.
     */
    private void grantAccessForSnapshot(SnapshotInfo snapshotInfo, Host host, StoragePoolVO storagePool) {
        s_logger.info("grantAccessForSnapshot: Granting access to snapshot [{}] for host [{}]", snapshotInfo.getId(), host.getName());

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());

        if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
            String svmName = details.get(Constants.SVM_NAME);
            String storagePoolUuid = storagePool.getUuid();

            // snapshotInfo.getPath() contains the cloned LUN name set during takeSnapshot()
            String snapshotLunName = snapshotInfo.getPath();
            if (snapshotLunName == null) {
                throw new CloudRuntimeException("grantAccessForSnapshot: Snapshot path (LUN name) is null for snapshot id: " + snapshotInfo.getId());
            }

            UnifiedSANStrategy sanStrategy = (UnifiedSANStrategy) Utility.getStrategyByStoragePoolDetails(details);
            String accessGroupName = Utility.getIgroupName(svmName, storagePoolUuid);

            // Map the snapshot's cloned LUN to the igroup
            String lunNumber = sanStrategy.ensureLunMapped(svmName, snapshotLunName, accessGroupName);

            // Store DiskTO.IQN in snapshot_details so StorageSystemDataMotionStrategy.getSnapshotDetails()
            // can build the iSCSI source details for the CopyCommand sent to the KVM agent

            // Update volume path if changed (e.g., after migration or re-mapping)
            String iscsiPath = Constants.SLASH + storagePool.getPath() + Constants.SLASH + lunNumber;
            snapshotDetailsDao.addDetail(snapshotInfo.getId(), DiskTO.IQN, iscsiPath, false);

            s_logger.info("grantAccessForSnapshot: Snapshot LUN [{}] mapped as LUN number [{}], IQN path [{}]",
                    snapshotLunName, lunNumber, iscsiPath);
        }
    }

    /**
     * Revokes host access to a snapshot's cloned LUN after copy-to-secondary-storage completes.
     * Unmaps the snapshot LUN from the igroup and removes the IQN from snapshot_details.
     */
    private void revokeAccessForSnapshot(SnapshotInfo snapshotInfo, Host host, StoragePoolVO storagePool) {
        s_logger.info("revokeAccessForSnapshot: Revoking access to snapshot [{}] for host [{}]", snapshotInfo.getId(), host.getName());

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());

        if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
            StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
            String storagePoolUuid = storagePool.getUuid();

            // Build a CloudStackVolume request using the snapshot's cloned LUN name
            String snapshotLunName = snapshotInfo.getPath();
            if (snapshotLunName == null) {
                s_logger.warn("revokeAccessForSnapshot: Snapshot path is null for snapshot id: {}, skipping", snapshotInfo.getId());
                return;
            }

            CloudStackVolume snapshotVolumeRequest = new CloudStackVolume();
            Lun snapshotLun = new Lun();
            snapshotLun.setName(snapshotLunName);
            Svm svm = new Svm();
            svm.setName(details.get(Constants.SVM_NAME));
            snapshotLun.setSvm(svm);
            snapshotVolumeRequest.setLun(snapshotLun);

            // Retrieve the LUN from ONTAP to get its UUID
            CloudStackVolume cloudStackVolume = storageStrategy.getCloudStackVolume(snapshotVolumeRequest);

            // Get the igroup to get its UUID
            AccessGroup accessGroupRequest = getAccessGroupRequestByProtocol(details, storagePoolUuid);
            AccessGroup accessGroup = storageStrategy.getAccessGroup(accessGroupRequest);

            // Remove the LUN mapping from the igroup
            Map<String, String> disableLogicalAccessMap = new HashMap<>();
            disableLogicalAccessMap.put(Constants.LUN_DOT_UUID, cloudStackVolume.getLun().getUuid());
            disableLogicalAccessMap.put(Constants.IGROUP_DOT_UUID, accessGroup.getIgroup().getUuid());
            storageStrategy.disableLogicalAccess(disableLogicalAccessMap);

            // Remove the IQN detail from snapshot_details
            snapshotDetailsDao.removeDetail(snapshotInfo.getId(), DiskTO.IQN);

            s_logger.info("revokeAccessForSnapshot: Successfully revoked access to snapshot LUN [{}] for host [{}]",
                    snapshotLunName, host.getName());
        }
    }

    /**
     * Deletes the cloned LUN that was created during takeSnapshot().
     * Called by the framework after the snapshot data has been copied to secondary storage.
     */
    private void deleteSnapshotClone(SnapshotInfo snapshotInfo, DataStore store) {
        s_logger.info("deleteSnapshotClone: Deleting snapshot clone for snapshot [{}]", snapshotInfo.getId());

        StoragePoolVO storagePool = storagePoolDao.findById(store.getId());
        if (storagePool == null) {
            throw new CloudRuntimeException("deleteSnapshotClone: Storage Pool not found for id: " + store.getId());
        }

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(store.getId());
        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);

        // Get the cloned LUN name from snapshotInfo.getPath() (set during takeSnapshot)
        String snapshotLunName = snapshotInfo.getPath();
        if (snapshotLunName == null) {
            s_logger.warn("deleteSnapshotClone: Snapshot path is null for snapshot id: {}, nothing to delete", snapshotInfo.getId());
            return;
        }

        // Get the cloned LUN UUID from snapshot_details
        SnapshotDetailsVO snapDetail = snapshotDetailsDao.findDetail(snapshotInfo.getSnapshotId(), Constants.ONTAP_SNAP_ID);
        String lunUuid = (snapDetail != null) ? snapDetail.getValue() : null;

        // Build delete request for the cloned LUN
        CloudStackVolume deleteRequest = new CloudStackVolume();
        Lun lun = new Lun();
        lun.setName(snapshotLunName);
        if (lunUuid != null) {
            lun.setUuid(lunUuid);
        }
        deleteRequest.setLun(lun);

        storageStrategy.deleteCloudStackVolume(deleteRequest);
        s_logger.info("deleteSnapshotClone: Successfully deleted cloned LUN [{}] for snapshot [{}]", snapshotLunName, snapshotInfo.getId());

        // Clean up snapshot details
        snapshotDetailsDao.removeDetail(snapshotInfo.getSnapshotId(), Constants.ONTAP_SNAP_ID);
        snapshotDetailsDao.removeDetail(snapshotInfo.getSnapshotId(), Constants.ONTAP_SNAP_SIZE);
    }

    @Override
    public long getDataObjectSizeIncludingHypervisorSnapshotReserve(DataObject dataObject, StoragePool storagePool) {
        return 0;
    }

    @Override
    public long getBytesRequiredForTemplate(TemplateInfo templateInfo, StoragePool storagePool) {
        return 0;
    }

    @Override
    public long getUsedBytes(StoragePool storagePool) {
        return 0;
    }

    @Override
    public long getUsedIops(StoragePool storagePool) {
        return 0;
    }

    @Override
    public void takeSnapshot(SnapshotInfo snapshot, AsyncCompletionCallback<CreateCmdResult> callback) {
        s_logger.info("takeSnapshot: snapshot info {}", snapshot.toString());
        CreateCmdResult result;
        String snapshotId = null;
        long snapshotSize = 0L;
        if (snapshot == null) {
            throw new InvalidParameterValueException("takeSnapshot: snapshot should not be null");
        }
        try {
            VolumeInfo volumeInfo = snapshot.getBaseVolume();
            s_logger.info("takeSnapshot: volumeInfo: {}", volumeInfo.toString());
            VolumeVO volumeVO = volumeDao.findById(volumeInfo.getId());
            if (volumeVO == null) {
                throw new CloudRuntimeException("takeSnapshot: VolumeVO not found for id: " + volumeInfo.getId());
            }
            s_logger.info("takeSnapshot: volumeVO: {}", volumeVO.toString());

            StoragePoolVO storagePool = storagePoolDao.findById(volumeVO.getPoolId());
            if(storagePool == null) {
                s_logger.error("takeSnapshot : Storage Pool not found for id: " + volumeVO.getPoolId());
                throw new CloudRuntimeException("takeSnapshot : Storage Pool not found for id: " + volumeVO.getPoolId());
            }
            s_logger.info("takeSnapshot: storagePool: {}", storagePool.toString());

            Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(volumeVO.getPoolId());
            StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(poolDetails);

            CloudStackVolume cloudStackVolumeRequest = getCloudStackVolumeRequestByProtocol(poolDetails, volumeVO);
            CloudStackVolume cloudStackVolume = storageStrategy.getCloudStackVolume(cloudStackVolumeRequest);

            SnapshotObjectTO snapshotObjectTo = (SnapshotObjectTO)snapshot.getTO();
            CloudStackVolume snapshotCloudStackVolumeRequest = snapshotCloudStackVolumeRequestByProtocol(poolDetails, storagePool, cloudStackVolume, snapshot);
            CloudStackVolume clonedCloudStackVolume = storageStrategy.copyCloudStackVolume(snapshotCloudStackVolumeRequest);
            UnifiedSANStrategy sanStrategy = (UnifiedSANStrategy) Utility.getStrategyByStoragePoolDetails(poolDetails);
            String accessGroupName = Utility.getIgroupName(poolDetails.get(Constants.SVM_NAME), storagePool.getUuid());

            // Map the snapshot's cloned LUN to the igroup
            String lunNumber = sanStrategy.ensureLunMapped(poolDetails.get(Constants.SVM_NAME), clonedCloudStackVolume.getLun().getName(), accessGroupName);
            // Store DiskTO.IQN in snapshot_details so StorageSystemDataMotionStrategy.getSnapshotDetails()
            // can build the iSCSI source details for the CopyCommand sent to the KVM agent

            // Update volume path if changed (e.g., after migration or re-mapping)
            String iscsiPath = Constants.SLASH + storagePool.getPath() + Constants.SLASH + lunNumber;
            if (ProtocolType.ISCSI.name().equalsIgnoreCase(poolDetails.get(Constants.PROTOCOL))) {
                snapshotObjectTo.setPath(iscsiPath);
                snapshotId = clonedCloudStackVolume.getLun().getUuid();
                snapshotSize = clonedCloudStackVolume.getLun().getSpace().getSize();
            }

            updateSnapshotDetails(snapshot.getId(), volumeInfo.getId(), poolDetails.get(Constants.VOLUME_UUID), snapshotId, volumeVO.getPoolId(), snapshotSize);

            /** Update size for the storage-pool including snapshot size */
            storagePoolDao.update(volumeVO.getPoolId(), storagePool);

            CreateObjectAnswer createObjectAnswer = new CreateObjectAnswer(snapshotObjectTo);
            result = new CreateCmdResult(null, createObjectAnswer);
            result.setResult(null);

        } catch (Exception ex) {
            s_logger.error("takeSnapshot: Failed due to ", ex);
            result = new CreateCmdResult(null, new CreateObjectAnswer(ex.toString()));
            result.setResult(ex.toString());
        }
        callback.complete(result);
    }

    private AccessGroup getAccessGroupRequestByProtocol(Map<String, String> poolDetails, String storagePoolUuid) {
        AccessGroup accessGroupRequest = null;
        ProtocolType protocolType = null;
        String protocol = null;
        String accessGroupName = null;
        String svmName = poolDetails.get(Constants.SVM_NAME);

        try {
            protocol = poolDetails.get(Constants.PROTOCOL);
            protocolType = ProtocolType.valueOf(protocol);
            if (ProtocolType.ISCSI.name().equalsIgnoreCase(poolDetails.get(Constants.PROTOCOL))) {
                accessGroupName = Utility.getIgroupName(svmName, storagePoolUuid);
            }
        } catch (IllegalArgumentException e) {
            throw new CloudRuntimeException("getCloudStackVolumeRequestByProtocol: Protocol: " + protocol + " is not valid");
        }
        switch (protocolType) {
            case ISCSI:
                accessGroupRequest = new AccessGroup();
                Igroup igroup = new Igroup();
                igroup.setName(accessGroupName);
                Svm svm = new Svm();
                svm.setName(svmName);
                igroup.setSvm(svm);
                accessGroupRequest.setIgroup(igroup);
                break;
            default:
                throw new CloudRuntimeException("createCloudStackVolumeRequestByProtocol: Unsupported protocol " + protocol);
        }
        return accessGroupRequest;
    }

    private CloudStackVolume getCloudStackVolumeRequestByProtocol(Map<String, String> poolDetails, VolumeVO volumeVO) {
        CloudStackVolume cloudStackVolumeRequest = null;
        ProtocolType protocolType = null;
        String protocol = null;
        String lunName = null;

        try {
            protocol = poolDetails.get(Constants.PROTOCOL);
            protocolType = ProtocolType.valueOf(protocol);
            if (ProtocolType.ISCSI.name().equalsIgnoreCase(poolDetails.get(Constants.PROTOCOL))) {
                lunName = volumeDetailsDao.findDetail(volumeVO.getId(), Constants.LUN_DOT_NAME) != null ?
                        volumeDetailsDao.findDetail(volumeVO.getId(), Constants.LUN_DOT_NAME).getValue() : null;
                if (lunName == null) {
                    s_logger.error("getCloudStackVolumeRequestByProtocol: No LUN name found for volume [{}]", volumeVO.getId());
                    throw new CloudRuntimeException("getCloudStackVolumeRequestByProtocol: No LUN name found for volume " + volumeVO.getId());
                }
                s_logger.info("getCloudStackVolumeRequestByProtocol: Retrieved LUN name [{}] for volume [{}]", lunName, volumeVO.getId());
            }
        } catch (IllegalArgumentException e) {
            throw new CloudRuntimeException("getCloudStackVolumeRequestByProtocol: Protocol: " + protocol + " is not valid");
        }
        switch (protocolType) {
            case ISCSI:
                cloudStackVolumeRequest = new CloudStackVolume();
                Svm svm = new Svm();
                svm.setName(poolDetails.get(Constants.SVM_NAME));
                Lun lun = new Lun();
                lun.setName(lunName);
                lun.setSvm(svm);
                cloudStackVolumeRequest.setLun(lun);
                break;
            default:
                throw new CloudRuntimeException("createCloudStackVolumeRequestByProtocol: Unsupported protocol " + protocol);
        }
        return cloudStackVolumeRequest;
    }

    private CloudStackVolume snapshotCloudStackVolumeRequestByProtocol(Map<String, String> details, StoragePoolVO storagePool, CloudStackVolume cloudStackVolume, SnapshotInfo snapshotInfo) {
        CloudStackVolume cloudStackVolumeRequest = null;
        ProtocolType protocolType = null;
        String protocol = null;
        long size = 0L;
        String snapshotName = null;
        String lunName = null;

        try {
            protocol = details.get(Constants.PROTOCOL);
            protocolType = ProtocolType.valueOf(protocol);
            if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                size = cloudStackVolume.getLun().getSpace().getSize();
                lunName = cloudStackVolume.getLun().getName();
                if(snapshotInfo.getName() == null || snapshotInfo.getName().isEmpty()) {
                    snapshotName = Constants.VOL + storagePool.getName() + Constants.SLASH + lunName + "-snapshot-" + snapshotInfo.getUuid();
                    s_logger.info("snapshotCloudStackVolumeRequestByProtocol: size: {} ", size);
                    s_logger.info("snapshotCloudStackVolumeRequestByProtocol: lunName: {} ", lunName);
                    s_logger.info("snapshotCloudStackVolumeRequestByProtocol: snapshotName: {} ", snapshotName);
                } else {
                    snapshotName = Constants.VOL + storagePool.getName() + Constants.SLASH + snapshotInfo.getName();
                }
                int trimRequired = snapshotName.length() - Constants.MAX_SNAPSHOT_NAME_LENGTH;
                if (trimRequired > 0) {
                    snapshotName = StringUtils.left(lunName, (lunName.length() - trimRequired)) + "-" + snapshotInfo.getUuid();
                }
            }
            s_logger.info("snapshotCloudStackVolumeRequestByProtocol: snapshotName after trim: {} ", snapshotName);
            long capacityBytes = storagePool.getCapacityBytes();
            long usedBytes = storagePool.getUsedBytes();
            usedBytes += size;
            if (usedBytes > capacityBytes) {
                throw new CloudRuntimeException("Insufficient space remains in this primary storage to take a snapshot");
            }
            storagePool.setUsedBytes(usedBytes);

        } catch (IllegalArgumentException e) {
            throw new CloudRuntimeException("getCloudStackVolumeRequestByProtocol: Protocol: "+ protocol +" is not valid");
        }
        switch (protocolType) {
            case ISCSI:
                cloudStackVolumeRequest = new CloudStackVolume();
                Lun lun = new Lun();
                Svm svm = new Svm();
                svm.setName(details.get(Constants.SVM_NAME));
                Lun.Source lunCloneSource = new Lun.Source();
                lunCloneSource.setName(cloudStackVolume.getLun().getName());
                lunCloneSource.setUuid(cloudStackVolume.getLun().getUuid());
                Lun.Clone lunClone = new Lun.Clone();
                lunClone.setSource(lunCloneSource);
                lun.setName(snapshotName);
                lun.setSvm(svm);
                lun.setClone(lunClone);
                cloudStackVolumeRequest.setLun(lun);
                break;
            default:
                throw new CloudRuntimeException("createCloudStackVolumeRequestByProtocol: Unsupported protocol " + protocol);

        }
        return cloudStackVolumeRequest;
    }

    private void updateSnapshotDetails(long snapshotId, long csVolumeId, String ontapVolumeUuid, String ontapNewLunId, long storagePoolId, long ontapSnapSize) {
        SnapshotDetailsVO snapshotDetail = new SnapshotDetailsVO(snapshotId, Constants.SRC_CS_VOLUME_ID,  String.valueOf(csVolumeId), false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(snapshotId, Constants.BASE_ONTAP_FV_ID, String.valueOf(ontapVolumeUuid), false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(snapshotId, Constants.ONTAP_SNAP_ID, String.valueOf(ontapNewLunId), false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(snapshotId, Constants.PRIMARY_POOL_ID, String.valueOf(storagePoolId), false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(snapshotId, Constants.ONTAP_SNAP_SIZE, String.valueOf(ontapSnapSize), false);
        snapshotDetailsDao.persist(snapshotDetail);
    }

    @Override
    public void revertSnapshot(SnapshotInfo snapshotOnImageStore, SnapshotInfo snapshotOnPrimaryStore, AsyncCompletionCallback<CommandResult> callback) {

    }

    @Override
    public void handleQualityOfServiceForVolumeMigration(VolumeInfo volumeInfo, QualityOfServiceState qualityOfServiceState) {

    }

    @Override
    public boolean canProvideStorageStats() {
        return false;
    }

    @Override
    public Pair<Long, Long> getStorageStats(StoragePool storagePool) {
        return null;
    }

    @Override
    public boolean canProvideVolumeStats() {
        return false; // Not yet implemented for RAW managed NFS
    }

    @Override
    public Pair<Long, Long> getVolumeStats(StoragePool storagePool, String volumeId) {
        return null;
    }

    @Override
    public boolean canHostAccessStoragePool(Host host, StoragePool pool) {
        return true;
    }

    @Override
    public boolean isVmInfoNeeded() {
        return true;
    }

    @Override
    public void provideVmInfo(long vmId, long volumeId) {

    }

    @Override
    public boolean isVmTagsNeeded(String tagKey) {
        return true;
    }

    @Override
    public void provideVmTags(long vmId, long volumeId, String tagValue) {

    }

    @Override
    public boolean isStorageSupportHA(Storage.StoragePoolType type) {
        return true;
    }

    @Override
    public void detachVolumeFromAllStorageNodes(Volume volume) {
    }

    private CloudStackVolume createDeleteCloudStackVolumeRequest(StoragePool storagePool, Map<String, String> details, VolumeInfo volumeInfo) {
        CloudStackVolume cloudStackVolumeDeleteRequest = null;

        String protocol = details.get(Constants.PROTOCOL);
        ProtocolType protocolType = ProtocolType.valueOf(protocol);
        switch (protocolType) {
            case NFS3:
                cloudStackVolumeDeleteRequest = new CloudStackVolume();
                cloudStackVolumeDeleteRequest.setDatastoreId(String.valueOf(storagePool.getId()));
                cloudStackVolumeDeleteRequest.setVolumeInfo(volumeInfo);
                break;
            case ISCSI:
                // Retrieve LUN identifiers stored during volume creation
                String lunName = volumeDetailsDao.findDetail(volumeInfo.getId(), Constants.LUN_DOT_NAME).getValue();
                String lunUUID = volumeDetailsDao.findDetail(volumeInfo.getId(), Constants.LUN_DOT_UUID).getValue();
                if (lunName == null) {
                    throw new CloudRuntimeException("deleteAsync: Missing LUN name for volume " + volumeInfo.getId());
                }
                cloudStackVolumeDeleteRequest = new CloudStackVolume();
                Lun lun = new Lun();
                lun.setName(lunName);
                lun.setUuid(lunUUID);
                cloudStackVolumeDeleteRequest.setLun(lun);
                break;
            default:
                throw new CloudRuntimeException("createDeleteCloudStackVolumeRequest: Unsupported protocol " + protocol);

        }
        return cloudStackVolumeDeleteRequest;

    }
}
