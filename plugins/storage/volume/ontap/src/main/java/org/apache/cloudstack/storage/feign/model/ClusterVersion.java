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

import java.util.Objects;

/**
 * This returns the cluster version information.  When the cluster has more than one node, the cluster version is equivalent to the lowest of generation, major, and minor versions on all nodes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClusterVersion {
  @JsonProperty("full")
  private String full = null;

  @JsonProperty("generation")
  private Integer generation = null;

  @JsonProperty("major")
  private Integer major = null;

  @JsonProperty("minor")
  private Integer minor = null;

   /**
   * The full cluster version string.
   * @return full
  **/
  @ApiModelProperty(example = "NetApp Release 9.4.0: Sun Nov 05 18:20:57 UTC 2017", value = "The full cluster version string.")
  public String getFull() {
    return full;
  }

   /**
   * The generation portion of the version.
   * @return generation
  **/
  @ApiModelProperty(example = "9", value = "The generation portion of the version.")
  public Integer getGeneration() {
    return generation;
  }

   /**
   * The major portion of the version.
   * @return major
  **/
  @ApiModelProperty(example = "4", value = "The major portion of the version.")
  public Integer getMajor() {
    return major;
  }

   /**
   * The minor portion of the version.
   * @return minor
  **/
  @ApiModelProperty(example = "0", value = "The minor portion of the version.")
  public Integer getMinor() {
    return minor;
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ClusterVersion clusterVersion = (ClusterVersion) o;
    return Objects.equals(this.full, clusterVersion.full) &&
        Objects.equals(this.generation, clusterVersion.generation) &&
        Objects.equals(this.major, clusterVersion.major) &&
        Objects.equals(this.minor, clusterVersion.minor);
  }

  @Override
  public int hashCode() {
    return Objects.hash(full, generation, major, minor);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ClusterVersion {\n");
    
    sb.append("    full: ").append(toIndentedString(full)).append("\n");
    sb.append("    generation: ").append(toIndentedString(generation)).append("\n");
    sb.append("    major: ").append(toIndentedString(major)).append("\n");
    sb.append("    minor: ").append(toIndentedString(minor)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}

