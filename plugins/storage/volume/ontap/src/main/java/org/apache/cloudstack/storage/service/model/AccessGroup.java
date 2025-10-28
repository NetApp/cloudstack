package org.apache.cloudstack.storage.service.model;

import org.apache.cloudstack.storage.feign.model.Igroup;
import org.apache.cloudstack.storage.feign.model.Policy;

public class AccessGroup {

    Igroup igroup;
    Policy policy;

    public Igroup getIgroup() {
        return igroup;
    }

    public void setIgroup(Igroup igroup) {
        this.igroup = igroup;
    }

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy;
    }
}
