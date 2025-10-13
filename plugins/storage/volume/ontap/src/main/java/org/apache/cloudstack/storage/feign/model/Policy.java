
package org.apache.cloudstack.storage.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Policy {
    private int minThroughputIops;
    private int minThroughputMbps;
    private int maxThroughputIops;
    private int maxThroughputMbps;
    private String uuid;
    private String name;
    public int getMinThroughputIops() { return minThroughputIops; }
    public void setMinThroughputIops(int minThroughputIops) { this.minThroughputIops = minThroughputIops; }
    public int getMinThroughputMbps() { return minThroughputMbps; }
    public void setMinThroughputMbps(int minThroughputMbps) { this.minThroughputMbps = minThroughputMbps; }
    public int getMaxThroughputIops() { return maxThroughputIops; }
    public void setMaxThroughputIops(int maxThroughputIops) { this.maxThroughputIops = maxThroughputIops; }
    public int getMaxThroughputMbps() { return maxThroughputMbps; }
    public void setMaxThroughputMbps(int maxThroughputMbps) { this.maxThroughputMbps = maxThroughputMbps; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Policy policy = (Policy) o;
        return Objects.equals(getUuid(), policy.getUuid());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getUuid());
    }
}
