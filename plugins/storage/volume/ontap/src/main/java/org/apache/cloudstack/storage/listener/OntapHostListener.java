// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.listener;

import javax.inject.Inject;

import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.alert.AlertManager;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.host.Host;
import com.cloud.storage.StoragePool;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import com.cloud.host.dao.HostDao;

/**
 * HypervisorHostListener implementation for ONTAP storage.
 * Handles connecting/disconnecting hosts to/from ONTAP-backed storage pools.
 */
public class OntapHostListener implements HypervisorHostListener {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AgentManager _agentMgr;
    @Inject
    private AlertManager _alertMgr;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;
    @Inject
    private HostDao _hostDao;
    @Inject private StoragePoolHostDao storagePoolHostDao;


    @Override
    public boolean hostConnect(long hostId, long poolId)  {
        logger.info("Connect to host " + hostId + " from pool " + poolId);
        Host host = _hostDao.findById(hostId);
        if (host == null) {
            logger.error("Failed to add host by HostListener as host was not found with id : {}", hostId);
            return false;
        }

        // TODO add host type check also since we support only KVM for now, host.getHypervisorType().equals(HypervisorType.KVM)
        StoragePool pool = _storagePoolDao.findById(poolId);
        logger.info("Connecting host {} to ONTAP storage pool {}", host.getName(), pool.getName());


        // incase host was not added by cloudstack , we will add it
        StoragePoolHostVO storagePoolHost = storagePoolHostDao.findByPoolHost(poolId, hostId);

        if (storagePoolHost == null) {
            storagePoolHost = new StoragePoolHostVO(poolId, hostId, "");

            storagePoolHostDao.persist(storagePoolHost);
        }

        // Validate pool type - ONTAP supports NFS and iSCSI
//        StoragePoolType poolType = pool.getPoolType();
//        // TODO add iscsi also here
//        if (poolType != StoragePoolType.NetworkFilesystem) {
//            logger.error("Unsupported pool type {} for ONTAP storage", poolType);
//            return false;
//        }

        try {
            // Create the CreateStoragePoolCommand to send to the agent
            ModifyStoragePoolCommand cmd = new ModifyStoragePoolCommand(true, pool);

            Answer answer = _agentMgr.easySend(hostId, cmd);

            if (answer == null) {
                throw new CloudRuntimeException(String.format("Unable to get an answer to the modify storage pool command (%s)", pool));
            }

            if (!answer.getResult()) {
                String msg = String.format("Unable to attach storage pool %s to host %d", pool, hostId);

                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, pool.getDataCenterId(), pool.getPodId(), msg, msg);

                throw new CloudRuntimeException(String.format(
                        "Unable to establish a connection from agent to storage pool %s due to %s", pool, answer.getDetails()));
            }
        } catch (Exception e) {
            logger.error("Exception while connecting host {} to storage pool {}", host.getName(), pool.getName(), e);
            throw new CloudRuntimeException("Failed to connect host to storage pool: " + e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean hostDisconnected(Host host, StoragePool pool) {
        logger.info("Disconnect from host " + host.getId() + " from pool " + pool.getName());

        Host hostToremove = _hostDao.findById(host.getId());
        if (hostToremove == null) {
            logger.error("Failed to add host by HostListener as host was not found with id : {}", host.getId());
            return false;
        }
        // TODO add storage pool get validation
        logger.info("Disconnecting host {} from ONTAP storage pool {}", host.getName(), pool.getName());

        try {
            DeleteStoragePoolCommand cmd = new DeleteStoragePoolCommand(pool);
            long hostId = host.getId();
            Answer answer = _agentMgr.easySend(hostId, cmd);

            if (answer != null && answer.getResult()) {
                logger.info("Successfully disconnected host {} from ONTAP storage pool {}", host.getName(), pool.getName());
                return true;
            } else {
                String errMsg = (answer != null) ? answer.getDetails() : "Unknown error";
                logger.warn("Failed to disconnect host {} from storage pool {}. Error: {}", host.getName(), pool.getName(), errMsg);
                return false;
            }
        } catch (Exception e) {
            logger.error("Exception while disconnecting host {} from storage pool {}", host.getName(), pool.getName(), e);
            return false;
        }
    }

    @Override
    public boolean hostDisconnected(long hostId, long poolId) {
        return false;
    }

    @Override
    public boolean hostAboutToBeRemoved(long hostId) {
        return false;
    }

    @Override
    public boolean hostRemoved(long hostId, long clusterId) {
        return false;
    }

    @Override
    public boolean hostEnabled(long hostId) {
        return false;
    }

    @Override
    public boolean hostAdded(long hostId) {
        return false;
    }

}
