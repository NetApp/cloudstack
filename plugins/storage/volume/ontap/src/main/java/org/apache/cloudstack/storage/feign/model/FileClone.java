package org.apache.cloudstack.storage.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileClone {
    @JsonProperty("source_path")
    private String sourcePath;
    @JsonProperty("destination_path")
    private String destinationPath;
    @JsonProperty("volume")
    private VolumeConcise volume;
    public VolumeConcise getVolume() {
        return volume;
    }
    public void setVolume(VolumeConcise volume) {
        this.volume = volume;
    }
    public String getSourcePath() {
        return sourcePath;
    }
    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }
    public String getDestinationPath() {
        return destinationPath;
    }
    public void setDestinationPath(String destinationPath) {}
}
