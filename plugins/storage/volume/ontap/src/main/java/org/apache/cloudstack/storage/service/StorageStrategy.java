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
import org.apache.cloudstack.storage.feign.client.JobFeignClient;
import org.apache.cloudstack.storage.feign.client.SvmFeignClient;
import org.apache.cloudstack.storage.feign.client.VolumeFeignClient;
import org.apache.cloudstack.storage.feign.model.Aggregate;
import org.apache.cloudstack.storage.feign.model.Job;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Storage Strategy represents the communication path for all the ONTAP storage options
 *
 * ONTAP storage operation would vary based on
 *      Supported protocols: NFS3.0, NFS4.1, FC, iSCSI, Nvme/TCP and Nvme/FC
 *      Supported platform:  Unified and Disaggregated
 */
public abstract class StorageStrategy {
    // Replace @Inject Feign clients with FeignClientFactory
    private final FeignClientFactory feignClientFactory;
    private final VolumeFeignClient volumeFeignClient;
    private final SvmFeignClient svmFeignClient;
    private final JobFeignClient jobFeignClient;

    protected OntapStorage storage;

    /**
     * Presents aggregate object for the unified storage, not eligible for disaggregated
     */
    private List<Aggregate> aggregates;

    private static final Logger s_logger = LogManager.getLogger(StorageStrategy.class);

    public StorageStrategy(OntapStorage ontapStorage) {
        storage = ontapStorage;
        String baseURL = Constants.HTTPS + storage.getManagementLIF();
        s_logger.info("Initializing StorageStrategy with base URL: " + baseURL);
        // Initialize FeignClientFactory and create clients
        this.feignClientFactory = new FeignClientFactory();
        this.volumeFeignClient = feignClientFactory.createClient(VolumeFeignClient.class, baseURL);
        this.svmFeignClient = feignClientFactory.createClient(SvmFeignClient.class, baseURL);
        this.jobFeignClient = feignClientFactory.createClient(JobFeignClient.class, baseURL);
    }

    // Connect method to validate ONTAP cluster, credentials, protocol, and SVM
    public boolean connect() {
        s_logger.info("Attempting to connect to ONTAP cluster at " + storage.getManagementLIF() + " and validate SVM " +
                storage.getSvmName() + ", protocol " + storage.getProtocol());
        //Get AuthHeader
        String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
        String svmName = storage.getSvmName();
        try {
            // Call the SVM API to check if the SVM exists
            Svm svm = new Svm();
            s_logger.info("Fetching the SVM details...");
            Map<String, Object> queryParams = Map.of(Constants.NAME, svmName, Constants.FIELDS, Constants.AGGREGATES +
                    Constants.COMMA + Constants.STATE);
            OntapResponse<Svm> svms = svmFeignClient.getSvmResponse(queryParams, authHeader);
            if (svms != null && svms.getRecords() != null && !svms.getRecords().isEmpty()) {
                svm = svms.getRecords().get(0);
            } else {
                throw new CloudRuntimeException("No SVM found on the ONTAP cluster by the name" + svmName + ".");
            }

            // Validations
            s_logger.info("Validating SVM state and protocol settings...");
            if (!Objects.equals(svm.getState(), Constants.RUNNING)) {
                s_logger.error("SVM " + svmName + " is not in running state.");
                throw new CloudRuntimeException("SVM " + svmName + " is not in running state.");
            }
            if (Objects.equals(storage.getProtocol(), Constants.NFS) && !svm.getNfsEnabled()) {
                s_logger.error("NFS protocol is not enabled on SVM " + svmName);
                throw new CloudRuntimeException("NFS protocol is not enabled on SVM " + svmName);
            } else if (Objects.equals(storage.getProtocol(), Constants.ISCSI) && !svm.getIscsiEnabled()) {
                s_logger.error("iSCSI protocol is not enabled on SVM " + svmName);
                throw new CloudRuntimeException("iSCSI protocol is not enabled on SVM " + svmName);
            }
            List<Aggregate> aggrs = svm.getAggregates();
            if (aggrs == null || aggrs.isEmpty()) {
                s_logger.error("No aggregates are assigned to SVM " + svmName);
                throw new CloudRuntimeException("No aggregates are assigned to SVM " + svmName);
            }
            this.aggregates = aggrs;
            s_logger.info("Successfully connected to ONTAP cluster and validated ONTAP details provided");
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to connect to ONTAP cluster: " + e.getMessage(), e);
        }
        return true;
    }

    // Common methods like create/delete etc., should be here

    /**
     * Creates ONTAP Flex-Volume
     * Eligible only for Unified ONTAP storage
     * throw exception in case of disaggregated ONTAP storage
     *
     * @param volumeName the name of the volume to create
     * @param size the size of the volume in bytes
     * @return the created Volume object
     */
    public Volume createStorageVolume(String volumeName, Long size) {
        s_logger.info("Creating volume: " + volumeName + " of size: " + size + " bytes");

        String svmName = storage.getSvmName();
        if (aggregates == null || aggregates.isEmpty()) {
            s_logger.error("No aggregates available to create volume on SVM " + svmName);
            throw new CloudRuntimeException("No aggregates available to create volume on SVM " + svmName);
        }
        // Get the AuthHeader
        String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());

        // Generate the Create Volume Request
        Volume volumeRequest = new Volume();
        Svm svm = new Svm();
        svm.setName(svmName);

        volumeRequest.setName(volumeName);
        volumeRequest.setSvm(svm);
        volumeRequest.setAggregates(aggregates);
        volumeRequest.setSize(size);
        // Make the POST API call to create the volume
        try {
            /*
              ONTAP created a default rule of 0.0.0.0 if no export rule are defined while creating volume
              and since in storage pool creation, cloudstack is not aware of the host , we can either create default or
              permissive rule and later update it as part of attachCluster or attachZone implementation
             */
            JobResponse jobResponse = volumeFeignClient.createVolumeWithJob(authHeader, volumeRequest);
            if (jobResponse == null || jobResponse.getJob() == null) {
                throw new CloudRuntimeException("Failed to initiate volume creation for " + volumeName);
            }
            String jobUUID = jobResponse.getJob().getUuid();

            //Create URI for GET Job API
            int jobRetryCount = 0;
            Job createVolumeJob = null;
            while(createVolumeJob == null || !createVolumeJob.getState().equals(Constants.JOB_SUCCESS)) {
                if(jobRetryCount >= Constants.JOB_MAX_RETRIES) {
                    s_logger.error("Job to create volume " + volumeName + " did not complete within expected time.");
                    throw new CloudRuntimeException("Job to create volume " + volumeName + " did not complete within expected time.");
                }

                try {
                    createVolumeJob = jobFeignClient.getJobByUUID(authHeader, jobUUID);
                    if (createVolumeJob == null) {
                        s_logger.warn("Job with UUID " + jobUUID + " not found. Retrying...");
                    } else if (createVolumeJob.getState().equals(Constants.JOB_FAILURE)) {
                        throw new CloudRuntimeException("Job to create volume " + volumeName + " failed with error: " + createVolumeJob.getMessage());
                    }
                } catch (FeignException.FeignClientException e) {
                    throw new CloudRuntimeException("Failed to fetch job status: " + e.getMessage());
                }

                jobRetryCount++;
                Thread.sleep(Constants.CREATE_VOLUME_CHECK_SLEEP_TIME); // Sleep for 2 seconds before polling again
            }
        } catch (Exception e) {
            s_logger.error("Exception while creating volume: ", e);
            throw new CloudRuntimeException("Failed to create volume: " + e.getMessage());
        }
        s_logger.info("Volume created successfully: " + volumeName);
        // Below code is to update volume uuid to storage pool mapping once and used for all other workflow saving get volume call
        OntapResponse<Volume> ontapVolume = new OntapResponse<>();
        try {
            Map<String, Object> queryParams = Map.of(Constants.NAME, volumeName);
             ontapVolume = volumeFeignClient.getVolume(authHeader, queryParams);
            if ((ontapVolume == null || ontapVolume.getRecords().isEmpty())) {
                s_logger.error("Exception while getting volume volume not found:");
                throw new CloudRuntimeException("Failed to fetch volume " + volumeName);
            }
        }catch (Exception e) {
            s_logger.error("Exception while getting volume: " + e.getMessage());
            throw new CloudRuntimeException("Failed to fetch volume: " + e.getMessage());
        }
        return ontapVolume.getRecords().get(0);
    }

    /**
     * Updates ONTAP Flex-Volume
     * Eligible only for Unified ONTAP storage
     * throw exception in case of disaggregated ONTAP storage
     *
     * @param volume the volume to update
     * @return the updated Volume object
     */
    public Volume updateStorageVolume(Volume volume)
    {
        //TODO
        return null;
    }

    /**
     * Delete ONTAP Flex-Volume
     * Eligible only for Unified ONTAP storage
     * throw exception in case of disaggregated ONTAP storage
     *
     * @param volume the volume to delete
     */
    public void deleteStorageVolume(Volume volume)
    {
        //TODO
    }

    /**
     * Gets ONTAP Flex-Volume
     * Eligible only for Unified ONTAP storage
     * throw exception in case of disaggregated ONTAP storage
     *
     * @param volume the volume to retrieve
     * @return the retrieved Volume object
     */
    public Volume getStorageVolume(Volume volume)
    {
        //TODO
        return null;
    }

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses.
     * it is going to mimic
     *     createLun       for iSCSI, FC protocols
     *     createFile      for NFS3.0 and NFS4.1 protocols
     *     createNameSpace for Nvme/TCP and Nvme/FC protocol
     * @param cloudstackVolume the CloudStack volume to create
     * @return the created CloudStackVolume object
     */
    abstract public CloudStackVolume createCloudStackVolume(CloudStackVolume cloudstackVolume);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses.
     * it is going to mimic
     *     updateLun       for iSCSI, FC protocols
     *     updateFile      for NFS3.0 and NFS4.1 protocols
     *     updateNameSpace for Nvme/TCP and Nvme/FC protocol
     * @param cloudstackVolume the CloudStack volume to update
     * @return the updated CloudStackVolume object
     */
    abstract CloudStackVolume updateCloudStackVolume(CloudStackVolume cloudstackVolume);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses.
     * it is going to mimic
     *     deleteLun       for iSCSI, FC protocols
     *     deleteFile      for NFS3.0 and NFS4.1 protocols
     *     deleteNameSpace for Nvme/TCP and Nvme/FC protocol
     * @param cloudstackVolume the CloudStack volume to delete
     */
    abstract void deleteCloudStackVolume(CloudStackVolume cloudstackVolume);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses.
     * it is going to mimic
     *     getLun       for iSCSI, FC protocols
     *     getFile      for NFS3.0 and NFS4.1 protocols
     *     getNameSpace for Nvme/TCP and Nvme/FC protocol
     * @param cloudstackVolume the CloudStack volume to retrieve
     * @return the retrieved CloudStackVolume object
     */
    abstract CloudStackVolume getCloudStackVolume(CloudStackVolume cloudstackVolume);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses
     *     createiGroup       for iSCSI and FC protocols
     *     createExportPolicy for NFS 3.0 and NFS 4.1 protocols
     *     createSubsystem    for Nvme/TCP and Nvme/FC protocols
     * @param accessGroup the access group to create
     * @return the created AccessGroup object
     */
    abstract public AccessGroup createAccessGroup(AccessGroup accessGroup);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses
     *     deleteiGroup       for iSCSI and FC protocols
     *     deleteExportPolicy for NFS 3.0 and NFS 4.1 protocols
     *     deleteSubsystem    for Nvme/TCP and Nvme/FC protocols
     * @param accessGroup the access group to delete
     */
    abstract void deleteAccessGroup(AccessGroup accessGroup);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses
     *     updateiGroup       example add/remove-Iqn   for iSCSI and FC protocols
     *     updateExportPolicy example add/remove-Rule for NFS 3.0 and NFS 4.1 protocols
     *     //TODO  for Nvme/TCP and Nvme/FC protocols
     * @param accessGroup the access group to update
     * @return the updated AccessGroup object
     */
    abstract AccessGroup updateAccessGroup(AccessGroup accessGroup);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses
     *     getiGroup       for iSCSI and FC protocols
     *     getExportPolicy for NFS 3.0 and NFS 4.1 protocols
     *     getNameSpace    for Nvme/TCP and Nvme/FC protocols
     * @param accessGroup the access group to retrieve
     * @return the retrieved AccessGroup object
     */
    abstract AccessGroup getAccessGroup(AccessGroup accessGroup);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses
     *     lunMap  for iSCSI and FC protocols
     *     //TODO  for Nvme/TCP and Nvme/FC protocols
     * @param values
     */
    abstract void enableLogicalAccess(Map<String,String> values);

    /**
     * Method encapsulates the behavior based on the opted protocol in subclasses
     *     lunUnmap  for iSCSI and FC protocols
     *     //TODO  for Nvme/TCP and Nvme/FC protocols
     * @param values
     */
    abstract void disableLogicalAccess(Map<String,String> values);
}
