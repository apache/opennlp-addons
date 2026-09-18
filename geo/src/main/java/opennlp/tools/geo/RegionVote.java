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

package opennlp.tools.geo;

import opennlp.tools.commons.ThreadSafe;
import opennlp.tools.util.StringUtil;

/**
 * One row of a document's region ballot: a country and the share of the document's
 * location evidence that points at it. The shares of one ballot sum to one, so a
 * share is also the confidence in that country.
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @param countryCode The
 *                    <a href="https://www.iso.org/iso-3166-country-codes.html">ISO
 *                    3166-1</a> alpha-2 country code. Must not be {@code null} or blank.
 * @param share The fraction of the document's location evidence pointing at the
 *              country. Must be in {@code (0, 1]}.
 *
 * @since 3.0.0
 */
@ThreadSafe
public record RegionVote(String countryCode, double share) {

  /**
   * Creates a vote.
   *
   * @throws IllegalArgumentException Thrown if {@code countryCode} is {@code null} or
   *         blank, or {@code share} is not in {@code (0, 1]}, including {@code NaN}.
   */
  public RegionVote {
    if (countryCode == null || StringUtil.isBlank(countryCode)) {
      throw new IllegalArgumentException("countryCode must not be null or blank");
    }
    if (!(share > 0.0 && share <= 1.0)) {
      throw new IllegalArgumentException("share must be in (0, 1], got: " + share);
    }
  }
}
