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

import org.opensaml.xml.encryption.Public;

public class Constants {

    public static final String NFS = "nfs";
    public static final String ISCSI = "iscsi";
    public static final String SIZE = "size";
    public static final String PROTOCOL = "protocol";
    public static final String SVM_NAME = "svmName";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String DATA_LIF = "dataLIF";
    public static final String MANAGEMENT_LIF = "managementLIF";
    public static final String VOLUME_NAME = "volumeName";
    public static final String VOLUME_UUID = "volumeUUID";
    public static final String IS_DISAGGREGATED = "isDisaggregated";
    public static final String RUNNING = "running";

    public static final int ONTAP_PORT = 443;

    public static final String JOB_RUNNING = "running";
    public static final String JOB_QUEUE = "queued";
    public static final String JOB_PAUSED = "paused";
    public static final String JOB_FAILURE = "failure";
    public static final String JOB_SUCCESS = "success";

    public static final String TRUE = "true";
    public static final String FALSE = "false";

    // Query params
    public static final String NAME = "name";
    public static final String FIELDS = "fields";
    public static final String AGGREGATES = "aggregates";
    public static final String STATE = "state";
    public static final String SVMNAME = "svm.name";
    public static final String DATA_NFS = "data_nfs";
    public static final String DATA_ISCSI = "data_iscsi";
    public static final String IP_ADDRESS = "ip.address";
    public static final String SERVICES = "services";
    public static final String RETURN_RECORDS = "return_records";

    public static final int JOB_MAX_RETRIES = 100;
    public static final int CREATE_VOLUME_CHECK_SLEEP_TIME = 2000;

    public static final String PATH_SEPARATOR = "/";
    public static final String EQUALS = "=";
    public static final String SEMICOLON = ";";
    public static final String COMMA = ",";

    public static final String VOLUME_PATH_PREFIX = "/vol/";

    public static final String KVM = "KVM";

    public static final String HTTPS = "https://";

}
