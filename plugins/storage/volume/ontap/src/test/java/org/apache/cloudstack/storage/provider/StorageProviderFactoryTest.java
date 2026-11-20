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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.UnifiedNASStrategy;
import org.apache.cloudstack.storage.service.UnifiedSANStrategy;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.utils.component.ComponentContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StorageProviderFactoryTest {

    @Test
    public void constructorCanBeInstantiated() {
        assertNotNull(new StorageProviderFactory());
    }

    @Test
    public void getStrategyReturnsNasStrategyForNfs3() throws Exception {
        StorageStrategy strategy = getStrategy(storage(ProtocolType.NFS3));

        assertInstanceOf(UnifiedNASStrategy.class, strategy);
        assertEquals("secret", storedPassword(strategy));
    }

    @Test
    public void getStrategyReturnsSanStrategyForIscsi() throws Exception {
        StorageStrategy strategy = getStrategy(storage(ProtocolType.ISCSI));

        assertInstanceOf(UnifiedSANStrategy.class, strategy);
        assertEquals("secret", storedPassword(strategy));
    }

    @Test
    public void getStrategyRejectsPasswordThatIsNotBase64() {
        OntapStorage storage = new OntapStorage("admin", "%%%", "10.0.0.8", "svm1", 1L, ProtocolType.NFS3);

        assertThrows(IllegalArgumentException.class, () -> getStrategy(storage));
    }

    private StorageStrategy getStrategy(OntapStorage storage) {
        try (MockedStatic<ComponentContext> componentContext = Mockito.mockStatic(ComponentContext.class)) {
            componentContext.when(() -> ComponentContext.inject(Mockito.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            StorageStrategy strategy = StorageProviderFactory.getStrategy(storage);
            if (strategy != null) {
                componentContext.verify(() -> ComponentContext.inject(strategy));
            }
            return strategy;
        }
    }

    private OntapStorage storage(ProtocolType protocol) {
        String encodedPassword = Base64.getEncoder().encodeToString("secret".getBytes(StandardCharsets.UTF_8));
        return new OntapStorage("admin", encodedPassword, "10.0.0.8", "svm1", 1024L, protocol);
    }

    private String storedPassword(StorageStrategy strategy) throws Exception {
        Field storageField = strategy.getClass().getSuperclass().getSuperclass().getDeclaredField("storage");
        storageField.setAccessible(true);
        return ((OntapStorage) storageField.get(strategy)).getPassword();
    }
}
