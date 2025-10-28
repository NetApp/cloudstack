package org.apache.cloudstack.storage.service.model;

import org.apache.cloudstack.storage.feign.model.ExportPolicy;
import org.apache.cloudstack.storage.feign.model.Igroup;

public class AccessGroup {

    private Igroup igroup;
    private ExportPolicy exportPolicy;

    public Igroup getIgroup() {
        return igroup;
    }

    public void setIgroup(Igroup igroup) {
        this.igroup = igroup;
    }

    public ExportPolicy getPolicy() {
        return exportPolicy;
    }

    public void setPolicy(ExportPolicy policy) {
        this.exportPolicy = policy;
    }
}
