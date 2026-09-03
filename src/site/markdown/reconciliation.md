<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Feature Reconciliation

This inventory compares add-ons modules with OpenNLP core and Kristian
Rickert's active or recently merged pull requests. Status was checked on
2026-09-03. Recheck it before merging a module or publishing a 3.x release.

## morfologik-addon

**Status: removed from the 3.x revamp branch.**

OpenNLP core's `opennlp-morfologik` module contains the same ten production
classes under the same `opennlp.morfologik` packages. Publishing both artifacts
would place competing versions of those classes on an application's classpath.

[OPENNLP-1905, PR #1207](https://github.com/apache/opennlp/pull/1207) merged
on 2026-08-13 and corrected locale-sensitive dictionary lookup in the core
module. The add-ons copy did not contain that correction. Core is the current
source for Morfologik support.

[OPENNLP-1893, PR #1190](https://github.com/apache/opennlp/pull/1190) is
related dictionary work, but it is not a duplicate of Morfologik. It implements
Hunspell affix stemming rather than Morfologik lemmatization and POS dictionary
lookup.

## geoentitylinker-addon

**Status: reconciliation required before a 3.x release.**

The existing module has a Lucene-backed entity linker and defines its own
gazetteer entry and scoring types. [OPENNLP-1879, PR #1154](https://github.com/apache/opennlp/pull/1154)
defines shared `Gazetteer` and `Geocoder` contracts plus newer in-memory,
Natural Earth, GeoNames, Overture, and user-provided implementations.

If the core contracts are accepted, migrate or replace this module so it uses
those contracts. Do not publish two unrelated public gazetteer models without
an explicit compatibility and migration plan.

## jwnl-addon

**Status: reconciliation required before a 3.x release.**

The existing module is an extJWNL-based `Lemmatizer`. [OPENNLP-1880, PR
#1155](https://github.com/apache/opennlp/pull/1155) defines the
`LexicalKnowledgeBase` contract, WN-LMF and WNDB readers, and Morphy
lemmatization. [OPENNLP-1887, PR #1167](https://github.com/apache/opennlp/pull/1167)
adds lexical expansion over that contract.

If the core contract is accepted, retain this module only if extJWNL provides
a useful implementation of that contract. Otherwise retire it in favor of the
new WordNet add-on implementation.

## japanese-addon

**Status: related, with no source duplication found.**

The existing module provides auxiliary-information support and Japanese name
finder feature generators. [OPENNLP-1894, PR #1191](https://github.com/apache/opennlp/pull/1191)
provides lattice tokenization for Japanese, Korean, and Chinese. The two can be
used together, but they do not implement the same operation.

Review the module description and examples after OPENNLP-1894 is resolved. No
code consolidation is currently required.

## subword-addon

**Status: core and add-on split completed locally; core PR remains open.**

[OPENNLP-1885, PR #1165](https://github.com/apache/opennlp/pull/1165) retains
the `SubwordTokenizer` contract, offsets, and dependency-free WordPiece support
in core. SentencePiece model loading and inference are in this add-on. Publish
the add-on after the core contract is available from an Apache snapshot, then
remove the module's temporary `opennlp.version` override.

## liblinear-addon and modelbuilder-addon

**Status: no matching modernization pull request found.**

Keep both in the 3.x revamp inventory. Review their APIs, tests, and manuals as
normal release work.
