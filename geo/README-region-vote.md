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

# Document region voting

This feature combines geocoded location mentions and country names into a document-level ballot for the most likely geographic region.

## Human definition

It gathers several weak geographic clues from a document and chooses a region only when one candidate wins by a sufficient margin.

## Prior art and comparable products

- [Mordecai](https://github.com/openeventdata/mordecai) uses full-document geoparsing context to resolve geographic references.

## Addon modules

`geo` supplies the region ballot, gazetteer, and geocoder contracts. `numeric`
contains the region-aware currency adapter and depends on `geo`. The immutable
Document container and typed layer contracts come from core.
