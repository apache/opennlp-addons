#!/bin/sh
#   Licensed to the Apache Software Foundation (ASF) under one
#   or more contributor license agreements.  See the NOTICE file
#   distributed with this work for additional information
#   regarding copyright ownership.  The ASF licenses this file
#   to you under the Apache License, Version 2.0 (the
#   "License"); you may not use this file except in compliance
#   with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing,
#   software distributed under the License is distributed on an
#   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#   KIND, either express or implied.  See the License for the
#   specific language governing permissions and limitations
#   under the License.

# Runs the Java half of the original comparison using an extracted CLI archive.
# EMBEDDINGS_HOME names the extracted archive; MODEL_DIR names the static model.
set -eu
: "${EMBEDDINGS_HOME:?Set EMBEDDINGS_HOME to the extracted embeddings CLI archive}"
: "${MODEL_DIR:?Set MODEL_DIR to the static model directory}"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
java -cp "$EMBEDDINGS_HOME/lib/*" "$SCRIPT_DIR/EmbedBenchM3.java" \
  "$MODEL_DIR" "$SCRIPT_DIR/sentences.txt" "${VECTORS_OUT:-jvm_vectors.tsv}" 3 5
