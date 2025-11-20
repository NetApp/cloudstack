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

package org.apache.cloudstack.storage.utils;

import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.FileInfo;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.LunSpace;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.provider.StorageProviderFactory;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.util.Base64Utils;

import java.util.Map;

public class Utility {

    private static final Logger s_logger = LogManager.getLogger(Utility.class);

    private static final String BASIC = "Basic";
    private static final String AUTH_HEADER_COLON = ":";

    /**
     * Method generates authentication headers using storage backend credentials passed as normal string
     *
     * @param username -->> username of the storage backend
     * @param password -->> normal decoded password of the storage backend
     * @return
     */
    public static String generateAuthHeader (String username, String password) {
        byte[] encodedBytes = Base64Utils.encode((username + AUTH_HEADER_COLON + password).getBytes());
        return BASIC + StringUtils.SPACE + new String(encodedBytes);
    }

    /**
     * Creates CloudStackVolume request object for ONTAP REST API calls.
     *
     * IMPORTANT: For Managed NFS (Option 2 Implementation):
     * - The NFS case below is DEPRECATED and NOT USED
     * - OntapPrimaryDatastoreDriver.createManagedNfsVolume() handles NFS volumes
     * - It returns UUID only without creating files (KVM creates qcow2 automatically)
     * - This method is ONLY used for iSCSI/block storage volumes
     *
     * @param storagePool Storage pool information
     * @param details Storage pool details with ONTAP connection info
     * @param volumeObject Volume information
     * @return CloudStackVolume request for ONTAP REST API
     */
    public static CloudStackVolume createCloudStackVolumeRequestByProtocol(StoragePoolVO storagePool, Map<String, String> details, VolumeInfo volumeObject) {
        CloudStackVolume cloudStackVolumeRequest = null;

        String protocol = details.get(Constants.PROTOCOL);
        ProtocolType protocolType = ProtocolType.valueOf(protocol);
        switch (protocolType) {
            case NFS:
                // DEPRECATED: This NFS case is NOT USED in Option 2 Implementation
                // For Managed NFS, OntapPrimaryDatastoreDriver.createManagedNfsVolume()
                // returns UUID only and lets KVM create qcow2 files automatically.
                // This legacy code remains for reference but is bypassed in current implementation.
                s_logger.warn("createCloudStackVolumeRequestByProtocol: NFS case should not be called. " +
                            "Use OntapPrimaryDatastoreDriver.createManagedNfsVolume() instead.");
                cloudStackVolumeRequest = new CloudStackVolume();
                FileInfo file = new FileInfo();
                file.setSize(volumeObject.getSize());
                file.setUnixPermissions(755);
                file.setType(FileInfo.TypeEnum.FILE);

                Volume poolVolume = new Volume();
                poolVolume.setName(details.get(Constants.VOLUME_NAME));
                poolVolume.setUuid(details.get(Constants.VOLUME_UUID));
                cloudStackVolumeRequest.setVolume(poolVolume);
                cloudStackVolumeRequest.setFile(file);
                cloudStackVolumeRequest.setCloudstackVolName(volumeObject.getName());
                break;
            case ISCSI:
                cloudStackVolumeRequest = new CloudStackVolume();
                Lun lunRequest = new Lun();
                Svm svm = new Svm();
                svm.setName(details.get(Constants.SVM_NAME));
                lunRequest.setSvm(svm);

                LunSpace lunSpace = new LunSpace();
                lunSpace.setSize(volumeObject.getSize());
                lunRequest.setSpace(lunSpace);
                //Lun name is full path like in unified "/vol/VolumeName/LunName"
                String lunFullName = Constants.VOLUME_PATH_PREFIX + storagePool.getName() + Constants.PATH_SEPARATOR + volumeObject.getName();
                lunRequest.setName(lunFullName);

                String hypervisorType = storagePool.getHypervisor().name();
                String osType = null;
                switch (hypervisorType) {
                    case Constants.KVM:
                        osType = Lun.OsTypeEnum.LINUX.getValue();
                        break;
                    default:
                        String errMsg = "createCloudStackVolume : Unsupported hypervisor type " + hypervisorType + " for ONTAP storage";
                        s_logger.error(errMsg);
                        throw new CloudRuntimeException(errMsg);
                }
                lunRequest.setOsType(Lun.OsTypeEnum.valueOf(osType));
                cloudStackVolumeRequest.setLun(lunRequest);
                break;
            default:
                throw new CloudRuntimeException("createCloudStackVolumeRequestByProtocol: Unsupported protocol " + protocol);

        }
        return cloudStackVolumeRequest;
    }

    public static StorageStrategy getStrategyByStoragePoolDetails(Map<String, String> details) {
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
