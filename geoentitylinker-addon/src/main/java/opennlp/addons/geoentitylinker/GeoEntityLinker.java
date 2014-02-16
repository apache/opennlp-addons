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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import opennlp.tools.entitylinker.domain.BaseLink;
import opennlp.tools.entitylinker.domain.LinkedSpan;
import opennlp.tools.util.Span;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import opennlp.tools.entitylinker.EntityLinker;

/**
 * Links location entities to the USGS and GeoNames gazatteers, and uses several
 * scoring techniques to enable resolution. The gazateers are stored in lucene
 * indexes. The indexes can be built using the GeoEntityLinkerSetupUtils class
 * in this same package.
 */
public class GeoEntityLinker implements EntityLinker<LinkedSpan, EntityLinkerProperties> {

  private CountryContext countryContext;
  private Map<String, Set<Integer>> countryMentions;
  private EntityLinkerProperties linkerProperties;
  private GazateerSearcher gazateerSearcher;
  private List<LinkedEntityScorer> scorers = new ArrayList<>();
  /**
   * Flag for deciding whether to search gaz only for toponyms within countries
   * that are mentioned in the document
   */
  private Boolean filterCountryContext = true;

  public GeoEntityLinker() throws Exception {
  }

  @Override
  public List<LinkedSpan> find(String doctext, Span[] sentences, String[][] tokensBySentence, Span[][] namesBySentence) {
    ArrayList<LinkedSpan> spans = new ArrayList<LinkedSpan>();

    if (linkerProperties == null) {
      throw new IllegalArgumentException("EntityLinkerProperties cannot be null");
    }
    countryMentions = countryContext.regexfind(doctext);

    for (int s = 0; s < sentences.length; s++) {
      Span[] names = namesBySentence[s];
      String[] tokens = tokensBySentence[s];
      String[] matches = Span.spansToStrings(names, tokens);

      for (int i = 0; i < matches.length; i++) {

        /**
         * nga gazateer is for other than US placenames,don't want to use it if
         * US is the only country mentioned in the doc
         *
         */
        ArrayList<BaseLink> geoNamesEntries = new ArrayList<BaseLink>();
        if (!(countryMentions.keySet().contains("us") && countryMentions.keySet().size() == 1)
                || countryMentions.keySet().size() > 1 || countryMentions.keySet().isEmpty()) {
        
          if (!countryMentions.keySet().isEmpty()) {
            for (String code : countryMentions.keySet()) {
              if (!code.equals("us")) {
                geoNamesEntries.addAll(gazateerSearcher.geonamesFind(matches[i], 3, code));
              }
            }
          } else {
            geoNamesEntries.addAll(gazateerSearcher.geonamesFind(matches[i], 3, ""));

          }

        }
        ArrayList<BaseLink> usgsEntries = new ArrayList<>();
        if (countryMentions.keySet().contains("us") || countryMentions.keySet().isEmpty()) {
          //usgsEntries = usgsGaz.find(matches[i], names[i], linkerProperties);
          usgsEntries.addAll(gazateerSearcher.usgsFind(matches[i], 3));
        }
        LinkedSpan<BaseLink> geoSpan = new LinkedSpan<>(geoNamesEntries, names[i].getStart(), names[i].getEnd());

        if (!usgsEntries.isEmpty()) {
          geoSpan.getLinkedEntries().addAll(usgsEntries);
          geoSpan.setSearchTerm(matches[i]);
        }

        if (!geoSpan.getLinkedEntries().isEmpty()) {
          geoSpan.setSearchTerm(matches[i]);
          geoSpan.setSentenceid(s);
          spans.add(geoSpan);
        }
      }
    }

    if (!scorers.isEmpty()) {
      for (LinkedEntityScorer scorer : scorers) {
        scorer.score(spans, doctext, sentences, linkerProperties, countryContext);
      }
    }

    return spans;
  }

  private void loadScorers() {
    if (scorers.isEmpty()) {
    //  scorers.add(new FuzzyStringMatchScorer());
      scorers.add(new GeoHashBinningScorer());
      scorers.add(new CountryProximityScorer());
      scorers.add(new ModelBasedScorer());
    }
  }

  @Override
  public void init(EntityLinkerProperties properties) {
    try {
      this.linkerProperties = properties;
      countryContext = new CountryContext(this.linkerProperties);
      gazateerSearcher = new GazateerSearcher(this.linkerProperties);
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
