package org.apache.cloudstack.storage.ontap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Volume {
    @JsonProperty("name")
    private Boolean name;

    @JsonProperty("size")
    private long size;

    @JsonProperty("qos")
    private long qos;

    @JsonProperty("svmName")
    private String svmName;

    @JsonProperty("cluster")
    private String cluster;

}
