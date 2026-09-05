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

package opennlp.tools.namefind;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuxiliaryInfoUtilTest {

  @Test
  public void testGetSeparatorIndex() {
    assertEquals(0, AuxiliaryInfoUtil.getSeparatorIndex("/POStag"));
    assertEquals(1, AuxiliaryInfoUtil.getSeparatorIndex("1/POStag"));
    assertEquals(10, AuxiliaryInfoUtil.getSeparatorIndex("word/stuff/POStag"));
  }

  @Test
  public void testGetSeparatorIndexNoPos() {
    Assertions.assertThrows(RuntimeException.class, () ->
            AuxiliaryInfoUtil.getSeparatorIndex("NOPOStags"));
  }

  @Test
  public void testGetWordPart() {
    assertEquals(" ", AuxiliaryInfoUtil.getWordPart("/POStag"));
    assertEquals("1", AuxiliaryInfoUtil.getWordPart("1/POStag"));
    assertEquals("word", AuxiliaryInfoUtil.getWordPart("word/POStag"));
    assertEquals("word/stuff", AuxiliaryInfoUtil.getWordPart("word/stuff/POStag"));
  }

  @Test
  public void testGetWordParts() {
    String[] results = AuxiliaryInfoUtil.getWordParts(new String[]{"1/A", "234/B", "3456/C", "/D"});
    assertEquals(4, results.length);
    assertEquals("1", results[0]);
    assertEquals("234", results[1]);
    assertEquals("3456", results[2]);
    assertEquals(" ", results[3]);
  }

  @Test
  public void testGetAuxPart() {
    assertEquals("POStag", AuxiliaryInfoUtil.getAuxPart("/POStag"));
    assertEquals("POStag", AuxiliaryInfoUtil.getAuxPart("1/POStag"));
    assertEquals("POStag", AuxiliaryInfoUtil.getAuxPart("word/POStag"));
    assertEquals("POStag", AuxiliaryInfoUtil.getAuxPart("word/stuff/POStag"));
  }

  @Test
  public void testGetAuxParts() {
    String[] results = AuxiliaryInfoUtil.getAuxParts(new String[] {"1/ABC", "234/B", "3456/CD", "/DEFGH"});
    assertEquals(4, results.length);
    assertEquals("ABC", results[0]);
    assertEquals("B", results[1]);
    assertEquals("CD", results[2]);
    assertEquals("DEFGH", results[3]);
  }
}
