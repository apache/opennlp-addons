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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queryparser.classic.ParseException;

import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Version;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import org.apache.lucene.analysis.util.CharArraySet;

/**
 *
 * Searches Gazetteers stored in a MMapDirectory Lucene index. The structure of
 * these indices are based on loading the indexes using the
 * GeoEntityLinkerSetupUtils
 *
 */
public class GazetteerSearcher {

  private double scoreCutoff = .90;
  private Directory geonamesIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader geonamesReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher geonamesSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer geonamesAnalyzer;
  //usgs US gazateer
  private Directory usgsIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader usgsReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher usgsSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer usgsAnalyzer;
  private EntityLinkerProperties properties;

  public GazetteerSearcher(EntityLinkerProperties properties) throws Exception {
    this.properties = properties;
    init();
  }

  /**
   *
   * @param searchString the named entity to look up in the lucene index
   * @param rowsReturned how many rows to allow lucene to return
   * @param code         the country code
   *
   * @return
   */
  public ArrayList<GazetteerEntry> geonamesFind(String searchString, int rowsReturned, String code) {
    ArrayList<GazetteerEntry> linkedData = new ArrayList<>();

    try {
      /**
       * build the search string Sometimes no country context is found. In this
       * case the code variable will be an empty string
       */
      String luceneQueryString = !code.equals("")
              ? "FULL_NAME_ND_RO:" + searchString.toLowerCase().trim() + " AND CC1:" + code.toLowerCase()//+"^90000" //[\"" + code.toLowerCase()+"\" TO \"" + code.toLowerCase() + "\"]"
              : "FULL_NAME_ND_RO:" + searchString.toLowerCase().trim();
      /**
       * check the cache and go no further if the records already exist
       */
      ArrayList<GazetteerEntry> get = GazetteerSearchCache.get(luceneQueryString);
      if (get != null) {

        return get;
      }

      QueryParser parser = new QueryParser(Version.LUCENE_45, luceneQueryString, geonamesAnalyzer);
      Query q = parser.parse(luceneQueryString);

      TopDocs search = geonamesSearcher.search(q, rowsReturned);

      for (int i = 0; i < search.scoreDocs.length; ++i) {
        GazetteerEntry entry = new GazetteerEntry();
        int docId = search.scoreDocs[i].doc;
        double sc = search.scoreDocs[i].score;

        entry.getScoreMap().put("lucene", sc);
        entry.setIndexID(docId + "");
        entry.setSource("geonames");

        Document d = geonamesSearcher.doc(docId);
        List<IndexableField> fields = d.getFields();
        for (int idx = 0; idx < fields.size(); idx++) {
          String value = d.get(fields.get(idx).name());
          value = value.toLowerCase();
          /**
           * these positions map to the required fields in the gaz TODO: allow a
           * configurable list of columns that map to the GazateerEntry fields,
           * then users would be able to plug in any gazateer they have (if they
           * build a lucene index out of it)
           */
          switch (idx) {
            case 1:
              entry.setItemID(value);
              break;
            case 3:
              entry.setLatitude(Double.valueOf(value));
              break;
            case 4:
              entry.setLongitude(Double.valueOf(value));
              break;
            case 10:
              entry.setItemType(value);
              break;
            case 12:
              entry.setItemParentID(value);
              if (!value.toLowerCase().equals(code.toLowerCase())) {
                continue;
              }
              break;
            case 23:
              entry.setItemName(value);
              break;
          }
          entry.getIndexData().put(fields.get(idx).name(), value);
        }
        /**
         * norm the levenstein distance
         */
        Double normLev = Double.valueOf(searchString.length()) / Double.valueOf(entry.getItemName().length());
        /**
         * only want hits above the levenstein thresh
         */
        if (normLev.compareTo(scoreCutoff) >= 0) {
          if (entry.getItemParentID().toLowerCase().equals(code.toLowerCase())) {
            entry.getScoreMap().put("normlucene", normLev);
            //make sure we don't produce a duplicate
            if (!linkedData.contains(entry)) {
              linkedData.add(entry);
              /**
               * add the records to the cache for this query
               */
              GazetteerSearchCache.put(luceneQueryString, linkedData);
            }
          }
        }
      }

    } catch (IOException | ParseException ex) {
      System.err.println(ex);
    }

    return linkedData;
  }

  /**
   * Looks up the name in the USGS gazateer, after checking the cache
   *
   * @param searchString the nameed entity to look up in the lucene index
   * @param rowsReturned how many rows to allow lucene to return
   *
   * @return
   */
  public ArrayList<GazetteerEntry> usgsFind(String searchString, int rowsReturned) {
    ArrayList<GazetteerEntry> linkedData = new ArrayList<>();
    String luceneQueryString = "FEATURE_NAME:" + searchString.toLowerCase().trim() + " OR MAP_NAME: " + searchString.toLowerCase().trim();
    try {

      /**
       * hit the cache
       */
      ArrayList<GazetteerEntry> get = GazetteerSearchCache.get(luceneQueryString);
      if (get != null) {
        //if the name is already there, return the list of cavhed results
        return get;
      }
      QueryParser parser = new QueryParser(Version.LUCENE_45, luceneQueryString, usgsAnalyzer);
      Query q = parser.parse(luceneQueryString);

      TopDocs search = usgsSearcher.search(q, rowsReturned);
      for (int i = 0; i < search.scoreDocs.length; i++) {
        GazetteerEntry entry = new GazetteerEntry();
        int docId = search.scoreDocs[i].doc;
        double sc = search.scoreDocs[i].score;
        //keep track of the min score for normalization

        entry.getScoreMap().put("lucene", sc);
        entry.setIndexID(docId + "");
        entry.setSource("usgs");
        entry.setItemParentID("us");
        Document d = usgsSearcher.doc(docId);
        List<IndexableField> fields = d.getFields();
        for (int idx = 0; idx < fields.size(); idx++) {
          String value = d.get(fields.get(idx).name());
          value = value.toLowerCase();
          switch (idx) {
            case 0:
              entry.setItemID(value);
              break;
            case 1:
              entry.setItemName(value);
              break;
            case 2:
              entry.setItemType(value);
              break;
            case 9:
              entry.setLatitude(Double.valueOf(value));
              break;
            case 10:
              entry.setLongitude(Double.valueOf(value));
              break;
          }
          entry.getIndexData().put(fields.get(idx).name(), value);
        }
        /**
         * norm the levenstein distance
         */
        Double normLev = Double.valueOf(searchString.length()) / Double.valueOf(entry.getItemName().length());
        /**
         * only want hits above the levenstein thresh
         */
        if (normLev.compareTo(scoreCutoff) >= 0) {
          //only keep it if the country code is a match. even when the code is passed in as a weighted condition, there is no == equiv in lucene

          entry.getScoreMap().put("normlucene", normLev);
          //make sure we don't produce a duplicate
          if (!linkedData.contains(entry)) {
            linkedData.add(entry);
            /**
             * add the records to the cache for this query
             */
            GazetteerSearchCache.put(luceneQueryString, linkedData);
          }
        }

      }

    } catch (IOException | ParseException ex) {
      System.err.println(ex);
    }

    return linkedData;
  }

  private void init() throws Exception {
    if (usgsIndex == null) {
      String indexloc = properties.getProperty("opennlp.geoentitylinker.gaz.usgs", "");
      if (indexloc.equals("")) {
        System.out.println("USGS Gaz location not found");

      }
      String cutoff = properties.getProperty("opennlp.geoentitylinker.gaz.lucenescore.min", String.valueOf(scoreCutoff));
      scoreCutoff = Double.valueOf(cutoff);
      usgsIndex = new MMapDirectory(new File(indexloc));
      usgsReader = DirectoryReader.open(usgsIndex);
      usgsSearcher = new IndexSearcher(usgsReader);
      usgsAnalyzer = new StandardAnalyzer(Version.LUCENE_45, new CharArraySet(Version.LUCENE_45, new ArrayList(), true));
    }
    if (geonamesIndex == null) {
      String indexloc = properties.getProperty("opennlp.geoentitylinker.gaz.geonames", "");
      if (indexloc.equals("")) {
        System.out.println("Geonames Gaz location not found");

      }
      String cutoff = properties.getProperty("opennlp.geoentitylinker.gaz.lucenescore.min", String.valueOf(scoreCutoff));
      scoreCutoff = Double.valueOf(cutoff);
      geonamesIndex = new MMapDirectory(new File(indexloc));
      geonamesReader = DirectoryReader.open(geonamesIndex);
      geonamesSearcher = new IndexSearcher(geonamesReader);
      //TODO: a language code switch statement should be employed here at some point
      geonamesAnalyzer = new StandardAnalyzer(Version.LUCENE_45, new CharArraySet(Version.LUCENE_45, new ArrayList(), true));

    }
  }
}
