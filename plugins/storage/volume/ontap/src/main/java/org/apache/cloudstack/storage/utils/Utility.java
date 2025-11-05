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

package org.apache.cloudstack.storage.utils;

import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.LunSpace;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.util.Base64Utils;

import java.util.Map;

public class Utility {

    private static final Logger s_logger = LogManager.getLogger(Utility.class);

    private static final String BASIC = "Basic";
    private static final String AUTH_HEADER_COLON = ":";

    /**
     * Method generates authentication headers using storage backend credentials passed as normal string
     *
     * @param username -->> username of the storage backend
     * @param password -->> normal decoded password of the storage backend
     * @return
     */
    public static String generateAuthHeader (String username, String password) {
        byte[] encodedBytes = Base64Utils.encode((username + AUTH_HEADER_COLON + password).getBytes());
        return BASIC + StringUtils.SPACE + new String(encodedBytes);
    }

    public static CloudStackVolume createCloudStackVolumeRequestByProtocol(StoragePoolVO storagePool, Map<String, String> details, DataObject dataObject) {
       CloudStackVolume cloudStackVolumeRequest = null;

       String protocol = details.get(Constants.PROTOCOL);
       if (ProtocolType.ISCSI.name().equalsIgnoreCase(protocol)) {
           cloudStackVolumeRequest = new CloudStackVolume();
           Lun lunRequest = new Lun();
           Svm svm = new Svm();
           svm.setName(details.get(Constants.SVM_NAME));
           lunRequest.setSvm(svm);

           LunSpace lunSpace = new LunSpace();
           lunSpace.setSize(dataObject.getSize());
           lunRequest.setSpace(lunSpace);
           //Lun name is full path like in unified "/vol/VolumeName/LunName"
           String lunFullName = Constants.VOLUME_PATH_PREFIX + storagePool.getName() + Constants.PATH_SEPARATOR + dataObject.getName();
           lunRequest.setName(lunFullName);

           String hypervisorType = storagePool.getHypervisor().name();
           String osType = null;
           switch (hypervisorType) {
               case Constants.KVM:
                   osType = Lun.OsTypeEnum.LINUX.getValue();
                   break;
               default:
                   String errMsg = "createCloudStackVolume : Unsupported hypervisor type " + hypervisorType + " for ONTAP storage";
                   s_logger.error(errMsg);
                   throw new CloudRuntimeException(errMsg);
           }
           lunRequest.setOsType(Lun.OsTypeEnum.valueOf(osType));

           cloudStackVolumeRequest.setLun(lunRequest);
           return cloudStackVolumeRequest;
       } else {
           throw new CloudRuntimeException("createCloudStackVolumeRequestByProtocol: Unsupported protocol " + protocol);
       }
    }
}
