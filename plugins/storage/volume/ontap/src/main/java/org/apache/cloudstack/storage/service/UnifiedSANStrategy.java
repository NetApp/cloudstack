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
import org.apache.cloudstack.storage.feign.FeignClientFactory;
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.LunMap;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

public class UnifiedSANStrategy extends SANStrategy {

    private static final Logger s_logger = LogManager.getLogger(UnifiedSANStrategy.class);
    // Replace @Inject Feign client with FeignClientFactory
    private final FeignClientFactory feignClientFactory;
    private final SANFeignClient sanFeignClient;

    public UnifiedSANStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
        String baseURL = Constants.HTTPS + ontapStorage.getManagementLIF();
        // Initialize FeignClientFactory and create SAN client
        this.feignClientFactory = new FeignClientFactory();
        this.sanFeignClient = feignClientFactory.createClient(SANFeignClient.class, baseURL);
    }

    public void setOntapStorage(OntapStorage ontapStorage) {
        this.storage = ontapStorage;
    }

    @Override
    public CloudStackVolume createCloudStackVolume(CloudStackVolume cloudstackVolume) {
        s_logger.info("createCloudStackVolume : Creating Lun with cloudstackVolume request {} ", cloudstackVolume);
        if (cloudstackVolume == null || cloudstackVolume.getLun() == null) {
            s_logger.error("createCloudStackVolume: LUN creation failed. Invalid request: {}", cloudstackVolume);
            throw new CloudRuntimeException("createCloudStackVolume : Failed to create Lun, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create URI for lun creation
            //TODO: It is possible that Lun creation will take time and we may need to handle through async job.
            OntapResponse<Lun> createdLun = sanFeignClient.createLun(authHeader, true, cloudstackVolume.getLun());
            if (createdLun == null || createdLun.getRecords() == null || createdLun.getRecords().size() == 0) {
                s_logger.error("createCloudStackVolume: LUN creation failed for Lun {}", cloudstackVolume.getLun().getName());
                throw new CloudRuntimeException("Failed to create Lun: " + cloudstackVolume.getLun().getName());
            }
            Lun lun = createdLun.getRecords().get(0);
            s_logger.debug("createCloudStackVolume: LUN created successfully. Lun: {}", lun);
            s_logger.info("createCloudStackVolume: LUN created successfully. LunName: {}", lun.getName());

            CloudStackVolume createdCloudStackVolume = new CloudStackVolume();
            createdCloudStackVolume.setLun(lun);
            return createdCloudStackVolume;
        } catch (Exception e) {
            s_logger.error("Exception occurred while creating LUN: {}, Exception: {}", cloudstackVolume.getLun().getName(), e.getMessage());
            throw new CloudRuntimeException("Failed to create Lun: " + e.getMessage());
        }
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
    public CloudStackVolume getCloudStackVolume(Map<String, String> values) {
        s_logger.info("getCloudStackVolume : fetching Lun");
        s_logger.debug("getCloudStackVolume : fetching Lun with params {} ", values);
        if (values == null || values.isEmpty()) {
            s_logger.error("getCloudStackVolume: get Lun failed. Invalid request: {}", values);
            throw new CloudRuntimeException("getCloudStackVolume : get Lun Failed, invalid request");
        }
        String svmName = values.get(Constants.SVM_DOT_NAME);
        String lunName = values.get(Constants.NAME);
        if(svmName == null || lunName == null || svmName.isEmpty() || lunName.isEmpty()) {
            s_logger.error("getCloudStackVolume: get Lun failed. Invalid svm:{} or Lun name: {}", svmName, lunName);
            throw new CloudRuntimeException("getCloudStackVolume : Failed to get Lun, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // get Igroup
            Map<String, Object> queryParams = Map.of(Constants.SVM_DOT_NAME, svmName, Constants.NAME, lunName);
            OntapResponse<Lun> lunResponse = sanFeignClient.getLunResponse(authHeader, queryParams);
            if (lunResponse == null || lunResponse.getRecords() == null || lunResponse.getRecords().size() == 0) {
                s_logger.error("getCloudStackVolume: Failed to fetch Lun");
                throw new CloudRuntimeException("getCloudStackVolume: Failed to fetch Lun");
            }
            Lun lun = lunResponse.getRecords().get(0);
            s_logger.debug("getCloudStackVolume: Lun Details : {}", lun);
            s_logger.info("getCloudStackVolume: Fetched the Lun successfully. LunName: {}", lun.getName());

            CloudStackVolume cloudStackVolume = new CloudStackVolume();
            cloudStackVolume.setLun(lun);
            return cloudStackVolume;
        } catch (Exception e) {
            s_logger.error("Exception occurred while fetching Lun, Exception: {}", e.getMessage());
            throw new CloudRuntimeException("Failed to fetch Lun details: " + e.getMessage());
        }
    }

    @Override
    public AccessGroup createAccessGroup(AccessGroup accessGroup) {
        s_logger.info("createAccessGroup : Create Igroup");
        s_logger.debug("createAccessGroup : Creating Igroup with access group request {} ", accessGroup);
        if (accessGroup == null || accessGroup.getIgroup() == null) {
            s_logger.error("createAccessGroup: Igroup creation failed. Invalid request: {}", accessGroup);
            throw new CloudRuntimeException("createAccessGroup : Failed to create Igroup, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create Igroup
            OntapResponse<Igroup> createdIgroup = sanFeignClient.createIgroup(authHeader, true, accessGroup.getIgroup());
            s_logger.debug("createAccessGroup: createdIgroup: {}", createdIgroup);
            s_logger.debug("createAccessGroup: createdIgroup Records: {}", createdIgroup.getRecords());
            if (createdIgroup == null || createdIgroup.getRecords() == null || createdIgroup.getRecords().size() == 0) {
                s_logger.error("createAccessGroup: Igroup creation failed for Igroup Name {}", accessGroup.getIgroup().getName());
                throw new CloudRuntimeException("Failed to create Igroup: " + accessGroup.getIgroup().getName());
            }
            Igroup igroup = createdIgroup.getRecords().get(0);
            s_logger.debug("createAccessGroup: Igroup created successfully. Igroup: {}", igroup);
            s_logger.info("createAccessGroup: Igroup created successfully. IgroupName: {}", igroup.getName());

            AccessGroup createdAccessGroup = new AccessGroup();
            createdAccessGroup.setIgroup(igroup);
            return createdAccessGroup;
        } catch (Exception e) {
            s_logger.error("Exception occurred while creating Igroup: {}, Exception: {}", accessGroup.getIgroup().getName(), e.getMessage());
            throw new CloudRuntimeException("Failed to create Igroup: " + e.getMessage());
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

    public AccessGroup getAccessGroup(Map<String, String> values) {
        s_logger.info("getAccessGroup : fetch Igroup");
        s_logger.debug("getAccessGroup : fetching Igroup with params {} ", values);
        if (values == null || values.isEmpty()) {
            s_logger.error("getAccessGroup: get Igroup failed. Invalid request: {}", values);
            throw new CloudRuntimeException("getAccessGroup : get Igroup Failed, invalid request");
        }
        String svmName = values.get(Constants.SVM_DOT_NAME);
        String igroupName = values.get(Constants.IGROUP_DOT_NAME);
        if(svmName == null || igroupName == null || svmName.isEmpty() || igroupName.isEmpty()) {
            s_logger.error("getAccessGroup: get Igroup failed. Invalid svm:{} or igroup name: {}", svmName, igroupName);
            throw new CloudRuntimeException("getAccessGroup : Failed to get Igroup, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // get Igroup
            Map<String, Object> queryParams = Map.of(Constants.SVM_DOT_NAME, svmName, Constants.IGROUP_DOT_NAME, igroupName);
            OntapResponse<Igroup> igroupResponse = sanFeignClient.getIgroupResponse(authHeader, queryParams);
            if (igroupResponse == null || igroupResponse.getRecords() == null || igroupResponse.getRecords().size() == 0) {
                s_logger.error("getAccessGroup: Failed to fetch Igroup");
                throw new CloudRuntimeException("Failed to fetch Igroup");
            }
            Igroup igroup = igroupResponse.getRecords().get(0);
            AccessGroup accessGroup = new AccessGroup();
            accessGroup.setIgroup(igroup);
            return accessGroup;
        } catch (Exception e) {
            s_logger.error("Exception occurred while fetching Igroup, Exception: {}", e.getMessage());
            throw new CloudRuntimeException("Failed to fetch Igroup details: " + e.getMessage());
        }
    }

    public void enableLogicalAccess(Map<String, String> values) {
        s_logger.info("enableLogicalAccess : Create LunMap");
        s_logger.debug("enableLogicalAccess : Creating LunMap with values {} ", values);
        String svmName = values.get(Constants.SVM_DOT_NAME);
        String lunName = values.get(Constants.LUN_DOT_NAME);
        String igroupName = values.get(Constants.IGROUP_DOT_NAME);
        if(svmName == null || lunName == null || igroupName == null || svmName.isEmpty() || lunName.isEmpty() || igroupName.isEmpty()) {
            s_logger.error("enableLogicalAccess: LunMap creation failed. Invalid request values: {}", values);
            throw new CloudRuntimeException("enableLogicalAccess : Failed to create LunMap, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create LunMap
            LunMap lunMapRequest = new LunMap();
            Svm svm = new Svm();
            svm.setName(svmName);
            lunMapRequest.setSvm(svm);
            //Set Lun name
            Lun lun = new Lun();
            lun.setName(lunName);
            lunMapRequest.setLun(lun);
            //Set Igroup name
            Igroup igroup = new Igroup();
            igroup.setName(igroupName);
            lunMapRequest.setIgroup(igroup);
            OntapResponse<LunMap> createdLunMap = sanFeignClient.createLunMap(authHeader, true, lunMapRequest);
            if (createdLunMap == null || createdLunMap.getRecords() == null || createdLunMap.getRecords().size() == 0) {
                s_logger.error("enableLogicalAccess: LunMap failed for Lun: {} and igroup: {}", lunName, igroupName);
                throw new CloudRuntimeException("Failed to perform LunMap for Lun: " + lunName + " and igroup: " + igroupName);
            }
            LunMap lunMap = createdLunMap.getRecords().get(0);
            s_logger.debug("enableLogicalAccess: LunMap created successfully, LunMap: {}", lunMap);
            s_logger.info("enableLogicalAccess: LunMap created successfully.");
        } catch (Exception e) {
            s_logger.error("Exception occurred while creating LunMap, Exception: {}", e);
            throw new CloudRuntimeException("Failed to create LunMap: " + e.getMessage());
        }
    }

    public void disableLogicalAccess(Map<String, String> values) {
        s_logger.info("disableLogicalAccess : Delete LunMap");
        s_logger.debug("disableLogicalAccess : Deleting LunMap with values {} ", values);
        String lunUUID = values.get(Constants.LUN_DOT_UUID);
        String igroupUUID = values.get(Constants.IGROUP_DOT_UUID);
        if(lunUUID == null || igroupUUID == null || lunUUID.isEmpty() || igroupUUID.isEmpty()) {
            s_logger.error("disableLogicalAccess: LunMap deletion failed. Invalid request values: {}", values);
            throw new CloudRuntimeException("disableLogicalAccess : Failed to delete LunMap, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // LunMap delete
            sanFeignClient.deleteLunMap(authHeader, lunUUID, igroupUUID);
            s_logger.info("disableLogicalAccess: LunMap deleted successfully.");
        } catch (Exception e) {
            s_logger.error("Exception occurred while deleting LunMap, Exception: {}", e);
            throw new CloudRuntimeException("Failed to delete LunMap: " + e.getMessage());
        }
    }
}
