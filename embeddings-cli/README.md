<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

# Embeddings command line tools

The archive contains the embedding libraries, their runtime dependencies and
launchers. Java 21 or later is required. Extract the zip or tar.gz archive and run:

```sh
bin/opennlp-embeddings
bin/opennlp-embeddings DistillModel help
bin/opennlp-embeddings AssembleModel help
```

Windows launchers use the same names with `.bat`. The shorter `embeddings`
launcher is also included. Use `JAVA_HOME` to choose the Java installation.
The launchers read `conf/log4j2.xml` for console logging configuration.

## Build from source

From the addons repository root, with the selected core snapshot available:

```sh
mvn -pl embeddings-cli -am verify -Dopennlp.forkCount=1
```

The archives are written to `embeddings-cli/target/`. The build checks the Linux
and macOS shell launchers by extracting the archive into a path with spaces and
running command listing, help and invalid-command cases. Windows execution
requires a separate Windows check.

The library README and training guide are in `embeddings/`. Developer tools are
in `dev/embeddings/`; the ONNX fixture generator is included at its source path.
Dependency license and notice files are retained in their jars and extracted into
`licenses/`. These research snapshot archives are development builds.
