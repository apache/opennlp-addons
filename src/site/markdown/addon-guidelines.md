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

# OpenNLP Add-ons Proposal

**Status: Proposed for consensus on dev@opennlp.apache.org.**

This proposal defines how the existing Apache OpenNLP project will develop,
review, publish, and maintain `apache/opennlp-addons`. 

The work is tracked by
[OPENNLP-1924](https://issues.apache.org/jira/browse/OPENNLP-1924).

## Purpose

OpenNLP add-ons should be visible, documented Maven artifacts with a regular
release path. They provide a supported home for useful components that should
not enlarge the core API or dependency set.

## Repository boundary

OpenNLP core contains shared contracts and implementations that fit its
component model without new runtime dependencies or bundled data.

An add-on implements a core contract or supplies an independent component
when it needs additional dependencies, models, dictionaries, datasets, or a
separate release cycle. Add-ons use public OpenNLP APIs and do not duplicate
or bypass them.

The sandbox remains the place for proofs of concept, services, and work that
is not ready for an Apache release.

If an add-on needs a missing reusable contract, the contract is proposed as a
small core change. The implementation remains in add-ons. The
`SubwordTokenizer` contract and its SentencePiece implementation follow this
split and will be the first test of this boundary.

## Publication and compatibility

Each module is published to Maven Central as
`org.apache.opennlp.addons:<artifactId>`. OpenNLP core dependencies continue
to use `org.apache.opennlp:<artifactId>`. Java package names do not change.

**Open question:** does all code need "addons" in the package name?  It might 
cause issues with protected scope boundaries.  But I think it's ok to do

Add-ons release on their own cadence through the normal OpenNLP vote process.
Each release documents the supported OpenNLP version and verifies every module
against that version. A module may extend a public core contract, but it may
not replace that contract with a competing API.

Dependencies and bundled resources must have ASF-compatible licenses and
recorded provenance. Modules that download user-selected resources must
document the source, license responsibility, and integrity checks.

Dependencies should *always* be at a minimum, if at all.  Additional 
dependencies in either core or addons would likely have far more scrutiny 
from a committer than hand coding a solution.

## Contributions and review

Each feature PR contains one module or one focused change. It includes:

- tests for normal and invalid input;
- a short manual page with dependency and usage examples;
- required LICENSE and NOTICE changes; and
- the OpenNLP versions used for verification.

A feature that depends on an unmerged core API stays draft and documents its
local build procedure. It becomes ready after it builds against an Apache
artifact containing that API.

The proposed merge threshold is green CI and one committer approval. A
reviewer may request another review for public API, licensing, security, or
release risk.

## Releases and adoption

An add-ons release needs a volunteer release manager and the normal project
vote. Released modules should be listed in the OpenNLP website and add-ons
manual so users can find them.

Downloads, user reports, and downstream use can support a later proposal to
move an add-on into core. Promotion is a separate consensus decision, not an
automatic result of usage.

## Consensus requested

Project consensus covers:

- the core, add-ons, and sandbox boundary above;
- `org.apache.opennlp.addons` as the Maven group;
- independent add-ons releases using the normal project vote;
- the compatibility and documentation requirements above; and
- one committer approval as the default add-ons review threshold.

Future changes to this agreement are discussed on the DEV list.

## DEV discussion

- [Core and add-ons scope discussion](https://lists.apache.org/thread/vc8mnvfp6rmr5qb2s9y6hw0zpcxm6t68)
- [OpenNLP 3.0.0-M6 release discussion](https://www.mail-archive.com/dev%40opennlp.apache.org/msg09074.html)
