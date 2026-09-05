/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright 2013 The Apache Software Foundation.
 */
package opennlp.addons.modelbuilder;

import java.util.Set;

import opennlp.addons.modelbuilder.impls.BaseModelBuilderParams;



/**
 *
Supplies a list of known entities (a list of names or locations)
 */
public interface KnownEntityProvider extends ModelParameter<BaseModelBuilderParams> {

  /**
   * returns a list of known non-ambiguous entities.
   * @return a set of entities
   */
  Set<String> getKnownEntities();

  /**
   * adds to the set of known entities. Overriding classes should hold this list in a class level set.
   * @param unambiguousEntity
   */
  void addKnownEntity(String unambiguousEntity);
  
  /**
   * defines the type of entity that the set contains, ie person, location, organization.
   * @return
   */
  String getKnownEntitiesType();

}
