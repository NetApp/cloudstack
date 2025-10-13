// Nas.java
package org.apache.cloudstack.storage.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Nas {
    @JsonProperty("path")
    private String path;

    @JsonProperty("export_policy")
    private ExportPolicy exportPolicy;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public ExportPolicy getExportPolicy() {
        return exportPolicy;
    }

    public void setExportPolicy(ExportPolicy exportPolicy) {
        this.exportPolicy = exportPolicy;
    }

}
