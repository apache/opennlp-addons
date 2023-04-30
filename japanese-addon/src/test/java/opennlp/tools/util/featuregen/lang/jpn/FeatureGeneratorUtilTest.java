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

package opennlp.tools.util.featuregen.lang.jpn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FeatureGeneratorUtilTest {

  @Test
  public void test() {
    // digits
    assertEquals("digit", FeatureGeneratorUtil.tokenFeature("12"));
    assertEquals("digit", FeatureGeneratorUtil.tokenFeature("1234"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("abcd234"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("1234-56"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("4/6/2017"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("1,234,567"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("12.34567"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("123(456)7890"));

    // letters
    assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("opennlp"));
    assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("O"));
    assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("OPENNLP"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("A."));
    assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("Mike"));
    assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("somethingStupid"));

    // symbols
    assertEquals("other", FeatureGeneratorUtil.tokenFeature(","));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("."));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("?"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("!"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("#"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("%"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("&"));
  }

  @Test
  public void testJapanese() {
    // Hiragana
    assertEquals("hira", FeatureGeneratorUtil.tokenFeature("そういえば"));
    assertEquals("hira", FeatureGeneratorUtil.tokenFeature("おーぷん・そ〜す・そふとうぇあ"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("あぱっち・そふとうぇあ財団"));

    // Katakana
    assertEquals("kata", FeatureGeneratorUtil.tokenFeature("ジャパン"));
    assertEquals("kata", FeatureGeneratorUtil.tokenFeature("オープン・ソ〜ス・ソフトウェア"));
    assertEquals("other", FeatureGeneratorUtil.tokenFeature("アパッチ・ソフトウェア財団"));
  }
}
