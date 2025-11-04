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

package org.apache.cloudstack.storage.feign;

import feign.RequestInterceptor;
import feign.Retryer;
import feign.Client;
import feign.httpclient.ApacheHttpClient;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.Response;
import feign.codec.DecodeException;
import feign.codec.EncodeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class FeignConfiguration {
    private static final Logger logger = LogManager.getLogger(FeignConfiguration.class);

    private final int retryMaxAttempt = 3;
    private final int retryMaxInterval = 5;
    private final String ontapFeignMaxConnection = "80";
    private final String ontapFeignMaxConnectionPerRoute = "20";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Client createClient() {
        int maxConn;
        int maxConnPerRoute;
        try {
            maxConn = Integer.parseInt(this.ontapFeignMaxConnection);
        } catch (Exception e) {
            logger.error("ontapFeignClient: encounter exception while parse the max connection from env. setting default value");
            maxConn = 20;
        }
        try {
            maxConnPerRoute = Integer.parseInt(this.ontapFeignMaxConnectionPerRoute);
        } catch (Exception e) {
            logger.error("ontapFeignClient: encounter exception while parse the max connection per route from env. setting default value");
            maxConnPerRoute = 2;
        }

        // Disable Keep Alive for Http Connection
        logger.debug("ontapFeignClient: Setting the feign client config values as max connection: {}, max connections per route: {}", maxConn, maxConnPerRoute);
        ConnectionKeepAliveStrategy keepAliveStrategy = (response, context) -> 0;
        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setMaxConnTotal(maxConn)
                .setMaxConnPerRoute(maxConnPerRoute)
                .setKeepAliveStrategy(keepAliveStrategy)
                .setSSLSocketFactory(getSSLSocketFactory())
                .setConnectionTimeToLive(60, TimeUnit.SECONDS)
                .build();
        return new ApacheHttpClient(httpClient);
    }

    private SSLConnectionSocketFactory getSSLSocketFactory() {
        try {
            // The TrustAllStrategy is a strategy used in SSL context configuration that accepts any certificate
            SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, new TrustAllStrategy()).build();
            return new SSLConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public RequestInterceptor createRequestInterceptor() {
        return template -> {
            logger.info("Feign Request URL: {}", template.url());
            logger.info("HTTP Method: {}", template.method());
            logger.info("Headers: {}", template.headers());
            if (template.body() != null) {
                logger.info("Body: {}", new String(template.body()));
            }
        };
    }

    public Retryer createRetryer() {
        return new Retryer.Default(1000L, retryMaxInterval * 1000L, retryMaxAttempt);
    }

    public Encoder createEncoder() {
        return new Encoder() {
            @Override
            public void encode(Object object, Type bodyType, feign.RequestTemplate template) throws EncodeException {
                try {
                    String json = objectMapper.writeValueAsString(object);
                    logger.debug("Encoding object to JSON: {}", json);

                    // Use the String version - it handles charset conversion internally
                    template.body(json);

                    template.header("Content-Type", "application/json");
                } catch (JsonProcessingException e) {
                    throw new EncodeException("Error encoding object to JSON", e);
                }
            }
        };
    }

    public Decoder createDecoder() {
        return new Decoder() {
            @Override
            public Object decode(Response response, Type type) throws IOException, DecodeException {
                if (response.body() == null) {
                    return null;
                }
                try {
                    String json = new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return objectMapper.readValue(json, objectMapper.getTypeFactory().constructType(type));
                } catch (IOException e) {
                    throw new DecodeException(response.status(), "Error decoding JSON response", response.request(), e);
                }
            }
        };
    }

}