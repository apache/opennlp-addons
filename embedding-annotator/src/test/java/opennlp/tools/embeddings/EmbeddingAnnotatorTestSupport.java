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

package opennlp.tools.embeddings;

import java.util.ArrayList;
import java.util.List;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;

/** Shared documents and batch recording for embedding-annotator tests. */
final class EmbeddingAnnotatorTestSupport {

  /** Prevents utility instances. */
  private EmbeddingAnnotatorTestSupport() {
  }

  /** {@return a document with token and sentence layers in original-text coordinates} */
  static Document sentencesAndTokens() {
    return Document.of("Dogs bark. Cats nap.")
        .with(Layers.SENTENCES, List.of(
            new Annotation<>(new Span(0, 10), "Dogs bark."),
            new Annotation<>(new Span(11, 20), "Cats nap.")))
        .with(Layers.TOKENS, List.of(
            new Annotation<>(new Span(0, 4), "Dogs"),
            new Annotation<>(new Span(5, 9), "bark"),
            new Annotation<>(new Span(9, 10), "."),
            new Annotation<>(new Span(11, 15), "Cats"),
            new Annotation<>(new Span(16, 19), "nap"),
            new Annotation<>(new Span(19, 20), ".")));
  }

  /** Encodes each text as its batch index and length, recording calls and input order. */
  static final class RecordingEmbedder implements TextEmbedder {

    int batchCalls;
    int singleCalls;
    List<String> seen = List.of();

    /** {@inheritDoc} */
    @Override
    public float[][] embedAll(List<? extends CharSequence> texts) {
      batchCalls++;
      final List<String> copy = new ArrayList<>(texts.size());
      final float[][] vectors = new float[texts.size()][];
      for (int i = 0; i < texts.size(); i++) {
        copy.add(texts.get(i).toString());
        vectors[i] = new float[] {i, texts.get(i).length()};
      }
      seen = copy;
      return vectors;
    }

    /** {@inheritDoc} */
    @Override
    public float[] embed(CharSequence text) {
      singleCalls++;
      return new float[2];
    }

    /** {@inheritDoc} */
    @Override
    public int dimension() {
      return 2;
    }
  }
}
