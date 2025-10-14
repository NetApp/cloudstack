package org.apache.cloudstack.storage.feign.client;

import org.apache.cloudstack.storage.feign.FeignConfiguration;
import org.apache.cloudstack.storage.feign.model.Cluster;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.net.URI;

@FeignClient(name="ClusterClient", url="https://{clusterIP}/api/cluster", configuration = FeignConfiguration.class)
public interface ClusterFeignClient {
    @RequestMapping(method= RequestMethod.GET)
    Cluster getCluster(URI baseURL, @RequestHeader("Authorization") String header, @RequestHeader("return_records") boolean value);

}
