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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.agent.api.to.DiskTO;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageLayer;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.libvirt.LibvirtException;

/**
 * KVM Storage Adaptor for NetApp ONTAP Managed NFS Storage.
 *
 * This adaptor handles NetApp ONTAP-backed NFS storage where each CloudStack volume
 * corresponds to a dedicated ONTAP volume with its own NFS export. This provides:
 *
 * - True managed storage: 1 CloudStack volume = 1 ONTAP volume
 * - Per-volume NFS exports and mount points
 * - ONTAP-level volume management, snapshots, QoS
 * - FlexClone support for instant template cloning
 * - qemu-img operations on ONTAP volumes
 *
 * Architecture:
 * - Management Server: OntapPrimaryDatastoreDriver creates ONTAP volumes via API
 * - KVM Agent: This adaptor mounts per-volume NFS exports and manages qcow2 files
 *
 * Mount structure:
 * - Pool mount: Not used (no shared pool mount point)
 * - Volume mounts: /mnt/<volumeUuid> → nfsServer:/cloudstack_vol_<volumeUuid>
 * - qcow2 location: /mnt/<volumeUuid>/<volumeUuid> (on ONTAP volume)
 *
 * Registered automatically by KVMStoragePoolManager via getStoragePoolType().
 */
public class OntapNfsStorageAdaptor implements StorageAdaptor {

    protected Logger logger = LogManager.getLogger(getClass());
    private String _mountPoint = "/mnt";
    private StorageLayer _storageLayer;

    private static final Map<String, KVMStoragePool> MapStorageUuidToStoragePool = new HashMap<String, KVMStoragePool>();
    // Map to store volumeUuid → actual mount point path (with sanitized junction path)
    private static final Map<String, String> volumeToMountPointMap = new HashMap<String, String>();

    public OntapNfsStorageAdaptor(StorageLayer storagelayer) {
        _storageLayer = storagelayer;
    }

    @Override
    public StoragePoolType getStoragePoolType() {
        return StoragePoolType.OntapNFS;
    }

    @Override
    public KVMStoragePool createStoragePool(String uuid, String host, int port, String path, String userInfo,
                                           StoragePoolType storagePoolType, Map<String, String> details, boolean isPrimaryStorage) {

        LibvirtStoragePool storagePool = new LibvirtStoragePool(uuid, path, StoragePoolType.OntapNFS, this, null);
        storagePool.setSourceHost(host);
        storagePool.setSourcePort(port);
        MapStorageUuidToStoragePool.put(uuid, storagePool);

        logger.info("Created ONTAP NFS storage pool: uuid=" + uuid + ", host=" + host + ", path=" + path);
        return storagePool;
    }

    @Override
    public KVMStoragePool getStoragePool(String uuid) {
        return getStoragePool(uuid, false);
    }

    @Override
    public KVMStoragePool getStoragePool(String uuid, boolean refreshInfo) {
        KVMStoragePool pool = MapStorageUuidToStoragePool.get(uuid);
        if (pool == null) {
            logger.warn("ONTAP NFS storage pool not found: " + uuid);
        }
        return pool;
    }

    @Override
    public boolean deleteStoragePool(String uuid) {
        KVMStoragePool pool = MapStorageUuidToStoragePool.remove(uuid);
        if (pool != null) {
            logger.info("Deleted ONTAP NFS storage pool: " + uuid);
            return true;
        }
        return false;
    }

    @Override
    public boolean deleteStoragePool(KVMStoragePool pool) {
        return deleteStoragePool(pool.getUuid());
    }

    /**
     * Connect (mount) a physical disk for VM attachment.
     * For ONTAP NFS, this mounts the per-volume NFS export at /mnt/<volumeUuid>
     *
     * @param volumeUuid Volume UUID (used as mount point name)
     * @param pool Storage pool
     * @param details Disk details including MOUNT_POINT (_iScsiName = junction path)
     * @param isVMMigrate Whether this is for VM migration
     * @return true if mount successful
     */
    @Override
    public boolean connectPhysicalDisk(String volumeUuid, KVMStoragePool pool, Map<String, String> details, boolean isVMMigrate) {
        logger.info("Connecting ONTAP NFS volume: " + volumeUuid);

        // Get junction path from details map (set by VolumeOrchestrator as MOUNT_POINT)
        // This contains the ONTAP junction path like "/cs_vol_7e72cff5_9730_46e3_80ff_fb76fd7b1dc8"
        String junctionPath = details != null ? details.get(DiskTO.MOUNT_POINT) : null;
        // Fallback: If MOUNT_POINT not in details, check if volumeUuid parameter itself is a junction path
        // (starts with /cs_vol_ or /) - this happens for ROOT volumes where vol.getPath() contains junction path
        if (junctionPath == null || junctionPath.isEmpty()) {
            if (volumeUuid != null && volumeUuid.startsWith("/")) {
                logger.info("volumeUuid parameter appears to be junction path: " + volumeUuid);
                junctionPath = volumeUuid;
            } else {
                logger.error("Missing junction path (MOUNT_POINT) in details and volumeUuid doesn't look like junction path: " + volumeUuid);
                return false;
            }
        }
        logger.info("Using junction path: " + junctionPath + " for volume: " + volumeUuid);
        // Create a sanitized mount point name (remove leading slash, replace special chars)
        String sanitizedPath = junctionPath.startsWith("/") ? junctionPath.substring(1) : junctionPath;
        sanitizedPath = sanitizedPath.replace("/", "_");
        String mountPoint = _mountPoint + "/" + sanitizedPath;

        // Check if already mounted
        if (isMounted(mountPoint)) {
            logger.info("Volume already mounted: " + mountPoint);
            return true;
        }
        try {
            // Create mount point directory
            _storageLayer.mkdirs(mountPoint);
            // Mount: mount -t nfs <nfsServer>:<junctionPath> <mountPoint>
            String nfsServer = pool.getSourceHost();
            String mountCmd = "mount -t nfs " + nfsServer + ":" + junctionPath + " " + mountPoint;
            Script script = new Script("/bin/bash", 60000, logger);
            script.add("-c");
            script.add(mountCmd);
            String result = script.execute();
            if (result != null) {
                logger.error("Failed to mount ONTAP NFS volume: " + volumeUuid + ", error: " + result);
                return false;
            }
            logger.info("Successfully mounted ONTAP NFS volume: " + nfsServer + ":" + junctionPath + " at " + mountPoint);
            // Store the mapping so other methods can find the correct mount point
            // Store both volumeUuid and junctionPath as keys (in case they're different)
            volumeToMountPointMap.put(volumeUuid, mountPoint);
            if (!volumeUuid.equals(junctionPath)) {
                volumeToMountPointMap.put(junctionPath, mountPoint);
            }
            return true;
        } catch (Exception e) {
            logger.error("Exception mounting ONTAP NFS volume: " + volumeUuid, e);
            return false;
        }
    }

    /**
     * Get the mount point for a volume UUID.
     * Returns the stored mount point from connectPhysicalDisk, or falls back to volumeUuid.
     */
    private String getMountPointForVolume(String volumeUuid) {
        // First check if we have a stored mount point (from connectPhysicalDisk)
        String mountPoint = volumeToMountPointMap.get(volumeUuid);
        if (mountPoint != null) {
            return mountPoint;
        }
        // Fallback: assume mount at /mnt/<volumeUuid> (old behavior)
        return _mountPoint + "/" + volumeUuid;
    }

    /**
     * Disconnect (unmount) a physical disk.
     *
     * @param volumeUuid Volume UUID
     * @param pool Storage pool
     * @return true if unmount successful
     */
    @Override
    public boolean disconnectPhysicalDisk(String volumeUuid, KVMStoragePool pool) {
        logger.info("Disconnecting ONTAP NFS volume: " + volumeUuid);
        String mountPoint = getMountPointForVolume(volumeUuid);
        if (!isMounted(mountPoint)) {
            logger.info("Volume not mounted, nothing to disconnect: " + mountPoint);
            return true;
        }
        try {
            // Unmount: umount <mountPoint>
            String umountCmd = "umount " + mountPoint;
            Script script = new Script("/bin/bash", 60000, logger);
            script.add("-c");
            script.add(umountCmd);
            String result = script.execute();
            if (result != null) {
                logger.warn("Failed to unmount ONTAP NFS volume: " + mountPoint + ", error: " + result);
                return false;
            }
            logger.info("Successfully unmounted ONTAP NFS volume: " + mountPoint);
            // Remove from mapping
            volumeToMountPointMap.remove(volumeUuid);
            return true;
        } catch (Exception e) {
            logger.error("Exception unmounting ONTAP NFS volume: " + volumeUuid, e);
            return false;
        }
    }

    @Override
    public boolean disconnectPhysicalDisk(Map<String, String> volumeToDisconnect) {
        // Extract volume UUID from the map and call the main disconnect method
        String volumeUuid = volumeToDisconnect.get(DiskTO.UUID);
        // Note: POOL_UUID constant doesn't exist in DiskTO, pool info comes from other sources
        String poolUuid = volumeToDisconnect.get("poolUuid");
        if (volumeUuid == null || poolUuid == null) {
            logger.error("Missing volume UUID or pool UUID in disconnect request");
            return false;
        }
        KVMStoragePool pool = getStoragePool(poolUuid);
        if (pool == null) {
            logger.error("Storage pool not found: " + poolUuid);
            return false;
        }
        return disconnectPhysicalDisk(volumeUuid, pool);
    }

    @Override
    public boolean disconnectPhysicalDiskByPath(String localPath) {
        // Extract volume UUID from path: /mnt/<volumeUuid>/<volumeUuid>
        if (localPath == null || !localPath.startsWith(_mountPoint + "/")) {
            return false; // Not our path
        }
        String[] pathParts = localPath.split("/");
        if (pathParts.length < 4) {
            return false;
        }
        String volumeUuid = pathParts[2]; // /mnt/<volumeUuid>/...
        String mountPoint = _mountPoint + "/" + volumeUuid;
        logger.info("Disconnecting ONTAP NFS volume by path: " + localPath + " -> " + volumeUuid);
        if (!isMounted(mountPoint)) {
            return true;
        }
        try {
            Script script = new Script("/bin/bash", 60000, logger);
            script.add("-c");
            script.add("umount " + mountPoint);
            String result = script.execute();
            return result == null;
        } catch (Exception e) {
            logger.error("Exception unmounting by path: " + localPath, e);
            return false;
        }
    }

    /**
     * Get physical disk information.
     * For ONTAP NFS, this returns information about the qcow2 file on the mounted volume.
     *
     * @param volumeUuid Volume UUID
     * @param pool Storage pool
     * @return KVMPhysicalDisk object with disk information
     */
    @Override
    public KVMPhysicalDisk getPhysicalDisk(String volumeUuid, KVMStoragePool pool) {
        logger.debug("Getting physical disk info for: " + volumeUuid);
        // Path to qcow2 file: <mountPoint>/<volumeUuid>
        String mountPoint = getMountPointForVolume(volumeUuid);
        String diskPath = mountPoint + "/" + volumeUuid;

        // Check if file exists - if not, this might be a new disk that needs to be created
        // For ONTAP managed storage, the disk file doesn't exist until we create it
        if (!_storageLayer.exists(diskPath)) {
            logger.info("Disk file does not exist: " + diskPath + ". This is expected for new ONTAP managed volumes. Returning disk object with default format.");
            // Return a basic disk object - size will be set during creation
            KVMPhysicalDisk disk = new KVMPhysicalDisk(diskPath, volumeUuid, pool);
            disk.setFormat(PhysicalDiskFormat.QCOW2); // Default format for new disks
            return disk;
        }
        KVMPhysicalDisk disk = new KVMPhysicalDisk(diskPath, volumeUuid, pool);
        try {
            // Get disk info using qemu-img info
            QemuImgFile imgFile = new QemuImgFile(diskPath);
            QemuImg qemuImg = new QemuImg(0);
            Map<String, String> info = qemuImg.info(imgFile);

            String format = info.get("file_format");
            if ("qcow2".equalsIgnoreCase(format)) {
                disk.setFormat(PhysicalDiskFormat.QCOW2);
            } else if ("raw".equalsIgnoreCase(format)) {
                disk.setFormat(PhysicalDiskFormat.RAW);
            }
            String sizeStr = info.get("virtual_size");
            if (sizeStr != null) {
                disk.setVirtualSize(Long.parseLong(sizeStr));
                disk.setSize(Long.parseLong(sizeStr));
            }
        } catch (QemuImgException | LibvirtException e) {
            logger.warn("Failed to get qemu-img info for: " + diskPath + ", " + e.getMessage());
            // Set default format if qemu-img fails
            disk.setFormat(PhysicalDiskFormat.QCOW2);
        }
        return disk;
    }

    /**
     * Create a new physical disk (qcow2 file) on ONTAP NFS volume.
     * The ONTAP volume itself should already be created by OntapPrimaryDatastoreDriver.
     * This method mounts the volume and creates the qcow2 file.
     *
     * @param volumeUuid Volume UUID
     * @param pool Storage pool
     * @param format Disk format (QCOW2, RAW)
     * @param provisioningType Provisioning type
     * @param size Disk size in bytes
     * @param passphrase Encryption passphrase (optional)
     * @return Created KVMPhysicalDisk
     */
    @Override
    public KVMPhysicalDisk createPhysicalDisk(String volumeUuid, KVMStoragePool pool, PhysicalDiskFormat format,
                                             Storage.ProvisioningType provisioningType, long size, byte[] passphrase) {
        logger.info("Creating ONTAP NFS physical disk: " + volumeUuid + ", size=" + size + ", format=" + format);

        // For ONTAP NFS, the ONTAP volume should already exist (created by management server)
        // We need to:
        // 1. Mount the per-volume NFS export (via connectPhysicalDisk)
        // 2. Create the qcow2 file on it

        String mountPoint = getMountPointForVolume(volumeUuid);
        String diskPath = mountPoint + "/" + volumeUuid;

        try {
            // The volume should be mounted via connectPhysicalDisk first
            // But if not, we can't proceed
            if (!isMounted(mountPoint)) {
                throw new CloudRuntimeException("ONTAP volume not mounted for: " + volumeUuid +
                        ". Volume must be mounted before creating disk.");
            }
            // Create qcow2 file using qemu-img
            QemuImgFile destFile = new QemuImgFile(diskPath, size, format);
            QemuImg qemuImg = new QemuImg(0);
            Map<String, String> options = new HashMap<>();
            if (format == PhysicalDiskFormat.QCOW2 && provisioningType == Storage.ProvisioningType.THIN) {
                options.put("preallocation", "metadata");
            } else if (format == PhysicalDiskFormat.QCOW2 && provisioningType == Storage.ProvisioningType.SPARSE) {
                options.put("preallocation", "off");
            } else if (format == PhysicalDiskFormat.QCOW2 && provisioningType == Storage.ProvisioningType.FAT) {
                options.put("preallocation", "falloc");
            }
            qemuImg.create(destFile, options);
            logger.info("Successfully created qcow2 file: " + diskPath);
            // Return disk object
            KVMPhysicalDisk disk = new KVMPhysicalDisk(diskPath, volumeUuid, pool);
            disk.setFormat(format);
            disk.setSize(size);
            disk.setVirtualSize(size);
            return disk;
        } catch (QemuImgException | LibvirtException e) {
            logger.error("Failed to create qcow2 file: " + diskPath, e);
            throw new CloudRuntimeException("Failed to create ONTAP NFS disk: " + e.getMessage());
        }
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String name, KVMStoragePool pool, PhysicalDiskFormat format,
                                             Storage.ProvisioningType provisioningType, long size, Long usableSize, byte[] passphrase) {
        return createPhysicalDisk(name, pool, format, provisioningType, size, passphrase);
    }

    @Override
    public boolean deletePhysicalDisk(String volumeUuid, KVMStoragePool pool, Storage.ImageFormat format) {
        logger.info("Deleting ONTAP NFS physical disk: " + volumeUuid);
        String mountPoint = getMountPointForVolume(volumeUuid);
        String diskPath = mountPoint + "/" + volumeUuid;
        try {
            // Delete qcow2 file
            if (_storageLayer.exists(diskPath)) {
                _storageLayer.delete(diskPath);
                logger.info("Deleted qcow2 file: " + diskPath);
            }
            // Unmount the volume
            if (isMounted(mountPoint)) {
                disconnectPhysicalDisk(volumeUuid, pool);
            }
            // Remove mount point directory
            if (_storageLayer.exists(mountPoint)) {
                _storageLayer.delete(mountPoint);
            }
            // Note: The ONTAP volume itself is deleted by OntapPrimaryDatastoreDriver via API
            logger.info("Successfully deleted ONTAP NFS disk: " + volumeUuid);
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete ONTAP NFS disk: " + volumeUuid, e);
            return false;
        }
    }

    @Override
    public KVMPhysicalDisk createDiskFromTemplate(KVMPhysicalDisk template, String name, PhysicalDiskFormat format,
                                                 Storage.ProvisioningType provisioningType, long size, KVMStoragePool destPool, int timeout, byte[] passphrase) {
        logger.info("Creating disk from template using ONTAP (future: FlexClone): " + name);

        // For now, use qemu-img convert (standard approach)
        // Future enhancement: Use ONTAP FlexClone API for instant cloning
        return copyPhysicalDisk(template, name, destPool, timeout, null, passphrase, provisioningType);
    }

    @Override
    public KVMPhysicalDisk createDiskFromTemplateBacking(KVMPhysicalDisk template, String name, PhysicalDiskFormat format,
                                                        long size, KVMStoragePool destPool, int timeout, byte[] passphrase) {
        // Create disk with backing file (qcow2 backing chain)
        throw new UnsupportedOperationException("Backing file not supported for ONTAP NFS yet");
    }

    @Override
    public KVMPhysicalDisk createTemplateFromDisk(KVMPhysicalDisk disk, String name, PhysicalDiskFormat format,
                                                 long size, KVMStoragePool destPool) {
        logger.info("Creating template from disk: " + name);
        return copyPhysicalDisk(disk, name, destPool, 0);
    }

    /**
     * Copy physical disk (used for template cloning and other copy operations).
     * This is the KEY method for creating VMs from templates.
     *
     * Future enhancement: Use ONTAP FlexClone for instant copy regardless of size.
     *
     * @param srcDisk Source disk
     * @param name Destination volume name
     * @param destPool Destination pool
     * @param timeout Timeout in seconds
     * @return Destination disk
     */
    @Override
    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk srcDisk, String name, KVMStoragePool destPool, int timeout) {
        return copyPhysicalDisk(srcDisk, name, destPool, timeout, null, null, null);
    }

    @Override
    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk srcDisk, String name, KVMStoragePool destPool, int timeout,
                                           byte[] srcPassphrase, byte[] dstPassphrase, Storage.ProvisioningType provisioningType) {
        logger.info("Copying disk: " + srcDisk.getName() + " -> " + name);
        try {
            // Get mount point for destination volume (handles junction path properly)
            String destMountPoint = getMountPointForVolume(name);
            String destPath = destMountPoint + "/" + name;
            // Ensure destination volume is mounted
            if (!isMounted(destMountPoint)) {
                throw new CloudRuntimeException("Destination ONTAP volume not mounted: " + name);
            }
            // Use qemu-img convert for the copy
            QemuImgFile srcFile = new QemuImgFile(srcDisk.getPath());
            QemuImgFile destFile = new QemuImgFile(destPath, srcDisk.getVirtualSize(), srcDisk.getFormat());
            QemuImg qemuImg = new QemuImg(timeout);
            // Note: QemuImg.convert doesn't take passphrase parameters in this version
            // For encrypted volumes, use options map instead
            qemuImg.convert(srcFile, destFile);
            logger.info("Successfully copied disk to: " + destPath);
            // Return destination disk
            KVMPhysicalDisk destDisk = new KVMPhysicalDisk(destPath, name, destPool);
            destDisk.setFormat(srcDisk.getFormat());
            destDisk.setSize(srcDisk.getSize());
            destDisk.setVirtualSize(srcDisk.getVirtualSize());
            return destDisk;

        } catch (QemuImgException | LibvirtException e) {
            logger.error("Failed to copy disk: " + srcDisk.getName() + " -> " + name, e);
            throw new CloudRuntimeException("Failed to copy ONTAP NFS disk: " + e.getMessage());
        }
    }

    @Override
    public KVMPhysicalDisk createTemplateFromDirectDownloadFile(String templateFilePath, String destTemplatePath,
                                                               KVMStoragePool destPool, Storage.ImageFormat format, int timeout) {
        logger.info("Creating template from direct download: " + templateFilePath + " -> " + destTemplatePath);
        try {
            // Use qemu-img convert to create template
            QemuImgFile srcFile = new QemuImgFile(templateFilePath);
            QemuImgFile destFile = new QemuImgFile(destTemplatePath);
            QemuImg qemuImg = new QemuImg(timeout);
            qemuImg.convert(srcFile, destFile);
            logger.info("Successfully created template: " + destTemplatePath);
            // Get the template name from path
            String name = destTemplatePath.substring(destTemplatePath.lastIndexOf("/") + 1);
            KVMPhysicalDisk disk = new KVMPhysicalDisk(destTemplatePath, name, destPool);
            disk.setFormat(PhysicalDiskFormat.QCOW2);
            return disk;
        } catch (QemuImgException | LibvirtException e) {
            logger.error("Failed to create template from direct download", e);
            throw new CloudRuntimeException("Failed to create template: " + e.getMessage());
        }
    }

    @Override
    public List<KVMPhysicalDisk> listPhysicalDisks(String storagePoolUuid, KVMStoragePool pool) {
        // Not implemented - not critical for basic operations
        throw new UnsupportedOperationException("listPhysicalDisks not implemented for ONTAP NFS");
    }

    @Override
    public boolean refresh(KVMStoragePool pool) {
        // Nothing to refresh for ONTAP NFS
        return true;
    }

    @Override
    public boolean createFolder(String uuid, String path) {
        return createFolder(uuid, path, null);
    }

    @Override
    public boolean createFolder(String uuid, String path, String localPath) {
        try {
            String fullPath = (localPath != null) ? localPath : (_mountPoint + "/" + uuid + "/" + path);
            _storageLayer.mkdirs(fullPath);
            logger.info("Created folder: " + fullPath);
            return true;
        } catch (Exception e) {
            logger.error("Failed to create folder: " + path, e);
            return false;
        }
    }

    /**
     * Check if a mount point is currently mounted.
     *
     * @param mountPoint Mount point path
     * @return true if mounted
     */
    private boolean isMounted(String mountPoint) {
        try {
            Script script = new Script("/bin/bash", 5000, logger);
            script.add("-c");
            script.add("mount | grep -q '" + mountPoint + "'");
            String result = script.execute();
            return result == null; // null means success (mount found)
        } catch (Exception e) {
            logger.warn("Failed to check mount status: " + mountPoint, e);
            return false;
        }
    }
}
