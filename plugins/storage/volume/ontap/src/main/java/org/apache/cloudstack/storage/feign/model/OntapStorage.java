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

package org.apache.cloudstack.storage.feign.model;

import org.apache.cloudstack.storage.service.model.ProtocolType;

public class OntapStorage {
    private String username;
    private String password;
    private String managementLIF;
    private String svmName;
    private ProtocolType protocolType;
    private Boolean isDisaggregated;

    public OntapStorage(String username, String password, String managementLIF, String svmName, ProtocolType protocolType, Boolean isDisaggregated) {
        this.username = username;
        this.password = password;
        this.managementLIF = managementLIF;
        this.svmName = svmName;
        this.protocolType = protocolType;
        this.isDisaggregated = isDisaggregated;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getManagementLIF() {
        return managementLIF;
    }

    public void setManagementLIF(String managementLIF) {
        this.managementLIF = managementLIF;
    }

    public String getSvmName() {
        return svmName;
    }

    public void setSvmName(String svmName) {
        this.svmName = svmName;
    }

    public ProtocolType getProtocol() {
        return protocolType;
    }

    public void setProtocol(ProtocolType protocolType) {
        this.protocolType = protocolType;
    }

    public Boolean getIsDisaggregated() {
        return isDisaggregated;
    }

    public void setIsDisaggregated(Boolean isDisaggregated) {
        this.isDisaggregated = isDisaggregated;
    }
}
