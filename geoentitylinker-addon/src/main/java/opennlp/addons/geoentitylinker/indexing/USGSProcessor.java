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
import opennlp.addons.geoentitylinker.AdminBoundary;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;

import org.apache.lucene.index.IndexWriter;

/**
 *
 * @author mgiaconia
 */
public class USGSProcessor {

  public static void main(String[] args) {
    try {
      Map<String, AdminBoundary> provData = getProvData(new File("C:\\temp\\gazetteers\\usgsdata\\GOVT_UNITS_20140601.txt"), GazetteerIndexer.GazType.USGS);
      process(new File("C:\\temp\\gazetteers\\usgsdata\\GOVT_UNITS_20140601.txt"), new File("C:\\temp\\gazetteers\\usgsdata\\NationalFile_20140601.txt"), null, null);
    } catch (Exception ex) {
      Logger.getLogger(USGSProcessor.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public static void process(File lookupData, File usgsGazDataFile, File outputCountryContextfile, IndexWriter w) throws Exception {
    Map<String, AdminBoundary> provData = getProvData(lookupData, GazetteerIndexer.GazType.USGS);
    readFile(usgsGazDataFile, w, GazetteerIndexer.GazType.USGS, provData);
    writeCountryContextFile(outputCountryContextfile, provData);
  }

  public static void readFile(File gazateerInputData, IndexWriter w, GazetteerIndexer.GazType type, Map<String, AdminBoundary> lookupMap) throws Exception {

    BufferedReader reader = new BufferedReader(new FileReader(gazateerInputData));
    List<String> fields = new ArrayList<>();
    int counter = 0;
    System.out.println("reading gazetteer data from USGS file...........");
    String line = "";
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
        String countyCode = get.getCountyCode();
        if (!get.getCountyName().equals("NO_DATA_FOUND_VALUE")) {
          countyname =  get.getCountyName();
        }
        if (!get.getCountyCode().equals("NO_DATA_FOUND_VALUE")) {
          countyCode = get.getCountyCode();
        }
        String hierarchy = get.getCountryName() + ", " + get.getProvinceName() +", "+ countyname + ", " + placeName;

        doc.add(new TextField("hierarchy", hierarchy, Field.Store.YES));
        doc.add(new TextField("placename", placeName, Field.Store.YES));
        doc.add(new TextField("latitude", lat, Field.Store.YES));
        doc.add(new TextField("longitude", lon, Field.Store.YES));
        doc.add(new TextField("loctype", dsg, Field.Store.YES));
        doc.add(new TextField("admincode", (get.getCountryCode() + "." + get.getProvCode()).toLowerCase(), Field.Store.YES));
        doc.add(new TextField("countrycode", get.getCountryCode().toLowerCase(), Field.Store.YES));
        doc.add(new TextField("countycode", (get.getCountryCode() + "." + get.getProvCode() + "." + countyCode).toLowerCase(), Field.Store.YES));

        doc.add(new TextField("locid", id, Field.Store.YES));
        doc.add(new TextField("gazsource", "usgs", Field.Store.YES));
        w.addDocument(doc);
      }
      counter++;
      if (counter % 100000 == 0) {
        w.commit();
        System.out.println(counter + " .........USGS entries committed to index..............");
      }

    }
    w.commit();
    System.out.println("Completed indexing USGS gaz!");
  }

  private static Map<String, AdminBoundary> getProvData(File govUnitsFile, GazetteerIndexer.GazType type) {
 System.out.println("Attempting to read USGS province (State) data from: " + govUnitsFile.getPath());
    Map<String, AdminBoundary> outmap = new HashMap<>();
    BufferedReader reader;

    try {

      reader = new BufferedReader(new FileReader(govUnitsFile));
      int i = 0;
      String line = "";
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
        AdminBoundary adminBoundary = new AdminBoundary(countryCode, countryName, stateCode, stateName, countyCode, countyName);
        outmap.put(stateCode + "." + countyCode, adminBoundary);
        //  System.out.println(adminBoundary);

      }
      reader.close();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  System.out.println("Successfully read USGS province (State) data from: " + govUnitsFile.getPath());

    return outmap;

  }

  public static void writeCountryContextFile(File outfile, Map<String, AdminBoundary> adms) {
    // FileWriter writer = null;
    try (FileWriter writer = new FileWriter(outfile, true)) {

      for (String admkey : adms.keySet()) {
        AdminBoundary adm = adms.get(admkey);
        if (adm == null) {
          continue;
        }
        String province = adm.getProvinceName();
        String country = adm.getCountryName();
        /**
         * this is the standard format of the country context file... Geonames
         * data will have an empty string for the county
         */
        String line = adm.getCountryCode() + "\t" + adm.getProvCode() + "\t" + adm.getCountyCode() + "\t" + country + "\t" + province + "\t" + adm.getCountyName() + "\n";
        writer.write(line);
      ///  System.out.println(line);

      }
      writer.close();
    } catch (IOException ex) {
      Logger.getLogger(GeonamesProcessor.class.getName()).log(Level.SEVERE, null, ex);
    }
    System.out.println("successfully wrote USGS entries to country oontext file");
  }
}
