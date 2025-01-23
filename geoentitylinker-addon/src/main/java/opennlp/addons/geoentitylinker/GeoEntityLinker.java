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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import opennlp.addons.geoentitylinker.scoring.CountryProximityScorer;
import opennlp.addons.geoentitylinker.scoring.FuzzyStringMatchScorer;
import opennlp.addons.geoentitylinker.scoring.GeoHashBinningScorer;
import opennlp.addons.geoentitylinker.scoring.LinkedEntityScorer;
import opennlp.addons.geoentitylinker.scoring.ModelBasedScorer;
import opennlp.addons.geoentitylinker.scoring.PlacetypeScorer;
import opennlp.addons.geoentitylinker.scoring.ProvinceProximityScorer;
import opennlp.tools.entitylinker.BaseLink;
import opennlp.tools.entitylinker.LinkedSpan;
import opennlp.tools.util.Span;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import opennlp.tools.entitylinker.EntityLinker;

/**
 * Links location entities to the USGS and GeoNames gazetteers, and uses several
 * scoring techniques to enable resolution. The gazetteers are stored in lucene
 * indexes. The indexes can be built using the GeoEntityLinkerSetupUtils class
 * in this same package.
 */
public class GeoEntityLinker implements EntityLinker<LinkedSpan<BaseLink>> {

  private static Integer topN = 2;
  private AdminBoundaryContextGenerator countryContext;
  private EntityLinkerProperties linkerProperties;
  private GazetteerSearcher gazetteerSearcher;
  private final List<LinkedEntityScorer<? extends BaseLink, AdminBoundaryContext>> scorers = new ArrayList<>();

  @Override
  public List<LinkedSpan<BaseLink>> find(String doctext, Span[] sentences,
                                         Span[][] tokensBySentence, Span[][] namesBySentence) {
    List<LinkedSpan<BaseLink>> spans = new ArrayList<>();
    //countryMentions = countryContext.regexfind(doctext);
    AdminBoundaryContext context = countryContext.getContext(doctext);
    for (int s = 0; s < sentences.length; s++) {
      Span[] names = namesBySentence[s];
      Span[] tokenSpans = tokensBySentence[s];
      String[] tokens = Span.spansToStrings(tokenSpans, sentences[s].getCoveredText(doctext));
      String[] matches = Span.spansToStrings(names, tokens);

      for (int i = 0; i < matches.length; i++) {

        ArrayList<BaseLink> geoNamesEntries = new ArrayList<>();
        if (!context.getWhereClauses().isEmpty()) {
          for (String whereclause : context.getWhereClauses()) {
            List<GazetteerEntry> find = gazetteerSearcher.find(matches[i], topN, whereclause);
            for (GazetteerEntry gazetteerEntry : find) {
              if (!geoNamesEntries.contains(gazetteerEntry)) {
                geoNamesEntries.add(gazetteerEntry);
              }
            }
          }
        } else { //this means there were no where clauses generated so the where clause will default to look at the entire index
          List<GazetteerEntry> find = gazetteerSearcher.find(matches[i], topN, " gaztype:usgs geonames regions ");
          for (GazetteerEntry gazetteerEntry : find) {
            if (!geoNamesEntries.contains(gazetteerEntry)) {
              geoNamesEntries.add(gazetteerEntry);
            }
          }
        }
        if (geoNamesEntries.isEmpty()) {
          continue;
        }
        /*
         * Normalize the returned scores for this name... this will assist the
         * sort
         */
        if (!spans.isEmpty()) {

          double maxscore = 0d;
          for (BaseLink gazetteerEntry : geoNamesEntries) {
            Double deNormScore = gazetteerEntry.getScoreMap().get("lucene");
            if (deNormScore.compareTo(maxscore) > 0) {
              maxscore = deNormScore;
            }
          }
          for (BaseLink gazetteerEntry : geoNamesEntries) {
            Double deNormScore = gazetteerEntry.getScoreMap().get("lucene");
            Double normalize = normalize(deNormScore, 0d, maxscore);
            gazetteerEntry.getScoreMap().put("normlucene", normalize);
          }
        }
        LinkedSpan<BaseLink> newspan = new LinkedSpan<>(geoNamesEntries, names[i], 0);
        newspan.setSearchTerm(matches[i]);
        newspan.setLinkedEntries(geoNamesEntries);
        newspan.setSentenceid(s);
        spans.add(newspan);
      }
    }

    if (!scorers.isEmpty()) {
      for (LinkedEntityScorer<? extends BaseLink, AdminBoundaryContext> scorer : scorers) {
        @SuppressWarnings("rawtypes")
        LinkedEntityScorer<BaseLink, AdminBoundaryContext> s = (LinkedEntityScorer) scorer;
        s.score(spans, doctext, sentences, linkerProperties, context);
      }
    }
    
    /*
     * sort the data with the best score on top based on the sum of the scores
     * below from the score map for each BaseLink object
     */
    for (LinkedSpan<? extends BaseLink> s : spans) {
      ArrayList<? extends BaseLink> linkedData = s.getLinkedEntries();
      linkedData.sort(Collections.reverseOrder((o1, o2) -> {
        Map<String, Double> o1scoreMap = o1.getScoreMap();
        Map<String, Double> o2scoreMap = o2.getScoreMap();
        if (o1scoreMap.size() != o2scoreMap.size()) {
          return 0;
        }
        double sumo1 = 0d;
        double sumo2 = 0d;
        for (String object : o1scoreMap.keySet()) {
          if (object.equals("typescore")
                  || object.equals("countrycontext")
                  || object.equals("placenamedicecoef")
                  || object.equals("provincecontext")
                  || object.equals("geohashbin")
                  || object.equals("normlucene")) {
            sumo1 += o1scoreMap.get(object);
            sumo2 += o2scoreMap.get(object);
          }
        }

        return Double.compare(sumo1, sumo2);
      }));
      //prune the list to topN
      Iterator<? extends BaseLink> iterator = linkedData.iterator();
      int n = 0;
      while (iterator.hasNext()) {
        if (n >= topN) {
          iterator.remove();
        }
        iterator.next();
        n++;
      }
    }

    return spans;
  }

  /**
   * transposes a value within one range to a relative value in a different
   * range. Used to normalize distances in this class.
   *
   * @param valueToNormalize the value to place within the new range
   * @param minimum the min of the set to be transposed
   * @param maximum the max of the set to be transposed
   * @return The value of the normalized distance.
   */
  private Double normalize(Double valueToNormalize, double minimum, double maximum) {
    double d = ((1 - 0) * (valueToNormalize - minimum)) / (maximum - minimum) + 0;
    return Double.isNaN(d) ? 0d : d;
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
    if (properties == null) {
      throw new IllegalArgumentException("EntityLinkerProperties cannot be null");
    }
    this.linkerProperties = properties;
    countryContext = new AdminBoundaryContextGenerator(this.linkerProperties);
    gazetteerSearcher = new GazetteerSearcher(this.linkerProperties);
    String rowsRetStr = this.linkerProperties.getProperty("opennlp.geoentitylinker.gaz.rowsreturned", "2");
    int rws;
    try {
      rws = Integer.parseInt(rowsRetStr);
    } catch (NumberFormatException e) {
      rws = 2;
    }
    topN = rws;
    loadScorers();
  }

  @Override
  public List<LinkedSpan<BaseLink>> find(String doctext, Span[] sentences, Span[][] tokensBySentence,
                                         Span[][] namesBySentence, int sentenceIndex) {
    throw new UnsupportedOperationException("The GeoEntityLinker requires the entire document "
        + "for proper scoring. This method is unsupported");
  }
}
