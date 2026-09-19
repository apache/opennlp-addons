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

package opennlp.tools.coref;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import opennlp.tools.coref.Mention.Animacy;
import opennlp.tools.coref.Mention.Gender;
import opennlp.tools.coref.Mention.Number;
import opennlp.tools.coref.Mention.Person;

/**
 * The English word knowledge the mention detector and sieves consult: the pronoun
 * table, nouns that carry a gender or animacy, name titles, the words that never count
 * as name content, and the first-name gender list.
 *
 * <p>The first names come from the bundled {@code first-names-en.txt}, an aggregate of
 * the United States Social Security Administration's baby name tables restricted to
 * names given to one gender at least 90% of the time; ambiguous names are absent and
 * so leave the gender unknown. Every lookup takes lowercased input.</p>
 */
final class CorefLexicon {

  /**
   * One pronoun's attributes.
   *
   * @param person The grammatical person.
   * @param number The number.
   * @param gender The gender.
   * @param animacy The animacy.
   */
  record Pronoun(Person person, Number number, Gender gender, Animacy animacy) {
  }

  private static final Pronoun HE =
      new Pronoun(Person.THIRD, Number.SINGULAR, Gender.MALE, Animacy.ANIMATE);
  private static final Pronoun SHE =
      new Pronoun(Person.THIRD, Number.SINGULAR, Gender.FEMALE, Animacy.ANIMATE);
  private static final Pronoun IT =
      new Pronoun(Person.THIRD, Number.SINGULAR, Gender.NEUTRAL, Animacy.INANIMATE);
  private static final Pronoun THEY =
      new Pronoun(Person.THIRD, Number.PLURAL, Gender.UNKNOWN, Animacy.UNKNOWN);
  private static final Pronoun I =
      new Pronoun(Person.FIRST, Number.SINGULAR, Gender.UNKNOWN, Animacy.ANIMATE);
  private static final Pronoun WE =
      new Pronoun(Person.FIRST, Number.PLURAL, Gender.UNKNOWN, Animacy.ANIMATE);
  private static final Pronoun YOU =
      new Pronoun(Person.SECOND, Number.UNKNOWN, Gender.UNKNOWN, Animacy.ANIMATE);

  private static final Pronoun THIS =
      new Pronoun(Person.THIRD, Number.SINGULAR, Gender.NEUTRAL, Animacy.INANIMATE);
  private static final Pronoun THESE =
      new Pronoun(Person.THIRD, Number.PLURAL, Gender.NEUTRAL, Animacy.INANIMATE);

  /**
   * The demonstratives that stand alone as pronouns, {@code That was costly}. They are
   * kept apart from {@link #PRONOUNS} because they mostly point at clauses and events,
   * which the rule sieves do not resolve, while a ranker may learn when they refer to
   * an entity.
   */
  private static final Map<String, Pronoun> DEMONSTRATIVES = Map.of(
      "this", THIS, "that", THIS, "these", THESE, "those", THESE);

  private static final Map<String, Pronoun> PRONOUNS = Map.ofEntries(
      Map.entry("he", HE), Map.entry("him", HE), Map.entry("his", HE),
      Map.entry("himself", HE),
      Map.entry("she", SHE), Map.entry("her", SHE), Map.entry("hers", SHE),
      Map.entry("herself", SHE),
      Map.entry("it", IT), Map.entry("its", IT), Map.entry("itself", IT),
      Map.entry("they", THEY), Map.entry("them", THEY), Map.entry("their", THEY),
      Map.entry("theirs", THEY), Map.entry("themselves", THEY),
      Map.entry("i", I), Map.entry("me", I), Map.entry("my", I), Map.entry("mine", I),
      Map.entry("myself", I),
      Map.entry("we", WE), Map.entry("us", WE), Map.entry("our", WE),
      Map.entry("ours", WE), Map.entry("ourselves", WE),
      Map.entry("you", YOU), Map.entry("your", YOU), Map.entry("yours", YOU),
      Map.entry("yourself", YOU), Map.entry("yourselves", YOU));

  /** Nouns whose referent is male. */
  private static final Set<String> MALE_NOUNS = Set.of(
      "man", "men", "boy", "boys", "father", "fathers", "dad", "daddy", "son", "sons",
      "brother", "brothers", "husband", "husbands", "uncle", "uncles", "nephew",
      "nephews", "grandfather", "grandfathers", "grandson", "grandsons", "king",
      "kings", "prince", "princes", "lord", "lords", "gentleman", "gentlemen", "sir",
      "mr", "mr.", "mister", "actor", "actors", "boyfriend", "boyfriends", "widower",
      "monk", "monks", "priest", "priests", "duke", "dukes", "emperor", "emperors",
      "hero", "heroes", "guy", "guys", "lad", "lads", "waiter", "waiters", "steward",
      "stepfather", "godfather", "groom", "bridegroom", "male", "males", "fiance");

  /** Nouns whose referent is female. */
  private static final Set<String> FEMALE_NOUNS = Set.of(
      "woman", "women", "girl", "girls", "mother", "mothers", "mom", "mommy", "mum",
      "daughter", "daughters", "sister", "sisters", "wife", "wives", "aunt", "aunts",
      "niece", "nieces", "grandmother", "grandmothers", "granddaughter",
      "granddaughters", "queen", "queens", "princess", "princesses", "lady", "ladies",
      "madam", "ma'am", "mrs", "mrs.", "ms", "ms.", "miss", "actress", "actresses",
      "girlfriend", "girlfriends", "widow", "widows", "nun", "nuns", "duchess",
      "empress", "heroine", "heroines", "gal", "waitress", "waitresses", "stewardess",
      "stepmother", "godmother", "bride", "brides", "female", "females", "fiancee",
      "maid", "maids", "mistress");

  /** Nouns whose referent is a person or group of people without a gender. */
  private static final Set<String> ANIMATE_NOUNS = Set.of(
      "person", "people", "persons", "child", "children", "kid", "kids", "baby",
      "babies", "infant", "infants", "adult", "adults", "teenager", "teenagers",
      "parent", "parents", "family", "families", "friend", "friends", "neighbor",
      "neighbors", "neighbour", "neighbours", "partner", "partners", "spouse",
      "spouses", "sibling", "siblings", "cousin", "cousins", "relative", "relatives",
      "student", "students", "teacher", "teachers", "professor", "professors",
      "doctor", "doctors", "dr", "dr.", "nurse", "nurses", "patient", "patients",
      "lawyer", "lawyers", "attorney", "attorneys", "judge", "judges", "officer",
      "officers", "police", "soldier", "soldiers", "general", "generals", "captain",
      "captains", "president", "presidents", "minister", "ministers", "senator",
      "senators", "governor", "governors", "mayor", "mayors", "secretary",
      "secretaries", "chairman", "chairwoman", "chair", "director", "directors",
      "manager", "managers", "leader", "leaders", "founder", "founders", "owner",
      "owners", "chief", "chiefs", "boss", "bosses", "employee", "employees",
      "worker", "workers", "staff", "colleague", "colleagues", "member", "members",
      "citizen", "citizens", "resident", "residents", "voter", "voters", "customer",
      "customers", "client", "clients", "user", "users", "reader", "readers",
      "writer", "writers", "author", "authors", "poet", "poets", "artist", "artists",
      "singer", "singers", "musician", "musicians", "player", "players", "coach",
      "coaches", "athlete", "athletes", "driver", "drivers", "pilot", "pilots",
      "farmer", "farmers", "scientist", "scientists", "researcher", "researchers",
      "engineer", "engineers", "expert", "experts", "spokesman", "spokeswoman",
      "spokesperson", "official", "officials", "critic", "critics", "witness",
      "witnesses", "victim", "victims", "suspect", "suspects", "survivor",
      "survivors", "speaker", "speakers", "host", "hosts", "guest", "guests",
      "visitor", "visitors", "traveler", "travelers", "tourist", "tourists",
      "passenger", "passengers", "audience", "crowd", "public", "team", "teams",
      "crew", "couple", "couples", "everyone", "everybody", "someone", "somebody",
      "anyone", "anybody", "nobody", "human", "humans", "individual", "individuals",
      "character", "characters", "villager", "villagers", "native", "natives",
      "immigrant", "immigrants", "refugee", "refugees", "prisoner", "prisoners",
      "veteran", "veterans", "philosopher", "philosophers", "historian", "historians",
      "economist", "economists", "physician", "physicians", "surgeon", "surgeons",
      "psychologist", "psychologists", "journalist", "journalists", "reporter",
      "reporters", "editor", "editors", "photographer", "photographers", "designer",
      "designers", "architect", "architects", "chef", "chefs", "cook", "cooks",
      "clerk", "clerks", "guard", "guards", "agent", "agents", "spy", "spies",
      "thief", "thieves", "killer", "killers", "criminal", "criminals", "inmate",
      "inmates", "candidate", "candidates", "applicant", "applicants", "graduate",
      "graduates", "scholar", "scholars", "fellow", "fellows", "pioneer",
      "pioneers", "champion", "champions", "winner", "winners", "loser", "losers",
      "rival", "rivals", "opponent", "opponents", "enemy", "enemies", "ally",
      "allies", "stranger", "strangers", "lover", "lovers", "fan", "fans",
      "supporter", "supporters", "follower", "followers", "believer", "believers",
      "buyer", "buyers", "seller", "sellers", "consumer", "consumers", "investor",
      "investors", "shareholder", "shareholders", "tenant", "tenants", "landlord",
      "landlords", "servant", "servants", "slave", "slaves", "master", "masters",
      "pupil", "pupils", "apprentice", "apprentices", "mentor", "mentors",
      "instructor", "instructors", "trainer", "trainers", "volunteer", "volunteers",
      "activist", "activists", "protester", "protesters", "politician",
      "politicians", "diplomat", "diplomats", "ambassador", "ambassadors",
      "commander", "commanders", "lieutenant", "lieutenants", "sergeant",
      "sergeants", "colonel", "colonels", "admiral", "admirals", "knight",
      "knights", "warrior", "warriors", "hunter", "hunters", "sailor", "sailors",
      "merchant", "merchants", "trader", "traders", "banker", "bankers",
      "accountant", "accountants", "dentist", "dentists", "pharmacist",
      "pharmacists", "therapist", "therapists", "counselor", "counselors",
      "priestess", "bishop", "bishops", "pope", "popes", "saint", "saints",
      "prophet", "prophets", "god", "gods", "goddess", "goddesses", "angel",
      "angels", "creature", "creatures", "animal", "animals", "dog", "dogs", "cat",
      "cats", "horse", "horses", "bird", "birds");

  /** Titles that precede a person's name, mapped to the gender they imply. */
  private static final Map<String, Gender> TITLES = Map.ofEntries(
      Map.entry("mr", Gender.MALE), Map.entry("mr.", Gender.MALE),
      Map.entry("mister", Gender.MALE), Map.entry("sir", Gender.MALE),
      Map.entry("lord", Gender.MALE), Map.entry("king", Gender.MALE),
      Map.entry("prince", Gender.MALE), Map.entry("duke", Gender.MALE),
      Map.entry("mrs", Gender.FEMALE), Map.entry("mrs.", Gender.FEMALE),
      Map.entry("ms", Gender.FEMALE), Map.entry("ms.", Gender.FEMALE),
      Map.entry("miss", Gender.FEMALE), Map.entry("lady", Gender.FEMALE),
      Map.entry("madam", Gender.FEMALE), Map.entry("queen", Gender.FEMALE),
      Map.entry("princess", Gender.FEMALE), Map.entry("duchess", Gender.FEMALE),
      Map.entry("dr", Gender.UNKNOWN), Map.entry("dr.", Gender.UNKNOWN),
      Map.entry("doctor", Gender.UNKNOWN), Map.entry("professor", Gender.UNKNOWN),
      Map.entry("prof", Gender.UNKNOWN), Map.entry("prof.", Gender.UNKNOWN),
      Map.entry("president", Gender.UNKNOWN), Map.entry("senator", Gender.UNKNOWN),
      Map.entry("governor", Gender.UNKNOWN), Map.entry("general", Gender.UNKNOWN),
      Map.entry("captain", Gender.UNKNOWN), Map.entry("judge", Gender.UNKNOWN),
      Map.entry("rev", Gender.UNKNOWN), Map.entry("rev.", Gender.UNKNOWN),
      Map.entry("st", Gender.UNKNOWN), Map.entry("st.", Gender.UNKNOWN));

  /**
   * Words carrying no name content, excluded from word inclusion and modifier checks so
   * that shared articles, linkers, and possessive markers alone never support a link.
   */
  private static final Set<String> STOP_WORDS = Set.of(
      "the", "a", "an", "of", "and", "or", "this", "that", "these", "those", "'s",
      "'", "its", "his", "her", "their", "my", "our", "your", "in", "on", "at",
      "for", "to", "by", "with", "from", "as");

  /** Determiners and quantifiers that open an indefinite noun phrase. */
  private static final Set<String> INDEFINITE_WORDS = Set.of(
      "a", "an", "some", "any", "another", "many", "several", "few", "no", "each",
      "every", "much", "more", "most", "such", "other", "others", "one", "various",
      "certain");

  /**
   * Heads of compound place and institution names, such as {@code city} in
   * {@code Kansas City}, whose presence tells a compound apart from the bare name.
   */
  private static final Set<String> COMPOUND_NAME_HEADS = Set.of(
      "city", "county", "state", "province", "region", "district", "territory",
      "kingdom", "republic", "island", "islands", "river", "lake", "bay", "sea",
      "ocean", "mountain", "mountains", "valley", "park", "street", "avenue", "road",
      "square", "bridge", "airport", "station", "university", "college", "school",
      "hospital", "church", "cathedral", "castle", "palace", "museum", "library",
      "hall", "center", "centre", "stadium", "tower", "hotel", "beach", "harbor",
      "harbour", "port", "fort", "canal", "strait", "peninsula", "desert", "forest");

  /** Verbs that make a following {@code it} pleonastic. */
  private static final Set<String> PLEONASTIC_VERBS = Set.of(
      "is", "was", "'s", "be", "been", "being", "seems", "seemed", "seem", "appears",
      "appeared", "appear", "looks", "looked", "look", "means", "meant", "mean",
      "turns", "turned", "turn", "follows", "followed", "follow", "happens",
      "happened", "happen", "remains", "remained", "remain", "makes", "made", "make",
      "takes", "took", "take", "became", "becomes", "become", "helps", "helped",
      "help", "matters", "mattered", "matter");

  /** Words that complete a pleonastic {@code it} construction. */
  private static final Set<String> PLEONASTIC_COMPLEMENTS = Set.of(
      "that", "to", "whether", "if", "how", "what", "why", "when", "where", "like",
      "as", "out", "because", "sense");

  /** Verbs of speech and thought that attribute an adjacent quotation to a speaker. */
  private static final Set<String> SPEECH_VERBS = Set.of(
      "said", "says", "say", "saying", "wrote", "writes", "write", "asked", "asks",
      "ask", "told", "tells", "tell", "replied", "replies", "reply", "added", "adds",
      "noted", "notes", "explained", "explains", "recalled", "recalls", "argued",
      "argues", "claimed", "claims", "stated", "states", "declared", "declares",
      "insisted", "insists", "answered", "answers", "continued", "continues",
      "remarked", "remarks", "observed", "observes", "admitted", "admits", "warned",
      "warns", "suggested", "suggests", "thought", "thinks", "felt", "feels",
      "believes", "believed", "shouted", "shouts", "whispered", "whispers", "cried",
      "cries", "responded", "responds", "commented", "comments", "announced",
      "announces", "concluded", "concludes", "agreed", "agrees", "joked", "jokes",
      "muttered", "mutters", "exclaimed", "exclaims", "laughed", "laughs", "sighed",
      "sighs", "promised", "promises", "complained", "complains", "quipped",
      "recounts", "recounted", "describes", "described", "tweeted", "posted");

  /** Tokens that open a quotation. */
  private static final Set<String> OPENING_QUOTES = Set.of("\"", "\u201c", "``", "\u00ab");

  /** Tokens that close a quotation. */
  private static final Set<String> CLOSING_QUOTES = Set.of("\"", "\u201d", "''", "\u00bb");

  /** Lazily loads the first-name gender list on first use. */
  private static final class FirstNames {

    /** The bundled first-name genders. */
    static final Map<String, Gender> BY_NAME = load();

    /** Prevents construction of the resource holder. */
    private FirstNames() {
    }

    /**
     * Reads the bundled first-name genders.
     *
     * @return The names by lowercase spelling.
     * @throws IllegalStateException Thrown if the resource is missing or malformed.
     * @throws UncheckedIOException Thrown if the resource cannot be read.
     */
    private static Map<String, Gender> load() {
      final Map<String, Gender> names = new HashMap<>();
      try (InputStream in = CorefLexicon.class.getResourceAsStream("first-names-en.txt")) {
        if (in == null) {
          throw new IllegalStateException("first-names-en.txt is missing");
        }
        final BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty() || line.charAt(0) == '#') {
            continue;
          }
          final int tab = line.indexOf('\t');
          if (tab <= 0 || tab + 1 >= line.length()) {
            throw new IllegalStateException("malformed first name entry: " + line);
          }
          names.put(line.substring(0, tab),
              line.charAt(tab + 1) == 'm' ? Gender.MALE : Gender.FEMALE);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return Map.copyOf(names);
    }
  }

  /** Prevents construction of this lookup class. */
  private CorefLexicon() {
  }

  /**
   * Looks up a pronoun.
   *
   * @param word The lowercased word.
   * @return The pronoun's attributes, or {@code null} if the word is no pronoun.
   */
  static Pronoun pronoun(String word) {
    return PRONOUNS.get(word);
  }

  /**
   * Looks up a demonstrative used as a pronoun.
   *
   * @param word The lowercased word.
   * @return Its attributes, or {@code null} if the word is not a demonstrative.
   */
  static Pronoun demonstrative(String word) {
    return DEMONSTRATIVES.get(word);
  }

  /**
   * Looks up the gender a noun or title implies.
   *
   * @param word The lowercased word.
   * @return The gender, {@code UNKNOWN} for a word without one.
   */
  static Gender nounGender(String word) {
    if (MALE_NOUNS.contains(word)) {
      return Gender.MALE;
    }
    if (FEMALE_NOUNS.contains(word)) {
      return Gender.FEMALE;
    }
    return TITLES.getOrDefault(word, Gender.UNKNOWN);
  }

  /**
   * Checks whether a noun names a person, group of people, or animal.
   *
   * @param word The lowercased word.
   * @return {@code true} if the noun is animate.
   */
  static boolean animateNoun(String word) {
    return ANIMATE_NOUNS.contains(word) || MALE_NOUNS.contains(word)
        || FEMALE_NOUNS.contains(word);
  }

  /**
   * Checks whether a word is a title that precedes a name.
   *
   * @param word The lowercased word.
   * @return {@code true} for a title.
   */
  static boolean title(String word) {
    return TITLES.containsKey(word);
  }

  /**
   * Looks up the gender of a first name.
   *
   * @param name The lowercased name.
   * @return The gender, {@code UNKNOWN} for an unlisted or ambiguous name.
   */
  static Gender firstNameGender(String name) {
    return FirstNames.BY_NAME.getOrDefault(name, Gender.UNKNOWN);
  }

  /**
   * Checks whether a word carries no name content.
   *
   * @param word The lowercased word.
   * @return {@code true} for a stop word or a token without a letter or digit.
   */
  static boolean stopWord(String word) {
    if (STOP_WORDS.contains(word)) {
      return true;
    }
    for (int i = 0; i < word.length(); ) {
      final int codePoint = word.codePointAt(i);
      if (Character.isLetterOrDigit(codePoint)) {
        return false;
      }
      i += Character.charCount(codePoint);
    }
    return true;
  }

  /**
   * Checks whether a word is a verb of speech or thought.
   *
   * @param word The lowercased word.
   * @return Whether it is a verb of speech or thought.
   */
  static boolean speechVerb(String word) {
    return SPEECH_VERBS.contains(word);
  }

  /**
   * Checks whether a token opens a quotation.
   *
   * @param token The token.
   * @return Whether it opens a quotation.
   */
  static boolean opensQuote(String token) {
    return OPENING_QUOTES.contains(token);
  }

  /**
   * Checks whether a token closes a quotation.
   *
   * @param token The token.
   * @return Whether it closes a quotation.
   */
  static boolean closesQuote(String token) {
    return CLOSING_QUOTES.contains(token);
  }

  /**
   * Checks whether a word opens an indefinite noun phrase.
   *
   * @param word The lowercased word.
   * @return Whether it opens an indefinite noun phrase.
   */
  static boolean indefiniteWord(String word) {
    return INDEFINITE_WORDS.contains(word);
  }

  /**
   * Checks whether a word heads a compound place or institution name.
   *
   * @param word The lowercased word.
   * @return Whether it heads a compound place or institution name.
   */
  static boolean compoundNameHead(String word) {
    return COMPOUND_NAME_HEADS.contains(word);
  }

  /**
   * Checks whether a word can head a pleonastic {@code it} construction.
   *
   * @param word The lowercased word.
   * @return Whether it is a verb that can make {@code it} pleonastic.
   */
  static boolean pleonasticVerb(String word) {
    return PLEONASTIC_VERBS.contains(word);
  }

  /**
   * Checks whether a word completes a pleonastic {@code it} construction.
   *
   * @param word The lowercased word.
   * @return Whether it completes a pleonastic {@code it} construction.
   */
  static boolean pleonasticComplement(String word) {
    return PLEONASTIC_COMPLEMENTS.contains(word);
  }
}
