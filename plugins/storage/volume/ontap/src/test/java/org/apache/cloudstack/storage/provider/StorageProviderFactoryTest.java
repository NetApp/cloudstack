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
package org.apache.cloudstack.storage.provider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.UnifiedNASStrategy;
import org.apache.cloudstack.storage.service.UnifiedSANStrategy;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.component.ComponentContext;

class StorageProviderFactoryTest {

    private static String encoded(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static OntapStorage storage(String password, ProtocolType protocol) {
        return new OntapStorage("admin", password, "10.0.0.1", "svm1", 1073741824L, protocol);
    }

    @Test
    void getStrategy_returnsNasStrategy_forNfs3() {
        try (MockedStatic<ComponentContext> context = mockStatic(ComponentContext.class)) {
            StorageStrategy strategy = StorageProviderFactory.getStrategy(storage(encoded("secret"), ProtocolType.NFS3));

            assertInstanceOf(UnifiedNASStrategy.class, strategy);
            context.verify(() -> ComponentContext.inject(any(UnifiedNASStrategy.class)));
        }
    }

    @Test
    void getStrategy_returnsSanStrategy_forIscsi() {
        try (MockedStatic<ComponentContext> context = mockStatic(ComponentContext.class)) {
            StorageStrategy strategy = StorageProviderFactory.getStrategy(storage(encoded("secret"), ProtocolType.ISCSI));

            assertInstanceOf(UnifiedSANStrategy.class, strategy);
            context.verify(() -> ComponentContext.inject(any(UnifiedSANStrategy.class)));
        }
    }

    @Test
    void getStrategy_rejectsPassword_thatIsNotBase64() {
        try (MockedStatic<ComponentContext> context = mockStatic(ComponentContext.class)) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> StorageProviderFactory.getStrategy(storage("not base64 !!", ProtocolType.ISCSI)));

            assertTrue(ex.getMessage() != null);
            context.verifyNoInteractions();
        }
    }

    @Test
    void getStrategy_rejectsMissingProtocol() {
        try (MockedStatic<ComponentContext> context = mockStatic(ComponentContext.class)) {
            assertThrows(NullPointerException.class,
                    () -> StorageProviderFactory.getStrategy(storage(encoded("secret"), null)));

            context.verifyNoInteractions();
        }
    }
}
