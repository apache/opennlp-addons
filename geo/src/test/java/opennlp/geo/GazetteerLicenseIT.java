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
package opennlp.geo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the provenance accompanying data in the actual runtime artifact. */
class GazetteerLicenseIT {
  @Test
  void packagedDataRetainsLicenseAndAttribution() throws IOException {
    String path = System.getProperty("opennlp.runtimeJar");
    assertNotNull(path, "Maven must provide the packaged runtime JAR");
    try (JarFile jar = new JarFile(path)) {
      assertNotNull(jar.getJarEntry("opennlp/geo/naturalearth-populated-places.txt"));
      assertTrue(read(jar, "META-INF/LICENSE").contains("Apache License"));
      assertTrue(read(jar, "META-INF/LICENSE").contains("Natural Earth \"Populated Places\""),
          "The generated license must retain the bundled data provenance");
      assertTrue(read(jar, "META-INF/NOTICE").contains("The Apache Software Foundation"));
    }
  }

  private static String read(JarFile jar, String name) throws IOException {
    var entry = jar.getJarEntry(name);
    assertNotNull(entry, name);
    try (var input = jar.getInputStream(entry)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
          .map(String::strip).collect(Collectors.joining(" "));
    }
  }
}
