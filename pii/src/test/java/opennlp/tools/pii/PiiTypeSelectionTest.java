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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests type selection and configuration ownership across configurable detectors. */
class PiiTypeSelectionTest {

  /**
   * One configurable detector with its supported types and a sample for the first type.
   *
   * @param name The detector name.
   * @param constructor The type-selecting constructor.
   * @param types The supported type names.
   * @param sample The text containing a mention of the first type.
   */
  private record Detector(String name, Function<Set<String>, PiiExtractor> constructor,
                          List<String> types, String sample) {
  }

  /**
   * Provides all built-in detectors that accept a type set.
   *
   * @return The detector configurations.
   */
  private static Stream<Detector> detectors() {
    return Stream.of(
        new Detector("cursor", CursorPiiExtractor::new,
            List.of(PiiMention.TYPE_EMAIL, PiiMention.TYPE_PHONE,
                PiiMention.TYPE_IBAN, PiiMention.TYPE_CARD), "jane@example.com"),
        new Detector("network", NetworkPiiExtractor::new,
            List.of(PiiMention.TYPE_IPV4, PiiMention.TYPE_IPV6, PiiMention.TYPE_MAC), "10.1.2.3"),
        new Detector("secrets", SecretsPiiExtractor::new,
            List.of(PiiMention.TYPE_AWS_ACCESS_KEY, PiiMention.TYPE_GITHUB_TOKEN,
                PiiMention.TYPE_JWT, PiiMention.TYPE_URL_CREDENTIAL), "AKIAIOSFODNN7EXAMPLE"),
        new Detector("crypto", CryptoPiiExtractor::new,
            List.of(PiiMention.TYPE_BTC_ADDRESS, PiiMention.TYPE_ETH_ADDRESS),
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"),
        new Detector("us", UsIdentityPiiExtractor::new,
            List.of(PiiMention.TYPE_US_SSN, PiiMention.TYPE_US_ITIN), "123-45-6789"),
        new Detector("eu", EuIdentityPiiExtractor::new,
            List.of(PiiMention.TYPE_UK_NHS, PiiMention.TYPE_DE_STEUER_ID), "943 476 5919"));
  }

  /**
   * Provides invalid sets, including null entries at different iteration positions.
   *
   * @return The detector name, configuration, case index and invalid type set.
   */
  private static Stream<Arguments> invalidSelections() {
    return detectors().flatMap(detector -> {
      final String first = detector.types().getFirst();
      final String second = detector.types().get(1);
      final List<Set<String>> invalid = Arrays.asList(null, Set.of(), Set.of("unknown"),
          Set.of(""), Set.of(" " + first), Set.of(first + " "),
          new LinkedHashSet<>(Arrays.asList((String) null)),
          new LinkedHashSet<>(Arrays.asList(null, first, second)),
          new LinkedHashSet<>(Arrays.asList(first, null, second)),
          new LinkedHashSet<>(Arrays.asList(first, second, null)));
      final List<Arguments> cases = new ArrayList<>();
      for (int i = 0; i < invalid.size(); i++) {
        cases.add(Arguments.of(detector.name(), detector, i, invalid.get(i)));
      }
      return cases.stream();
    });
  }

  /**
   * Provides every non-empty supported subset, with mutable and immutable inputs.
   *
   * @return The detector name, configuration, selected types and input mutability.
   */
  private static Stream<Arguments> validSelections() {
    return detectors().flatMap(detector -> {
      final List<Arguments> cases = new ArrayList<>();
      for (int mask = 1; mask < 1 << detector.types().size(); mask++) {
        final Set<String> selected = new LinkedHashSet<>();
        for (int index = 0; index < detector.types().size(); index++) {
          if ((mask & (1 << index)) != 0) {
            selected.add(detector.types().get(index));
          }
        }
        cases.add(Arguments.of(detector.name(), detector, selected, true));
        cases.add(Arguments.of(detector.name(), detector, Set.copyOf(selected), false));
      }
      return cases.stream();
    });
  }

  /**
   * Checks the documented exception type without changing the supplied set.
   *
   * @param name The detector name.
   * @param detector The detector configuration.
   * @param index The invalid-case identifier.
   * @param selected The invalid type set.
   */
  @ParameterizedTest(name = "{0}, invalid={2}")
  @MethodSource("invalidSelections")
  void testRejectsInvalidTypes(String name, Detector detector, int index, Set<String> selected) {
    final Set<String> original = selected == null ? null : new LinkedHashSet<>(selected);

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> detector.constructor().apply(selected), name + ": " + index);
    Assertions.assertEquals(original, selected);
  }

  /**
   * Checks supported subsets, extraction filtering and ownership of a mutable input set.
   *
   * @param name The detector name.
   * @param detector The detector configuration.
   * @param selected The supported type subset.
   * @param mutable Whether the supplied set can be modified after construction.
   */
  @ParameterizedTest(name = "{0}, selected={2}, mutable={3}")
  @MethodSource("validSelections")
  void testAcceptsSupportedSubsets(String name, Detector detector, Set<String> selected,
      boolean mutable) {
    final Set<String> original = Set.copyOf(selected);
    final PiiExtractor extractor = detector.constructor().apply(selected);
    final List<PiiMention> expected = extractor.extract(detector.sample());

    Assertions.assertEquals(original, selected);
    if (original.contains(detector.types().getFirst())) {
      Assertions.assertEquals(List.of(detector.types().getFirst()),
          expected.stream().map(PiiMention::type).toList(), name);
    } else {
      Assertions.assertTrue(expected.isEmpty(), name);
    }
    if (mutable) {
      selected.clear();
      selected.add("unknown");
      selected.add(null);
    }
    Assertions.assertEquals(expected, extractor.extract(detector.sample()));
  }
}
