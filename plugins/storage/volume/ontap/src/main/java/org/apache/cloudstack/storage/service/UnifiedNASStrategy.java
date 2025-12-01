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
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.feign.FeignClientFactory;
import org.apache.cloudstack.storage.feign.client.JobFeignClient;
import org.apache.cloudstack.storage.feign.client.NASFeignClient;
import org.apache.cloudstack.storage.feign.client.VolumeFeignClient;
import org.apache.cloudstack.storage.feign.model.ExportPolicy;
import org.apache.cloudstack.storage.feign.model.ExportRule;
import org.apache.cloudstack.storage.feign.model.FileInfo;
import org.apache.cloudstack.storage.feign.model.Job;
import org.apache.cloudstack.storage.feign.model.Nas;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UnifiedNASStrategy extends NASStrategy {

    private static final Logger s_logger = LogManager.getLogger(UnifiedNASStrategy.class);
    private final FeignClientFactory feignClientFactory;
    private final NASFeignClient nasFeignClient;
    private final VolumeFeignClient volumeFeignClient;
    private final JobFeignClient jobFeignClient;
    @Inject
    private StoragePoolDetailsDao storagePoolDetailsDao;

    public UnifiedNASStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
        String baseURL = Constants.HTTPS + ontapStorage.getManagementLIF();
        // Initialize FeignClientFactory and create NAS client
        this.feignClientFactory = new FeignClientFactory();
        // NAS client uses export policy API endpoint
        this.nasFeignClient = feignClientFactory.createClient(NASFeignClient.class, baseURL);
        this.volumeFeignClient = feignClientFactory.createClient(VolumeFeignClient.class,baseURL );
        this.jobFeignClient = feignClientFactory.createClient(JobFeignClient.class, baseURL );
    }

    public void setOntapStorage(OntapStorage ontapStorage) {
        this.storage = ontapStorage;
    }

    @Override
    public CloudStackVolume createCloudStackVolume(CloudStackVolume cloudstackVolume) {
        s_logger.info("createCloudStackVolume: Create cloudstack volume " + cloudstackVolume);
        // Skip ontap file creation for now
//        try {
//            boolean created = createFile(cloudstackVolume.getVolume().getUuid(),cloudstackVolume.getCloudstackVolName(), cloudstackVolume.getFile());
//            if(created){
//                s_logger.debug("Successfully created file in ONTAP under volume with path {} or name {}  ", cloudstackVolume.getVolume().getUuid(), cloudstackVolume.getCloudstackVolName());
//                FileInfo responseFile = cloudstackVolume.getFile();
//                responseFile.setPath(cloudstackVolume.getCloudstackVolName());
//            }else {
//                s_logger.error("File not created for volume  {}",  cloudstackVolume.getVolume().getUuid());
//                throw new CloudRuntimeException("File not created");
//            }
//
//        }catch (Exception e) {
//            s_logger.error("Exception occurred while creating file or dir: {}. Exception: {}", cloudstackVolume.getCloudstackVolName(), e.getMessage());
//            throw new CloudRuntimeException("Failed to create file: " + e.getMessage());
//        }
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

        Map<String, String> storagePooldetails = accessGroup.getStoragePooldetails();
        String svmName = storagePooldetails.get(Constants.SVM_NAME);
        Map<String, String> volumedetails = accessGroup.getVolumedetails();
        String volumeUUID = volumedetails.get(Constants.VOLUME_UUID);
        String volumeName = volumedetails.get(Constants.VOLUME_NAME);

        // Create the export policy
        ExportPolicy policyRequest = createExportPolicyRequest(accessGroup,svmName,volumeName);
        try {
            createExportPolicy(svmName, policyRequest);
            s_logger.info("ExportPolicy created: {}, now attaching this policy to storage pool volume", policyRequest.getName());
            String sanitizedName = volumeUUID.replace('-', '_');
            String ontapVolumeName = "cs_vol_" + sanitizedName;
            // get the volume from ontap using name
            try {
                // Get the AuthHeader
                String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
                Map<String, Object> queryParams = Map.of(Constants.NAME, ontapVolumeName);
                s_logger.debug("Fetching volume details for: " + volumeName);

                OntapResponse<Volume> ontapVolume = volumeFeignClient.getVolume(authHeader, queryParams);
                s_logger.debug("Feign call completed. Processing response...");

                if (ontapVolume == null) {
                    s_logger.error("OntapResponse is null for volume: " + volumeName);
                    throw new CloudRuntimeException("Failed to fetch volume " + volumeName + ": Response is null");
                }
                s_logger.debug("OntapResponse is not null. Checking records field...");

                if (ontapVolume.getRecords() == null) {
                    s_logger.error("OntapResponse.records is null for volume: " + volumeName);
                    throw new CloudRuntimeException("Failed to fetch volume " + volumeName + ": Records list is null");
                }
                s_logger.debug("Records field is not null. Size: " + ontapVolume.getRecords().size());

                if (ontapVolume.getRecords().isEmpty()) {
                    s_logger.error("OntapResponse.records is empty for volume: " + volumeName);
                    throw new CloudRuntimeException("Failed to fetch volume " + volumeName + ": No records found");
                }
                Volume onpvolume = ontapVolume.getRecords().get(0);
                s_logger.info("Volume retrieved successfully: " + volumeName + ", UUID: " + onpvolume.getUuid());
                // attach export policy to volume of storage pool
                assignExportPolicyToVolume(onpvolume.getUuid(),policyRequest.getName());
                s_logger.info("Successfully assigned exportPolicy {} to volume {}", policyRequest.getName(), volumeName);
                accessGroup.setPolicy(policyRequest);
                return accessGroup;
            } catch (Exception e) {
                s_logger.error("Exception while retrieving volume details for: " + volumeName, e);
                throw new CloudRuntimeException("Failed to fetch volume: " + volumeName + ". Error: " + e.getMessage(), e);
            }
        }catch(Exception e){
            s_logger.error("Exception occurred while creating access group: " +  e);
            throw new CloudRuntimeException("Failed to create access group: " + e);
        }
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


    private void createExportPolicy(String svmName, ExportPolicy policy) {
        s_logger.info("Creating export policy: {} for SVM: {}", policy, svmName);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            nasFeignClient.createExportPolicy(authHeader,  policy);
            try {
                Map<String, Object> queryParams = Map.of(Constants.NAME, policy.getName());
                OntapResponse<ExportPolicy> policiesResponse = nasFeignClient.getExportPolicyResponse(authHeader, queryParams);
                if (policiesResponse == null || policiesResponse.getRecords().isEmpty()) {
                    throw new CloudRuntimeException("Export policy " + policy.getName() + " was not created on ONTAP. " +
                            "Received successful response but policy does not exist.");
                }
                s_logger.info("Export policy created and verified successfully: " + policy.getName());
            } catch (FeignException e) {
                s_logger.error("Failed to verify export policy creation: " + policy.getName(), e);
                throw new CloudRuntimeException("Export policy creation verification failed: " + e.getMessage());
            }
            s_logger.info("Export policy created successfully with name {}", policy.getName());
        } catch (FeignException e) {
            s_logger.error("Failed to create export policy: {}", policy, e);
            throw new CloudRuntimeException("Failed to create export policy: " + e.getMessage());
        } catch (Exception e) {
            s_logger.error("Exception while creating export policy: {}", policy, e);
            throw new CloudRuntimeException("Failed to create export policy: " + e.getMessage());
        }
    }

    private void deleteExportPolicy(String svmName, String policyName) {
        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            Map<String, Object> queryParams = Map.of(Constants.NAME, policyName);
            OntapResponse<ExportPolicy> policiesResponse = nasFeignClient.getExportPolicyResponse(authHeader, queryParams);

            if (policiesResponse == null ) {
                s_logger.warn("Export policy not found for deletion: {}", policyName);
                throw new CloudRuntimeException("Export policy not found : " + policyName);
            }
            String policyId = String.valueOf(policiesResponse.getRecords().get(0).getId());
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

    private void assignExportPolicyToVolume(String volumeUuid, String policyName) {
        s_logger.info("Assigning export policy: {} to volume: {}", policyName, volumeUuid);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            Map<String, Object> queryParams = Map.of(Constants.NAME, policyName);
            OntapResponse<ExportPolicy> policiesResponse = nasFeignClient.getExportPolicyResponse(authHeader, queryParams);
            if (policiesResponse == null || policiesResponse.getRecords().isEmpty()) {
                s_logger.error("Export policy not found for assigning rule: {}", policyName);
                throw new CloudRuntimeException("Export policy not found: " + policyName);
            }

            // Create Volume update object with NAS configuration
            Volume volumeUpdate = new Volume();
            Nas nas = new Nas();
            ExportPolicy policy = new ExportPolicy();
            policy.setName(policyName);
            nas.setExportPolicy(policy);
            volumeUpdate.setNas(nas);

            try {
            /*
              ONTAP created a default rule of 0.0.0.0 if no export rule are defined while creating volume
              and since in storage pool creation, cloudstack is not aware of the host , we can either create default or
              permissive rule and later update it as part of attachCluster or attachZone implementation
             */
                JobResponse jobResponse = volumeFeignClient.updateVolumeRebalancing(authHeader, volumeUuid, volumeUpdate);
                if (jobResponse == null || jobResponse.getJob() == null) {
                    throw new CloudRuntimeException("Failed to attach policy " + policyName + "to volume " + volumeUuid);
                }
                String jobUUID = jobResponse.getJob().getUuid();

                //Create URI for GET Job API
                int jobRetryCount = 0;
                Job createVolumeJob = null;
                while(createVolumeJob == null || !createVolumeJob.getState().equals(Constants.JOB_SUCCESS)) {
                    if(jobRetryCount >= Constants.JOB_MAX_RETRIES) {
                        s_logger.error("Job to update volume " + volumeUuid + " did not complete within expected time.");
                        throw new CloudRuntimeException("Job to update volume " + volumeUuid + " did not complete within expected time.");
                    }

                    try {
                        createVolumeJob = jobFeignClient.getJobByUUID(authHeader, jobUUID);
                        if (createVolumeJob == null) {
                            s_logger.warn("Job with UUID " + jobUUID + " not found. Retrying...");
                        } else if (createVolumeJob.getState().equals(Constants.JOB_FAILURE)) {
                            throw new CloudRuntimeException("Job to update volume " + volumeUuid + " failed with error: " + createVolumeJob.getMessage());
                        }
                    } catch (FeignException.FeignClientException e) {
                        throw new CloudRuntimeException("Failed to fetch job status: " + e.getMessage());
                    }

                    jobRetryCount++;
                    Thread.sleep(Constants.CREATE_VOLUME_CHECK_SLEEP_TIME); // Sleep for 2 seconds before polling again
                }
            } catch (Exception e) {
                s_logger.error("Exception while updating volume: ", e);
                throw new CloudRuntimeException("Failed to update volume: " + e.getMessage());
            }

            s_logger.info("Export policy successfully assigned to volume: {}", volumeUuid);
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
        } catch (FeignException e){
            if (e.status() == 404) {
                s_logger.debug("File not found: {} in volume: {}", filePath, volumeUuid);
                return null;
            }
            s_logger.error("Failed to get file info: {} in volume: {}", filePath, volumeUuid, e);
            throw new CloudRuntimeException("Failed to get file info: " + e.getMessage());
        } catch (Exception e){
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
        } catch (Exception e){
            s_logger.error("Exception while updating file: {} in volume: {}", filePath, volumeUuid, e);
            return false;
        }
    }

    private String generateExportPolicyName(String svmName, String volumeName){
        return Constants.EXPORT + Constants.HYPHEN + svmName + Constants.HYPHEN + volumeName;
    }

    private ExportPolicy createExportPolicyRequest(AccessGroup accessGroup,String svmName , String volumeName){

        String exportPolicyName = generateExportPolicyName(svmName,volumeName);
        ExportPolicy exportPolicy = new ExportPolicy();

        List<ExportRule> rules = new ArrayList<>();
        ExportRule exportRule = new ExportRule();

        List<ExportRule.ExportClient> exportClients = new ArrayList<>();
//        List<HostVO> hosts = accessGroup.getHostsToConnect();
//        for (HostVO host : hosts) {
//            String hostStorageIp = host.getStorageIpAddress();
//            String ip = (hostStorageIp != null && !hostStorageIp.isEmpty())
//                    ? hostStorageIp
//                    : host.getPrivateIpAddress();
//            String ipToUse = ip + "/31";
//            ExportRule.ExportClient exportClient = new ExportRule.ExportClient();
//            exportClient.setMatch(ipToUse);
//            exportClients.add(exportClient);
//        }

        ExportRule.ExportClient exportClient = new ExportRule.ExportClient();
        String ipToUse = "0.0.0.0/0";
        exportClient.setMatch(ipToUse);
        exportClients.add(exportClient);
        exportRule.setClients(exportClients);
        exportRule.setProtocols(List.of(ExportRule.ProtocolsEnum.any));
        exportRule.setRoRule(List.of("sys")); // Use sys (Unix UID/GID) authentication for NFS
        exportRule.setRwRule(List.of("sys")); // Use sys (Unix UID/GID) authentication for NFS
        exportRule.setSuperuser(List.of("sys")); // Allow root/superuser access with sys auth
        rules.add(exportRule);

        Svm svm = new Svm();
        svm.setName(svmName);
        exportPolicy.setSvm(svm);
        exportPolicy.setRules(rules);
        exportPolicy.setName(exportPolicyName);

        return exportPolicy;
    }
}
