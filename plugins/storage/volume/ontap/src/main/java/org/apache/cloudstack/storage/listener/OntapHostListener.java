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
package org.apache.cloudstack.storage.listener;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;

/**
 * OntapHostListener handles host lifecycle events for ONTAP storage pools.
 *
 * For ONTAP iSCSI storage pools:
 * - The igroup (initiator group) is created/updated in OntapPrimaryDatastoreLifecycle.attachCluster()
 * - The actual iSCSI target discovery and login is handled by StorageManager via ModifyStoragePoolCommand
 * - This listener simply manages the storage pool-host relationship in the database
 *
 * For ONTAP NFS storage pools:
 * - The export policy is configured during storage pool creation
 * - The actual NFS mount is handled by StorageManager via ModifyStoragePoolCommand
 * - This listener simply manages the storage pool-host relationship in the database
 */
public class OntapHostListener implements HypervisorHostListener {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject private HostDao hostDao;
    @Inject private AgentManager agentMgr;
    @Inject private PrimaryDataStoreDao storagePoolDao;
    @Inject private DataStoreManager dataStoreMgr;
    @Inject private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject private StoragePoolHostDao storagePoolHostDao;

    @Override
    public boolean hostAdded(long hostId) {
        HostVO host = hostDao.findById(hostId);

        if (host == null) {
            logger.error("hostAdded: Host {} not found", hostId);
            return false;
        }

        if (host.getClusterId() == null) {
            logger.error("hostAdded: Host {} has no associated cluster", hostId);
            return false;
        }

        logger.info("hostAdded: Host {} added to cluster {}", hostId, host.getClusterId());
        return true;
    }

    @Override
    public boolean hostConnect(long hostId, long storagePoolId) {
        logger.debug("hostConnect: Connecting host {} to storage pool {}", hostId, storagePoolId);

        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.error("hostConnect: Host {} not found", hostId);
            return false;
        }

        // Create or update the storage pool host mapping in the database
        // The actual storage pool connection (iSCSI login or NFS mount) is handled
        // by the StorageManager via ModifyStoragePoolCommand sent to the host agent
        StoragePoolHostVO storagePoolHost = storagePoolHostDao.findByPoolHost(storagePoolId, hostId);
        StoragePool storagePool = (StoragePool)dataStoreMgr.getDataStore(storagePoolId, DataStoreRole.Primary);
        if (storagePoolHost == null) {
            storagePoolHost = new StoragePoolHostVO(storagePoolId, hostId, "");
            storagePoolHostDao.persist(storagePoolHost);
            ModifyStoragePoolCommand cmd = new ModifyStoragePoolCommand(true, storagePool);
            Answer answer = agentMgr.easySend(host.getId(), cmd);
            if (answer == null || !answer.getResult()) {
                storagePoolDao.expunge(storagePool.getId());
                throw new CloudRuntimeException("attachCluster: Failed to attach storage pool to host: " + host.getId() +
                        " due to " + (answer != null ? answer.getDetails() : "no answer from agent"));
            }
            logger.info("Connection established between storage pool {} and host {}", storagePool, host);
        } else {
            // TODO: Update any necessary details if needed, by fetching OntapVolume info from ONTAP
            logger.debug("hostConnect: Storage pool-host mapping already exists for pool {} and host {}",
                    storagePool.getName(), host.getName());
        }

        return true;
    }

    @Override
    public boolean hostDisconnected(long hostId, long storagePoolId) {
        logger.debug("hostDisconnected: Disconnecting host {} from storage pool {}",
                    hostId, storagePoolId);

        StoragePoolHostVO storagePoolHost = storagePoolHostDao.findByPoolHost(storagePoolId, hostId);
        if (storagePoolHost != null) {
            storagePoolHostDao.deleteStoragePoolHostDetails(hostId, storagePoolId);
            logger.info("hostDisconnected: Removed storage pool-host mapping for pool {} and host {}",
                       storagePoolId, hostId);
        } else {
            logger.debug("hostDisconnected: No storage pool-host mapping found for pool {} and host {}",
                        storagePoolId, hostId);
        }

        return true;
    }

    @Override
    public boolean hostAboutToBeRemoved(long hostId) {
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.error("hostAboutToBeRemoved: Host {} not found", hostId);
            return false;
        }

        logger.info("hostAboutToBeRemoved: Host {} about to be removed from cluster {}",
                   hostId, host.getClusterId());

        // Note: When a host is removed, the igroup initiator should be removed in
        // the appropriate lifecycle method, not here
        return true;
    }

    @Override
    public boolean hostRemoved(long hostId, long clusterId) {
        logger.info("hostRemoved: Host {} removed from cluster {}", hostId, clusterId);
        return true;
    }

    @Override
    public boolean hostEnabled(long hostId) {
        logger.debug("hostEnabled: Host {} enabled", hostId);
        return true;
    }
}

