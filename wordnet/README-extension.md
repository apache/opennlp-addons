<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# WN-LMF lexicon extension composition

This feature composes a caller-resolved WN-LMF `LexiconExtension` over its exact base lexicon while validating versions, identifiers, external references, cycles, and nesting limits.

## Human definition

It lets an application add its own entries, senses, synsets, and relations to an existing WordNet without modifying or replacing the original lexicon.

## Prior art and comparable products

- The [Global WordNet Association WN-LMF schemas](https://globalwordnet.github.io/schemas/) define the exchange format and extension model implemented by this feature.

## Module

The WordNet API, WN-LMF reader, and extension composition live together in the
addons `wordnet` module. Build instructions are in [README.md](README.md).
