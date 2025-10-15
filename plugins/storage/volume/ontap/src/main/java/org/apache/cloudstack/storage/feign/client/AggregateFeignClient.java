package org.apache.cloudstack.storage.feign.client;

import org.apache.cloudstack.storage.feign.model.AggregateResponse;
import org.apache.cloudstack.storage.feign.model.Aggregate;
import org.apache.cloudstack.storage.feign.FeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.net.URI;

@Lazy
@FeignClient(name="AggregateClient", url="https://{clusterIP}/api/storage/aggregates", configuration = FeignConfiguration.class)
public interface AggregateFeignClient {

	@RequestMapping(method=RequestMethod.GET)
	AggregateResponse getAggregateResponse(URI baseURL, @RequestHeader("Authorization") String header);

	@RequestMapping(method=RequestMethod.GET, value="/{uuid}")
	Aggregate getAggregateByUUID(URI baseURL,@RequestHeader("Authorization") String header, @PathVariable(name = "uuid", required = true) String uuid);

}