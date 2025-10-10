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

package org.apache.cloudstack.storage.provider;

import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.feign.client.VolumeFeignClient;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.request.VolumeRequestDTO;
import org.apache.cloudstack.storage.feign.model.response.JobResponseDTO;
import org.apache.cloudstack.storage.service.NASStrategy;
import org.apache.cloudstack.storage.service.SANStrategy;
import org.apache.cloudstack.storage.service.UnifiedNASStrategy;
import org.apache.cloudstack.storage.service.UnifiedSANStrategy;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StorageProviderManager {
    @Autowired
    private Utility utils;

    @Autowired
    private VolumeFeignClient volumeFeignClient;

    private final NASStrategy nasStrategy;
    private final SANStrategy sanStrategy;
    private static final Logger s_logger = (Logger) LogManager.getLogger(StorageProviderManager.class);

    private final String username;
    private final String password;
    private final String svmName;
    private final String aggrName;

    public StorageProviderManager(Map<String, String> details, String protocol) {
        this.svmName = details.get("svmName");
        this.aggrName = details.get("aggrName");
        this.username = details.get("username");
        this.password = details.get("password");
        if (protocol.equalsIgnoreCase(Storage.StoragePoolType.NetworkFilesystem.name())) {
            this.nasStrategy = new UnifiedNASStrategy(details);
            this.sanStrategy = null;
        } else if (protocol.equalsIgnoreCase(Storage.StoragePoolType.Iscsi.name())) {
            this.sanStrategy = new UnifiedSANStrategy(details);
            this.nasStrategy = null;
        } else {
            this.nasStrategy = null;
            this.sanStrategy = null;
            throw new CloudRuntimeException("Unsupported protocol: " + protocol);
        }
    }

    // Connect method to validate ONTAP cluster, credentials, protocol, and SVM
    public boolean connect(Map<String, String> details) {
        // 1. Check if ONTAP cluster is reachable ==> Use GET cluster API
        // 2. Validate credentials
        // 3. Check protocol support
        // 4. Check if SVM with given name exists
        // Use Feign client and models for actual implementation
        // Return true if all validations pass, false otherwise

        return false;
    }

    // Common methods like create/delete etc., should be here
    public void createVolume(String volumeName, int size) {
       // TODO: Call the ontap feign client for creating volume here
        // Get the AuthHeader
        String authHeader = utils.generateAuthHeader(username, password);

        // Generate the Create Volume Request
        VolumeRequestDTO volumeRequest = new VolumeRequestDTO();
        VolumeRequestDTO.SvmDTO svm = new VolumeRequestDTO.SvmDTO();
        VolumeRequestDTO.AggregateDTO aggr = new VolumeRequestDTO.AggregateDTO();
        svm.setName(svmName);
        aggr.setName(aggrName); // TODO: Get aggr list and pick the least used one

        volumeRequest.setName(volumeName);
        volumeRequest.setSvm(svm);
        volumeRequest.setAggregates((List<VolumeRequestDTO.AggregateDTO>) aggr);
        volumeRequest.setSize(size);
        // Make the POST API call to create the volume
        try {
            JobResponseDTO response = volumeFeignClient.createVolumeWithJob(authHeader, volumeRequest);
            //TODO: Add code to poll the job status until it is completed/ a timeout of 3 mins

        } catch (Exception e) {
            s_logger.error("Exception while creating volume: ", e);
            throw new CloudRuntimeException("Failed to create volume: " + e.getMessage());
        }
        s_logger.info("Volume created successfully: " + volumeName);
    }
}
