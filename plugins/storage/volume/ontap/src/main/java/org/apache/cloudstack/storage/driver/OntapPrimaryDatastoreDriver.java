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
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.storage.dao.VMTemplateDao;
//import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.dao.VMTemplatePoolDao;
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
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Initiator;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class OntapPrimaryDatastoreDriver implements PrimaryDataStoreDriver {

    private static final Logger s_logger = LogManager.getLogger(OntapPrimaryDatastoreDriver.class);

    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject private PrimaryDataStoreDao storagePoolDao;
    @Inject private VMInstanceDao vmDao;
    @Inject private VolumeDao volumeDao;
    @Inject private VolumeDetailsDao volumeDetailsDao;
    @Override
    public Map<String, String> getCapabilities() {
        s_logger.trace("OntapPrimaryDatastoreDriver: getCapabilities: Called");
        Map<String, String> mapCapabilities = new HashMap<>();
        // RAW managed initial implementation: snapshot features not yet supported
        // TODO Set it to false once we start supporting snapshot feature
        mapCapabilities.put(DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT.toString(), Boolean.FALSE.toString());
        mapCapabilities.put(DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_SNAPSHOT.toString(), Boolean.FALSE.toString());

        return mapCapabilities;
    }

    @Override
    public DataTO getTO(DataObject data) {
        return null;
    }

    @Override
    public DataStoreTO getStoreTO(DataStore store) { return null; }

    @Override
    public void createAsync(DataStore dataStore, DataObject dataObject, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult createCmdResult = null;
        String errMsg = null;
        if (dataStore == null) {
            throw new InvalidParameterValueException("createAsync: dataStore should not be null");
        }
        if (dataObject == null) {
            throw new InvalidParameterValueException("createAsync: dataObject should not be null");
        }
        if (callback == null) {
            throw new InvalidParameterValueException("createAsync: callback should not be null");
        }
        try {
            s_logger.info("createAsync: Started for data store name [{}] and data object name [{}] of type [{}]", dataStore.getName(), dataObject.getName(), dataObject.getType());
            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                s_logger.error("createCloudStackVolume : Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("createCloudStackVolume : Storage Pool not found for id: " + dataStore.getId());
            }
            Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(dataStore.getId());

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeInfo volInfo = (VolumeInfo) dataObject;
                // Create LUN/backing for volume and record relevant details
                CloudStackVolume created = createCloudStackVolume(dataStore, volInfo);

                // Immediately ensure LUN-map exists and update VolumeVO path
                if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                    String svmName = details.get(Constants.SVM_NAME);
                    String lunName = volumeDetailsDao.findDetail(volInfo.getId(), Constants.LUN_DOT_NAME) != null ?
                            volumeDetailsDao.findDetail(volInfo.getId(), Constants.LUN_DOT_NAME).getValue() : null;
                    if (lunName == null) {
                        // Fallback from returned LUN
                        lunName = created != null && created.getLun() != null ? created.getLun().getName() : null;
                    }
                    if (lunName == null) {
                        throw new CloudRuntimeException("createAsync: Missing LUN name for volume " + volInfo.getId());
                    }
                    long scopeId = (storagePool.getScope() == ScopeType.CLUSTER) ? storagePool.getClusterId() : storagePool.getDataCenterId();
                    String lunNumber = ensureLunMapped(storagePool, svmName, lunName, scopeId);

                    VolumeVO volumeVO = volumeDao.findById(volInfo.getId());
                    if (volumeVO != null) {
                        String iscsiPath = Constants.SLASH + storagePool.getPath() + Constants.SLASH + lunNumber;
                        volumeVO.set_iScsiName(iscsiPath);
                        volumeVO.setPath(iscsiPath);
                        volumeVO.setPoolType(storagePool.getPoolType());
                        volumeVO.setPoolId(storagePool.getId());
                        volumeDao.update(volumeVO.getId(), volumeVO);
                        s_logger.info("createAsync: Volume [{}] iSCSI path set to {}", volumeVO.getId(), iscsiPath);
                    }
                } else if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                    // Ensure pool fields are recorded for managed NFS as well
                    VolumeVO volumeVO = volumeDao.findById(volInfo.getId());
                    if (volumeVO != null) {
                        volumeVO.setPoolType(storagePool.getPoolType());
                        volumeVO.setPoolId(storagePool.getId());
                        volumeDao.update(volumeVO.getId(), volumeVO);
                        s_logger.info("createAsync: Managed NFS volume [{}] associated with pool {}", volumeVO.getId(), storagePool.getId());
                    }
                }
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
            callback.complete(createCmdResult);
        }
    }

    private CloudStackVolume createCloudStackVolume(DataStore dataStore, DataObject dataObject) {
        StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
        if (storagePool == null) {
            s_logger.error("createCloudStackVolume: Storage Pool not found for id: {}", dataStore.getId());
            throw new CloudRuntimeException("createCloudStackVolume: Storage Pool not found for id: " + dataStore.getId());
        }
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(dataStore.getId());
        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);

        if (dataObject.getType() == DataObjectType.VOLUME) {
            VolumeInfo volumeObject = (VolumeInfo) dataObject;

            CloudStackVolume cloudStackVolumeRequest = Utility.createCloudStackVolumeRequestByProtocol(storagePool, details, volumeObject);
            CloudStackVolume cloudStackVolume = storageStrategy.createCloudStackVolume(cloudStackVolumeRequest);
            if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL)) && cloudStackVolume.getLun() != null && cloudStackVolume.getLun().getName() != null) {
                s_logger.info("createCloudStackVolume: iSCSI LUN object created for volume [{}]", volumeObject.getId());
                volumeDetailsDao.addDetail(volumeObject.getId(), Constants.LUN_DOT_UUID, cloudStackVolume.getLun().getUuid(), false);
                volumeDetailsDao.addDetail(volumeObject.getId(), Constants.LUN_DOT_NAME, cloudStackVolume.getLun().getName(), false);
                VolumeVO volumeVO = volumeDao.findById(volumeObject.getId());
                if (volumeVO != null) {
                    volumeVO.setPath(null);
                    if (cloudStackVolume.getLun().getUuid() != null) {
                        volumeVO.setFolder(cloudStackVolume.getLun().getUuid());
                    }
                    volumeVO.setPoolType(storagePool.getPoolType());
                    volumeVO.setPoolId(storagePool.getId());
                    volumeDao.update(volumeVO.getId(), volumeVO);
                }
            } else if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                s_logger.info("createCloudStackVolume: Managed NFS object created for volume [{}]", volumeObject.getId());
                // For Managed NFS, set pool fields on Volume
                VolumeVO volumeVO = volumeDao.findById(volumeObject.getId());
                if (volumeVO != null) {
                    volumeVO.setPoolType(storagePool.getPoolType());
                    volumeVO.setPoolId(storagePool.getId());
                    volumeDao.update(volumeVO.getId(), volumeVO);
                }
            } else {
                String errMsg = "createCloudStackVolume: Volume creation failed for dataObject: " + volumeObject;
                s_logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
            return cloudStackVolume;
        } else {
            throw new CloudRuntimeException("createCloudStackVolume: Unsupported DataObjectType: " + dataObject.getType());
        }
    }

    private String ensureLunMapped(StoragePoolVO storagePool, String svmName, String lunName, long scopeId) {
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
        String accessGroupName = Utility.getIgroupName(svmName, storagePool.getScope(), scopeId);

        // Check existing map first. getLogicalAccess returns null (no exception) when map doesn't exist.
        Map<String, String> getMap = new HashMap<>();
        getMap.put(Constants.LUN_DOT_NAME, lunName);
        getMap.put(Constants.SVM_DOT_NAME, svmName);
        getMap.put(Constants.IGROUP_DOT_NAME, accessGroupName);
        Map<String, String> mapResp = storageStrategy.getLogicalAccess(getMap);
        if (mapResp != null && mapResp.containsKey(Constants.LOGICAL_UNIT_NUMBER)) {
            String lunNumber = mapResp.get(Constants.LOGICAL_UNIT_NUMBER);
            s_logger.info("ensureLunMapped: Existing LunMap found for LUN [{}] in igroup [{}] with LUN number [{}]", lunName, accessGroupName, lunNumber);
            return lunNumber;
        }
        // Create if not exists
        Map<String, String> enableMap = new HashMap<>();
        enableMap.put(Constants.LUN_DOT_NAME, lunName);
        enableMap.put(Constants.SVM_DOT_NAME, svmName);
        enableMap.put(Constants.IGROUP_DOT_NAME, accessGroupName);
        Map<String, String> response = storageStrategy.enableLogicalAccess(enableMap);
        if (response == null || !response.containsKey(Constants.LOGICAL_UNIT_NUMBER)) {
            throw new CloudRuntimeException("ensureLunMapped: Failed to map LUN [" + lunName + "] to iGroup [" + accessGroupName + "]");
        }
        return response.get(Constants.LOGICAL_UNIT_NUMBER);
    }

    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {
        CommandResult commandResult = new CommandResult();
        try {
            if (store == null || data == null) {
                throw new CloudRuntimeException("deleteAsync: store or data is null");
            }
            if (data.getType() == DataObjectType.VOLUME) {
                StoragePoolVO storagePool = storagePoolDao.findById(store.getId());
                if(storagePool == null) {
                    s_logger.error("deleteAsync : Storage Pool not found for id: " + store.getId());
                    throw new CloudRuntimeException("deleteAsync : Storage Pool not found for id: " + store.getId());
                }
                Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(store.getId());
                if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                    // ManagedNFS qcow2 backing file deletion handled by KVM host/libvirt; nothing to do via ONTAP REST.
                    s_logger.info("deleteAsync: ManagedNFS volume {} no-op ONTAP deletion", data.getId());
                } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                    StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
                    VolumeInfo volumeObject = (VolumeInfo) data;
                    s_logger.info("deleteAsync: Deleting volume & LUN for volume id [{}]", volumeObject.getId());
                    String lunName = volumeDetailsDao.findDetail(volumeObject.getId(), Constants.LUN_DOT_NAME).getValue();
                    String lunUUID = volumeDetailsDao.findDetail(volumeObject.getId(), Constants.LUN_DOT_UUID).getValue();
                    if (lunName == null) {
                        throw new CloudRuntimeException("deleteAsync: Missing LUN name for volume " + volumeObject.getId());
                    }
                    CloudStackVolume delRequest = new CloudStackVolume();
                    Lun lun = new Lun();
                    lun.setName(lunName);
                    lun.setUuid(lunUUID);
                    delRequest.setLun(lun);
                    storageStrategy.deleteCloudStackVolume(delRequest);
                    // Set the result
                    commandResult.setResult(null);
                    commandResult.setSuccess(true);
                    s_logger.info("deleteAsync: Volume LUN [{}] deleted successfully", lunName);
                } else {
                    throw new CloudRuntimeException("deleteAsync: Unsupported protocol for deletion: " + details.get(Constants.PROTOCOL));
                }
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
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {

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

    @Override
    public boolean grantAccess(DataObject dataObject, Host host, DataStore dataStore) {
        if (dataStore == null) {
            throw new InvalidParameterValueException("grantAccess: dataStore should not be null");
        }
        if (dataObject == null) {
            throw new InvalidParameterValueException("grantAccess: dataObject should not be null");
        }
        if (host == null) {
            throw new InvalidParameterValueException("grantAccess: host should not be null");
        }
        try {
            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                s_logger.error("grantAccess: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("grantAccess : Storage Pool not found for id: " + dataStore.getId());
            }
            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                s_logger.error("grantAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("grantAccess: Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if (volumeVO == null) {
                    s_logger.error("grantAccess : Cloud Stack Volume not found for id: " + dataObject.getId());
                    throw new CloudRuntimeException("grantAccess : Cloud Stack Volume not found for id: " + dataObject.getId());
                }
                Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
                String svmName = details.get(Constants.SVM_NAME);
                String cloudStackVolumeName = volumeDetailsDao.findDetail(volumeVO.getId(), Constants.LUN_DOT_NAME).getValue();
                long scopeId = (storagePool.getScope() == ScopeType.CLUSTER) ? host.getClusterId() : host.getDataCenterId();
                // Validate initiator membership
                validateHostInitiatorInIgroup(storagePool, svmName, scopeId, host);
                // Ensure mapping exists
                String lunNumber = ensureLunMapped(storagePool, svmName, cloudStackVolumeName, scopeId);
                // Update Volume path if missing or changed
                String iscsiPath = Constants.SLASH + storagePool.getPath() + Constants.SLASH + lunNumber;
                if (volumeVO.getPath() == null || !volumeVO.getPath().equals(iscsiPath)) {
                    volumeVO.set_iScsiName(iscsiPath);
                    volumeVO.setPath(iscsiPath);
                }
                // Ensure pool fields are set (align with SolidFire)
                volumeVO.setPoolType(storagePool.getPoolType());
                volumeVO.setPoolId(storagePool.getId());
                volumeDao.update(volumeVO.getId(), volumeVO);
            } else {
                s_logger.error("Invalid DataObjectType (" + dataObject.getType() + ") passed to grantAccess");
                throw new CloudRuntimeException("Invalid DataObjectType (" + dataObject.getType() + ") passed to grantAccess");
            }
        } catch(Exception e){
            s_logger.error("grantAccess: Failed for dataObject [{}]: {}", dataObject, e.getMessage());
            throw new CloudRuntimeException("grantAccess: Failed with error :" + e.getMessage());
        }
        return true;
    }

    private void validateHostInitiatorInIgroup(StoragePoolVO storagePool, String svmName, long scopeId, Host host) {
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
        String accessGroupName = Utility.getIgroupName(svmName, storagePool.getScope(), scopeId);
        AccessGroup accessGroup = getAccessGroupByName(storageStrategy, svmName, accessGroupName);
        if (host == null || host.getStorageUrl() == null) {
            throw new CloudRuntimeException("validateHostInitiatorInIgroup: host/initiator required but not provided");
        }
        if (!hostInitiatorFoundInIgroup(host.getStorageUrl(), accessGroup.getIgroup())) {
            s_logger.error("validateHostInitiatorInIgroup: initiator [{}] is not present in iGroup [{}]", host.getStorageUrl(), accessGroupName);
            throw new CloudRuntimeException("validateHostInitiatorInIgroup: initiator [" + host.getStorageUrl() + "] is not present in iGroup [" + accessGroupName + "]");
        }
    }

    private boolean hostInitiatorFoundInIgroup(String hostInitiator, Igroup igroup) {
        if(igroup != null && igroup.getInitiators() != null && hostInitiator != null && !hostInitiator.isEmpty()) {
            for(Initiator initiator : igroup.getInitiators()) {
                if(initiator.getName().equalsIgnoreCase(hostInitiator)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void revokeAccess(DataObject dataObject, Host host, DataStore dataStore) {
        if (dataStore == null) {
            throw new InvalidParameterValueException("revokeAccess: data store should not be null");
        }
        if (dataObject == null) {
            throw new InvalidParameterValueException("revokeAccess: data object should not be null");
        }
        if (host == null) {
            throw new InvalidParameterValueException("revokeAccess: host should not be null");
        }
        if (dataObject.getType() == DataObjectType.VOLUME) {
            Volume volume = volumeDao.findById(dataObject.getId());
            if (volume.getInstanceId() != null) {
                VirtualMachine vm = vmDao.findById(volume.getInstanceId());
                if (vm != null && !Arrays.asList(VirtualMachine.State.Destroyed, VirtualMachine.State.Expunging, VirtualMachine.State.Error).contains(vm.getState())) {
                    s_logger.debug("revokeAccess: Volume [{}] is still attached to VM [{}] in state [{}], skipping revokeAccess",
                            dataObject.getId(), vm.getInstanceName(), vm.getState());
                    return;
                }
            }
        }
        try {
            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if (storagePool == null) {
                s_logger.error("revokeAccess: Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("revokeAccess : Storage Pool not found for id: " + dataStore.getId());
            }
            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                s_logger.error("revokeAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("revokeAccess: Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if (volumeVO == null) {
                    s_logger.error("revokeAccess: Cloud Stack Volume not found for id: " + dataObject.getId());
                    throw new CloudRuntimeException("revokeAccess : Cloud Stack Volume not found for id: " + dataObject.getId());
                }
                revokeAccessForVolume(storagePool, volumeVO, host);
            } else {
                s_logger.error("revokeAccess: Invalid DataObjectType (" + dataObject.getType() + ") passed to revokeAccess");
                throw new CloudRuntimeException("Invalid DataObjectType (" + dataObject.getType() + ") passed to revokeAccess");
            }
        } catch(Exception e){
            s_logger.error("revokeAccess: Failed for dataObject [{}]: {}", dataObject, e.getMessage());
            throw new CloudRuntimeException("revokeAccess: Failed with error :" + e.getMessage());
        }
    }

    private void revokeAccessForVolume(StoragePoolVO storagePool, VolumeVO volumeVO, Host host) {
        s_logger.info("revokeAccessForVolume: Revoking access to volume [{}] for host [{}]", volumeVO.getName(), host.getName());
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
        String svmName = details.get(Constants.SVM_NAME);
        long scopeId = (storagePool.getScope() == ScopeType.CLUSTER) ? host.getClusterId() : host.getDataCenterId();

        if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
            String accessGroupName = Utility.getIgroupName(svmName, storagePool.getScope(), scopeId);

            String lunName = volumeDetailsDao.findDetail(volumeVO.getId(), Constants.LUN_DOT_NAME) != null ?
                    volumeDetailsDao.findDetail(volumeVO.getId(), Constants.LUN_DOT_NAME).getValue() : null;
            if (lunName == null) {
                s_logger.warn("revokeAccessForVolume: No LUN name detail found for volume [{}]; assuming no backend LUN to revoke", volumeVO.getId());
                return;
            }

            CloudStackVolume cloudStackVolume = getCloudStackVolumeByName(storageStrategy, svmName, lunName);
            if (cloudStackVolume == null || cloudStackVolume.getLun() == null || cloudStackVolume.getLun().getUuid() == null) {
                s_logger.warn("revokeAccessForVolume: LUN for volume [{}] not found on ONTAP, assuming already deleted", volumeVO.getId());
                return;
            }

            AccessGroup accessGroup = getAccessGroupByName(storageStrategy, svmName, accessGroupName);
            if (accessGroup == null || accessGroup.getIgroup() == null || accessGroup.getIgroup().getUuid() == null) {
                s_logger.warn("revokeAccessForVolume: iGroup [{}] not found on ONTAP, assuming already deleted", accessGroupName);
                return;
            }

            if (!hostInitiatorFoundInIgroup(host.getStorageUrl(), accessGroup.getIgroup())) {
                s_logger.error("revokeAccessForVolume: initiator [{}] is not present in iGroup [{}]", host.getStorageUrl(), accessGroupName);
                return;
            }

            Map<String, String> disableLogicalAccessMap = new HashMap<>();
            disableLogicalAccessMap.put(Constants.LUN_DOT_UUID, cloudStackVolume.getLun().getUuid());
            disableLogicalAccessMap.put(Constants.IGROUP_DOT_UUID, accessGroup.getIgroup().getUuid());
            storageStrategy.disableLogicalAccess(disableLogicalAccessMap);
        }
    }


    private CloudStackVolume getCloudStackVolumeByName(StorageStrategy storageStrategy, String svmName, String cloudStackVolumeName) {
        Map<String, String> getCloudStackVolumeMap = new HashMap<>();
        getCloudStackVolumeMap.put(Constants.NAME, cloudStackVolumeName);
        getCloudStackVolumeMap.put(Constants.SVM_DOT_NAME, svmName);
        CloudStackVolume cloudStackVolume = storageStrategy.getCloudStackVolume(getCloudStackVolumeMap);
        if (cloudStackVolume == null || cloudStackVolume.getLun() == null || cloudStackVolume.getLun().getName() == null) {
            s_logger.error("getCloudStackVolumeByName: LUN [{}] not found on ONTAP; returning null", cloudStackVolumeName);
            return null;
        }
        return cloudStackVolume;
    }

    private AccessGroup getAccessGroupByName(StorageStrategy storageStrategy, String svmName, String accessGroupName) {
        Map<String, String> getAccessGroupMap = new HashMap<>();
        getAccessGroupMap.put(Constants.NAME, accessGroupName);
        getAccessGroupMap.put(Constants.SVM_DOT_NAME, svmName);
        AccessGroup accessGroup = storageStrategy.getAccessGroup(getAccessGroupMap);
        if (accessGroup == null || accessGroup.getIgroup() == null || accessGroup.getIgroup().getName() == null) {
            s_logger.error("getAccessGroupByName: iGroup [{}] not found on ONTAP; returning null", accessGroupName);
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

    @Override
    public void takeSnapshot(SnapshotInfo snapshot, AsyncCompletionCallback<CreateCmdResult> callback) {

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
}
