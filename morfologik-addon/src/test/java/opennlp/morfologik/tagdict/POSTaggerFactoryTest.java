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

package opennlp.morfologik.tagdict;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import opennlp.morfologik.builder.POSDictionayBuilderTest;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSSample;
import opennlp.tools.postag.POSTaggerFactory;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.postag.TagDictionary;
import opennlp.tools.postag.WordTagSampleStream;
import opennlp.tools.util.MarkableFileInputStreamFactory;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.TrainingParameters;
import opennlp.tools.util.model.ModelType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the {@link POSTaggerFactory} class.
 */
public class POSTaggerFactoryTest {

  @Test
  public void testPOSTaggerWithCustomFactory() throws Exception {

    Path dictionary = POSDictionayBuilderTest.createMorfologikDictionary();
    POSTaggerFactory inFactory = new MorfologikPOSTaggerFactory();
    TagDictionary inDict = inFactory.createTagDictionary(dictionary.toFile());
    inFactory.setTagDictionary(inDict);

    POSModel posModel = trainPOSModel(ModelType.MAXENT, inFactory);

    POSTaggerFactory factory = posModel.getFactory();
    assertTrue(factory.getTagDictionary() instanceof MorfologikTagDictionary);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

      posModel.serialize(out);
      POSModel fromSerialized = new POSModel(new ByteArrayInputStream(out.toByteArray()));

      factory = fromSerialized.getFactory();
      assertTrue(factory.getTagDictionary() instanceof MorfologikTagDictionary);

      assertEquals(2, factory.getTagDictionary().getTags("casa").length);
    }
  }

  private static ObjectStream<POSSample> createSampleStream() throws IOException {
    File data = new File("target/test-classes/AnnotatedSentences.txt");
    return new WordTagSampleStream(new PlainTextByLineStream(
            new MarkableFileInputStreamFactory(data), StandardCharsets.UTF_8));
  }

  static POSModel trainPOSModel(ModelType type, POSTaggerFactory factory)
      throws IOException {
    return POSTaggerME.train("en", createSampleStream(),
        TrainingParameters.defaultParams(), factory);
  }

}