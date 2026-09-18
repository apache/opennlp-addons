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

# Regenerates PhoneNumberLengths from libphonenumber's live metadata.
# Set PHONE_METADATA_SOURCE, PHONE_METADATA_REVISION, and PHONE_METADATA_DATE
# together to reproduce a downloaded snapshot without network access.
set -euo pipefail

if [[ $# -gt 1 || ($# -eq 1 && $1 != "--check") ]]; then
  echo "Usage: $0 [--check]" >&2
  exit 2
fi

MODE="${1:-write}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/pii/src/main/java/opennlp/tools/pii/PhoneNumberLengths.java"
TMP_SOURCE="$(mktemp)"
TMP_OUT="$(mktemp "$OUT.XXXXXX")"
TMP_METADATA="$(mktemp)"
trap 'rm -f "$TMP_SOURCE" "$TMP_OUT" "$TMP_METADATA"' EXIT

if [[ -n "${PHONE_METADATA_SOURCE:-}" ]]; then
  if [[ -z "${PHONE_METADATA_REVISION:-}" || -z "${PHONE_METADATA_DATE:-}" ]]; then
    echo "Offline generation requires PHONE_METADATA_REVISION and PHONE_METADATA_DATE" >&2
    exit 2
  fi
  cp "$PHONE_METADATA_SOURCE" "$TMP_SOURCE"
  REVISION="$PHONE_METADATA_REVISION"
  SNAPSHOT_DATE="$PHONE_METADATA_DATE"
else
  REPOSITORY="https://github.com/google/libphonenumber.git"
  REVISION="$(git ls-remote "$REPOSITORY" refs/heads/master | awk '{print $1}')"
  if [[ ${#REVISION} -ne 40 || "$REVISION" == *[!0-9a-f]* ]]; then
    echo "Could not resolve the libphonenumber master revision" >&2
    exit 1
  fi
  URL="https://raw.githubusercontent.com/google/libphonenumber/$REVISION/resources/PhoneNumberMetadata.xml"
  curl --connect-timeout 20 --max-time 120 --retry 3 --retry-all-errors -fsSL \
      "$URL" -o "$TMP_SOURCE"
  curl --connect-timeout 20 --max-time 120 --retry 3 --retry-all-errors -fsSL \
      "https://api.github.com/repos/google/libphonenumber/commits/$REVISION" -o "$TMP_METADATA"
  SNAPSHOT_DATE="$(java "$ROOT/pii/src/test/java/opennlp/tools/pii/ReferenceDataGenerator.java" \
      commit-date "$TMP_METADATA")"
fi

cp -p "$OUT" "$TMP_OUT"
java "$ROOT/pii/src/test/java/opennlp/tools/pii/ReferenceDataGenerator.java" phone \
    "$TMP_SOURCE" "$TMP_OUT" "$REVISION" "$SNAPSHOT_DATE"

if [[ "$MODE" == "--check" ]]; then
  if ! diff -u "$OUT" "$TMP_OUT"; then
    echo "Phone metadata snapshot is stale; run dev/fetch-phone-number-lengths.sh" >&2
    exit 1
  fi
  echo "Phone metadata snapshot is current"
else
  mv "$TMP_OUT" "$OUT"
  echo "Regenerated $OUT"
fi
