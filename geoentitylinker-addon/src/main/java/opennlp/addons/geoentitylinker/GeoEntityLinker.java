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

import java.io.IOException;
import opennlp.addons.geoentitylinker.scoring.ModelBasedScorer;
import opennlp.addons.geoentitylinker.scoring.LinkedEntityScorer;
import opennlp.addons.geoentitylinker.scoring.CountryProximityScorer;
import opennlp.addons.geoentitylinker.scoring.GeoHashBinningScorer;
import opennlp.addons.geoentitylinker.scoring.FuzzyStringMatchScorer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import opennlp.addons.geoentitylinker.scoring.PlacetypeScorer;
import opennlp.addons.geoentitylinker.scoring.ProvinceProximityScorer;
import opennlp.tools.entitylinker.BaseLink;
import opennlp.tools.entitylinker.LinkedSpan;
import opennlp.tools.util.Span;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import opennlp.tools.entitylinker.EntityLinker;

/**
 * Links location entities to the USGS and GeoNames gazatteers, and uses several
 * scoring techniques to enable resolution. The gazateers are stored in lucene
 * indexes. The indexes can be built using the GeoEntityLinkerSetupUtils class
 * in this same package.
 */
public class GeoEntityLinker implements EntityLinker<LinkedSpan> {

  private static Integer topN = 2;
  private AdminBoundaryContextGenerator countryContext;
  private EntityLinkerProperties linkerProperties;
  private GazetteerSearcher gazateerSearcher;
  private List<LinkedEntityScorer<AdminBoundaryContext>> scorers = new ArrayList<>();

  @Override
  public List<LinkedSpan> find(String doctext, Span[] sentences, String[][] tokensBySentence, Span[][] namesBySentence) {
    ArrayList<LinkedSpan> spans = new ArrayList<LinkedSpan>();

    if (linkerProperties == null) {
      throw new IllegalArgumentException("EntityLinkerProperties cannot be null");
    }
    //countryMentions = countryContext.regexfind(doctext);
    AdminBoundaryContext context = countryContext.getContext(doctext);
    for (int s = 0; s < sentences.length; s++) {
      Span[] names = namesBySentence[s];
      String[] tokens = tokensBySentence[s];
      String[] matches = Span.spansToStrings(names, tokens);

      for (int i = 0; i < matches.length; i++) {

        ArrayList<BaseLink> geoNamesEntries = new ArrayList<>();
        if (!context.getWhereClauses().isEmpty()) {
          for (String whereclause : context.getWhereClauses()) {
            geoNamesEntries.addAll(gazateerSearcher.find(matches[i], topN, whereclause));
          }
        } else {//this means there were no where clauses generated so the where clause will default to look at the entire index
          geoNamesEntries.addAll(gazateerSearcher.find(matches[i], topN, " gaztype:usgs geonames regions "));
        }
        if (geoNamesEntries.isEmpty()) {
          continue;
        }
        LinkedSpan newspan = new LinkedSpan(geoNamesEntries, names[i], 0);
        newspan.setSearchTerm(matches[i]);
        newspan.setLinkedEntries(geoNamesEntries);
        newspan.setSentenceid(s);
        spans.add(newspan);
      }

    }

    if (!scorers.isEmpty()) {
      for (LinkedEntityScorer scorer : scorers) {
        scorer.score(spans, doctext, sentences, linkerProperties, context);
      }
    }
    /**
     * sort the data with the best score on top based on the sum of the scores
     * below from the score map for each baselink object
     */
    for (LinkedSpan<BaseLink> s : spans) {
      ArrayList<BaseLink> linkedData = s.getLinkedEntries();
      Collections.sort(linkedData, Collections.reverseOrder(new Comparator<BaseLink>() {
        @Override
        public int compare(BaseLink o1, BaseLink o2) {
          HashMap<String, Double> o1scoreMap = o1.getScoreMap();
          HashMap<String, Double> o2scoreMap = o2.getScoreMap();
          if (o1scoreMap.size() != o2scoreMap.size()) {
            return 0;
          }
          double sumo1 = 0d;
          double sumo2 = 0d;
          for (String object : o1scoreMap.keySet()) {
            if (object.equals("typescore")
                    || object.equals("countrycontext")
                    || object.equals("placenamedicecoef")
                    || object.equals("geohashbin")) {
              sumo1 += o1scoreMap.get(object);
              sumo2 += o2scoreMap.get(object);
            }
          }

          return Double.compare(sumo1,
                  sumo2);
        }
      }));
    }

    return spans;
  }

  private void loadScorers() {
    if (scorers.isEmpty()) {
      scorers.add(new ProvinceProximityScorer());
      scorers.add(new GeoHashBinningScorer());
      scorers.add(new CountryProximityScorer());
      scorers.add(new ModelBasedScorer());
      scorers.add(new FuzzyStringMatchScorer());
      scorers.add(new PlacetypeScorer());
    }
  }

  @Override
  public void init(EntityLinkerProperties properties) throws IOException {
    try {
      this.linkerProperties = properties;
      countryContext = new AdminBoundaryContextGenerator(this.linkerProperties);
      gazateerSearcher = new GazetteerSearcher(this.linkerProperties);
      String rowsRetStr = this.linkerProperties.getProperty("opennlp.geoentitylinker.gaz.rowsreturned", "2");
      Integer rws = 2;
      try {
        rws = Integer.valueOf(rowsRetStr);
      } catch (NumberFormatException e) {
        rws = 2;
      }
      topN = rws;
      loadScorers();
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }

  @Override
  public List<LinkedSpan> find(String text, Span[] sentences, Span[] tokens, Span[] nameSpans) {
    throw new UnsupportedOperationException("The GeoEntityLinker requires the entire document for proper scoring. This method is unsupported"); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public List<LinkedSpan> find(String text, Span[] sentences, Span[] tokens, Span[] nameSpans, int sentenceIndex) {
    throw new UnsupportedOperationException("The GeoEntityLinker requires the entire document for proper scoring. This method is unsupported"); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public List<LinkedSpan> find(String text, Span[] sentences, String[] tokens, Span[] nameSpans) {
    throw new UnsupportedOperationException("The GeoEntityLinker requires the entire document for proper scoring. This method is unsupported"); //To change body of generated methods, choose Tools | Templates.
  }
}
