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
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.component.ComponentContext;
import java.net.URI;
import java.util.List;

public abstract class StorageStrategy {
    private Utility utils;
    // Replace @Inject Feign clients with FeignClientFactory
    private FeignClientFactory feignClientFactory;
    private VolumeFeignClient volumeFeignClient;
    private SvmFeignClient svmFeignClient;
    private JobFeignClient jobFeignClient;

    protected OntapStorage storage;

    private List<Aggregate> aggregates;

    private static final Logger s_logger = LogManager.getLogger(StorageStrategy.class);

    // Default constructor for Spring
    public StorageStrategy() {
        initializeDependencies();
    }

    public StorageStrategy(OntapStorage ontapStorage) {
        storage = ontapStorage;
        initializeDependencies();
    }

    private void initializeDependencies() {
        utils = ComponentContext.inject(Utility.class);
        // Initialize FeignClientFactory and create clients
        this.feignClientFactory = new FeignClientFactory();
        this.volumeFeignClient = feignClientFactory.createClient(VolumeFeignClient.class);
        this.svmFeignClient = feignClientFactory.createClient(SvmFeignClient.class);
        this.jobFeignClient = feignClientFactory.createClient(JobFeignClient.class);

    }

    public void setStorage(OntapStorage ontapStorage) {
        this.storage = ontapStorage;
    }

    // Connect method to validate ONTAP cluster, credentials, protocol, and SVM
    public boolean connect() {
        s_logger.info(" storage object is {} ", storage.getIsDisaggregated());
        s_logger.info(" storage object is {} ", storage.getManagementLIF());
        s_logger.info(" storage object is {} ", storage.getProtocolType());
        s_logger.info(" storage object is {} ", storage.getSvmName());
        s_logger.info(" storage object is {} ", storage.getPassword());
        s_logger.info(" storage object is {} ", storage.getUsername());

        s_logger.info("Attempting to connect to ONTAP cluster at " + storage.getManagementLIF());


        s_logger.info(" util is null or not  {} ", utils);
        //Get AuthHeader
        String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());
        String svmName = storage.getSvmName();
        try {
            // Call the SVM API to check if the SVM exists
            Svm svm = new Svm();
            URI url = URI.create(Constants.HTTPS + storage.getManagementLIF() + Constants.GET_SVMs + "?name=" + svmName + "&fields=aggregates");
            OntapResponse<Svm> svms = svmFeignClient.getSvmResponse(url, authHeader);
            if (svms != null && svms.getRecords() != null && !svms.getRecords().isEmpty()) {
                svm = svms.getRecords().get(0);
            } else {
                throw new CloudRuntimeException("No SVM found on the ONTAP cluster by the name" + svmName + ".");
            }

            // Validations
//            if (!Objects.equals(svm.getState(), Constants.RUNNING)) {
//                s_logger.error("SVM " + svmName + " is not in running state.");
//                throw new CloudRuntimeException("SVM " + svmName + " is not in running state.");
//            }
//            if (Objects.equals(storage.getProtocolType(), Constants.NFS) && !svm.getNfsEnabled()) {
//                s_logger.error("NFS protocol is not enabled on SVM " + svmName);
//                throw new CloudRuntimeException("NFS protocol is not enabled on SVM " + svmName);
//            } else if (Objects.equals(storage.getProtocolType(), Constants.ISCSI) && !svm.getIscsiEnabled()) {
//                s_logger.error("iSCSI protocol is not enabled on SVM " + svmName);
//                throw new CloudRuntimeException("iSCSI protocol is not enabled on SVM " + svmName);
//            }
            List<Aggregate> aggrs = svm.getAggregates();
            if (aggrs == null || aggrs.isEmpty()) {
                s_logger.error("No aggregates are assigned to SVM " + svmName);
                throw new CloudRuntimeException("No aggregates are assigned to SVM " + svmName);
            }
            this.aggregates = aggrs;
            s_logger.info("Successfully connected to ONTAP cluster and validated ONTAP details provided");
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to connect to ONTAP cluster: " + e.getMessage());
        }
        return true;
    }

    // Common methods like create/delete etc., should be here
    public void createVolume(String volumeName, Long size) {
        s_logger.info("Creating volume: " + volumeName + " of size: " + size + " bytes");

        String svmName = storage.getSvmName();
        if (aggregates == null || aggregates.isEmpty()) {
            s_logger.error("No aggregates available to create volume on SVM " + svmName);
            throw new CloudRuntimeException("No aggregates available to create volume on SVM " + svmName);
        }
        // Get the AuthHeader
        String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());

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
            // Create URI for POST CreateVolume API
            URI url = URI.create(Constants.HTTPS + storage.getManagementLIF() + Constants.CREATE_VOLUME);
            // Call the VolumeFeignClient to create the volume
            JobResponse jobResponse = volumeFeignClient.createVolumeWithJob( authHeader, volumeRequest);
            if (jobResponse == null || jobResponse.getJob() == null) {
                throw new CloudRuntimeException("Failed to initiate volume creation for " + volumeName);
            }
            String jobUUID = jobResponse.getJob().getUuid();

            //Create URI for GET Job API
            url = URI.create(Constants.HTTPS + storage.getManagementLIF() + Constants.GET_JOB_BY_UUID);
            int jobRetryCount = 0;
            Job createVolumeJob = null;
            while (createVolumeJob == null || !createVolumeJob.getState().equals(Constants.JOB_SUCCESS)) {
                if (jobRetryCount >= Constants.JOB_MAX_RETRIES) {
                    s_logger.error("Job to create volume " + volumeName + " did not complete within expected time.");
                    throw new CloudRuntimeException("Job to create volume " + volumeName + " did not complete within expected time.");
                }

                try {
                    createVolumeJob = jobFeignClient.getJobByUUID(url, authHeader, jobUUID);
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
    }
}