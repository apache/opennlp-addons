#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Regenerates IbanLengths from the live SWIFT ISO 13616 registry TXT file.
# Set IBAN_REGISTRY_SOURCE to reproduce an already downloaded snapshot.
set -euo pipefail

if [[ $# -gt 1 || ($# -eq 1 && $1 != "--check") ]]; then
  echo "Usage: $0 [--check]" >&2
  exit 2
fi

MODE="${1:-write}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/pii/src/main/java/opennlp/tools/pii/IbanLengths.java"
URL="https://www.swift.com/swift-resource/11971/download?language=en"
TMP_SOURCE="$(mktemp)"
TMP_OUT="$(mktemp "$OUT.XXXXXX")"
trap 'rm -f "$TMP_SOURCE" "$TMP_OUT"' EXIT

if [[ -n "${IBAN_REGISTRY_SOURCE:-}" ]]; then
  cp "$IBAN_REGISTRY_SOURCE" "$TMP_SOURCE"
else
  curl --connect-timeout 20 --max-time 120 --retry 3 --retry-all-errors -fsSL \
      "$URL" -o "$TMP_SOURCE"
fi

cp -p "$OUT" "$TMP_OUT"
java "$ROOT/pii/src/test/java/opennlp/tools/pii/ReferenceDataGenerator.java" iban \
    "$TMP_SOURCE" "$TMP_OUT"

if [[ "$MODE" == "--check" ]]; then
  if ! diff -u "$OUT" "$TMP_OUT"; then
    echo "SWIFT IBAN registry snapshot is stale; run dev/fetch-iban-lengths.sh" >&2
    exit 1
  fi
  echo "SWIFT IBAN registry snapshot is current"
else
  mv "$TMP_OUT" "$OUT"
  echo "Regenerated $OUT"
fi
