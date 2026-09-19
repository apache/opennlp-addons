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

package opennlp.tools.formats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import opennlp.tools.chunker.ChunkerAnnotator;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.coref.CorefAnnotator;
import opennlp.tools.coref.CorefMention;
import opennlp.tools.coref.CorefModel;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Scores pronoun resolution on GAP (Webster et al., TACL 2018), 8,908 Wikipedia
 * snippets each naming one pronoun and two candidate names with a coreference label
 * for each name. Unlike the OntoGUM harness, every layer is predicted: sentences and
 * tokens by the {@code opennlp-en-ud-ewt} models, Penn tags by {@code en-pos-maxent},
 * entities and chunks as for OntoGUM. A name counts as resolved when any mention in the
 * pronoun's chain overlaps its span.
 *
 * <p>Runs when {@code opennlp.coref.gap.dir} names a directory with the GAP TSV files
 * (Apache License 2.0, fetched by the runner); {@code opennlp.coref.gap.split} chooses
 * {@code development} (default), {@code validation}, or {@code test}, and
 * {@code opennlp.coref.model} a ranking model; {@code opennlp.coref.gap.dump} names a
 * file that receives, per snippet, the labels, the predictions, and the pronoun's chain.
 * Reports the F1 over both labels of every
 * snippet, split by the pronoun's gender, and their ratio, the paper's bias measure.
 * Rows append to {@code target/gap-eval-results.csv}.</p>
 */
public class GapCorefEvalTest {

  private static final Logger LOG = LoggerFactory.getLogger(GapCorefEvalTest.class);

  private static final String GAP_DIR_PROPERTY = "opennlp.coref.gap.dir";
  private static final String SPLIT_PROPERTY = "opennlp.coref.gap.split";
  private static final String MODEL_PROPERTY = "opennlp.coref.model";
  private static final String DUMP_PROPERTY = "opennlp.coref.gap.dump";
  private static final Path RESULTS_FILE = Path.of("target", "gap-eval-results.csv");
  private static final Set<String> MASCULINE = Set.of("he", "his", "him");

  /** True and false positive and false negative counts of one group. */
  private static final class Counts {
    int tp;
    int fp;
    int fn;

    void add(boolean gold, boolean predicted) {
      if (gold && predicted) {
        tp++;
      } else if (predicted) {
        fp++;
      } else if (gold) {
        fn++;
      }
    }

    double f1() {
      final double p = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
      final double r = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
      return p + r == 0 ? 0 : 2 * p * r / (p + r);
    }
  }

  @Test
  @EnabledIfSystemProperty(named = GAP_DIR_PROPERTY, matches = ".+")
  void testScoresGapSplit() throws IOException {
    final String split = System.getProperty(SPLIT_PROPERTY, "development");
    final Path file = Path.of(System.getProperty(GAP_DIR_PROPERTY), "gap-" + split + ".tsv");
    final Path models = CorefEvalSupport.modelsDirectory();
    final SentenceDetectorME sentences = new SentenceDetectorME(new SentenceModel(
        models.resolve("opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin")));
    final TokenizerME tokenizer = new TokenizerME(new TokenizerModel(
        models.resolve("opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin")));
    // The chunker and the mention detector read Penn tags; the tagger converts to UD by default.
    final POSTaggerME tagger = new POSTaggerME(new POSModel(models.resolve("en-pos-maxent.bin")),
        POSTagFormat.PENN);
    final List<NameFinderME> finders = CorefEvalSupport.nameFinders(models);
    final ChunkerAnnotator chunker = new ChunkerAnnotator(
        new ChunkerME(new ChunkerModel(models.resolve("en-chunker.bin"))));
    final String modelPath = System.getProperty(MODEL_PROPERTY);
    final CorefAnnotator annotator = modelPath == null ? new CorefAnnotator()
        : new CorefAnnotator(new CorefModel(Path.of(modelPath)));

    final Counts all = new Counts();
    final Counts masculine = new Counts();
    final Counts feminine = new Counts();
    int examples = 0;
    final StringBuilder dump = new StringBuilder();
    for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      final String[] fields = CorefEvalSupport.splitOn(line, '\t');
      if (fields.length < 10 || "ID".equals(fields[0])) {
        continue;
      }
      final String text = fields[1];
      final Span pronoun = new Span(Integer.parseInt(fields[3]),
          Integer.parseInt(fields[3]) + fields[2].length());
      final Span a = new Span(Integer.parseInt(fields[5]),
          Integer.parseInt(fields[5]) + fields[4].length());
      final Span b = new Span(Integer.parseInt(fields[8]),
          Integer.parseInt(fields[8]) + fields[7].length());
      final Document resolved = annotator.annotate(chunker.annotate(
          CorefEvalSupport.withEntities(preprocess(text, sentences, tokenizer, tagger), finders)));
      final List<Annotation<CorefMention>> chains = resolved.get(CorefAnnotator.CHAINS);
      int chain = -1;
      for (final Annotation<CorefMention> mention : chains) {
        if (mention.span().equals(pronoun)) {
          chain = mention.value().chain();
        }
      }
      final boolean aPredicted = linked(chains, chain, a);
      final boolean bPredicted = linked(chains, chain, b);
      final boolean aGold = Boolean.parseBoolean(fields[6]);
      final boolean bGold = Boolean.parseBoolean(fields[9]);
      final Counts gender = MASCULINE.contains(StringUtil.toLowerCase(fields[2]))
          ? masculine : feminine;
      for (final Counts counts : new Counts[] {all, gender}) {
        counts.add(aGold, aPredicted);
        counts.add(bGold, bPredicted);
      }
      examples++;
      if (System.getProperty(DUMP_PROPERTY) != null) {
        dump.append(fields[0]).append('\t').append(fields[2]).append(" chain ").append(chain)
            .append(" A=").append(fields[4]).append(' ').append(aGold).append('/').append(aPredicted)
            .append(" B=").append(fields[7]).append(' ').append(bGold).append('/').append(bPredicted)
            .append(" |");
        for (final Annotation<CorefMention> mention : chains) {
          if (mention.value().chain() == chain
              || chain < 0 && mention.span().intersects(pronoun)) {
            dump.append(' ').append(mention.value().kind()).append(':')
                .append(text, mention.span().getStart(), mention.span().getEnd());
          }
        }
        if (chain < 0) {
          for (final Annotation<String> token : resolved.get(Layers.TOKENS)) {
            if (token.span().intersects(pronoun)) {
              dump.append(" token:").append(token.value()).append('/')
                  .append(resolved.get(Layers.POS_TAGS).get(
                      resolved.get(Layers.TOKENS).indexOf(token)).value());
            }
          }
        }
        dump.append('\n');
      }
    }
    if (System.getProperty(DUMP_PROPERTY) != null) {
      Files.writeString(Path.of(System.getProperty(DUMP_PROPERTY)), dump.toString());
    }
    Assertions.assertTrue(examples > 0, "no GAP examples in " + file);
    final double bias = masculine.f1() == 0 ? 0 : feminine.f1() / masculine.f1();
    LOG.info("GAP {} ({} snippets): F1 {} | masculine {} | feminine {} | bias {}", split,
        examples, percent(all.f1()), percent(masculine.f1()), percent(feminine.f1()),
        String.format(Locale.ROOT, "%.2f", bias));
    Files.createDirectories(RESULTS_FILE.getParent());
    if (!Files.exists(RESULTS_FILE)) {
      Files.writeString(RESULTS_FILE, "time,split,snippets,f1,masculine_f1,feminine_f1,bias\n");
    }
    Files.writeString(RESULTS_FILE, String.format(Locale.ROOT, "%s,%s,%d,%.4f,%.4f,%.4f,%.3f%n",
        Instant.now(), split, examples, all.f1(), masculine.f1(), feminine.f1(), bias),
        java.nio.file.StandardOpenOption.APPEND);
    Assertions.assertTrue(all.f1() > 0.2, "GAP F1 collapsed: " + all.f1());
  }

  /** {@return whether a mention of the pronoun's chain other than the pronoun overlaps a name} */
  private static boolean linked(List<Annotation<CorefMention>> chains, int chain, Span name) {
    if (chain < 0) {
      return false;
    }
    for (final Annotation<CorefMention> mention : chains) {
      if (mention.value().chain() == chain && mention.span().intersects(name)
          && !CorefMention.KIND_PRONOUN.equals(mention.value().kind())) {
        return true;
      }
    }
    return false;
  }

  /** Splits, tokenizes, and tags a snippet into a document with the three base layers. */
  private static Document preprocess(String text, SentenceDetectorME sentences,
      TokenizerME tokenizer, POSTaggerME tagger) {
    final List<Annotation<String>> sentenceLayer = new ArrayList<>();
    final List<Annotation<String>> tokenLayer = new ArrayList<>();
    final List<Annotation<String>> tagLayer = new ArrayList<>();
    for (final Span sentence : sentences.sentPosDetect(text)) {
      final String sentenceText = text.substring(sentence.getStart(), sentence.getEnd());
      sentenceLayer.add(new Annotation<>(sentence, sentenceText));
      final Span[] tokens = tokenizer.tokenizePos(sentenceText);
      final String[] words = new String[tokens.length];
      for (int t = 0; t < tokens.length; t++) {
        words[t] = sentenceText.substring(tokens[t].getStart(), tokens[t].getEnd());
      }
      final String[] tags = tagger.tag(words);
      for (int t = 0; t < tokens.length; t++) {
        final Span span = new Span(sentence.getStart() + tokens[t].getStart(),
            sentence.getStart() + tokens[t].getEnd());
        tokenLayer.add(new Annotation<>(span, words[t]));
        tagLayer.add(new Annotation<>(span, tags[t]));
      }
    }
    return Document.of(text)
        .with(Layers.SENTENCES, sentenceLayer)
        .with(Layers.TOKENS, tokenLayer)
        .with(Layers.POS_TAGS, tagLayer);
  }

  private static String percent(double value) {
    return String.format(Locale.ROOT, "%.1f", 100 * value);
  }
}
