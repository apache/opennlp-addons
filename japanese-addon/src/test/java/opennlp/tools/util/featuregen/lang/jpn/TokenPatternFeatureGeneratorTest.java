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

import java.util.ArrayList;
import java.util.List;

import opennlp.tools.util.featuregen.AdaptiveFeatureGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TokenPatternFeatureGeneratorTest {

  private List<String> features;

  @BeforeEach
  public void setUp() throws Exception {
    features = new ArrayList<>();
  }

  @Test
  public void testSingleToken() {

    String[] testSentence = new String[] {"This", "is", "an", "example", "sentence"};
    final int testTokenIndex = 3;

    AdaptiveFeatureGenerator generator = new TokenPatternFeatureGenerator();

    generator.createFeatures(features, testSentence, testTokenIndex, null);
    assertEquals(1, features.size());
    assertEquals("st=example", features.get(0));
  }

  @Test
  public void testSentence() {

    String[] testSentence = new String[] {"This is an example sentence"};
    final int testTokenIndex = 0;

    AdaptiveFeatureGenerator generator = new TokenPatternFeatureGenerator();

    generator.createFeatures(features, testSentence, testTokenIndex, null);
    assertEquals(14, features.size());
    assertEquals("stn=5", features.get(0));
    assertEquals("pt2=alphaalpha", features.get(1));
    assertEquals("pt3=alphaalphaalpha", features.get(2));
    assertEquals("st=this", features.get(3));
    assertEquals("pt2=alphaalpha", features.get(4));
    assertEquals("pt3=alphaalphaalpha", features.get(5));
    assertEquals("st=is", features.get(6));
    assertEquals("pt2=alphaalpha", features.get(7));
    assertEquals("pt3=alphaalphaalpha", features.get(8));
    assertEquals("st=an", features.get(9));
    assertEquals("pt2=alphaalpha", features.get(10));
    assertEquals("st=example", features.get(11));
    assertEquals("st=sentence", features.get(12));
    assertEquals("pta=alphaalphaalphaalphaalpha", features.get(13));
  }
}
