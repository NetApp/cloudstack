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

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.ssl.SSLContexts;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import feign.Client;
import feign.Request;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.httpclient.ApacheHttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FeignConfigurationTest {

    @Test
    public void createClientUsesConfiguredLimitsAndDisablesKeepAlive() throws Exception {
        FeignConfiguration configuration = new FeignConfiguration();

        Client client = configuration.createClient();

        assertInstanceOf(ApacheHttpClient.class, client);
        ConnectionKeepAliveStrategy keepAliveStrategy = findKeepAliveStrategy(client);
        assertNotNull(keepAliveStrategy);
        assertEquals(0L, keepAliveStrategy.getKeepAliveDuration(null, null));
    }

    @Test
    public void createClientWrapsSslSocketFactoryFailure() {
        FeignConfiguration configuration = new FeignConfiguration();

        try (MockedStatic<SSLContexts> sslContexts = Mockito.mockStatic(SSLContexts.class)) {
            sslContexts.when(SSLContexts::custom).thenThrow(new IllegalStateException("ssl failed"));

            RuntimeException thrown = assertThrows(RuntimeException.class, configuration::createClient);
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
    }

    @Test
    public void requestInterceptorLogsRequestsWithAndWithoutBody() {
        RequestInterceptor interceptor = new FeignConfiguration().createRequestInterceptor();
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.GET);
        template.uri("/api/cluster");

        interceptor.apply(template);
        assertNull(template.body());

        template.body("{\"name\":\"svm1\"}");
        interceptor.apply(template);
        assertEquals("{\"name\":\"svm1\"}", new String(template.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void encoderWritesJsonAndRejectsNullAndUnserializableObjects() {
        Encoder encoder = new FeignConfiguration().createEncoder();
        RequestTemplate emptyTemplate = new RequestTemplate();
        encoder.encode(null, String.class, emptyTemplate);
        assertNull(emptyTemplate.body());

        RequestTemplate jsonTemplate = new RequestTemplate();
        encoder.encode(Map.of("name", "vol"), Map.class, jsonTemplate);
        assertTrue(new String(jsonTemplate.body(), StandardCharsets.UTF_8).contains("vol"));
        assertTrue(jsonTemplate.headers().values().stream().flatMap(values -> values.stream())
                .anyMatch("application/json"::equals));

        Loop loop = new Loop();
        loop.self = loop;
        EncodeException thrown = assertThrows(EncodeException.class,
                () -> encoder.encode(loop, Loop.class, new RequestTemplate()));
        assertTrue(thrown.getMessage().contains("Error encoding object to JSON"));
    }

    @Test
    public void decoderHandlesNullInvalidAndUnexpectedBodies() throws Exception {
        Decoder decoder = new FeignConfiguration().createDecoder();
        Response empty = Mockito.mock(Response.class);
        Mockito.doReturn(null).when(empty).body();
        assertNull(decoder.decode(empty, Map.class));

        Map<?, ?> decoded = (Map<?, ?>) decoder.decode(jsonResponse("{\"name\":\"vol\"}"), Map.class);
        assertEquals("vol", decoded.get("name"));

        DecodeException invalid = assertThrows(DecodeException.class,
                () -> decoder.decode(jsonResponse("not-json"), Map.class));
        assertTrue(invalid.getMessage().contains("Error decoding JSON response"));

        Response unexpected = Mockito.mock(Response.class);
        Response.Body body = Mockito.mock(Response.Body.class);
        Mockito.doReturn(body).when(unexpected).body();
        Mockito.doReturn(500).when(unexpected).status();
        Mockito.doReturn(request()).when(unexpected).request();
        Mockito.doThrow(new IllegalStateException("stream closed")).when(body).asInputStream();
        DecodeException unexpectedError = assertThrows(DecodeException.class, () -> decoder.decode(unexpected, Map.class));
        assertTrue(unexpectedError.getMessage().contains("Unexpected error during decoding"));
    }

    @Test
    public void decoderReportsIoFailuresWhileReadingTheBody() throws Exception {
        Decoder decoder = new FeignConfiguration().createDecoder();
        Response response = Mockito.mock(Response.class);
        Response.Body body = Mockito.mock(Response.Body.class);
        Mockito.doReturn(body).when(response).body();
        Mockito.doReturn(503).when(response).status();
        Mockito.doReturn(request()).when(response).request();
        InputStream stream = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("read failed");
            }
        };
        Mockito.doReturn(stream).when(body).asInputStream();

        DecodeException thrown = assertThrows(DecodeException.class, () -> decoder.decode(response, String.class));
        assertEquals(503, thrown.status());
        assertTrue(thrown.getMessage().contains("Error decoding JSON response"));
    }

    @Test
    public void clientFactoryUsesTheSuppliedConfiguration() {
        FeignConfiguration configuration = new FeignConfiguration();
        FeignClientFactory factory = new FeignClientFactory(configuration);

        Probe client = factory.createClient(Probe.class, "http://127.0.0.1");

        assertNotNull(client);
    }

    private Response jsonResponse(String json) {
        return Response.builder()
                .status(200)
                .reason("OK")
                .headers(Collections.emptyMap())
                .request(request())
                .body(json, StandardCharsets.UTF_8)
                .build();
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://127.0.0.1/api", Collections.emptyMap(), null, StandardCharsets.UTF_8);
    }

    private ConnectionKeepAliveStrategy findKeepAliveStrategy(Object root) throws Exception {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return findKeepAliveStrategy(root, seen);
    }

    private ConnectionKeepAliveStrategy findKeepAliveStrategy(Object value, Set<Object> seen) throws Exception {
        if (value == null || value instanceof String || value.getClass().isPrimitive() || !seen.add(value)) {
            return null;
        }
        if (value instanceof ConnectionKeepAliveStrategy) {
            return (ConnectionKeepAliveStrategy) value;
        }
        Class<?> type = value.getClass();
        if (type.getName().startsWith("java.")) {
            return null;
        }
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object child = field.get(value);
                ConnectionKeepAliveStrategy found = findKeepAliveStrategy(child, seen);
                if (found != null) {
                    return found;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static final class Loop {
        @SuppressWarnings("unused")
        public Loop self;
    }

    private interface Probe {
    }
}
