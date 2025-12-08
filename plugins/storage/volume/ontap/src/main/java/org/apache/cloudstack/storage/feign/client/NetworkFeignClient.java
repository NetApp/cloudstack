package org.apache.cloudstack.storage.feign.client;

import feign.Headers;
import feign.Param;
import feign.QueryMap;
import feign.RequestLine;
import org.apache.cloudstack.storage.feign.model.IpInterface;
import org.apache.cloudstack.storage.feign.model.response.OntapResponse;

import java.util.Map;

public interface NetworkFeignClient {
    @RequestLine("GET /api/network/ip/interfaces")
    @Headers({"Authorization: {authHeader}"})
    OntapResponse<IpInterface> getNetworkIpInterfaces(@Param("authHeader") String authHeader, @QueryMap Map<String, Object> queryParams);
}
