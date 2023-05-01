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
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;

public class RegionProcessor {

  public static void main(String[] args) {
    RegionProcessor.process(new File("C:\\temp\\gazetteers\\regions.txt"), new File("C:\\temp\\gazetteers\\testRegionContext.txt"), null);
  }

  /**
   *
   * @param regionsFile the file that stores Region references. the format of
   * this file is tab delimitted text with index 0 as the name of the region,
   * index 1 as the longitude, and index 2 as the latitude
   * @param outputCountryContextfile this is the country context files shared by
   * all indexing processors
   * @param w
   */
  public static void process(File regionsFile, File outputCountryContextfile, IndexWriter w) {
    try {
      readFile(regionsFile, outputCountryContextfile, w);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static void readFile(File gazateerInputData, File outputCountryContextfile, IndexWriter w) throws Exception {
    List<String> ccfileentries = new ArrayList<>();
    BufferedReader reader = new BufferedReader(new FileReader(gazateerInputData));
    List<String> fields = new ArrayList<>();
    int counter = 0;
    System.out.println("reading gazetteer data from Regions file...........");
    String line;
    while ((line = reader.readLine()) != null) {

      String[] values = line.split("\t");
      if (counter == 0) {

      } else {
        Document doc = new Document();
        for (int i = 0; i < fields.size() - 1; i++) {
          doc.add(new TextField(fields.get(i), values[i].trim(), Field.Store.YES));
        }
        String placeName = values[0];
        String lat = values[2];
        String lon = values[1];
        String dsg = "region";
        String id = "rg" + counter;

        String hierarchy = placeName;

        doc.add(new TextField("hierarchy", hierarchy, Field.Store.YES));
        doc.add(new TextField("placename", placeName, Field.Store.YES));
        doc.add(new StringField("latitude", lat, Field.Store.YES));
        doc.add(new StringField("longitude", lon, Field.Store.YES));
        doc.add(new StringField("loctype", dsg, Field.Store.YES));
        doc.add(new StringField("admincode", "", Field.Store.YES));
        doc.add(new StringField("countrycode", id, Field.Store.YES));
        doc.add(new StringField("countycode", "", Field.Store.YES));

        doc.add(new StringField("locid", id, Field.Store.YES));
        doc.add(new StringField("gazsource", "region", Field.Store.YES));
        //countrycontext file format
        // US	KY	131	United States	Kentucky	Leslie

        ccfileentries.add(id + "\t" + id + "\t" + id + "\t" + placeName + "\t" + "NO_DATA_FOUND" + "\t" + "NO_DATA_FOUND" + "\t" + "("
            + placeName + ")" + "\t" + "NO_DATA_FOUND" + "\t" + "NO_DATA_FOUND" + "\n");
        if (w != null) {
          w.addDocument(doc);
        }
      }
      counter++;

    }
    if (w != null) {
      w.commit();
    }
    FileWriter writer = new FileWriter(outputCountryContextfile, true);
    BufferedWriter bw = new BufferedWriter(writer);
    for (String string : ccfileentries) {
      bw.write(string);
    }
    System.out.println("successfully wrote Region entries to country oontext file");
    bw.close();
    System.out.println("Completed indexing regions!");
  }

}
