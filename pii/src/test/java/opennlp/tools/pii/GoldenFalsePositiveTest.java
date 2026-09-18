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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the extractors to the golden false positive corpus: every fixture in
 * {@code golden-false-positives.txt} must stay unmatched by the widest configuration the
 * package offers.
 *
 * <p>Precision is the property that decides whether a redaction pipeline can be left
 * running unattended, and it is the property unit tests written alongside a detector are
 * worst at defending, since they are written by whoever just decided what the detector
 * accepts. The corpus is the counterweight: near misses, formatted values that mean
 * something else, and the checksums that separate a real value from a plausible one.</p>
 */
public class GoldenFalsePositiveTest {

  private static final String CORPUS = "/opennlp/tools/pii/golden-false-positives.txt";

  /** The widest configuration, national identifier packs included. */
  private static final PiiExtractor WIDE = PiiPacks.allStructured();

  static List<String> fixtures() {
    final List<String> fixtures = new ArrayList<>();
    try (InputStream in = GoldenFalsePositiveTest.class.getResourceAsStream(CORPUS)) {
      Assertions.assertNotNull(in, "corpus not found: " + CORPUS);
      final BufferedReader reader = new BufferedReader(
          new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank() && !line.startsWith("#")) {
          fixtures.add(line);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return fixtures;
  }

  @ParameterizedTest
  @MethodSource("fixtures")
  void testWidestConfigurationFindsNothing(String fixture) {
    final List<PiiMention> mentions = WIDE.extract(fixture);

    Assertions.assertEquals(List.of(), mentions,
        () -> "false positive in [" + fixture + "]: " + describe(fixture, mentions));
  }

  @ParameterizedTest
  @MethodSource("fixtures")
  void testDefaultExtractorFindsNothing(String fixture) {
    final PiiExtractor extractor = new CursorPiiExtractor();
    final List<PiiMention> mentions = extractor.extract(fixture);

    Assertions.assertEquals(List.of(), mentions,
        () -> "false positive in [" + fixture + "]: " + describe(fixture, mentions));
  }

  /**
   * Verifies that the corpus is joined into one text without any fixture matching, which
   * catches a scanner that only rejects a near miss because the text ends where it does.
   */
  @Test
  void testWholeCorpusAsOneTextFindsNothing() {
    final String joined = String.join("\n", fixtures());

    Assertions.assertEquals(List.of(), WIDE.extract(joined));
  }

  @Test
  void testCorpusIsSubstantial() {
    Assertions.assertTrue(fixtures().size() >= 120,
        "the corpus has shrunk: " + fixtures().size() + " fixtures");
  }

  private static String describe(String fixture, List<PiiMention> mentions) {
    final StringBuilder out = new StringBuilder();
    for (final PiiMention mention : mentions) {
      out.append(mention.type()).append("=[")
          .append(fixture, mention.span().getStart(), mention.span().getEnd()).append("] ");
    }
    return out.toString();
  }
}
