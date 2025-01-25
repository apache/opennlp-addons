/*
 * Copyright 2014 The Apache Software Foundation.
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

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GeonamesFileDownloader {

  final static int size = 1024;
  private static final String ALL_COUNTRIES = "https://download.geonames.org/export/dump/ZM.zip";
  private static final String COUNTRY_INFO = "";
  private static final String ADM1_LOOKUP = "";

  public static void main(String[] args) {
    downloadGeonamesFiles(COUNTRY_INFO, "c:\\temp\\gazetteers");
  }

  public static void downloadGeonamesFiles(String outputFileName, String outputDir) {
    String fileDownload = fileDownload(ALL_COUNTRIES, outputDir);
    unzipMyZip(fileDownload, outputDir);

    fileDownload(COUNTRY_INFO, outputDir);
    fileDownload(ADM1_LOOKUP, outputDir);

  }

  public static void unzipMyZip(String zipFileName, String directoryToExtractTo) {
    Enumeration<? extends ZipEntry> entriesEnum;
    ZipFile zip;
    try {
      zip = new ZipFile(zipFileName);
      entriesEnum = zip.entries();
      while (entriesEnum.hasMoreElements()) {
        ZipEntry entry = entriesEnum.nextElement();
        InputStream is = zip.getInputStream(entry); // get the input stream
        OutputStream os = new FileOutputStream(zipFileName.replace("\\.zip", ".txt"));
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) != -1) {
          os.write(buf, 0, r);
        }
        os.close();
        is.close();
      }
    } catch (IOException ioe) {
      System.err.println("Some Exception Occurred:");
      ioe.printStackTrace();
    }
  }

  public static String fileUrl(String fAddress, String localFileName, String destDir) {
    String filename = destDir + "\\" + localFileName;
    try (InputStream is = new URL(fAddress).openConnection().getInputStream();
         OutputStream outStream = new BufferedOutputStream(new FileOutputStream(destDir + "\\" + localFileName))) {

      byte[] buf = new byte[size];
      int byteRead, byteWritten = 0;
      while ((byteRead = is.read(buf)) != -1) {
        outStream.write(buf, 0, byteRead);
        byteWritten += byteRead;
      }
      System.out.println("Downloaded Successfully.");
      System.out.println("File name:\"" + localFileName + "\"\nNo of bytes :" + byteWritten);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return filename;
  }

  public static String fileDownload(String fAddress, String destinationDir) {
    int slashIndex = fAddress.lastIndexOf('/');
    int periodIndex = fAddress.lastIndexOf('.');

    String fileName = fAddress.substring(slashIndex + 1);
    String retFileName = "";
    if (periodIndex >= 1 && slashIndex >= 0
            && slashIndex < fAddress.length() - 1) {
      retFileName = fileUrl(fAddress, fileName, destinationDir);
    } else {
      System.err.println("path or file name.");
    }
    return retFileName;
  }

}
