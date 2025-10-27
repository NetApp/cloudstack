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
import org.apache.cloudstack.storage.feign.client.NASFeignClient;
import org.apache.cloudstack.storage.feign.client.SvmFeignClient;
import org.apache.cloudstack.storage.feign.client.VolumeFeignClient;
import org.apache.cloudstack.storage.feign.model.*;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.net.URI;

public class UnifiedNASStrategy extends NASStrategy{

    @Inject
    private Utility utils;

    @Inject
    private NASFeignClient nasFeignClient;

    @Inject
    private SvmFeignClient svmFeignClient;

    @Inject
    private VolumeFeignClient volumeFeignClient;

    private static final Logger s_logger = LogManager.getLogger(NASStrategy.class);
    public UnifiedNASStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
    }

    @Override
    public String createExportPolicy(String svmName, String policyName) {
        s_logger.info("Creating export policy: {} for SVM: {}", policyName, svmName);

        try {
            // Get AuthHeader
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Create ExportPolicy object
            ExportPolicy exportPolicy = new ExportPolicy();
            exportPolicy.setName(policyName);

            // Set SVM
            Svm svm = new Svm();
            svm.setName(svmName);
            exportPolicy.setSvm(svm);

            // Create URI for export policy creation
            URI url = URI.create(Constants.HTTPS + storage.getManagementLIF() + "/api/protocols/nfs/export-policies");

            // Create export policy
            ExportPolicy createdPolicy = nasFeignClient.createExportPolicy(url, authHeader, true, exportPolicy);

            if (createdPolicy != null && createdPolicy.getId() != null) {
                s_logger.info("Export policy created successfully with ID: {}", createdPolicy.getId());
                return createdPolicy.getId().toString();
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

    @Override
    public boolean deleteExportPolicy(String svmName, String policyName) {
        try {
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Get policy ID first
            URI getUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/protocols/nfs/export-policies?name=" + policyName + "&svm.name=" + svmName);

            OntapResponse<ExportPolicy> policiesResponse = nasFeignClient.getExportPolicyResponse(getUrl, authHeader);

            if (policiesResponse.getRecords() == null || policiesResponse.getRecords().isEmpty()) {
                s_logger.warn("Export policy not found for deletion: {}", policyName);
                return false;
            }

            String policyId = policiesResponse.getRecords().get(0).getId().toString();

            // Delete the policy
            URI deleteUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/protocols/nfs/export-policies/" + policyId);

            nasFeignClient.deleteExportPolicyById(deleteUrl, authHeader, policyId);

            s_logger.info("Export policy deleted successfully: {}", policyName);
            return true;

        } catch (Exception e) {
            s_logger.error("Failed to delete export policy: {}", policyName, e);
            return false;
        }
    }

    @Override
    public boolean exportPolicyExists(String svmName, String policyName) {
        try {
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());
            URI url = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/protocols/nfs/export-policies?name=" + policyName + "&svm.name=" + svmName);

            OntapResponse<ExportPolicy> response = nasFeignClient.getExportPolicyResponse(url, authHeader);
            return response.getRecords() != null && !response.getRecords().isEmpty();

        } catch (Exception e) {
            s_logger.warn("Error checking export policy existence: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String addExportRule(String policyName, String clientMatch, String[] protocols, String[] roRule, String[] rwRule) {
        return "";
    }

    @Override
    public String assignExportPolicyToVolume(String volumeUuid, String policyName) {
        s_logger.info("Assigning export policy: {} to volume: {}", policyName, volumeUuid);

        try {
            // Get AuthHeader
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // First, get the export policy by name
            URI getPolicyUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/protocols/nfs/export-policies?name=" + policyName + "&svm.name=" + storage.getSvmName());

            OntapResponse<ExportPolicy> policiesResponse = nasFeignClient.getExportPolicyResponse(getPolicyUrl, authHeader);

            if (policiesResponse.getRecords() == null || policiesResponse.getRecords().isEmpty()) {
                throw new CloudRuntimeException("Export policy not found: " + policyName);
            }

            ExportPolicy exportPolicy = policiesResponse.getRecords().get(0);

            // Create Volume update object with NAS configuration
            Volume volumeUpdate = new Volume();
            Nas nas = new Nas();
            nas.setExportPolicy(exportPolicy);
            volumeUpdate.setNas(nas);

            // Update the volume
            URI updateVolumeUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/storage/volumes/" + volumeUuid);

            volumeFeignClient.updateVolumeRebalancing(updateVolumeUrl, authHeader, volumeUuid, volumeUpdate);

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

    @Override
    public String enableNFS(String svmUuid) {
        s_logger.info("Enabling NFS on SVM: {}", svmUuid);

        try {
            // Get AuthHeader
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Create SVM update object to enable NFS
            Svm svmUpdate = new Svm();
            svmUpdate.setNfsEnabled(true);

            // Update the SVM to enable NFS
            URI updateSvmUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/svm/svms/" + svmUuid);

            svmFeignClient.updateSVM(updateSvmUrl, authHeader, svmUpdate);

            s_logger.info("NFS successfully enabled on SVM: {}", svmUuid);
            return "NFS enabled on SVM: " + svmUuid;

        } catch (FeignException e) {
            s_logger.error("Failed to enable NFS on SVM: {}", svmUuid, e);
            throw new CloudRuntimeException("Failed to enable NFS: " + e.getMessage());
        } catch (Exception e) {
            s_logger.error("Exception while enabling NFS on SVM: {}", svmUuid, e);
            throw new CloudRuntimeException("Failed to enable NFS: " + e.getMessage());
        }
    }

    // TODO should we return boolean or string ?
    private boolean createFile(String volumeUuid, String filePath, Long fileSize) {
        s_logger.info("Creating file: {} in volume: {}", filePath, volumeUuid);

        try {
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            FileInfo fileInfo = new FileInfo();
            fileInfo.setPath(filePath);
            fileInfo.setType(FileInfo.TypeEnum.FILE);

            if (fileSize != null && fileSize > 0) {
                fileInfo.setSize(fileSize);
            }

            URI createFileUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/storage/volumes/" + volumeUuid + "/files" + filePath);

            nasFeignClient.createFile(createFileUrl, authHeader, volumeUuid, filePath, fileInfo);

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
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Check if file exists first
            if (!fileExists(volumeUuid, filePath)) {
                s_logger.warn("File does not exist: {} in volume: {}", filePath, volumeUuid);
                return false;
            }

            URI deleteFileUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/storage/volumes/" + volumeUuid + "/files" + filePath);

            nasFeignClient.deleteFile(deleteFileUrl, authHeader, volumeUuid, filePath);

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

    private boolean fileExists(String volumeUuid, String filePath) {
        s_logger.debug("Checking if file exists: {} in volume: {}", filePath, volumeUuid);

        try {
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Build URI for file info retrieval - volume-specific endpoint
            URI getFileUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/storage/volumes/" + volumeUuid + "/files" + filePath);

            nasFeignClient.getFileResponse(getFileUrl, authHeader, volumeUuid, filePath);

            s_logger.debug("File exists: {} in volume: {}", filePath, volumeUuid);
            return true;

        } catch (FeignException e) {
            // TODO check the status code while testing for file not found error
            if (e.status() == 404) {
                s_logger.debug("File does not exist: {} in volume: {}", filePath, volumeUuid);
                return false;
            }
            s_logger.error("Error checking file existence: {} in volume: {}", filePath, volumeUuid, e);
            return false;
        } catch (Exception e) {
            s_logger.error("Exception while checking file existence: {} in volume: {}", filePath, volumeUuid, e);
            return false;
        }
    }

    private OntapResponse<FileInfo> getFileInfo(String volumeUuid, String filePath) {
        s_logger.debug("Getting file info for: {} in volume: {}", filePath, volumeUuid);

        try {
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            URI getFileUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/storage/volumes/" + volumeUuid + "/files" + filePath);

            OntapResponse<FileInfo> response = nasFeignClient.getFileResponse(getFileUrl, authHeader, volumeUuid, filePath);

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
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

            URI updateFileUrl = URI.create(Constants.HTTPS + storage.getManagementLIF() +
                    "/api/storage/volumes/" + volumeUuid + "/files" + filePath);

            nasFeignClient.updateFile(updateFileUrl, authHeader, volumeUuid, filePath, fileInfo);

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
