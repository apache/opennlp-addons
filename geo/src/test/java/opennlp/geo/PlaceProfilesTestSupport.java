/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
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
package opennlp.geo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Table loading for place-profile tests. */
final class PlaceProfilesTestSupport {

  /** Prevents construction. */
  private PlaceProfilesTestSupport() {
  }

  /**
   * Loads an in-memory UTF-8 table.
   *
   * @param table The table text.
   * @return The loaded profiles.
   * @throws IOException Thrown if the table cannot be loaded.
   */
  static PlaceProfiles load(String table) throws IOException {
    return PlaceProfiles.load(new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8)));
  }
}
