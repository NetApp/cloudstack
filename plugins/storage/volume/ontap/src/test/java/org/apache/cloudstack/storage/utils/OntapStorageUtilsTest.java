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

import com.cloud.alert.AlertManager;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.provider.StorageProviderFactory;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import feign.FeignException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
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
    public void isOntapSnapshotNotFoundError_matchesEntryDoesNotExist() {
        CloudRuntimeException ex = new CloudRuntimeException("Job failed with error: entry doesn't exist");
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(ex));
    }

    @Test
    public void isOntapSnapshotNotFoundError_rejectsUnrelatedErrors() {
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(
                new CloudRuntimeException("Job failed with error: permission denied")));
    }

    @Test
    public void toFlexVolRelativePath_stripsVolPrefix() {
        assertEquals("lun1", OntapStorageUtils.toFlexVolRelativePath("/vol/vol1/lun1", "vol1"));
        assertEquals("file-uuid", OntapStorageUtils.toFlexVolRelativePath("file-uuid", "vol1"));
        assertEquals("file-uuid", OntapStorageUtils.toFlexVolRelativePath("/file-uuid", "vol1"));
    }

    @Test
    public void toLunCloneSourcePathInSnapshot_buildsSnapshotQualifiedPath() {
        assertEquals("/vol/vol1/.snapshot/snap_cs200/source_lun",
                OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/vol1/source_lun", "vol1", "snap_cs200"));
        assertEquals("/vol/vol1/.snapshot/snap_cs200/source_lun",
                OntapStorageUtils.toLunCloneSourcePathInSnapshot("source_lun", "vol1", "snap_cs200"));
    }

    @Test
    public void toLunCloneSourcePathInSnapshot_rejectsBlankInputs() {
        org.junit.jupiter.api.Assertions.assertThrows(com.cloud.exception.InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/vol1/lun", "vol1", null));
        org.junit.jupiter.api.Assertions.assertThrows(com.cloud.exception.InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/vol1/lun", "", "snap"));
        org.junit.jupiter.api.Assertions.assertThrows(com.cloud.exception.InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("", "vol1", "snap"));
    }

    @Test
    public void isValidName_rejectsNullTooLongAndNonLetters() {
        assertFalse(OntapStorageUtils.isValidName(null));
        assertFalse(OntapStorageUtils.isValidName("a".repeat(201)));
        assertFalse(OntapStorageUtils.isValidName("1volume"));
        assertFalse(OntapStorageUtils.isValidName("vol-name"));
        assertTrue(OntapStorageUtils.isValidName("Volume_1"));
    }

    @Test
    public void getOSTypeFromHypervisor_mapsKvmAndRejectsOthers() {
        assertEquals("LINUX", OntapStorageUtils.getOSTypeFromHypervisor(OntapStorageConstants.KVM));
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> OntapStorageUtils.getOSTypeFromHypervisor("XenServer"));
        assertTrue(ex.getMessage().contains("Unsupported hypervisor type"));
    }

    @Test
    public void sendStorageAlert_sendsWhenManagerPresentAndSkipsWhenAbsent() {
        AlertManager alertManager = mock(AlertManager.class);

        OntapStorageUtils.sendStorageAlert(null, 1L, 2L, "subject", "body");
        OntapStorageUtils.sendStorageAlert(alertManager, null, 2L, "subject", "body");
        OntapStorageUtils.sendStorageAlert(alertManager, 4L, 5L, "subject", "body");

        verify(alertManager).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_STORAGE_MISC), eq(0L), eq(2L), eq("subject"), eq("body"));
        verify(alertManager).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_STORAGE_MISC), eq(4L), eq(5L), eq("subject"), eq("body"));
    }

    @Test
    public void resolveStrategyFromPoolDetails_rejectsEmptyDetails() {
        assertThrows(CloudRuntimeException.class, () -> OntapStorageUtils.resolveStrategyFromPoolDetails(null));
        assertThrows(CloudRuntimeException.class, () -> OntapStorageUtils.resolveStrategyFromPoolDetails(new HashMap<>()));
    }

    @Test
    public void resolveStrategyFromPoolDetails_returnsFactoryStrategy() {
        StorageStrategy strategy = mock(StorageStrategy.class);
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            factory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(strategy);

            assertSame(strategy, OntapStorageUtils.resolveStrategyFromPoolDetails(poolDetails()));
        }
    }

    @Test
    public void getStrategyByStoragePoolDetails_returnsStrategyWhenConnectSucceeds() {
        StorageStrategy strategy = mock(StorageStrategy.class);
        when(strategy.connect(false)).thenReturn(true);
        when(strategy.connect(true)).thenReturn(true);
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            factory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(strategy);

            assertSame(strategy, OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails()));
            assertSame(strategy, OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails(), true));
        }
        verify(strategy).connect(false);
        verify(strategy).connect(true);
    }

    @Test
    public void getStrategyByStoragePoolDetails_throwsWhenConnectFails() {
        StorageStrategy strategy = mock(StorageStrategy.class);
        when(strategy.connect(anyBoolean())).thenReturn(false);
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            factory.when(() -> StorageProviderFactory.getStrategy(any())).thenReturn(strategy);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails(), false));
            assertTrue(ex.getMessage().contains("Connection to Ontap SVM"));
        }
    }

    @Test
    public void getOntapSnapshotName_normalizesPrefixesAndLength() {
        assertThrows(InvalidParameterValueException.class, () -> OntapStorageUtils.getOntapSnapshotName(null));
        assertThrows(InvalidParameterValueException.class, () -> OntapStorageUtils.getOntapSnapshotName("   "));
        assertEquals("snap_1", OntapStorageUtils.getOntapSnapshotName("snap-1"));
        assertEquals("s_1snap", OntapStorageUtils.getOntapSnapshotName("1snap"));
        assertEquals("s____", OntapStorageUtils.getOntapSnapshotName("!!!"));

        String longName = "n" + "a".repeat(300);
        assertEquals(OntapStorageConstants.MAX_SNAPSHOT_NAME_LENGTH, OntapStorageUtils.getOntapSnapshotName(longName).length());
    }

    @Test
    public void buildOntapSnapshotName_appliesSuffixAndTruncates() {
        assertEquals("snapshot", OntapStorageUtils.buildOntapSnapshotName(null, null));
        assertEquals("snapshot", OntapStorageUtils.buildOntapSnapshotName("  ", ""));
        assertEquals("snap_1_cs_200", OntapStorageUtils.buildOntapSnapshotName("snap-1", "cs-200"));

        String longBase = "n" + "a".repeat(300);
        String withSuffix = OntapStorageUtils.buildOntapSnapshotName(longBase, "id");
        assertEquals(OntapStorageConstants.MAX_SNAPSHOT_NAME_LENGTH, withSuffix.length());
        assertTrue(withSuffix.endsWith("_id"));
    }

    @Test
    public void extractUuidFromOntapJobDescription_readsUuidAndIgnoresNoise() {
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription(null, "/snapshots/"));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("desc", null));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("desc", ""));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("no marker", "/snapshots/"));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /snapshots/", "/snapshots/"));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /snapshots/   ", "/snapshots/"));
        assertEquals("abc", OntapStorageUtils.extractUuidFromOntapJobDescription(
                "POST /api/snapshots/abc?fields=uuid", "/snapshots/"));
        assertEquals("abc", OntapStorageUtils.extractUuidFromOntapJobDescription(
                "POST /api/snapshots/abc/extra", "/snapshots/"));
        assertEquals("abc", OntapStorageUtils.extractUuidFromOntapJobDescription(
                "POST /snapshots/abc", "/snapshots/"));
    }

    @Test
    public void isOntapObjectNotFoundError_matchesStatusAndMessage() {
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(null));
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException()));

        FeignException notFound = mock(FeignException.class);
        when(notFound.status()).thenReturn(404);
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(notFound));

        FeignException other = mock(FeignException.class);
        when(other.status()).thenReturn(500);
        when(other.getMessage()).thenReturn("volume does not exist");
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(other));

        FeignException silent = mock(FeignException.class);
        when(silent.status()).thenReturn(500);
        when(silent.getMessage()).thenReturn(null);
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(silent));

        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException("HTTP 404 from array")));
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException("Object Not Found")));
    }

    @Test
    public void toFlexVolRelativePath_handlesMissingPrefix() {
        assertNull(OntapStorageUtils.toFlexVolRelativePath(null, "vol1"));
        assertEquals("", OntapStorageUtils.toFlexVolRelativePath("", "vol1"));
        assertEquals("lun1", OntapStorageUtils.toFlexVolRelativePath("/lun1", null));
        assertEquals("lun1", OntapStorageUtils.toFlexVolRelativePath("/lun1", ""));
        assertEquals("relative", OntapStorageUtils.toFlexVolRelativePath("relative", null));
    }

    @Test
    public void toLunCloneSourcePathInSnapshot_rejectsNullPieces() {
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/vol1/lun", null, "snap"));
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/vol1/lun", "vol1", ""));
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot(null, "vol1", "snap"));
    }

    private static Map<String, String> poolDetails() {
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.USERNAME, "admin");
        details.put(OntapStorageConstants.PASSWORD, "cGFzc3dvcmQ=");
        details.put(OntapStorageConstants.STORAGE_IP, "192.168.1.100");
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.SIZE, "1073741824");
        details.put(OntapStorageConstants.PROTOCOL, ProtocolType.NFS3.name());
        return details;
    }
}
