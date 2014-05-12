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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import org.apache.log4j.Logger;

/**
 * Finds instances of country mentions in a String, typically a document text.
 * Used to boost or degrade scoring of linked geo entities
 *
 */
public class CountryContext {

  private static final Logger LOGGER = Logger.getLogger(CountryContext.class);
  private List<CountryContextEntry> countrydata;
  private Map<String, Set<String>> nameCodesMap = new HashMap<>();
  private Map<String, Set<Integer>> countryMentions = new HashMap<>();
  private Set<CountryContextEntry> countryHits = new HashSet<>();
  private EntityLinkerProperties properties;
  
  public CountryContext(EntityLinkerProperties properties) throws Exception {
    this.properties = properties;
    if (countrydata == null) {
      String path = this.properties.getProperty("opennlp.geoentitylinker.countrycontext.filepath", "");
      
      File countryContextFile = new File(path);
      countrydata = getCountryContextFromFile(countryContextFile);
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
   * @param properties EntityLinkerProperties for getting database connection
   * @return
   */
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
  
  private List<CountryContextEntry> getCountryContextFromFile(File countryContextFile) {
    List<CountryContextEntry> entries = new ArrayList<>();
    String path = countryContextFile.getPath();
    BufferedReader reader;
    
    try {
      path = properties.getProperty("opennlp.geoentitylinker.countrycontext.filepath", "");
      
      reader = new BufferedReader(new FileReader(path));
      
      while (reader.read() != -1) {
        String line = reader.readLine();
        String[] values = line.split("\t");
        if (values.length != 4) {
          throw new IOException("improperly formatted country context file");
        }
        CountryContextEntry entry = new CountryContextEntry();
        // rc,cc1, full_name_nd_ro,dsg
        entry.setRc(values[0].toLowerCase());
        entry.setCc1(values[1].toLowerCase());
        entry.setFull_name_nd_ro(values[2].toLowerCase());
        entry.setDsg(values[3].toLowerCase());
        entries.add(entry);
      }
      reader.close();
    } catch (IOException ex) {
      LOGGER.error(ex);
    }
    return entries;
    
  }
}
