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

import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import feign.FeignException;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.feign.model.ExportPolicy;
import org.apache.cloudstack.storage.feign.model.ExportRule;
import org.apache.cloudstack.storage.feign.model.Job;
import org.apache.cloudstack.storage.feign.model.Nas;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.OntapStorageUtils;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UnifiedNASStrategy extends NASStrategy {

    private static final Logger s_logger = LogManager.getLogger(UnifiedNASStrategy.class);
    @Inject private VolumeDao volumeDao;
    @Inject private EndPointSelector epSelector;
    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;

    public UnifiedNASStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
    }

    public void setOntapStorage(OntapStorage ontapStorage) {
        this.storage = ontapStorage;
    }

    @Override
    public CloudStackVolume createCloudStackVolume(CloudStackVolume cloudstackVolume) {
        if (cloudstackVolume == null || cloudstackVolume.getVolumeInfo() == null) {
            throw new CloudRuntimeException(" Failed to create volume, invalid request");
        }
        DataObject volumeObject = cloudstackVolume.getVolumeInfo();
        VolumeVO volumeVO = volumeDao.findById(volumeObject.getId());
        if (volumeVO == null) {
            throw new CloudRuntimeException(" Failed to create volume, volume not found");
        }
        EndPoint endPoint = epSelector.select(volumeObject);
        if (endPoint == null) {
            throw new CloudRuntimeException(" Failed to create volume, no endpoint available");
        }
        CreateObjectCommand cmd = new CreateObjectCommand(volumeObject.getTO());
        Answer answer = endPoint.sendMessage(cmd);
        if (answer == null || !answer.getResult()) {
            String detail = answer != null ? answer.getDetails() : "no answer";
            throw new CloudRuntimeException(" Failed to create volume: " + detail);
        }
        volumeDao.update(volumeObject.getId(), volumeVO);
        return cloudstackVolume;
    }

    @Override
    CloudStackVolume updateCloudStackVolume(CloudStackVolume cloudstackVolume) {
        return null;
    }

    @Override
    public void deleteCloudStackVolume(CloudStackVolume cloudstackVolume) {
        if (cloudstackVolume == null || cloudstackVolume.getVolumeInfo() == null) {
            throw new CloudRuntimeException(" Failed to delete volume, invalid request");
        }
        DataObject volumeInfo = cloudstackVolume.getVolumeInfo();
        EndPoint endpoint = epSelector.select(volumeInfo);
        if (endpoint == null) {
            throw new CloudRuntimeException(" Failed to delete volume, no endpoint available");
        }
        DeleteCommand cmd = new DeleteCommand(volumeInfo.getTO());
        Answer answer = endpoint.sendMessage(cmd);
        if (answer == null || !answer.getResult()) {
            String detail = answer != null ? answer.getDetails() : "no answer";
            throw new CloudRuntimeException(" Failed to delete volume: " + detail);
        }
    }

    @Override
    public void copyCloudStackVolume(CloudStackVolume cloudstackVolume) {

    }

    @Override
    public CloudStackVolume getCloudStackVolume(Map<String, String> cloudStackVolumeMap) {
        return null;
    }

    @Override
    public JobResponse revertSnapshotForCloudStackVolume(String snapshotName, String flexVolUuid, String snapshotUuid, String volumePath, String lunUuid, String flexVolName) {
        return null;
    }

    @Override
    public AccessGroup createAccessGroup(AccessGroup accessGroup) {
        s_logger.info("createAccessGroup: Create access group {}: " , accessGroup);

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(accessGroup.getStoragePoolId());
        String svmName = details.get(Constants.SVM_NAME);
        String volumeUUID = details.get(Constants.VOLUME_UUID);
        String volumeName = details.get(Constants.VOLUME_NAME);

        // Create the export policy
        ExportPolicy policyRequest = createExportPolicyRequest(accessGroup,svmName,volumeName);
        try {
            ExportPolicy createdPolicy = createExportPolicy(svmName, policyRequest);
            s_logger.info("createAccessGroup: ExportPolicy created: {}, now attaching this policy to storage pool volume", createdPolicy.getName());
            // attach export policy to volume of storage pool
            assignExportPolicyToVolume(volumeUUID,createdPolicy.getName());
            // save the export policy details in storage pool details
            storagePoolDetailsDao.addDetail(accessGroup.getStoragePoolId(), Constants.EXPORT_POLICY_ID, String.valueOf(createdPolicy.getId()), true);
            storagePoolDetailsDao.addDetail(accessGroup.getStoragePoolId(), Constants.EXPORT_POLICY_NAME, createdPolicy.getName(), true);
            s_logger.info("Successfully assigned exportPolicy {} to volume {}", policyRequest.getName(), volumeName);
            accessGroup.setPolicy(policyRequest);
            return accessGroup;
        }catch(Exception e){
            s_logger.error("createAccessGroup: Exception occurred while creating access group: " +  e);
            throw new CloudRuntimeException("Failed to create access group: " + e);
        }
    }

    @Override
    public void deleteAccessGroup(AccessGroup accessGroup) {
        s_logger.info("deleteAccessGroup: Deleting export policy");

        if (accessGroup == null) {
            throw new CloudRuntimeException("Invalid accessGroup object - accessGroup is null");
        }

        try {
            Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(accessGroup.getStoragePoolId());
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Determine export policy attached to the storage pool
            String exportPolicyName = details.get(Constants.EXPORT_POLICY_NAME);
            String exportPolicyId = details.get(Constants.EXPORT_POLICY_ID);

            try {
                nasFeignClient.deleteExportPolicyById(authHeader,exportPolicyId);
                s_logger.info("deleteAccessGroup: Successfully deleted export policy '{}'", exportPolicyName);
            } catch (Exception e) {
                s_logger.error("deleteAccessGroup: Failed to delete export policy. Exception: {}", e.getMessage(), e);
                throw new CloudRuntimeException("Failed to delete export policy: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            s_logger.error("deleteAccessGroup: Failed to delete export policy. Exception: {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to delete export policy: " + e.getMessage(), e);
        }
    }

    @Override
    public AccessGroup updateAccessGroup(AccessGroup accessGroup) {
        return null;
    }

    @Override
    public AccessGroup getAccessGroup(Map<String, String> values) {
        return null;
    }

    @Override
    public Map <String, String> enableLogicalAccess(Map<String, String> values) {
        return null;
    }

    @Override
    public void disableLogicalAccess(Map<String, String> values) {
    }

    @Override
    public Map<String, String> getLogicalAccess(Map<String, String> values) {
        return null;
    }

    private ExportPolicy createExportPolicy(String svmName, ExportPolicy policy) {
        s_logger.info("createExportPolicy: Creating export policy: {} for SVM: {}", policy, svmName);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            nasFeignClient.createExportPolicy(authHeader,  policy);
            OntapResponse<ExportPolicy> policiesResponse = null;
            try {
                Map<String, Object> queryParams = Map.of(Constants.NAME, policy.getName());
                policiesResponse = nasFeignClient.getExportPolicyResponse(authHeader, queryParams);
                if (policiesResponse == null || policiesResponse.getRecords().isEmpty()) {
                    throw new CloudRuntimeException("Export policy " + policy.getName() + " was not created on ONTAP. " +
                            "Received successful response but policy does not exist.");
                }
                s_logger.info("createExportPolicy: Export policy created and verified successfully: " + policy.getName());
            } catch (FeignException e) {
                s_logger.error("createExportPolicy: Failed to verify export policy creation: " + policy.getName(), e);
                throw new CloudRuntimeException("Export policy creation verification failed: " + e.getMessage());
            }
            s_logger.info("createExportPolicy: Export policy created successfully with name {}", policy.getName());
            return policiesResponse.getRecords().get(0);
        } catch (FeignException e) {
            s_logger.error("createExportPolicy: Failed to create export policy: {}", policy, e);
            throw new CloudRuntimeException("Failed to create export policy: " + e.getMessage());
        } catch (Exception e) {
            s_logger.error("createExportPolicy: Exception while creating export policy: {}", policy, e);
            throw new CloudRuntimeException("Failed to create export policy: " + e.getMessage());
        }
    }

    private void assignExportPolicyToVolume(String volumeUuid, String policyName) {
        s_logger.info("assignExportPolicyToVolume: Assigning export policy: {} to volume: {}", policyName, volumeUuid);

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create Volume update object with NAS configuration
            Volume volumeUpdate = new Volume();
            Nas nas = new Nas();
            ExportPolicy policy = new ExportPolicy();
            policy.setName(policyName);
            nas.setExportPolicy(policy);
            volumeUpdate.setNas(nas);

            try {
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
                        s_logger.error("assignExportPolicyToVolume: Job to update volume " + volumeUuid + " did not complete within expected time.");
                        throw new CloudRuntimeException("Job to update volume " + volumeUuid + " did not complete within expected time.");
                    }
                    try {
                        createVolumeJob = jobFeignClient.getJobByUUID(authHeader, jobUUID);
                        if (createVolumeJob == null) {
                            s_logger.warn("assignExportPolicyToVolume: Job with UUID " + jobUUID + " not found. Retrying...");
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
                s_logger.error("assignExportPolicyToVolume: Exception while updating volume: ", e);
                throw new CloudRuntimeException("Failed to update volume: " + e.getMessage());
            }
            s_logger.info("assignExportPolicyToVolume: Export policy successfully assigned to volume: {}", volumeUuid);
        } catch (FeignException e) {
            s_logger.error("assignExportPolicyToVolume: Failed to assign export policy to volume: {}", volumeUuid, e);
            throw new CloudRuntimeException("Failed to assign export policy: " + e.getMessage());
        } catch (Exception e) {
            s_logger.error("assignExportPolicyToVolume: Exception while assigning export policy to volume: {}", volumeUuid, e);
            throw new CloudRuntimeException("Failed to assign export policy: " + e.getMessage());
        }
    }

    private ExportPolicy createExportPolicyRequest(AccessGroup accessGroup,String svmName , String volumeName){

        String exportPolicyName = OntapStorageUtils.generateExportPolicyName(svmName,volumeName);
        ExportPolicy exportPolicy = new ExportPolicy();

        List<ExportRule> rules = new ArrayList<>();
        ExportRule exportRule = new ExportRule();

        List<ExportRule.ExportClient> exportClients = new ArrayList<>();
        List<HostVO> hosts = accessGroup.getHostsToConnect();
        for (HostVO host : hosts) {
            String hostStorageIp = host.getStorageIpAddress();
            String ip = (hostStorageIp != null && !hostStorageIp.isEmpty())
                    ? hostStorageIp
                    : host.getPrivateIpAddress();
            String ipToUse = ip + "/32";
            ExportRule.ExportClient exportClient = new ExportRule.ExportClient();
            exportClient.setMatch(ipToUse);
            exportClients.add(exportClient);
        }
        exportRule.setClients(exportClients);
        exportRule.setProtocols(List.of(ExportRule.ProtocolsEnum.ANY));
        exportRule.setRoRule(List.of("sys"));
        exportRule.setRwRule(List.of("sys"));
        exportRule.setSuperuser(List.of("sys"));
        rules.add(exportRule);

        Svm svm = new Svm();
        svm.setName(svmName);
        exportPolicy.setSvm(svm);
        exportPolicy.setRules(rules);
        exportPolicy.setName(exportPolicyName);

        return exportPolicy;
    }
}
