/*
 * Copyright 2013 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.addons.geoentitylinker;

/**
 * Stores a tuple from the {@code opennlp.geoentitylinker.countrycontext.txt} file.
 * It is used to find country mentions in document text.
 */
public record CountryContextEntry(String rc, String cc1, String full_name_nd_ro,
                                  String dsg, String provCode) {

}
