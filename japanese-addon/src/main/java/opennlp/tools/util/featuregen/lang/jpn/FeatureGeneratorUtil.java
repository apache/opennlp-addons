/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package opennlp.tools.util.featuregen.lang.jpn;

import java.util.Objects;

/**
 * This class provide common utilities for feature generation.
 */
public class FeatureGeneratorUtil {

  /**
   * Generates a class name for the specified token.
   * The classes are as follows where the first matching class is used:
   * <ul>
   * <li>hira - Japanese Hiragana</li>
   * <li>kata - Japanese Katakana</li>
   * <li>digit - all number letters</li>
   * <li>alpha - all alphabet letters</li>
   * <li>other - other </li>
   * </ul>
   * @param token A token or word.
   * @return The class name that the specified token belongs in.
   */
  public static String tokenFeature(String token) {

    Objects.requireNonNull(token, "token must be not null!");

    if (token.length() == 0) return "other";

    // scan token only once
    char c = token.charAt(0);
    if (Character.isDigit(c)) {
      for (int i = 1; i < token.length(); i++) {
        c = token.charAt(i);
        if (!Character.isDigit(c)) return "other";
      }
      return "digit";
    }
    else {
      Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
      if (ub.equals(Character.UnicodeBlock.HIRAGANA)) {
        for (int i = 1; i < token.length(); i++) {
          c = token.charAt(i);
          if (c != '・' && c != 'ー' && c != '〜') {
            ub = Character.UnicodeBlock.of(c);
            if (!ub.equals(Character.UnicodeBlock.HIRAGANA)) return "other";
          }
        }
        return "hira";
      }
      else if (ub.equals(Character.UnicodeBlock.KATAKANA)) {
        for (int i = 1; i < token.length(); i++) {
          c = token.charAt(i);
          if (c != '・' && c != 'ー' && c != '〜') {
            ub = Character.UnicodeBlock.of(c);
            if (!ub.equals(Character.UnicodeBlock.KATAKANA)) return "other";
          }
        }
        return "kata";
      }
      else if (Character.isAlphabetic(c) &&
          !ub.equals(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)) {
        for (int i = 1; i < token.length(); i++) {
          c = token.charAt(i);
          if (!Character.isAlphabetic(c)) return "other";
        }
        return "alpha";
      }
    }

    return "other";
  }
}
