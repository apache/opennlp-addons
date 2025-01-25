/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bwaldvogel.liblinear.Feature;
import de.bwaldvogel.liblinear.FeatureNode;
import de.bwaldvogel.liblinear.Linear;
import de.bwaldvogel.liblinear.Model;
import de.bwaldvogel.liblinear.Parameter;
import de.bwaldvogel.liblinear.Problem;
import de.bwaldvogel.liblinear.SolverType;

import opennlp.tools.ml.AbstractEventTrainer;
import opennlp.tools.ml.model.DataIndexer;
import opennlp.tools.ml.model.MaxentModel;
import opennlp.tools.util.TrainingParameters;

public class LiblinearTrainer extends AbstractEventTrainer {

  private final SolverType solverType;
  private final double c;
  private final double eps;
  private final double p;
  private final int bias;
  
  public LiblinearTrainer(TrainingParameters trainParams) {
    String solverTypeName = trainParams.getStringParameter("solverType", "");
    
    if (solverTypeName != null) {
      try {
        solverType = SolverType.valueOf(trainParams.getStringParameter("solverType", ""));
      }
      catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("solverType [" + solverTypeName + "] is not available!");
      }
    }
    else {
      throw new IllegalArgumentException("solverType needs to be specified!");
    }
    
    c = trainParams.getDoubleParameter("c", 0);
    eps = trainParams.getDoubleParameter("eps", 0);
    p = trainParams.getDoubleParameter("p", 0);
    bias = trainParams.getIntParameter("bias", 0);
  }
  
  private static Problem constructProblem(List<Double> vy, List<Feature[]> vx, int maxIndex, double bias) {
    
    // Initialize problem
    Problem problem = new Problem();
    problem.l = vy.size();
    problem.n = maxIndex;
    problem.bias = bias;

    if (bias >= 0) {
      problem.n++;
    }

    problem.x = new Feature[problem.l][];

    for (int i = 0; i < problem.l; i++) {
      problem.x[i] = vx.get(i);

      if (bias >= 0) {
        problem.x[i][problem.x[i].length - 1] = new FeatureNode(maxIndex + 1, bias);
      }
    }

    problem.y = new double[problem.l];

    for (int i = 0; i < problem.l; i++) {
      problem.y[i] = vy.get(i);
    }
    
    return problem;
  }

  @Override
  public MaxentModel doTrain(DataIndexer indexer) throws IOException {

    List<Double> vy = new ArrayList<>();
    List<Feature[]> vx = new ArrayList<>();

    // outcomes
    int[] outcomes = indexer.getOutcomeList();

    int max_index = 0;
    
    // For each event ...
    for (int i = 0; i < indexer.getContexts().length; i++) {

      int outcome = outcomes[i];
      vy.add((double) outcome);

      int[] features = indexer.getContexts()[i];

      Feature[] x;
      if (bias >= 0) {
        x = new Feature[features.length + 1];
      } else {
        x = new Feature[features.length];
      }

      // for each feature ...
      for (int fi = 0; fi < features.length; fi++) {
        // TODO: SHOULD BE indexer.getNumTimesEventsSeen()[i] and not fi !!!
        x[fi] = new FeatureNode(features[fi] + 1, indexer.getNumTimesEventsSeen()[i]);
      } 

      if (features.length > 0) {
        max_index = Math.max(max_index, x[features.length - 1].getIndex());
      }
      
      vx.add(x);
    }

    Problem problem = constructProblem(vy, vx, max_index, bias);
    Parameter parameter = new Parameter(solverType, c, eps, p);
    
    Model liblinearModel = Linear.train(problem, parameter);

    Map<String, Integer> predMap = new HashMap<>();
    
    String[] predLabels = indexer.getPredLabels();
    for (int i = 0; i < predLabels.length; i++) {
      predMap.put(predLabels[i], i);
    }
    
    return new LiblinearModel(liblinearModel, indexer.getOutcomeLabels(), predMap);
  }

  @Override
  public boolean isSortAndMerge() {
    return true;
  }
}
