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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import io.swagger.annotations.ApiModelProperty;

/**
 * Complete cluster information
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Cluster {
	@JsonProperty("name")
	private String name = null;
	@JsonProperty("uuid")
	private String uuid = null;

	@JsonProperty("version")
	private ClusterVersion version = null;
	@JsonProperty("health")
	private String health = null;

	@JsonProperty("san_optimized")
	private Boolean sanOptimized = null;

	@JsonProperty("disaggregated")
	private Boolean disaggregated = null;

	@ApiModelProperty(example = "healthy", value = "")
	public String getHealth() {
		return health;
	}

	public void setHealth(String health) {
		this.health = health;
	}

	public Cluster name(String name) {
		this.name = name;
		return this;
	}

	/**
	 * Get name
	 * 
	 * @return name
	 **/
	@ApiModelProperty(example = "cluster1", value = "")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Get uuid
	 * 
	 * @return uuid
	 **/
	@ApiModelProperty(example = "1cd8a442-86d1-11e0-ae1c-123478563412", value = "")
	public String getUuid() {
		return uuid;
	}

	public Cluster version(ClusterVersion version) {
		this.version = version;
		return this;
	}

	/**
	 * Get version
	 * 
	 * @return version
	 **/
	@ApiModelProperty(value = "")
	public ClusterVersion getVersion() {
		return version;
	}

	public void setVersion(ClusterVersion version) {
		this.version = version;
	}
	@ApiModelProperty(value = "")
	public Boolean getSanOptimized() {
		return sanOptimized;
	}

	public void setSanOptimized(Boolean sanOptimized) {
		this.sanOptimized = sanOptimized;
	}
	@ApiModelProperty(value = "")
	public Boolean getDisaggregated() {
		return disaggregated;
	}
	public void setDisaggregated(Boolean disaggregated) {
		this.disaggregated = disaggregated;
	}
	@Override
	public String toString() {
		return "Cluster{" +
				"name='" + name + '\'' +
				", uuid='" + uuid + '\'' +
				", version=" + version +
				", sanOptimized=" + sanOptimized +
				", disaggregated=" + disaggregated +
				'}';
	}
}
