package org.apache.cloudstack.storage.feign.client;

import org.apache.cloudstack.storage.feign.FeignConfiguration;
import org.apache.cloudstack.storage.feign.model.SvmResponse;
import org.apache.cloudstack.storage.feign.model.Svm;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.net.URI;

@FeignClient(name = "SvmClient", url = "https://{clusterIP}/api/svm/svms", configuration = FeignConfiguration.class)
public interface SvmFeignClient {

	@RequestMapping(method = RequestMethod.GET)
	SvmResponse getSvmResponse(URI baseURL, @RequestHeader("Authorization") String header);

	@RequestMapping(method = RequestMethod.GET, value = "/{uuid}")
	Svm getSvmByUUID(URI baseURL, @RequestHeader("Authorization") String header);

}
