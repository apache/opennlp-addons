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

package opennlp.morfologik.builder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import morfologik.stemming.DictionaryMetadata;
import opennlp.morfologik.lemmatizer.MorfologikLemmatizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class POSDictionaryBuilderTest {

  @Test
  public void testBuildDictionary() throws Exception {
    
    Path output = createMorfologikDictionary();
    MorfologikLemmatizer ml = new MorfologikLemmatizer(output);

    assertNotNull(ml);
  }
  
  public static Path createMorfologikDictionary() throws Exception {
    Path tabFilePath = File.createTempFile(
        POSDictionaryBuilderTest.class.getName(), ".txt").toPath();
    Path infoFilePath = DictionaryMetadata.getExpectedMetadataLocation(tabFilePath);
    
    Files.copy(POSDictionaryBuilderTest.class.getResourceAsStream(
        "/dictionaryWithLemma.txt"), tabFilePath, StandardCopyOption.REPLACE_EXISTING);
    Files.copy(POSDictionaryBuilderTest.class.getResourceAsStream(
        "/dictionaryWithLemma.info"), infoFilePath, StandardCopyOption.REPLACE_EXISTING);
    
    MorfologikDictionaryBuilder builder = new MorfologikDictionaryBuilder();
    
    return builder.build(tabFilePath);
  }

}
