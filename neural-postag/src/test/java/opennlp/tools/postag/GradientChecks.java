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

package opennlp.tools.postag;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared fixtures of the finite-difference gradient checks in this package.
 */
final class GradientChecks {

  /** The step of every central finite difference these checks take. */
  static final double EPSILON = 1e-5d;

  private GradientChecks() {
  }

  /**
   * Asserts that an analytic gradient matches its finite-difference estimate. The
   * comparison is relative, floored so that gradients near zero are not held to an
   * unreachable relative bound.
   *
   * @param analytic The gradient the backward pass produced.
   * @param numerical The central finite-difference estimate of the same gradient.
   * @param tolerance The relative bound the difference must stay under.
   * @param what Names the parameter, so a failure identifies which one disagreed.
   */
  static void assertClose(double analytic, double numerical, double tolerance,
      String what) {
    final double scale = Math.max(1e-3d, Math.abs(analytic) + Math.abs(numerical));
    assertTrue(Math.abs(analytic - numerical) / scale < tolerance,
        () -> what + " analytic " + analytic + " vs numerical " + numerical);
  }
}
