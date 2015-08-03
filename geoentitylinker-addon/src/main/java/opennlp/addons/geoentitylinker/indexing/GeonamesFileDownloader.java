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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GeonamesFileDownloader {

  final static int size = 1024;
  private static final String ALL_COUNTRIES = "http://download.geonames.org/export/dump/ZM.zip";
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

  public static final void writeFile(InputStream in, OutputStream out)
          throws IOException {
    byte[] buffer = new byte[1024];
    int len;

    while ((len = in.read(buffer)) != 0) {
      out.write(buffer, 0, len);
    }

    in.close();
    out.close();
  }

  public static void unzipMyZip(String zipFileName,
          String directoryToExtractTo) {
    Enumeration entriesEnum;
    ZipFile zip;
    try {
      zip = new ZipFile(zipFileName);
      entriesEnum = zip.entries();
      while (entriesEnum.hasMoreElements()) {
        ZipEntry entry = (ZipEntry) entriesEnum.nextElement();
        InputStream is = zip.getInputStream(entry); // get the input stream
        OutputStream os = new java.io.FileOutputStream(new File(zipFileName.replace("\\.zip", ".txt")));
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
      return;
    }
  }

  public static String fileUrl(String fAddress, String localFileName, String destinationDir) {
    OutputStream outStream = null;
    URLConnection uCon = null;
    String filename = destinationDir + "\\" + localFileName;
    InputStream is = null;
    try {
      URL Url;
      byte[] buf;
      int ByteRead, ByteWritten = 0;
      Url = new URL(fAddress);
      outStream = new BufferedOutputStream(new FileOutputStream(destinationDir + "\\" + localFileName));

      uCon = Url.openConnection();
      is = uCon.getInputStream();
      buf = new byte[size];
      while ((ByteRead = is.read(buf)) != -1) {
        outStream.write(buf, 0, ByteRead);
        ByteWritten += ByteRead;
      }
      System.out.println("Downloaded Successfully.");
      System.out.println("File name:\"" + localFileName + "\"\nNo ofbytes :" + ByteWritten);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        is.close();
        outStream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
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
