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

import org.apache.cloudstack.storage.feign.model.OntapStorage;

import java.util.Map;

public class UnifiedSANStrategy extends SANStrategy{
    public UnifiedSANStrategy(OntapStorage ontapStorage) {
        super(ontapStorage);
    }

    @Override
    public void createCloudStackVolume(Map<String, String> values) {

    }

    @Override
    void updateCloudStackVolume(Map<String, String> values) {

    }

    @Override
    void deleteCloudStackVolume(Map<String, String> values) {

    }

    @Override
    void getCloudStackVolume(Map<String, String> values) {

    }

    @Override
    void enableAccess(Map<String, String> values) {

    }

    @Override
    void disableAccess(Map<String, String> values) {

    }

    @Override
    void updateAccess(Map<String, String> values) {

    }

    @Override
    void getAccess(Map<String, String> values) {

    }

    @Override
    void enableLogicalAccess(Map<String, String> values) {

    }

    @Override
    void disableLogicalAccess(Map<String, String> values) {

    }

}
