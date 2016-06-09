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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import opennlp.addons.geoentitylinker.AdminBoundary;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;

public class GeonamesProcessor {

  public static void process(File countryCodesLookupFile, File adm1CodesLookupFile, File geonamesGazetteerFile, File outputCountryContextFile, IndexWriter w) throws Exception {
    Map<String, String> countryCodes = getCountryCodes(countryCodesLookupFile);

    Map<String, AdminBoundary> adm1s = getProvData(adm1CodesLookupFile, countryCodes);
    //  List<AdminBoundary> adm2s = getCountryContextFromFile(new File("C:\\temp\\gazetteers\\geonamesdata\\admin2Codes.txt"));
    //admin2Codes.txt

    readFile(geonamesGazetteerFile, GazetteerIndexer.GazType.GEONAMES, adm1s, countryCodes, w);
    //now append to the coutnry context file
    writeCountryContextFile(outputCountryContextFile, adm1s);

  }

  public GeonamesProcessor() {
  }

  private static Map<String, AdminBoundary> getProvData(File adm1CodesLookupFile, Map<String, String> ccodes) {
    System.out.println("Attempting to read geonames province data from: " + adm1CodesLookupFile.getPath());

    Map<String, AdminBoundary> outmap = new HashMap<>();
    BufferedReader reader;
    Set<String> nullcodes = new HashSet<>();
    try {

      reader = new BufferedReader(new FileReader(adm1CodesLookupFile));
      int i = 0;
      String line = "";
      while ((line = reader.readLine()) != null) {

        // String line = reader.readLine();
        String[] values = line.split("\t");
        if (values.length != 4) {
          throw new IOException("improperly formatted province lookup file");
        }
        String ccode = values[0].toLowerCase();

        String[] split = ccode.split("\\.");
        String pcode = "";
        if (split.length == 2) {
          //System.out.println(split);
          ccode = split[0];
          pcode = split[1];
        }

        String pname = values[2];

        if (ccode.matches("[0-9].*")) {
          String code = ccode;
          ccode = pcode;
          pcode = code;
        }

        String cname = ccodes.get(ccode);

        if (cname == null) {
          nullcodes.add(ccode);
        }
        AdminBoundary data = new AdminBoundary(ccode, cname, pcode, pname, "NO_DATA_FOUND", "NO_DATA_FOUND", cname, pname, "NO_DATA_FOUND");
        //  System.out.println(data);
        outmap.put(ccode + "." + pcode, data);

      }
      System.out.println("INFO: there were " + nullcodes.size() + " null prov codes. This is due to inconsistencies in reference data.");
      reader.close();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
    System.out.println("Successfully read geonames province data from: " + adm1CodesLookupFile.getPath());

    return outmap;

  }

  private static Map<String, String> getCountryCodes(File countryContextFile) {
    Map<String, String> ccs = new HashMap<>();
    BufferedReader reader;
    try {

      reader = new BufferedReader(new FileReader(countryContextFile));
      int i = 0;
      String line = "";
      boolean start = false;
      while ((line = reader.readLine()) != null) {
        if (!line.toLowerCase().startsWith("#iso\t") && !start) {

          continue;
        } else {
          start = true;
        }
        String[] values = line.split("\t");

        String ccode = values[0].toLowerCase();//this is the 2 digit ISO code
        String cname = values[4].toLowerCase();
        if (!ccode.equals("")) {
          ccs.put(ccode, cname);
        }

      }
      reader.close();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
    ccs.put("SS", "South Sudan");
    ccs.put("CS", "Kosovo");
    return ccs;

  }

  public static void writeCountryContextFile(File outfile, Map<String, AdminBoundary> adms) {
    // FileWriter writer = null;
    try (FileWriter writer = new FileWriter(outfile, true)) {

      for (String admKey : adms.keySet()) {
        AdminBoundary adm = adms.get(admKey);
        if (adm == null) {
          continue;
        }
        String province = adm.getProvinceName();
        String country = adm.getCountryName();

        String line = adm.getCountryCode() + "\t" + adm.getProvCode() + "\t" + "" + "\t" + country + "\t" + province + "\t" + "" + "\t" + "(" + country + ")" + "\t"
            + adm.getProvinceName() + "\t" + adm.getCountyName() + "\n";
        writer.write(line);
        // System.out.println(line);

      }

    } catch (IOException ex) {
      ex.printStackTrace();
    }
    System.out.println("successfully wrote Geonames entries to country oontext file");
  }

  /**
   *
   * @param gazateerInputData the Geonames allCounties.txt file
   * @param type the types of gaz entry, usgs, geonames, or regions
   * @param adms the province info
   * @param countrycodes the country code info
   * @param w the lucene index writer
   * @throws Exception
   */
  public static void readFile(File gazateerInputData, GazetteerIndexer.GazType type, Map<String, AdminBoundary> adms, Map<String, String> countrycodes, IndexWriter w) throws Exception {

    BufferedReader reader = new BufferedReader(new FileReader(gazateerInputData));
    String[] boosts = "ADM1 ADM1H ADM2 ADM2H ADM3 ADM3H ADM4 ADM4H ADM5 ADMD ADMDH PCLD PCLH PCLI PCLIX TERR PCLIX PPL PPLA PPLA2 PPLA3 PPLA4 PPLC PPLCH PPLF PPLG PPLH PPLL PPLQ PPLR PPLS PPLX STLMT".split(" ");
    Map<String, Float> boostMap = new HashMap<>();
    for (String boost : boosts) {
      boostMap.put(boost.toLowerCase(), 10f);
    }
    String[] fieldStrings = new String[]{
      "geonameid",
      "name",
      "asciiname",
      "alternatenames",
      "latitude",
      "longitude",
      "feature_class",
      "feature_code",
      "country code",
      "cc2",
      "admin1_code",
      "admin2_code",
      "admin3_code",
      "admin4_code",
      "population",
      "elevation",
      "dem ",
      "timezone",
      "modification_date"};

    List<String> fields = Arrays.asList(fieldStrings);
    int counter = 0;
    System.out.println("reading gazetteer data from file...........");
    String line = "";
    while ((line = reader.readLine()) != null) {
      String[] values = line.split(type.getSeparator());

      Document doc = new Document();
      String admincode = values[10].toLowerCase();
      String ccode = values[8].toLowerCase();
      if (ccode.contains(",")) {
        String[] codes = ccode.split(",");
        if (codes.length > 0) {
          ccode = codes[0];
        }
      }
      AdminBoundary adm = adms.get(ccode + "." + admincode);

      String placeName = values[2];
      String lat = values[4];
      String lon = values[5];
      String dsg = values[7].toLowerCase();

      String id = values[0];
      String concatIndexEntry = "";
      String countryname = "";
      if (adm != null) {
        concatIndexEntry = adm.getCountryName() + ", " + adm.getProvinceName() + ", " + placeName;
        countryname = adm.getCountryName();
      } else {
        //there is no admin info, but we can still use the countrycode to concat the country name
        String n = countrycodes.get(ccode);
        countryname = n;
        if (n != null) {
          concatIndexEntry = n + ", " + placeName;
        } else {
          ///don't want a single token hierarchy entry.
          concatIndexEntry = "";
        }
      }
      if (ccode == null) {
        System.out.println("naughty country code");
      }
      for (int i = 0; i < fields.size() - 1; i++) {
        doc.add(new TextField(fields.get(i), values[i].trim(), Field.Store.YES));

      }
      if (dsg.equals("pcli")) {
        System.out.println("placename: " + placeName + " RESET TO: " + countryname);
        placeName = countryname;
      }
      /**
       * add standard fields to the index
       */
      doc.add(new TextField("hierarchy", concatIndexEntry, Field.Store.YES));
      doc.add(new TextField("placename", placeName, Field.Store.YES));
      // doc.add(new TextField("countryname", countryname, Field.Store.YES));
      //System.out.println(placeName);

      doc.add(new TextField("latitude", lat, Field.Store.YES));
      doc.add(new TextField("longitude", lon, Field.Store.YES));
      doc.add(new StringField("loctype", dsg, Field.Store.YES));
      doc.add(new StringField("admincode", (ccode + "." + admincode).toLowerCase(), Field.Store.YES));
      doc.add(new StringField("countrycode", ccode.toLowerCase(), Field.Store.YES));
      doc.add(new StringField("countycode", "", Field.Store.YES));
      doc.add(new StringField("locid", id, Field.Store.YES));
      placeName = placeName.replace("republic of", "").replace("federative", "");
      if (id.equals("3175395")) {
        System.out.println(placeName);
      }
      doc.add(new StringField("gazsource", "geonames", Field.Store.YES));

      w.addDocument(doc);

      counter++;
      if (counter % 100000 == 0) {
        w.commit();
        System.out.println(counter + " .........Geonames entries committed to index..............");
      }

    }

    System.out.println("Completed indexing geonames gaz! index name is: " + type.toString());
  }

}
