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

import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.storage.feign.FeignClientFactory;
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Initiator;
import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.LunMap;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
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
            // Get StoragePool details
            if (accessGroup.getPrimaryDataStoreInfo() == null || accessGroup.getPrimaryDataStoreInfo().getDetails() == null
                    || accessGroup.getPrimaryDataStoreInfo().getDetails().isEmpty()) {
                throw new CloudRuntimeException("createAccessGroup : Failed to create Igroup, invalid datastore details in the request");
            }
            Map<String, String> dataStoreDetails = accessGroup.getPrimaryDataStoreInfo().getDetails();
            s_logger.debug("createAccessGroup: Successfully fetched datastore details.");

            // Get AuthHeader
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Generate Igroup request
            Igroup igroupRequest = new Igroup();
            List<String> hostsIdentifier = new ArrayList<>();
            String svmName = dataStoreDetails.get(Constants.SVM_NAME);
            igroupName = Utility.getIgroupName(svmName, accessGroup.getScope().getScopeType(), accessGroup.getScope().getScopeId());
            Hypervisor.HypervisorType hypervisorType = accessGroup.getPrimaryDataStoreInfo().getHypervisor();

            ProtocolType protocol = ProtocolType.valueOf(dataStoreDetails.get(Constants.PROTOCOL));
            // Check if all hosts support the protocol
            if (accessGroup.getHostsToConnect() == null || accessGroup.getHostsToConnect().isEmpty()) {
                throw new CloudRuntimeException("createAccessGroup : Failed to create Igroup, no hosts to connect provided in the request");
            }
            if (!validateProtocolSupportAndFetchHostsIdentifier(accessGroup.getHostsToConnect(), protocol, hostsIdentifier)) {
                String errMsg = "createAccessGroup: Not all hosts in the " +  accessGroup.getScope().getScopeType().toString()  + " support the protocol: " + protocol.name();
                throw new CloudRuntimeException(errMsg);
            }

            if (svmName != null && !svmName.isEmpty()) {
                Svm svm = new Svm();
                svm.setName(svmName);
                igroupRequest.setSvm(svm);
            }

            if (igroupName != null && !igroupName.isEmpty()) {
                igroupRequest.setName(igroupName);
            }

//            if (hypervisorType != null) {
//                String hypervisorName = hypervisorType.name();
//                igroupRequest.setOsType(Igroup.OsTypeEnum.valueOf(Utility.getOSTypeFromHypervisor(hypervisorName)));
//            } else if ( accessGroup.getScope().getScopeType() == ScopeType.ZONE) {
//                igroupRequest.setOsType(Igroup.OsTypeEnum.linux); // TODO: Defaulting to LINUX for zone scope for now, this has to be revisited when we support other hypervisors
//            }
            igroupRequest.setOsType(Igroup.OsTypeEnum.linux);

            if (hostsIdentifier != null && hostsIdentifier.size() > 0) {
                List<Initiator> initiators = new ArrayList<>();
                for (String hostIdentifier : hostsIdentifier) {
                    Initiator initiator = new Initiator();
                    initiator.setName(hostIdentifier);
                    initiators.add(initiator);
                }
                igroupRequest.setInitiators(initiators);
            }
            igroupRequest.setProtocol(Igroup.ProtocolEnum.valueOf("iscsi"));
            // Create Igroup
            s_logger.debug("createAccessGroup: About to call sanFeignClient.createIgroup with igroupName: {}", igroupName);
            AccessGroup createdAccessGroup = new AccessGroup();
            OntapResponse<Igroup> createdIgroup = null;
            try {
                createdIgroup = sanFeignClient.createIgroup(authHeader, true, igroupRequest);
            } catch (Exception feignEx) {
                String errMsg = feignEx.getMessage();
                if (errMsg != null && errMsg.contains(("5374023"))) {
                    s_logger.warn("createAccessGroup: Igroup with name {} already exists. Fetching existing Igroup.", igroupName);
                    // TODO: Currently we aren't doing anything with the returned AccessGroup object, so, haven't added code here to fetch the existing Igroup and set it in AccessGroup.
                    return createdAccessGroup;
                }
                s_logger.error("createAccessGroup: Exception during Feign call: {}", feignEx.getMessage(), feignEx);
                throw feignEx;
            }

            s_logger.debug("createAccessGroup: createdIgroup: {}", createdIgroup);
            s_logger.debug("createAccessGroup: createdIgroup Records: {}", createdIgroup.getRecords());
            if (createdIgroup == null || createdIgroup.getRecords() == null || createdIgroup.getRecords().isEmpty()) {
                s_logger.error("createAccessGroup: Igroup creation failed for Igroup Name {}", igroupName);
                throw new CloudRuntimeException("Failed to create Igroup: " + igroupName);
            }
            Igroup igroup = createdIgroup.getRecords().get(0);
            s_logger.debug("createAccessGroup: Successfully extracted igroup from response: {}", igroup);
            s_logger.info("createAccessGroup: Igroup created successfully. IgroupName: {}", igroup.getName());

            createdAccessGroup.setIgroup(igroup);
            s_logger.debug("createAccessGroup: Returning createdAccessGroup");
            return createdAccessGroup;
        } catch (Exception e) {
            s_logger.error("Exception occurred while creating Igroup: {}, Exception: {}", igroupName, e.getMessage(), e);
            throw new CloudRuntimeException("Failed to create Igroup: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteAccessGroup(AccessGroup accessGroup) {
        s_logger.info("deleteAccessGroup: Deleting iGroup");

        if (accessGroup == null) {
            throw new CloudRuntimeException("deleteAccessGroup: Invalid accessGroup object - accessGroup is null");
        }

        // Get PrimaryDataStoreInfo from accessGroup
        PrimaryDataStoreInfo primaryDataStoreInfo = accessGroup.getPrimaryDataStoreInfo();
        if (primaryDataStoreInfo == null) {
            throw new CloudRuntimeException("deleteAccessGroup: PrimaryDataStoreInfo is null in accessGroup");
        }

        try {
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());

            // Extract SVM name from storage (already initialized in constructor via OntapStorage)
            String svmName = storage.getSvmName();

            // Determine scope and generate iGroup name
            String igroupName;
            if (primaryDataStoreInfo.getClusterId() != null) {
                igroupName = Utility.getIgroupName(svmName, com.cloud.storage.ScopeType.CLUSTER, primaryDataStoreInfo.getClusterId());
                s_logger.info("deleteAccessGroup: Deleting cluster-scoped iGroup '{}'", igroupName);
            } else {
                igroupName = Utility.getIgroupName(svmName, com.cloud.storage.ScopeType.ZONE, primaryDataStoreInfo.getDataCenterId());
                s_logger.info("deleteAccessGroup: Deleting zone-scoped iGroup '{}'", igroupName);
            }

            // Get the iGroup to retrieve its UUID
            Map<String, Object> igroupParams = Map.of(
                    Constants.SVM_DOT_NAME, svmName,
                    Constants.NAME, igroupName
            );

            try {
                OntapResponse<Igroup> igroupResponse = sanFeignClient.getIgroupResponse(authHeader, igroupParams);
                if (igroupResponse == null || igroupResponse.getRecords() == null || igroupResponse.getRecords().isEmpty()) {
                    s_logger.warn("deleteAccessGroup: iGroup '{}' not found, may have been already deleted", igroupName);
                    return;
                }

                Igroup igroup = igroupResponse.getRecords().get(0);
                String igroupUuid = igroup.getUuid();

                if (igroupUuid == null || igroupUuid.isEmpty()) {
                    throw new CloudRuntimeException("deleteAccessGroup: iGroup UUID is null or empty for iGroup: " + igroupName);
                }

                s_logger.info("deleteAccessGroup: Deleting iGroup '{}' with UUID '{}'", igroupName, igroupUuid);

                // Delete the iGroup using the UUID
                sanFeignClient.deleteIgroup(authHeader, igroupUuid);

                s_logger.info("deleteAccessGroup: Successfully deleted iGroup '{}'", igroupName);

            } catch (Exception e) {
                String errorMsg = e.getMessage();
                // Check if iGroup doesn't exist (ONTAP error code: 5374852 - "The initiator group does not exist.")
                if (errorMsg != null && (errorMsg.contains("5374852") || errorMsg.contains("not found"))) {
                    s_logger.warn("deleteAccessGroup: iGroup '{}' does not exist, skipping deletion", igroupName);
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            s_logger.error("deleteAccessGroup: Failed to delete iGroup. Exception: {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to delete iGroup: " + e.getMessage(), e);
        }
    }

    private boolean validateProtocolSupportAndFetchHostsIdentifier(List<HostVO> hosts, ProtocolType protocolType, List<String> hostIdentifiers) {
        switch (protocolType) {
            case ISCSI:
                String protocolPrefix = Constants.IQN;
                for (HostVO host : hosts) {
                    if (host == null || host.getStorageUrl() == null || host.getStorageUrl().trim().isEmpty()
                            || !host.getStorageUrl().startsWith(protocolPrefix)) {
                        return false;
                    }
                    hostIdentifiers.add(host.getStorageUrl());
                }
                break;
            default:
                throw new CloudRuntimeException("validateProtocolSupportAndFetchHostsIdentifier : Unsupported protocol: " + protocolType.name());
        }
        s_logger.info("validateProtocolSupportAndFetchHostsIdentifier: All hosts support the protocol: " + protocolType.name());
        return true;
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
            String authHeader = Utility.generateAuthHeader(storage.getUsername(), storage.getPassword());
            // Create LunMap
            OntapResponse<LunMap> createdLunMap = sanFeignClient.createLunMap(authHeader, true, lunMapRequest);
            if (createdLunMap == null || createdLunMap.getRecords() == null || createdLunMap.getRecords().size() == 0) {
                s_logger.error("enableLogicalAccess: LunMap failed for Lun: {} and igroup: {}", lunName, igroupName);
                throw new CloudRuntimeException("Failed to perform LunMap for Lun: " + lunName + " and igroup: " + igroupName);
            }
            LunMap lunMap = createdLunMap.getRecords().get(0);
            s_logger.debug("enableLogicalAccess: LunMap created successfully, LunMap: {}", lunMap);
            s_logger.info("enableLogicalAccess: LunMap created successfully.");
        } catch (Exception e) {
            s_logger.error("Exception occurred while creating LunMap: {}, Exception: {}", e.getMessage(), e);
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
            s_logger.error("Exception occurred while deleting LunMap: {}, Exception: {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to delete LunMap: " + e.getMessage());
        }
    }
}
