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

import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Lun;
import org.apache.cloudstack.storage.feign.model.LunMap;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import java.net.URI;

public interface SANFeignClient {

    // LUN Operation APIs
    @RequestLine("POST /")
    @Headers({"Authorization: {authHeader}", "return_records: {returnRecords}"})
    OntapResponse<Lun> createLun(@Param("authHeader") String authHeader,
                                @Param("returnRecords") boolean returnRecords,
                                Lun lun);

    @RequestLine("GET /")
    @Headers({"Authorization: {authHeader}"})
    OntapResponse<Lun> getLunResponse(@Param("authHeader") String authHeader);

    @RequestLine("GET /{uuid}")
    @Headers({"Authorization: {authHeader}"})
    Lun getLunByUUID(@Param("authHeader") String authHeader, @Param("uuid") String uuid);

    @RequestLine("PATCH /{uuid}")
    @Headers({"Authorization: {authHeader}"})
    void updateLun(@Param("authHeader") String authHeader, @Param("uuid") String uuid, Lun lun);

    @RequestLine("DELETE /{uuid}")
    @Headers({"Authorization: {authHeader}"})
    void deleteLun(@Param("authHeader") String authHeader, @Param("uuid") String uuid);

    // iGroup Operation APIs
    @RequestLine("POST /")
    @Headers({"Authorization: {authHeader}", "return_records: {returnRecords}"})
    OntapResponse<Igroup> createIgroup(@Param("authHeader") String authHeader,
                                      @Param("returnRecords") boolean returnRecords,
                                      Igroup igroupRequest);

    @RequestLine("GET /")
    @Headers({"Authorization: {authHeader}"}) // TODO: Check this again, uuid should be part of the path?
    OntapResponse<Igroup> getIgroupResponse(@Param("authHeader") String authHeader, @Param("uuid") String uuid);

    @RequestLine("GET /{uuid}")
    @Headers({"Authorization: {authHeader}"})
    Igroup getIgroupByUUID(@Param("authHeader") String authHeader, @Param("uuid") String uuid);

    @RequestLine("DELETE /{uuid}")
    @Headers({"Authorization: {authHeader}"})
    void deleteIgroup(@Param("baseUri") URI baseUri, @Param("authHeader") String authHeader, @Param("uuid") String uuid);

    @RequestLine("POST /{uuid}/igroups")
    @Headers({"Authorization: {authHeader}", "return_records: {returnRecords}"})
    OntapResponse<Igroup> addNestedIgroups(@Param("authHeader") String authHeader,
                                          @Param("uuid") String uuid,
                                          Igroup igroupNestedRequest,
                                          @Param("returnRecords") boolean returnRecords);

    // LUN Maps Operation APIs
    @RequestLine("POST /")
    @Headers({"Authorization: {authHeader}"})
    OntapResponse<LunMap> createLunMap(@Param("authHeader") String authHeader, LunMap lunMap);

    @RequestLine("GET /")
    @Headers({"Authorization: {authHeader}"})
    OntapResponse<LunMap> getLunMapResponse(@Param("authHeader") String authHeader);

    @RequestLine("DELETE /{lunUuid}/{igroupUuid}")
    @Headers({"Authorization: {authHeader}"})
    void deleteLunMap(@Param("authHeader") String authHeader,
                     @Param("lunUuid") String lunUuid,
                     @Param("igroupUuid") String igroupUuid);
}
