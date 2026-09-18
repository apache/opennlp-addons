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
package opennlp.embeddings.cmdline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the packaged launchers with only the dependencies shipped in the archive. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class DistributionIT {

  @Test
  void launchesFromDirectoryWithSpaces(@TempDir Path temp) throws Exception {
    final Path archive;
    try (var files = Files.list(Path.of("target"))) {
      archive = files.filter(file -> file.getFileName().toString().endsWith("-bin.zip"))
          .findFirst().orElseThrow(() -> new AssertionError("Missing CLI binary archive"));
    }
    final Path install = Files.createDirectories(temp.resolve("installation with spaces"));
    unpack(archive, install);
    final Path home;
    try (var files = Files.list(install)) {
      home = files.filter(Files::isDirectory).findFirst().orElseThrow();
    }
    assertTrue(Files.isRegularFile(home.resolve("bin/embeddings.bat")));
    assertTrue(Files.isRegularFile(home.resolve("bin/opennlp-embeddings.bat")));
    assertTrue(Files.isRegularFile(home.resolve("LICENSE")));
    assertTrue(Files.isRegularFile(home.resolve("NOTICE")));
    final String listing = run(home, temp, "opennlp-embeddings", 0);
    assertTrue(listing.contains("AssembleModel"), listing);
    assertTrue(listing.contains("DistillModel"), listing);
    final String help = run(home, temp, "embeddings", 0, "DistillModel", "help");
    assertTrue(help.contains("-teacher"), help);
    assertTrue(help.contains("-pcaDims"), help);
    final String error = run(home, temp, "opennlp-embeddings", 1, "UnknownCommand");
    assertTrue(error.contains("UnknownCommand"), error);
  }

  /** Extracts the locally built archive, rejecting entries outside the installation. */
  private void unpack(Path archive, Path install) throws IOException {
    try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        final Path target = install.resolve(entry.getName()).normalize();
        assertTrue(target.startsWith(install), entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(zip, target);
        }
      }
    }
  }

  /** Runs a launcher outside the build tree and returns its combined console output. */
  private String run(Path home, Path temp, String launcher, int exitCode, String... arguments)
      throws Exception {
    final var command = new java.util.ArrayList<String>();
    command.add("sh");
    command.add(home.resolve("bin").resolve(launcher).toString());
    command.addAll(java.util.List.of(arguments));
    final Path output = Files.createTempFile(temp, "cli-", ".log");
    final var builder = new ProcessBuilder(command).directory(temp.toFile())
        .redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().remove("CLASSPATH");
    builder.environment().remove("JAVACMD");
    builder.environment().remove("OPENNLP_HOME");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    final Process process = builder.start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI process timed out");
      final String text = Files.readString(output);
      assertEquals(exitCode, process.exitValue(), text);
      return text;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
