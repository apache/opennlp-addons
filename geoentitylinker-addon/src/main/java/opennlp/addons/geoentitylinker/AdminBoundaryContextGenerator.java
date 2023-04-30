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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds instances of country mentions in a String, typically a document text.
 * Used to boost or degrade scoring of linked geo entities
 *
 */
public class AdminBoundaryContextGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private List<CountryContextEntry> countrydata;
  private Map<String, Set<String>> nameCodesMap = new HashMap<>();
  private final Map<String, Set<Integer>> countryMentions = new HashMap<>();

  Map<String, String> countryRegexMap = new HashMap<>();
  Map<String, String> provinceRegexMap = new HashMap<>();
  Map<String, String> countyRegexMap = new HashMap<>();

  private final Set<CountryContextEntry> countryHits = new HashSet<>();
  private final EntityLinkerProperties properties;
  private final List<AdminBoundary> adminBoundaryData= new ArrayList<>();
  private final Set<AdminBoundary> adminBoundaryHits = new HashSet<>();
  private AdminBoundaryContext context;

  public AdminBoundaryContext getContext(String text) {
    context = null;
    nameCodesMap.clear();
    context = process(text);

    return context;
  }

  private final Set<String> countryHitSet = new HashSet<>();
  private final Map<String, String> countryMap = new HashMap<>();
  private final Map<String, Map<String, String>> provMap = new HashMap<>();
  private final Map<String, Map<String, String>> countyMap = new HashMap<>();

  private Map<String, Set<Integer>> provMentions = new HashMap<>();
  private Map<String, Set<Integer>> countyMentions = new HashMap<>();

  private final Set<String> provHits = new HashSet<>();
  private final Set<String> countyHits = new HashSet<>();

  public static void main(String[] args) {
    try {
      AdminBoundaryContextGenerator countryContext
          = new AdminBoundaryContextGenerator(new EntityLinkerProperties(new File("C:\\Temp\\gaz_data\\newCountryContextfile.txt")));

      AdminBoundaryContext c = countryContext.process("This artcle is about fairfax county virginia in the north of florida in the united states. It is also about Moscow and atlanta. Hillsborough county florida is a nice place. Eastern Africa people are cool.");
      System.out.println(c);
    } catch (Exception ex) {
      java.util.logging.Logger.getLogger(AdminBoundaryContextGenerator.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public AdminBoundaryContextGenerator(EntityLinkerProperties properties) throws IOException {
    this.properties = properties;
    if (countrydata == null) {
      String path = this.properties.getProperty("opennlp.geoentitylinker.countrycontext.filepath", "");
      if (path == null || path.trim().isEmpty()) {
        throw new IOException("missing country context data configuration. Property opennlp.geoentitylinker.countrycontext.filepath must have a valid path value in entitylinker properties file");
      }
      File countryContextFile = new File(path);
      if (countryContextFile == null || !countryContextFile.exists()) {
        throw new IOException("missing country context file");
      }
      //countrydata = getCountryContextFromFile(countryContextFile);
      getContextFromFile(countryContextFile);
      if (adminBoundaryData.isEmpty()) {
        throw new IOException("missing country context data");
      }
    }
  }

  public Map<String, Set<Integer>> getCountryMentions() {
    return countryMentions;
  }

  /**
   * @return returns the last set of hits after calling regexFind
   */
  public Set<CountryContextEntry> getCountryHits() {
    return countryHits;
  }

  /**
   * @return returns the last name to codes map after calling regexFind
   */
  public Map<String, Set<String>> getNameCodesMap() {
    return nameCodesMap;
  }

  public void setNameCodesMap(Map<String, Set<String>> nameCodesMap) {
    this.nameCodesMap = nameCodesMap;
  }

  private void reset() {
    this.nameCodesMap.clear();
    this.countryHitSet.clear();
    this.countryHits.clear();
    this.countryMentions.clear();
    this.provHits.clear();
    this.provMentions.clear();
    this.countyHits.clear();
    this.countyMentions.clear();
    this.adminBoundaryHits.clear();
  }

  /**
   * Finds indicators of countries, provinces, and cities, as per the USGS and
   * Geonames gazetteers. The results of this are used to score toponymns
   * downstream. The full text of a document should be passed in here.
   *
   * @param text the full text of the document (block of text).
   */
  private AdminBoundaryContext process(String text) {
    try {
      reset();
      Map<String, Set<Integer>> countryhitMap = regexfind(text, countryMap, countryHitSet, "country");
      if (!countryhitMap.isEmpty()) {
        for (String cc : countryhitMap.keySet()) {
          Map<String, String> provsForCc = provMap.get(cc);
          if (provsForCc != null) {
            provMentions.putAll(regexfind(text, provsForCc, provHits, "province"));
            if (provMentions != null) {
              for (String prov : provMentions.keySet()) {
                Map<String, String> get = countyMap.get(prov);
                if (get != null) {
                  countyMentions.putAll(regexfind(text, get, countyHits, "province"));
                }
              }
            }
          }
        }
      } else {
        for (Map<String, String> provsForCc : provMap.values()) {
          if (provsForCc != null) {
            provMentions = regexfind(text, provsForCc, provHits, "province");
            if (provMentions != null) {
              for (String prov : provMentions.keySet()) {
                //fake a country hit based on a province hit... this gets fuzzy
                String cc = prov.split("\\.")[0];
                if (!countryhitMap.containsKey(cc)) {
                  countryhitMap.put(cc, provMentions.get(prov));
                  countryHitSet.add(cc);
                } else {
                  countryhitMap.get(cc).addAll(provMentions.get(prov));
                }
                Map<String, String> get = countyMap.get(prov);
                if (get != null) {
                  countyMentions = regexfind(text, get, countyHits, "oounty");
                }
              }
            }
          }
        }
      }

      Map<String, String> countryRefMap = new HashMap<>();

      for (String c : countryHitSet) {
        String countryName = countryMap.get(c);
        if (countryName != null) {
          countryRefMap.put(c, countryName);
        }
      }

      return new AdminBoundaryContext(countryhitMap, provMentions, countyMentions, countryHitSet, provHits, countyHits,
              countryRefMap, provMap, countyMap, nameCodesMap, countryRegexMap, provinceRegexMap, countyRegexMap);
    } catch (Exception e) {
      LOG.error(e.getLocalizedMessage(), e);
    }
    return null;
  }

  /**
   * discovers indicators of admin boundary data using regex.
   *
   * @param docText the full text
   * @param lookupMap a map to use to find names. the key=a location code, the
   * value is an actual name.
   * @param hitsRef a reference to a set that stores the hits by id
   */
  private Map<String, Set<Integer>> regexfind(String docText, Map<String, String> lookupMap, Set<String> hitsRef, String locationType) {
    Map<String, Set<Integer>> mentions = new HashMap<>();
    if (lookupMap == null) {
      return mentions;
    }
    try {

      for (String entry : lookupMap.keySet()) {

        String name = lookupMap.get(entry).toLowerCase();
        if (name == null) {
          continue;
        }
        switch (locationType) {
          case "country":
            if (this.countryRegexMap.containsKey(entry)) {
              name = countryRegexMap.get(entry);
            }
            break;

          case "province":
            if (this.provinceRegexMap.containsKey(entry)) {
              name = provinceRegexMap.get(entry);
            }
            break;
          case "county":
            if (this.countyRegexMap.containsKey(entry)) {
              name = countyRegexMap.get(entry);
            }
            break;
        }
        name = "(^|[^\\p{L}\\p{Nd}])" + name.replace(", the", "") + "([^\\p{L}\\p{Nd}]|$)";
        Pattern regex = Pattern.compile(name, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher rs = regex.matcher(docText);
        String code = entry.toLowerCase();
        code = code.trim().replace("ï»¿", "");
        boolean found = false;
        while (rs.find()) {
          found = true;
          Integer start = rs.start();
          String hit = rs.group().toLowerCase().trim();
          hit = hit.replaceAll("\\.|,|;|\\?|!|\\\\|/|\"|'|=|-|&", "");
          if (mentions.containsKey(code)) {
            mentions.get(code).add(start);
          } else {
            Set<Integer> newset = new HashSet<>();
            newset.add(start);
            mentions.put(code, newset);
          }
          if (!hit.equals("")) {
            if (this.nameCodesMap.containsKey(hit)) {
              nameCodesMap.get(hit).add(code);
            } else {
              HashSet<String> newset = new HashSet<>();
              newset.add(code);
              nameCodesMap.put(hit, newset);
            }
          }

        }
        if (found) {
          hitsRef.add(code);

        }
      }

    } catch (Exception ex) {
      LOG.error(ex.getLocalizedMessage(), ex);
    }

    return mentions;
  }

  private void getContextFromFile(File countryContextFile) {
    if (this.adminBoundaryData != null && !this.adminBoundaryData.isEmpty()) {
      return;
    }

    BufferedReader reader;
    try {
      reader = new BufferedReader(new FileReader(countryContextFile));
      String line;
      int lineNum = 0;
      while ((line = reader.readLine()) != null) {
        String[] values = line.split("\t");
        if (lineNum == 0) {
          lineNum++;
          continue;
          //skip column name headers
        }
        if (values.length == 9) {
          AdminBoundary entry = new AdminBoundary(
              values[0].toLowerCase().trim().replace("ï»¿", ""),
              values[3].toLowerCase().trim(),
              values[1].toLowerCase().trim(),
              values[4].toLowerCase().trim(),
              values[2].toLowerCase().trim(),
              values[5].toLowerCase().trim(),
              values[6].toLowerCase().trim(),
              values[7].toLowerCase().trim(),
              values[8].toLowerCase().trim());
          this.adminBoundaryData.add(entry);
        } else {
          throw new IllegalArgumentException("Improperly formatted file");
        }

      }
      reader.close();
    } catch (IOException ex) {
      LOG.error(ex.getLocalizedMessage(), ex);
    }

    loadMaps(this.adminBoundaryData);

  }

  private void loadMaps(List<AdminBoundary> boundaries) {
    for (AdminBoundary adm : boundaries) {
      if (!adm.getCountryCode().equals("null")) {
        countryMap.put(adm.getCountryCode(), adm.getCountryName());
        if (countryRegexMap.containsKey(adm.getCountryCode())) {
          String currentRegex = countryRegexMap.get(adm.getCountryCode());
          if (currentRegex.length() > adm.getCountryRegex().length()) {
            // the longest one wins if they are not all the same for each entry in the file
            countryRegexMap.put(adm.getCountryCode(), currentRegex);
          }//else do nothing
        } else {
          countryRegexMap.put(adm.getCountryCode(), adm.getCountryRegex());
        }

        if (!adm.getProvCode().equals("null")) {
          Map<String, String> provs = provMap.get(adm.getCountryCode());
          if (provs == null) {
            provs = new HashMap<>();
          }
          //if (!provs.containsKey(adm.getProvCode())) {
          provs.put(adm.getCountryCode() + "." + adm.getProvCode(), adm.getProvinceName());
          provMap.put(adm.getCountryCode(), provs);
          // }

          if (!adm.getCountyCode().equalsIgnoreCase("no_data_found") && !adm.getCountyName().equalsIgnoreCase("no_data_found")) {
            Map<String, String> counties = countyMap.get(adm.getCountryCode() + "." + adm.getProvCode());
            if (counties == null) {
              counties = new HashMap<>();
            }            // if (!counties.containsKey(adm.getCountyCode())) {
            String countyid = adm.getCountryCode() + "." + adm.getProvCode() + "." + adm.getCountyCode();
            counties.put(countyid, adm.getCountyName());
            countyMap.put(adm.getCountryCode() + "." + adm.getProvCode(), counties);
            // }

          }

        }
      }
    }
    fillProvRegexMap();
    fillCountyRegexMap();
  }

  private void fillProvRegexMap() {
    this.provinceRegexMap = new HashMap<>();
    // this.adminBoundaryData
    for (AdminBoundary adm : adminBoundaryData) {

      if (provinceRegexMap.containsKey(adm.getProvCode())) {
        String currentRegex = provinceRegexMap.get(adm.getProvCode());
        if (currentRegex.length() > adm.getProvinceRegex().length()) {
          // the longest one wins if they are not all the same for each entry in the file
          provinceRegexMap.put(adm.getProvCode(), currentRegex);
        }//else do nothing
      } else {
        provinceRegexMap.put(adm.getProvCode(), adm.getProvinceRegex());
      }
    }
  }

  private void fillCountyRegexMap() {
    this.countyRegexMap = new HashMap<>();
    // this.adminBoundaryData
    for (AdminBoundary adm : adminBoundaryData) {

      if (countyRegexMap.containsKey(adm.getCountyCode())) {
        String currentRegex = countyRegexMap.get(adm.getCountyCode());
        if (currentRegex.length() > adm.getCountyRegex().length()) {
          // the longest one wins if they are not all the same for each entry in the file
          countyRegexMap.put(adm.getCountyCode(), currentRegex);
        }//else do nothing
      } else {
        countyRegexMap.put(adm.getCountyCode(), adm.getCountyRegex());
      }
    }

  }

}
