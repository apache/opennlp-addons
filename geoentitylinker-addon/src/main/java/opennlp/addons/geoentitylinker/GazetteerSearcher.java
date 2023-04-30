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
import java.lang.invoke.MethodHandles;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import opennlp.tools.entitylinker.EntityLinkerProperties;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.util.CharArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * Searches Gazetteers stored in a MMapDirectory Lucene index. The structure of
 * these indices are based on loading the indexes using the GazetteerIndexer
 *
 */
public class GazetteerSearcher {

  private final String REGEX_CLEAN = "[^\\p{L}\\p{Nd}]";
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private double scoreCutoff = .70;
  private final boolean doubleQuoteAllSearchTerms = false;
  private boolean useHierarchyField = false;

  private final EntityLinkerProperties properties;

  private Directory opennlpIndex;//= new MMapDirectory(new File(indexloc));
  private IndexReader opennlpReader;// = DirectoryReader.open(geonamesIndex);
  private IndexSearcher opennlpSearcher;// = new IndexSearcher(geonamesReader);
  private Analyzer opennlpAnalyzer;

  public static void main(String[] args) {
    try {
      boolean b = true;
      new GazetteerSearcher(new EntityLinkerProperties(new File("c:\\temp\\entitylinker.properties"))).find("alabama", 5, " countrycode:us AND gazsource:usgs");
    } catch (IOException ex) {
      LOG.error(ex.getLocalizedMessage(), ex);
    }
  }

  public GazetteerSearcher(EntityLinkerProperties properties) throws IOException {
    this.properties = properties;
    init();
  }

  /**
   * Searches the single lucene index that includes the location hierarchy.
   *
   * @param searchString the location name to search for
   * @param rowsReturned how many index entries to return (top N...)
   * @param whereClause the conditional statement that defines the index type
   * and the country oode.
   * @return
   */
  public ArrayList<GazetteerEntry> find(String searchString, int rowsReturned, String whereClause) {
    ArrayList<GazetteerEntry> linkedData = new ArrayList<>();
    searchString = cleanInput(searchString);
    if (searchString.isEmpty()) {
      return linkedData;
    }
    try {
      /*
       * build the search string Sometimes no country context is found. In this
       * case the code variables will be empty strings
       */
      String placeNameQueryString = "placename:(" + searchString.toLowerCase() + ") " + "AND " + whereClause;
      if (searchString.trim().contains(" ") && useHierarchyField) {
        placeNameQueryString = "(placename:(" + searchString.toLowerCase() + ") AND hierarchy:(" + formatForHierarchy(searchString) + "))"
            + " AND " + whereClause;
      }

      /*
       * check the cache and go no further if the records already exist
       */
      ArrayList<GazetteerEntry> get = GazetteerSearchCache.get(placeNameQueryString);
      if (get != null) {

        return get;
      }
      /*
       * search the placename
       */
      QueryParser parser = new QueryParser(placeNameQueryString, opennlpAnalyzer);
      Query q = parser.parse(placeNameQueryString);
      //Filter filter = new QueryWrapperFilter(new QueryParser(Version.LUCENE_48, whereClause, opennlpAnalyzer).parse(whereClause));      

      TopDocs bestDocs = opennlpSearcher.search(q, rowsReturned);
      Double maxscore = 0d;
      for (int i = 0; i < bestDocs.scoreDocs.length; ++i) {
        int docId = bestDocs.scoreDocs[i].doc;
        double sc = bestDocs.scoreDocs[i].score;
        if (maxscore.compareTo(sc) < 0) {
          maxscore = sc;
        }
        Document d = opennlpSearcher.doc(docId);
        List<IndexableField> fields = d.getFields();

        String lat = d.get("latitude");
        String lon = d.get("longitude");
        String placename = d.get("placename");
        String parentid = d.get("countrycode").toLowerCase();
        String provid = d.get("admincode");
        String itemtype = d.get("loctype");
        String source = d.get("gazsource");
        String hier = d.get("hierarchy");

        GazetteerEntry ge = new GazetteerEntry(parentid, String.valueOf(docId), placename, itemtype);
        ge.getScoreMap().put("lucene", sc);
        ge.setIndexID(String.valueOf(docId));
        ge.setSource(source);
        ge.setLatitude(Double.valueOf(lat));
        ge.setLongitude(Double.valueOf(lon));
        ge.setProvinceCode(provid);
        ge.setCountryCode(parentid);
        ge.setHierarchy(hier);
        for (IndexableField field : fields) {
          ge.getIndexData().put(field.name(), d.get(field.name()));
        }

        /*
         * only want hits above the levenshtein thresh. This should be a low
         * thresh due to the use of the hierarchy field in the index
         */
        // if (normLev > scoreCutoff) {
        if (ge.getItemParentID().equalsIgnoreCase(parentid) || parentid.equalsIgnoreCase("")) {
          //make sure we don't produce a duplicate
          if (!linkedData.contains(ge)) {
            linkedData.add(ge);
            /*
             * add the records to the cache for this query
             */
            GazetteerSearchCache.put(placeNameQueryString, linkedData);
          }
        }
      }

    } catch (IOException | ParseException ex) {
      LOG.error(ex.getLocalizedMessage(), ex);
    }

    return linkedData;
  }

  /**
   * Replaces any noise chars with a space, and depending on configuration adds
   * double quotes to the string
   *
   * @param input
   * @return
   */
  private String cleanInput(String input) {
    String output = input.replaceAll(REGEX_CLEAN, " ").trim();
    output = output.replace("  ", " ");
    if (doubleQuoteAllSearchTerms) {
      return "\"" + output + "\"";
    } else {
      return output;
    }

  }

  private void init() throws IOException {

    if (opennlpIndex == null) {
      String indexloc = properties.getProperty("opennlp.geoentitylinker.gaz", "");
      if (indexloc.equals("")) {
        LOG.error("Opennlp combined Gaz directory location not found!");
      }

      opennlpIndex = new MMapDirectory(Paths.get(indexloc));
      opennlpReader = DirectoryReader.open(opennlpIndex);
      opennlpSearcher = new IndexSearcher(opennlpReader);
      opennlpAnalyzer
          = //new StandardAnalyzer(Version.LUCENE_48, new CharArraySet(Version.LUCENE_48, new ArrayList(), true));
          new StandardAnalyzer(new CharArraySet(new ArrayList<>(), true));
      Map<String, Analyzer> analyMap = new HashMap<>();

      analyMap.put("countrycode", new KeywordAnalyzer());
      analyMap.put("admincode", new KeywordAnalyzer());
      analyMap.put("loctype", new KeywordAnalyzer());
      analyMap.put("countycode", new KeywordAnalyzer());
      analyMap.put("gazsource", new KeywordAnalyzer());

      opennlpAnalyzer
          = new PerFieldAnalyzerWrapper(opennlpAnalyzer, analyMap);

      String cutoff = properties.getProperty("opennlp.geoentitylinker.gaz.lucenescore.min", String.valueOf(scoreCutoff));
      String usehierarchy = properties.getProperty("opennlp.geoentitylinker.gaz.hierarchyfield", String.valueOf("0"));
      if (cutoff != null && !cutoff.isEmpty()) {
        scoreCutoff = Double.parseDouble(cutoff);
      }
      if (usehierarchy != null && !usehierarchy.isEmpty()) {
        useHierarchyField = Boolean.parseBoolean(usehierarchy);
      }
      //  opennlp.geoentitylinker.gaz.doublequote=false
      //opennlp.geoentitylinker.gaz.hierarchyfield=false

    }
  }

  private String formatForHierarchy(String searchTerm) {
    String[] parts = cleanInput(searchTerm).split(" ");
    String out = "";
    if (parts.length != 0) {
      for (String string : parts) {
        out += string + " AND ";
      }
      out = out.substring(0, out.lastIndexOf(" AND "));
    } else {
      out = cleanInput(searchTerm);
    }
    return out;
  }

}
