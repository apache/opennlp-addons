/*
 * Copyright 2013 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.addons.geoentitylinker;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import opennlp.tools.entitylinker.BaseLink;

/**
 *
 * Stores a minimal amount of information from a geographic placenames gazateer
 */
public class GazetteerEntry extends BaseLink {

  private Double latitude;
  private Double longitude;
  private String source;
  private String indexID;
  private Map<String, String> indexData = new HashMap<>();
  private String countryCode;
  private String provinceCode;
  private String hierarchy;

  /**
   * returns the id from the lucene document
   *
   * @return
   */
  public String getIndexID() {
    return indexID;
  }
  /*
   * sets the id from the lucene document
   */

  public void setIndexID(String indexID) {
    this.indexID = indexID;
  }

  /**
   * returns the latitude from the gazateer
   *
   * @return
   */
  public Double getLatitude() {
    return latitude;
  }

  /**
   * sets the latitude from the gazateer
   *
   */
  public void setLatitude(Double latitude) {
    this.latitude = latitude;
  }

  /**
   * returns the longitude from the gaz
   *
   * @return
   */
  public Double getLongitude() {
    return longitude;
  }

  /**
   * sets the longitude from the gaz
   *
   * @param longitude
   */
  public void setLongitude(Double longitude) {
    this.longitude = longitude;
  }

  /**
   * returns the source of the gazateer data
   *
   * @return
   */
  public String getSource() {
    return source;
  }

  /**
   * sets the source (the source of the gazateer data)
   *
   * @param source
   */
  public void setSource(String source) {
    this.source = source;
  }

  /**
   * Returns all the other fields in the gazateer in the form of a map
   *
   * @return
   */
  public Map<String, String> getIndexData() {
    return indexData;
  }

  /**
   * sets the other fields in the gazeteer in the form of a map
   *
   * @param indexData stores all fields in the index as fieldname:value
   */
  public void setIndexData(Map<String, String> indexData) {
    this.indexData = indexData;
  }

  @Override
  public String toString() {

    return super.toString() + "\n\t\tGazateerEntry\n" + "\t\tlatitude=" + latitude + ", \n\t\tlongitude=" + longitude + ", \n\t\tsource=" + source + ", \n\t\tindexID=" + indexID + ",\n\t\tindexData=" + indexData + "\n";
  }

  @Override
  public int hashCode() {
    int hash = 7;
    hash = 29 * hash + Objects.hashCode(this.latitude);
    hash = 29 * hash + Objects.hashCode(this.longitude);
    hash = 29 * hash + Objects.hashCode(this.source);
    hash = 29 * hash + Objects.hashCode(this.indexID);
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final GazetteerEntry other = (GazetteerEntry) obj;
    if (!Objects.equals(this.latitude, other.latitude)) {
      return false;
    }
    if (!Objects.equals(this.longitude, other.longitude)) {
      return false;
    }
    if (!Objects.equals(this.source, other.source)) {
      return false;
    }
    if (!Objects.equals(this.indexID, other.indexID)) {
      return false;
    }
    return true;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode;
  }

  public String getProvinceCode() {
    return provinceCode;
  }

  public void setProvinceCode(String provinceCode) {
    this.provinceCode = provinceCode;
  }

  public String getHierarchy() {
    return hierarchy;
  }

  public void setHierarchy(String hierarchy) {
    this.hierarchy = hierarchy;
  }

}
