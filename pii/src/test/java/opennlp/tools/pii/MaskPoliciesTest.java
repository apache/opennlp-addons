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

import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;

/**
 * Tests {@link MaskPolicies} and the type-aware {@link Masker} overload it feeds.
 */
public class MaskPoliciesTest {

  private static String mask(String text, String type) {
    return MaskPolicies.forType(type).apply(text);
  }

  @ParameterizedTest
  @CsvSource({
      "'4111 1111 1111 1111', card, '**** **** **** 1111'",
      "'4111-1111-1111-1111', card, '****-****-****-1111'",
      "'4111111111111111', card, '************1111'",
      "'DE89 3704 0044 0532 0130 00', iban, '**** **** **** **** **30 00'",
  })
  void testPaymentTypesKeepTheirLastFourDigits(String value, String type, String expected) {
    Assertions.assertEquals(expected, mask(value, type));
  }

  @ParameterizedTest
  @CsvSource({
      "'jane@example.com', email, '****@*******.***'",
      "'(555) 123-4567', phone, '(***) ***-****'",
      "'192.168.1.20', ipv4, '***.***.*.**'",
      "'123-45-6789', us-ssn, '***-**-****'",
      "'943 476 5919', uk-nhs, '*** *** ****'",
      "'00:1b:44:11:3a:b7', mac, '**:**:**:**:**:**'",
  })
  void testShapeKeepingTypesKeepTheirSeparators(String value, String type, String expected) {
    Assertions.assertEquals(expected, mask(value, type));
  }

  @ParameterizedTest
  @CsvSource({
      "'AKIAIOSFODNN7EXAMPLE', aws-access-key",
      "'ghp_0123456789abcdefghijklmnopqrstuvwxyz', github-token",
      "'0x52908400098527886E0F7030069857D2E4169EE7', eth-address",
      "'user:secret', url-credential",
  })
  void testSecretsAreMaskedWholeIncludingTheirShape(String value, String type) {
    final String masked = mask(value, type);

    Assertions.assertEquals("*".repeat(value.length()), masked);
  }

  /**
   * Verifies that a secret's shape is gone: an underscore or an {@code 0x} left visible
   * would still say which system the value opens.
   */
  @Test
  void testASecretLeavesNoRecognizableMarker() {
    Assertions.assertFalse(mask("ghp_0123456789abcdefghij", PiiMention.TYPE_GITHUB_TOKEN)
        .contains("_"));
    Assertions.assertFalse(mask("0x52908400098527886E0F7030069857D2E4169EE7",
        PiiMention.TYPE_ETH_ADDRESS).contains("x"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"jwt", "btc-address", "some-type-nobody-has-defined", "email "})
  void testUnknownAndSecretTypesAreMaskedWhole(String type) {
    Assertions.assertEquals("*****", mask("ab-cd", type));
  }

  @Test
  void testEveryPolicyIsLengthPreserving() {
    final String value = "4111 1111 1111 1111";

    for (final String type : new String[] {PiiMention.TYPE_CARD, PiiMention.TYPE_EMAIL,
        PiiMention.TYPE_JWT, PiiMention.TYPE_US_SSN, PiiMention.TYPE_CA_SIN,
        PiiMention.TYPE_IMEI, "unknown"}) {
      Assertions.assertEquals(value.length(), mask(value, type).length(), type);
    }
  }

  @Test
  void testUsesAnExplicitMaskCharacter() {
    Assertions.assertEquals("XXXX XXXX XXXX 1111",
        MaskPolicies.forType(PiiMention.TYPE_CARD, 'X').apply("4111 1111 1111 1111"));
  }

  @Test
  void testRejectsAMissingTypeOrASurrogateMask() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> MaskPolicies.forType(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> MaskPolicies.forType(PiiMention.TYPE_CARD, '\ud800'));
    Assertions.assertThrows(IllegalArgumentException.class, () -> MaskPolicies.byType('\ud800'));
  }

  @Test
  void testByTypeChoosesPerMention() {
    final String text = "card 4111 1111 1111 1111 mail jane@example.com "
        + "key AKIAIOSFODNN7EXAMPLE";
    final Document document = new PiiAnnotator(PiiPacks.allStructured())
        .annotate(Document.of(text));

    final String masked = Masker.mask(document, PiiAnnotator.PII, MaskPolicies.byType());
    Assertions.assertEquals("card **** **** **** 1111 mail ****@*******.*** "
        + "key ********************", masked);
  }

  @Test
  void testTypeAwareMaskingIsLengthPreserving() {
    final String text = "card 4111 1111 1111 1111 mail jane@example.com "
        + "key AKIAIOSFODNN7EXAMPLE";
    final Document document = new PiiAnnotator(PiiPacks.allStructured())
        .annotate(Document.of(text));

    Assertions.assertEquals(text.length(),
        Masker.mask(document, PiiAnnotator.PII, MaskPolicies.byType()).length());
  }

  @Test
  void testTypeAwareMaskingUsesTheGivenMaskCharacter() {
    final Document document = new PiiAnnotator(new CursorPiiExtractor())
        .annotate(Document.of("card 4111111111111111"));

    Assertions.assertEquals("card XXXXXXXXXXXX1111",
        Masker.mask(document, PiiAnnotator.PII, MaskPolicies.byType('X')));
  }

  @Test
  void testTypeAwareMaskingRejectsBadArguments() {
    final Document document = new PiiAnnotator(new CursorPiiExtractor())
        .annotate(Document.of("card 4111111111111111"));

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> Masker.mask(null, PiiAnnotator.PII, MaskPolicies.byType()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> Masker.mask(document, null, MaskPolicies.byType()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> Masker.mask(document, PiiAnnotator.PII,
            (Function<PiiMention, MaskPolicy>) null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> Masker.mask(Document.of("no layer"), PiiAnnotator.PII, MaskPolicies.byType()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> Masker.mask(document, PiiAnnotator.PII, mention -> null));
  }
}
