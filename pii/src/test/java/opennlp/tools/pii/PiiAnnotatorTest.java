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

package opennlp.tools.pii;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;

public class PiiAnnotatorTest {

  private final PiiAnnotator annotator = new PiiAnnotator(new CursorPiiExtractor());

  @Test
  void testProvidesPiiLayer() {
    final Document document = annotator.annotate(
        Document.of("Invoice for jane@example.com, card 4111 1111 1111 1111."));

    final List<Annotation<PiiMention>> mentions = document.get(PiiAnnotator.PII);
    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_EMAIL, mentions.get(0).value().type());
    Assertions.assertEquals(PiiMention.TYPE_CARD, mentions.get(1).value().type());
    Assertions.assertTrue(document.layers().contains(PiiAnnotator.PII));
  }

  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> new PiiAnnotator(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> annotator.annotate(null));
  }

  /**
   * Verifies that a text without any PII still receives the PII layer, present but
   * empty, so downstream consumers can rely on the layer existing after this annotator
   * has run.
   */
  @Test
  void testTextWithoutPiiGetsEmptyLayer() {
    final Document document = annotator.annotate(
        Document.of("The quick brown fox jumps over the lazy dog."));

    Assertions.assertTrue(document.layers().contains(PiiAnnotator.PII));
    Assertions.assertTrue(document.get(PiiAnnotator.PII).isEmpty());
  }
}
