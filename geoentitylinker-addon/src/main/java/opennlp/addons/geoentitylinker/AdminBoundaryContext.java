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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AdminBoundaryContext {

  private final Map<String, Set<Integer>> countryMentions;
  private final Map<String, Set<Integer>> provMentions;
  private final Map<String, Set<Integer>> countyMentions;
  private final Set<String> countryHits;
  private final Set<String> provHits;
  private final Set<String> countyHits;
  private final Map<String, String> countryRefMap;
  private final Map<String, String> countryRegexMap;
  private final Map<String, String> countyRegexMap;
  private final Map<String, String> provinceRegexMap;
  private final Map<String, Map<String, String>> provRefMap;
  private final Map<String, Map<String, String>> countyRefMap;
  private final Set<String> whereClauses;
  private final Map<String, Set<String>> nameCodesMap;

  public AdminBoundaryContext(Map<String, Set<Integer>> countryMentions,
      Map<String, Set<Integer>> provMentions,
      Map<String, Set<Integer>> countyMentions,
      Set<String> countryHits,
      Set<String> provHits,
      Set<String> countyHits,
      Map<String, String> countryRefMap,
      Map<String, Map<String, String>> provRefMap,
      Map<String, Map<String, String>> countyRefMap, Map<String, Set<String>> nameCodesMap, Map<String, String> countryRegexMap, Map<String, String> provinceRegexMap,
      Map<String, String> countyRegexMap) {
    this.countryMentions = countryMentions;
    this.provMentions = provMentions;
    this.countyMentions = countyMentions;
    this.countryHits = countryHits;
    this.provHits = provHits;
    this.countyHits = countyHits;
    this.countryRefMap = countryRefMap;
    this.provRefMap = provRefMap;
    this.countyRefMap = countyRefMap;
    this.whereClauses = setWhereClauses();
    this.nameCodesMap = nameCodesMap;
    this.countryRegexMap = countryRegexMap;
    this.provinceRegexMap = provinceRegexMap;
    this.countyRegexMap = countyRegexMap;
  }

  public Map<String, Set<String>> getNameCodesMap() {
    return nameCodesMap;
  }

  public Map<String, Set<Integer>> getCountryMentions() {
    return countryMentions;
  }

  public Map<String, Set<Integer>> getProvMentions() {
    return provMentions;
  }

  public Map<String, Set<Integer>> getCountyMentions() {
    return countyMentions;
  }

  public Set<String> getCountryHits() {
    return countryHits;
  }

  public Set<String> getProvHits() {
    return provHits;
  }

  public Set<String> getCountyHits() {
    return countyHits;
  }

  public Map<String, String> getCountryRefMap() {
    return countryRefMap;
  }

  public Map<String, Map<String, String>> getProvRefMap() {
    return provRefMap;
  }

  public Map<String, Map<String, String>> getCountyRefMap() {
    return countyRefMap;
  }

  public Set<String> getWhereClauses() {
    return whereClauses;
  }

  private Set<String> setWhereClauses() {
    Set<String> clauses = new HashSet<>();
    for (String countryCode : this.getCountryHits()) {
      String gazType = countryCode.equalsIgnoreCase("us") ? " AND gazsource:usgs" : " AND gazsource:geonames";
      if (countryCode.toLowerCase().matches(".*rg[0-9].*")) {
        gazType = " AND gazsource:region";
      }
      Map<String, String> provsForCountry = this.getProvRefMap().get(countryCode);
      if (provsForCountry == null) {
        provsForCountry = new HashMap<>();
      }
      Map<String, String> provs = new HashMap<>();

//      if (!provsForCountry.isEmpty()) {
//        for (String pcode : provsForCountry.keySet()) {
//          if (this.getProvHits().contains(pcode)) {
//            provs.put(pcode, provsForCountry.get(pcode));
//
//            clauses.add(" countrycode:" + countryCode + " AND admincode:" + pcode + gazType);
//
//          }
//        }
//      }
      if (provs.isEmpty()) {
        //got a country with no mentioned provs
        clauses.add(" countrycode:" + countryCode + gazType);
      }
    }
    return clauses;
  }

  public Map<String, String> getCountryRegexMap() {
    return countryRegexMap;
  }

  public Map<String, String> getCountyRegexMap() {
    return countyRegexMap;
  }

  public Map<String, String> getProvinceRegexMap() {
    return provinceRegexMap;
  }

}
