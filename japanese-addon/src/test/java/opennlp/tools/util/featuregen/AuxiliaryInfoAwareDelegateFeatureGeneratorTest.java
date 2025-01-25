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

package opennlp.tools.util.featuregen;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuxiliaryInfoAwareDelegateFeatureGeneratorTest {

  private final String[] testSentence = "w1/pos1 w2/pos2 w3/pos3 w4/pos4".split("\\s+");

  private List<String> features;

  @BeforeEach
  public void setUp() {
    features = new ArrayList<>();
  }

  @Test
  public void testWord() {
    AdaptiveFeatureGenerator featureGenerator = new AuxiliaryInfoAwareDelegateFeatureGenerator(
        new IdentityFeatureGenerator(), false);

    featureGenerator.createFeatures(features, testSentence, 2, null);
    assertEquals(1, features.size());
    assertEquals("w3", features.get(0));
  }

  @Test
  public void testAuxInfo() {
    AdaptiveFeatureGenerator featureGenerator = new AuxiliaryInfoAwareDelegateFeatureGenerator(
        new IdentityFeatureGenerator(), true);

    featureGenerator.createFeatures(features, testSentence, 3, null);
    assertEquals(1, features.size());
    assertEquals("pos4", features.get(0));
  }

  static class IdentityFeatureGenerator implements AdaptiveFeatureGenerator {

    @Override
    public void createFeatures(List<String> features, String[] tokens, int index, String[] previousOutcomes) {
      features.add(tokens[index]);
    }
  }
}
