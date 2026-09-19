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

package opennlp.tools.coref;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import opennlp.tools.ml.model.AbstractModel;
import opennlp.tools.ml.model.MaxentModel;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;
import opennlp.tools.util.model.BaseModel;

/**
 * The {@link CorefAnnotator} ranking model: a classifier over anaphor and candidate
 * antecedent pairs with the outcomes link and apart, trained by {@link CorefTrainer}.
 *
 * @since 3.0.0
 */
public class CorefModel extends BaseModel {

  @Serial
  private static final long serialVersionUID = -674127645048281122L;

  private static final String COMPONENT_NAME = "CorefAnnotator";

  private static final String COREF_MODEL_ENTRY_NAME = "coref.model";

  /** The manifest property that marks a model trained by ranking. */
  private static final String RANKING_PROPERTY = "coref.ranking";

  /** The manifest property holding the required contextual token-vector dimension. */
  private static final String TOKEN_VECTOR_DIMENSION_PROPERTY = "coref.tokenVectorDimension";

  /**
   * Initializes a model from a trained pair classifier.
   *
   * @param languageCode The ISO language code of the training data. Must not be
   *                     {@code null} or blank.
   * @param pairModel The pair classifier with the {@code link} and {@code apart}
   *                  outcomes. Must not be {@code null}.
   * @param manifestInfoEntries Additional manifest entries, or {@code null}.
   * @throws IllegalArgumentException Thrown if {@code languageCode} is {@code null} or
   *         blank, {@code pairModel} is {@code null}, or the model does not contain
   *         exactly the {@code link} and {@code apart} outcomes.
   */
  public CorefModel(String languageCode, MaxentModel pairModel,
      Map<String, String> manifestInfoEntries) {
    this(languageCode, pairModel, false, 0, manifestInfoEntries);
  }

  /**
   * Initializes a model from a trained pair classifier or ranker.
   *
   * @param languageCode The ISO language code of the training data. Must not be
   *                     {@code null} or blank.
   * @param pairModel The pair model with the {@code link} and {@code apart} outcomes.
   *                  Must not be {@code null}.
   * @param ranking Whether the model was trained by ranking, so a candidate is linked
   *                when it outscores the new-chain option instead of a threshold.
   * @param manifestInfoEntries Additional manifest entries, or {@code null}.
   * @throws IllegalArgumentException Thrown if {@code languageCode} is {@code null} or
   *         blank, {@code pairModel} is {@code null}, or the model does not contain
   *         exactly the {@code link} and {@code apart} outcomes.
   */
  public CorefModel(String languageCode, MaxentModel pairModel, boolean ranking,
      Map<String, String> manifestInfoEntries) {
    this(languageCode, pairModel, ranking, 0, manifestInfoEntries);
  }

  /**
   * Initializes a model from a trained pair classifier or ranker.
   *
   * @param languageCode The ISO language code of the training data. Must not be
   *                     {@code null} or blank.
   * @param pairModel The pair model with the {@code link} and {@code apart} outcomes.
   *                  Must not be {@code null}.
   * @param ranking Whether the model was trained by ranking.
   * @param tokenVectorDimension The contextual token-vector dimension used in training,
   *                             or zero when training used no token vectors.
   * @param manifestInfoEntries Additional manifest entries, or {@code null}.
   * @throws IllegalArgumentException Thrown if an argument is invalid or the model does
   *         not contain exactly the {@code link} and {@code apart} outcomes.
   */
  public CorefModel(String languageCode, MaxentModel pairModel, boolean ranking,
      int tokenVectorDimension, Map<String, String> manifestInfoEntries) {
    super(COMPONENT_NAME, requireLanguageCode(languageCode), manifestInfoEntries);
    if (pairModel == null) {
      throw new IllegalArgumentException("pairModel must not be null");
    }
    if (tokenVectorDimension < 0) {
      throw new IllegalArgumentException(
          "tokenVectorDimension must not be negative: " + tokenVectorDimension);
    }
    artifactMap.put(COREF_MODEL_ENTRY_NAME, pairModel);
    final Properties manifest = (Properties) artifactMap.get(MANIFEST_ENTRY);
    manifest.setProperty(RANKING_PROPERTY, Boolean.toString(ranking));
    manifest.setProperty(TOKEN_VECTOR_DIMENSION_PROPERTY,
        Integer.toString(tokenVectorDimension));
    checkArtifactMap();
  }

  /** {@return whether the model was trained by ranking} */
  public boolean isRanking() {
    return Boolean.parseBoolean(getManifestProperty(RANKING_PROPERTY));
  }

  /**
   * Returns the contextual token-vector dimension used to train the model.
   *
   * @return The positive required dimension, or zero when the model uses no contextual
   *         token vectors.
   */
  public int getTokenVectorDimension() {
    return Integer.parseInt(getManifestProperty(TOKEN_VECTOR_DIMENSION_PROPERTY));
  }

  /**
   * Reads a model.
   *
   * @param in The stream to read from. Must not be {@code null}.
   * @throws IOException Thrown if reading fails or the model is invalid.
   * @throws IllegalArgumentException Thrown if the model contents are invalid.
   */
  public CorefModel(InputStream in) throws IOException {
    super(COMPONENT_NAME, requireSource(in, "in"));
  }

  /**
   * Reads a model.
   *
   * @param modelFile The file to read. Must not be {@code null}.
   * @throws IOException Thrown if reading fails or the model is invalid.
   * @throws IllegalArgumentException Thrown if the model contents are invalid.
   */
  public CorefModel(File modelFile) throws IOException {
    super(COMPONENT_NAME, requireSource(modelFile, "modelFile"));
  }

  /**
   * Reads a model.
   *
   * @param modelPath The path to read. Must not be {@code null}.
   * @throws IOException Thrown if reading fails or the model is invalid.
   * @throws IllegalArgumentException Thrown if the model contents are invalid.
   */
  public CorefModel(Path modelPath) throws IOException {
    super(COMPONENT_NAME, requireSource(modelPath, "modelPath"));
  }

  /**
   * Validates a model source before it is read.
   *
   * @param source The model source.
   * @param name The source parameter name.
   * @return The validated source.
   * @param <T> The source type.
   * @throws IllegalArgumentException Thrown if {@code source} is {@code null}.
   */
  private static <T> T requireSource(T source, String name) {
    if (source == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return source;
  }

  /**
   * Validates a language code before model construction or training.
   *
   * @param languageCode The language code.
   * @return The validated language code.
   * @throws IllegalArgumentException Thrown if {@code languageCode} is {@code null} or
   *         blank.
   */
  static String requireLanguageCode(String languageCode) {
    if (languageCode == null || StringUtil.isBlank(languageCode)) {
      throw new IllegalArgumentException("languageCode must not be null or blank");
    }
    return languageCode;
  }

  /** {@inheritDoc} Requires the pair classifier entry. */
  @Override
  protected void validateArtifactMap() throws InvalidFormatException {
    super.validateArtifactMap();
    if (!(artifactMap.get(COREF_MODEL_ENTRY_NAME) instanceof AbstractModel pairModel)) {
      throw new InvalidFormatException("Coreference model is incomplete");
    }
    if (pairModel.getNumOutcomes() != 2
        || pairModel.getIndex(SieveResolver.LINK) < 0
        || pairModel.getIndex(SieveResolver.APART) < 0) {
      throw new InvalidFormatException(
          "Coreference model requires exactly the link and apart outcomes");
    }
    final String dimension = getManifestProperty(TOKEN_VECTOR_DIMENSION_PROPERTY);
    try {
      if (dimension == null || Integer.parseInt(dimension) < 0) {
        throw new InvalidFormatException(
            "Coreference model requires a non-negative token-vector dimension");
      }
    } catch (NumberFormatException e) {
      throw new InvalidFormatException(
          "Coreference model has an invalid token-vector dimension: " + dimension, e);
    }
  }

  /** {@return the pair classifier} */
  public MaxentModel getPairModel() {
    return (MaxentModel) artifactMap.get(COREF_MODEL_ENTRY_NAME);
  }
}
