/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cloudstack.storage.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Svm {
    @JsonProperty("uuid")
    private String uuid = null;

    @JsonProperty("name")
    private String name = null;

    @JsonProperty("iscsi")
    private ProtocolStatus iscsi = null;

    @JsonProperty("fcp")
    private ProtocolStatus fcp = null;

    @JsonProperty("nfs")
    private ProtocolStatus nfs = null;

    @JsonProperty("aggregates")
    private List<Aggregate> aggregates = null;

    @JsonProperty("aggregates_delegated")
    private Boolean aggregatesDelegated = null;

    @JsonProperty("state")
    private String state = null;

    @JsonIgnore
    private Links links = null;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getNfsEnabled() {
        return nfs == null ? false : nfs.getEnabled();
    }

    public Boolean getIscsiEnabled() {
        return iscsi == null ? false : iscsi.getEnabled();
    }

    public Boolean getFcpEnabled() {
        return fcp == null ? false : fcp.getEnabled();
    }

    public List<Aggregate> getAggregates() {
        return aggregates;
    }

    public void setAggregates(List<Aggregate> aggregates) {
        this.aggregates = aggregates;
    }

    public Boolean getAggregatesDelegated() {
        return aggregatesDelegated;
    }

    public void setAggregatesDelegated(Boolean aggregatesDelegated) {
        this.aggregatesDelegated = aggregatesDelegated;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Links getLinks() {
        return links;
    }

    public void setLinks(Links links) {
        this.links = links;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Svm svm = (Svm) o;
        return Objects.equals(getUuid(), svm.getUuid());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getUuid());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProtocolStatus {
        @JsonProperty("enabled")
        private Boolean enabled;
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Links { }
}
