#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Fetches fastText Common Crawl vectors and trims them to what the POS-tagging
# eval harness can actually see, producing a GloVe-format file loadable via
# -Dopennlp.postag.vectors=<out>:
#
#   - every word form in the UD EWT CoNLL-U files (original and lowercase)
#   - every word in the system lexicon
#   - the most frequent BACKBONE_ROWS rows of the full table, kept as a
#     backbone for the harness's subword pooling of uncovered forms
#
# The full table is 2M rows x 300d (~2.4 GB in memory); the trimmed output is
# a few hundred thousand rows. The fastText header line ("2000000 300") must
# be stripped: opennlp's GloVe parser treats every line as "token floats..."
# and would die on it.
#
# Usage: MODELS_DIR=/work/models/fasttext UD_DIR=/work/ud-data ./fetch-fasttext-vectors.sh
set -euo pipefail

MODELS_DIR="${MODELS_DIR:-/work/models/fasttext}"
UD_DIR="${UD_DIR:-/work/ud-data}"
LEXICON="${LEXICON:-/usr/share/dict/words}"
BACKBONE_ROWS="${BACKBONE_ROWS:-200000}"
URL="https://dl.fbaipublicfiles.com/fasttext/vectors-crawl/cc.en.300.vec.gz"

mkdir -p "$MODELS_DIR"
gz="$MODELS_DIR/cc.en.300.vec.gz"
out="$MODELS_DIR/cc.en.300.ewt.vec"
vocab="$MODELS_DIR/ewt-vocab.txt"

if [ ! -f "$gz" ]; then
  echo "downloading $URL (~4.5 GB, resumable) ..."
  curl -fSL -C - -o "$gz" "$URL"
fi

if [ ! -f "$vocab" ]; then
  echo "building vocabulary from $UD_DIR and $LEXICON ..."
  awk '!/^#/ && NF >= 2 { print $2 }' \
      "$UD_DIR"/en_ewt-ud-train.conllu \
      "$UD_DIR"/en_ewt-ud-dev.conllu \
      "$UD_DIR"/en_ewt-ud-test.conllu > "$vocab.forms"
  cat "$LEXICON" >> "$vocab.forms"
  tr 'A-Z' 'a-z' < "$vocab.forms" > "$vocab.lower"
  sort -u "$vocab.forms" "$vocab.lower" > "$vocab"
  rm -f "$vocab.forms" "$vocab.lower"
fi

echo "vocab: $(wc -l < "$vocab") forms; trimming (streams ~7 GB) ..."
zcat "$gz" | tail -n +2 | awk -v vocab="$vocab" -v backbone="$BACKBONE_ROWS" '
  BEGIN { while ((getline w < vocab) > 0) keep[w] = 1 }
  NR <= backbone { print; seen[$1] = 1; next }
  ($1 in keep) && !($1 in seen) { print }
' > "$out"

echo "wrote $(wc -l < "$out") rows to $out"
