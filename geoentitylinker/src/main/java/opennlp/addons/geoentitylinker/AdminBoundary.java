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

/**
 * Stores an admin boundary down to the US county level. Only US places from the
 * USGS Gazetteer will have county level info
 */
public record AdminBoundary(String countryCode, String countryName, String provinceCode, String provinceName,
                            String countyCode, String countyName, String countryRegex, String provinceRegex,
                            String countyRegex) {

  private static final String NO_DATA_FOUND_VALUE = "NO_DATA_FOUND";

  public AdminBoundary(String countryCode, String countryName, String provinceCode, String provinceName, String countyCode, String countyName,
                       String countryRegex, String provinceRegex, String countyRegex) {
    this.countryCode = countryCode;
    this.provinceCode = provinceCode;
    this.provinceName = provinceName;
    this.countryName = countryName;
    this.countyName = countyName.isEmpty() ? NO_DATA_FOUND_VALUE : countyName;
    this.countyCode = countyCode.isEmpty() ? NO_DATA_FOUND_VALUE : countyCode;
    this.countryRegex = countryRegex;
    this.provinceRegex = provinceRegex;
    this.countyRegex = countyRegex;
  }

  public String getProvCode() {
    return provinceCode;
  }

  @Override
  public String toString() {
    return "AdminBoundary{" + "countryCode=" + countryCode + "," +
            "provinceCode=" + provinceCode + ", provinceName=" + provinceName + ", " +
            "countryName=" + countryName + ", countyName=" + countyName + ", " +
            "countyCode=" + countyCode + '}';
  }

}
