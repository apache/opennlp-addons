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

Add-ons Proposal (temporary)
===========

> [!IMPORTANT]
> Everything under `dev-TEMP/` is working material for the dev list discussion, not documentation. The whole directory is deleted before an add-ons release.

This text used to ship as a chapter of the add-ons manual. It is a position being argued, so it does not belong in a user manual. The durable rules it contained now live in the manual chapter "Contributing an Add-on"; what is left here is the part that needs project consensus.

Tracked by [OPENNLP-1924](https://issues.apache.org/jira/browse/OPENNLP-1924). Status: proposed for consensus on dev@opennlp.apache.org.

## Purpose

OpenNLP add-ons should be visible, documented Maven artifacts with a regular release path. They give a supported home to useful components that should not enlarge the core API or its dependency set.

## Consensus requested

- The boundary between core, add-ons, and the sandbox, as described in the manual chapter "Contributing an Add-on".
- `org.apache.opennlp.addons` as the Maven group for add-on artifacts.
- Independent add-ons releases through the normal project vote.
- The compatibility and documentation requirements in that same chapter.
- A default review threshold for this repository. See below.

Future changes to whatever is agreed here are discussed on the dev list.

## Open question: review threshold

Originally proposed: green CI and one committer approval, with a reviewer free to request a second review for public API, licensing, security, or release risk.

The current configuration is asymmetric with core, which is worth settling deliberately rather than by default:

| | `apache/opennlp` | `apache/opennlp-addons` |
| --- | --- | --- |
| Required approving reviews on the default branch | 1 | none |
| Required status checks | `asf-allowlist-check` | none |
| Force push and deletion | restricted | restricted |
| Workflows | includes `asf-allowlist-check` | `Java CI`, `Dependency licenses` |

So a committer can currently push straight to `main` here. Matching core is a single `.asf.yaml` edit, plus porting the allowlist-check workflow if the status check is wanted too.

## Open question: security

The add-ons repository has no `SECURITY.md`, and the README has no security section. Core publishes a security policy and an explicit trust model in [SECURITY.md](https://github.com/apache/opennlp/blob/main/SECURITY.md): text under analysis is untrusted, models and dictionaries are trusted but hardened, configuration is fully trusted, with a list of known non-findings.

Add-ons need a stated position on how they relate to that model, since an add-on may bundle data, download user-selected resources, or delegate to native code. Core's policy already speaks to the last case for ONNX Runtime.

## Promotion into core

Downloads, user reports, and downstream use can support a later proposal to move an add-on into core. Promotion stays a separate consensus decision and is not an automatic result of adoption.

## Prior dev list discussion

- [Core and add-ons scope](https://lists.apache.org/thread/vc8mnvfp6rmr5qb2s9y6hw0zpcxm6t68)
- [OpenNLP 3.0.0-M6 release](https://www.mail-archive.com/dev%40opennlp.apache.org/msg09074.html)
