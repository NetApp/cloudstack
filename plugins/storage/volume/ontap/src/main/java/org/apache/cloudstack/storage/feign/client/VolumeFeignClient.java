package org.apache.cloudstack.storage.feign.client;

import feign.QueryMap;
import org.apache.cloudstack.storage.feign.model.Volume;
import org.apache.cloudstack.storage.feign.model.response.JobResponse;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;

import java.util.Map;

public interface VolumeFeignClient {

    @RequestLine("DELETE /api/storage/volumes/{uuid}")
    @Headers({"Authorization: {authHeader}"})
    JobResponse deleteVolume(@Param("authHeader") String authHeader, @Param("uuid") String uuid);

    @RequestLine("POST /api/storage/volumes")
    @Headers({"Authorization: {authHeader}"})
    JobResponse createVolumeWithJob(@Param("authHeader") String authHeader, Volume volumeRequest);

    @RequestLine("GET /api/storage/volumes")
    @Headers({"Authorization: {authHeader}"})
    OntapResponse<Volume> getAllVolumes(@Param("authHeader") String authHeader, @QueryMap Map<String, Object> queryParams);

    @RequestLine("GET /api/storage/volumes/{uuid}")
    @Headers({"Authorization: {authHeader}"})
    Volume getVolumeByUUID(@Param("authHeader") String authHeader, @Param("uuid") String uuid);

    @RequestLine("GET /api/storage/volumes")
    @Headers({"Authorization: {authHeader}"})
    OntapResponse<Volume> getVolume(@Param("authHeader") String authHeader, @QueryMap Map<String, Object> queryMap);

    @RequestLine("PATCH /api/storage/volumes/{uuid}")
    @Headers({ "Authorization: {authHeader}"})
    JobResponse updateVolumeRebalancing(@Param("authHeader") String authHeader, @Param("uuid") String uuid, Volume volumeRequest);
}
