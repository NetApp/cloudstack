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
import com.cloud.storage.ScopeType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Volume;
import com.cloud.storage.StoragePool;
import com.cloud.storage.dao.VolumeDao;
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
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;

public class OntapPrimaryDatastoreDriver implements PrimaryDataStoreDriver {

    private static final Logger s_logger = LogManager.getLogger(OntapPrimaryDatastoreDriver.class);

    @Inject private Utility utils;
    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject private PrimaryDataStoreDao storagePoolDao;
    @Inject private VolumeDao volumeDao;
    @Override
    public Map<String, String> getCapabilities() {
        s_logger.trace("OntapPrimaryDatastoreDriver: getCapabilities: Called");
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
    public DataStoreTO getStoreTO(DataStore store) {
        return null;
    }

    @Override
    public void createAsync(DataStore dataStore, DataObject dataObject, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult createCmdResult = null;
        String path = null;
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
            s_logger.info("createAsync: Started for data store [{}] and data object [{}] of type [{}]", dataStore, dataObject, dataObject.getType());

            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if(storagePool == null) {
                s_logger.error("createCloudStackVolume : Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("createCloudStackVolume : Storage Pool not found for id: " + dataStore.getId());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                path = createCloudStackVolumeForTypeVolume(storagePool, dataObject);
                createCmdResult = new CreateCmdResult(path, new Answer(null, true, null));
            } else {
                errMsg = "Invalid DataObjectType (" + dataObject.getType() + ") passed to createAsync";
                s_logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
        } catch (Exception e) {
            errMsg = e.getMessage();
            s_logger.error("createAsync: Failed for dataObject [{}]: {}", dataObject, errMsg);
            createCmdResult = new CreateCmdResult(null, new Answer(null, false, errMsg));
            createCmdResult.setResult(e.toString());
        } finally {
            callback.complete(createCmdResult);
        }
    }

    private String createCloudStackVolumeForTypeVolume(StoragePoolVO storagePool, DataObject dataObject) {
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = utils.getStrategyByStoragePoolDetails(details);
        s_logger.info("createCloudStackVolumeForTypeVolume: Connection to Ontap SVM [{}] successful, preparing CloudStackVolumeRequest", details.get(Constants.SVM_NAME));
        CloudStackVolume cloudStackVolumeRequest = utils.createCloudStackVolumeRequestByProtocol(storagePool, details, dataObject);
        CloudStackVolume cloudStackVolume = storageStrategy.createCloudStackVolume(cloudStackVolumeRequest);
        if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL)) && cloudStackVolume.getLun() != null && cloudStackVolume.getLun().getName() != null) {
            return cloudStackVolume.getLun().getName();
        } else {
            String errMsg = "createCloudStackVolumeForTypeVolume: Volume creation failed. Lun or Lun Path is null for dataObject: " + dataObject;
            s_logger.error(errMsg);
            throw new CloudRuntimeException(errMsg);
        }
    }

    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {

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
            if(storagePool == null) {
                s_logger.error("grantAccess : Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("grantAccess : Storage Pool not found for id: " + dataStore.getId());
            }
            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                s_logger.error("grantAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("grantAccess: Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if(volumeVO == null) {
                    s_logger.error("grantAccess : Cloud Stack Volume not found for id: " + dataObject.getId());
                    throw new CloudRuntimeException("grantAccess : Cloud Stack Volume not found for id: " + dataObject.getId());
                }
                grantAccessForVolume(storagePool, volumeVO, host);
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

    private void grantAccessForVolume(StoragePoolVO storagePool, VolumeVO volumeVO, Host host) {
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = utils.getStrategyByStoragePoolDetails(details);
        String svmName = details.get(Constants.SVM_NAME);
        long scopeId = (storagePool.getScope() == ScopeType.CLUSTER) ? host.getClusterId() : host.getDataCenterId();

        if(ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
            String accessGroupName = utils.getIgroupName(svmName, scopeId);
            CloudStackVolume cloudStackVolume = getCloudStackVolumeByName(storageStrategy, svmName, volumeVO.getPath());
            AccessGroup accessGroup = getAccessGroupByName(storageStrategy, svmName, accessGroupName);
            if(accessGroup.getIgroup().getInitiators() == null || accessGroup.getIgroup().getInitiators().size() == 0 || !accessGroup.getIgroup().getInitiators().contains(host.getStorageUrl())) {
                s_logger.error("grantAccess: initiator [{}] is not present in iGroup [{}]", host.getStorageUrl(), accessGroupName);
                throw new CloudRuntimeException("grantAccess: initiator [" + host.getStorageUrl() + "] is not present in iGroup [" + accessGroupName);
            }

            Map<String, String> enableLogicalAccessMap = new HashMap<>();
            enableLogicalAccessMap.put(Constants.LUN_DOT_NAME, volumeVO.getPath());
            enableLogicalAccessMap.put(Constants.SVM_DOT_NAME, svmName);
            enableLogicalAccessMap.put(Constants.IGROUP_DOT_NAME, accessGroupName);
            storageStrategy.enableLogicalAccess(enableLogicalAccessMap);
        }
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
        try {
            StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
            if(storagePool == null) {
                s_logger.error("revokeAccess : Storage Pool not found for id: " + dataStore.getId());
                throw new CloudRuntimeException("revokeAccess : Storage Pool not found for id: " + dataStore.getId());
            }
            if (storagePool.getScope() != ScopeType.CLUSTER && storagePool.getScope() != ScopeType.ZONE) {
                s_logger.error("revokeAccess: Only Cluster and Zone scoped primary storage is supported for storage Pool: " + storagePool.getName());
                throw new CloudRuntimeException("revokeAccess: Only Cluster and Zone scoped primary storage is supported for Storage Pool: " + storagePool.getName());
            }

            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeVO volumeVO = volumeDao.findById(dataObject.getId());
                if(volumeVO == null) {
                    s_logger.error("revokeAccess : Cloud Stack Volume not found for id: " + dataObject.getId());
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
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(storagePool.getId());
        StorageStrategy storageStrategy = utils.getStrategyByStoragePoolDetails(details);
        String svmName = details.get(Constants.SVM_NAME);
        long scopeId = (storagePool.getScope() == ScopeType.CLUSTER) ? host.getClusterId() : host.getDataCenterId();

        if(ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
            String accessGroupName = utils.getIgroupName(svmName, scopeId);
            CloudStackVolume cloudStackVolume = getCloudStackVolumeByName(storageStrategy, svmName, volumeVO.getPath());
            AccessGroup accessGroup = getAccessGroupByName(storageStrategy, svmName, accessGroupName);
            //TODO check if initiator does exits in igroup, will throw the error ?
            if(!accessGroup.getIgroup().getInitiators().contains(host.getStorageUrl())) {
                s_logger.error("grantAccess: initiator [{}] is not present in iGroup [{}]", host.getStorageUrl(), accessGroupName);
                throw new CloudRuntimeException("grantAccess: initiator [" + host.getStorageUrl() + "] is not present in iGroup [" + accessGroupName);
            }

            Map<String, String> disableLogicalAccessMap = new HashMap<>();
            disableLogicalAccessMap.put(Constants.LUN_DOT_UUID, cloudStackVolume.getLun().getUuid().toString());
            disableLogicalAccessMap.put(Constants.IGROUP_DOT_UUID, accessGroup.getIgroup().getUuid());
            storageStrategy.disableLogicalAccess(disableLogicalAccessMap);
        }
    }

    private CloudStackVolume getCloudStackVolumeByName(StorageStrategy storageStrategy, String svmName, String cloudStackVolumeName) {
        Map<String, String> getCloudStackVolumeMap = new HashMap<>();
        getCloudStackVolumeMap.put(Constants.NAME, cloudStackVolumeName);
        getCloudStackVolumeMap.put(Constants.SVM_DOT_NAME, svmName);
        CloudStackVolume cloudStackVolume = storageStrategy.getCloudStackVolume(getCloudStackVolumeMap);
        if(cloudStackVolume == null || cloudStackVolume.getLun() == null || cloudStackVolume.getLun().getName() == null) {
            s_logger.error("getCloudStackVolumeByName: Failed to get LUN details [{}]", cloudStackVolumeName);
            throw new CloudRuntimeException("getCloudStackVolumeByName: Failed to get LUN [" + cloudStackVolumeName + "]");
        }
        return cloudStackVolume;
    }

    private AccessGroup getAccessGroupByName(StorageStrategy storageStrategy, String svmName, String accessGroupName) {
        Map<String, String> getAccessGroupMap = new HashMap<>();
        getAccessGroupMap.put(Constants.NAME, accessGroupName);
        getAccessGroupMap.put(Constants.SVM_DOT_NAME, svmName);
        AccessGroup accessGroup = storageStrategy.getAccessGroup(getAccessGroupMap);
        if (accessGroup == null || accessGroup.getIgroup() == null || accessGroup.getIgroup().getName() == null) {
            s_logger.error("getAccessGroupByName: Failed to get iGroup details [{}]", accessGroupName);
            throw new CloudRuntimeException("getAccessGroupByName: Failed to get iGroup details [" + accessGroupName + "]");
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
        return true;
    }

    @Override
    public Pair<Long, Long> getStorageStats(StoragePool storagePool) {
        return null;
    }

    @Override
    public boolean canProvideVolumeStats() {
        return true;
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
