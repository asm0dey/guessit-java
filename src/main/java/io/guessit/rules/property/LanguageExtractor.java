package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.lang.Language;
import io.guessit.lang.LanguageRegistry;

import java.util.*;

/**
 * Extracts {@code language} and {@code subtitle_language}.
 *
 * <p>Pipeline within this extractor:
 * <ol>
 *   <li>Word-level scan ({@link #extract}). For each non-numeric word: try a
 *       direct {@link LanguageRegistry} lookup; otherwise try stripping a
 *       configured subtitle/language prefix or suffix and lookup the rest.
 *       Matches start out as {@code language}.</li>
 *   <li>Standalone affixes (e.g. "subs", "vostfr") are emitted as private
 *       {@code subtitle_language.prefix} markers; {@link #renameStandaloneAffixes}
 *       finds the adjacent {@code language} match (in either direction, with
 *       group-marker awareness) and renames it to {@code subtitle_language}.</li>
 *   <li>Other post-passes drop common-word false positives, drop
 *       {@code Undetermined} when a real language is also present, rename
 *       languages that sit immediately before a subtitle extension, and
 *       remove a release-group match that has been shadowed by a subtitle
 *       language found inside it.</li>
 * </ol>
 *
 * <p>Filtering by {@code allowed_languages} happens at extract time so that
 * configuring a smaller language set genuinely shrinks the candidate space
 * (and thus the conflict graph).
 */
public final class LanguageExtractor implements Extractor {

    public static final String SUBTITLE_LANGUAGE = "subtitle_language";
    public static final String LANGUAGE = "language";
    /**
     * Private match name for a standalone language-suffix word (e.g. {@code AUDIO})
     * abutting a language match. Mirrors python's {@code language.suffix} match,
     * used by {@link ReleaseGroupExtractor} to widen the scene-prev boundary so
     * the release-group candidate skips past the suffix.
     */
    public static final String LANGUAGE_SUFFIX = "language.suffix";

    @Override
    public String name() {
        return LANGUAGE;
    }

    private static final String UND_NAME = "Undetermined";
    private static final String MUL_NAME = "Multiple languages";
    private static final String MARKER_PREFIX = "subtitle_language.prefix";

    @Override
    public void extract(ParseContext ctx) {
        var allowed = allowedLanguages(ctx);
        if (allowed.isEmpty()) return;

        var registry = LanguageRegistry.instance();
        var affixes = loadAffixConfiguration(ctx);
        var words = Words.iter(ctx.input);

        var pairConsumed = new HashSet<Integer>();
        var countryWordConsumed = new HashSet<Integer>();

        extractTwoWordLanguages(ctx, words, pairConsumed, allowed, registry);
        extractTwoWordSubtitleAffixes(ctx, words, pairConsumed, affixes);
        extractSingleWordLanguages(ctx, words, pairConsumed, countryWordConsumed, allowed, registry, affixes);
        extractLanguageSuffixes(ctx, words, affixes.languageSuffixes);
    }

    private record AffixConfiguration(
            List<String> subtitlePrefixes,
            List<String> subtitleSuffixes,
            List<String> languageAffixes,
            List<String> languagePrefixes,
            List<String> languageSuffixes) {
    }

    private AffixConfiguration loadAffixConfiguration(ParseContext ctx) {
        var section = ctx.config.section(LANGUAGE);
        var subtitleAffixes = stringList(section.get("subtitle_affixes"));
        var subtitlePrefixes = combine(subtitleAffixes, stringList(section.get("subtitle_prefixes")));
        var subtitleSuffixes = combine(subtitleAffixes, stringList(section.get("subtitle_suffixes")));
        var languageAffixes = stringList(section.get("language_affixes"));
        var languagePrefixes = combine(languageAffixes, stringList(section.get("language_prefixes")));
        var languageSuffixes = combine(languageAffixes, stringList(section.get("language_suffixes")));
        return new AffixConfiguration(subtitlePrefixes, subtitleSuffixes, languageAffixes,
                languagePrefixes, languageSuffixes);
    }

    private void extractTwoWordLanguages(ParseContext ctx, List<Words.Word> words,
                                         Set<Integer> pairConsumed, List<String> allowed,
                                         LanguageRegistry registry) {
        var input = ctx.input;
        for (int i = 0; i + 1 < words.size(); i++) {
            var w1 = words.get(i);
            var w2 = words.get(i + 1);
            var combined = (w1.value() + " " + w2.value()).toLowerCase(Locale.ROOT);
            var pairLang = registry.find(combined).orElse(null);
            if (pairLang == null || !isAllowed(pairLang, allowed)) continue;
            ctx.matches.add(new Match(LANGUAGE, pairLang, w1.start(), w2.end(),
                    input.substring(w1.start(), w2.end()), 1000, Set.of(), false));
            pairConsumed.add(i);
            pairConsumed.add(i + 1);
            i++;
        }
    }

    private void extractTwoWordSubtitleAffixes(ParseContext ctx, List<Words.Word> words,
                                               Set<Integer> pairConsumed, AffixConfiguration affixes) {
        var input = ctx.input;
        for (int i = 0; i + 1 < words.size(); i++) {
            if (pairConsumed.contains(i)) continue;
            var w1 = words.get(i);
            var w2 = words.get(i + 1);
            String gap = input.substring(w1.end(), w2.start());
            if (!gap.chars().allMatch(c -> Seps.isSep((char) c))) continue;
            var combined = (w1.value() + " " + w2.value()).toLowerCase(Locale.ROOT);
            if (matchesAny(combined, affixes.subtitlePrefixes) || matchesAny(combined, affixes.subtitleSuffixes)) {
                ctx.matches.add(new Match(MARKER_PREFIX, input.substring(w1.start(), w2.end()),
                        w1.start(), w2.end(), input.substring(w1.start(), w2.end()),
                        1000, Set.of(), true));
                pairConsumed.add(i);
                pairConsumed.add(i + 1);
            }
        }
    }

    private void extractSingleWordLanguages(ParseContext ctx, List<Words.Word> words,
                                            Set<Integer> pairConsumed, Set<Integer> countryWordConsumed,
                                            List<String> allowed, LanguageRegistry registry,
                                            AffixConfiguration affixes) {
        var input = ctx.input;
        for (int wi = 0; wi < words.size(); wi++) {
            if (pairConsumed.contains(wi) || countryWordConsumed.contains(wi)) continue;

            Words.Word word = words.get(wi);
            var lower = word.value().toLowerCase(Locale.ROOT);

            if (lower.chars().allMatch(Character::isDigit)) continue;

            if (tryProcessSubtitleAffix(ctx, word, lower, affixes)) continue;
            if (tryProcessLanguageAffix(ctx, word, lower, affixes, allowed, registry)) continue;
            if (tryProcessDirectLanguage(ctx, words, wi, word, lower, input, allowed, registry, countryWordConsumed))
                continue;

            tryProcessAffixStripping(ctx, word, lower, affixes, allowed, registry);
        }
    }

    private boolean tryProcessSubtitleAffix(ParseContext ctx, Words.Word word, String lower,
                                            AffixConfiguration affixes) {
        if (matchesAny(lower, affixes.subtitlePrefixes) || matchesAny(lower, affixes.subtitleSuffixes)) {
            ctx.matches.add(new Match(MARKER_PREFIX, word.value(),
                    word.start(), word.end(), word.value(), 1000, Set.of(), true));
            return true;
        }
        return false;
    }

    private boolean tryProcessLanguageAffix(ParseContext ctx, Words.Word word, String lower,
                                            AffixConfiguration affixes, List<String> allowed,
                                            LanguageRegistry registry) {
        if (matchesAny(lower, affixes.languageAffixes)) {
            var und = registry.find("und").orElse(null);
            if (und != null && isAllowed(und, allowed)) {
                ctx.matches.add(new Match(LANGUAGE, und, word.start(), word.end(),
                        word.value(), 1000, Set.of(), false));
                return true;
            }
        }
        return false;
    }

    private boolean tryProcessDirectLanguage(ParseContext ctx, List<Words.Word> words, int wi,
                                             Words.Word word, String lower, String input,
                                             List<String> allowed, LanguageRegistry registry,
                                             Set<Integer> countryWordConsumed) {
        var lang = registry.find(lower).orElse(null);
        if (lang == null || !isAllowed(lang, allowed)) return false;

        var result = tryParseCountryCode(words, wi, word, input, lang, registry);
        if (result.hasCountry) {
            countryWordConsumed.add(wi + 1);
        }

        ctx.matches.add(new Match(LANGUAGE, result.language, word.start(), result.matchEnd,
                input.substring(word.start(), result.matchEnd), 1000, Set.of(), false));
        return true;
    }

    private record LanguageWithCountryResult(Language language, int matchEnd, boolean hasCountry) {
    }

    private LanguageWithCountryResult tryParseCountryCode(List<Words.Word> words, int wi,
                                                          Words.Word word, String input,
                                                          Language lang, LanguageRegistry registry) {
        // Check if we have space for "-XX" pattern
        if (word.end() + 3 > input.length() || input.charAt(word.end()) != '-') {
            return new LanguageWithCountryResult(lang, word.end(), false);
        }

        String countryToken = input.substring(word.end() + 1, word.end() + 3);
        if (!countryToken.matches("[A-Za-z]{2}")) {
            return new LanguageWithCountryResult(lang, word.end(), false);
        }

        // Verify separator after country code
        boolean validEnd = word.end() + 3 == input.length() || Seps.isSep(input.charAt(word.end() + 3));
        if (!validEnd) {
            return new LanguageWithCountryResult(lang, word.end(), false);
        }

        var resolved = registry.findCountry(countryToken.toUpperCase(Locale.ROOT)).orElse(null);
        if (resolved == null) {
            return new LanguageWithCountryResult(lang, word.end(), false);
        }

        Language langWithCountry = new Language(lang.alpha2(), lang.alpha3(), lang.name(), resolved);
        int matchEnd = word.end() + 3;

        // Mark next word as consumed if it matches the country token
        boolean hasCountryWord = wi + 1 < words.size()
                && words.get(wi + 1).start() == word.end() + 1
                && words.get(wi + 1).end() == word.end() + 3;

        return new LanguageWithCountryResult(langWithCountry, matchEnd, hasCountryWord);
    }

    private void tryProcessAffixStripping(ParseContext ctx, Words.Word word, String lower,
                                          AffixConfiguration affixes, List<String> allowed,
                                          LanguageRegistry registry) {
        if (tryStripAffix(ctx, word, lower, affixes.subtitlePrefixes, true, allowed, registry, SUBTITLE_LANGUAGE))
            return;
        if (tryStripAffix(ctx, word, lower, affixes.languagePrefixes, true, allowed, registry, LANGUAGE))
            return;
        if (tryStripAffix(ctx, word, lower, affixes.subtitleSuffixes, false, allowed, registry, SUBTITLE_LANGUAGE))
            return;
        tryStripAffix(ctx, word, lower, affixes.languageSuffixes, false, allowed, registry, LANGUAGE);
    }

    private void extractLanguageSuffixes(ParseContext ctx, List<Words.Word> words,
                                         List<String> languageSuffixes) {
        var input = ctx.input;
        for (Words.Word word : words) {
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (!matchesAny(lower, languageSuffixes)) continue;
            int ws = word.start();
            ctx
                    .matches
                    .named(LANGUAGE)
                    .filter(m -> m.end() <= ws)
                    .filter(m -> Seps.betweenIsSeps(input, m.end(), ws))
                    .max(Comparator.comparingInt(Match::end))
                    .ifPresent(
                            _ -> ctx.matches.add(new Match(LANGUAGE_SUFFIX,
                                    word.value(),
                                    word.start(),
                                    word.end(),
                                    word.value(),
                                    1000,
                                    Set.of(),
                                    true)));
        }
    }

    private static boolean matchesAny(String word, List<String> affixes) {
        for (var a : affixes) if (word.equals(a.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean tryStripAffix(ParseContext ctx, Words.Word word, String lower,
                                         List<String> affixes, boolean prefix,
                                         List<String> allowed, LanguageRegistry reg, String name) {
        for (var a : affixes) {
            var al = a.toLowerCase(Locale.ROOT);
            if (al.length() >= lower.length()) continue;
            String rest;
            if (prefix) {
                if (!lower.startsWith(al)) continue;
                rest = lower.substring(al.length());
            } else {
                if (!lower.endsWith(al)) continue;
                rest = lower.substring(0, lower.length() - al.length());
            }
            var lang = reg.find(rest).orElse(null);
            if (lang != null && isAllowed(lang, allowed)) {
                // Tag attached-affix subtitle_language matches so OutputBuilder
                // skips promoting them to language under --includes language;
                // python's prefix→subtitle conversion is rule-disabled in that
                // case, but for attached "SubFR"-style tokens the match is
                // emitted as subtitle_language directly and stays excluded.
                Set<String> tags = SUBTITLE_LANGUAGE.equals(name)
                        ? Set.of("attached-affix") : Set.of();
                ctx.matches.add(new Match(name, lang, word.start(), word.end(),
                        word.value(), 1000, tags, false));
                return true;
            }
        }
        return false;
    }

    private static List<String> allowedLanguages(ParseContext ctx) {
        var explicit = ctx.options.allowedLanguages();
        if (!explicit.isEmpty()) return explicit;
        return ctx.config.topLevelList("allowed_languages");
    }

    private static boolean isAllowed(Language lang, List<String> allowed) {
        var lc = HashSet.newHashSet(allowed.size());
        for (var s : allowed) lc.add(s.toLowerCase(Locale.ROOT));
        if (lang.alpha2() != null && lc.contains(lang.alpha2().toLowerCase(Locale.ROOT))) return true;
        if (lang.alpha3() != null && lc.contains(lang.alpha3().toLowerCase(Locale.ROOT))) return true;
        return lang.name() != null && lc.contains(lang.name().toLowerCase(Locale.ROOT));
    }

    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) {
            var out = new ArrayList<String>(l.size());
            for (var v : l) if (v != null) out.add(v.toString());
            return out;
        }
        return List.of();
    }

    private static List<String> combine(List<String> a, List<String> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        var out = new ArrayList<String>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    @Override
    public void postProcess(ParseContext ctx) {
        dropAudioProfileOverlap(ctx);
        dropCommonWordLanguages(ctx);
        renameStandaloneAffixes(ctx);
        renameWithSubtitleExtension(ctx);
        // Re-run common-word filter to drop subtitle_language=und matches that
        // renameStandaloneAffixes synthesised over a common-word marker like
        // "st" or "sub" (mirrors python's RemoveInvalidLanguages priority).
        dropCommonWordLanguages(ctx);
        dropUndeterminedWhenRealLangPresent(ctx);
        // Keep MARKER_PREFIX private matches alive: downstream EpisodeTitle
        // hole-computation uses them to terminate holes (mirrors python which
        // never strips its subtitle_language.prefix marker). dropPrivateAffixes
        // is a no-op now but the call site is preserved for future cleanup.
        cleanupReleaseGroups(ctx);
    }

    /**
     * Drop language/subtitle_language matches whose span exactly overlaps an
     * audio_profile match (e.g. "HE" → audio_profile=High Efficiency wins,
     * not language=Hebrew; "ES" → audio_profile=Extended Surround wins, not
     * language=Spanish). Mirrors python's per-pattern conflict_solver.
     */
    private void dropAudioProfileOverlap(ParseContext ctx) {
        var profiles = ctx.matches.named("audio_profile")
                .map(m -> new int[]{m.start(), m.end()})
                .toList();
        if (profiles.isEmpty()) return;
        var toRemove = new ArrayList<Match>();
        for (var lang : ctx.matches.all()
                .filter(m -> LANGUAGE.equals(m.name()) || SUBTITLE_LANGUAGE.equals(m.name()))
                .toList()) {
            for (var sp : profiles) {
                if (lang.start() == sp[0] && lang.end() == sp[1]) {
                    toRemove.add(lang);
                    break;
                }
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void cleanupReleaseGroups(ParseContext ctx) {
        var groups = ctx.matches.named("release_group").toList();
        for (var g : groups) {
            var langs = ctx.matches.named(SUBTITLE_LANGUAGE)
                    .filter(m -> m.start() >= g.start() && m.end() <= g.end())
                    .toList();
            if (!langs.isEmpty()) {
                ctx.matches.remove(g);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void dropCommonWordLanguages(ParseContext ctx) {
        var ac = ctx.config.raw().get("advanced_config");
        if (!(ac instanceof java.util.Map<?, ?> m)) return;
        var raw = ((java.util.Map<String, Object>) m).get("common_words");
        if (!(raw instanceof List<?> l)) return;
        var lc = new HashSet<String>();
        for (var s : l) lc.add(s.toString().toLowerCase(Locale.ROOT));
        // Bracket markers ("[ENG+PT+DE]") that contain 2+ language matches form a
        // language list — exempt their members from common-word filtering so all
        // codes survive even when one happens to be a common word like "de".
        var langListMarkers = new ArrayList<int[]>();
        for (var marker : ctx.markers) {
            if (!"group".equals(marker.name())) continue;
            int s = marker.start();
            int e = marker.end();
            long count = ctx.matches.all()
                    .filter(mm -> LANGUAGE.equals(mm.name()) || SUBTITLE_LANGUAGE.equals(mm.name()))
                    .filter(mm -> mm.start() >= s && mm.end() <= e)
                    .count();
            if (count >= 2) langListMarkers.add(new int[]{s, e});
        }
        var toRemove = new ArrayList<Match>();
        for (var name : List.of(LANGUAGE, SUBTITLE_LANGUAGE)) {
            for (var match : ctx.matches.named(name).toList()) {
                if (!lc.contains(match.raw().toLowerCase(Locale.ROOT))) continue;
                boolean inList = langListMarkers.stream().anyMatch(
                        sp -> match.start() >= sp[0] && match.end() <= sp[1]);
                if (!inList) toRemove.add(match);
            }
        }
        for (var match : toRemove) ctx.matches.remove(match);
    }

    private void renameStandaloneAffixes(ParseContext ctx) {
        var markers = ctx.matches.all()
                .filter(m -> MARKER_PREFIX.equals(m.name()))
                .toList();
        if (markers.isEmpty()) return;

        var toDropMarker = new ArrayList<Match>();
        for (var marker : markers) {
            var renamed = renameAdjacentLanguagesAfter(ctx, marker);
            if (!renamed) renamed = renameAdjacentLanguagesBefore(ctx, marker);
            if (renamed) continue;
            // No adjacent language to rename. Emit subtitle_language=und only
            // when the marker is "standalone" — both sides between marker and
            // the surrounding match (or filepart edge) contain only separator
            // chars, and the marker is not inside a non-trivial bracket group
            // (which would imply a release group token like "[ShinBunBu-Subs]").
            if (!isStandaloneAffix(ctx, marker)) {
                // Marker abuts content (e.g. "St.Patricks.Day") — it served no
                // purpose, drop it so it doesn't break downstream title-hole
                // computation.
                toDropMarker.add(marker);
                continue;
            }
            var registry = LanguageRegistry.instance();
            var und = registry.find("und").orElse(null);
            if (und == null) continue;
            ctx.matches.add(new Match(SUBTITLE_LANGUAGE, und, marker.start(), marker.end(),
                    marker.raw(), 1000, Set.of(), false));
            // Marker has done its job: emitted und. Drop it so it doesn't
            // block downstream title-hole computation.
            toDropMarker.add(marker);
        }
        for (var m : toDropMarker) ctx.matches.remove(m);
    }

    private static boolean renameAdjacentLanguagesAfter(ParseContext ctx, Match marker) {
        var input = ctx.input;
        var langs = ctx.matches.named(LANGUAGE).toList();

        // Try to handle group-aware promotion first
        if (tryRenameLanguagesInAdjacentGroup(ctx, marker, langs, input)) {
            return true;
        }

        // Find the next language after the marker
        var next = findNextLanguageAfterMarker(langs, marker, input);
        if (next == null) {
            return false;
        }

        renameToSubtitle(ctx, next);

        // Handle consecutive languages within enclosing group
        renameConsecutiveLanguagesInEnclosingGroup(ctx, marker, next, langs, input);

        return true;
    }

    private static boolean tryRenameLanguagesInAdjacentGroup(ParseContext ctx, Match marker,
                                                             List<Match> langs, String input) {
        Marker nextGroup = findNextGroupMarker(ctx, marker);
        if (nextGroup == null) {
            return false;
        }

        if (!Seps.betweenIsSeps(input, marker.end(), nextGroup.start())) {
            return false;
        }

        var inGroup = findLanguagesInGroup(langs, nextGroup);
        if (inGroup.isEmpty()) {
            return false;
        }

        for (var l : inGroup) {
            renameToSubtitle(ctx, l);
        }
        return true;
    }

    private static Marker findNextGroupMarker(ParseContext ctx, Match marker) {
        Marker nextGroup = null;
        for (var g : ctx.markers) {
            if (!"group".equals(g.name())) continue;
            if (g.start() < marker.end()) continue;
            if (nextGroup == null || g.start() < nextGroup.start()) {
                nextGroup = g;
            }
        }
        return nextGroup;
    }

    private static List<Match> findLanguagesInGroup(List<Match> langs, Marker group) {
        return langs.stream()
                .filter(l -> l.start() >= group.start() && l.end() <= group.end())
                .toList();
    }

    private static Match findNextLanguageAfterMarker(List<Match> langs, Match marker, String input) {
        var next = langs.stream()
                .filter(l -> l.start() >= marker.end())
                .min(Comparator.comparingInt(Match::start))
                .orElse(null);

        if (next == null) {
            return null;
        }

        if (!Seps.betweenIsSeps(input, marker.end(), next.start())) {
            return null;
        }

        return next;
    }

    private static void renameConsecutiveLanguagesInEnclosingGroup(ParseContext ctx, Match marker,
                                                                   Match firstLanguage,
                                                                   List<Match> langs, String input) {
        Marker enclosing = findSmallestEnclosingGroup(ctx, marker);
        if (enclosing == null) {
            return;
        }

        var sortedAfter = langs.stream()
                .filter(l -> l.start() > firstLanguage.start() && l.end() <= enclosing.end())
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        int prevEnd = firstLanguage.end();
        for (var l : sortedAfter) {
            if (!Seps.betweenIsSeps(input, prevEnd, l.start())) {
                break;
            }
            renameToSubtitle(ctx, l);
            prevEnd = l.end();
        }
    }

    private static Marker findSmallestEnclosingGroup(ParseContext ctx, Match marker) {
        Marker smallest = null;
        for (var g : ctx.markers) {
            if (!"group".equals(g.name())) continue;
            if (g.start() > marker.start() || g.end() < marker.end()) continue;

            if (smallest == null || (g.end() - g.start() < smallest.end() - smallest.start())) {
                smallest = g;
            }
        }
        return smallest;
    }

    private static boolean isStandaloneAffix(ParseContext ctx, Match marker) {
        if (!isMarkerValidInGroups(ctx, marker)) {
            return false;
        }

        var bounds = findFilepartBounds(ctx, marker);
        var adjacentMatches = findAdjacentMatches(ctx, marker, bounds);

        String beforeGap = ctx.input.substring(adjacentMatches.prevEnd, marker.start());
        String afterGap = ctx.input.substring(marker.end(), adjacentMatches.nextStart);

        boolean beforeOk = isAllSeparators(beforeGap);
        boolean afterOk = isAllSeparators(afterGap) ||
                isTrailingReleaseGroup(afterGap, adjacentMatches.nextStart, bounds.rightBound);

        return beforeOk && afterOk;
    }

    private static boolean isMarkerValidInGroups(ParseContext ctx, Match marker) {
        for (var g : ctx.markers) {
            if (!"group".equals(g.name())) continue;
            if (marker.start() < g.start() || marker.end() > g.end()) continue;

            if (!isMarkerStandaloneInGroup(ctx.input, marker, g)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMarkerStandaloneInGroup(String input, Match marker, Marker group) {
        int innerStart = Math.min(group.start() + 1, marker.start());
        int innerEnd = Math.max(group.end() - 1, marker.end());

        // Marker fills the entire group
        if (innerStart >= marker.start() && innerEnd <= marker.end()) {
            return true;
        }

        String before = innerStart < marker.start()
                ? input.substring(innerStart, marker.start()) : "";
        String after = innerEnd > marker.end()
                ? input.substring(marker.end(), innerEnd) : "";

        return isAllSeparators(before) && isAllSeparators(after);
    }

    private record FilepartBounds(int leftBound, int rightBound) {
    }

    private static FilepartBounds findFilepartBounds(ParseContext ctx, Match marker) {
        int leftBound = 0;
        int rightBound = ctx.input.length();

        for (var fp : ctx.markers) {
            if (!"path".equals(fp.name())) continue;
            if (marker.start() >= fp.start() && marker.end() <= fp.end()) {
                return new FilepartBounds(fp.start(), fp.end());
            }
        }
        return new FilepartBounds(leftBound, rightBound);
    }

    private record AdjacentMatches(int prevEnd, int nextStart) {
    }

    private static AdjacentMatches findAdjacentMatches(ParseContext ctx, Match marker,
                                                       FilepartBounds bounds) {
        int prevEnd = bounds.leftBound;
        int nextStart = bounds.rightBound;

        for (var m : ctx.matches.all().toList()) {
            if (m == marker) continue;
            if (m.isPrivate() && !LANGUAGE.equals(m.name())) continue;

            if (m.end() <= marker.start() && m.end() > prevEnd) {
                prevEnd = m.end();
            }
            if (m.start() >= marker.end() && m.start() < nextStart) {
                nextStart = m.start();
            }
        }

        return new AdjacentMatches(prevEnd, nextStart);
    }

    private static boolean isAllSeparators(String text) {
        return text.chars().allMatch(c -> Seps.isSep((char) c));
    }

    private static boolean isTrailingReleaseGroup(String afterGap, int nextStart, int rightBound) {
        if (nextStart != rightBound) {
            return false;
        }

        String trimmed = afterGap.replaceAll("^[\\s._\\[\\](){}+*|=~#/\\\\,;:]+", "");
        if (!trimmed.startsWith("-")) {
            return false;
        }

        String rest = trimmed.substring(1)
                .replaceAll("^[\\s._\\[\\](){}+*|=~#/\\\\,;:]+", "");

        int wordEnd = findAlphanumericEnd(rest);
        if (wordEnd == 0) {
            return false;
        }

        String tail = rest.substring(wordEnd);
        return isAllSeparators(tail);
    }

    private static int findAlphanumericEnd(String text) {
        int wordEnd = 0;
        while (wordEnd < text.length() && Character.isLetterOrDigit(text.charAt(wordEnd))) {
            wordEnd++;
        }
        return wordEnd;
    }

    private static boolean renameAdjacentLanguagesBefore(ParseContext ctx, Match marker) {
        var input = ctx.input;
        var prev = ctx.matches.named(LANGUAGE)
                .filter(l -> l.end() <= marker.start())
                .max(Comparator.comparingInt(Match::end))
                .orElse(null);
        if (prev == null) return false;
        if (!Seps.betweenIsSeps(input, prev.end(), marker.start())) return false;
        renameToSubtitle(ctx, prev);
        return true;
    }

    private static void renameToSubtitle(ParseContext ctx, Match lang) {
        ctx.matches.replace(lang, new Match(SUBTITLE_LANGUAGE, lang.value(),
                lang.start(), lang.end(), lang.raw(), lang.priority() + 1, lang.tags(), false));
    }

    private void renameWithSubtitleExtension(ParseContext ctx) {
        var subtitleExt = ctx.matches.named("container")
                .filter(m -> m.tags().contains("subtitle") && m.tags().contains("extension"))
                .findFirst()
                .orElse(null);
        if (subtitleExt == null) return;
        var lang = ctx.matches.named(LANGUAGE)
                .filter(l -> l.end() <= subtitleExt.start())
                .max(Comparator.comparingInt(Match::end))
                .orElse(null);
        if (lang == null) return;
        renameToSubtitle(ctx, lang);
    }

    private void dropUndeterminedWhenRealLangPresent(ParseContext ctx) {
        for (var prop : List.of(LANGUAGE, SUBTITLE_LANGUAGE)) {
            var matches = ctx.matches.named(prop).toList();
            boolean hasReal = matches.stream().anyMatch(m -> m.value() instanceof Language l
                    && !UND_NAME.equals(l.name()) && !MUL_NAME.equals(l.name()));
            if (hasReal) {
                for (var m : matches) {
                    if (m.value() instanceof Language l && UND_NAME.equals(l.name())) {
                        ctx.matches.remove(m);
                    }
                }
            }
        }
    }

}

