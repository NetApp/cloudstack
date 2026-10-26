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

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.joda.time.Duration;

import com.cloud.agent.api.to.HostTO;
import com.cloud.hypervisor.kvm.resource.KVMHABase.HAStoragePool;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;

/**
 * KVM-side pool handle for {@link StoragePoolType#OntapNFS}. The share is mounted directly via
 * {@code mount.nfs} by {@link OntapNfsStorageAdaptor}; libvirt is never asked to manage the pool.
 * Backing files are addressed by their mounted path inside domain XML (<disk type='file'>).
 */
public class OntapNfsStoragePool implements KVMStoragePool {
    private final String uuid;
    private final String sourceHost;
    private final int sourcePort;
    private final String remotePath;
    private final String localPath;
    private final StoragePoolType storagePoolType;
    private final StorageAdaptor storageAdaptor;
    private final Map<String, String> details;

    public OntapNfsStoragePool(String uuid, String sourceHost, int sourcePort, String remotePath,
                               String localPath, StoragePoolType storagePoolType,
                               StorageAdaptor storageAdaptor, Map<String, String> details) {
        this.uuid = uuid;
        this.sourceHost = sourceHost;
        this.sourcePort = sourcePort;
        this.remotePath = remotePath;
        this.localPath = localPath;
        this.storagePoolType = storagePoolType;
        this.storageAdaptor = storageAdaptor;
        this.details = details;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public String getSourceHost() {
        return sourceHost;
    }

    @Override
    public int getSourcePort() {
        return sourcePort;
    }

    @Override
    public String getSourceDir() {
        return remotePath;
    }

    @Override
    public String getLocalPath() {
        return localPath;
    }

    @Override
    public long getCapacity() {
        return 0;
    }

    @Override
    public long getUsed() {
        return 0;
    }

    @Override
    public long getAvailable() {
        return 0;
    }

    @Override
    public StoragePoolType getType() {
        return storagePoolType;
    }

    @Override
    public PhysicalDiskFormat getDefaultFormat() {
        return PhysicalDiskFormat.RAW;
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String name, PhysicalDiskFormat format,
                                              Storage.ProvisioningType provisioningType, long size, byte[] passphrase) {
        return storageAdaptor.createPhysicalDisk(name, this, format, provisioningType, size, passphrase);
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String name, Storage.ProvisioningType provisioningType,
                                              long size, byte[] passphrase) {
        return storageAdaptor.createPhysicalDisk(name, this, PhysicalDiskFormat.RAW, provisioningType, size, passphrase);
    }

    @Override
    public boolean connectPhysicalDisk(String volumeUuid, Map<String, String> volumeDetails) {
        return storageAdaptor.connectPhysicalDisk(volumeUuid, this, volumeDetails, false);
    }

    @Override
    public KVMPhysicalDisk getPhysicalDisk(String volumeUuid) {
        return storageAdaptor.getPhysicalDisk(volumeUuid, this);
    }

    @Override
    public boolean disconnectPhysicalDisk(String volumeUuid) {
        return storageAdaptor.disconnectPhysicalDisk(volumeUuid, this);
    }

    @Override
    public boolean deletePhysicalDisk(String volumeUuid, Storage.ImageFormat format) {
        return storageAdaptor.deletePhysicalDisk(volumeUuid, this, format);
    }

    @Override
    public List<KVMPhysicalDisk> listPhysicalDisks() {
        return storageAdaptor.listPhysicalDisks(uuid, this);
    }

    @Override
    public boolean refresh() {
        return storageAdaptor.refresh(this);
    }

    @Override
    public boolean delete() {
        return storageAdaptor.deleteStoragePool(this);
    }

    @Override
    public boolean createFolder(String path) {
        return storageAdaptor.createFolder(uuid, path, localPath);
    }

    @Override
    public boolean isExternalSnapshot() {
        return false;
    }

    @Override
    public String getAuthUserName() {
        return null;
    }

    @Override
    public String getAuthSecret() {
        return null;
    }

    @Override
    public boolean supportsConfigDriveIso() {
        return true;
    }

    @Override
    public Map<String, String> getDetails() {
        return details;
    }

    @Override
    public boolean isPoolSupportHA() {
        return false;
    }

    @Override
    public String getHearthBeatPath() {
        return null;
    }

    @Override
    public String createHeartBeatCommand(HAStoragePool primaryStoragePool, String hostPrivateIp,
                                         boolean hostValidation) {
        return null;
    }

    @Override
    public String getStorageNodeId() {
        return null;
    }

    @Override
    public Boolean hasHeartBeat(HAStoragePool pool, HostTO host) {
        return null;
    }

    @Override
    public Boolean hasVmActivity(HAStoragePool pool, HostTO host, Duration activityScriptTimeout,
                                 String volumeUUIDListString, String vmActivityCheckPath, long duration) {
        return null;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.JSON_STYLE)
                .append("uuid", uuid)
                .append("path", localPath)
                .toString();
    }
}
