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
import org.apache.log4j.Logger;

/**
 * Finds instances of country mentions in a String, typically a document text.
 * Used to boost or degrade scoring of linked geo entities
 *
 */
public class AdminBoundaryContextGenerator {

  private static final Logger LOGGER = Logger.getLogger(AdminBoundaryContextGenerator.class);
  private List<CountryContextEntry> countrydata;
  private Map<String, Set<String>> nameCodesMap = new HashMap<>();
  private Map<String, Set<Integer>> countryMentions = new HashMap<>();
  private Set<CountryContextEntry> countryHits = new HashSet<>();
  private EntityLinkerProperties properties;
  private List<AdminBoundary> adminBoundaryData;
  private Set<AdminBoundary> adminBoundaryHits = new HashSet<>();
  private AdminBoundaryContext context;

  public AdminBoundaryContext getContext(String text) {
    context = null;
    nameCodesMap.clear();
    context = process(text);

    return context;
  }

  private Set<String> countryHitSet = new HashSet<>();
  private Map<String, String> countryMap = new HashMap<>();
  private Map<String, Map<String, String>> provMap = new HashMap<>();
  private Map<String, Map<String, String>> countyMap = new HashMap<>();

  private Map<String, Set<Integer>> provMentions = new HashMap<>();
  private Map<String, Set<Integer>> countyMentions = new HashMap<>();

  private Set<String> provHits = new HashSet<String>();
  private Set<String> countyHits = new HashSet<String>();

  public static void main(String[] args) {
    try {
      AdminBoundaryContextGenerator countryContext = new AdminBoundaryContextGenerator(new EntityLinkerProperties(new File("c:\\temp\\entitylinker.properties")));
      GeoEntityLinker linker = new GeoEntityLinker();
      linker.init(new EntityLinkerProperties(new File("c:\\temp\\entitylinker.properties")));

      countryContext.process("This artcle is about fairfax county virginia in the north of florida in the united states. It is also about Moscow and atlanta. Hillsborough county florida is a shithole. Eastern Africa people are cool.");

    } catch (Exception ex) {
      java.util.logging.Logger.getLogger(AdminBoundaryContextGenerator.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public AdminBoundaryContextGenerator(EntityLinkerProperties properties) throws IOException{
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
      adminBoundaryData = getContextFromFile(countryContextFile);
      if (adminBoundaryData.isEmpty()) {
        throw new IOException("missing country context data");
      }
    }
  }

  public Map<String, Set<Integer>> getCountryMentions() {
    return countryMentions;
  }

  /**
   * returns the last set of hits after calling regexFind
   *
   * @return
   */
  public Set<CountryContextEntry> getCountryHits() {
    return countryHits;
  }

  /**
   * returns the last name to codes map after calling regexFind
   *
   * @return
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
   * @return
   */
  private AdminBoundaryContext process(String text) {
    try {

      reset();
      Map<String, Set<Integer>> countryhitMap = regexfind(text, countryMap, countryHitSet);
      if (!countryhitMap.isEmpty()) {
        for (String cc : countryhitMap.keySet()) {
          Map<String, String> provsForCc = provMap.get(cc);
          if (provsForCc != null) {
            provMentions = regexfind(text, provsForCc, provHits);
            if (provMentions != null) {
              for (String prov : provMentions.keySet()) {
                Map<String, String> get = countyMap.get(prov);
                if (get != null) {
                  countyMentions = regexfind(text, get, countyHits);
                }
              }
            }
          }
        }
      } else {
        for (Map<String, String> provsForCc : provMap.values()) {
          if (provsForCc != null) {
            provMentions = regexfind(text, provsForCc, provHits);
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
                  countyMentions = regexfind(text, get, countyHits);
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

      AdminBoundaryContext context = new AdminBoundaryContext(countryhitMap, provMentions, countyMentions, countryHitSet, provHits, countyHits, countryRefMap, provMap, countyMap, nameCodesMap);

      return context;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Finds mentions of countries to assist in toponym resolution. Countries are
   * discovered via regex based on a configured file called
   * opennlp.geoentitylinker.countrycontext.txt. the file is configured using
   * the entitylinker.properties file as such:
   * opennlp.geoentitylinker.countrycontext.filepath=/opt/opennlp/opennlp.geoentitylinker.countrycontext.txt
   *
   * Finding mentions in documents is very helpful for scoring. Lazily loads the
   * list from the file.
   *
   * @param docText the full text of the document
   * @return
   */
  @Deprecated
  public Map<String, Set<Integer>> regexfind(String docText) {
    countryMentions = new HashMap<>();
    nameCodesMap.clear();
    try {

      for (CountryContextEntry entry : countrydata) {
        Pattern regex = Pattern.compile(entry.getFull_name_nd_ro().trim(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher rs = regex.matcher(docText);
        String code = entry.getCc1().toLowerCase();

        boolean found = false;
        while (rs.find()) {
          found = true;
          Integer start = rs.start();
          String hit = rs.group().toLowerCase();
          if (countryMentions.containsKey(code)) {
            countryMentions.get(code).add(start);
          } else {
            Set<Integer> newset = new HashSet<Integer>();
            newset.add(start);
            countryMentions.put(code, newset);
          }
          if (!hit.equals("")) {
            if (this.nameCodesMap.containsKey(hit)) {
              nameCodesMap.get(hit).add(code);
            } else {
              HashSet<String> newset = new HashSet<String>();
              newset.add(code);
              nameCodesMap.put(hit, newset);
            }
          }
        }
        if (found) {
          countryHits.add(entry);
        }

      }

    } catch (Exception ex) {
      LOGGER.error(ex);
    }

    return countryMentions;
  }

  /**
   * discovers indicators of admin boundary data using regex.
   *
   * @param docText the full text
   * @param lookupMap a map to use to find names. the key=a location code, the
   * value is an actual name.
   * @param hitsRef a reference to a set that stores the hits by id
   * @return
   */
  private Map<String, Set<Integer>> regexfind(String docText, Map<String, String> lookupMap, Set<String> hitsRef) {
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
        name = "(^|[^\\p{L}\\p{Nd}])" + name.replace(", the", "") + "([^\\p{L}\\p{Nd}]|$)";
        Pattern regex = Pattern.compile(name, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher rs = regex.matcher(docText);
        String code = entry.toLowerCase();

        boolean found = false;
        while (rs.find()) {
          found = true;
          Integer start = rs.start();
          String hit = rs.group().toLowerCase().trim();
          hit = hit.replaceAll("\\.|,|;|\\?|!|\\\\|/|\"|'|=|-|&", "");
          if (mentions.containsKey(code)) {
            mentions.get(code).add(start);
          } else {
            Set<Integer> newset = new HashSet<Integer>();
            newset.add(start);
            mentions.put(code, newset);
          }
          if (!hit.equals("")) {
            if (this.nameCodesMap.containsKey(hit)) {
              nameCodesMap.get(hit).add(code);
            } else {
              HashSet<String> newset = new HashSet<String>();
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
      LOGGER.error(ex);
      ex.printStackTrace();

    }

    return mentions;
  }

  private List<AdminBoundary> getContextFromFile(File countryContextFile) {
    if (this.adminBoundaryData != null && !this.adminBoundaryData.isEmpty()) {
      return adminBoundaryData;
    }
    List<AdminBoundary> entries = new ArrayList<>();
    BufferedReader reader;
    try {
      reader = new BufferedReader(new FileReader(countryContextFile));
      String line = "";
      while ((line = reader.readLine()) != null) {
        String[] values = line.split("\t");
        int len = values.length;
        if (len < 5 || len > 6) {
          throw new IllegalArgumentException("Improperly formatted file");
        }
        if (values.length == 6) {
          AdminBoundary entry = new AdminBoundary(
                  values[0].toLowerCase().trim(),
                  values[3].toLowerCase().trim(),
                  values[1].toLowerCase().trim(),
                  values[4].toLowerCase().trim(),
                  values[2].toLowerCase().trim(),
                  values[5].toLowerCase().trim());
          entries.add(entry);
        } else {
          AdminBoundary entry = new AdminBoundary(
                  values[0].toLowerCase().trim(),
                  values[3].toLowerCase().trim(),
                  values[1].toLowerCase().trim(),
                  values[4].toLowerCase().trim(),
                  values[2].toLowerCase().trim(),
                  "");
          entries.add(entry);
        }

      }
      reader.close();
    } catch (IOException ex) {
      LOGGER.error(ex);
    }
    loadMaps(entries);
    return entries;

  }

  private void loadMaps(List<AdminBoundary> boundaries) {
    for (AdminBoundary adm : boundaries) {
      if (!adm.getCountryCode().equals("null")) {
        countryMap.put(adm.getCountryCode(), adm.getCountryName());

        if (!adm.getProvCode().equals("null")) {
          Map<String, String> provs = provMap.get(adm.getCountryCode());
          if (provs == null) {
            provs = new HashMap<>();
          }
          //if (!provs.containsKey(adm.getProvCode())) {
          provs.put(adm.getCountryCode() + "." + adm.getProvCode(), adm.getProvinceName());
          provMap.put(adm.getCountryCode(), provs);
          // }

          if (!adm.getCountyCode().toLowerCase().equals("no_data_found") && !adm.getCountyName().toLowerCase().equals("no_data_found")) {
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
  }

}
