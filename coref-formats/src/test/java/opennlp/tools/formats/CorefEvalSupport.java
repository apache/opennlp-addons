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

package opennlp.tools.formats;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.Span;

/**
 * Shared steps of the coreference evaluation harnesses: the entity models and the
 * entity layer they predict.
 */
public final class CorefEvalSupport {

  /** The system property naming the model directory, by default {@code ~/.opennlp}. */
  public static final String MODELS_DIR_PROPERTY = "opennlp.coref.models.dir";

  private static final String[] NER_MODELS =
      {"en-ner-person.bin", "en-ner-location.bin", "en-ner-organization.bin"};

  private CorefEvalSupport() {
  }

  /** {@return the model directory the property names, or {@code ~/.opennlp}} */
  public static Path modelsDirectory() {
    return Path.of(System.getProperty(MODELS_DIR_PROPERTY,
        System.getProperty("user.home") + "/.opennlp"));
  }

  /**
   * Splits text at an exact delimiter and preserves empty fields.
   *
   * @param value The text. Must not be {@code null}.
   * @param delimiter The delimiter.
   * @return The fields in order.
   * @throws IllegalArgumentException Thrown if {@code value} is {@code null}.
   */
  public static String[] splitOn(String value, char delimiter) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    final List<String> fields = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= value.length(); i++) {
      if (i == value.length() || value.charAt(i) == delimiter) {
        fields.add(value.substring(start, i));
        start = i + 1;
      }
    }
    return fields.toArray(new String[0]);
  }

  /** Loads the person, location, and organization name finders. */
  public static List<NameFinderME> nameFinders(Path models) throws IOException {
    final List<NameFinderME> finders = new ArrayList<>();
    for (final String model : NER_MODELS) {
      finders.add(new NameFinderME(new TokenNameFinderModel(models.resolve(model))));
    }
    return finders;
  }

  /**
   * Runs the name finders over each sentence and adds the entity layer, keeping the
   * first-found span wherever two finders overlap.
   */
  public static Document withEntities(Document document, List<NameFinderME> finders) {
    final List<Annotation<String>> sentences = document.get(Layers.SENTENCES);
    final List<Annotation<String>> tokens = document.get(Layers.TOKENS);
    final List<Annotation<String>> entities = new ArrayList<>();
    int first = 0;
    for (final Annotation<String> sentence : sentences) {
      int last = first;
      while (last < tokens.size()
          && tokens.get(last).span().getStart() < sentence.span().getEnd()) {
        last++;
      }
      final String[] words = new String[last - first];
      for (int t = first; t < last; t++) {
        words[t - first] = tokens.get(t).value();
      }
      final List<Span> found = new ArrayList<>();
      for (final NameFinderME finder : finders) {
        for (final Span span : finder.find(words)) {
          if (found.stream().noneMatch(span::intersects)) {
            found.add(span);
          }
        }
      }
      found.sort((a, b) -> Integer.compare(a.getStart(), b.getStart()));
      for (final Span span : found) {
        entities.add(new Annotation<>(new Span(
            tokens.get(first + span.getStart()).span().getStart(),
            tokens.get(first + span.getEnd() - 1).span().getEnd()), span.getType()));
      }
      first = last;
    }
    for (final NameFinderME finder : finders) {
      finder.clearAdaptiveData();
    }
    return document.with(Layers.ENTITIES, entities);
  }
}
