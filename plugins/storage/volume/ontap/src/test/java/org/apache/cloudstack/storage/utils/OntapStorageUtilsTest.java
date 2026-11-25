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

import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.cloudstack.storage.feign.model.OntapStorage;
import org.apache.cloudstack.storage.provider.StorageProviderFactory;
import org.apache.cloudstack.storage.service.StorageStrategy;
import org.apache.cloudstack.storage.service.model.ProtocolType;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import com.cloud.alert.AlertManager;
import com.cloud.exception.InvalidParameterValueException;
import feign.FeignException;

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


    private static final int MAX_NAME = OntapStorageConstants.MAX_SNAPSHOT_NAME_LENGTH;

    // ------------------------------------------------------- auth header

    @Test
    void generateAuthHeader_returnsBasicBase64OfUserAndPassword() {
        assertEquals("Basic YWRtaW46cGFzcw==", OntapStorageUtils.generateAuthHeader("admin", "pass"));
    }

    // ------------------------------------------------------- isValidName

    @Test
    void isValidName_acceptsLettersDigitsAndUnderscores() {
        assertTrue(OntapStorageUtils.isValidName("vol_01"));
    }

    @Test
    void isValidName_acceptsNameOfExactlyTwoHundredCharacters() {
        assertTrue(OntapStorageUtils.isValidName("a".repeat(200)));
    }

    @Test
    void isValidName_rejectsNull() {
        assertFalse(OntapStorageUtils.isValidName(null));
    }

    @Test
    void isValidName_rejectsEmptyName() {
        assertFalse(OntapStorageUtils.isValidName(""));
    }

    @Test
    void isValidName_rejectsNameLongerThanTwoHundredCharacters() {
        assertFalse(OntapStorageUtils.isValidName("a".repeat(201)));
    }

    @Test
    void isValidName_rejectsNameStartingWithDigitOrUnderscore() {
        assertFalse(OntapStorageUtils.isValidName("1vol"));
        assertFalse(OntapStorageUtils.isValidName("_vol"));
    }

    @Test
    void isValidName_rejectsHyphenAndOtherSpecialCharacters() {
        assertFalse(OntapStorageUtils.isValidName("vol-01"));
        assertFalse(OntapStorageUtils.isValidName("vol 01"));
        assertFalse(OntapStorageUtils.isValidName("vol.01"));
    }

    // ------------------------------------------------ getOSTypeFromHypervisor

    @Test
    void getOSTypeFromHypervisor_returnsLinux_forKvm() {
        assertEquals("LINUX", OntapStorageUtils.getOSTypeFromHypervisor(OntapStorageConstants.KVM));
    }

    @Test
    void getOSTypeFromHypervisor_throws_forUnsupportedHypervisor() {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> OntapStorageUtils.getOSTypeFromHypervisor("VMware"));

        assertTrue(ex.getMessage().contains("Unsupported hypervisor type VMware"), ex.getMessage());
    }

    // ------------------------------------------------------ sendStorageAlert

    @Test
    void sendStorageAlert_doesNothing_whenAlertManagerIsNull() {
        assertDoesNotThrow(() -> OntapStorageUtils.sendStorageAlert(null, 1L, 2L, "subject", "body"));
    }

    @Test
    void sendStorageAlert_forwardsZoneAndPod_whenProvided() {
        AlertManager alertMgr = mock(AlertManager.class);

        OntapStorageUtils.sendStorageAlert(alertMgr, 7L, 3L, "subject", "body");

        verify(alertMgr).sendAlert(AlertManager.AlertType.ALERT_TYPE_STORAGE_MISC, 7L, 3L, "subject", "body");
    }

    @Test
    void sendStorageAlert_usesZoneZero_whenZoneIsNull() {
        AlertManager alertMgr = mock(AlertManager.class);

        OntapStorageUtils.sendStorageAlert(alertMgr, null, null, "subject", "body");

        verify(alertMgr).sendAlert(AlertManager.AlertType.ALERT_TYPE_STORAGE_MISC, 0L, null, "subject", "body");
    }

    // -------------------------------------------- strategy resolution

    private Map<String, String> poolDetails(String protocol) {
        Map<String, String> details = new HashMap<>();
        details.put(OntapStorageConstants.USERNAME, "admin");
        details.put(OntapStorageConstants.PASSWORD, "c2VjcmV0");
        details.put(OntapStorageConstants.STORAGE_IP, "10.0.0.1");
        details.put(OntapStorageConstants.SVM_NAME, "svm1");
        details.put(OntapStorageConstants.SIZE, "1073741824");
        details.put(OntapStorageConstants.PROTOCOL, protocol);
        return details;
    }

    @Test
    void resolveStrategyFromPoolDetails_throws_whenDetailsAreNull() {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> OntapStorageUtils.resolveStrategyFromPoolDetails(null));

        assertTrue(ex.getMessage().contains("null or empty"), ex.getMessage());
    }

    @Test
    void resolveStrategyFromPoolDetails_throws_whenDetailsAreEmpty() {
        assertThrows(CloudRuntimeException.class,
                () -> OntapStorageUtils.resolveStrategyFromPoolDetails(Collections.emptyMap()));
    }

    @Test
    void resolveStrategyFromPoolDetails_buildsOntapStorageFromDetails() {
        StorageStrategy strategy = mock(StorageStrategy.class);
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            factory.when(() -> StorageProviderFactory.getStrategy(any(OntapStorage.class))).thenReturn(strategy);

            StorageStrategy resolved = OntapStorageUtils.resolveStrategyFromPoolDetails(poolDetails("ISCSI"));

            assertSame(strategy, resolved);
            ArgumentCaptor<OntapStorage> captor = ArgumentCaptor.forClass(OntapStorage.class);
            factory.verify(() -> StorageProviderFactory.getStrategy(captor.capture()));
            assertEquals("admin", captor.getValue().getUsername());
            assertEquals("c2VjcmV0", captor.getValue().getPassword());
            assertEquals("10.0.0.1", captor.getValue().getStorageIP());
            assertEquals("svm1", captor.getValue().getSvmName());
            assertEquals(1073741824L, captor.getValue().getSize());
            assertEquals(ProtocolType.ISCSI, captor.getValue().getProtocol());
        }
    }

    @Test
    void resolveStrategyFromPoolDetails_throws_whenSizeIsNotNumeric() {
        Map<String, String> details = poolDetails("ISCSI");
        details.put(OntapStorageConstants.SIZE, "big");

        assertThrows(NumberFormatException.class, () -> OntapStorageUtils.resolveStrategyFromPoolDetails(details));
    }

    @Test
    void resolveStrategyFromPoolDetails_throws_whenProtocolIsUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> OntapStorageUtils.resolveStrategyFromPoolDetails(poolDetails("FC")));
    }

    @Test
    void getStrategyByStoragePoolDetails_returnsConnectedStrategy() {
        StorageStrategy strategy = mock(StorageStrategy.class);
        when(strategy.connect(false)).thenReturn(true);
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            factory.when(() -> StorageProviderFactory.getStrategy(any(OntapStorage.class))).thenReturn(strategy);

            assertSame(strategy, OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails("NFS3")));
        }
    }

    @Test
    void getStrategyByStoragePoolDetails_passesAggregateValidationFlagToConnect() {
        StorageStrategy strategy = mock(StorageStrategy.class);
        when(strategy.connect(true)).thenReturn(true);
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            factory.when(() -> StorageProviderFactory.getStrategy(any(OntapStorage.class))).thenReturn(strategy);

            assertSame(strategy, OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails("NFS3"), true));
            verify(strategy).connect(true);
        }
    }

    @Test
    void getStrategyByStoragePoolDetails_throws_whenConnectionFails() {
        StorageStrategy strategy = mock(StorageStrategy.class);
        when(strategy.connect(anyBoolean())).thenReturn(false);
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            factory.when(() -> StorageProviderFactory.getStrategy(any(OntapStorage.class))).thenReturn(strategy);

            CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                    () -> OntapStorageUtils.getStrategyByStoragePoolDetails(poolDetails("NFS3")));

            assertTrue(ex.getMessage().contains("svm1"), ex.getMessage());
            assertTrue(ex.getMessage().contains("failed"), ex.getMessage());
        }
    }

    @Test
    void getStrategyByStoragePoolDetails_doesNotConnect_whenDetailsAreInvalid() {
        try (MockedStatic<StorageProviderFactory> factory = mockStatic(StorageProviderFactory.class)) {
            assertThrows(CloudRuntimeException.class, () -> OntapStorageUtils.getStrategyByStoragePoolDetails(null));

            factory.verifyNoInteractions();
        }
    }

    // ------------------------------------------ simple name/path builders

    @Test
    void generateExportPolicyName_joinsSvmAndVolumeWithHyphens() {
        assertEquals("cs-svm1-vol1", OntapStorageUtils.generateExportPolicyName("svm1", "vol1"));
    }

    @Test
    void getLunName_buildsUnifiedLunPath() {
        assertEquals("/vol/flexvol/lun1", OntapStorageUtils.getLunName("flexvol", "lun1"));
    }

    // ------------------------------------------ toFlexVolRelativePath

    @Test
    void toFlexVolRelativePath_returnsNull_forNullPath() {
        assertNull(OntapStorageUtils.toFlexVolRelativePath(null, "fv"));
    }

    @Test
    void toFlexVolRelativePath_returnsEmpty_forEmptyPath() {
        assertEquals("", OntapStorageUtils.toFlexVolRelativePath("", "fv"));
    }

    @Test
    void toFlexVolRelativePath_stripsFlexVolPrefix_forAbsoluteLunPath() {
        assertEquals("dir/lun1", OntapStorageUtils.toFlexVolRelativePath("/vol/fv/dir/lun1", "fv"));
    }

    @Test
    void toFlexVolRelativePath_keepsRelativePathUnchanged() {
        assertEquals("uuid-file", OntapStorageUtils.toFlexVolRelativePath("uuid-file", "fv"));
    }

    @Test
    void toFlexVolRelativePath_stripsLeadingSlash_whenFlexVolNameMissing() {
        assertEquals("abs/path", OntapStorageUtils.toFlexVolRelativePath("/abs/path", null));
        assertEquals("abs/path", OntapStorageUtils.toFlexVolRelativePath("/abs/path", ""));
    }

    @Test
    void toFlexVolRelativePath_stripsLeadingSlash_whenPathBelongsToOtherFlexVol() {
        assertEquals("vol/other/lun1", OntapStorageUtils.toFlexVolRelativePath("/vol/other/lun1", "fv"));
    }

    // ------------------------------------ toLunCloneSourcePathInSnapshot

    @Test
    void toLunCloneSourcePathInSnapshot_buildsPathInsideSnapshotDirectory() {
        assertEquals("/vol/fv/.snapshot/snap1/lun1",
                OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/fv/lun1", "fv", "snap1"));
    }

    @Test
    void toLunCloneSourcePathInSnapshot_throws_whenFlexVolNameMissing() {
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/fv/lun1", null, "snap1"));
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/fv/lun1", "", "snap1"));
    }

    @Test
    void toLunCloneSourcePathInSnapshot_throws_whenSnapshotNameMissing() {
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/fv/lun1", "fv", null));
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/fv/lun1", "fv", ""));
    }

    @Test
    void toLunCloneSourcePathInSnapshot_throws_whenLunPathMissingOrOnlyFlexVol() {
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot(null, "fv", "snap1"));
        assertThrows(InvalidParameterValueException.class,
                () -> OntapStorageUtils.toLunCloneSourcePathInSnapshot("/vol/fv/", "fv", "snap1"));
    }

    // ------------------------------------------------ getOntapSnapshotName

    @Test
    void getOntapSnapshotName_throws_forNullOrBlankName() {
        assertThrows(InvalidParameterValueException.class, () -> OntapStorageUtils.getOntapSnapshotName(null));
        assertThrows(InvalidParameterValueException.class, () -> OntapStorageUtils.getOntapSnapshotName("   "));
    }

    @Test
    void getOntapSnapshotName_replacesInvalidCharactersWithUnderscores() {
        assertEquals("my_snap_1", OntapStorageUtils.getOntapSnapshotName("my snap-1"));
    }

    @Test
    void getOntapSnapshotName_prefixesNamesThatDoNotStartWithLetter() {
        assertEquals("s_1abc", OntapStorageUtils.getOntapSnapshotName("1abc"));
        assertEquals("s___", OntapStorageUtils.getOntapSnapshotName("@@"));
    }

    @Test
    void getOntapSnapshotName_truncatesToMaximumLength() {
        String result = OntapStorageUtils.getOntapSnapshotName("a".repeat(MAX_NAME + 50));

        assertEquals(MAX_NAME, result.length());
    }

    // --------------------------------------------- buildOntapSnapshotName

    @Test
    void buildOntapSnapshotName_appendsSanitizedSuffix() {
        assertEquals("daily_cs_77", OntapStorageUtils.buildOntapSnapshotName("daily", "cs-77"));
    }

    @Test
    void buildOntapSnapshotName_usesDefaultBase_whenNameIsNullOrBlank() {
        assertEquals("snapshot_cs1", OntapStorageUtils.buildOntapSnapshotName(null, "cs1"));
        assertEquals("snapshot_cs1", OntapStorageUtils.buildOntapSnapshotName("  ", "cs1"));
    }

    @Test
    void buildOntapSnapshotName_omitsSeparator_whenSuffixIsNullOrEmpty() {
        assertEquals("daily", OntapStorageUtils.buildOntapSnapshotName("daily", null));
        assertEquals("daily", OntapStorageUtils.buildOntapSnapshotName("daily", ""));
    }

    @Test
    void buildOntapSnapshotName_truncatesBaseSoSuffixAlwaysFits() {
        String result = OntapStorageUtils.buildOntapSnapshotName("a".repeat(MAX_NAME + 10), "cs9");

        assertEquals(MAX_NAME, result.length());
        assertTrue(result.endsWith("_cs9"));
    }

    @Test
    void buildOntapSnapshotName_returnsTruncatedBaseWithoutSuffix_whenSuffixConsumesWholeBudget() {
        String hugeSuffix = "x".repeat(MAX_NAME);

        String result = OntapStorageUtils.buildOntapSnapshotName("a".repeat(MAX_NAME + 20), hugeSuffix);

        assertEquals("a".repeat(MAX_NAME), result);
    }

    @Test
    void buildOntapSnapshotName_dropsSuffixWhenItExactlyFillsTheBudget() {
        String suffixFillingBudget = "x".repeat(MAX_NAME - 1);

        String result = OntapStorageUtils.buildOntapSnapshotName("a".repeat(MAX_NAME + 20), suffixFillingBudget);

        assertEquals("a".repeat(MAX_NAME), result);
    }

    @Test
    void extractUuid_findsSegmentAtTheVeryStartOfTheDescription() {
        assertEquals("u-1", OntapStorageUtils.extractUuidFromOntapJobDescription("/snapshots/u-1", "/snapshots/"));
    }

    // ------------------------------- extractUuidFromOntapJobDescription

    @Test
    void extractUuid_returnsNull_forNullInputs() {
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription(null, "/snapshots/"));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /x/snapshots/u1", null));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /x/snapshots/u1", ""));
    }

    @Test
    void extractUuid_returnsNull_whenSegmentNotPresent() {
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /x/other/u1", "/snapshots/"));
    }

    @Test
    void extractUuid_returnsNull_whenNothingFollowsSegment() {
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /x/snapshots/   ", "/snapshots/"));
    }

    @Test
    void extractUuid_returnsUuid_afterSegment() {
        assertEquals("u-123",
                OntapStorageUtils.extractUuidFromOntapJobDescription("POST /api/cg/c1/snapshots/u-123", "/snapshots/"));
    }

    @Test
    void extractUuid_stripsQueryStringAndTrailingPath() {
        assertEquals("u-123",
                OntapStorageUtils.extractUuidFromOntapJobDescription("POST /snapshots/u-123?return_timeout=0", "/snapshots/"));
        assertEquals("u-123",
                OntapStorageUtils.extractUuidFromOntapJobDescription("POST /snapshots/u-123/files", "/snapshots/"));
    }

    @Test
    void extractUuid_returnsNull_whenOnlyQueryOrSlashFollowsSegment() {
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /snapshots/?x=1", "/snapshots/"));
        assertNull(OntapStorageUtils.extractUuidFromOntapJobDescription("POST /snapshots//rest", "/snapshots/"));
    }

    // ------------------------------------- isOntapObjectNotFoundError

    @Test
    void isOntapObjectNotFoundError_returnsFalse_forNull() {
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(null));
    }

    @Test
    void isOntapObjectNotFoundError_returnsTrue_forFeignHttp404() {
        FeignException notFound = mock(FeignException.class);
        when(notFound.status()).thenReturn(404);

        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(notFound));
    }

    @Test
    void isOntapObjectNotFoundError_returnsFalse_forFeignServerErrorWithoutMessage() {
        FeignException serverError = mock(FeignException.class);
        when(serverError.status()).thenReturn(500);
        when(serverError.getMessage()).thenReturn(null);

        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(serverError));
    }

    @Test
    void isOntapObjectNotFoundError_returnsTrue_forKnownNotFoundMessages() {
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException("HTTP 404 returned")));
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException("Snapshot Not Found")));
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException("LUN does not exist")));
        assertTrue(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException("entry doesn't exist")));
    }

    @Test
    void isOntapObjectNotFoundError_returnsFalse_forUnrelatedMessage() {
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException("volume is busy")));
    }

    @Test
    void isOntapObjectNotFoundError_returnsFalse_forErrorWithoutMessage() {
        assertFalse(OntapStorageUtils.isOntapObjectNotFoundError(new RuntimeException((String) null)));
    }
}
