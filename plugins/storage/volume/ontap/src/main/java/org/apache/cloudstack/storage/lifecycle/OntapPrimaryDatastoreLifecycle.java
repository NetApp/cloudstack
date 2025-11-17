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

package org.apache.cloudstack.storage.lifecycle;


import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.common.base.Preconditions;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreParameters;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.lifecycle.BasePrimaryDataStoreLifeCycleImpl;
import org.apache.cloudstack.storage.feign.model.ExportPolicy;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.provider.StorageProviderFactory;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.AccessGroup;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.cloudstack.storage.utils.Constants;
import org.apache.cloudstack.storage.utils.Utility;
import org.apache.cloudstack.storage.volume.datastore.PrimaryDataStoreHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class OntapPrimaryDatastoreLifecycle extends BasePrimaryDataStoreLifeCycleImpl implements PrimaryDataStoreLifeCycle {
    @Inject private ClusterDao _clusterDao;
    @Inject private StorageManager _storageMgr;
    @Inject private ResourceManager _resourceMgr;
    @Inject private PrimaryDataStoreHelper _dataStoreHelper;
    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    private static final Logger s_logger = LogManager.getLogger(OntapPrimaryDatastoreLifecycle.class);

    // ONTAP minimum volume size is 1.56 GB (1677721600 bytes)
    private static final long ONTAP_MIN_VOLUME_SIZE = 1677721600L;

    /**
     * Creates primary storage on NetApp storage
     * @param dsInfos datastore information map
     * @return DataStore instance
     */
    @Override
    public DataStore initialize(Map<String, Object> dsInfos) {
        if (dsInfos == null) {
            throw new CloudRuntimeException("Datastore info map is null, cannot create primary storage");
        }
        String url = (String) dsInfos.get("url");
        Long zoneId = (Long) dsInfos.get("zoneId");
        Long podId = (Long) dsInfos.get("podId");
        Long clusterId = (Long) dsInfos.get("clusterId");
        String storagePoolName = (String) dsInfos.get("name");
        String providerName = (String) dsInfos.get("providerName");
        Long capacityBytes = (Long) dsInfos.get("capacityBytes");
        String tags = (String) dsInfos.get("tags");
        Boolean isTagARule = (Boolean) dsInfos.get("isTagARule");

        s_logger.info("Creating ONTAP primary storage pool with name: " + storagePoolName + ", provider: " + providerName +
                ", zoneId: " + zoneId + ", podId: " + podId + ", clusterId: " + clusterId);
        s_logger.debug("Received capacityBytes from UI: " + capacityBytes);

        // Additional details requested for ONTAP primary storage pool creation
        @SuppressWarnings("unchecked")
        Map<String, String> details = (Map<String, String>) dsInfos.get("details");

        // Validate and set capacity
        if (capacityBytes == null || capacityBytes <= 0) {
            s_logger.warn("capacityBytes not provided or invalid (" + capacityBytes + "), using ONTAP minimum size: " + ONTAP_MIN_VOLUME_SIZE);
            capacityBytes = ONTAP_MIN_VOLUME_SIZE;
        } else if (capacityBytes < ONTAP_MIN_VOLUME_SIZE) {
            s_logger.warn("capacityBytes (" + capacityBytes + ") is below ONTAP minimum (" + ONTAP_MIN_VOLUME_SIZE + "), adjusting to minimum");
            capacityBytes = ONTAP_MIN_VOLUME_SIZE;
        }

        // Validate scope
        if (podId == null ^ clusterId == null) {
            throw new CloudRuntimeException("Cluster Id or Pod Id is null, cannot create primary storage");
        }

        if (podId == null && clusterId == null) {
            if (zoneId != null) {
                s_logger.info("Both Pod Id and Cluster Id are null, Primary storage pool will be associated with a Zone");
            } else {
                throw new CloudRuntimeException("Pod Id, Cluster Id and Zone Id are all null, cannot create primary storage");
            }
        }

        if (storagePoolName == null || storagePoolName.isEmpty()) {
            throw new CloudRuntimeException("Storage pool name is null or empty, cannot create primary storage");
        }

        if (providerName == null || providerName.isEmpty()) {
            throw new CloudRuntimeException("Provider name is null or empty, cannot create primary storage");
        }

        PrimaryDataStoreParameters parameters = new PrimaryDataStoreParameters();
        if (clusterId != null) {
            ClusterVO clusterVO = _clusterDao.findById(clusterId);
            Preconditions.checkNotNull(clusterVO, "Unable to locate the specified cluster");
            if (clusterVO.getHypervisorType() != Hypervisor.HypervisorType.KVM) {
                throw new CloudRuntimeException("ONTAP primary storage is supported only for KVM hypervisor");
            }
            parameters.setHypervisorType(clusterVO.getHypervisorType());
        }

        // Required ONTAP detail keys
        Set<String> requiredKeys = Set.of(
                Constants.USERNAME,
                Constants.PASSWORD,
                Constants.SVM_NAME,
                Constants.PROTOCOL,
                Constants.MANAGEMENT_LIF,
                Constants.IS_DISAGGREGATED
        );

        // Parse key=value pairs from URL into details (skip empty segments)
        if (url != null && !url.isEmpty()) {
            for (String segment : url.split(Constants.SEMICOLON)) {
                if (segment.isEmpty()) {
                    continue;
                }
                String[] kv = segment.split(Constants.EQUALS, 2);
                if (kv.length == 2) {
                    details.put(kv[0].trim(), kv[1].trim());
                }
            }
        }

        // Validate existing entries (reject unexpected keys, empty values)
        for (Map.Entry<String, String> e : details.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            if (!requiredKeys.contains(key)) {
                throw new CloudRuntimeException("Unexpected ONTAP detail key in URL: " + key);
            }
            if (val == null || val.isEmpty()) {
                throw new CloudRuntimeException("ONTAP primary storage creation failed, empty detail: " + key);
            }
        }

        // Detect missing required keys
        Set<String> providedKeys = new java.util.HashSet<>(details.keySet());
        if (!providedKeys.containsAll(requiredKeys)) {
            Set<String> missing = new java.util.HashSet<>(requiredKeys);
            missing.removeAll(providedKeys);
            throw new CloudRuntimeException("ONTAP primary storage creation failed, missing detail(s): " + missing);
        }

        details.put(Constants.SIZE, capacityBytes.toString());

        // Default for IS_DISAGGREGATED if needed
        details.putIfAbsent(Constants.IS_DISAGGREGATED, "false");

        // Determine storage pool type and path based on protocol
        String path;
        String host = "";
        ProtocolType protocol = ProtocolType.valueOf(details.get(Constants.PROTOCOL));
        switch (protocol) {
            case NFS:
                parameters.setType(Storage.StoragePoolType.NetworkFilesystem);
                // Path should be just the NFS export path (junction path), NOT host:path
                // CloudStack will construct the full mount path as: hostAddress + ":" + path
                path = "/" + storagePoolName;
                s_logger.info("Setting NFS path for storage pool: " + path);
                host = "10.193.192.136"; // TODO hardcoded for now
                break;
            case ISCSI:
                parameters.setType(Storage.StoragePoolType.Iscsi);
                path = "iqn.1992-08.com.netapp:" + details.get(Constants.SVM_NAME) + "." + storagePoolName;
                s_logger.info("Setting iSCSI path for storage pool: " + path);
                parameters.setHost(details.get(Constants.MANAGEMENT_LIF));
                break;
            default:
                throw new CloudRuntimeException("Unsupported protocol: " + protocol + ", cannot create primary storage");
        }

        // Connect to ONTAP and create volume
        OntapStorage ontapStorage = new OntapStorage(
                details.get(Constants.USERNAME),
                details.get(Constants.PASSWORD),
                details.get(Constants.MANAGEMENT_LIF),
                details.get(Constants.SVM_NAME),
                protocol,
                Boolean.parseBoolean(details.get(Constants.IS_DISAGGREGATED).toLowerCase()));

        StorageStrategy storageStrategy = StorageProviderFactory.getStrategy(ontapStorage);
        boolean isValid = storageStrategy.connect();
        if (isValid) {
            long volumeSize = Long.parseLong(details.get(Constants.SIZE));
            s_logger.info("Creating ONTAP volume '" + storagePoolName + "' with size: " + volumeSize + " bytes (" +
                    (volumeSize / (1024 * 1024 * 1024)) + " GB)");
            try {
                Volume volume = storageStrategy.createStorageVolume(storagePoolName, volumeSize);
                if (volume == null) {
                    s_logger.error("createStorageVolume returned null for volume: " + storagePoolName);
                    throw new CloudRuntimeException("Failed to create ONTAP volume: " + storagePoolName);
                }

                s_logger.info("Volume object retrieved successfully. UUID: " + volume.getUuid() + ", Name: " + volume.getName());

                details.putIfAbsent(Constants.VOLUME_UUID, volume.getUuid());
                details.putIfAbsent(Constants.VOLUME_NAME, volume.getName());
            } catch (Exception e) {
                s_logger.error("Exception occurred while creating ONTAP volume: " + storagePoolName, e);
                throw new CloudRuntimeException("Failed to create ONTAP volume: " + storagePoolName + ". Error: " + e.getMessage(), e);
            }
        } else {
            throw new CloudRuntimeException("ONTAP details validation failed, cannot create primary storage");
        }

        // Set parameters for primary data store
        parameters.setPort(Constants.ONTAP_PORT);
        parameters.setHost(host);
        parameters.setPath(path);
        parameters.setTags(tags != null ? tags : "");
        parameters.setIsTagARule(isTagARule != null ? isTagARule : Boolean.FALSE);
        parameters.setDetails(details);
        parameters.setUuid(UUID.randomUUID().toString());
        parameters.setZoneId(zoneId);
        parameters.setPodId(podId);
        parameters.setClusterId(clusterId);
        parameters.setName(storagePoolName);
        parameters.setProviderName(providerName);
        parameters.setManaged(true); // ONTAP storage is always managed
        parameters.setCapacityBytes(capacityBytes);
        parameters.setUsedBytes(0);

        return _dataStoreHelper.createPrimaryDataStore(parameters);
    }

    @Override
    public boolean attachCluster(DataStore dataStore, ClusterScope scope) {
        logger.debug("In attachCluster for ONTAP primary storage");
        PrimaryDataStoreInfo primaryStore = (PrimaryDataStoreInfo)dataStore;
        List<HostVO> hostsToConnect = _resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(primaryStore);

        logger.debug(" datastore object received is  {} ",primaryStore );

        logger.debug(String.format("Attaching the pool to each of the hosts %s in the cluster: %s", hostsToConnect, primaryStore.getClusterId()));

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(primaryStore.getId());
        StorageStrategy strategy = Utility.getStrategyByStoragePoolDetails(details);
        ExportPolicy exportPolicy = new ExportPolicy();
        AccessGroup accessGroupRequest = new AccessGroup();
        accessGroupRequest.setHostsToConnect(hostsToConnect);
        accessGroupRequest.setScope(scope);
        primaryStore.setDetails(details);// setting details as it does not come from cloudstack
        accessGroupRequest.setPrimaryDataStoreInfo(primaryStore);
        accessGroupRequest.setPolicy(exportPolicy);
        strategy.createAccessGroup(accessGroupRequest);

        logger.debug("attachCluster: Attaching the pool to each of the host in the cluster: {}", primaryStore.getClusterId());
        for (HostVO host : hostsToConnect) {
            // TODO: Fetch the host IQN and add to the initiator group on ONTAP cluster
            try {
                _storageMgr.connectHostToSharedPool(host, dataStore.getId());
            } catch (Exception e) {
                logger.warn("Unable to establish a connection between " + host + " and " + dataStore, e);
                throw new CloudRuntimeException("Failed to attach storage pool to cluster: " + e.getMessage(), e);
            }
        }
        _dataStoreHelper.attachCluster(dataStore);
        return true;
    }

    @Override
    public boolean attachHost(DataStore store, HostScope scope, StoragePoolInfo existingInfo) {
        return false;
    }

    @Override
    public boolean attachZone(DataStore dataStore, ZoneScope scope, Hypervisor.HypervisorType hypervisorType) {
        logger.debug("In attachZone for ONTAP primary storage");

        PrimaryDataStoreInfo primaryStore = (PrimaryDataStoreInfo)dataStore;
        List<HostVO> hostsToConnect = _resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(dataStore, scope.getScopeId(), Hypervisor.HypervisorType.KVM);
        logger.debug(String.format("In createPool. Attaching the pool to each of the hosts in %s.", hostsToConnect));

        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(primaryStore.getId());
        StorageStrategy strategy = Utility.getStrategyByStoragePoolDetails(details);
        ExportPolicy exportPolicy = new ExportPolicy();
        AccessGroup accessGroupRequest = new AccessGroup();
        accessGroupRequest.setHostsToConnect(hostsToConnect);
        accessGroupRequest.setScope(scope);
        primaryStore.setDetails(details); // setting details as it does not come from cloudstack
        accessGroupRequest.setPrimaryDataStoreInfo(primaryStore);
        accessGroupRequest.setPolicy(exportPolicy);
        strategy.createAccessGroup(accessGroupRequest);

        for (HostVO host : hostsToConnect) {
            // TODO: Fetch the host IQN and add to the initiator group on ONTAP cluster
            try {
                _storageMgr.connectHostToSharedPool(host, dataStore.getId());
            } catch (Exception e) {
                logger.warn("Unable to establish a connection between " + host + " and " + dataStore, e);
                throw new CloudRuntimeException("Failed to attach storage pool to host: " + e.getMessage(), e);
            }
        }
        _dataStoreHelper.attachZone(dataStore);
        return true;
    }

    @Override
    public boolean maintain(DataStore store) {
        return true;
    }

    @Override
    public boolean cancelMaintain(DataStore store) {
        return true;
    }

    @Override
    public boolean deleteDataStore(DataStore store) {
        return true;
    }

    @Override
    public boolean migrateToObjectStore(DataStore store) {
        return true;
    }

    @Override
    public void updateStoragePool(StoragePool storagePool, Map<String, String> details) {

    }

    @Override
    public void enableStoragePool(DataStore store) {

    }

    @Override
    public void disableStoragePool(DataStore store) {

    }

    @Override
    public void changeStoragePoolScopeToZone(DataStore store, ClusterScope clusterScope, Hypervisor.HypervisorType hypervisorType) {

    }

    @Override
    public void changeStoragePoolScopeToCluster(DataStore store, ClusterScope clusterScope, Hypervisor.HypervisorType hypervisorType) {

    }
}

