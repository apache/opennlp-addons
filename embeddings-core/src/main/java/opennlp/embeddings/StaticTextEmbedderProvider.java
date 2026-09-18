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
package opennlp.embeddings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import opennlp.tools.embeddings.TextEmbedder;
import opennlp.tools.embeddings.TextEmbedderProvider;
import opennlp.tools.util.ext.ProviderSpec;

/**
 * Loads a static model directory as a {@link TextEmbedder}. The provider name is
 * {@code static}. Provider discovery accepts local paths without an {@code .onnx} suffix
 * and with no options. Creating a model checks the directory layout and reads its
 * configuration. The provider has no native runtime, so it is always available.
 *
 * @since 3.0.0
 */
public final class StaticTextEmbedderProvider implements TextEmbedderProvider {

  private static final String NAME = "static";

  /** Creates a factory without opening a model. */
  public StaticTextEmbedderProvider() {
  }

  /** {@inheritDoc} */
  @Override
  public String name() {
    return NAME;
  }

  /** {@inheritDoc} */
  @Override
  public boolean supports(ProviderSpec spec) {
    if (spec == null) {
      throw new IllegalArgumentException("spec must not be null");
    }
    return spec.path().isPresent() && !spec.locationEndsWith(".onnx") && spec.hasOnlyOptions();
  }

  /** {@inheritDoc} */
  @Override
  public TextEmbedder create(ProviderSpec spec) throws IOException {
    if (!supports(spec)) {
      throw new IllegalArgumentException("spec must name a local static model with no options");
    }
    return load(spec.path().orElseThrow(), spec.options());
  }

  /**
   * Checks the files in a model directory. Unlike {@link #supports(ProviderSpec)},
   * this convenience method inspects the file system. The tokenizer layout is checked
   * by {@link #load(Path, Map)}. The directory must hold exactly one float or
   * quantized matrix file and a configuration file.
   *
   * @param model The model directory.
   * @param options An empty option map.
   * @return Whether the directory contains the required model and configuration files.
   */
  public boolean supports(Path model, Map<String, String> options) {
    return model != null && options != null && options.isEmpty()
        && Files.isDirectory(model)
        && (Files.isRegularFile(model.resolve(ModelFileNames.SAFETENSORS))
            ^ Files.isRegularFile(model.resolve(ModelFileNames.QUANTIZED)))
        && Files.isRegularFile(model.resolve(ModelFileNames.CONFIG));
  }

  /**
   * Loads a static model directory.
   *
   * @param model The model directory.
   * @param options An empty option map.
   * @return The loaded model.
   * @throws IllegalArgumentException If the path is null or options are null or nonempty.
   * @throws IOException If model files cannot be read.
   */
  public TextEmbedder load(Path model, Map<String, String> options) throws IOException {
    if (model == null) {
      throw new IllegalArgumentException("model must not be null");
    }
    if (options == null || !options.isEmpty()) {
      throw new IllegalArgumentException("options must be non-null and empty");
    }
    return StaticEmbeddingModel.load(model);
  }
}
