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
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.LunMap;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.net.URI;
import java.util.Map;

public class UnifiedSANStrategy extends SANStrategy {

    private static final Logger s_logger = LogManager.getLogger(UnifiedSANStrategy.class);
    @Inject private Utility utils;
    @Inject private SANFeignClient sanFeignClient;
    public UnifiedSANStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
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
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create URI for lun creation
            URI url = utils.generateURI(Constants.CREATE_LUN);
            //TODO: It is possible that Lun creation will take time and we may need to handle through async job.
            OntapResponse<Lun> createdLun = sanFeignClient.createLun(url, authHeader, true, cloudstackVolume.getLun());
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
            s_logger.error("Exception occurred while creating LUN: {}. Exception: {}", cloudstackVolume.getLun().getName(), e.getMessage());
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

    }

    @Override
    public CloudStackVolume getCloudStackVolume(Map<String, String> values) {
        s_logger.info("getCloudStackVolume : fetching Lun with params {} ", values);
        if (values == null || values.isEmpty()) {
            s_logger.error("getCloudStackVolume: get LUN failed. Invalid request: {}", values);
            throw new CloudRuntimeException("getCloudStackVolume : get Lun Failed, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create URI for lun creation
            URI url = utils.generateURI(Constants.CREATE_LUN);
            url = utils.addQueryParamsToURI(url, values);
            s_logger.info("getCloudStackVolume: URL: {}", url);
            //TODO: It is possible that Lun creation will take time and we may need to handle through async job.
            OntapResponse<Lun> lunResponse = sanFeignClient.getLunResponse(url, authHeader);
            if (lunResponse == null || lunResponse.getRecords() == null || lunResponse.getRecords().size() == 0) {
                s_logger.error("getCloudStackVolume: Failed to fetch lun");
                throw new CloudRuntimeException("Failed to fetch Lun");
            }
            Lun lun = lunResponse.getRecords().get(0);
            s_logger.debug("getCloudStackVolume: Lun Details : {}", lun);
            s_logger.info("getCloudStackVolume: Fetched the LUN successfully. LunName: {}", lun.getName());

            CloudStackVolume createdCloudStackVolume = new CloudStackVolume();
            createdCloudStackVolume.setLun(lun);
            return createdCloudStackVolume;
        } catch (Exception e) {
            s_logger.error("Exception occurred while fetching LUN. Exception: {}", e.getMessage());
            throw new CloudRuntimeException("Failed to fetch Lun: " + e.getMessage());
        }
    }

    @Override
    public AccessGroup createAccessGroup(AccessGroup accessGroup) {
        s_logger.info("createAccessGroup : Creating Igroup with accessGroup request {} ", accessGroup);
        if (accessGroup == null || accessGroup.getIgroup() == null) {
            s_logger.error("createAccessGroup: Igroup creation failed. Invalid request: {}", accessGroup);
            throw new CloudRuntimeException("createAccessGroup : Failed to create Lun, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create URI for Igroup creation
            URI url = utils.generateURI(Constants.CREATE_IGROUP);
            OntapResponse<Igroup> createdIgroup = sanFeignClient.createIgroup(url, authHeader, true, accessGroup.getIgroup());
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
            s_logger.error("Exception occurred while creating Igroup: {}. Exception: {}", accessGroup.getIgroup().getName(), e.getMessage());
            throw new CloudRuntimeException("Failed to create Igroup: " + e.getMessage());
        }
    }

    @Override
    public void deleteAccessGroup(AccessGroup accessGroup) {

    }

    @Override
    public AccessGroup updateAccessGroup(AccessGroup accessGroup) {
        //TODO
        return null;
    }

    @Override
    public AccessGroup getAccessGroup(Map<String, String> values) {
        s_logger.info("getAccessGroup : fetching Igroup with params {} ", values);
        if (values == null || values.isEmpty()) {
            s_logger.error("getAccessGroup: get Igroup failed. Invalid request: {}", values);
            throw new CloudRuntimeException("getAccessGroup : get Igroup Failed, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create URI for lun creation
            URI url = utils.generateURI(Constants.CREATE_IGROUP);
            url = utils.addQueryParamsToURI(url, values);
            s_logger.info("getAccessGroup: URL: {}", url);
            //TODO: It is possible that Lun creation will take time and we may need to handle through async job.
            OntapResponse<Igroup> igroupResponse = sanFeignClient.getIgroupResponse(url, authHeader);
            if (igroupResponse == null || igroupResponse.getRecords() == null || igroupResponse.getRecords().size() == 0) {
                s_logger.error("getAccessGroup: Failed to fetch Igroup");
                throw new CloudRuntimeException("Failed to fetch Igroup");
            }
            Igroup igroup = igroupResponse.getRecords().get(0);
            s_logger.debug("getAccessGroup: Igroup Details : {}", igroup);
            s_logger.info("getAccessGroup: Fetched the Igroup successfully. LunName: {}", igroup.getName());

            AccessGroup accessGroup = new AccessGroup();
            accessGroup.setIgroup(igroup);
            return accessGroup;
        } catch (Exception e) {
            s_logger.error("Exception occurred while fetching Igroup. Exception: {}", e.getMessage());
            throw new CloudRuntimeException("Failed to fetch Igroup details: " + e.getMessage());
        }
    }

    @Override
    public void enableLogicalAccess(Map<String, String> values) {
        s_logger.info("enableLogicalAccess : Creating LunMap with values {} ", values);
        LunMap lunMapRequest = new LunMap();
        String svmName = values.get(Constants.SVM_DOT_NAME);
        String lunName = values.get(Constants.LUN_DOT_NAME);
        String igroupName = values.get(Constants.IGROUP_DOT_NAME);
        if(svmName == null || lunName == null || igroupName == null || svmName.isEmpty() || lunName.isEmpty() || igroupName.isEmpty()) {
            s_logger.error("enableLogicalAccess: LunMap creation failed. Invalid request values: {}", values);
            throw new CloudRuntimeException("enableLogicalAccess : Failed to create LunMap, invalid request");
        }
        try {
            // Get AuthHeader
            String authHeader = utils.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create URI for Igroup creation
            URI url = utils.generateURI(Constants.CREATE_LUNMAP);
            OntapResponse<LunMap> createdLunMap = sanFeignClient.createLunMap(url, authHeader, true, lunMapRequest);
            if (createdLunMap == null || createdLunMap.getRecords() == null || createdLunMap.getRecords().size() == 0) {
                s_logger.error("enableLogicalAccess: LunMap creation failed for Lun {} and igroup ", lunName, igroupName);
                throw new CloudRuntimeException("Failed to create LunMap: creation failed for Lun: " +lunName+ "and igroup: " + igroupName);
            }
            LunMap lunMap = createdLunMap.getRecords().get(0);
            s_logger.debug("enableLogicalAccess: LunMap created successfully. LunMap: {}", lunMap);
            s_logger.info("enableLogicalAccess: LunMap created successfully.");
        } catch (Exception e) {
            s_logger.error("Exception occurred while creating LunMap: {}. Exception: {}", e.getMessage());
            throw new CloudRuntimeException("Failed to create LunMap: " + e.getMessage());
        }
    }

    @Override
    void disableLogicalAccess(Map<String, String> values) {

    }
}
