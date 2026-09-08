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

Thank you for contributing to Apache OpenNLP.

In order to streamline the review of the contribution we ask you
to ensure the following steps have been taken:

### For all changes:

- [ ] Is there a JIRA ticket associated with this PR? Is it referenced 
     in the commit message?

- [ ] Does your PR title start with OPENNLP-XXXX where XXXX is the JIRA number you are trying to resolve? Pay particular attention to the hyphen "-" character.

- [ ] Has your PR been rebased against the latest commit within the target branch (typically main)?

- [ ] Is your initial contribution a single, squashed commit?

### For code changes:

- [ ] Does `mvn clean verify -Dopennlp.forkCount=1` pass at the repository root?
- [ ] Have you written or updated unit tests to verify your changes?
- [ ] If adding new dependencies to the code, are these dependencies licensed in a way that is compatible for inclusion under [ASF 2.0](https://www.apache.org/legal/resolved.html#category-a)? 
- [ ] If applicable, have you updated LICENSE for the files included in the source and binary artifacts?
- [ ] If applicable, have you updated NOTICE for the files included in the source and binary artifacts?

### For documentation related changes:

- [ ] Have you ensured that format looks appropriate for the output in which it is rendered?

### Note:

Please ensure that once the PR is submitted, you check GitHub Actions for build issues and submit an update to your PR as soon as possible.
