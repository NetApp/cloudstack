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

import org.apache.cloudstack.storage.utils.OntapStorageConstants;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
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
import org.apache.cloudstack.storage.feign.model.FileCloneRequest;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.SANStrategy;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.UnifiedSANStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Primary datastore driver for NetApp ONTAP storage systems.
 * Handles volume lifecycle operations for iSCSI and NFS protocols.
 */
public class OntapPrimaryDatastoreDriver implements PrimaryDataStoreDriver {

    private static final Logger logger = LogManager.getLogger(OntapPrimaryDatastoreDriver.class);

    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject private PrimaryDataStoreDao storagePoolDao;
    @Inject private VolumeDao volumeDao;
    @Inject private VolumeDetailsDao volumeDetailsDao;
    @Inject private SnapshotDetailsDao snapshotDetailsDao;

    @Override
    public Map<String, String> getCapabilities() {
        logger.trace("OntapPrimaryDatastoreDriver: getCapabilities: Called");
        Map<String, String> mapCapabilities = new HashMap<>();
        mapCapabilities.put(DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT.toString(), Boolean.TRUE.toString());
        mapCapabilities.put(DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_SNAPSHOT.toString(), Boolean.TRUE.toString());
        return mapCapabilities;
    }

    @Override
    public DataTO getTO(DataObject data) {
        return null;
    }

    @Override
    public DataStoreTO getStoreTO(DataStore store) { return null; }

    @Override
    public boolean volumesRequireGrantAccessWhenUsed() {
        logger.trace("volumesRequireGrantAccessWhenUsed invoked");
        return true;
    }

    /**
     * Creates a volume on the ONTAP storage system.
     */
    @Override
    public void createAsync(DataStore dataStore, DataObject dataObject, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult createCmdResult = null;
        String errMsg;

        if (dataObject == null) {
            throw new InvalidParameterValueException("dataObject should not be null");
        }
        if (dataStore == null) {
            throw new InvalidParameterValueException("dataStore should not be null");
        }
        if (callback == null) {
            throw new InvalidParameterValueException("callback should not be null");
        }

        try {
            logger.info("Started for data store name [{}] and data object name [{}] of type [{}]",
                    dataStore.getName(), dataObject.getName(), dataObject.getType());

            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                logger.error("createAsync: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("Storage Pool not found for id: " + dataStore.getId());
            }

            Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(dataStore.getId());

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeInfo volInfo = (VolumeInfo) dataObject;

                // Update CloudStack volume record with storage pool association and protocol-specific details
                VolumeVO volumeVO = volumeDao.findById(volInfo.getId());
                if (volumeVO != null) {
                    // Create the backend storage object (LUN for iSCSI, no-op for NFS)
                    CloudStackVolume created = createCloudStackVolume(storagePool, volInfo, details);

                    volumeVO.setPoolType(storagePool.getPoolType());
                    volumeVO.setPoolId(storagePool.getId());

                    if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(OntapStorageConstants.PROTOCOL))) {
                        String lunName = created != null && created.getLun() != null ? created.getLun().getName() : null;
                        if (lunName == null) {
                            throw new CloudRuntimeException("Missing LUN name for volume " + volInfo.getId());
                        }

                        // Persist LUN details for future operations (delete, grant/revoke access)
                        volumeDetailsDao.addDetail(volInfo.getId(), OntapStorageConstants.LUN_DOT_UUID, created.getLun().getUuid(), false);
                        volumeDetailsDao.addDetail(volInfo.getId(), OntapStorageConstants.LUN_DOT_NAME, lunName, false);
                        if (created.getLun().getUuid() != null) {
                            volumeVO.setFolder(created.getLun().getUuid());
                        }

                        logger.info("createAsync: Created LUN [{}] for volume [{}]. LUN mapping will occur during grantAccess() to per-host igroup.",
                                lunName, volumeVO.getId());
                        createCmdResult = new CreateCmdResult(lunName, new Answer(null, true, null));
                    } else if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(OntapStorageConstants.PROTOCOL))) {
                        createCmdResult = new CreateCmdResult(volInfo.getUuid(), new Answer(null, true, null));
                        logger.info("createAsync: Managed NFS volume [{}] with path [{}] associated with pool {}",
                                volumeVO.getId(), volInfo.getUuid(), storagePool.getId());
                    }
                    volumeDao.update(volumeVO.getId(), volumeVO);
                }
            } else {
                errMsg = "Invalid DataObjectType (" + dataObject.getType() + ") passed to createAsync";
                logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
        } catch (Exception e) {
            errMsg = e.getMessage();
            logger.error("createAsync: Failed for dataObject name [{}]: {}", dataObject.getName(), errMsg);
            createCmdResult = new CreateCmdResult(null, new Answer(null, false, errMsg));
            createCmdResult.setResult(e.toString());
        } finally {
            if (createCmdResult != null && createCmdResult.isSuccess()) {
                logger.info("createAsync: Operation completed successfully for {}", dataObject.getType());
            }
            callback.complete(createCmdResult);
        }
    }

    /**
     * Creates a volume on the ONTAP backend.
     */
    private CloudStackVolume createCloudStackVolume(StoragePoolVO storagePool, VolumeInfo volumeObject, Map<String, String> details) {
        StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(details);
        CloudStackVolume cloudStackVolumeRequest = OntapStorageUtils.createCloudStackVolumeRequestByProtocol(storagePool, details, volumeObject);
        return storageStrategy.createCloudStackVolume(cloudStackVolumeRequest);
    }

    /**
     * Deletes a volume or snapshot from the ONTAP storage system.
     *
     * <p>For volumes, deletes the backend storage object (LUN for iSCSI, no-op for NFS).
     * For snapshots, deletes the FlexVolume snapshot from ONTAP that was created by takeSnapshot.</p>
     */
    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {
        CommandResult commandResult = new CommandResult();
        try {
            if (store == null || data == null) {
                throw new CloudRuntimeException("store or data is null");
            }

            if (data.getType() == DataObjectType.VOLUME) {
                StoragePoolVO storagePool = storagePoolDao.findById(store.getId());
                if (storagePool == null) {
                    logger.error("deleteAsync: Storage Pool not found for id: " + store.getId());
                    throw new CloudRuntimeException("Storage Pool not found for id: " + store.getId());
                }
                Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(store.getId());
                StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(details);
                logger.info("createCloudStackVolumeForTypeVolume: Connection to Ontap SVM [{}] successful, preparing CloudStackVolumeRequest", details.get(OntapStorageConstants.SVM_NAME));
                VolumeInfo volumeInfo = (VolumeInfo) data;
                CloudStackVolume cloudStackVolumeRequest = createDeleteCloudStackVolumeRequest(storagePool, details, volumeInfo);
                storageStrategy.deleteCloudStackVolume(cloudStackVolumeRequest);
                logger.info("deleteAsync: Volume deleted: " + volumeInfo.getId());
                commandResult.setResult(null);
                commandResult.setSuccess(true);
            } else if (data.getType() == DataObjectType.SNAPSHOT) {
                // Delete the clone object (file/LUN) that was created by takeSnapshot
                deleteOntapSnapshot((SnapshotInfo) data, commandResult);
            } else {
                throw new CloudRuntimeException("Unsupported data object type: " + data.getType());
            }
        } catch (Exception e) {
            logger.error("deleteAsync: Failed for data object [{}]: {}", data, e.getMessage());
            commandResult.setSuccess(false);
            commandResult.setResult(e.getMessage());
        } finally {
            callback.complete(commandResult);
        }
    }

    /**
     * Deletes a clone-backed ONTAP snapshot object (NFS file clone or iSCSI LUN clone).
     */
    private void deleteOntapSnapshot(SnapshotInfo snapshotInfo, CommandResult commandResult) {
        long snapshotId = snapshotInfo.getId();
        logger.info("deleteOntapSnapshot: Deleting clone-backed ONTAP snapshot object for CloudStack snapshot [{}]", snapshotId);

        try {
            String flexVolUuid = getSnapshotDetail(snapshotId, OntapStorageConstants.BASE_ONTAP_FV_ID);
            String cloneUuid = getSnapshotDetail(snapshotId, OntapStorageConstants.ONTAP_CLONE_ID);
            String cloneName = getSnapshotDetail(snapshotId, OntapStorageConstants.ONTAP_CLONE_NAME);
            String poolIdStr = getSnapshotDetail(snapshotId, OntapStorageConstants.PRIMARY_POOL_ID);
            String protocol = getSnapshotDetail(snapshotId, OntapStorageConstants.PROTOCOL);

            if (poolIdStr == null || protocol == null || cloneName == null) {
                logger.warn("deleteOntapSnapshot: Missing clone metadata for snapshot [{}]. " +
                                "poolId={}, protocol={}, cloneName={}. Treating as success.",
                        snapshotId, poolIdStr, protocol, cloneName);
                commandResult.setSuccess(true);
                commandResult.setResult(null);
                return;
            }

            long poolId = Long.parseLong(poolIdStr);
            Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(poolId);

            StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails);
            String authHeader = storageStrategy.getAuthHeader();
            String svmName = poolDetails.get(OntapStorageConstants.SVM_NAME);

            if (ProtocolType.NFS3.name().equalsIgnoreCase(protocol)) {
                if (flexVolUuid == null || flexVolUuid.isEmpty()) {
                    logger.warn("deleteOntapSnapshot: Missing FlexVol UUID for NFS clone delete on snapshot [{}]. Treating as success.", snapshotId);
                    commandResult.setSuccess(true);
                    commandResult.setResult(null);
                    return;
                }
                logger.info("deleteOntapSnapshot: Deleting NFS clone file [{}] on FlexVol [{}]", cloneName, flexVolUuid);
                storageStrategy.getNasFeignClient().deleteFile(authHeader, flexVolUuid, cloneName);
            } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
                if (cloneUuid == null || cloneUuid.isEmpty()) {
                    String cloneLunPath = OntapStorageUtils.getLunName(poolDetails.get(OntapStorageConstants.VOLUME_NAME), cloneName);
                    cloneUuid = resolveLunUuidByName(storageStrategy, authHeader, svmName, cloneLunPath);
                }
                logger.info("deleteOntapSnapshot: Deleting iSCSI clone LUN [{}] (uuid={})", cloneName, cloneUuid);
                storageStrategy.getSanFeignClient().deleteLun(authHeader, cloneUuid, Map.of("allow_delete_while_mapped", "true"));
            } else {
                throw new CloudRuntimeException("Unsupported protocol for snapshot delete: " + protocol);
            }

            logger.info("deleteOntapSnapshot: Successfully deleted clone object [{}] for CloudStack snapshot [{}]",
                    cloneName, snapshotId);

            commandResult.setSuccess(true);
            commandResult.setResult(null);

        } catch (Exception e) {
            // Check if the error indicates snapshot doesn't exist (already deleted)
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("404") || errorMsg.contains("not found") ||
                    errorMsg.contains("does not exist"))) {
                logger.warn("deleteOntapSnapshot: Snapshot clone object for CloudStack snapshot [{}] not found, " +
                        "may have been already deleted. Treating as success.", snapshotId);
                commandResult.setSuccess(true);
                commandResult.setResult(null);
            } else {
                logger.error("deleteOntapSnapshot: Failed to delete ONTAP snapshot for CloudStack snapshot [{}]: {}",
                        snapshotId, e.getMessage(), e);
                commandResult.setSuccess(false);
                commandResult.setResult(e.getMessage());
            }
        }
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, AsyncCompletionCallback<CopyCommandResult> callback) {
        throw new UnsupportedOperationException("Copy operation is not supported for ONTAP primary storage.");
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        throw new UnsupportedOperationException("Copy operation is not supported for ONTAP primary storage.");
    }

    @Override
    public boolean canCopy(DataObject srcData, DataObject destData) {
        return false;
    }

    @Override
    public void resize(DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {}

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
                throw new InvalidParameterValueException("dataStore should not be null");
            }
            if (dataObject == null) {
                throw new InvalidParameterValueException("dataObject should not be null");
            }
            if (host == null) {
                throw new InvalidParameterValueException("host should not be null");
            }

            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                logger.error("grantAccess: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("Storage Pool not found for id: " + dataStore.getId());
            }

            // ONTAP managed storage only supports cluster and zone scoped pools
            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                logger.error("grantAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if (volumeVO == null) {
                    logger.error("grantAccess: CloudStack Volume not found for id: " + dataObject.getId());
                    throw new CloudRuntimeException("CloudStack Volume not found for id: " + dataObject.getId());
                }

                Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
                String svmName = details.get(OntapStorageConstants.SVM_NAME);

                if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(OntapStorageConstants.PROTOCOL))) {
                    // Only retrieve LUN name for iSCSI volumes
                    grantAccessIscsi(host, volumeVO, details, svmName, storagePool);
                } else if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(OntapStorageConstants.PROTOCOL))) {
                    // For NFS, no access grant needed - file is accessible via mount
                    logger.debug("grantAccess: NFS volume [{}], no igroup mapping required", volumeVO.getUuid());
                    return true;
                }
                volumeVO.setPoolType(storagePool.getPoolType());
                volumeVO.setPoolId(storagePool.getId());
                volumeDao.update(volumeVO.getId(), volumeVO);
            } else {
                logger.error("Invalid DataObjectType (" + dataObject.getType() + ") passed to grantAccess");
                throw new CloudRuntimeException("Invalid DataObjectType (" + dataObject.getType() + ") passed to grantAccess");
            }
            return true;
        } catch (Exception e) {
            logger.error("grantAccess: Failed for dataObject [{}]: {}", dataObject, e.getMessage());
            throw new CloudRuntimeException("Failed with error: " + e.getMessage(), e);
        }
    }

    private void grantAccessIscsi(Host host, VolumeVO volumeVO, Map<String, String> details, String svmName, StoragePoolVO storagePool) {
        String cloudStackVolumeName = volumeDetailsDao.findDetail(volumeVO.getId(), OntapStorageConstants.LUN_DOT_NAME).getValue();
        UnifiedSANStrategy sanStrategy = (UnifiedSANStrategy) OntapStorageUtils.getStrategyByStoragePoolDetails(details);
        String accessGroupName = OntapStorageUtils.getIgroupName(svmName, host.getName());

        // Validate if Igroup exist ONTAP for this host as we may be using delete_on_unmap= true and igroup may be deleted by ONTAP automatically
        Map<String, String> getAccessGroupMap = Map.of(
                OntapStorageConstants.NAME, accessGroupName,
                OntapStorageConstants.SVM_DOT_NAME, svmName
        );
        AccessGroup accessGroup = sanStrategy.getAccessGroup(getAccessGroupMap);
        if(accessGroup == null || accessGroup.getIgroup() == null) {
            logger.info("grantAccess: Igroup {} does not exist for the host {} : Need to create Igroup for the host ", accessGroupName, host.getName());
            // create the igroup for the host and perform lun-mapping
            accessGroup = new AccessGroup();
            List<HostVO> hosts = new ArrayList<>();
            hosts.add((HostVO) host);
            accessGroup.setHostsToConnect(hosts);
            accessGroup.setStoragePoolId(storagePool.getId());
            accessGroup = sanStrategy.createAccessGroup(accessGroup);
        }else{
            logger.info("grantAccess: Igroup {} already exist for the host {}: ", accessGroup.getIgroup().getName() , host.getName());
            /* TODO Below cases will be covered later, for now they will be a pre-requisite on customer side
              1. Igroup exist with the same name but host initiator has been removed
              2.  Igroup exist with the same name but host initiator has been changed may be due to new NIC or new adapter
              In both cases we need to verify current host initiator is registered in the igroup before allowing access
              Incase it is not , add it and proceed for lun-mapping
             */
        }
        logger.info("grantAccess: Igroup {}  is present now with initiators {} ", accessGroup.getIgroup().getName(), accessGroup.getIgroup().getInitiators());
        // Create or retrieve existing LUN mapping
        String lunNumber = sanStrategy.ensureLunMapped(svmName, cloudStackVolumeName, accessGroupName);

        // Update volume path if changed (e.g., after migration or re-mapping)
        String iscsiPath = OntapStorageConstants.SLASH + storagePool.getPath() + OntapStorageConstants.SLASH + lunNumber;
        if (volumeVO.getPath() == null || !volumeVO.getPath().equals(iscsiPath)) {
            volumeVO.set_iScsiName(iscsiPath);
            volumeVO.setPath(iscsiPath);
        }
    }

    /**
     * Revokes a host's access to a volume.
     */
    @Override
    public void revokeAccess(DataObject dataObject, Host host, DataStore dataStore) {
        try {
            if (dataStore == null) {
                throw new InvalidParameterValueException("dataStore should not be null");
            }
            if (dataObject == null) {
                throw new InvalidParameterValueException("dataObject should not be null");
            }
            if (host == null) {
                throw new InvalidParameterValueException("host should not be null");
            }

            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                logger.error("revokeAccess: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("Storage Pool not found for id: " + dataStore.getId());
            }

            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                logger.error("revokeAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if (volumeVO == null) {
                    logger.error("revokeAccess: CloudStack Volume not found for id: " + dataObject.getId());
                    throw new CloudRuntimeException("CloudStack Volume not found for id: " + dataObject.getId());
                }
                revokeAccessForVolume(storagePool, volumeVO, host);
            } else {
                logger.error("revokeAccess: Invalid DataObjectType (" + dataObject.getType() + ") passed to revokeAccess");
                throw new CloudRuntimeException("Invalid DataObjectType (" + dataObject.getType() + ") passed to revokeAccess");
            }
        } catch (Exception e) {
            logger.error("revokeAccess: Failed for dataObject [{}]: {}", dataObject, e.getMessage());
            throw new CloudRuntimeException("Failed with error: " + e.getMessage(), e);
        }
    }

    /**
     * Revokes volume access for the specified host.
     */
    private void revokeAccessForVolume(StoragePoolVO storagePool, VolumeVO volumeVO, Host host) {
        logger.info("revokeAccessForVolume: Revoking access to volume [{}] for host [{}]", volumeVO.getName(), host.getName());

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(details);
        String svmName = details.get(OntapStorageConstants.SVM_NAME);

        if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(OntapStorageConstants.PROTOCOL))) {
            String accessGroupName = OntapStorageUtils.getIgroupName(svmName, host.getName());

            // Retrieve LUN name from volume details; if missing, volume may not have been fully created
            VolumeDetailVO lunDetail = volumeDetailsDao.findDetail(volumeVO.getId(), OntapStorageConstants.LUN_DOT_NAME);
            ValidateRevoke result = getValidateRevoke(volumeVO, host, lunDetail, storageStrategy, svmName, accessGroupName);
            if (result == null) return;

            // Remove the LUN mapping from the igroup
            Map<String, String> disableLogicalAccessMap = new HashMap<>();
            disableLogicalAccessMap.put(OntapStorageConstants.LUN_DOT_UUID, result.cloudStackVolume.getLun().getUuid());
            disableLogicalAccessMap.put(OntapStorageConstants.IGROUP_DOT_UUID, result.accessGroup.getIgroup().getUuid());
            storageStrategy.disableLogicalAccess(disableLogicalAccessMap);

            logger.info("revokeAccessForVolume: Successfully revoked access to LUN [{}] for host [{}]",
                    result.lunName, host.getName());
        }
    }

    @Nullable
    private ValidateRevoke getValidateRevoke(VolumeVO volumeVO, Host host, VolumeDetailVO lunDetail, StorageStrategy storageStrategy, String svmName, String accessGroupName) {
        String lunName = lunDetail != null ? lunDetail.getValue() : null;
        if (lunName == null) {
            logger.warn("revokeAccessForVolume: No LUN name found for volume [{}]; skipping revoke", volumeVO.getId());
            return null;
        }

        // Verify LUN still exists on ONTAP (may have been manually deleted)
        CloudStackVolume cloudStackVolume = getCloudStackVolumeByName(storageStrategy, svmName, lunName);
        if (cloudStackVolume == null || cloudStackVolume.getLun() == null || cloudStackVolume.getLun().getUuid() == null) {
            logger.warn("revokeAccessForVolume: LUN for volume [{}] not found on ONTAP, skipping revoke", volumeVO.getId());
            return null;
        }

        // Verify igroup still exists on ONTAP
        AccessGroup accessGroup = getAccessGroupByName(storageStrategy, svmName, accessGroupName);
        if (accessGroup == null || accessGroup.getIgroup() == null || accessGroup.getIgroup().getUuid() == null) {
            logger.warn("revokeAccessForVolume: iGroup [{}] not found on ONTAP, skipping revoke", accessGroupName);
            return null;
        }

        // Verify host initiator is in the igroup before attempting to remove mapping
        SANStrategy sanStrategy = (UnifiedSANStrategy) storageStrategy;
        if (!sanStrategy.validateInitiatorInAccessGroup(host.getStorageUrl(), svmName, accessGroup.getIgroup())) {
            logger.warn("revokeAccessForVolume: Initiator [{}] is not in iGroup [{}], skipping revoke",
                    host.getStorageUrl(), accessGroupName);
            return null;
        }
        return new ValidateRevoke(lunName, cloudStackVolume, accessGroup);
    }

    private static class ValidateRevoke {
        public final String lunName;
        public final CloudStackVolume cloudStackVolume;
        public final AccessGroup accessGroup;

        public ValidateRevoke(String lunName, CloudStackVolume cloudStackVolume, AccessGroup accessGroup) {
            this.lunName = lunName;
            this.cloudStackVolume = cloudStackVolume;
            this.accessGroup = accessGroup;
        }
    }

    /**
     * Retrieves a volume from ONTAP by name.
     */
    private CloudStackVolume getCloudStackVolumeByName(StorageStrategy storageStrategy, String svmName, String cloudStackVolumeName) {
        Map<String, String> getCloudStackVolumeMap = new HashMap<>();
        getCloudStackVolumeMap.put(OntapStorageConstants.NAME, cloudStackVolumeName);
        getCloudStackVolumeMap.put(OntapStorageConstants.SVM_DOT_NAME, svmName);

        CloudStackVolume cloudStackVolume = storageStrategy.getCloudStackVolume(getCloudStackVolumeMap);
        if (cloudStackVolume == null || cloudStackVolume.getLun() == null || cloudStackVolume.getLun().getName() == null) {
            logger.warn("getCloudStackVolumeByName: LUN [{}] not found on ONTAP", cloudStackVolumeName);
            return null;
        }
        return cloudStackVolume;
    }

    /**
     * Retrieves an access group from ONTAP by name.
     */
    private AccessGroup getAccessGroupByName(StorageStrategy storageStrategy, String svmName, String accessGroupName) {
        Map<String, String> getAccessGroupMap = new HashMap<>();
        getAccessGroupMap.put(OntapStorageConstants.NAME, accessGroupName);
        getAccessGroupMap.put(OntapStorageConstants.SVM_DOT_NAME, svmName);

        AccessGroup accessGroup = storageStrategy.getAccessGroup(getAccessGroupMap);
        if (accessGroup == null || accessGroup.getIgroup() == null || accessGroup.getIgroup().getName() == null) {
            logger.warn("getAccessGroupByName: iGroup [{}] not found on ONTAP", accessGroupName);
            return null;
        }
        return accessGroup;
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

    /**
     * Takes a snapshot by creating an ONTAP FlexVolume-level snapshot.
     *
     * <p>This method creates a point-in-time, space-efficient snapshot of the entire
     * FlexVolume containing the CloudStack volume. FlexVolume snapshots are atomic
     * and capture all files/LUNs within the volume at the moment of creation.</p>
     *
     * <p>Both NFS and iSCSI protocols use the same FlexVolume snapshot approach:
     * <ul>
     *   <li>NFS: The QCOW2 file is captured within the FlexVolume snapshot</li>
     *   <li>iSCSI: The LUN is captured within the FlexVolume snapshot</li>
     * </ul>
     * </p>
     *
     * <p>With {@code STORAGE_SYSTEM_SNAPSHOT=true}, {@code StorageSystemSnapshotStrategy}
     * handles the workflow.</p>
     */
    @Override
    public void takeSnapshot(SnapshotInfo snapshot, AsyncCompletionCallback<CreateCmdResult> callback) {
        logger.info("OntapPrimaryDatastoreDriver.takeSnapshot: Creating clone-backed snapshot for snapshot [{}]", snapshot.getId());
        CreateCmdResult result;
        StorageStrategy storageStrategy = null;
        String authHeader = null;
        String protocol = null;
        String flexVolUuid = null;
        String cloneName = null;
        String cloneLunPath = null;
        String svmName = null;

        try {
            VolumeInfo volumeInfo = snapshot.getBaseVolume();

            VolumeVO volumeVO = volumeDao.findById(volumeInfo.getId());
            if (volumeVO == null) {
                throw new CloudRuntimeException("VolumeVO not found for id: " + volumeInfo.getId());
            }

            StoragePoolVO storagePool = storagePoolDao.findById(volumeVO.getPoolId());
            if (storagePool == null) {
                logger.error("takeSnapshot: Storage Pool not found for id: {}", volumeVO.getPoolId());
                throw new CloudRuntimeException("Storage Pool not found for id: " + volumeVO.getPoolId());
            }

            Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(volumeVO.getPoolId());
            protocol = poolDetails.get(OntapStorageConstants.PROTOCOL);
            flexVolUuid = poolDetails.get(OntapStorageConstants.VOLUME_UUID);
            svmName = poolDetails.get(OntapStorageConstants.SVM_NAME);

            if (flexVolUuid == null || flexVolUuid.isEmpty()) {
                throw new CloudRuntimeException("FlexVolume UUID not found in pool details for pool " + volumeVO.getPoolId());
            }

            storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails);
            authHeader = storageStrategy.getAuthHeader();

            SnapshotObjectTO snapshotObjectTo = (SnapshotObjectTO) snapshot.getTO();
            String cloudStackSnapshotName = snapshot.getName();
            cloneName = OntapStorageUtils.getOntapCloneName(cloudStackSnapshotName);
            String volumePath = resolveVolumePathOnOntap(volumeVO, protocol, poolDetails);
            String cloneId = null;
            String lunUuid = null;
            JobResponse nfsJobResponse = null;

            if (ProtocolType.NFS3.name().equalsIgnoreCase(protocol)) {
                FileCloneRequest fileCloneRequest = new FileCloneRequest();
                FileCloneRequest.VolumeRef volumeRef = new FileCloneRequest.VolumeRef();
                volumeRef.setUuid(flexVolUuid);
                volumeRef.setName(poolDetails.get(OntapStorageConstants.VOLUME_NAME));
                fileCloneRequest.setVolume(volumeRef);
                fileCloneRequest.setSourcePath(volumePath);
                fileCloneRequest.setDestinationPath(cloneName);
                fileCloneRequest.setIsOverride(Boolean.FALSE);
                logger.info("takeSnapshot: Creating NFS file clone [{}] from source [{}] on FlexVol UUID [{}]",
                        cloneName, volumePath, flexVolUuid);
                nfsJobResponse = storageStrategy.getNasFeignClient().cloneFile(authHeader, fileCloneRequest);
                cloneId = cloneName;
            } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
                VolumeDetailVO lunDetail = volumeDetailsDao.findDetail(volumeVO.getId(), OntapStorageConstants.LUN_DOT_UUID);
                lunUuid = lunDetail != null ? lunDetail.getValue() : null;
                if (lunUuid == null) {
                    throw new CloudRuntimeException("LUN UUID not found for iSCSI volume " + volumeVO.getId());
                }
                if (volumePath == null || volumePath.isEmpty()) {
                    throw new CloudRuntimeException("Source LUN path is missing for iSCSI volume " + volumeVO.getId());
                }
                if (!volumePath.startsWith(OntapStorageConstants.VOLUME_PATH_PREFIX)) {
                    throw new CloudRuntimeException("Invalid source LUN path (must start with " +
                            OntapStorageConstants.VOLUME_PATH_PREFIX + "): " + volumePath);
                }
                cloneLunPath = OntapStorageUtils.getLunName(
                        poolDetails.get(OntapStorageConstants.VOLUME_NAME), cloneName);
                if (!cloneLunPath.startsWith(OntapStorageConstants.VOLUME_PATH_PREFIX)) {
                    throw new CloudRuntimeException("Invalid iSCSI clone LUN path generated: " + cloneLunPath);
                }
                String svmNameForClone = poolDetails.get(OntapStorageConstants.SVM_NAME);
                String flexVolNameForClone = poolDetails.get(OntapStorageConstants.VOLUME_NAME);
                if (svmNameForClone == null || svmNameForClone.isEmpty()) {
                    throw new CloudRuntimeException("SVM name is mandatory for iSCSI clone request");
                }
                if (flexVolNameForClone == null || flexVolNameForClone.isEmpty()) {
                    throw new CloudRuntimeException("FlexVolume name is mandatory for iSCSI clone request");
                }
                Lun cloneRequest = new Lun();
                cloneRequest.setName(cloneLunPath);
                Svm svm = new Svm();
                svm.setName(svmNameForClone);
                cloneRequest.setSvm(svm);
                Lun.Location location = new Lun.Location();
                Lun.LocationVolume locationVolume = new Lun.LocationVolume();
                locationVolume.setName(flexVolNameForClone);
                location.setVolume(locationVolume);
                cloneRequest.setLocation(location);
                Lun.Clone clone = new Lun.Clone();
                Lun.Source source = new Lun.Source();
                source.setName(volumePath);
                source.setUuid(lunUuid);
                clone.setSource(source);
                cloneRequest.setClone(clone);
                logger.info("takeSnapshot: Creating iSCSI LUN clone [{}] from source LUN UUID [{}]", cloneName, lunUuid);
                OntapResponse<Lun> createCloneResponse = storageStrategy.getSanFeignClient().createLun(authHeader, true, cloneRequest);
                if (createCloneResponse == null || createCloneResponse.getRecords() == null || createCloneResponse.getRecords().isEmpty()) {
                    throw new CloudRuntimeException("Failed to create iSCSI clone LUN for volume " + volumeVO.getId());
                }
                cloneId = createCloneResponse.getRecords().get(0).getUuid();
                if (cloneId == null || cloneId.isEmpty()) {
                    cloneId = resolveLunUuidByName(storageStrategy, authHeader, svmNameForClone, cloneLunPath);
                }
            } else {
                throw new CloudRuntimeException("Unsupported protocol for snapshot clone: " + protocol);
            }

            if (ProtocolType.NFS3.name().equalsIgnoreCase(protocol)) {
                if (nfsJobResponse == null || nfsJobResponse.getJob() == null) {
                    throw new CloudRuntimeException("Failed to initiate clone-backed snapshot for volume " + volumeVO.getId());
                }
                // Poll for async NFS clone completion
                Boolean jobSucceeded = storageStrategy.jobPollForSuccess(nfsJobResponse.getJob().getUuid(), 30, 2000);
                if (!jobSucceeded) {
                    throw new CloudRuntimeException("Clone create job failed for snapshot " + cloudStackSnapshotName);
                }
            }

            snapshotObjectTo.setPath(OntapStorageConstants.ONTAP_CLONE_NAME + "=" + cloneName);

            // Persist snapshot details for revert/delete operations
            updateSnapshotDetails(snapshot.getId(), volumeInfo.getId(), flexVolUuid,
                    cloneId, cloudStackSnapshotName, cloneName, volumePath, volumeVO.getPoolId(), protocol, lunUuid);

            CreateObjectAnswer createObjectAnswer = new CreateObjectAnswer(snapshotObjectTo);
            result = new CreateCmdResult(null, createObjectAnswer);
            result.setResult(null);

            logger.info("takeSnapshot: Successfully created clone-backed snapshot [{}] (clone={}) for volume [{}]",
                    cloudStackSnapshotName, cloneName, volumeVO.getId());

        } catch (Exception ex) {
            String rollbackStatus = rollbackPartialSnapshotClone(storageStrategy, authHeader, protocol, flexVolUuid,
                    cloneName, cloneLunPath, svmName);
            String errorWithRollback = ex.toString() + " | rollbackStatus=" + rollbackStatus;
            logger.error("takeSnapshot: Failed with rollback status [{}]", rollbackStatus, ex);
            result = new CreateCmdResult(null, new CreateObjectAnswer(errorWithRollback));
            result.setResult(errorWithRollback);
        }

        callback.complete(result);
    }

    /**
     * Best-effort rollback of partially created snapshot clone objects when takeSnapshot fails.
     * Returns a status string that is appended to the task result so CloudStack has clear context.
     */
    private String rollbackPartialSnapshotClone(StorageStrategy storageStrategy, String authHeader, String protocol,
                                               String flexVolUuid, String cloneName, String cloneLunPath, String svmName) {
        if (storageStrategy == null || authHeader == null || protocol == null || cloneName == null || cloneName.isEmpty()) {
            return "not-attempted";
        }
        try {
            if (ProtocolType.NFS3.name().equalsIgnoreCase(protocol)) {
                storageStrategy.getNasFeignClient().deleteFile(authHeader, flexVolUuid, cloneName);
                return "nfs-clone-deleted";
            }
            if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
                String lunNameForLookup = cloneLunPath != null ? cloneLunPath : cloneName;
                String cloneUuid = resolveLunUuidByName(storageStrategy, authHeader, svmName, lunNameForLookup);
                storageStrategy.getSanFeignClient().deleteLun(authHeader, cloneUuid, Map.of("allow_delete_while_mapped", "true"));
                return "iscsi-clone-deleted";
            }
            return "unsupported-protocol";
        } catch (Exception cleanupEx) {
            String cleanupMessage = cleanupEx.getMessage() != null ? cleanupEx.getMessage() : cleanupEx.toString();
            logger.warn("rollbackPartialSnapshotClone: Failed to clean up clone [{}] for protocol [{}]: {}",
                    cloneName, protocol, cleanupMessage);
            return "cleanup-failed:" + cleanupMessage;
        }
    }

    /**
     * Resolves the volume path on ONTAP for snapshot restore operations.
     *
     * @param volumeVO    The CloudStack volume
     * @param protocol    Storage protocol (NFS3 or ISCSI)
     * @param poolDetails Pool configuration details
     * @return The ONTAP path (file path for NFS, LUN name for iSCSI)
     */
    private String resolveVolumePathOnOntap(VolumeVO volumeVO, String protocol, Map<String, String> poolDetails) {
        if (ProtocolType.NFS3.name().equalsIgnoreCase(protocol)) {
            // For NFS, use the volume's file path
            return volumeVO.getPath();
        } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
            // For iSCSI, retrieve the LUN name from volume details
            VolumeDetailVO volumeDetails = volumeDetailsDao.findDetail(volumeVO.getId(), OntapStorageConstants.LUN_DOT_NAME);

             if(volumeDetails != null) {
                 String lunName = volumeDetails.getValue();
                 if (lunName == null) {
                     throw new CloudRuntimeException("No LUN name found for volume " + volumeVO.getId());
                 }
                 return lunName;
             }
        }
        throw new CloudRuntimeException("Unsupported protocol " + protocol);
    }

    private String resolveLunUuidByName(StorageStrategy storageStrategy, String authHeader, String svmName, String lunName) {
        OntapResponse<Lun> lunResponse = storageStrategy.getSanFeignClient().getLunResponse(authHeader,
                Map.of(OntapStorageConstants.SVM_DOT_NAME, svmName, OntapStorageConstants.NAME, lunName));
        if (lunResponse == null || lunResponse.getRecords() == null || lunResponse.getRecords().isEmpty()) {
            throw new CloudRuntimeException("Failed to resolve LUN UUID for clone " + lunName);
        }
        return lunResponse.getRecords().get(0).getUuid();
    }

    /**
     * Reverts a volume to a snapshot using protocol-specific ONTAP restore APIs.
     *
     * <p>This method delegates to the appropriate StorageStrategy to restore the
     * specific file (NFS) or LUN (iSCSI) from the FlexVolume snapshot directly
     * via ONTAP REST API, without involving the hypervisor agent.</p>
     *
     * <p><b>Protocol-specific handling (delegated to strategy classes):</b></p>
     * <ul>
     *   <li><b>NFS (UnifiedNASStrategy):</b> Uses the single-file restore API:
     *       {@code POST /api/storage/volumes/{volume_uuid}/snapshots/{snapshot_uuid}/files/{file_path}/restore}
     *       Restores the QCOW2 file from the FlexVolume snapshot to its original location.</li>
     *   <li><b>iSCSI (UnifiedSANStrategy):</b> Uses the LUN restore API:
     *       {@code POST /api/storage/luns/{lun.uuid}/restore}
     *       Restores the LUN data from the snapshot to the specified destination path.</li>
     * </ul>
     */
    @Override
    public void revertSnapshot(SnapshotInfo snapshotOnImageStore, SnapshotInfo snapshotOnPrimaryStore,
                               AsyncCompletionCallback<CommandResult> callback) {
        logger.info("OntapPrimaryDatastoreDriver.revertSnapshot: Reverting snapshot [{}]",
                snapshotOnImageStore.getId());

        CommandResult result = new CommandResult();

        try {
            // Use the snapshot that has the ONTAP details stored
            SnapshotInfo snapshot = snapshotOnPrimaryStore != null ? snapshotOnPrimaryStore : snapshotOnImageStore;
            long snapshotId = snapshot.getId();

            // Retrieve snapshot details stored during takeSnapshot
            String flexVolUuid = getSnapshotDetail(snapshotId, OntapStorageConstants.BASE_ONTAP_FV_ID);
            String ontapCloneId = getSnapshotDetail(snapshotId, OntapStorageConstants.ONTAP_CLONE_ID);
            String ontapCloneName = getSnapshotDetail(snapshotId, OntapStorageConstants.ONTAP_CLONE_NAME);
            if (ontapCloneName == null) {
                // Backward compatibility for snapshots created before clone-name metadata was persisted.
                ontapCloneName = getSnapshotDetail(snapshotId, OntapStorageConstants.ONTAP_SNAP_NAME);
            }
            String volumePath = getSnapshotDetail(snapshotId, OntapStorageConstants.VOLUME_PATH);
            String poolIdStr = getSnapshotDetail(snapshotId, OntapStorageConstants.PRIMARY_POOL_ID);
            String protocol = getSnapshotDetail(snapshotId, OntapStorageConstants.PROTOCOL);

            if (flexVolUuid == null || ontapCloneName == null || volumePath == null || poolIdStr == null) {
                throw new CloudRuntimeException("Missing required snapshot details for snapshot " + snapshotId +
                        " (flexVolUuid=" + flexVolUuid + ", cloneName=" + ontapCloneName +
                        ", volumePath=" + volumePath + ", poolId=" + poolIdStr + ")");
            }

            long poolId = Long.parseLong(poolIdStr);
            Map<String, String> poolDetails = storagePoolDetailsDao.listDetailsKeyPairs(poolId);

            StorageStrategy storageStrategy = OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails);

            // Get the FlexVolume name (required for CLI-based restore API for all protocols)
            String flexVolName = poolDetails.get(OntapStorageConstants.VOLUME_NAME);
            if (flexVolName == null || flexVolName.isEmpty()) {
                throw new CloudRuntimeException("FlexVolume name not found in pool details for pool " + poolId);
            }

            // Prepare protocol-specific parameters (lunUuid is only needed for backward compatibility)
            String lunUuid = null;
            if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
                lunUuid = ontapCloneId;
            }

            // Delegate to strategy class for protocol-specific restore
            JobResponse jobResponse = storageStrategy.revertSnapshotForCloudStackVolume(
                    ontapCloneName, flexVolUuid, ontapCloneId, volumePath, lunUuid, flexVolName);

            if (jobResponse == null || jobResponse.getJob() == null) {
                throw new CloudRuntimeException("Failed to initiate restore from snapshot [" +
                        ontapCloneName + "]");
            }

            // Poll for job completion (use longer timeout for large LUNs/files)
            Boolean jobSucceeded = storageStrategy.jobPollForSuccess(jobResponse.getJob().getUuid(), 60, 2000);
            if (!jobSucceeded) {
                throw new CloudRuntimeException("Restore job failed for snapshot [" +
                        ontapCloneName + "]");
            }

            logger.info("revertSnapshot: Successfully restored {} [{}] from clone [{}]",
                    ProtocolType.ISCSI.name().equalsIgnoreCase(protocol) ? "LUN" : "file",
                    volumePath, ontapCloneName);

            result.setResult(null); // Success

        } catch (Exception ex) {
            logger.error("revertSnapshot: Failed to revert snapshot {}", snapshotOnImageStore, ex);
            result.setResult(ex.toString());
        }

        callback.complete(result);
    }

    /**
     * Retrieves a snapshot detail value by key.
     *
     * @param snapshotId The CloudStack snapshot ID
     * @param key        The detail key
     * @return The detail value, or null if not found
     */
    private String getSnapshotDetail(long snapshotId, String key) {
        SnapshotDetailsVO detail = snapshotDetailsDao.findDetail(snapshotId, key);
        return detail != null ? detail.getValue() : null;
    }

    @Override
    public void handleQualityOfServiceForVolumeMigration(VolumeInfo volumeInfo, QualityOfServiceState qualityOfServiceState) {}

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
    public void provideVmInfo(long vmId, long volumeId) {}

    @Override
    public boolean isVmTagsNeeded(String tagKey) {
        return true;
    }

    @Override
    public void provideVmTags(long vmId, long volumeId, String tagValue) {}

    @Override
    public boolean isStorageSupportHA(Storage.StoragePoolType type) {
        return true;
    }

    @Override
    public void detachVolumeFromAllStorageNodes(Volume volume) {
    }

    private CloudStackVolume createDeleteCloudStackVolumeRequest(StoragePool storagePool, Map<String, String> details, VolumeInfo volumeInfo) {
        CloudStackVolume cloudStackVolumeDeleteRequest = null;

        String protocol = details.get(OntapStorageConstants.PROTOCOL);
        ProtocolType protocolType = ProtocolType.valueOf(protocol);
        switch (protocolType) {
            case NFS3:
                cloudStackVolumeDeleteRequest = new CloudStackVolume();
                cloudStackVolumeDeleteRequest.setDatastoreId(String.valueOf(storagePool.getId()));
                cloudStackVolumeDeleteRequest.setVolumeInfo(volumeInfo);
                break;
            case ISCSI:
                // Retrieve LUN identifiers stored during volume creation
                String lunName = volumeDetailsDao.findDetail(volumeInfo.getId(), OntapStorageConstants.LUN_DOT_NAME).getValue();
                String lunUUID = volumeDetailsDao.findDetail(volumeInfo.getId(), OntapStorageConstants.LUN_DOT_UUID).getValue();
                if (lunName == null) {
                    throw new CloudRuntimeException("Missing LUN name for volume " + volumeInfo.getId());
                }
                cloudStackVolumeDeleteRequest = new CloudStackVolume();
                Lun lun = new Lun();
                lun.setName(lunName);
                lun.setUuid(lunUUID);
                cloudStackVolumeDeleteRequest.setLun(lun);
                break;
            default:
                throw new CloudRuntimeException("Unsupported protocol " + protocol);

        }
        return cloudStackVolumeDeleteRequest;

    }

    // ──────────────────────────────────────────────────────────────────────────
    // Snapshot Helper Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Persists snapshot metadata in snapshot_details table.
     *
     * @param csSnapshotId      CloudStack snapshot ID
     * @param csVolumeId        Source CloudStack volume ID
     * @param flexVolUuid       ONTAP FlexVolume UUID
     * @param ontapSnapshotUuid ONTAP FlexVolume snapshot UUID
     * @param snapshotName      ONTAP snapshot name
     * @param volumePath        Path of the volume file/LUN within the FlexVolume (for restore)
     * @param storagePoolId     Primary storage pool ID
     * @param protocol          Storage protocol (NFS3 or ISCSI)
     * @param lunUuid           LUN UUID (only for iSCSI, null for NFS)
     */
    private void updateSnapshotDetails(long csSnapshotId, long csVolumeId, String flexVolUuid,
                                        String ontapCloneId, String snapshotName, String ontapCloneName,
                                        String volumePath, long storagePoolId, String protocol,
                                        String lunUuid) {
        SnapshotDetailsVO snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.SRC_CS_VOLUME_ID, String.valueOf(csVolumeId), false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.BASE_ONTAP_FV_ID, flexVolUuid, false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.ONTAP_SNAP_ID, ontapCloneId, false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.ONTAP_SNAP_NAME, snapshotName, false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.ONTAP_CLONE_ID, ontapCloneId, false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.ONTAP_CLONE_NAME, ontapCloneName, false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.VOLUME_PATH, volumePath, false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.PRIMARY_POOL_ID, String.valueOf(storagePoolId), false);
        snapshotDetailsDao.persist(snapshotDetail);

        snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                OntapStorageConstants.PROTOCOL, protocol, false);
        snapshotDetailsDao.persist(snapshotDetail);

        // Store LUN UUID for iSCSI volumes (required for LUN restore API)
        if (lunUuid != null && !lunUuid.isEmpty()) {
            snapshotDetail = new SnapshotDetailsVO(csSnapshotId,
                    OntapStorageConstants.LUN_DOT_UUID, lunUuid, false);
            snapshotDetailsDao.persist(snapshotDetail);
        }
    }

}
