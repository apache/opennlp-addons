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

package opennlp.jwnl.lemmatizer;

import java.util.ArrayList;
import java.util.List;

import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.dictionary.Dictionary;
import net.sf.extjwnl.dictionary.MorphologicalProcessor;

import opennlp.tools.lemmatizer.Lemmatizer;

/**
 * A {@link Lemmatizer} implementation based on extJWNL
 * and underlying WordNet resources.
 *
 * @see Dictionary
 * @see MorphologicalProcessor
 * @see POS
 */
public class JWNLLemmatizer implements Lemmatizer {

  private final MorphologicalProcessor morphy;

  /**
   * Initializes a {@link JWNLLemmatizer} instance.
   * Loads {@link Dictionary JWNL dictionary} and {@link MorphologicalProcessor} objects.
   * It also loads the JWNL configuration.
   * 
   * @throws JWNLException Thrown if errors occurred ramping up the WordNet resources.
   */
  public JWNLLemmatizer() throws JWNLException {
    super();
    Dictionary dict = Dictionary.getDefaultResourceInstance();
    morphy = dict.getMorphologicalProcessor();
  }

  /**
   * Takes a word and a POS tag and obtains a word's lemma from WordNet.
   * 
   * @param word The word to find the corresponding lemma for.
   * @param postag The POS tag associated with the {@code word}.
   * @return lemma The lemma as provided by WordNet, or {@code null} if not found.
   */
  public String lemmatize(String word, String postag) {
    String constantTag = "NNP";
    String lemma;
    try {
      POS pos;
      if (postag.startsWith("N") || postag.startsWith("n")) {
        pos = POS.NOUN;
      } else if (postag.startsWith("V") || postag.startsWith("v")) {
        pos = POS.VERB;
      } else if (postag.startsWith("J") || postag.startsWith("a")) {
        pos = POS.ADJECTIVE;
      } else if (postag.startsWith("RB") || postag.startsWith("r")) {
        pos = POS.ADVERB;
      } else {
        pos = POS.ADVERB;
      }
      IndexWord baseForm = morphy.lookupBaseForm(pos, word);
      if (baseForm != null) {
        lemma = baseForm.getLemma();
      } else if (postag.startsWith(constantTag)) {
        lemma = word;
      } else {
        lemma= word.toLowerCase();
      }
    } catch (JWNLException e) {
      return null;
    }
    return lemma;
  }

  @Override
  public String[] lemmatize(final String[] tokens, final String[] postags) {
    List<String> lemmas = new ArrayList<>();
    for (int i = 0; i < tokens.length; i++) {
      lemmas.add(this.lemmatize(tokens[i], postags[i]));
    }
    return lemmas.toArray(new String[0]);
  }

  @Override
  public List<List<String>> lemmatize(final List<String> tokens, final List<String> posTags) {
    throw new UnsupportedOperationException("Method not implemented here!");
  }
}

