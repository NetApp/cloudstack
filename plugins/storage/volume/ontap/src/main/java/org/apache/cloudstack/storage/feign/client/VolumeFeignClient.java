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
package org.apache.cloudstack.storage.feign.client;

import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import java.net.URI;

public interface VolumeFeignClient {

//    @RequestLine("DELETE")
//    @Headers({
//            "Authorization: {authHeader}",
//            "Accept: application/json"
//    })
//    void deleteVolume(URI baseURL, @Param("authHeader") String authHeader, @Param("uuid") String uuid);

    @RequestLine("POST /api/storage/volumes")
    @Headers({
            "Authorization: {authHeader}",
            "Content-Type: application/json",
            "Accept: application/json"
    })
    JobResponse createVolumeWithJob(@Param("authHeader") String authHeader, Volume volumeRequest);

    @RequestLine("GET")
    @Headers({
            "Authorization: {authHeader}",
            "Accept: application/json"
    })
    Volume getVolumeByUUID(URI baseURL, @Param("authHeader") String authHeader, @Param("uuid") String uuid);

//    @RequestLine("PATCH")
//    @Headers({
//            "Authorization: {authHeader}",
//            "Content-Type: application/json",
//            "Accept: application/json"
//    })
//    JobResponse updateVolumeRebalancing(URI baseURL, @Param("acceptHeader") String acceptHeader, @Param("uuid") String uuid,  Volume volumeRequest);
}
