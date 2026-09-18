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

package opennlp.tools.pii;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the actual shell commands against isolated inputs and a local download substitute. */
@EnabledOnOs(OS.LINUX)
class ReferenceDataGeneratorCommandTest {
  @TempDir
  Path temporary;

  private record Fixture(Path root, Path script, Path output, Path source, Map<String, String> environment) {
  }

  private Fixture fixture(String kind) throws Exception {
    final Path root = Files.createDirectory(temporary.resolve("source tree " + kind));
    final String name = switch (kind) {
      case "iana" -> "IanaTlds";
      case "iban" -> "IbanLengths";
      default -> "PhoneNumberLengths";
    };
    final String scriptName = switch (kind) {
      case "iana" -> "fetch-iana-tlds.sh";
      case "iban" -> "fetch-iban-lengths.sh";
      default -> "fetch-phone-number-lengths.sh";
    };
    final Path script = root.resolve("dev/" + scriptName);
    Files.createDirectories(script.getParent());
    Files.copy(Path.of("../dev/" + scriptName), script);
    final Path generator = root.resolve("pii/src/test/java/opennlp/tools/pii/ReferenceDataGenerator.java");
    Files.createDirectories(generator.getParent());
    Files.copy(Path.of("src/test/java/opennlp/tools/pii/ReferenceDataGenerator.java"), generator);
    final Path output = root.resolve("pii/src/main/java/opennlp/tools/pii/" + name + ".java");
    Files.createDirectories(output.getParent());
    Files.writeString(output, ReferenceDataGeneratorTest.template(kind));
    final Path source = root.resolve("downloaded input");
    Files.writeString(source, ReferenceDataGeneratorTest.validInput(kind));
    final Map<String, String> environment = new java.util.HashMap<>();
    final String variable = switch (kind) {
      case "iana" -> "IANA_TLDS_SOURCE";
      case "iban" -> "IBAN_REGISTRY_SOURCE";
      default -> "PHONE_METADATA_SOURCE";
    };
    environment.put(variable, source.toString());
    environment.put("PHONE_METADATA_REVISION", "0123456789abcdef0123456789abcdef01234567");
    environment.put("PHONE_METADATA_DATE", "2024-02-29");
    return new Fixture(root, script, output, source, environment);
  }

  private int run(Fixture fixture, String... options) throws Exception {
    final java.util.List<String> command = new java.util.ArrayList<>();
    command.add("bash");
    command.add(fixture.script().toString());
    command.addAll(java.util.List.of(options));
    final Path log = fixture.root().resolve("command.log");
    final ProcessBuilder builder = new ProcessBuilder(command).directory(fixture.root().toFile())
        .redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().putAll(fixture.environment());
    final Process process = builder.start();
    if (!process.waitFor(45, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor();
      throw new AssertionError("Generator timed out: " + Files.readString(log));
    }
    return process.exitValue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"iana", "iban", "phone"})
  void writeCheckAndRepeatPreserveSource(String kind) throws Exception {
    final Fixture fixture = fixture(kind);
    final String original = Files.readString(fixture.output());
    final FileTime originalTime = Files.getLastModifiedTime(fixture.output());
    assertEquals(1, run(fixture, "--check"));
    assertEquals(original, Files.readString(fixture.output()));
    assertEquals(originalTime, Files.getLastModifiedTime(fixture.output()));
    assertEquals(0, run(fixture));
    final String generated = Files.readString(fixture.output());
    final FileTime generatedTime = Files.getLastModifiedTime(fixture.output());
    assertFalse(original.equals(generated));
    assertEquals(0, run(fixture, "--check"));
    assertEquals(generated, Files.readString(fixture.output()));
    assertEquals(generatedTime, Files.getLastModifiedTime(fixture.output()));
    assertEquals(0, run(fixture));
    assertEquals(generated, Files.readString(fixture.output()));
    assertEquals(2, run(fixture, "--invalid"));
    assertEquals(generated, Files.readString(fixture.output()));
    Files.writeString(fixture.source(), "broken registry");
    assertTrue(run(fixture) != 0);
    assertEquals(generated, Files.readString(fixture.output()));
  }

  private void mockDownloads(Fixture fixture) throws Exception {
    final Path bin = Files.createDirectory(fixture.root().resolve("bin"));
    final Path curl = bin.resolve("curl");
    Files.writeString(curl, """
        #!/usr/bin/env bash
        set -eu
        printf 'called' >> "$GENERATOR_CURL_LOG"
        if [[ ${GENERATOR_CURL_EXIT:-0} != 0 ]]; then exit "$GENERATOR_CURL_EXIT"; fi
        destination=''
        metadata=false
        while [[ $# -gt 0 ]]; do
          case "$1" in
            -o) shift; destination=$1 ;;
            */commits/*) metadata=true ;;
          esac
          shift
        done
        if $metadata; then
          printf '%s' '{"commit":{"committer":{"date":"2024-02-29T11:22:33Z"}}}' > "$destination"
        else
          cp "$GENERATOR_TEST_SOURCE" "$destination"
        fi
        """);
    assertTrue(curl.toFile().setExecutable(true));
    final Path git = bin.resolve("git");
    Files.writeString(git, "#!/usr/bin/env bash\nprintf '%s\\n' "
        + "'0123456789abcdef0123456789abcdef01234567 refs/heads/master'\n");
    assertTrue(git.toFile().setExecutable(true));
    fixture.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    fixture.environment().put("GENERATOR_CURL_LOG", fixture.root().resolve("curl.log").toString());
    fixture.environment().put("GENERATOR_TEST_SOURCE", fixture.source().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"iana", "phone"})
  void downloadUsesValidatedSourceAndPreservesOutputOnFailure(String kind) throws Exception {
    final Fixture fixture = fixture(kind);
    mockDownloads(fixture);
    fixture.environment().remove(kind.equals("iana") ? "IANA_TLDS_SOURCE" : "PHONE_METADATA_SOURCE");
    final String original = Files.readString(fixture.output());
    fixture.environment().put("GENERATOR_CURL_EXIT", "22");
    assertEquals(22, run(fixture));
    assertEquals(original, Files.readString(fixture.output()));
    fixture.environment().put("GENERATOR_CURL_EXIT", "0");
    assertEquals(0, run(fixture));
    assertEquals(ReferenceDataGeneratorTest.generate(kind, ReferenceDataGeneratorTest.validInput(kind)),
        Files.readString(fixture.output()));
    assertTrue(Files.exists(fixture.root().resolve("curl.log")));
  }

  @Test
  void offlinePhoneRequiresRevisionAndDate() throws Exception {
    final Fixture fixture = fixture("phone");
    fixture.environment().remove("PHONE_METADATA_DATE");
    assertEquals(2, run(fixture));
    fixture.environment().put("PHONE_METADATA_DATE", "2024-02-29");
    fixture.environment().remove("PHONE_METADATA_REVISION");
    assertEquals(2, run(fixture));
  }
}
