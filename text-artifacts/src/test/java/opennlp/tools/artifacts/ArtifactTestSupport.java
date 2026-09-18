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

package opennlp.tools.artifacts;

/** Unicode fixtures shared by artifact detector tests. */
final class ArtifactTestSupport {

  /** Prevents construction of the fixture utility. */
  private ArtifactTestSupport() {
  }

  /**
   * Builds a string from code points.
   *
   * @param codePoints The code points.
   * @return The string.
   */
  static String cp(int... codePoints) {
    return new String(codePoints, 0, codePoints.length);
  }

  /**
   * Encodes ASCII as Unicode tag characters.
   *
   * @param ascii The ASCII text to encode.
   * @param cancel Whether to append CANCEL TAG.
   * @return The tag character sequence.
   */
  static String tags(String ascii, boolean cancel) {
    final StringBuilder tagged = new StringBuilder();
    for (int i = 0; i < ascii.length(); i++) {
      tagged.appendCodePoint(0xE0000 + ascii.charAt(i));
    }
    if (cancel) {
      tagged.appendCodePoint(0xE007F);
    }
    return tagged.toString();
  }
}
