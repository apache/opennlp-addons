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
package opennlp.addons.geoentitylinker.indexing;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;

import opennlp.addons.geoentitylinker.AdminBoundary;

public class USGSProcessor {

  private static final String TAB = "\t";

  public static void main(String[] args) {
    try {
      Map<String, AdminBoundary> provData = getProvData(new File("C:\\temp\\gazetteers\\usgsdata\\GOVT_UNITS_20140601.txt"), GazetteerIndexer.GazType.USGS);
      process(new File("C:\\temp\\gazetteers\\usgsdata\\GOVT_UNITS_20140601.txt"), new File("C:\\temp\\gazetteers\\usgsdata\\NationalFile_20140601.txt"), null, null);
    } catch (Exception ex) {
      Logger.getLogger(USGSProcessor.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public static void process(File lookupData, File usgsGazDataFile,
                             File outputCountryContextfile, IndexWriter w) throws IOException {
    Map<String, AdminBoundary> provData = getProvData(lookupData, GazetteerIndexer.GazType.USGS);
    readFile(usgsGazDataFile, w, GazetteerIndexer.GazType.USGS, provData);
    writeCountryContextFile(outputCountryContextfile, provData);
  }

  public static void readFile(File gazetteerInput, IndexWriter w, GazetteerIndexer.GazType type,
                              Map<String, AdminBoundary> lookupMap) throws IOException {

    Map<String, StateCentroid> states = new HashMap<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(gazetteerInput))) {

      List<String> fields = new ArrayList<>();
      int counter = 0;
      System.out.println("reading gazetteer data from USGS file...........");
      String line;
      while ((line = reader.readLine()) != null) {

        String[] values = line.split(type.getSeparator());
        if (counter == 0) {
          for (String columnName : values) {
            fields.add(columnName.replace("»¿", "").trim());
          }

        } else {
          Document doc = new Document();
          for (int i = 0; i < fields.size() - 1; i++) {
            doc.add(new TextField(fields.get(i), values[i].trim(), Field.Store.YES));
          }
          String placeName = values[1];
          String lat = values[9];
          String lon = values[10];
          String dsg = values[2];
          String id = values[0];

          String ccode = values[6];
          String admincode = values[3];
          AdminBoundary get = lookupMap.get(admincode + "." + ccode);
          String countyname = "";
          if (get == null) {
            System.out.println("null...continuing to index" + " ccode: " + ccode + " , admincode: " + admincode + " , placename: " + placeName);
            continue;

          }
          String countyCode = get.countyCode();

          if (!get.countyName().equals("NO_DATA_FOUND_VALUE")) {
            countyname = get.countyName();
          }
          if (!get.countyCode().equals("NO_DATA_FOUND_VALUE")) {
            countyCode = get.countyCode();
          }
          String hierarchy = get.countryName() + ", " + get.provinceName() + ", " + countyname + ", " + placeName;

          if (states.containsKey(get.provinceName())) {
            StateCentroid entry = states.get(get.provinceName());
            entry.count++;
            entry.latSum += Double.parseDouble(lat);
            entry.longSum += Double.parseDouble(lon);
          } else {
            StateCentroid centroid = new StateCentroid();
            centroid.statecode = get.getProvCode();
            centroid.count = 1;
            centroid.latSum = Double.parseDouble(lat);
            centroid.longSum = Double.parseDouble(lon);
            states.put(get.provinceName(), centroid);
          }

          doc.add(new TextField("hierarchy", hierarchy, Field.Store.YES));
          doc.add(new TextField("placename", placeName, Field.Store.YES));
          doc.add(new TextField("latitude", lat, Field.Store.YES));
          doc.add(new TextField("longitude", lon, Field.Store.YES));
          doc.add(new StringField("loctype", dsg, Field.Store.YES));
          doc.add(new StringField("admincode", (get.countryCode() + "." + get.getProvCode()).toLowerCase(), Field.Store.YES));
          doc.add(new StringField("countrycode", get.countryCode().toLowerCase(), Field.Store.YES));
          doc.add(new StringField("countycode", (get.countryCode() + "." + get.getProvCode() + "." + countyCode).toLowerCase(), Field.Store.YES));

          doc.add(new StringField("locid", id, Field.Store.YES));
          doc.add(new StringField("gazsource", "usgs", Field.Store.YES));
          w.addDocument(doc);
        }
        counter++;
        if (counter % 100000 == 0) {
          w.commit();
          System.out.println(counter + " .........USGS entries committed to index..............");
        }

      }
    }


    for (String state : states.keySet()) {
      StateCentroid get = states.get(state);
      Document doc = new Document();
      doc.add(new TextField("hierarchy", "united states, " + state, Field.Store.YES));
      doc.add(new TextField("placename", state, Field.Store.YES));
      //calculate a centroid for all the points that were in the state
      doc.add(new TextField("latitude", (get.latSum / get.count) + "", Field.Store.YES));
      doc.add(new TextField("longitude", (get.longSum / get.count) + "", Field.Store.YES));
      doc.add(new StringField("loctype", "adm1", Field.Store.YES));
      doc.add(new StringField("admincode", get.statecode, Field.Store.YES));
      doc.add(new StringField("countrycode", "us", Field.Store.YES));
      doc.add(new StringField("countycode", "", Field.Store.YES));

      doc.add(new StringField("locid", "us_state:" + state, Field.Store.YES));
      doc.add(new StringField("gazsource", "usgs", Field.Store.YES));
      w.addDocument(doc);

      // System.out.println(get.statecode + "," + (get.latSum / get.count) + "," + (get.longSum / get.count));
    }
    Document doc = new Document();
    doc.add(new TextField("hierarchy", "united states", Field.Store.YES));
    doc.add(new TextField("placename", "united states", Field.Store.YES));
    //calculate a centroid for all the points that were in the state
    doc.add(new TextField("latitude", 39.0 + "", Field.Store.YES));
    doc.add(new TextField("longitude", -103.0 + "", Field.Store.YES));
    doc.add(new StringField("loctype", "pcli", Field.Store.YES));
    doc.add(new StringField("admincode", "", Field.Store.YES));
    doc.add(new StringField("countrycode", "us", Field.Store.YES));
    doc.add(new StringField("countycode", "", Field.Store.YES));

    doc.add(new StringField("locid", "us_centroid" + "unitedstates", Field.Store.YES));
    doc.add(new StringField("gazsource", "usgs", Field.Store.YES));
    //System.out.println("uscentroid," + (sumofLatSums / sumOfCounts) + "," + (sumofLonSums / sumOfCounts));

    w.addDocument(doc);
    w.commit();

    System.out.println("Completed indexing USGS gaz!");
  }

  private static class StateCentroid {

    double latSum;
    double longSum;
    String statecode;
    int count;
  }

  private static Map<String, AdminBoundary> getProvData(File govUnitsFile, GazetteerIndexer.GazType type) {
    System.out.println("Attempting to read USGS province (State) data from: " + govUnitsFile.getPath());
    Map<String, AdminBoundary> outmap = new HashMap<>();

    try (BufferedReader reader = new BufferedReader(new FileReader(govUnitsFile))) {
      int i = 0;
      String line;
      String[] fields = null;
      while ((line = reader.readLine()) != null) {

        String[] values = line.split(type.getSeparator());
        if (i == 0) {
          fields = values;
          i++;
          continue;
        }
        i++;
        // System.out.println(i);
        String countyCode = values[2];
        String countyName = values[3];
        String stateCode = values[5];
        String stateName = values[6];
        String countryCode = values[7];
        String countryName = values[8];
        AdminBoundary adminBoundary = new AdminBoundary(countryCode, countryName, stateCode, stateName, countyCode,
                countyName, null, null, null);
        outmap.put(stateCode + "." + countyCode, adminBoundary);
        //  System.out.println(adminBoundary);

      }
    } catch (IOException ex) {
      ex.printStackTrace();
    }
    System.out.println("Successfully read USGS province (State) data from: " + govUnitsFile.getPath());

    return outmap;

  }

  public static void writeCountryContextFile(File outfile, Map<String, AdminBoundary> adms) {

    try (BufferedWriter bw = new BufferedWriter(new FileWriter(outfile, true))) {
      for (String admkey : adms.keySet()) {
        AdminBoundary adm = adms.get(admkey);
        if (adm == null) {
          continue;
        }
        String province = adm.provinceName();
        String country = adm.countryName();
        /*
         * this is the standard format of the country context file... Geonames
         * data will have an empty string for the county
         */
        String line = adm.countryCode() + TAB + adm.getProvCode() + TAB + adm.countyCode() +
                TAB + country + TAB + province + TAB + adm.countyName() + TAB
                + "(U\\.S\\.[ $]|U\\.S\\.A\\.[ $]|United States|the US[ $]|a us[ $])" +
                TAB + adm.provinceName() + TAB + adm.countyName() + "\n";
        bw.write(line);
        //  System.out.println(line);
      }
    } catch (IOException ex) {
      Logger.getLogger(GeonamesProcessor.class.getName()).log(Level.SEVERE, null, ex);
    }
    System.out.println("successfully wrote USGS entries to country context file");
  }
}
