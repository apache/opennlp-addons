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

public class TokenClassFeatureGeneratorTest {

  private List<String> features;
  private static final String[] TEST_SENTENCE = new String[] {"This", "is", "an", "Example", "sentence"};

  @BeforeEach
  public void setUp() {
    features = new ArrayList<>();
  }

  @Test
  public void testGenWAC() {

    final int testTokenIndex = 3;

    AdaptiveFeatureGenerator generator = new TokenClassFeatureGenerator(true);

    generator.createFeatures(features, TEST_SENTENCE, testTokenIndex, null);

   assertEquals(2, features.size());
   assertEquals("wc=alpha", features.get(0));
   assertEquals("w&c=example,alpha", features.get(1));
  }

  @Test
  public void testNoWAC() {

    final int testTokenIndex = 3;

    AdaptiveFeatureGenerator generator = new TokenClassFeatureGenerator(false);

    generator.createFeatures(features, TEST_SENTENCE, testTokenIndex, null);

   assertEquals(1, features.size());
   assertEquals("wc=alpha", features.get(0));
  }
}
