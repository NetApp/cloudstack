// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Set;

import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.junit.Test;
import org.reflections.Reflections;

import com.cloud.storage.Storage.StoragePoolType;

public class OntapSanStorageAdaptorTest {

    @Test
    public void getStoragePoolTypeReturnsOntapSan() {
        assertEquals(StoragePoolType.OntapSAN, new OntapSanStorageAdaptor().getStoragePoolType());
    }

    @Test
    public void createdPoolCarriesOntapSanTypeAndRawFormat() {
        OntapSanStorageAdaptor adaptor = new OntapSanStorageAdaptor();

        KVMStoragePool pool = adaptor.createStoragePool("ontap-san-pool-uuid", "10.0.0.1", 3260, null, null,
                StoragePoolType.OntapSAN, null, true);

        assertEquals(StoragePoolType.OntapSAN, pool.getType());
        // Attach builds a block-based disk off the physical disk format rather than the pool type,
        // which is why splitting OntapSAN out of Iscsi leaves the generated domain XML unchanged.
        assertEquals(PhysicalDiskFormat.RAW, pool.getDefaultFormat());
        assertSame(pool, adaptor.getStoragePool("ontap-san-pool-uuid"));
    }

    /**
     * KVMStoragePoolManager discovers adaptors by a Reflections scan of its own package, instantiating
     * each concrete implementation through a no-arg constructor and keying it on getStoragePoolType().
     * A type with no adaptor silently falls back to LibvirtStorageAdaptor instead of failing at
     * startup, so this reproduces the discovery preconditions rather than waiting for the symptom.
     * The manager itself is not constructed here because doing so also instantiates
     * MultipathSCSIAdapterBase, which requires agent scripts resolvable from the working directory.
     */
    @Test
    public void adaptorSatisfiesThePoolManagerDiscoveryContract() throws ReflectiveOperationException {
        String scannedPackage = KVMStoragePoolManager.class.getPackage().getName();
        Set<Class<? extends StorageAdaptor>> discovered =
                new Reflections(scannedPackage).getSubTypesOf(StorageAdaptor.class);

        assertTrue("OntapSanStorageAdaptor must live in " + scannedPackage + " to be discovered",
                discovered.contains(OntapSanStorageAdaptor.class));
        assertFalse("An abstract adaptor is skipped by the scan",
                Modifier.isAbstract(OntapSanStorageAdaptor.class.getModifiers()));

        StorageAdaptor adaptor = OntapSanStorageAdaptor.class.getDeclaredConstructor().newInstance();
        assertEquals(StoragePoolType.OntapSAN, adaptor.getStoragePoolType());
        assertEquals("The superclass must keep serving the other iSCSI vendors",
                StoragePoolType.Iscsi, new IscsiAdmStorageAdaptor().getStoragePoolType());
    }
}
