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

package org.apache.cloudstack.storage.service;

import com.cloud.utils.exception.CloudRuntimeException;
import feign.FeignException;
import org.apache.cloudstack.storage.feign.FeignClientFactory;
import org.apache.cloudstack.storage.feign.client.NASFeignClient;
import org.apache.cloudstack.storage.feign.client.VolumeFeignClient;
import org.apache.cloudstack.storage.feign.model.ExportPolicy;
import org.apache.cloudstack.storage.feign.model.FileInfo;
import org.apache.cloudstack.storage.feign.model.Nas;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;

public class UnifiedNASStrategy extends NASStrategy {

    private static final Logger s_logger = LogManager.getLogger(UnifiedNASStrategy.class);
    private final FeignClientFactory feignClientFactory;
    private final NASFeignClient nasFeignClient;
    private final VolumeFeignClient volumeFeignClient;

    public UnifiedNASStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
        String baseURL = Constants.HTTPS + ontapStorage.getManagementLIF();
        // Initialize FeignClientFactory and create NAS client
        this.feignClientFactory = new FeignClientFactory();
        this.nasFeignClient = feignClientFactory.createClient(NASFeignClient.class, baseURL);
        this.volumeFeignClient = feignClientFactory.createClient(VolumeFeignClient.class, baseURL);
    }

    public void setOntapStorage(OntapStorage ontapStorage) {
        this.storage = ontapStorage;
    }

    @Override
    public CloudStackVolume createCloudStackVolume(CloudStackVolume cloudstackVolume) {
        s_logger.info("createCloudStackVolume: Create cloudstack volume " + cloudstackVolume);
        try {
            createFile(cloudstackVolume.getVolume().getUuid(),cloudstackVolume.getCloudstackVolName(), cloudstackVolume.getFile());
            s_logger.debug("Successfully created file in ONTAP under volume with path {} or name {}  ", cloudstackVolume.getVolume().getUuid(), cloudstackVolume.getCloudstackVolName());
            FileInfo responseFile = cloudstackVolume.getFile();
            responseFile.setPath(cloudstackVolume.getCloudstackVolName());
        }catch (Exception e) {
            s_logger.error("Exception occurred while creating file or dir: {}. Exception: {}", cloudstackVolume.getCloudstackVolName(), e.getMessage());
            throw new CloudRuntimeException("Failed to create file: " + e.getMessage());
        }
        return cloudstackVolume;
    }

    @Override
    CloudStackVolume updateCloudStackVolume(CloudStackVolume cloudstackVolume) {
        //TODO
        return null;
    }

    @Override
    void deleteCloudStackVolume(CloudStackVolume cloudstackVolume) {
        //TODO
    }

    @Override
    CloudStackVolume getCloudStackVolume(CloudStackVolume cloudstackVolume) {
        //TODO
        return null;
    }

    @Override
    public AccessGroup createAccessGroup(AccessGroup accessGroup) {
        //TODO
        return null;
    }

    @Override
    public void deleteAccessGroup(AccessGroup accessGroup) {
        //TODO
    }

    @Override
    public AccessGroup updateAccessGroup(AccessGroup accessGroup) {
        //TODO
        return null;
    }

    @Override
    public AccessGroup getAccessGroup(AccessGroup accessGroup) {
        //TODO
        return null;
    }

    @Override
    void enableLogicalAccess(Map<String, String> values) {
        //TODO
    }

    @Override
    void disableLogicalAccess(Map<String, String> values) {
        //TODO
    }


    private ExportPolicy createExportPolicy(String svmName, String policyName) {
        s_logger.info("Creating export policy: {} for SVM: {}", policyName, svmName);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Create ExportPolicy object
            ExportPolicy exportPolicy = new ExportPolicy();
            exportPolicy.setName(policyName);

            // Set SVM
            Svm svm = new Svm();
            svm.setName(svmName);
            exportPolicy.setSvm(svm);

            // Create export policy
            ExportPolicy createdPolicy = nasFeignClient.createExportPolicy(authHeader,  exportPolicy);

            if (createdPolicy != null && createdPolicy.getId() != null) {
                s_logger.info("Export policy created successfully with ID: {}", createdPolicy.getId());
                return createdPolicy;
            } else {
                throw new CloudRuntimeException("Failed to create export policy: " + policyName);
            }

        } catch (FeignException e) {
            s_logger.error("Failed to create export policy: {}", policyName, e);
            throw new CloudRuntimeException("Failed to create export policy: " + e.getMessage());
        } catch (Exception e) {
            s_logger.error("Exception while creating export policy: {}", policyName, e);
            throw new CloudRuntimeException("Failed to create export policy: " + e.getMessage());
        }
    }


    private void deleteExportPolicy(String svmName, String policyName) {
        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            OntapResponse<ExportPolicy> policiesResponse = nasFeignClient.getExportPolicyResponse(authHeader);

            if (policiesResponse.getRecords() == null || policiesResponse.getRecords().isEmpty()) {
                s_logger.warn("Export policy not found for deletion: {}", policyName);
                throw new CloudRuntimeException("Export policy not found : " + policyName);
            }
            String policyId = policiesResponse.getRecords().get(0).getId().toString();
            nasFeignClient.deleteExportPolicyById(authHeader, policyId);
            s_logger.info("Export policy deleted successfully: {}", policyName);
        } catch (Exception e) {
            s_logger.error("Failed to delete export policy: {}", policyName, e);
            throw new CloudRuntimeException("Failed to delete export policy: " + policyName);
        }
    }


    private String addExportRule(String policyName, String clientMatch, String[] protocols, String[] roRule, String[] rwRule) {
        return "";
    }

    private String assignExportPolicyToVolume(String volumeUuid, String policyName) {
        s_logger.info("Assigning export policy: {} to volume: {}", policyName, volumeUuid);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            OntapResponse<ExportPolicy> policiesResponse = nasFeignClient.getExportPolicyResponse(authHeader);
            if (policiesResponse.getRecords() == null || policiesResponse.getRecords().isEmpty()) {
                throw new CloudRuntimeException("Export policy not found: " + policyName);
            }
            ExportPolicy exportPolicy = policiesResponse.getRecords().get(0);
            // Create Volume update object with NAS configuration
            Volume volumeUpdate = new Volume();
            Nas nas = new Nas();
            nas.setExportPolicy(exportPolicy);
            volumeUpdate.setNas(nas);

            volumeFeignClient.updateVolumeRebalancing(authHeader, volumeUuid, volumeUpdate);
            s_logger.info("Export policy successfully assigned to volume: {}", volumeUuid);
            return "Export policy " + policyName + " assigned to volume " + volumeUuid;

        } catch (FeignException e) {
            s_logger.error("Failed to assign export policy to volume: {}", volumeUuid, e);
            throw new CloudRuntimeException("Failed to assign export policy: " + e.getMessage());
        } catch (Exception e) {
            s_logger.error("Exception while assigning export policy to volume: {}", volumeUuid, e);
            throw new CloudRuntimeException("Failed to assign export policy: " + e.getMessage());
        }
    }

    private boolean createFile(String volumeUuid, String filePath, FileInfo fileInfo) {
        s_logger.info("Creating file: {} in volume: {}", filePath, volumeUuid);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            nasFeignClient.createFile(authHeader, volumeUuid, filePath, fileInfo);
            s_logger.info("File created successfully: {} in volume: {}", filePath, volumeUuid);
            return true;
        } catch (FeignException e) {
            s_logger.error("Failed to create file: {} in volume: {}", filePath, volumeUuid, e);
            return false;
        } catch (Exception e) {
            s_logger.error("Exception while creating file: {} in volume: {}", filePath, volumeUuid, e);
            return false;
        }
    }

    private boolean deleteFile(String volumeUuid, String filePath) {
        s_logger.info("Deleting file: {} from volume: {}", filePath, volumeUuid);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            nasFeignClient.deleteFile(authHeader, volumeUuid, filePath);
            s_logger.info("File deleted successfully: {} from volume: {}", filePath, volumeUuid);
            return true;
        } catch (FeignException e) {
            s_logger.error("Failed to delete file: {} from volume: {}", filePath, volumeUuid, e);
            return false;
        } catch (Exception e) {
            s_logger.error("Exception while deleting file: {} from volume: {}", filePath, volumeUuid, e);
            return false;
        }
    }

    private OntapResponse<FileInfo> getFileInfo(String volumeUuid, String filePath) {
        s_logger.debug("Getting file info for: {} in volume: {}", filePath, volumeUuid);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            OntapResponse<FileInfo> response = nasFeignClient.getFileResponse(authHeader, volumeUuid, filePath);
            s_logger.debug("Retrieved file info for: {} in volume: {}", filePath, volumeUuid);
            return response;
        } catch (FeignException e) {
            if (e.status() == 404) {
                s_logger.debug("File not found: {} in volume: {}", filePath, volumeUuid);
                return null;
            }
            s_logger.error("Failed to get file info: {} in volume: {}", filePath, volumeUuid, e);
            throw new CloudRuntimeException("Failed to get file info: " + e.getMessage());
        } catch (Exception e) {
            s_logger.error("Exception while getting file info: {} in volume: {}", filePath, volumeUuid, e);
            throw new CloudRuntimeException("Failed to get file info: " + e.getMessage());
        }
    }

    private boolean updateFile(String volumeUuid, String filePath, FileInfo fileInfo) {
        s_logger.info("Updating file: {} in volume: {}", filePath, volumeUuid);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            nasFeignClient.updateFile( authHeader, volumeUuid, filePath, fileInfo);
            s_logger.info("File updated successfully: {} in volume: {}", filePath, volumeUuid);
            return true;
        } catch (FeignException e) {
            s_logger.error("Failed to update file: {} in volume: {}", filePath, volumeUuid, e);
            return false;
        } catch (Exception e) {
            s_logger.error("Exception while updating file: {} in volume: {}", filePath, volumeUuid, e);
            return false;
        }
    }
}
