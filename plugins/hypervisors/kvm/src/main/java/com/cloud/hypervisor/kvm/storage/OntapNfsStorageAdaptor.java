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

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.DiskTO;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

/**
 * Serves {@link StoragePoolType#OntapNFS} pools: the truly managed NetApp ONTAP NFS primary where
 * ONTAP owns file create / resize / delete via its REST API, and the KVM agent only mounts the
 * share, resolves file paths, and runs qemu-img for template copies. Backing files are RAW; ONTAP
 * provides thin provisioning, snapshots and FlexClone at the volume layer, so there is no need
 * for qcow2's in-file equivalents.
 *
 * <p>The mount is created directly via {@code mount.nfs} rather than through a libvirt NETFS
 * pool. Libvirt references files on the mount with {@code <disk type='file'>}, so no libvirt
 * storage-pool definition is required for VM boot.</p>
 *
 * <p>Package placement is required: {@link KVMStoragePoolManager} discovers adaptors by a
 * Reflections scan of {@code com.cloud.hypervisor.kvm.storage}; an unregistered type silently
 * falls back to {@link LibvirtStorageAdaptor}.</p>
 */
public class OntapNfsStorageAdaptor implements StorageAdaptor {

    protected Logger logger = LogManager.getLogger(getClass());

    /**
     * Mount-root parent, matching {@link LibvirtStorageAdaptor#_mountPoint}. Each pool mounts at
     * {@code /mnt/<pool-uuid>} so admin tooling sees the same layout regardless of pool type.
     */
    private static final String MOUNT_ROOT = "/mnt";

    private static final Map<String, OntapNfsStoragePool> POOLS = new HashMap<>();

    @Override
    public StoragePoolType getStoragePoolType() {
        return StoragePoolType.OntapNFS;
    }

    @Override
    public KVMStoragePool createStoragePool(String uuid, String host, int port, String path, String userInfo,
                                            StoragePoolType storagePoolType, Map<String, String> details,
                                            boolean isPrimaryStorage) {
        String mountPath = MOUNT_ROOT + File.separator + uuid;
        logger.info("createStoragePool: OntapNFS pool [{}], mounting {}:{} at {}", uuid, host, path, mountPath);

        ensureMountDirectory(mountPath);
        if (!isAlreadyMounted(mountPath)) {
            mountNfsShare(host, path, mountPath, mountOptionsFromDetails(details));
        } else {
            logger.debug("createStoragePool: {} is already a mount point, reusing", mountPath);
        }

        OntapNfsStoragePool pool = new OntapNfsStoragePool(uuid, host, port, path, mountPath,
                storagePoolType, this, details);
        POOLS.put(uuid, pool);
        return pool;
    }

    @Override
    public KVMStoragePool getStoragePool(String uuid) {
        return POOLS.get(uuid);
    }

    @Override
    public KVMStoragePool getStoragePool(String uuid, boolean refreshInfo) {
        return POOLS.get(uuid);
    }

    @Override
    public boolean deleteStoragePool(String uuid) {
        OntapNfsStoragePool pool = POOLS.remove(uuid);
        if (pool == null) {
            logger.debug("deleteStoragePool: no OntapNFS pool with uuid {}; nothing to unmount", uuid);
            return true;
        }
        unmountShare(pool.getLocalPath());
        return true;
    }

    @Override
    public boolean deleteStoragePool(KVMStoragePool pool) {
        return deleteStoragePool(pool.getUuid());
    }

    /**
     * Returns a descriptor for the file at {@code <mount>/<name>}. Two call sites arrive here:
     *   1) Blank volume provisioning: the file was already allocated on ONTAP via REST by the
     *      mgmt-server driver; we only hand back a descriptor for the attach path.
     *   2) {@link #copyPhysicalDisk} needs a destination descriptor before invoking qemu-img
     *      convert; qemu-img creates the file on the NFS mount and ONTAP sees a new RAW file.
     *
     * No qemu-img create / libvirt storage-vol-create happens here — doing so would either race
     * ONTAP's allocation (case 1) or produce a zero-size placeholder that convert cannot
     * overwrite (case 2).
     */
    @Override
    public KVMPhysicalDisk createPhysicalDisk(String name, KVMStoragePool pool, PhysicalDiskFormat format,
                                              ProvisioningType provisioningType, long size, byte[] passphrase) {
        String destPath = joinPath(pool.getLocalPath(), name);
        logger.debug("createPhysicalDisk: OntapNFS pool {} returning RAW descriptor for {} ({} bytes)",
                pool.getUuid(), destPath, size);
        KVMPhysicalDisk disk = new KVMPhysicalDisk(destPath, name, pool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        disk.setSize(size);
        disk.setVirtualSize(size);
        return disk;
    }

    @Override
    public KVMPhysicalDisk getPhysicalDisk(String volumeUuid, KVMStoragePool pool) {
        String filePath = joinPath(pool.getLocalPath(), volumeUuid);
        File file = new File(filePath);
        long size = file.exists() ? file.length() : 0L;

        KVMPhysicalDisk disk = new KVMPhysicalDisk(filePath, volumeUuid, pool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        disk.setSize(size);
        disk.setVirtualSize(size);
        return disk;
    }

    @Override
    public boolean connectPhysicalDisk(String volumePath, KVMStoragePool pool, Map<String, String> details,
                                       boolean isVMMigrate) {
        // File is reachable the moment the share is mounted; nothing per-volume to connect.
        return true;
    }

    @Override
    public boolean disconnectPhysicalDisk(String volumePath, KVMStoragePool pool) {
        return true;
    }

    @Override
    public boolean disconnectPhysicalDisk(Map<String, String> volumeToDisconnect) {
        if (volumeToDisconnect == null) {
            return false;
        }
        String path = volumeToDisconnect.get(DiskTO.PATH);
        return path == null || !path.startsWith(MOUNT_ROOT);
    }

    /**
     * The adaptor contract requires each adaptor to claim only the paths it hands out.
     * OntapNFS paths all live under {@link #MOUNT_ROOT}/<pool-uuid>/; anything else is not ours.
     */
    @Override
    public boolean disconnectPhysicalDiskByPath(String localPath) {
        if (localPath == null || !localPath.startsWith(MOUNT_ROOT + File.separator)) {
            return false;
        }
        for (OntapNfsStoragePool pool : POOLS.values()) {
            if (localPath.startsWith(pool.getLocalPath() + File.separator)) {
                // File-level disconnect is a no-op; the file lives on the share until mgmt-server
                // deletes it via the ONTAP REST API.
                return true;
            }
        }
        return false;
    }

    /**
     * Blank volumes are deleted by the mgmt-server driver through ONTAP's file DELETE API; the
     * agent must not touch the file (doing so would race the ONTAP side and leak accounting).
     */
    @Override
    public boolean deletePhysicalDisk(String uuid, KVMStoragePool pool, Storage.ImageFormat format) {
        logger.debug("deletePhysicalDisk: OntapNFS pool {}, delete of {} is owned by the mgmt-server; agent is a no-op",
                pool.getUuid(), uuid);
        return true;
    }

    @Override
    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk disk, String name, KVMStoragePool destPool, int timeout) {
        return copyPhysicalDisk(disk, name, destPool, timeout, null, null, null);
    }

    /**
     * Copies a source disk — typically a secondary-storage template in qcow2 — into a new RAW
     * file on this pool using {@code qemu-img convert}. The destination path lives on the NFS
     * mount, so qemu-img writes through the share and ONTAP records it as a new RAW file. No
     * backing-file chain is produced: templates on an OntapNFS pool are always full RAW copies,
     * and VM root disks are later created by the mgmt-server through ONTAP FlexClone, not here.
     */
    @Override
    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk srcDisk, String destVolumeUuid, KVMStoragePool destPool,
                                            int timeout, byte[] srcPassphrase, byte[] destPassphrase,
                                            ProvisioningType provisioningType) {
        if (destPool.getType() != StoragePoolType.OntapNFS) {
            throw new CloudRuntimeException("OntapNfsStorageAdaptor.copyPhysicalDisk called with non-OntapNFS destination "
                    + destPool.getType());
        }

        String destPath = joinPath(destPool.getLocalPath(), destVolumeUuid);
        logger.info("copyPhysicalDisk: converting [{}] ({}) to [{}] (RAW) on OntapNFS pool {}",
                srcDisk.getPath(), srcDisk.getFormat(), destPath, destPool.getUuid());

        QemuImgFile srcFile = new QemuImgFile(srcDisk.getPath(), srcDisk.getFormat());
        QemuImgFile destFile = new QemuImgFile(destPath, PhysicalDiskFormat.RAW);

        try {
            QemuImg qemu = new QemuImg(timeout);
            qemu.convert(srcFile, destFile);
        } catch (QemuImgException | LibvirtException ex) {
            String msg = "qemu-img convert failed from " + srcDisk.getPath() + " to " + destPath + ": " + ex.getMessage();
            logger.error(msg, ex);
            // Best-effort cleanup of a partial RAW; ONTAP will see the delete via the share.
            new File(destPath).delete();
            throw new CloudRuntimeException(msg, ex);
        }

        KVMPhysicalDisk disk = new KVMPhysicalDisk(destPath, destVolumeUuid, destPool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        File destFileHandle = new File(destPath);
        long size = destFileHandle.exists() ? destFileHandle.length() : 0L;
        disk.setSize(size);
        disk.setVirtualSize(size);
        return disk;
    }

    @Override
    public boolean refresh(KVMStoragePool pool) {
        return true;
    }

    @Override
    public List<KVMPhysicalDisk> listPhysicalDisks(String storagePoolUuid, KVMStoragePool pool) {
        throw new UnsupportedOperationException("Listing physical disks is not supported on OntapNFS; the mgmt-server "
                + "tracks volume files in volume_details");
    }

    /**
     * Agent-side disk-from-template is not used for OntapNFS: the mgmt-server driver clones the
     * cached RAW template via ONTAP's file-clone (FlexClone) API before this method would run.
     */
    @Override
    public KVMPhysicalDisk createDiskFromTemplate(KVMPhysicalDisk template, String name, PhysicalDiskFormat format,
                                                  ProvisioningType provisioningType, long size, KVMStoragePool destPool,
                                                  int timeout, byte[] passphrase) {
        throw new UnsupportedOperationException("createDiskFromTemplate is not supported on OntapNFS; the mgmt-server "
                + "clones from the cached template via ONTAP's file-clone API");
    }

    @Override
    public KVMPhysicalDisk createTemplateFromDisk(KVMPhysicalDisk disk, String name, PhysicalDiskFormat format,
                                                   long size, KVMStoragePool destPool) {
        throw new UnsupportedOperationException("createTemplateFromDisk is not supported on OntapNFS");
    }

    @Override
    public KVMPhysicalDisk createDiskFromTemplateBacking(KVMPhysicalDisk template, String name, PhysicalDiskFormat format,
                                                         long size, KVMStoragePool destPool, int timeout,
                                                         byte[] passphrase) {
        // No backing-file chains on RAW; VM root disks on OntapNFS are full FlexClones from mgmt.
        return null;
    }

    /**
     * Converts a locally-downloaded template file into a RAW file on the OntapNFS mount. Used
     * when the template arrived via direct download instead of secondary storage; the end state
     * is identical to the {@link #copyPhysicalDisk} path.
     */
    @Override
    public KVMPhysicalDisk createTemplateFromDirectDownloadFile(String templateFilePath, String destTemplatePath,
                                                                KVMStoragePool destPool, Storage.ImageFormat format,
                                                                int timeout) {
        if (destPool.getType() != StoragePoolType.OntapNFS) {
            throw new CloudRuntimeException("OntapNfsStorageAdaptor.createTemplateFromDirectDownloadFile called with "
                    + "non-OntapNFS destination " + destPool.getType());
        }
        String destPath = joinPath(destPool.getLocalPath(), destTemplatePath);
        PhysicalDiskFormat srcFormat = (format == Storage.ImageFormat.QCOW2) ? PhysicalDiskFormat.QCOW2
                : PhysicalDiskFormat.RAW;

        logger.info("createTemplateFromDirectDownloadFile: converting [{}] ({}) to [{}] (RAW) on OntapNFS pool {}",
                templateFilePath, srcFormat, destPath, destPool.getUuid());

        QemuImgFile srcFile = new QemuImgFile(templateFilePath, srcFormat);
        QemuImgFile destFile = new QemuImgFile(destPath, PhysicalDiskFormat.RAW);
        try {
            QemuImg qemu = new QemuImg(timeout);
            qemu.convert(srcFile, destFile);
        } catch (QemuImgException | LibvirtException ex) {
            new File(destPath).delete();
            throw new CloudRuntimeException("qemu-img convert of direct-download template failed: " + ex.getMessage(), ex);
        }

        KVMPhysicalDisk disk = new KVMPhysicalDisk(destPath, destTemplatePath, destPool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        File destHandle = new File(destPath);
        long size = destHandle.exists() ? destHandle.length() : 0L;
        disk.setSize(size);
        disk.setVirtualSize(size);
        return disk;
    }

    @Override
    public boolean createFolder(String uuid, String path) {
        return createFolder(uuid, path, null);
    }

    @Override
    public boolean createFolder(String uuid, String path, String localPath) {
        String base = localPath;
        if (base == null) {
            OntapNfsStoragePool pool = POOLS.get(uuid);
            base = pool != null ? pool.getLocalPath() : MOUNT_ROOT + File.separator + uuid;
        }
        File folder = new File(base + File.separator + path);
        if (!folder.exists()) {
            return folder.mkdirs();
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Mount helpers
    // ---------------------------------------------------------------------------------------------

    private void ensureMountDirectory(String mountPath) {
        File dir = new File(mountPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new CloudRuntimeException("Unable to create mount directory " + mountPath);
        }
    }

    /**
     * {@code mountpoint -q} exits 0 when the path is a mount point. Checking before mount avoids
     * "already mounted" errors on agent restart or pool refresh.
     */
    private boolean isAlreadyMounted(String mountPath) {
        Script script = new Script(true, "mountpoint", 0, logger);
        script.add("-q", mountPath);
        return script.execute() == null;
    }

    private void mountNfsShare(String host, String remotePath, String mountPath, String options) {
        Script script = new Script(true, "mount", 0, logger);
        script.add("-t", "nfs");
        if (StringUtils.isNotBlank(options)) {
            script.add("-o", options);
        }
        script.add(host + ":" + remotePath, mountPath);

        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = script.execute(parser);
        if (result != null) {
            throw new CloudRuntimeException("Failed to mount " + host + ":" + remotePath + " at " + mountPath
                    + " — " + result + " / " + parser.getLines());
        }
        logger.info("mountNfsShare: mounted {}:{} at {} with options [{}]", host, remotePath, mountPath, options);
    }

    private void unmountShare(String mountPath) {
        if (mountPath == null) {
            return;
        }
        if (!isAlreadyMounted(mountPath)) {
            logger.debug("unmountShare: {} is not mounted; nothing to do", mountPath);
            return;
        }
        Script script = new Script(true, "umount", 0, logger);
        script.add(mountPath);
        String result = script.execute();
        if (result != null) {
            logger.warn("unmountShare: umount {} returned: {}", mountPath, result);
        } else {
            logger.info("unmountShare: unmounted {}", mountPath);
        }
    }

    /**
     * Reads the comma-separated mount options the mgmt-server set on the pool (vers=3 by default
     * for ONTAP managed NFS). Falls back to no options.
     */
    private String mountOptionsFromDetails(Map<String, String> details) {
        if (details == null) {
            return null;
        }
        String raw = details.get(ApiConstants.NFS_MOUNT_OPTIONS);
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return raw.replaceAll("\\s", "");
    }

    private String joinPath(String base, String name) {
        if (base == null) {
            return name;
        }
        return base.endsWith(File.separator) ? base + name : base + File.separator + name;
    }
}
