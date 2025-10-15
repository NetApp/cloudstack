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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * AggregateResponse
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AggregateResponse {
  @JsonProperty("error")
  private Error error = null;
  @JsonProperty("num_records")
  private Integer numRecords = null;
  @JsonProperty("records")
  private List<Aggregate> records = null;

  public AggregateResponse error(Error error) {
    this.error = error;
    return this;
  }

   /**
   * Get error
   * @return error
  **/
  @ApiModelProperty(value = "")
  public Error getError() {
    return error;
  }

  public void setError(Error error) {
    this.error = error;
  }

  public AggregateResponse numRecords(Integer numRecords) {
    this.numRecords = numRecords;
    return this;
  }

   /**
   * Number of records
   * @return numRecords
  **/
  @ApiModelProperty(value = "Number of records")
  public Integer getNumRecords() {
    return numRecords;
  }

  public void setNumRecords(Integer numRecords) {
    this.numRecords = numRecords;
  }

  public AggregateResponse records(List<Aggregate> records) {
    this.records = records;
    return this;
  }

  public AggregateResponse addRecordsItem(Aggregate recordsItem) {
    if (this.records == null) {
      this.records = new ArrayList<Aggregate>();
    }
    this.records.add(recordsItem);
    return this;
  }

   /**
   * Get records
   * @return records
  **/
  @ApiModelProperty(value = "")
  public List<Aggregate> getRecords() {
    return records;
  }

  public void setRecords(List<Aggregate> records) {
    this.records = records;
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AggregateResponse aggregateResponse = (AggregateResponse) o;
    return 
        Objects.equals(this.error, aggregateResponse.error) &&
        Objects.equals(this.numRecords, aggregateResponse.numRecords) &&
        Objects.equals(this.records, aggregateResponse.records);
  }

  @Override
  public int hashCode() {
    return Objects.hash(error, numRecords, records);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AggregateResponse {\n");
    
    sb.append("    error: ").append(toIndentedString(error)).append("\n");
    sb.append("    numRecords: ").append(toIndentedString(numRecords)).append("\n");
    sb.append("    records: ").append(toIndentedString(records)).append("\n");
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

