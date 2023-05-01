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
package opennlp.addons.modelbuilder.impls;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import opennlp.addons.modelbuilder.Modelable;
import opennlp.tools.namefind.TokenNameFinderFactory;
import opennlp.tools.util.MarkableFileInputStreamFactory;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.namefind.NameSampleDataStream;
import opennlp.tools.namefind.TokenNameFinderModel;

import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.TrainingParameters;

/**
 * Creates annotations, writes annotations to file, and creates a model and writes to a file
 */
public class GenericModelableImpl implements Modelable {

  private Set<String> annotatedSentences = new HashSet<>();
  BaseModelBuilderParams params;

  @Override
  public void setParameters(BaseModelBuilderParams params) {
    this.params = params;
  }

  @Override
  public String annotate(String sentence, String namedEntity, String entityType) {
    return sentence.replace(namedEntity, " <START:" + entityType + "> " + namedEntity + " <END> ");
  }

  @Override
  public void writeAnnotatedSentences() {
    try (Writer bw = new BufferedWriter(new FileWriter(params.getAnnotatedTrainingDataFile(), false))) {
      for (String s : annotatedSentences) {
        bw.write(s.replace("\n", " ").trim() + "\n");
      }
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  @Override
  public Set<String> getAnnotatedSentences() {
    return annotatedSentences;
  }

  @Override
  public void setAnnotatedSentences(Set<String> annotatedSentences) {
    this.annotatedSentences = annotatedSentences;
  }

  @Override
  public void addAnnotatedSentence(String annotatedSentence) {
    annotatedSentences.add(annotatedSentence);
  }

  @Override
  public void buildModel(String entityType) {
    try (ObjectStream<NameSample> sampleStream = new NameSampleDataStream(new PlainTextByLineStream(
            new MarkableFileInputStreamFactory(params.getAnnotatedTrainingDataFile()), StandardCharsets.UTF_8));
         OutputStream modelOut = new BufferedOutputStream(new FileOutputStream(params.getModelFile()))) {
      System.out.println("\tBuilding Model using " + annotatedSentences.size() + " annotations");
      System.out.println("\t\treading training data...");
      TokenNameFinderModel model;
      model = NameFinderME.train("en", entityType, sampleStream,
                TrainingParameters.defaultParams(), new TokenNameFinderFactory());
      sampleStream.close();
      model.serialize(modelOut);
      System.out.println("\tmodel generated");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public TokenNameFinderModel getModel() {
    TokenNameFinderModel nerModel = null;
    try {
      nerModel = new TokenNameFinderModel(new FileInputStream(params.getModelFile()));
    } catch (IOException ex) {
      Logger.getLogger(GenericModelableImpl.class.getName()).log(Level.SEVERE, null, ex);
    }
    return nerModel;
  }

  @Override
  public String[] tokenizeSentenceToWords(String sentence) {
    return sentence.split("\\s+");
  }
}
