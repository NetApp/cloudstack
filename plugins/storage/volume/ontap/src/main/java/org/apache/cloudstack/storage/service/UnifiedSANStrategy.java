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

import com.cloud.utils.component.ComponentContext;
import org.apache.cloudstack.storage.feign.FeignClientFactory;
import org.apache.cloudstack.storage.feign.client.SANFeignClient;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.utils.Utility;

public class UnifiedSANStrategy extends SANStrategy{

    private Utility utils;
   // Add missing Feign client setup for NAS operations
    private FeignClientFactory feignClientFactory;
    private  SANFeignClient sanFeignClient;


    public UnifiedSANStrategy() {
        super();
        initializeDependencies();
    }

    public UnifiedSANStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
    }

    private void initializeDependencies() {
        utils = ComponentContext.inject(Utility.class);
        // Initialize FeignClientFactory and create NAS client
        feignClientFactory = new FeignClientFactory();
        sanFeignClient = feignClientFactory.createClient(SANFeignClient.class);// TODO needs to be changed
    }

    @Override
    public String createLUN(String svmName, String volumeName, String lunName, long sizeBytes, String osType) {
        return "";
    }

    @Override
    public String createIgroup(String svmName, String igroupName, String[] initiators) {
        return "";
    }

    @Override
    public String mapLUNToIgroup(String lunName, String igroupName) {
        return "";
    }

    @Override
    public String enableISCSI(String svmUuid) {
        return "";
    }
}

