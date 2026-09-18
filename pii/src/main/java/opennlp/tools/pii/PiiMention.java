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

import opennlp.tools.util.Span;

/**
 * A mention of personally identifiable information: an original-text {@link Span},
 * a type, and an extractor-defined normalized value.
 *
 * <p>Types are open strings. The constants name the built-in types; custom extractors
 * can use additional names. The reporting extractor defines normalization.</p>
 *
 * @param span The location of the mention in the original text. Must not be
 *             {@code null}.
 * @param type The mention type, for example {@link #TYPE_EMAIL}. Must not be
 *             {@code null} or blank.
 * @param normalized The normalized form. Must not be {@code null} or blank.
 *
 * @since 3.0.0
 */
public record PiiMention(Span span, String type, String normalized) {

  /** An email address. */
  public static final String TYPE_EMAIL = "email";

  /** A phone number. */
  public static final String TYPE_PHONE = "phone";

  /** An International Bank Account Number. */
  public static final String TYPE_IBAN = "iban";

  /** A payment card number. */
  public static final String TYPE_CARD = "card";

  /**
   * An <a href="https://datatracker.ietf.org/doc/html/rfc791">IPv4</a> address in dotted
   * decimal notation.
   */
  public static final String TYPE_IPV4 = "ipv4";

  /**
   * An <a href="https://datatracker.ietf.org/doc/html/rfc4291">IPv6</a> address in the
   * text representation, compressed or full.
   */
  public static final String TYPE_IPV6 = "ipv6";

  /**
   * An <a href="https://standards.ieee.org/products-programs/regauth/">IEEE 802</a> MAC
   * address.
   */
  public static final String TYPE_MAC = "mac";

  /**
   * An <a href="https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_identifiers.html">
   * AWS access key identifier</a>.
   */
  public static final String TYPE_AWS_ACCESS_KEY = "aws-access-key";

  /**
   * A <a href="https://github.blog/2021-04-05-behind-githubs-new-authentication-token-formats/">
   * GitHub access token</a>.
   */
  public static final String TYPE_GITHUB_TOKEN = "github-token";

  /**
   * A <a href="https://datatracker.ietf.org/doc/html/rfc7519">JSON Web Token</a> in
   * compact serialization.
   */
  public static final String TYPE_JWT = "jwt";

  /**
   * A <a href="https://en.bitcoin.it/wiki/Address">Bitcoin</a> address.
   */
  public static final String TYPE_BTC_ADDRESS = "btc-address";

  /**
   * An <a href="https://ethereum.org/en/developers/docs/accounts/">Ethereum</a> account
   * address.
   */
  public static final String TYPE_ETH_ADDRESS = "eth-address";

  /**
   * An <a href="https://en.wikipedia.org/wiki/ABA_routing_transit_number">ABA routing
   * transit number</a> of a United States financial institution.
   */
  public static final String TYPE_ABA_ROUTING = "aba-routing";

  /**
   * A credential embedded in a URL's userinfo component, as described by
   * <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-3.2.1">RFC 3986</a>.
   */
  public static final String TYPE_URL_CREDENTIAL = "url-credential";

  /**
   * A <a href="https://www.ssa.gov/employer/randomization.html">United States Social
   * Security number</a>.
   */
  public static final String TYPE_US_SSN = "us-ssn";

  /**
   * A <a href="https://www.irs.gov/individuals/individual-taxpayer-identification-number">
   * United States Individual Taxpayer Identification Number</a>.
   */
  public static final String TYPE_US_ITIN = "us-itin";

  /**
   * A <a href="https://www.datadictionary.nhs.uk/attributes/nhs_number.html">United
   * Kingdom NHS number</a>.
   */
  public static final String TYPE_UK_NHS = "uk-nhs";

  /**
   * A German <a href="https://www.bzst.de/DE/Privatpersonen/SteuerlicheIdentifikationsnummer/steuerlicheidentifikationsnummer_node.html">
   * steuerliche Identifikationsnummer</a>, the personal tax identifier.
   */
  public static final String TYPE_DE_STEUER_ID = "de-steuer-id";

  /**
   * A GSMA
   * <a href="https://imeidb.gsma.com/imei/resources/documents/TS.06-v22.0.pdf">
   * International Mobile Equipment Identity</a> identifying a mobile device.
   */
  public static final String TYPE_IMEI = "imei";

  /**
   * A Canadian
   * <a href="https://www.canada.ca/en/employment-social-development/services/sin.html">
   * Social Insurance Number</a>.
   */
  public static final String TYPE_CA_SIN = "ca-sin";

  /**
   * Validates the mention.
   *
   * @throws IllegalArgumentException Thrown if {@code span} is {@code null}, or
   *         {@code type} or {@code normalized} is {@code null} or blank.
   */
  public PiiMention {
    if (span == null) {
      throw new IllegalArgumentException("span must not be null");
    }
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type must not be null or blank");
    }
    if (normalized == null || normalized.isBlank()) {
      throw new IllegalArgumentException("normalized must not be null or blank");
    }
  }
}
