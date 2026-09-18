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

import java.util.Set;

/**
 * Factories for contact, payment, network, credential, wallet, national and device
 * detectors.
 *
 * <p>Each factory creates a stateless extractor that can be shared between threads.
 * Use {@link CompositePiiExtractor} to combine packs. Overlapping detections are
 * retained. Equal spans are ordered by {@link PiiTypePriority}, then pack order
 * when ranks are equal.</p>
 *
 * <p>Packs are opt-in and do not change the default {@link CursorPiiExtractor} selection.</p>
 *
 * @since 3.0.0
 */
public final class PiiPacks {

  /** Prevents construction. */
  private PiiPacks() {
  }

  /**
   * Payment data: payment card numbers, IBANs, and ABA routing numbers.
   *
   * @return A new extractor.
   */
  public static PiiExtractor payment() {
    return new CompositePiiExtractor(
        new CursorPiiExtractor(Set.of(PiiMention.TYPE_IBAN, PiiMention.TYPE_CARD)),
        new BankingPiiExtractor());
  }

  /**
   * Contact data: email addresses and phone numbers.
   *
   * @return A new extractor.
   */
  public static PiiExtractor contact() {
    return new CursorPiiExtractor(Set.of(PiiMention.TYPE_EMAIL, PiiMention.TYPE_PHONE));
  }

  /**
   * Network addresses: IPv4, IPv6, and MAC addresses.
   *
   * @return A new extractor.
   */
  public static PiiExtractor network() {
    return new NetworkPiiExtractor();
  }

  /**
   * Credentials: AWS access keys, GitHub tokens, JSON Web Tokens, and credentials embedded
   * in a URL.
   *
   * @return A new extractor.
   */
  public static PiiExtractor secrets() {
    return new SecretsPiiExtractor();
  }

  /**
   * Wallet addresses: Bitcoin and Ethereum.
   *
   * @return A new extractor.
   */
  public static PiiExtractor crypto() {
    return new CryptoPiiExtractor();
  }

  /**
   * United States national identifiers: Social Security numbers and Individual Taxpayer
   * Identification Numbers.
   *
   * @return A new extractor.
   */
  public static PiiExtractor usIdentity() {
    return new UsIdentityPiiExtractor();
  }

  /**
   * European national identifiers: United Kingdom NHS numbers and German tax identification
   * numbers.
   *
   * @return A new extractor.
   */
  public static PiiExtractor euIdentity() {
    return new EuIdentityPiiExtractor();
  }

  /**
   * Canadian national identifiers: context-labeled Social Insurance Numbers.
   *
   * @return A new extractor.
   */
  public static PiiExtractor caIdentity() {
    return new CaIdentityPiiExtractor();
  }

  /**
   * Device identifiers: context-labeled International Mobile Equipment Identities.
   *
   * @return A new extractor.
   */
  public static PiiExtractor device() {
    return new DevicePiiExtractor();
  }

  /**
   * Enables all structured detectors, retaining overlapping detections from all packs.
   * Use a specific pack to limit the enabled types.
   *
   * @return A new extractor.
   */
  public static PiiExtractor allStructured() {
    return new CompositePiiExtractor(
        new CursorPiiExtractor(),
        new SecretsPiiExtractor(),
        new CryptoPiiExtractor(),
        new NetworkPiiExtractor(),
        new UsIdentityPiiExtractor(),
        new EuIdentityPiiExtractor(),
        new CaIdentityPiiExtractor(),
        new DevicePiiExtractor(),
        new BankingPiiExtractor());
  }
}
