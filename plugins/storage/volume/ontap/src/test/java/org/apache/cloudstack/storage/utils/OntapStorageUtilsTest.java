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
package org.apache.cloudstack.storage.utils;

import java.util.HashMap;
import java.util.Map;

import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.feign.model.VolumeQosPolicy;
import org.apache.cloudstack.storage.service.model.CloudStackVolume;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OntapStorageUtilsTest {

    @Test
    public void getIgroupName_returnsExpectedFormat_whenWithinLimit() {
        String result = OntapStorageUtils.getIgroupName("svm1", "host-uuid-123");

        assertEquals("cs_host-uuid-123_svm1", result);
        assertTrue(result.length() <= OntapStorageConstants.IGROUP_NAME_MAX_LENGTH);
    }

    @Test
    public void getIgroupName_sanitizesInvalidCharacters() {
        // Characters outside [a-zA-Z0-9_-] in the host uuid must be replaced with '_'.
        String result = OntapStorageUtils.getIgroupName("svm1", "host.uuid:123/abc");

        assertEquals("cs_host_uuid_123_abc_svm1", result);
    }

    @Test
    public void getIgroupName_doesNotTruncate_whenExactlyAtMaxLength() {
        // Format: cs(2) + _(1) + hostUuid + _(1) + svmName
        // For an overall length of 96 with a 4-char uuid, svmName must be 88 chars.
        String svmName = "a".repeat(88);
        String hostUuid = "uuid";

        String result = OntapStorageUtils.getIgroupName(svmName, hostUuid);

        assertEquals(OntapStorageConstants.IGROUP_NAME_MAX_LENGTH, result.length());
        assertEquals("cs_" + hostUuid + "_" + svmName, result);
    }

    @Test
    public void getIgroupName_truncates_whenExceedingMaxLength() {
        String svmName = "a".repeat(200);
        String hostUuid = "host-uuid-123";

        String result = OntapStorageUtils.getIgroupName(svmName, hostUuid);

        assertEquals(OntapStorageConstants.IGROUP_NAME_MAX_LENGTH, result.length());
        // The truncated value must still be a prefix of the full, untruncated name.
        String fullName = "cs_" + hostUuid + "_" + svmName;
        assertEquals(fullName.substring(0, OntapStorageConstants.IGROUP_NAME_MAX_LENGTH), result);
        assertTrue(result.startsWith("cs_"));
    }

    @Test
    public void getIgroupName_truncates_whenOneCharOverMaxLength() {
        // Build a name that is exactly one character over the limit (97 chars):
        // svmName of 89 chars + 4-char uuid -> 2 + 1 + 4 + 1 + 89 = 97.
        String svmName = "a".repeat(89);
        String hostUuid = "uuid";

        String result = OntapStorageUtils.getIgroupName(svmName, hostUuid);

        assertEquals(OntapStorageConstants.IGROUP_NAME_MAX_LENGTH, result.length());
    }

    @Test
    public void createCloudStackVolumeRequestByProtocol_attachesQosToIscsiLun() {
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        when(storagePool.getName()).thenReturn("pool1");
        when(storagePool.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getName()).thenReturn("data_disk");
        when(volumeInfo.getSize()).thenReturn(1073741824L);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.ISCSI.name());
        details.put(OntapStorageConstants.SVM_NAME, "svm1");

        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to200_iops_svm1");
        qosPolicy.setUuid("qos-uuid");

        CloudStackVolume request = OntapStorageUtils.createCloudStackVolumeRequestByProtocol(
                storagePool, details, volumeInfo, qosPolicy);

        assertNotNull(request.getLun().getQosPolicy());
        assertEquals("qos-uuid", request.getLun().getQosPolicy().getUuid());
        assertEquals("cs_100_to200_iops_svm1", request.getLun().getQosPolicy().getName());
    }

    @Test
    public void createCloudStackVolumeRequestByProtocol_attachesQosToNfsFile() {
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        when(storagePool.getId()).thenReturn(1L);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);

        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        details.put(OntapStorageConstants.VOLUME_UUID, "flex-uuid");

        VolumeQosPolicy qosPolicy = new VolumeQosPolicy();
        qosPolicy.setName("cs_100_to200_iops_svm1");
        qosPolicy.setUuid("qos-uuid");

        CloudStackVolume request = OntapStorageUtils.createCloudStackVolumeRequestByProtocol(
                storagePool, details, volumeInfo, qosPolicy);

        assertEquals("flex-uuid", request.getFlexVolumeUuid());
        assertNotNull(request.getFile().getQosPolicy());
        assertEquals("qos-uuid", request.getFile().getQosPolicy().getUuid());
    }

    @Test
    public void isOntapSnapshotNotFoundError_matchesEntryDoesNotExist() {
        CloudRuntimeException ex = new CloudRuntimeException("Job failed with error: entry doesn't exist");
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(ex));
    }

    @Test
    public void isOntapSnapshotNotFoundError_rejectsUnrelatedErrors() {
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(
                new CloudRuntimeException("Job failed with error: permission denied")));
    }
}
