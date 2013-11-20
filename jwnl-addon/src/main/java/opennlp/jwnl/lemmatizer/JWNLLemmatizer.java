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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import opennlp.tools.lemmatizer.DictionaryLemmatizer;

import net.didion.jwnl.JWNLException;
import net.didion.jwnl.data.Adjective;
import net.didion.jwnl.data.FileDictionaryElementFactory;
import net.didion.jwnl.data.IndexWord;
import net.didion.jwnl.data.POS;
import net.didion.jwnl.data.PointerType;
import net.didion.jwnl.data.VerbFrame;
import net.didion.jwnl.dictionary.FileBackedDictionary;
import net.didion.jwnl.dictionary.MorphologicalProcessor;
import net.didion.jwnl.dictionary.file_manager.FileManager;
import net.didion.jwnl.dictionary.file_manager.FileManagerImpl;
import net.didion.jwnl.dictionary.morph.DefaultMorphologicalProcessor;
import net.didion.jwnl.dictionary.morph.DetachSuffixesOperation;
import net.didion.jwnl.dictionary.morph.LookupExceptionsOperation;
import net.didion.jwnl.dictionary.morph.LookupIndexWordOperation;
import net.didion.jwnl.dictionary.morph.Operation;
import net.didion.jwnl.dictionary.morph.TokenizerOperation;
import net.didion.jwnl.princeton.data.PrincetonWN17FileDictionaryElementFactory;
import net.didion.jwnl.princeton.file.PrincetonRandomAccessDictionaryFile;

public class JWNLLemmatizer implements DictionaryLemmatizer {

  private net.didion.jwnl.dictionary.Dictionary dict;
  private MorphologicalProcessor morphy;

  /**
   * Creates JWNL dictionary and morphological processor objects in
   * JWNLemmatizer constructor. It also loads the JWNL configuration into the
   * constructor. 
   * 
   * Constructor code based on Apache OpenNLP JWNLDictionary class. 
   * 
   * @param wnDirectory
   * @throws IOException
   * @throws JWNLException
   */
  public JWNLLemmatizer(String wnDirectory) throws IOException, JWNLException {
    PointerType.initialize();
    Adjective.initialize();
    VerbFrame.initialize();
    Map<POS, String[][]> suffixMap = new HashMap<POS, String[][]>();
    suffixMap.put(POS.NOUN, new String[][] { { "s", "" }, { "ses", "s" },
        { "xes", "x" }, { "zes", "z" }, { "ches", "ch" }, { "shes", "sh" },
        { "men", "man" }, { "ies", "y" } });
    suffixMap.put(POS.VERB, new String[][] { { "s", "" }, { "ies", "y" },
        { "es", "e" }, { "es", "" }, { "ed", "e" }, { "ed", "" },
        { "ing", "e" }, { "ing", "" } });
    suffixMap.put(POS.ADJECTIVE, new String[][] { { "er", "" }, { "est", "" },
        { "er", "e" }, { "est", "e" } });
    DetachSuffixesOperation tokDso = new DetachSuffixesOperation(suffixMap);
    tokDso.addDelegate(DetachSuffixesOperation.OPERATIONS, new Operation[] {
        new LookupIndexWordOperation(), new LookupExceptionsOperation() });
    TokenizerOperation tokOp = new TokenizerOperation(new String[] { " ", "-" });
    tokOp.addDelegate(TokenizerOperation.TOKEN_OPERATIONS,
        new Operation[] { new LookupIndexWordOperation(),
            new LookupExceptionsOperation(), tokDso });
    DetachSuffixesOperation morphDso = new DetachSuffixesOperation(suffixMap);
    morphDso.addDelegate(DetachSuffixesOperation.OPERATIONS, new Operation[] {
        new LookupIndexWordOperation(), new LookupExceptionsOperation() });
    Operation[] operations = { new LookupExceptionsOperation(), morphDso, tokOp };
    morphy = new DefaultMorphologicalProcessor(operations);
    FileManager manager = new FileManagerImpl(wnDirectory,
        PrincetonRandomAccessDictionaryFile.class);
    FileDictionaryElementFactory factory = new PrincetonWN17FileDictionaryElementFactory();
    FileBackedDictionary.install(manager, morphy, factory, true);
    dict = net.didion.jwnl.dictionary.Dictionary.getInstance();
    morphy = dict.getMorphologicalProcessor();
  }
  
  
  /**
   * It takes a word and a POS tag and obtains a word's lemma from WordNet.
   * 
   * @param word
   * @param postag
   * @return lemma
   */
  public String lemmatize(String word, String postag) {
    String constantTag = "NNP";
    IndexWord baseForm;
    String lemma = null;
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
      baseForm = morphy.lookupBaseForm(pos, word);
      if (baseForm != null) {
        lemma = baseForm.getLemma().toString();
      }
      else if (baseForm == null && postag.startsWith(String.valueOf(constantTag))) {
          lemma = word;
        }
        else {
          lemma= word.toLowerCase();
        }
    } catch (JWNLException e) {
      e.printStackTrace();
      return null;
    }
    return lemma;
  }

}

