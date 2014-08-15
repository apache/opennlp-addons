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
package opennlp.addons.geoentitylinker.scoring;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import opennlp.addons.geoentitylinker.AdminBoundaryContext;
import opennlp.addons.geoentitylinker.GazetteerEntry;
import opennlp.tools.entitylinker.EntityLinkerProperties;
import opennlp.tools.entitylinker.BaseLink;
import opennlp.tools.entitylinker.LinkedSpan;
import opennlp.tools.util.Span;

/**
 *
 * Generates scores based on string comparisons levenstein and dice
 */
public class FuzzyStringMatchScorer implements LinkedEntityScorer<AdminBoundaryContext> {

  @Override
  public void score(List<LinkedSpan> linkedSpans, String docText, Span[] sentenceSpans, EntityLinkerProperties properties, AdminBoundaryContext additionalContext) {
    for (LinkedSpan<BaseLink> linkedSpan : linkedSpans) {
      for (BaseLink link : linkedSpan.getLinkedEntries()) {
        if (link instanceof GazetteerEntry) {
          GazetteerEntry entry = (GazetteerEntry) link;
          String hierarchy = entry.getHierarchy();
          if (hierarchy != null) {
            Double dice = getDiceCoefficient(linkedSpan.getSearchTerm().toLowerCase(), hierarchy.toLowerCase(), 2);
            link.getScoreMap().put("hierarchydicecoef", dice);
            Double ld = (double) getLevenshteinDistance(linkedSpan.getSearchTerm().toLowerCase(), hierarchy.toLowerCase());
            link.getScoreMap().put("hierarchylevenshtein", ld);
          }
          String placename = entry.getItemName().toLowerCase();
           if (placename != null) {
            Double dice = getDiceCoefficient(linkedSpan.getSearchTerm().toLowerCase(), placename, 2);
            link.getScoreMap().put("placenamedicecoef", dice);
            
          }
        }
      }
    }

  }

  /**
   * Generates a score based on an overlap of nGrams between two strings using
   * the DiceCoefficient technique.
   *
   * @param s1 first string
   * @param s2 second string
   * @param nGrams number of chars in each gram
   * @return
   */
  public double getDiceCoefficient(String s1, String s2, int nGrams) {
    if (s1.isEmpty() || s2.isEmpty()) {
      return 0d;
    }
    List<String> s1Grams = new ArrayList<>();
    List<String> s2Grams = new ArrayList<>();
    String[] split1 = s1.split("[ ,]");
    for (String token : split1) {
      if (token.trim().equals("")) {
        continue;
      }
      s1Grams.add(token);
    }
    String[] split2 = s2.split("[ ,]");
    for (String token : split2) {
      if (token.trim().equals("")) {
        continue;
      }
      s2Grams.add(token);
    }

    Set<String> overlap = new HashSet<String>(s1Grams);
    overlap.retainAll(s2Grams);
    double totcombigrams = overlap.size();

    return (2 * totcombigrams) / (s1Grams.size() + s2Grams.size());
  }

  private int minimum(int a, int b, int c) {
    return Math.min(Math.min(a, b), c);
  }

  public int getLevenshteinDistance(CharSequence str1,
          CharSequence str2) {
    int[][] distance = new int[str1.length() + 1][str2.length() + 1];

    for (int i = 0; i <= str1.length(); i++) {
      distance[i][0] = i;
    }
    for (int j = 1; j <= str2.length(); j++) {
      distance[0][j] = j;
    }

    for (int i = 1; i <= str1.length(); i++) {
      for (int j = 1; j <= str2.length(); j++) {
        distance[i][j] = minimum(
                distance[i - 1][j] + 1,
                distance[i][j - 1] + 1,
                distance[i - 1][j - 1] + ((str1.charAt(i - 1) == str2.charAt(j - 1)) ? 0 : 1));
      }
    }

    return distance[str1.length()][str2.length()];
  }
}
