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

package opennlp.tools.money;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link MoneyAnnotator} as a text-level layer: it needs no other layer, and its
 * annotations highlight the mention in the original text.
 */
public class MoneyAnnotatorTest {

  @Test
  void testMoneyLayerOnRawText() {
    final Document document = DocumentAnalyzer.builder()
        .add(new MoneyAnnotator(new CursorMoneyExtractor()))
        .build()
        .analyze("the deal closed at $2.3 billion after weeks of talks");

    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    assertEquals(1, money.size());
    final Annotation<MoneyAmount> mention = money.get(0);
    assertEquals("$2.3 billion",
        mention.span().getCoveredText(document.text()).toString());
    assertEquals(0, new BigDecimal("2300000000").compareTo(mention.value().amount()));
    assertEquals("USD", mention.value().currency());
  }

  @Test
  void testNullExtractorThrows() {
    assertThrows(IllegalArgumentException.class, () -> new MoneyAnnotator(null));
  }

  @Test
  void testNullDocumentThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyAnnotator(new CursorMoneyExtractor()).annotate(null));
  }
}
