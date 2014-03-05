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
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Version;

/**
 *
 * Creates two lucene indexes, geonames and usgs for use in GeoEntityLinker
 */
public class GazetteerIndexer {

  public GazetteerIndexer() {

  }


  public static interface Separable {

    String getSeparator();
  }

  public enum GazType implements Separable {

    GEONAMES {
              @Override
              public String toString() {
                return "/opennlp_geoentitylinker_geonames_idx";
              }

              @Override
              public String getSeparator() {
                return "\t";
              }
            },
    USGS {
              @Override
              public String toString() {
                return "/opennlp_geoentitylinker_usgsgaz_idx";
              }

              @Override
              public String getSeparator() {
                return "\\|";
              }
            }
  }

  /**
   * indexes the USGS or Geonames gazateers.
   *
   * @param outputIndexDir     a DIRECTORY path where you would like to store
   *                           the output lucene indexes
   * @param gazetteerInputData the file, "as is" that was downloaded from the
   *                           USGS and GEONAMES website
   * @param type               indicates whether the data is USGS or GEONAMES
   *                           format
   * @throws Exception
   */
  public void index(File outputIndexDir, File gazetteerInputData, GazType type) throws Exception {
    if (!outputIndexDir.isDirectory()) {
      throw new IllegalArgumentException("outputIndexDir must be a directory.");
    }

    String indexloc = outputIndexDir + type.toString();
    Directory index = new MMapDirectory(new File(indexloc));

    Analyzer a = new StandardAnalyzer(Version.LUCENE_45);
    IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_45, a);

    IndexWriter w = new IndexWriter(index, config);

    readFile(gazetteerInputData, w, type);
    w.commit();
    w.close();

  }

  public void readFile(File gazateerInputData, IndexWriter w, GazType type) throws Exception {
    BufferedReader reader = new BufferedReader(new FileReader(gazateerInputData));
    List<String> fields = new ArrayList<String>();
    int counter = 0;
    // int langCodeIndex = 0;
    System.out.println("reading gazetteer data from file...........");
    while (reader.read() != -1) {
      String line = reader.readLine();
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
        w.addDocument(doc);
      }
      counter++;
      if (counter % 100000 == 0) {
        w.commit();
        System.out.println(counter + " .........committed to index..............");
      }

    }
    w.commit();
    System.out.println("Completed indexing gaz! index name is: " + type.toString());
  }

  /**
   * TODO: make these analyzers configurable
   */
//  private void loadAnalyzerMap() {
////    languageAnalyzerMap.put("ara", new ArabicAnalyzer(Version.LUCENE_45));
////    languageAnalyzerMap.put("tha", new ThaiAnalyzer(Version.LUCENE_45));
////    languageAnalyzerMap.put("rus", new RussianAnalyzer(Version.LUCENE_45));
////    languageAnalyzerMap.put("fas", new PersianAnalyzer(Version.LUCENE_45));
//
//  }
}
