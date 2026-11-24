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
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Covers the two testable seams carved out of {@link IscsiStorageCleanupMonitor}:
 *
 *   isIscsiDisk           — the phase-1 scan filter, including the partition-suffix
 *                            exclusion that stops the monitor from passing udev
 *                            {@code -partN} symlinks into {@code iscsiadm --logout}
 *                            (which is session-scoped and would kill the whole LUN).
 *
 *   resolveToKernelDevice — the symlink-chain resolution that lets phase 2 match
 *                            VM XML paths (by-id form, as used by ONTAP) against the
 *                            by-path keys from phase 1 even though their strings
 *                            differ. Both forms must resolve to the same kernel
 *                            block device under /dev for the match to succeed.
 */
public class IscsiStorageCleanupMonitorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // A representative path echoed from the field reproduction on cstack58. The IQN,
    // portal, SVM UUID, and LUN number are preserved verbatim so the test documents
    // the exact shape the monitor sees in production for ONTAP iSCSI.
    private static final String ONTAP_WHOLE_DISK =
            "/dev/disk/by-path/ip-10.196.34.138:3260-iscsi-iqn.1992-08.com.netapp:sn.6dedd379dd0411f18be4005056bd8428:vs.3-lun-0";

    private static final String ONTAP_PART1 = ONTAP_WHOLE_DISK + "-part1";

    // ----------------------------------------------------------------------------
    // isIscsiDisk
    // ----------------------------------------------------------------------------

    @Test
    public void isIscsiDiskAcceptsWholeDiskByPathEntry() {
        assertTrue("whole-disk iSCSI by-path must be accepted",
                IscsiStorageCleanupMonitor.isIscsiDisk(ONTAP_WHOLE_DISK));
    }

    /**
     * The bug fix. Before the partition filter was added, udev's {@code -part1} sibling
     * of a whole-LUN by-path entry was passed to {@code IscsiAdmStorageAdaptor
     * .disconnectPhysicalDiskByPath}. That method parses host/port/IQN/LUN and runs
     * {@code iscsiadm --logout} against the parent target, which is session-scoped
     * and tears down every LUN sharing the session. Rejecting partition entries at
     * scan time prevents the entire class of regression.
     */
    @Test
    public void isIscsiDiskRejectsPartitionByPathEntry() {
        assertFalse("partition by-path (-part1) must be rejected",
                IscsiStorageCleanupMonitor.isIscsiDisk(ONTAP_PART1));
    }

    @Test
    public void isIscsiDiskRejectsMultiDigitPartitionEntry() {
        String part15 = ONTAP_WHOLE_DISK + "-part15";
        assertFalse("multi-digit partition suffix must still match REGEX_PART",
                IscsiStorageCleanupMonitor.isIscsiDisk(part15));
    }

    @Test
    public void isIscsiDiskRejectsByIdPath() {
        // VM XMLs for ONTAP use by-id form. They should never land in diskStatusMap
        // (keyed on by-path scans). The by-id match happens later via kernel-device
        // resolution, not raw path equality.
        assertFalse("by-id path is not a by-path scan entry",
                IscsiStorageCleanupMonitor.isIscsiDisk(
                        "/dev/disk/by-id/wwn-0x600a098078304d35503f61316c425743"));
    }

    @Test
    public void isIscsiDiskRejectsNonIscsiByPathEntry() {
        // A SATA disk also has a by-path entry; neither 'iscsi' nor 'iqn' appears in it.
        assertFalse("non-iscsi by-path (SATA) must be rejected",
                IscsiStorageCleanupMonitor.isIscsiDisk("/dev/disk/by-path/pci-0000:00:1f.2-ata-1"));
    }

    @Test
    public void isIscsiDiskRejectsPathWithoutIqnSubstring() {
        // Hypothetical malformed entry that contains 'iscsi' but no 'iqn'.
        assertFalse("iscsi path without iqn must be rejected",
                IscsiStorageCleanupMonitor.isIscsiDisk(
                        "/dev/disk/by-path/ip-10.196.34.138:3260-iscsi-lun-0"));
    }

    @Test
    public void isIscsiDiskRejectsNull() {
        assertFalse("null must be rejected (defensive)",
                IscsiStorageCleanupMonitor.isIscsiDisk(null));
    }

    @Test
    public void isIscsiDiskRejectsPathOutsideByPathPrefix() {
        // Even with iscsi/iqn substrings present, only /dev/disk/by-path/... is in scope.
        assertFalse("paths outside /dev/disk/by-path must be rejected",
                IscsiStorageCleanupMonitor.isIscsiDisk(
                        "/tmp/by-path/ip-10.196.34.138:3260-iscsi-iqn.test:vol-lun-0"));
    }

    /**
     * Documents the field repro directly: 6 ONTAP LUNs × (1 whole-disk entry + 1 partition
     * entry) = 12 udev symlinks. Only the 6 whole-disk entries should pass the scan filter.
     * The 6 partition entries are what triggered the bug before the fix.
     */
    @Test
    public void scanFilterSelectsOnlyWholeDiskEntriesForOntapRepro() {
        String[] scanned = new String[12];
        for (int lun = 0; lun < 6; lun++) {
            String base = "/dev/disk/by-path/ip-10.196.34.138:3260-iscsi-iqn.1992-08.com.netapp:sn.6dedd379dd0411f18be4005056bd8428:vs.3-lun-" + lun;
            scanned[lun * 2]     = base;
            scanned[lun * 2 + 1] = base + "-part1";
        }

        int accepted = 0;
        int rejected = 0;
        for (String path : scanned) {
            if (IscsiStorageCleanupMonitor.isIscsiDisk(path)) {
                accepted++;
                assertFalse("accepted entries must not look like partitions: " + path,
                        path.matches(IscsiStorageCleanupMonitor.REGEX_PART));
            } else {
                rejected++;
                assertTrue("every rejection in this scan must be a partition entry: " + path,
                        path.matches(IscsiStorageCleanupMonitor.REGEX_PART));
            }
        }
        assertEquals("6 whole-disk entries must be accepted", 6, accepted);
        assertEquals("6 partition entries must be rejected", 6, rejected);
    }

    // ----------------------------------------------------------------------------
    // resolveToKernelDevice
    // ----------------------------------------------------------------------------

    /**
     * Happy path: a symlink whose target lives under /dev resolves to that target's
     * absolute path. /dev/null is used because it exists on every Linux host and under
     * macOS/BSD too, so the test is portable without a Linux-only setup.
     */
    @Test
    public void resolveToKernelDeviceFollowsSymlinkInsideDev() throws IOException {
        Path link = tempFolder.newFolder().toPath().resolve("fake-by-id");
        Files.createSymbolicLink(link, Paths.get("/dev/null"));

        assertEquals("/dev/null", IscsiStorageCleanupMonitor.resolveToKernelDevice(link.toString()));
    }

    /**
     * A dangling symlink is common during cleanup races (session logged out but udev
     * hasn't removed the by-path symlink yet). toRealPath throws NoSuchFileException;
     * the helper must swallow it and return null so the monitor doesn't mistake the
     * stale entry for an active device.
     */
    @Test
    public void resolveToKernelDeviceReturnsNullForDanglingSymlink() throws IOException {
        File target = tempFolder.newFile("target");
        Path link = tempFolder.getRoot().toPath().resolve("dangling-link");
        Files.createSymbolicLink(link, target.toPath());
        assertTrue("target removal must succeed to make the link dangle", target.delete());

        assertNull("dangling symlink must resolve to null, not throw",
                IscsiStorageCleanupMonitor.resolveToKernelDevice(link.toString()));
    }

    /**
     * File-backed VM disk: VM XML points at a qcow2 under /var/lib/libvirt/images.
     * toRealPath succeeds but the real path is not under /dev — the helper must
     * reject so this disk never collides with a by-path entry's kernel device.
     */
    @Test
    public void resolveToKernelDeviceReturnsNullWhenRealPathNotInDev() throws IOException {
        File backing = tempFolder.newFile("vm.qcow2");

        assertNull("non-/dev real path must not be considered a kernel device",
                IscsiStorageCleanupMonitor.resolveToKernelDevice(backing.getAbsolutePath()));
    }

    @Test
    public void resolveToKernelDeviceReturnsNullForNullInput() {
        assertNull(IscsiStorageCleanupMonitor.resolveToKernelDevice(null));
    }

    @Test
    public void resolveToKernelDeviceReturnsNullForNonExistentPath() {
        String absent = tempFolder.getRoot().toPath().resolve("does-not-exist").toString();

        assertNull("non-existent path must return null, not throw",
                IscsiStorageCleanupMonitor.resolveToKernelDevice(absent));
    }

    /**
     * Confirms the invariant that makes the fix's phase-2 index work: two different
     * symlinks pointing at the same kernel device resolve to the same string. This is
     * the whole premise behind matching by-path keys to by-id VM disk paths — they
     * meet at /dev/sdX (or in this test, /dev/null) even though their strings differ.
     */
    @Test
    public void resolveToKernelDeviceConvergesForTwoSymlinksToSameTarget() throws IOException {
        Path byIdLike  = tempFolder.newFolder("by-id-dir").toPath().resolve("wwn-0xDEADBEEF");
        Path byPathLike = tempFolder.newFolder("by-path-dir").toPath().resolve("ip-...-iscsi-...-lun-0");
        Files.createSymbolicLink(byIdLike,  Paths.get("/dev/null"));
        Files.createSymbolicLink(byPathLike, Paths.get("/dev/null"));

        String resolvedById   = IscsiStorageCleanupMonitor.resolveToKernelDevice(byIdLike.toString());
        String resolvedByPath = IscsiStorageCleanupMonitor.resolveToKernelDevice(byPathLike.toString());

        assertEquals("by-id form and by-path form must converge at the kernel device",
                resolvedById, resolvedByPath);
        assertEquals("/dev/null", resolvedById);
    }
}
