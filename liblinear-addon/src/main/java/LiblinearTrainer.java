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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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
import de.bwaldvogel.liblinear.Train;
import opennlp.tools.ml.AbstractEventTrainer;
import opennlp.tools.ml.model.DataIndexer;
import opennlp.tools.ml.model.MaxentModel;

public class LiblinearTrainer extends AbstractEventTrainer {

  public LiblinearTrainer(Map<String, String> trainParams,
      Map<String, String> reportMap) {
    super(trainParams, reportMap);
    
    //  TODO: Extract solver type here
    // depending on it, extract parameters
    // e.g. bias, C, eps for L1_LR
    
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
        problem.x[i][problem.x[i].length - 1] = new FeatureNode(max_index + 1, bias);
      }
    }

    problem.y = new double[problem.l];

    for (int i = 0; i < problem.l; i++) {
      problem.y[i] = vy.get(i).doubleValue();
    }
    
    return problem;
  }

  @Override
  public MaxentModel doTrain(DataIndexer indexer) throws IOException {

    List<Double> vy = new ArrayList<Double>();
    List<Feature[]> vx = new ArrayList<Feature[]>();

    // outcomes
    int outcomes[] = indexer.getOutcomeList();

    final int bias = 0;
    
    int max_index = 0;
    
    // For each event ...
    for (int i = 0; i < indexer.getContexts().length; i++) {

      int outcome = outcomes[i];
      vy.add(Double.valueOf(outcome));

      int features[] = indexer.getContexts()[i];

      Feature[] x;
      if (bias >= 0) {
        x = new Feature[features.length + 1];
      } else {
        x = new Feature[features.length];
      }

      // for each feature ...
      for (int fi = 0; fi < features.length; fi++) {
        x[fi] = new FeatureNode(features[fi] + 1, indexer.getNumTimesEventsSeen()[fi]);
      } 

      if (features.length > 0) {
        max_index = Math.max(max_index, x[features.length - 1].getIndex());
      }
      
      vx.add(x);
    }

    Problem problem = constructProblem(vy, vx, max_index, bias);
    Parameter parameter = new Parameter(SolverType.L1R_LR, 1d, 0.001d);
    
    Model liblinearModel = Linear.train(problem, parameter);

    Map<String, Integer> predMap = new HashMap<String, Integer>();
    
    String predLabels[] = indexer.getPredLabels();
    for (int i = 0; i < predLabels.length; i++) {
      predMap.put(predLabels[i], i);
    }
    
    return new LiblinearModel(liblinearModel, indexer.getOutcomeLabels(), predMap);
  }

  @Override
  public boolean isSortAndMerge() {
    return true;
  }

  public static void main(String[] args) throws Exception {

    File file = File.createTempFile("svm", "test");
    file.deleteOnExit();

    Collection<String> lines = new ArrayList<String>();
    lines.add("1 1:1 3:1 4:1 6:1");
    lines.add("2 2:1 3:1 5:1 7:1");
    lines.add("1 3:1 5:1");
    lines.add("1 1:1 4:1 7:1");
    lines.add("2 4:1 5:1 7:1");
    lines.add("1 1:1 4:1 7:1");
    lines.add("2 4:1 5:1 7:1");

    BufferedWriter writer = new BufferedWriter(new FileWriter(file));
    try {
      for (String line : lines)
        writer.append(line).append("\n");
    } finally {
      writer.close();
    }

    Train train = new Train();

    Problem problem = train.readProblem(file, 0d);

    Model model = Linear.train(problem, new Parameter(SolverType.L1R_LR, 10d,
        0.02d));
    
    double result = Linear.predict(model, new Feature[]{new FeatureNode(4, 1d), new FeatureNode(1, 1d)});
    double outcomes[] = new double[2];
    double result2 = Linear.predictProbability(model, new Feature[]{new FeatureNode(4, 1d), new FeatureNode(1, 1d)}, outcomes);

    System.out.println(result);
  }
}
