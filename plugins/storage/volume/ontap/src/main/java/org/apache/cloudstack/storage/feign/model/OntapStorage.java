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

import org.apache.cloudstack.storage.utils.Constants.ProtocolType;

public class OntapStorage {
    public static String Username;
    public static String Password;
    public static String ManagementLIF;
    public static String SvmName;
    public static ProtocolType Protocol;
    public static Boolean IsDisaggregated;

    public OntapStorage(String username, String password, String managementLIF, String svmName, ProtocolType protocol, Boolean isDisaggregated) {
        Username = username;
        Password = password;
        ManagementLIF = managementLIF;
        SvmName = svmName;
        Protocol = protocol;
        IsDisaggregated = isDisaggregated;
    }

    public String getUsername() {
        return Username;
    }

    public void setUsername(String username) {
        Username = username;
    }

    public String getPassword() {
        return Password;
    }

    public void setPassword(String password) {
        Password = password;
    }

    public String getManagementLIF() {
        return ManagementLIF;
    }

    public void setManagementLIF(String managementLIF) {
        ManagementLIF = managementLIF;
    }

    public String getSvmName() {
        return SvmName;
    }

    public void setSvmName(String svmName) {
        SvmName = svmName;
    }

    public ProtocolType getProtocol() {
        return Protocol;
    }

    public void setProtocol(ProtocolType protocol) {
        Protocol = protocol;
    }

    public Boolean getIsDisaggregated() {
        return IsDisaggregated;
    }

    public void setIsDisaggregated(Boolean isDisaggregated) {
        IsDisaggregated = isDisaggregated;
    }
}
