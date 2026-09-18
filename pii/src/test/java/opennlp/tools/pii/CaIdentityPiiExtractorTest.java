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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class CaIdentityPiiExtractorTest {

  private final CaIdentityPiiExtractor extractor = new CaIdentityPiiExtractor();

  /**
   * Checks accepted labels and compact or grouped number forms.
   *
   * @param text The labeled input.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "SIN: 046 454 286",
      "sin=046454286",
      "Social Insurance Number: 046-454-286",
      "social insurance no. 046 454 286"})
  void testAcceptsLabeledSin(String text) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_CA_SIN, mentions.get(0).type());
    Assertions.assertEquals("046454286", mentions.get(0).normalized());
  }

  /**
   * Checks missing labels, invalid checksums and invalid number forms.
   *
   * @param text The rejected input.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "046 454 286",
      "SIN: 046 454 287",
      "SIN: 046-454 286",
      "SIN: 04645428",
      "SIN: 0464542860",
      "SIN was recorded as 046 454 286",
      "casino: 046 454 286"})
  void testRejectsUnlabeledOrInvalidSin(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks the numeric span without including its label. */
  @Test
  void testReportsExactNumberSpan() {
    final String text = "Employee SIN: 046 454 286.";
    final PiiMention mention = extractor.extract(text).get(0);

    Assertions.assertEquals("046 454 286",
        text.substring(mention.span().getStart(), mention.span().getEnd()));
  }

  /** Checks the null-text boundary contract. */
  @Test
  void testRejectsMissingText() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }
}
