/*
 * Copyright (c) Ian F. Darwin 1986, 1987, 1989, 1990, 1991, 1992, 1994, 1995.
 * Software written by Ian F. Darwin and others;
 * maintained 1994- Christos Zoulas.
 *
 * This software is not subject to any export provision of the United States
 * Department of Commerce, and may be exported to any country or planet.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice immediately at the beginning of the file, without modification,
 *    this list of conditions, and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opennlp.tools.assets;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * File signatures, format names, media types, and precomputed base64 prefixes.
 *
 * <p>Entries are adapted from
 * <a href="https://github.com/apache/tika/blob/5837488582bedbe86443ae8fa9265952b8cf96a6/tika-core/src/main/resources/org/apache/tika/mime/tika-mimetypes.xml">
 * Apache Tika's media-type catalog</a>, using fixed byte sequences at offset zero.
 * The catalog's Apache, file(1), and PRONOM notices are included in LICENSE and
 * NOTICE. Matching a signature identifies a possible format; it does not validate
 * the file. Encoded text formats such as XML and PHP are included.</p>
 *
 * <p>Lookups prefer the longest matching signature. RIFF form types at offset eight
 * are handled by {@link CursorAssetDetector}; fixed RIFF prefixes, such as CDA,
 * can also appear here. The detector also checks AIFF form types after matching a
 * FORM prefix, Excel document types after matching a BOF prefix, EMF signatures
 * after matching an EMR_HEADER prefix, and pcapng byte-order magic after
 * matching a Section Header Block prefix. JPEG 2000 formats use the brand in the
 * file-type box after the shared signature box. DEX requires 3 decimal version
 * digits and a zero terminator after the prefix. WebVTT permits an initial UTF-8 BOM
 * and requires a separator or EOF after its identifier.</p>
 *
 * @since 3.0.0
 */
final class KnownMagics {

  private static final String MPX_FORMAT = "mpx";
  private static final String MPX_MEDIA_TYPE = "application/x-project";

  /**
   * The format name and media type identified by a header.
   *
   * @param name The format name.
   * @param mediaType The media type.
   */
  record Format(String name, String mediaType) {
  }

  /**
   * One table entry: the magic bytes, their precomputed base64 image, and the tags.
   *
   * @param magic The leading bytes a file of the format starts with.
   * @param prefix The base64 image of {@code magic}, floored to the characters the
   *               magic fully determines.
   * @param format The format name and media type.
   */
  record Entry(byte[] magic, String prefix, Format format) {
  }

  /** The formats named by the constants on {@link EmbeddedAsset}. */
  private static final List<Entry> CORE = List.of(
      e("89504e470d0a1a0a", EmbeddedAsset.FORMAT_PNG, "image/png"),
      e("ffd8ff", EmbeddedAsset.FORMAT_JPEG, "image/jpeg"),
      e("474946383761", EmbeddedAsset.FORMAT_GIF, "image/gif"), // GIF87a
      e("474946383961", EmbeddedAsset.FORMAT_GIF, "image/gif"), // GIF89a
      e("255044462d", EmbeddedAsset.FORMAT_PDF, "application/pdf"), // %PDF-
      e("504b0304", EmbeddedAsset.FORMAT_ZIP, "application/zip"),
      e("504b0506", EmbeddedAsset.FORMAT_ZIP, "application/zip"), // empty archive
      e("504b0708", EmbeddedAsset.FORMAT_ZIP, "application/zip"), // spanned archive
      e("49492a00", EmbeddedAsset.FORMAT_TIFF, "image/tiff"), // II*\0
      e("4d4d002a", EmbeddedAsset.FORMAT_TIFF, "image/tiff"), // MM\0*
      e("49492b00", EmbeddedAsset.FORMAT_TIFF, "image/tiff"), // II+\0, BigTIFF
      e("4d4d002b", EmbeddedAsset.FORMAT_TIFF, "image/tiff"), // MM\0+, BigTIFF
      e("1f8b08", EmbeddedAsset.FORMAT_GZIP, "application/gzip"),
      e("377abcaf271c", EmbeddedAsset.FORMAT_SEVEN_ZIP, "application/x-7z-compressed"),
      e("526172211a07", EmbeddedAsset.FORMAT_RAR, "application/vnd.rar"), // v4 and v5
      e("664c6143", EmbeddedAsset.FORMAT_FLAC, "audio/flac"), // fLaC
      e("4f676753", EmbeddedAsset.FORMAT_OGG, "application/ogg"), // OggS
      e("4d546864", EmbeddedAsset.FORMAT_MIDI, "audio/midi"), // MThd
      e("53514c69746520666f726d6174203300", EmbeddedAsset.FORMAT_SQLITE,
          "application/vnd.sqlite3"),
      e("7f454c46", EmbeddedAsset.FORMAT_ELF, "application/x-elf"),
      e("4d5a9000", EmbeddedAsset.FORMAT_PE,
          "application/vnd.microsoft.portable-executable"), // MZ with the DOS stub
      // This signature also occurs in Mach-O universal binaries; this entry selects Java.
      e("cafebabe", EmbeddedAsset.FORMAT_CLASS, "application/java-vm"),
      e("774f4646", EmbeddedAsset.FORMAT_WOFF, "font/woff"), // wOFF
      e("774f4632", EmbeddedAsset.FORMAT_WOFF2, "font/woff2"), // wOF2
      e("494433", EmbeddedAsset.FORMAT_MP3, "audio/mpeg"), // ID3
      e("d0cf11e0a1b11ae1", EmbeddedAsset.FORMAT_OLE2, "application/x-ole-storage"),
      e("28b52ffd", EmbeddedAsset.FORMAT_ZSTD, "application/zstd"),
      e("0061736d", EmbeddedAsset.FORMAT_WASM, "application/wasm")); // \0asm

  /** The remaining recognized formats. */
  private static final List<Entry> DERIVED = List.of(
      // -----BEGIN CERTIFICATE-----
      e("2d2d2d2d2d424547494e2043455254494649434154452d2d2d2d2d",
          "pem-cert", "application/x-x509-cert"),
      // -----BEGIN DSA PARAMETERS-----
      e("2d2d2d2d2d424547494e2044534120504152414d45544552532d2d2d2d2d", "pem-parameters",
          "application/x-x509-dsa-parameters"),
      // -----BEGIN DSA PRIVATE KEY-----
      e("2d2d2d2d2d424547494e204453412050524956415445204b45592d2d2d2d2d",
          "pem-key", "application/x-x509-key"),
      // -----BEGIN EC PARAMETERS-----
      e("2d2d2d2d2d424547494e20454320504152414d45544552532d2d2d2d2d", "pem-parameters",
          "application/x-x509-ec-parameters"),
      // -----BEGIN PRIVATE KEY-----
      e("2d2d2d2d2d424547494e2050524956415445204b45592d2d2d2d2d",
          "pem-key", "application/x-x509-key"),
      // -----BEGIN PUBLIC KEY-----
      e("2d2d2d2d2d424547494e205055424c4943204b45592d2d2d2d2d",
          "pem-key", "application/x-x509-key"),
      // -----BEGIN RSA PRIVATE KEY-----
      e("2d2d2d2d2d424547494e205253412050524956415445204b45592d2d2d2d2d",
          "pem-key", "application/x-x509-key"),
      // Binary DXF sentinel ends with CR, LF, SUB, and NUL bytes.
      e("4175746f4341442042696e617279204458460d0a1a00", "dxf", "image/vnd.dxf"),
      // MPX followed by a comma or semicolon list separator.
      e("4d50582c", MPX_FORMAT, MPX_MEDIA_TYPE),
      e("4d50583b", MPX_FORMAT, MPX_MEDIA_TYPE),
      // %!PS-Adobe-3.0 EPSF-3.0
      e("252150532d41646f62652d332e3020455053462d332e30", "ps", "application/postscript"),
      // -----BEGIN DSA KEY-----
      e("2d2d2d2d2d424547494e20445341204b45592d2d2d2d2d", "pem-key", "application/x-x509-key"),
      // -----BEGIN RSA KEY-----
      e("2d2d2d2d2d424547494e20525341204b45592d2d2d2d2d", "pem-key", "application/x-x509-key"),
      // The 19-byte DXB 1.0 header includes CR, LF, SUB, and NUL.
      e("4175746f4341442044584220312e300d0a1a00", "dxb", "image/vnd.dxb"),
      // !<arch>.debian-binary
      e("213c617263683e0a64656269616e2d62696e617279", "deb", "application/x-debian-package"),
      // !<arch>.debian-split
      e("213c617263683e0a64656269616e2d73706c6974", "deb", "application/x-debian-package"),
      // ....MSISAM Database
      e("000100004d534953414d204461746162617365", "mny", "application/x-msmoney"),
      // -----BEGIN KEY-----
      e("2d2d2d2d2d424547494e204b45592d2d2d2d2d", "pem-key", "application/x-x509-key"),
      // HWP Document File V
      e("48575020446f63756d656e742046696c652056", "hwp", "application/x-hwp"),
      // %!PS-AdobeFont-1.0
      e("252150532d41646f6265466f6e742d312e30", "pfa", "application/x-font-type1"),
      e("524946462400000043444441666d742018", "cda", "application/x-cdf"), // RIFF$...CDDAfmt .
      // ......F..1...t..
      e("0606edf5d81d46e5bd31efe7fe74b71d", "indd", "application/x-adobe-indesign"),
      // -----BEGIN PKCS7-----
      e("2d2d2d2d2d424547494e20504b4353372d2d2d2d2d", "p7s", "application/pkcs7-signature"),
      e("457874656e646564204d6f64756c653a", "mod", "audio/x-mod"), // Extended Module:
      // StartFontMetrics
      e("5374617274466f6e744d657472696373", "afm", "application/x-font-adobe-metric"),
      e("c0b9072e4f93f146a015792ca1d9e821", "axx", "application/x-axcrypt"), // ....O..F..y,...!
      e("424547494e3a5643414c454e444152", "ics", "text/calendar"), // BEGIN:VCALENDAR
      e("454846415f4845414445525f544147", "hfa", "application/x-erdas-hfa"), // EHFA_HEADER_TAG
      e("1010101010101011111111111153", "foxmail", "application/x-foxmail"),
      e("4241280000002e00000000000000", "ico", "image/vnd.microsoft.icon"), // BA(...........
      e("49491a0000004845415043434452", "crw", "image/x-raw-canon"), // II....HEAPCCDR
      e("5044535f56455253494f4e5f4944", "pds", "application/x-pds"), // PDS_VERSION_ID
      e("0000000c4a584c200d0a870a", "jxl", "image/jxl"), // ....JXL ....
      e("0000000c6a5020200d0a870a", "jp2", "image/jp2"),
      e("4163746976654d696d650000", "activemime", "application/x-activemime"), // ActiveMime..
      e("445644564944454f2d564d47", "ifo", "application/x-dvd-ifo"), // DVDVIDEO-VMG
      e("445644564944454f2d565453", "ifo", "application/x-dvd-ifo"), // DVDVIDEO-VTS
      e("feff003c003f0078006d006c", "xml", "application/xml"), // ...<.?.x.m.l
      e("fffe3c003f0078006d006c00", "xml", "application/xml"), // ..<.?.x.m.l.
      e("3c73746174615f6474613e", "dta", "application/x-stata-dta"), // <stata_dta>
      e("424547494e3a5643415244", "vcf", "text/x-vcard"), // BEGIN:VCARD
      e("5341532020202020382e30", "sd2", "application/x-sas-data-v6"), // SAS     8.0
      e("5341532020202020392e30", "sd2", "application/x-sas-data-v6"), // SAS     9.0
      e("53494d504c4520203d2020", "fits", "image/fits"), // SIMPLE  =
      e("535200040000000a000004", "idl", "application/x-idl-save-file"),
      e("64383a616e6e6f756e6365", "torrent", "application/x-bittorrent"), // d8:announce
      e("66696c65646573633a2f2f", "arc", "application/x-internet-archive"), // filedesc://
      e("00000200060406000800", "wk1", "application/vnd.lotus-1-2-3"),
      e("4e49544630322e303030", "ntf", "image/nitf"), // NITF02.000
      e("4e49544630322e313030", "ntf", "image/nitf"), // NITF02.100
      e("5341532020202020362e", "sd2", "application/x-sas-data-v6"), // SAS     6.
      e("5341532020202020372e", "sd2", "application/x-sas-data-v6"), // SAS     7.
      e("0f534942454c495553", "sib", "application/x-sibelius"), // .SIBELIUS
      e("2321414d522d57420a", "amr-wb", "audio/amr-wb"), // #!AMR-WB.
      e("234558544d33550d0a", "m3u", "audio/x-mpegurl"), // #EXTM3U..
      e("4e49544630312e3130", "ntf", "image/nitf"), // NITF01.10
      e("576f726450726f0dfb", "lwp", "application/vnd.lotus-wordpro"), // WordPro..
      e("67696d702078636620", "xcf", "image/x-xcf"), // gimp xcf
      e("0000000877696465", "mov", "video/quicktime"), // ....wide
      e("00001a0000100400", "wk3", "application/vnd.lotus-1-2-3"),
      e("00001a0002100400", "wk4", "application/vnd.lotus-1-2-3"),
      e("00001a0003100400", "123", "application/vnd.lotus-1-2-3"),
      e("000100005374616e", "accdb", "application/x-msaccess"), // ....Stan
      e("213c617263683e0a", "ar", "application/x-archive"), // !<arch>.
      e("3b454c4313000000", "elc", "application/x-elc"), // ;ELC....
      e("3c4d494646696c65", "mif", "application/vnd.mif"), // <MIFFile
      e("4153544d2d453537", "e57", "model/e57"), // ASTM-E57
      e("41542654464f524d", "djvu", "image/vnd.djvu"), // AT&TFORM
      e("4a454f4c2e4e4d52", "jdf", "application/x-jeol-jdf"), // JEOL.NMR
      e("524d4e2e4c4f454a", "jdf", "application/x-jeol-jdf"), // RMN.LOEJ
      e("566a434430313030", "cdx", "chemical/x-cdx"), // VjCD0100
      e("576f726450726f00", "lwp", "application/vnd.lotus-wordpro"), // WordPro.
      e("5a5854617065211a", "tzx", "application/x-spectrum-tzx"), // ZXTape!.
      e("636f6e6563746978", "vhd", "application/x-vhd"), // conectix
      // The OpenEXR magic does not establish ACES conformance.
      e("762f3101", "exr", "image/x-exr"),
      e("974a42320d0a1a0a", "jb2", "image/x-jbig2"), // .JB2....
      e("efbbbf255044462d", EmbeddedAsset.FORMAT_PDF, "application/pdf"), // ...%PDF-
      e("efbbbf3c3f786d6c", "xml", "application/xml"), // ...<?xml
      e("234558544d3355", "m3u8", "application/vnd.apple.mpegurl"), // #EXTM3U
      e("53747566664974", "sit", "application/x-stuffit"), // StuffIt
      e("efbbbf574542565454", "vtt", "text/vtt"), // UTF-8 BOM and WEBVTT
      e("574542565454", "vtt", "text/vtt"), // WEBVTT
      e("894844460d0a1a0a", "hdf", "application/x-hdf"), // .HDF....
      e("000002000110", "wb1", "application/x-quattro-pro"),
      e("000002000210", "wb2", "application/x-quattro-pro"),
      e("000002000404", "wks", "application/vnd.lotus-1-2-3"),
      e("000002002051", "wq1", "application/x-quattro-pro"),
      e("000002002151", "wq2", "application/x-quattro-pro"),
      e("010009000003", "wmf", "image/wmf"),
      e("01da01010003", "rgb", "image/x-rgb"),
      e("284457462056", "dwf", "model/vnd.dwf"), // (DWF V
      e("2f2a2058504d", "xpm", "image/x-xpixmap"), // /* XPM
      e("303730373031", "cpio", "application/x-cpio"), // 070701
      e("303730373032", "cpio", "application/x-cpio"), // 070702
      e("303730373037", "cpio", "application/x-cpio"), // 070707
      e("384250530001", "psd", "image/vnd.adobe.photoshop"), // 8BPS..
      e("384250530002", "psd", "image/vnd.adobe.photoshop"), // 8BPS..
      e("3c4d616b6572", "mif", "application/vnd.mif"), // <Maker
      e("4143312e3430", "dwg", "image/vnd.dwg"), // AC1.40
      e("4143312e3530", "dwg", "image/vnd.dwg"), // AC1.50
      e("4143322e3130", "dwg", "image/vnd.dwg"), // AC2.10
      e("4143322e3231", "dwg", "image/vnd.dwg"), // AC2.21
      e("4143322e3232", "dwg", "image/vnd.dwg"), // AC2.22
      e("4d41544c4142", "mat", "application/x-matlab-data"), // MATLAB
      e("62706c697374", "bplist", "application/x-bplist"), // bplist
      e("636166660000", "caf", "audio/x-caf"), // caff..
      e("636166660001", "caf", "audio/x-caf"), // caff..
      e("636166660002", "caf", "audio/x-caf"), // caff..
      e("636166664000", "caf", "audio/x-caf"), // caff@.
      e("636166668000", "caf", "audio/x-caf"), // caff..
      // The Snappy stream identifier includes the chunk type and length.
      e("ff060000734e61507059", "sz", "application/x-snappy-framed"),
      e("d7cdc69a0000", "wmf", "image/wmf"),
      e("dba52d000000", "doc", "application/msword"),
      e("fd377a585a00", "xz", "application/x-xz"), // .7zXZ.
      e("2321414d52", "amr", "audio/amr"), // #!AMR
      e("254644462d", "fdf", "application/vnd.fdf"), // %FDF-
      e("3c3f584d4c", "xml", "application/xml"), // <?XML
      e("3c3f706870", "php", "text/x-php"), // <?php
      e("3c3f786d6c", "xml", "application/xml"), // <?xml
      e("3c426f6f6b", "mif", "application/vnd.mif"), // <Book
      e("3d3c61723e", "ar", "application/x-archive"), // =<ar>
      e("4143312e32", "dwg", "image/vnd.dwg"), // AC1.2
      e("4245474d46", "cgm", "image/cgm"), // BEGMF
      e("464f524d", "aiff", "audio/x-aiff"), // FORM
      e("4d43302e30", "dwg", "image/vnd.dwg"), // MC0.0
      e("4d4f564900", "sgi-movie", "video/x-sgi-movie"), // MOVI.
      e("4d4f564901", "sgi-movie", "video/x-sgi-movie"), // MOVI.
      e("4d4f564902", "sgi-movie", "video/x-sgi-movie"), // MOVI.
      e("4d4f5649fe", "sgi-movie", "video/x-sgi-movie"), // MOVI.
      e("4d4f5649ff", "sgi-movie", "video/x-sgi-movie"), // MOVI.
      e("4f54544f00", "otf", "application/x-font-otf"), // OTTO.
      e("504f5e5160", "doc", "application/msword"), // PO^Q`
      e("5341500d0a", "sap", "audio/x-sap"), // SAP..
      e("574152432f", "warc", "application/warc"), // WARC/
      e("7b5c727466", "rtf", "application/rtf"), // {\rtf
      e("00000100", "ico", "image/vnd.microsoft.icon"),
      e("000001b3", "mpeg", "video/mpeg"),
      e("000001ba", "mpeg", "video/mpeg"),
      e("00010000", "ttf", "application/x-font-ttf"),
      e("00051600", "applefile", "application/applefile"),
      e("00051607", "appledouble", "multipart/appledouble"),
      e("01000000", "emf", "image/emf"),
      e("03000800", "axml", "application/vnd.android.axml"),
      e("09000400", "xls", "application/vnd.ms-excel.sheet.2"),
      e("09020600", "xls", "application/vnd.ms-excel.sheet.3"),
      e("09040600", "xls", "application/vnd.ms-excel.sheet.4"),
      e("0a0d0d0a", "pcapng", "application/vnd.tcpdump.pcapng"),
      e("1a45dfa3", "matroska", "application/x-matroska"),
      e("2e524d46", "rm", "application/vnd.rn-realmedia"), // .RMF
      e("2e736e64", "au", "audio/basic"), // .snd
      e("3c4d4d4c", "mif", "application/vnd.mif"), // <MML
      e("3c737667", "svg", "image/svg+xml"), // <svg
      e("41474432", "fh", "image/x-freehand"), // AGD2
      e("41474433", "fh", "image/x-freehand"), // AGD3
      e("41474434", "fh", "image/x-freehand"), // AGD4
      e("425047fb", "bpg", "image/x-bpg"), // BPG.
      e("43444601", "nc", "application/x-netcdf"), // CDF.
      e("43444602", "nc", "application/x-netcdf"), // CDF.
      e("43723234", "crx", "application/x-chrome-package"), // Cr24
      e("45502a00", "mdi", "image/vnd.ms-modi"), // EP*.
      e("47524942", "grb", "application/x-grib"), // GRIB
      e("49494e31", "niff", "image/x-niff"), // IIN1
      e("4949524f", "orf", "image/x-raw-olympus"), // IIRO
      e("494d504d", "mod", "audio/x-mod"), // IMPM
      e("49545346", "chm", "application/vnd.ms-htmlhelp"), // ITSF
      e("4c415346", "las", "application/x-asprs"), // LASF
      e("4c5a4950", "lz", "application/x-lzip"), // LZIP
      e("4d534346", "cab", "application/vnd.ms-cab-compressed"), // MSCF
      e("4e45531a", "nes", "application/x-nesrom"), // NES.
      e("50415231", "parquet", "application/x-parquet"), // PAR1
      e("50534944", "sid", "audio/prs.sid"), // PSID
      e("53445058", "dpx", "image/x-dpx"), // SDPX
      e("5543321a", "uc2", "application/x-uc2-compressed"), // UC2.
      e("58504453", "dpx", "image/x-dpx"), // XPDS
      e("6465780a", "dex", "application/x-dex"),
      e("69636e73", "icns", "image/icns"), // icns
      e("78617221", "xar", "application/x-xar"), // xar!
      e("8a4d4e470d0a1a0a", "mng", "video/x-mng"), // .MNG....
      e("8b4a4e470d0a1a0a", "jng", "image/x-jng"), // .JNG....
      e("b168de3a", "dcx", "image/vnd.zbrush.dcx"),
      e("bebafeca", "macho-fat", "application/x-mach-o-universal"),
      e("bfbafeca", "macho-fat", "application/x-mach-o-universal"),
      e("c5d0d3c6", "ps", "application/postscript"),
      e("cafebabf", "macho-fat", "application/x-mach-o-universal"),
      e("cefaedfe", "macho", "application/x-mach-o"),
      e("cffaedfe", "macho", "application/x-mach-o"),
      e("edabeedb", "rpm", "application/x-rpm"),
      e("fe370023", "doc", "application/msword"),
      e("feedface", "macho", "application/x-mach-o"),
      e("feedfacf", "macho", "application/x-mach-o"),
      e("ff4fff51", "j2c", "image/x-jp2-codestream"));

  /** All entries, longest magic first. */
  static final List<Entry> ENTRIES;

  /** The distinct base64 prefixes of all entries, for bare-run scanning. */
  static final List<String> PREFIXES;

  static {
    final List<Entry> entries = new ArrayList<>(CORE);
    entries.addAll(DERIVED);
    entries.sort((a, b) -> Integer.compare(b.magic().length, a.magic().length));
    ENTRIES = List.copyOf(entries);
    final Set<String> prefixes = new LinkedHashSet<>();
    for (final Entry entry : ENTRIES) {
      prefixes.add(entry.prefix());
    }
    PREFIXES = List.copyOf(prefixes);
  }

  /** Prevents construction of the signature table utility. */
  private KnownMagics() {
  }

  /**
   * Identifies the format and media type of decoded header bytes.
   *
   * @param header The decoded leading bytes.
   * @return Both identifiers from the longest matching signature, or {@code null}
   *         when the bytes match no known signature.
   */
  static Format formatOf(byte[] header) {
    for (final Entry entry : ENTRIES) {
      if (startsWith(header, entry.magic())) {
        return entry.format();
      }
    }
    return null;
  }

  /**
   * Whether the bytes start with the prefix.
   *
   * @param bytes The bytes.
   * @param prefix The expected leading bytes.
   * @return {@code true} on a full match within bounds.
   */
  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (bytes[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Builds an entry from the hex form of its magic, precomputing the base64 prefix the
   * magic fully determines: three bytes fix four characters, and a trailing one or two
   * bytes fix one or two more.
   *
   * @param hex The magic bytes in hex.
   * @param format The format tag.
   * @param mediaType The media type.
   * @return The entry. Never {@code null}.
   */
  private static Entry e(String hex, String format, String mediaType) {
    final byte[] magic = HexFormat.of().parseHex(hex);
    final String encoded = Base64.getEncoder().withoutPadding().encodeToString(magic);
    return new Entry(magic, encoded.substring(0, magic.length * 8 / 6), new Format(format, mediaType));
  }
}
