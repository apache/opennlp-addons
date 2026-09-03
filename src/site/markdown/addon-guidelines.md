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

# Add-ons Proposal (DRAFT)

**Status: DRAFT. This page records proposals, not decisions.**

This document is a working capture of the core/addons discussion on the dev
list, not a policy. It exists so we can edit wording in the open instead of
re-arguing from memory in email threads.

**Committers: edit this file as you please.** Change wording, add sections,
strike things out, propose alternatives. Every edit is a proposal, not a
decision. These notes become final only after consensus on the dev list
(dev@opennlp.apache.org). When consensus forms on a section, we will mark that
section as agreed and link the thread.

Tracked under [OPENNLP-1924](https://issues.apache.org/jira/browse/OPENNLP-1924)
("ADDONs - refactor + publish setup").

## What this document is for

The addons repository (apache/opennlp-addons) is being revamped: published to
Maven Central, released on its own cadence, and given written boundaries so
that routing a contribution to core, addons, or sandbox stops being a
case-by-case debate. This file collects what has been said so far, the
questions that are open, and the proposals on the table.

## Positions stated on the dev list so far

Source threads:
"[DISCUSS] Drawing a scope line between opennlp core and addons" (Aug 25 to
Sep 1, 2026) and "[DISCUSS] 3.0.0-M6 release" (Sep 1, 2026).

### Richard Zowalla (rzo1), Aug 25: the opening proposal

A contribution belongs in **core** if it:

- fits the existing abstractions / component model,
- introduces no new heavy runtime dependencies, and
- bundles no data files (models, dictionaries, datasets).

A contribution belongs in **addons** (or sandbox) if it:

- brings its own dependencies,
- bundles or downloads data, or
- introduces an independent component family.

Two conditions attached to the proposal:

1. **"Addons" must not mean "graveyard".** Redirecting work there is only
   good if addons actually release on a predictable cadence. The proposal
   includes a commitment to regular addons releases.
2. **The line must be permeable.** Real adoption (downloads, traffic, user
   questions) is the signal to promote an addons module into core later.
   Starting in addons is incubation with a lower review bar, not a rejection.

The stated motivation: review capacity is the bottleneck; a handful of
reviewers cannot review ~200k added lines in core in a reasonable time frame.

### Jeff Zemerick (jzemerick), Aug 25

- Raised whether expected usage should factor into the core/addons decision.
- Strong agreement that addons must not be a graveyard; suggested better
  promotion of addons on the website and inside OpenNLP itself.
- Noted Lucene renamed its sandbox to "contrib" and incorporated it into the main
  repository; flagged single-repository as a possible later discussion.
- Observed that opennlp-addons is not published to Maven Central and sees
  roughly one code contribution per year, suggesting people do not know about
  it. Conclusion: the first best step is to give opennlp-addons some TLC and
  get it publishing its own releases.

### Kristian Rickert (krickert), Aug 25 to Sep 1

- +1 for addons: "a good middle ground between core and sandbox", fine with
  it as long as the jars are pushed to Maven Central so they are actually
  usable.
- Draft PRs on core should be read as work in progress, not as queue load.
- Dependencies: none of the open or draft PRs introduce new dependencies, and
  a "zero new dependencies in core" rule is acceptable. Where addons do need
  a dependency, use it sparingly and behind an SPI service loader over an
  interface, so adopting it is a startup-pluggable decision (the Quarkus /
  Micronaut style: pure-JDK default implementation, optional providers).
- The gRPC server PR brings Netty and was always intended as a separate
  build; it is a PoC and does not belong in core.
- Downloads and model hosting should follow LEGAL-732; the verified
  downloader (OPENNLP-1909) covers new models.
- Routing opinion on the shared foundations: Document Shape (OPENNLP-1888)
  and SentencePiece (OPENNLP-1885) are API/interface features and strengthen
  the addons argument by giving addons a contract to build on; the resource
  loader (OPENNLP-1909) should be interface in core, downloaders in addons.
  CJK tokenization (OPENNLP-1894) supported core placement as a reach and contributor
  recruitment question. Dependency parser (OPENNLP-547) also supported core placement.
- On the SentencePiece shape specifically (M6 thread, Sep 1): the analysis
  and offset tracking belongs in a core API layer; the implementation is an
  addon. New technologies can then fill the same gap behind the same
  interface.
- Personal stance, stated for the record: "I don't care where it goes as long
  as I can make it. Under the Apache name is all I need." Graduation rules,
  in that view, leaves the implementation unchanged.
- Suggested sequence: docs as part of the PRs; review the three shared
  foundations in core (1888, 1885, 1909); clean up addons and publish to
  Maven Central; all other PRs become addons via SPI loaders; if an addon
  exposes a broadly reusable missing abstraction, submit only that small interface
  addition to core.

### Atita Arora, Aug 30

Separated the proposal into three goals that do not share one solution:

1. Clear the review crunch (throughput).
2. Keep core minimal and coherent (architecture).
3. Give experimental work a home and a path to core (adoption).

Key points:

- The split only fixes throughput **if addons uses a lighter review bar**.
  Requested an explicit yes/no answer.
- The addons compatibility contract with core APIs should be written down up
  front, whatever it is.
- Promotion on adoption only works if addons is discoverable enough to
  produce an adoption signal; today it is not. Jeff's cleanup point is the
  precondition, not a nice-to-have. Otherwise the membrane is one-way (the
  Solr/Lucene contrib pattern).
- Suggested sequencing: cleanup and Central publishing first, then the
  documented line, then the unsolved gaps (review capacity, discoverability).
- Requested short design notes per foundational component family (annotation
  layers, SentencePiece) ahead of the PRs, so reviewers can review the shape
  instead of reconstructing it from diffs.
- Volunteered to help on the addons cleanup and release side.

### Richard Zowalla (rzo1), Aug 31: interim summary

Not really contested:

- Clean up addons and get it releasing properly to Maven Central.
- No new dependencies in core.
- The gRPC PR is a PoC and gets its own build/repo, not core.

Proposed but not yet decided:

- Core gets the shared foundations: Document Shape (OPENNLP-1888),
  SentencePiece (OPENNLP-1885), resource loader (OPENNLP-1909), with the
  interface in core and the downloaders in addons.
- Everything else goes to addons behind SPI loaders, with a narrow exception
  for a broadly reusable missing core abstraction.
- CJK tokenization (OPENNLP-1894) and the dependency parser (OPENNLP-547)
  not much discussed either way.

Still open:

- The review bar for addons (Atita's question, unanswered at that point).
- What addons promises in terms of compatibility with core APIs, if anything.
- Who drives discoverability.
- Review capacity in general, including whether drafts count against the
  queue.

Corrections of record:

- Core has no Lucene dependency (only ONNX); the sole Lucene in the tree is a
  test-scoped benchmark baseline on one research branch, and Richard's
  position is no Lucene in core in any scope, because many users embed
  OpenNLP inside Lucene/Solr/Elasticsearch applications where the host sets
  the version.
- Richard would like broader participation before calling consensus: four
  people in the thread is not broad consensus yet.

### Martin Wiesner (mawiesne), Sep 1

1. Fine with releasing opennlp-addons, provided more people step up as
   release managers so the load is shared: +1 under that condition.
2. No new deps in core: +1. OpenNLP 3.x was envisioned as the modularized
   form of 2.x; new deps and heavy components (Lucene named explicitly)
   contradict that and should be avoided at all times.
3. gRPC: +1 for its own repo as a standalone tool, once 3.0.0 GA is out
   (targeted November); review focus stays on core and addons.
4. OPENNLP-1888 landed in core/main. OPENNLP-1885, -1893, -1909, and maybe
   -1894 should also fit in core; effort goes there so M6 matures.
5. Embeddings PRs live in opennlp-addons for now; download numbers give the
   adoption signal. M6 should be freed of non-core issues.
6. Review bar: personally favors a high bar but acknowledges it can slow
   innovation. Minimum acceptable: tests with good coverage, minimal
   documentation (an addons developer manual?), and one reviewing person.
7. API compatibility: addons should strictly adhere to opennlp-api and its
   contracts; additions may extend but must not reinvent or circumvent.

### M6 thread (Sep 1): SentencePiece carve confirmed

In the 3.0.0-M6 discussion, the SentencePiece split was stated and accepted:
the subword contract and offset tracking go to the core API layer; the
SentencePiece implementation code goes to addons. Richard: "Sounds good
to me." Martin: "Go for API/core carving."

## The question list (from OPENNLP-1924)

The JIRA ticket lists what the revamped addons must formalize. Status column
reflects the thread so far:

| Question | State in the thread |
| --- | --- |
| Boundaries: what belongs in sandbox vs addons vs core | Proposed test on the table (rzo1, Aug 25), not yet consented |
| Release train for addons | Wanted by all who spoke; cadence unspecified |
| Versioning scheme | Not discussed |
| Compatibility rules | "Compatible with latest core" (1924); "strictly adhere to opennlp-api" (mawiesne); not written down |
| Retirement rules | Not discussed |
| Graduation rules | Permeable line proposed (rzo1); adoption signal required; discoverability precondition (Atita); download numbers as the signal (mawiesne) |
| API addition rules | Narrow exception proposed: only a broadly reusable missing abstraction goes to core, as a small interface addition (krickert, rzo1) |
| Documentation | Canary implementation uses Maven Site Plugin 3.22.0, Doxia Markdown, and Maven Fluido Skin 2.1.0; publication location and release wiring are open |
| Existing modules | A reconciliation inventory now identifies duplicate code and active OpenNLP PRs before the first 3.x add-ons release |

## Documentation proposal

The add-ons manual uses Markdown under `src/site/markdown`, with navigation in
`src/site/site.xml`. Apache Maven Site and Doxia render the manual, and Apache
Maven Fluido Skin supplies the page layout. All three are build-time tools and
do not change any add-on's runtime dependencies.

Run `mvn -N site` to generate `target/site/index.html`. The canary branch uses
this layout so the proposal can be evaluated with generated output instead of
screenshots or an additional documentation branch.

## Open questions

These need answers on the dev list before this document can lose its DRAFT
marker:

1. **Review bar for addons.** Lighter than core, yes or no? Martin's floor:
   tests with good coverage, minimal docs, one reviewer. Is that the rule?
2. **Compatibility contract.** What exactly does an addon promise against
   core APIs, and what happens when core changes?
3. **Discoverability.** Who drives it (website, manual, release notes), so
   the adoption signal exists at all?
4. **Coordinates.** Legacy pattern (`org.apache.opennlp:foo-addon`) or a
   dedicated group (`org.apache.opennlp.addons:foo`)? No 3.x artifact is
   published yet, so this can be decided before the first release.
5. **SPI loaders.** Addons behind SPI service loaders was proposed and
   uncontested, but the mechanism itself is not designed yet. Core today has
   no ServiceLoader-based discovery for such components.
6. **Release management.** Who are the release managers for addons (Martin's
   condition for his +1)?
7. **Versioning scheme.** Track core versions, or independent?
8. **Drafts and the queue.** Do draft PRs count against review capacity?
   (Kristian: no, drafts are WIP by definition.)
9. **Documentation publication.** Where should the generated add-ons manual be
   published, and should site generation be part of the release vote?

## Working agreements (not policy, just how the revamp branch operates)

These govern the `OPENNLP-1924-canary-addon` branch while the questions above
are open:

- Add-ons modules inherit the root `opennlp.version`, currently `3.0.0-M5`.
  A development branch may temporarily select `3.0.0-SNAPSHOT` when its API
  dependency is still under core review. Use the isolated repository procedure
  in [Building Against a Local Core Snapshot](building-snapshot.html).
- A branch in this repo may not compile against the published snapshots while
  its core dependency is still unmerged. That is acceptable as long as there
  is no open PR against it.
- No new dependencies without a discussion first; when one is accepted, it
  goes behind an SPI service loader over an interface.
- If addon work surfaces a missing core abstraction, propose the small
  interface change to core directly; do not work around it addon-side.
- End-user and contributor documentation belongs under `src/site/markdown`.
  Keep the root README as the short repository entry point and verify manual
  changes with `mvn -N site`.
- Check existing modules against OpenNLP core and active pull requests before
  including them in a 3.x release. Record the result in
  [Feature Reconciliation](reconciliation.html).
