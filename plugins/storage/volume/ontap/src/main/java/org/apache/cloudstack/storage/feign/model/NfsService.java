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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents ONTAP REST {@code /api/protocols/nfs/services/{svm.uuid}}: the per-SVM
 * NFS service document. Carries v4.x enablement and ID-mapping configuration that
 * is NOT addressable on the {@code /api/svm/svms} endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NfsService {

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("protocol")
    private Protocol protocol;

    public Boolean getEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public boolean isV41Enabled() {
        return protocol != null && Boolean.TRUE.equals(protocol.v41Enabled);
    }

    public boolean isV4NumericIds() {
        return protocol != null && Boolean.TRUE.equals(protocol.v4NumericIds);
    }

    public String getV4IdDomain() {
        return protocol != null ? protocol.v4IdDomain : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Protocol {

        @JsonProperty("v3_enabled")
        private Boolean v3Enabled;

        @JsonProperty("v40_enabled")
        private Boolean v40Enabled;

        @JsonProperty("v41_enabled")
        private Boolean v41Enabled;

        @JsonProperty("v41_pnfs_enabled")
        private Boolean v41PnfsEnabled;

        @JsonProperty("v4_id_domain")
        private String v4IdDomain;

        @JsonProperty("v4_numeric_ids")
        private Boolean v4NumericIds;

        public Boolean getV3Enabled() { return v3Enabled; }
        public void setV3Enabled(Boolean v3Enabled) { this.v3Enabled = v3Enabled; }

        public Boolean getV40Enabled() { return v40Enabled; }
        public void setV40Enabled(Boolean v40Enabled) { this.v40Enabled = v40Enabled; }

        public Boolean getV41Enabled() { return v41Enabled; }
        public void setV41Enabled(Boolean v41Enabled) { this.v41Enabled = v41Enabled; }

        public Boolean getV41PnfsEnabled() { return v41PnfsEnabled; }
        public void setV41PnfsEnabled(Boolean v41PnfsEnabled) { this.v41PnfsEnabled = v41PnfsEnabled; }

        public String getV4IdDomain() { return v4IdDomain; }
        public void setV4IdDomain(String v4IdDomain) { this.v4IdDomain = v4IdDomain; }

        public Boolean getV4NumericIds() { return v4NumericIds; }
        public void setV4NumericIds(Boolean v4NumericIds) { this.v4NumericIds = v4NumericIds; }
    }
}
