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

import org.junit.Assert;
import org.junit.Test;

public class FeatureGeneratorUtilTest {

  @Test
  public void test() {
    // digits
    Assert.assertEquals("digit", FeatureGeneratorUtil.tokenFeature("12"));
    Assert.assertEquals("digit", FeatureGeneratorUtil.tokenFeature("1234"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("abcd234"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("1234-56"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("4/6/2017"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("1,234,567"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("12.34567"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("123(456)7890"));

    // letters
    Assert.assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("opennlp"));
    Assert.assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("O"));
    Assert.assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("OPENNLP"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("A."));
    Assert.assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("Mike"));
    Assert.assertEquals("alpha", FeatureGeneratorUtil.tokenFeature("somethingStupid"));

    // symbols
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature(","));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("."));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("?"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("!"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("#"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("%"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("&"));
  }

  @Test
  public void testJapanese() {
    // Hiragana
    Assert.assertEquals("hira", FeatureGeneratorUtil.tokenFeature("そういえば"));
    Assert.assertEquals("hira", FeatureGeneratorUtil.tokenFeature("おーぷん・そ〜す・そふとうぇあ"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("あぱっち・そふとうぇあ財団"));

    // Katakana
    Assert.assertEquals("kata", FeatureGeneratorUtil.tokenFeature("ジャパン"));
    Assert.assertEquals("kata", FeatureGeneratorUtil.tokenFeature("オープン・ソ〜ス・ソフトウェア"));
    Assert.assertEquals("other", FeatureGeneratorUtil.tokenFeature("アパッチ・ソフトウェア財団"));
  }
}
