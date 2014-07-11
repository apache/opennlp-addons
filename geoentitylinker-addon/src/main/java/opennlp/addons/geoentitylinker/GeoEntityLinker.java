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

import opennlp.addons.geoentitylinker.scoring.ModelBasedScorer;
import opennlp.addons.geoentitylinker.scoring.LinkedEntityScorer;
import opennlp.addons.geoentitylinker.scoring.CountryProximityScorer;
import opennlp.addons.geoentitylinker.scoring.GeoHashBinningScorer;
import opennlp.addons.geoentitylinker.scoring.FuzzyStringMatchScorer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private AdminBoundaryContextGenerator countryContext;
  private Map<String, Set<Integer>> countryMentions;
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
            geoNamesEntries.addAll(gazateerSearcher.find(matches[i], 3, whereclause));
          }
        }else{//this means there were no where clauses generated so the where clause will default to look at the entire index
          geoNamesEntries.addAll(gazateerSearcher.find(matches[i], 3, " gaztype:* "));
        }
        //start generating queries
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

    return spans;
  }

  private void loadScorers() {
    if (scorers.isEmpty()) {
      scorers.add(new GeoHashBinningScorer());
      scorers.add(new CountryProximityScorer());
      scorers.add(new ModelBasedScorer());
      scorers.add(new FuzzyStringMatchScorer());
     // scorers.add(new ProvinceProximityScorer());
    }
  }

  @Override
  public void init(EntityLinkerProperties properties) {
    try {
      this.linkerProperties = properties;
      countryContext = new AdminBoundaryContextGenerator(this.linkerProperties);
      gazateerSearcher = new GazetteerSearcher(this.linkerProperties);
      loadScorers();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
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
