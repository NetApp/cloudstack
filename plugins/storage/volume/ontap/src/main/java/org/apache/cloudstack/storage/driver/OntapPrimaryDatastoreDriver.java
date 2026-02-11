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

    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject private PrimaryDataStoreDao storagePoolDao;

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
            s_logger.info("createAsync: Started for data store [{}] and data object [{}] of type [{}]",
                    dataStore, dataObject, dataObject.getType());
            if (dataObject.getType() == DataObjectType.VOLUME) {
                VolumeInfo volumeInfo = (VolumeInfo) dataObject;
                path = createCloudStackVolumeForTypeVolume(dataStore, volumeInfo);
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
            if (createCmdResult != null && createCmdResult.isSuccess()) {
                s_logger.info("createAsync: Volume created successfully. Path: {}", path);
            }
            callback.complete(createCmdResult);
        }
    }

    private String createCloudStackVolumeForTypeVolume(DataStore dataStore, VolumeInfo volumeObject) {
        StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
        if(storagePool == null) {
            s_logger.error("createCloudStackVolume : Storage Pool not found for id: " + dataStore.getId());
            throw new CloudRuntimeException("createCloudStackVolume : Storage Pool not found for id: " + dataStore.getId());
        }
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(dataStore.getId());
        StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
        s_logger.info("createCloudStackVolumeForTypeVolume: Connection to Ontap SVM [{}] successful, preparing CloudStackVolumeRequest", details.get(Constants.SVM_NAME));
        CloudStackVolume cloudStackVolumeRequest = Utility.createCloudStackVolumeRequestByProtocol(storagePool, details, volumeObject);
        CloudStackVolume cloudStackVolume = storageStrategy.createCloudStackVolume(cloudStackVolumeRequest);
        if (ProtocolType.ISCSI.name().equalsIgnoreCase(details.get(Constants.PROTOCOL)) && cloudStackVolume.getLun() != null && cloudStackVolume.getLun().getName() != null) {
            return cloudStackVolume.getLun().getName();
        } else if (ProtocolType.NFS3.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
            return volumeObject.getUuid(); // return the volume UUID for agent as path for mounting
        } else {
            String errMsg = "createCloudStackVolumeForTypeVolume: Volume creation failed. Lun or Lun Path is null for dataObject: " + volumeObject;
            s_logger.error(errMsg);
            throw new CloudRuntimeException(errMsg);
        }
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
                StorageStrategy storageStrategy = Utility.getStrategyByStoragePoolDetails(details);
                s_logger.info("createCloudStackVolumeForTypeVolume: Connection to Ontap SVM [{}] successful, preparing CloudStackVolumeRequest", details.get(Constants.SVM_NAME));
                VolumeInfo volumeInfo = (VolumeInfo) data;
                CloudStackVolume cloudStackVolumeRequest = createDeleteCloudStackVolumeRequest(storagePool,details,volumeInfo);
                // NFS qcow2 backing file deletion handled by KVM host/libvirt; nothing to do via ONTAP REST.
                storageStrategy.deleteCloudStackVolume(cloudStackVolumeRequest);
                s_logger.error("deleteAsync : Volume deleted: " + volumeInfo.getId());
            }
        } catch (Exception e) {
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
        return true;
    }

    @Override
    public void revokeAccess(DataObject dataObject, Host host, DataStore dataStore) {
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

    private CloudStackVolume createDeleteCloudStackVolumeRequest(StoragePool storagePool, Map<String, String> details, VolumeInfo volumeInfo) {
        CloudStackVolume cloudStackVolumeRequest = null;

        String protocol = details.get(Constants.PROTOCOL);
        ProtocolType protocolType = ProtocolType.valueOf(protocol);
        switch (protocolType) {
            case NFS3:
                cloudStackVolumeRequest = new CloudStackVolume();
                cloudStackVolumeRequest.setDatastoreId(String.valueOf(storagePool.getId()));
                cloudStackVolumeRequest.setVolumeInfo(volumeInfo);
                break;
            default:
                throw new CloudRuntimeException("createDeleteCloudStackVolumeRequest: Unsupported protocol " + protocol);

        }
        return cloudStackVolumeRequest;

    }
}
