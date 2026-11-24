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
import java.util.List;
import java.util.Map;

public class IscsiStorageCleanupMonitor implements Runnable{
    protected Logger logger = LogManager.getLogger(getClass());
    private static final int CLEANUP_INTERVAL_SEC = 60; // check every X seconds
    private static final String ISCSI_PATH_PREFIX = "/dev/disk/by-path";
    private static final String KEYWORD_ISCSI = "iscsi";
    private static final String KEYWORD_IQN = "iqn";
    private static final String REGEX_PART = "\\S+part\\d+$";

    private IscsiAdmStorageAdaptor iscsiStorageAdaptor;

    private Map<String, Boolean> diskStatusMap;

    public IscsiStorageCleanupMonitor() {
        diskStatusMap = new HashMap<>();
        logger.debug("Initialize cleanup thread");
        iscsiStorageAdaptor = new IscsiAdmStorageAdaptor();
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

        private boolean isIscsiDisk(String path) {
            // Exclude partition symlinks (-partN). udev creates a by-path entry per
            // partition when the LUN has a partition table. iscsiadm --logout is
            // session-scoped, so cleaning up a partition entry would tear down the
            // whole LUN's session. The whole-disk by-path entry (no -partN suffix)
            // is what the monitor should operate on.
            return path.startsWith(ISCSI_PATH_PREFIX)
                    && path.contains(KEYWORD_ISCSI)
                    && path.contains(KEYWORD_IQN)
                    && !path.matches(REGEX_PART);
        }

        /**
         * for each volume if the volume is path is of type iscsi, add to diskstatusmap and set status to false.
         * @param iscsiVolumes
         */
        private void initializeDiskStatusMap(File[] iscsiVolumes){
            diskStatusMap.clear();
            for( File v : iscsiVolumes) {
                if (isIscsiDisk(v.getAbsolutePath())) {
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
                String kernelDevice = resolveToKernelDevice(byPath);
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
                        String kernelDevice = resolveToKernelDevice(diskPath);
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
         * Resolves a /dev/disk/by-* symlink (or any path under /dev) to its underlying
         * kernel device absolute path (e.g. /dev/sdb). Returns null when the symlink
         * is dangling, the file is missing, or the real path is not under /dev.
         */
        private String resolveToKernelDevice(String path) {
            try {
                Path real = Paths.get(path).toRealPath();
                if (real.startsWith("/dev")) {
                    return real.toString();
                }
            } catch (IOException e) {
                logger.debug("Could not resolve [{}] to a kernel device: {}", path, e.getMessage());
            }
            return null;
        }

        /**
         * When the state is false, the iscsi sessions are stale. They may be
         * removed. We go through each volume which is false, check iscsiadm,
         * if the volume still exisits, logout of that volume and remove it from the map

         * XXX: It is possible that someone had manually added an iSCSI volume.
         * we would not be able to detect that
         */
        private void disconnectInactiveSessions(){

            for (String diskPath : diskStatusMap.keySet()) {
                if (!diskStatusMap.get(diskPath)) {
                    if (Files.exists(Paths.get(diskPath))) {
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
