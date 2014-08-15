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
package opennlp.addons.geoentitylinker.indexing;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Version;

/**
 *
 * Creates two lucene indexes, geonames and usgs for use in GeoEntityLinker
 */
public class GazetteerIndexer {

  public static void main(String[] args) {
    try {
      GazetteerIndexer i = new GazetteerIndexer();
      i.index(new File("C:\\temp\\gazetteers\\geonamesdata\\allcountries\\allCountries.txt"),
              new File("C:\\temp\\gazetteers\\geonamesdata\\countryinfo.txt"),
              new File("C:\\temp\\gazetteers\\geonamesdata\\admin1CodesASCII.txt"),
              new File("C:\\temp\\gazetteers\\usgsdata\\NationalFile_20140601.txt"),
              new File("C:\\temp\\gazetteers\\usgsdata\\GOVT_UNITS_20140601.txt"),
              new File("C:\\temp\\gazetteers\\"),
              new File("C:\\temp\\gazetteers\\newCountryContextFile.txt"),
              new File("C:\\temp\\gazetteers\\regions.txt"));
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public GazetteerIndexer() {

  }

  public static interface Separable {

    String getSeparator();
  }

  public enum GazType implements Separable {

    GEONAMES {
              @Override
              public String toString() {
                return "/opennlp_geoentitylinker_geonames_idx";
              }

              @Override
              public String getSeparator() {
                return "\t";
              }
            },
    USGS {
              @Override
              public String toString() {
                return "/opennlp_geoentitylinker_usgsgaz_idx";
              }

              @Override
              public String getSeparator() {
                return "\\|";
              }
            }
  }

  /**
   *
   * @param geonamesData the actual Geonames gazetteer data downloaded from
   * here: http://download.geonames.org/export/dump/ then click on this link
   * 'allCountries.zip'
   * @param geoNamesCountryInfo the countryinfo lookup table that can be
   * downloaded from here
   * http://download.geonames.org/export/dump/countryinfo.txt
   * @param geonamesAdmin1CodesASCII The lookup data for the province names for
   * each place found here:
   * http://download.geonames.org/export/dump/admin1CodesASCII.txt highlight the
   * table view, and copy results into a text file. Make sure the tab delimitted
   * format is maintained.
   * @param usgsDataFile the actual USGS gazetteer downloaded from here:
   * http://geonames.usgs.gov/domestic/download_data.htm click on the
   * national_file####.zip link to get all the most recent features
   *
   * @param usgsGovUnitsFile go to here:
   * http://geonames.usgs.gov/domestic/download_data.htm in the section titled
   * "Topical Gazetteers -- File Format" click on the drop down list and select
   * "Government Units". The downloaded file is what you need for this param.
   * @param outputIndexDir where you want the final index. Must be a directory,
   * not an actual file.
   * @param outputCountryContextFile The output countrycontext file. THis is a
   * very important file used inside the GeoEntityLinker to assist in toponym
   * resolution.
   * @param regionsFile this file contains a list of regions in the following
   * format: tab delimitted text with index 0 as the name of the region, index 1
   * as the longitude, and index 2 as the latitude
   * @throws Exception
   */
  public void index(File geonamesData, File geoNamesCountryInfo, File geonamesAdmin1CodesASCII,
          File usgsDataFile, File usgsGovUnitsFile, File outputIndexDir, File outputCountryContextFile, File regionsFile) throws Exception {
    if (!outputIndexDir.isDirectory()) {
      throw new IllegalArgumentException("outputIndexDir must be a directory.");
    }
    if (!geonamesData.exists()) {
      throw new FileNotFoundException("geonames data file does not exist");
    }
    if (!geoNamesCountryInfo.exists()) {
      throw new FileNotFoundException("geoNamesCountryCodes data file does not exist");
    }
    if (!geonamesAdmin1CodesASCII.exists()) {
      throw new FileNotFoundException("geonamesAdmin1CodesASCII data file does not exist");
    }

    if (!usgsDataFile.exists()) {
      throw new FileNotFoundException("usgsDataFile data file does not exist");
    }
    if (!usgsGovUnitsFile.exists()) {
      throw new FileNotFoundException("usgsGovUnitsFile data file does not exist");
    }
    if (!outputIndexDir.exists()) {
      throw new FileNotFoundException("outputIndexDir data file does not exist");
    }
    if (!regionsFile.exists()) {
      throw new FileNotFoundException("regionsFile data file does not exist");
    }

    String indexloc = outputIndexDir.getPath() + "/opennlp_geoentitylinker_gazetteer";
    Directory index = new MMapDirectory(new File(indexloc));
    Analyzer a = new StandardAnalyzer(Version.LUCENE_48, new CharArraySet(Version.LUCENE_48, new ArrayList(), true));
    Map<String, Analyzer> analyMap = new HashMap<>();

    analyMap.put("countrycode", new KeywordAnalyzer());
    analyMap.put("admincode", new KeywordAnalyzer());
    analyMap.put("loctype", new KeywordAnalyzer());
    analyMap.put("countycode", new KeywordAnalyzer());
    analyMap.put("gazsource", new KeywordAnalyzer());
    
    PerFieldAnalyzerWrapper aWrapper
            = new PerFieldAnalyzerWrapper(a, analyMap);

    IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_48, aWrapper);

    IndexWriter w = new IndexWriter(index, config);
    USGSProcessor.process(usgsGovUnitsFile, usgsDataFile, outputCountryContextFile, w);

    GeonamesProcessor.process(geoNamesCountryInfo, geonamesAdmin1CodesASCII, geonamesData, outputCountryContextFile, w);

    RegionProcessor.process(regionsFile, outputCountryContextFile, w);
    w.commit();
    w.close();
    System.out.println("\nIndexing complete. Be sure to add '" + indexloc + "' and context file '" + outputCountryContextFile.getPath() + "' to entitylinker.properties file");
  }

}
