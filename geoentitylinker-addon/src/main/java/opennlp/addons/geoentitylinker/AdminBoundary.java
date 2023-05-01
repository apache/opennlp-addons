/*
 * Copyright 2014 The Apache Software Foundation.
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

import java.util.Objects;

/**
 * Stores an admin boundary down to the US county level. Only US places from the
 * USGS Gazetteer will have county level info
 */
public class AdminBoundary {

  private static final String NO_DATA_FOUND_VALUE = "NO_DATA_FOUND";
  private final String countryCode;
  private final String provinceCode;
  private final String provinceName;
  private final String countryName;
  private final String countyName;
  private final String countyCode;
  private final String countryRegex;
  private final String provinceRegex;
  private final String countyRegex;


  public AdminBoundary(String countryCode, String countryName, String provinceCode, String provinceName, String countyCode, String countyName,
      String countryRegex, String provinceRegex, String countyRegex) {
    this.countryCode = countryCode;
    this.provinceCode = provinceCode;
    this.provinceName = provinceName;
    this.countryName = countryName;
    this.countyName = countyName.equals("") ? NO_DATA_FOUND_VALUE : countyName;
    this.countyCode = countyCode.equals("") ? NO_DATA_FOUND_VALUE : countyCode;
    this.countryRegex = countryRegex;
    this.provinceRegex = provinceRegex;
    this.countyRegex = countyRegex;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public String getProvCode() {
    return provinceCode;
  }

  public String getProvinceName() {
    return provinceName;
  }

  public String getCountryName() {
    return countryName;
  }

  public String getCountyName() {
    return countyName;
  }

  public String getCountyCode() {
    return countyCode;
  }

  @Override
  public String toString() {
    return "AdminBoundary{" + "countryCode=" + countryCode + ", provinceCode=" + provinceCode + ", provinceName=" + provinceName + ", countryName=" + countryName + ", countyName=" + countyName + ", countyCode=" + countyCode + '}';
  }

  @Override
  public int hashCode() {
    int hash = 7;
    hash = 11 * hash + Objects.hashCode(this.countryCode);
    hash = 11 * hash + Objects.hashCode(this.provinceCode);
    hash = 11 * hash + Objects.hashCode(this.provinceName);
    hash = 11 * hash + Objects.hashCode(this.countryName);
    hash = 11 * hash + Objects.hashCode(this.countyName);
    hash = 11 * hash + Objects.hashCode(this.countyCode);
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
    final AdminBoundary other = (AdminBoundary) obj;
    if (!Objects.equals(this.countryCode, other.countryCode)) {
      return false;
    }
    if (!Objects.equals(this.provinceCode, other.provinceCode)) {
      return false;
    }
    if (!Objects.equals(this.provinceName, other.provinceName)) {
      return false;
    }
    if (!Objects.equals(this.countryName, other.countryName)) {
      return false;
    }
    if (!Objects.equals(this.countyName, other.countyName)) {
      return false;
    }
    if (!Objects.equals(this.countyCode, other.countyCode)) {
      return false;
    }
    return true;
  }

  public String getProvinceCode() {
    return provinceCode;
  }

  public String getCountryRegex() {
    return countryRegex;
  }

  public String getProvinceRegex() {
    return provinceRegex;
  }

  public String getCountyRegex() {
    return countyRegex;
  }

}
