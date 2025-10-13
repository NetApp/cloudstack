package org.apache.cloudstack.storage.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VolumeQosPolicy {
    @JsonProperty("max_throughput_iops")
    private Integer maxThroughputIops = null;

    @JsonProperty("max_throughput_mbps")
    private Integer maxThroughputMbps = null;

    @JsonProperty("min_throughput_iops")
    private Integer minThroughputIops = null;

    @JsonProperty("name")
    private String name = null;

    @JsonProperty("uuid")
    private String uuid = null;

    public Integer getMaxThroughputIops() {
        return maxThroughputIops;
    }

    public void setMaxThroughputIops(Integer maxThroughputIops) {
        this.maxThroughputIops = maxThroughputIops;
    }

    public Integer getMaxThroughputMbps() {
        return maxThroughputMbps;
    }

    public void setMaxThroughputMbps(Integer maxThroughputMbps) {
        this.maxThroughputMbps = maxThroughputMbps;
    }

    public Integer getMinThroughputIops() {
        return minThroughputIops;
    }

    public void setMinThroughputIops(Integer minThroughputIops) {
        this.minThroughputIops = minThroughputIops;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}
