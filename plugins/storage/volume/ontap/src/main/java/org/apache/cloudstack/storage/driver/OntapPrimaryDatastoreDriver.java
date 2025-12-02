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
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
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
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.provider.StorageProviderFactory;
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

    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject private PrimaryDataStoreDao storagePoolDao;
    @Inject private com.cloud.storage.dao.VolumeDao volumeDao;
    @Inject private EndPointSelector epSelector;

    @Override
    public Map<String, String> getCapabilities() {
        s_logger.trace("OntapPrimaryDatastoreDriver: getCapabilities: Called");
        Map<String, String> mapCapabilities = new HashMap<>();
        // RAW managed initial implementation: snapshot features not yet supported
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
                path = createCloudStackVolumeForTypeVolume(dataStore, dataObject);
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
                s_logger.info("createAsync: Volume metadata created successfully. Path: {}", path);
            }
            callback.complete(createCmdResult);
        }
    }

    /**
     * Sends CreateObjectCommand to KVM agent to create qcow2 file using qemu-img.
     * The KVM agent will call:
     * - KVMStorageProcessor.createVolume()
     * - primaryPool.createPhysicalDisk()
     * - LibvirtStorageAdaptor.createPhysicalDiskByQemuImg()
     * Which executes: qemu-img create -f qcow2 /mnt/<uuid>/<volumeUuid> <size>
     *
     * @param volumeInfo Volume information with size, format, uuid
     * @return Answer from KVM agent indicating success/failure
     */
    private Answer createVolumeOnKVMHost(VolumeInfo volumeInfo) {
        try {
            s_logger.info("createVolumeOnKVMHost: Sending CreateObjectCommand to KVM agent for volume: {}", volumeInfo.getUuid());
            // Create command with volume TO (Transfer Object)
            CreateObjectCommand cmd = new CreateObjectCommand(volumeInfo.getTO());
            // Select endpoint (KVM agent) to send command
            // epSelector will find an appropriate KVM host in the cluster/pod
            EndPoint ep = epSelector.select(volumeInfo);
            if (ep == null) {
                String errMsg = "No remote endpoint to send CreateObjectCommand, check if host is up";
                s_logger.error(errMsg);
                return new Answer(cmd, false, errMsg);
            }
            s_logger.info("createVolumeOnKVMHost: Sending command to endpoint: {}", ep.getHostAddr());
            // Send command to KVM agent and wait for response
            Answer answer = ep.sendMessage(cmd);
            if (answer != null && answer.getResult()) {
                s_logger.info("createVolumeOnKVMHost: Successfully created qcow2 file on KVM host");
            } else {
                s_logger.error("createVolumeOnKVMHost: Failed to create qcow2 file: {}",
                    answer != null ? answer.getDetails() : "null answer");
            }
            return answer;
        } catch (Exception e) {
            s_logger.error("createVolumeOnKVMHost: Exception sending CreateObjectCommand", e);
            return new Answer(null, false, e.toString());
        }
    }

    /**
     * Creates CloudStack volume based on storage protocol type (NFS or iSCSI).
     *
     * For Managed NFS (Option 2 Implementation):
     * - Creates ONTAP volume and sets metadata in CloudStack DB
     * - Sends CreateObjectCommand to KVM host to create qcow2 file using qemu-img
     * - ONTAP volume provides the backing NFS storage
     *
     * For iSCSI/Block Storage:
     * - Creates LUN via ONTAP REST API
     * - Returns LUN path for direct attachment
     */
    private String createCloudStackVolumeForTypeVolume(DataStore dataStore, DataObject dataObject) {
        StoragePoolVO storagePool = storagePoolDao.findById(dataStore.getId());
        if(storagePool == null) {
            s_logger.error("createCloudStackVolumeForTypeVolume: Storage Pool not found for id: {}", dataStore.getId());
            throw new CloudRuntimeException("createCloudStackVolumeForTypeVolume: Storage Pool not found for id: " + dataStore.getId());
        }

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(dataStore.getId());
        String protocol = details.get(Constants.PROTOCOL);

        if (ProtocolType.NFS.name().equalsIgnoreCase(protocol)) {
            // Step 1: Create ONTAP volume and set metadata
            String volumeUuid = createManagedNfsVolume(dataStore, dataObject, storagePool);

            // Step 2: Create export policy and attach it to new volume (MUST be before KVM mount)
            VolumeInfo volumeInfo = (VolumeInfo) dataObject;
            Map<String, String> volumeDetails = new HashMap<>();
            volumeDetails.put(Constants.VOLUME_UUID, volumeInfo.getUuid());
            volumeDetails.put(Constants.VOLUME_NAME, volumeInfo.getName());

            StorageStrategy storageStrategy = getStrategyByStoragePoolDetails(details);
            AccessGroup accessGroup = new AccessGroup();
            accessGroup.setStoragePooldetails(details);
            accessGroup.setVolumedetails(volumeDetails);
            storageStrategy.createAccessGroup(accessGroup);

            // Step 3: Send command to KVM host to create qcow2 file using qemu-img
            Answer answer = createVolumeOnKVMHost(volumeInfo);
            if (answer == null || !answer.getResult()) {
                String errMsg = answer != null ? answer.getDetails() : "Failed to create qcow2 on KVM host";
                s_logger.error("createCloudStackVolumeForTypeVolume: " + errMsg);
                throw new CloudRuntimeException(errMsg);
            }
                // create export policy
//            VolumeInfo volumeInfo = (VolumeInfo) dataObject;
//            String svmName = details.get(Constants.SVM_NAME);
//            String volumeName = volumeInfo.getName();
//            String volumeUUID = volumeInfo.getUuid();
//
//            // Create the export policy
//            ExportPolicy policyRequest = createExportPolicyRequest(svmName,volumeName);
//            try {
//                createExportPolicy(svmName, policyRequest);
//                s_logger.info("ExportPolicy created: {}, now attaching this policy to storage pool volume", policyRequest.getName());
//
//                // attach export policy to volume of storage pool
//                assignExportPolicyToVolume(volumeUUID,policyRequest.getName());
//                s_logger.info("Successfully assigned exportPolicy {} to volume {}", policyRequest.getName(), volumeName);
//                accessGroup.setPolicy(policyRequest);
//                return accessGroup;
//            }catch(Exception e){
//                s_logger.error("Exception occurred while creating access group: " +  e);
//                throw new CloudRuntimeException("Failed to create access group: " + e);
//            }
//



            return volumeUuid;
        } else if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
            return createManagedBlockVolume(dataStore, dataObject, storagePool, details);
        } else {
            String errMsg = String.format("createCloudStackVolumeForTypeVolume: Unsupported protocol [%s]", protocol);
            s_logger.error(errMsg);
            throw new CloudRuntimeException(errMsg);
        }
    }

    /**
     * Creates Managed NFS Volume with ONTAP backing storage.
     *
     * Architecture: 1 CloudStack Volume = 1 ONTAP Volume = 1 NFS Export
     *
     * Flow:
     * 1. Create ONTAP FlexVolume via REST API with junction path /cloudstack_vol_<volumeUuid>
     * 2. ONTAP automatically creates NFS export for the junction path
     * 3. Store volume metadata in CloudStack DB
     * 4. Volume attach triggers OntapNfsStorageAdaptor.connectPhysicalDisk()
     * 5. KVM mounts: nfs://nfsServer/cloudstack_vol_<volumeUuid> to /mnt/volumeUuid
     * 6. qemu-img creates qcow2 file in the mounted directory
     * 7. File created at: /vol/cloudstack_vol_<volumeUuid>/<volumeUuid>.qcow2 (on ONTAP)
     *
     * Key Details:
     * - Each CloudStack volume gets its own dedicated ONTAP FlexVolume
     * - Each ONTAP volume has unique junction path: /cloudstack_vol_<volumeUuid>
     * - Each volume mounted at: /mnt/<volumeUuid>
     * - qcow2 file stored at root of ONTAP volume
     * - volume._iScsiName stores the NFS junction path for OntapNfsStorageAdaptor
     *
     * @param dataStore CloudStack data store (storage pool)
     * @param dataObject Volume data object
     * @param storagePool Storage pool VO
     * @return Volume UUID (used as filename for qcow2 file)
     */
    private String createManagedNfsVolume(DataStore dataStore, DataObject dataObject, StoragePoolVO storagePool) {
        VolumeInfo volumeInfo = (VolumeInfo) dataObject;
        VolumeVO volume = volumeDao.findById(volumeInfo.getId());
        String volumeUuid = volumeInfo.getUuid();

        // Step 1: Create ONTAP FlexVolume via REST API
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(dataStore.getId());
        StorageStrategy storageStrategy = getStrategyByStoragePoolDetails(details);

        String sanitizedName = volumeUuid.replace('-', '_');
        String ontapVolumeName = "cs_vol_" + sanitizedName;
        String junctionPath = "/" + ontapVolumeName;
        long sizeInBytes = volumeInfo.getSize();

        s_logger.info("Creating ONTAP FlexVolume: name={}, junctionPath={}, size={}GB for CloudStack volume {}",
                      ontapVolumeName, junctionPath, sizeInBytes / (1024 * 1024 * 1024), volumeUuid);

        try {
            // Create ONTAP volume with NFS export
            org.apache.cloudstack.storage.feign.model.Volume ontapVolume =
                storageStrategy.createStorageVolume(ontapVolumeName, sizeInBytes);

            if (ontapVolume == null || ontapVolume.getUuid() == null) {
                String errMsg = "Failed to create ONTAP volume: " + ontapVolumeName;
                s_logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }

            s_logger.info("ONTAP FlexVolume created successfully: name={}, uuid={}, junctionPath={}",
                          ontapVolumeName, ontapVolume.getUuid(), junctionPath);

        } catch (Exception e) {
            String errMsg = "Exception creating ONTAP volume " + ontapVolumeName + ": " + e.getMessage();
            s_logger.error(errMsg, e);
            throw new CloudRuntimeException(errMsg, e);
        }

        // Step 2: Update CloudStack volume metadata
        volume.setPoolType(Storage.StoragePoolType.OntapNFS);
        volume.setPoolId(dataStore.getId());
        volume.setPath(volumeUuid);  // Filename for qcow2 file

        // Store junction path in _iScsiName field
        // OntapNfsStorageAdaptor will use this to mount: nfs://<server>/cloudstack_vol_<volumeUuid>
        volume.set_iScsiName(junctionPath);

        volumeDao.update(volume.getId(), volume);

        s_logger.info("CloudStack volume metadata updated: uuid={}, path={}, junctionPath={}, poolType={}, size={}GB",
                      volumeUuid, volumeUuid, junctionPath, "OntapNFS",
                      volumeInfo.getSize() / (1024 * 1024 * 1024));

        return volumeUuid;
    }

    /**
     * Creates iSCSI/Block volume by calling ONTAP REST API to create a LUN.
     *
     * For block storage (iSCSI), the storage provider must create the LUN
     * before CloudStack can use it. This is different from NFS where the
     * hypervisor creates the file.
     *
     * @param dataStore CloudStack data store
     * @param dataObject Volume data object
     * @param storagePool Storage pool VO
     * @param details Storage pool details containing ONTAP connection info
     * @return LUN path/name for iSCSI attachment
     */
    private String createManagedBlockVolume(DataStore dataStore, DataObject dataObject,
                                           StoragePoolVO storagePool, Map<String, String> details) {
        StorageStrategy storageStrategy = getStrategyByStoragePoolDetails(details);

        s_logger.info("createManagedBlockVolume: Creating iSCSI LUN on ONTAP SVM [{}]", details.get(Constants.SVM_NAME));

        CloudStackVolume cloudStackVolumeRequest = Utility.createCloudStackVolumeRequestByProtocol(storagePool, details, (VolumeInfo) dataObject);

        CloudStackVolume cloudStackVolume = storageStrategy.createCloudStackVolume(cloudStackVolumeRequest);

        if (cloudStackVolume.getLun() != null && cloudStackVolume.getLun().getName() != null) {
            String lunPath = cloudStackVolume.getLun().getName();
            s_logger.info("createManagedBlockVolume: iSCSI LUN created successfully: {}", lunPath);
            return lunPath;
        } else {
            String errMsg = String.format("createManagedBlockVolume: LUN creation failed for volume [%s]. " +
                                        "LUN or LUN path is null.", dataObject.getUuid());
            s_logger.error(errMsg);
            throw new CloudRuntimeException(errMsg);
        }
    }

    /**
     * Optional: Prepares ONTAP volume for optimal qcow2 file storage.
     *
     * Future enhancements can include:
     * - Enable compression for qcow2 files
     * - Set QoS policies
     * - Enable deduplication
     * - Configure snapshot policies
     *
     * This is a placeholder for ONTAP-specific optimizations.
     */
    private void prepareOntapVolumeForQcow2Storage(DataStore dataStore, VolumeInfo volumeInfo) {
        // TODO: Implement ONTAP volume optimizations
        // Examples:
        // - storageStrategy.enableCompression(volumePath)
        // - storageStrategy.setQosPolicy(volumePath, iops)
        // - storageStrategy.enableDeduplication(volumePath)
        s_logger.debug("prepareOntapVolumeForQcow2Storage: Placeholder for future ONTAP optimizations");
    }

    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {
        CommandResult commandResult = new CommandResult();
        try {
            if (store == null || data == null) {
                throw new CloudRuntimeException("deleteAsync: store or data is null");
            }
            if (data.getType() == DataObjectType.VOLUME) {
                Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(store.getId());
                if (ProtocolType.NFS.name().equalsIgnoreCase(details.get(Constants.PROTOCOL))) {
                    VolumeInfo volumeInfo = (VolumeInfo) data;
                    String volumeUuid = volumeInfo.getUuid();
                    String ontapVolumeName = "cloudstack_vol_" + volumeUuid;

                    s_logger.info("deleteAsync: Deleting ONTAP FlexVolume {} for CloudStack volume {}",
                                  ontapVolumeName, volumeUuid);

                    // TODO: Implement ONTAP volume deletion via StorageStrategy.deleteStorageVolume()
                    // For now, ONTAP volumes will remain after CloudStack volume deletion
                    // This allows manual cleanup and prevents accidental data loss during development
                    // Future implementation:
                    // StorageStrategy storageStrategy = getStrategyByStoragePoolDetails(details);
                    // Volume ontapVolume = new Volume();
                    // ontapVolume.setName(ontapVolumeName);
                    // storageStrategy.deleteStorageVolume(ontapVolume);

                    s_logger.warn("deleteAsync: ONTAP volume deletion not yet implemented. " +
                                 "Manual cleanup required for ONTAP volume: {}", ontapVolumeName);
                }
            }
        } catch (Exception e) {
            commandResult.setResult(e.getMessage());
            s_logger.error("deleteAsync: Exception deleting volume", e);
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

    private StorageStrategy getStrategyByStoragePoolDetails(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            s_logger.error("getStrategyByStoragePoolDetails: Storage pool details are null or empty");
            throw new CloudRuntimeException("getStrategyByStoragePoolDetails: Storage pool details are null or empty");
        }
        String protocol = details.get(Constants.PROTOCOL);
        OntapStorage ontapStorage = new OntapStorage(details.get(Constants.USERNAME), details.get(Constants.PASSWORD),
                details.get(Constants.MANAGEMENT_LIF), details.get(Constants.SVM_NAME), ProtocolType.valueOf(protocol),
                Boolean.parseBoolean(details.get(Constants.IS_DISAGGREGATED)));
        StorageStrategy storageStrategy = StorageProviderFactory.getStrategy(ontapStorage);
        boolean isValid = storageStrategy.connect();
        if (isValid) {
            s_logger.info("Connection to Ontap SVM [{}] successful", details.get(Constants.SVM_NAME));
            return storageStrategy;
        } else {
            s_logger.error("getStrategyByStoragePoolDetails: Connection to Ontap SVM [" + details.get(Constants.SVM_NAME) + "] failed");
            throw new CloudRuntimeException("getStrategyByStoragePoolDetails: Connection to Ontap SVM [" + details.get(Constants.SVM_NAME) + "] failed");
        }
    }
}