package org.apache.cloudstack.storage.service.model;

import org.apache.cloudstack.storage.feign.model.FileInfo;
import org.apache.cloudstack.storage.feign.model.Lun;

public class CloudStackVolume {

    FileInfo file;
    Lun lun;

    public FileInfo getFile() {
        return file;
    }

    public void setFile(FileInfo file) {
        this.file = file;
    }

    public Lun getLun() {
        return lun;
    }

    public void setLun(Lun lun) {
        this.lun = lun;
    }
}
