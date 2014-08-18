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
package opennlp.addons.geoentitylinker.scoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import opennlp.addons.geoentitylinker.AdminBoundaryContext;
import opennlp.addons.geoentitylinker.GazetteerEntry;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import opennlp.tools.entitylinker.LinkedSpan;
import opennlp.tools.util.Span;

/**
 *
 * @author mgiaconia
 */
public class PlacetypeScorer implements LinkedEntityScorer<AdminBoundaryContext> {

  private static final String[] boosts = "ADM1 ADM1H ADM2 ADM2H ADM3 ADM3H ADM4 ADM4H ADM5 ADMD ADMDH PCLD PCLH PCLI PCLIX TERR PCLIX PPL PPLA PPLA2 PPLA3 PPLA4 PPLC PPLCH PPLF PPLG PPLH PPLL PPLQ PPLR PPLS PPLX STLMT".split(" ");
  private Map<String, Double> boosetedTypes = new HashMap<>();

  public PlacetypeScorer() {
    fillMap();
  }

  @Override
  public void score(List<LinkedSpan> linkedSpans, String docText, Span[] sentenceSpans, EntityLinkerProperties properties, AdminBoundaryContext additionalContext) {
    for (LinkedSpan<GazetteerEntry> geospan : linkedSpans) {
      ArrayList<GazetteerEntry> linkedEntries = geospan.getLinkedEntries();
      for (GazetteerEntry gazetteerEntry : linkedEntries) {
        String type = gazetteerEntry.getItemType().toLowerCase();
        Double score = getScore(type);
        if (score == null) {
          score = 0d;
        }
        gazetteerEntry.getScoreMap().put("typescore", score);
      }
    }
  }

  private Double getScore(String type) {
    Double ret = boosetedTypes.get(type.toLowerCase());
    return ret == null ? 0d : ret;
  }

  private void fillMap() {
    if (boosetedTypes.isEmpty()) {
      for (String type : boosts) {
        if (type.equals("PCLI")) {
          boosetedTypes.put(type.toLowerCase(), 1d);
        } else if (type.startsWith("P") && !type.equals("PCLI")) {
          boosetedTypes.put(type.toLowerCase(), .5d);
        } else if (type.startsWith("ADM")) {
          boosetedTypes.put(type.toLowerCase(), .75d);
        }

      }
      boosetedTypes.put("pplc", .9);
    }
  }

}
