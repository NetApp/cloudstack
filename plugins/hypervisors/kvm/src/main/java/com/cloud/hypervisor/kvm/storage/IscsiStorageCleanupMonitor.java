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

import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtDomainXMLParser;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IscsiStorageCleanupMonitor implements Runnable{
    protected static final Logger LOGGER = LogManager.getLogger(IscsiStorageCleanupMonitor.class);
    protected Logger logger = LOGGER;
    private static final int CLEANUP_INTERVAL_SEC = 60; // check every X seconds
    static final String ISCSI_PATH_PREFIX = "/dev/disk/by-path";
    static final String KEYWORD_ISCSI = "iscsi";
    static final String KEYWORD_IQN = "iqn";
    static final String REGEX_PART = "\\S+part\\d+$";
    static final String DEV_PREFIX = "/dev";
    static final String LUN_MARKER = "-lun-";

    private IscsiAdmStorageAdaptor iscsiStorageAdaptor;

    private Map<String, Boolean> diskStatusMap;

    public IscsiStorageCleanupMonitor() {
        diskStatusMap = new HashMap<>();
        logger.debug("Initialize cleanup thread");
        iscsiStorageAdaptor = new IscsiAdmStorageAdaptor();
    }

    /**
     * True if {@code path} is an iSCSI by-path symlink for a whole LUN (not a partition entry).
     * udev creates a sibling {@code -partN} by-path entry for every partition on a LUN; because
     * {@code iscsiadm --logout} is session-scoped, acting on a partition entry would tear down
     * the whole LUN's session. The monitor must operate only on whole-disk entries.
     *
     * Visible for testing.
     */
    static boolean isIscsiDisk(String path) {
        return path != null
                && path.startsWith(ISCSI_PATH_PREFIX)
                && path.contains(KEYWORD_ISCSI)
                && path.contains(KEYWORD_IQN)
                && !path.matches(REGEX_PART);
    }

    /**
     * Resolves a {@code /dev/disk/by-*} symlink (or any path under {@code /dev}) to its
     * underlying kernel device absolute path (e.g. {@code /dev/sdb}). Returns {@code null}
     * when the input is null, the symlink is dangling, the file is missing, or the real
     * path is not under {@code /dev} (which filters out file-backed VM disks).
     *
     * Visible for testing.
     */
    static String resolveToKernelDevice(String path) {
        if (path == null) {
            return null;
        }
        try {
            Path real = Paths.get(path).toRealPath();
            if (real.startsWith(DEV_PREFIX)) {
                return real.toString();
            }
        } catch (IOException e) {
            LOGGER.debug("Could not resolve [{}] to a kernel device: {}", path, e.getMessage());
        }
        return null;
    }

    /**
     * Returns the target identifier shared by all LUNs of a by-path iSCSI entry — everything up
     * to the {@code -lun-} suffix (portal + target IQN). Returns {@code null} when {@code path}
     * is not an iSCSI by-path for a specific LUN.
     *
     * <p>{@code iscsiadm --logout} is session-scoped (keyed on target IQN + portal), so logging
     * out any one by-path tears down the session for every LUN on the same target. On shared-
     * target backends (e.g. ONTAP SVMs, where many LUNs map to one IQN) the monitor must
     * preserve a session whenever another LUN on the same target is still in use by a VM.
     *
     * <p>Visible for testing.
     */
    static String extractTargetKey(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.lastIndexOf(LUN_MARKER);
        if (idx < 0) {
            return null;
        }
        return path.substring(0, idx);
    }


    private class Monitor extends ManagedContextRunnable {

        @Override
        protected void runInContext() {
            Connect conn = null;
            try {
                conn = LibvirtConnection.getConnection();

                //populate all the iscsi disks currently attached to this host
                File[] iscsiVolumes = new File(ISCSI_PATH_PREFIX).listFiles();
                if (iscsiVolumes == null || iscsiVolumes.length == 0) {
                    logger.debug("No iscsi sessions found for cleanup");
                    return;
                }

                // set all status values to false
                initializeDiskStatusMap(iscsiVolumes);

                // check if iscsi sessions belong to any VM
                updateDiskStatusMapWithInactiveIscsiSessions(conn);

                // disconnect stale iscsi sessions
                disconnectInactiveSessions();

            } catch (LibvirtException e) {
                logger.warn("[ignored] Error trying to cleanup ", e);
            }
        }

        /**
         * for each volume if the volume is path is of type iscsi, add to diskstatusmap and set status to false.
         * @param iscsiVolumes
         */
        private void initializeDiskStatusMap(File[] iscsiVolumes){
            diskStatusMap.clear();
            for( File v : iscsiVolumes) {
                if (IscsiStorageCleanupMonitor.isIscsiDisk(v.getAbsolutePath())) {
                    logger.debug("found iscsi disk by cleanup thread, marking inactive: " + v.getAbsolutePath());
                    diskStatusMap.put(v.getAbsolutePath(), false);
                }
            }
        }

        /** Loop over list of VMs or domains, get disks, if disk is in diskStatusMap, set status value to true.
         *
         * @param conn
         */
        private void updateDiskStatusMapWithInactiveIscsiSessions(Connect conn){
            // VM XML and diskStatusMap keys can reference the same iSCSI LUN through
            // different udev symlinks: by-path (ip-...-iscsi-...-lun-N) vs by-id
            // (wwn-0x... for storage providers that identify LUNs by NAA WWID, such as
            // ONTAP). A raw string compare misses that match and the session gets
            // disconnected while VMs are still using it. Resolve both sides to the
            // underlying kernel block device (e.g. /dev/sdb) so the match works for
            // any symlink form.
            Map<String, String> kernelDeviceToByPath = new HashMap<>();
            for (String byPath : diskStatusMap.keySet()) {
                String kernelDevice = IscsiStorageCleanupMonitor.resolveToKernelDevice(byPath);
                if (kernelDevice != null) {
                    kernelDeviceToByPath.put(kernelDevice, byPath);
                }
            }

            try {
                int[] domains = conn.listDomains();
                logger.debug(String.format("found %d domains", domains.length));
                for (int domId : domains) {
                    Domain dm = conn.domainLookupByID(domId);
                    final String domXml = dm.getXMLDesc(0);
                    final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
                    parser.parseDomainXML(domXml);
                    List<LibvirtVMDef.DiskDef> disks = parser.getDisks();

                    //check the volume map. If an entry exists change the status to True
                    for (final LibvirtVMDef.DiskDef disk : disks) {
                        final String diskPath = disk.getDiskPath();
                        if (diskPath == null || diskPath.matches(REGEX_PART)) {
                            continue;
                        }
                        String kernelDevice = IscsiStorageCleanupMonitor.resolveToKernelDevice(diskPath);
                        if (kernelDevice == null) {
                            continue;
                        }
                        String byPath = kernelDeviceToByPath.get(kernelDevice);
                        if (byPath != null) {
                            diskStatusMap.put(byPath, true);
                            logger.debug("active disk found by cleanup thread " + diskPath + " -> " + byPath);
                        }
                    }
                }
            } catch (LibvirtException e) {
                logger.warn("[ignored] Error trying to cleanup ", e);
            }

        }

        /**
         * When the state is false, the iscsi sessions are stale. They may be
         * removed. We go through each volume which is false, check iscsiadm,
         * if the volume still exisits, logout of that volume and remove it from the map

         * XXX: It is possible that someone had manually added an iSCSI volume.
         * we would not be able to detect that
         *
         * {@code iscsiadm --logout} is target-scoped. On shared-target backends (e.g. ONTAP SVMs
         * that expose many LUNs under one IQN) logging out one inactive LUN would also kill the
         * session for sibling LUNs still in use by other VMs on this host. Before disconnecting
         * an inactive by-path entry, confirm no active LUN remains on the same target.
         */
        private void disconnectInactiveSessions(){
            Set<String> activeTargets = new HashSet<>();
            for (Map.Entry<String, Boolean> e : diskStatusMap.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    String targetKey = IscsiStorageCleanupMonitor.extractTargetKey(e.getKey());
                    if (targetKey != null) {
                        activeTargets.add(targetKey);
                    }
                }
            }

            for (String diskPath : diskStatusMap.keySet()) {
                if (!diskStatusMap.get(diskPath)) {
                    if (Files.exists(Paths.get(diskPath))) {
                        String targetKey = IscsiStorageCleanupMonitor.extractTargetKey(diskPath);
                        if (targetKey != null && activeTargets.contains(targetKey)) {
                            logger.debug("Preserving iSCSI session for inactive LUN [{}]: " +
                                    "sibling LUN on target [{}] is still in use by a VM on this host",
                                    diskPath, targetKey);
                            continue;
                        }
                        try {
                            logger.info("Cleaning up disk " + diskPath);
                            iscsiStorageAdaptor.disconnectPhysicalDiskByPath(diskPath);
                        } catch (Exception e) {
                            logger.warn("[ignored] Error cleaning up " + diskPath, e);
                        }
                    }
                }
            }

        }
    }

    @Override
    public void run() {
        while(true) {
            try {
                Thread.sleep(CLEANUP_INTERVAL_SEC * 1000);
            } catch (InterruptedException e) {
                logger.debug("[ignored] interrupted between heartbeats.");
            }

            Thread monitorThread = new Thread(new Monitor());
            monitorThread.start();
            try {
                monitorThread.join();
            } catch (InterruptedException e) {
                logger.debug("[ignored] interrupted joining monitor.");
            }
        }
    }
}
