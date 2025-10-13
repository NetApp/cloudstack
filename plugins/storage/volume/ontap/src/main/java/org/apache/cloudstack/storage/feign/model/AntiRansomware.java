// AntiRansomware.java
package org.apache.cloudstack.storage.feign.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AntiRansomware {
    @JsonProperty("state")
    private String state;

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}

