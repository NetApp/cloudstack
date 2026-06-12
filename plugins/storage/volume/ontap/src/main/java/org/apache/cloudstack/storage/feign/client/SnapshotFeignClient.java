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

import feign.Headers;
import feign.Param;
import feign.QueryMap;
import feign.RequestLine;
import org.apache.cloudstack.storage.feign.model.FlexVolSnapshot;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;

import java.util.Map;

/**
 * Feign client for ONTAP FlexVolume snapshot operations.
 *
 * <p>Maps to the ONTAP REST API endpoint:
 * {@code /api/storage/volumes/{volume_uuid}/snapshots}</p>
 *
 * <p>FlexVolume snapshots are point-in-time, space-efficient copies of an entire
 * FlexVolume. Unlike file-level clones, a single FlexVolume snapshot atomically
 * captures <b>all</b> files/LUNs within the volume, making it ideal for VM-level
 * snapshots when multiple CloudStack disks reside on the same FlexVolume.</p>
 */
public interface SnapshotFeignClient {

    /**
     * Creates a new snapshot for the specified FlexVolume.
     *
     * <p>ONTAP REST: {@code POST /api/storage/volumes/{volume_uuid}/snapshots}</p>
     *
     * @param authHeader  Basic auth header
     * @param volumeUuid  UUID of the ONTAP FlexVolume
     * @param snapshot    Snapshot request body (at minimum, the {@code name} field)
     * @return JobResponse containing the async job reference
     */
    @RequestLine("POST /api/storage/volumes/{volumeUuid}/snapshots")
    @Headers({"Authorization: {authHeader}", "Content-Type: application/json"})
    JobResponse createSnapshot(@Param("authHeader") String authHeader,
                               @Param("volumeUuid") String volumeUuid,
                               FlexVolSnapshot snapshot);

    /**
     * Lists snapshots for the specified FlexVolume.
     *
     * <p>ONTAP REST: {@code GET /api/storage/volumes/{volume_uuid}/snapshots}</p>
     *
     * @param authHeader  Basic auth header
     * @param volumeUuid  UUID of the ONTAP FlexVolume
     * @param queryParams Optional query parameters (e.g., {@code name}, {@code fields})
     * @return Paginated response of FlexVolSnapshot records
     */
    @RequestLine("GET /api/storage/volumes/{volumeUuid}/snapshots")
    @Headers({"Authorization: {authHeader}"})
    OntapResponse<FlexVolSnapshot> getSnapshots(@Param("authHeader") String authHeader,
                                                @Param("volumeUuid") String volumeUuid,
                                                @QueryMap Map<String, Object> queryParams);

    /**
     * Retrieves a specific snapshot by UUID.
     *
     * <p>ONTAP REST: {@code GET /api/storage/volumes/{volume_uuid}/snapshots/{uuid}}</p>
     *
     * @param authHeader    Basic auth header
     * @param volumeUuid    UUID of the ONTAP FlexVolume
     * @param snapshotUuid  UUID of the snapshot
     * @return The FlexVolSnapshot object
     */
    @RequestLine("GET /api/storage/volumes/{volumeUuid}/snapshots/{snapshotUuid}")
    @Headers({"Authorization: {authHeader}"})
    FlexVolSnapshot getSnapshotByUuid(@Param("authHeader") String authHeader,
                                      @Param("volumeUuid") String volumeUuid,
                                      @Param("snapshotUuid") String snapshotUuid);

    /**
     * Deletes a specific snapshot.
     *
     * <p>ONTAP REST: {@code DELETE /api/storage/volumes/{volume_uuid}/snapshots/{uuid}}</p>
     *
     * @param authHeader    Basic auth header
     * @param volumeUuid    UUID of the ONTAP FlexVolume
     * @param snapshotUuid  UUID of the snapshot to delete
     * @return JobResponse containing the async job reference
     */
    @RequestLine("DELETE /api/storage/volumes/{volumeUuid}/snapshots/{snapshotUuid}")
    @Headers({"Authorization: {authHeader}"})
    JobResponse deleteSnapshot(@Param("authHeader") String authHeader,
                               @Param("volumeUuid") String volumeUuid,
                               @Param("snapshotUuid") String snapshotUuid);

}
