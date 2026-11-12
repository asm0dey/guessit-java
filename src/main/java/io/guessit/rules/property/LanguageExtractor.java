package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.lang.LanguageRegistry;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mirkoddd.sift.core.Sift.exactly;
import static io.guessit.engine.MatchName.*;

/**
 * Extracts {@code language} and {@code subtitle_language}.
 */
public final class LanguageExtractor implements Extractor {

    private static final String GROUP_MARKER = "group";
    private static final String UND_NAME = "Undetermined";
    private static final String MUL_NAME = "Multiple languages";
    private static final MatchName MARKER_PREFIX = MatchName.SUBTITLE_LANGUAGE_PREFIX;

    @Override
    public String name() {
        return LANGUAGE.name().toLowerCase();
    }

    @Override
    public String description() {
        return "language tags (ENG, FRENCH, MULTI, …)";
    }

    private record AffixConfiguration(
            List<String> subtitlePrefixes,
            List<String> subtitleSuffixes,
            List<String> languageAffixes,
            List<String> languagePrefixes,
            List<String> languageSuffixes) {
    }

    private record ExtractionEnv(
            ParseContext ctx,
            List<Words.Word> words,
            Set<Integer> pairConsumed,
            Set<Integer> countryWordConsumed,
            Set<String> allowedLc,
            LanguageRegistry registry,
            AffixConfiguration affixes
    ) {}

    @Override
    public void extract(ParseContext ctx) {
        var allowedList = allowedLanguages(ctx);
        if (allowedList.isEmpty()) return;

        var allowedLc = allowedList.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        var env = new ExtractionEnv(
                ctx,
                Words.iter(ctx.input),
                new HashSet<>(),
                new HashSet<>(),
                allowedLc,
                LanguageRegistry.instance(),
                loadAffixConfiguration(ctx)
        );

        extractTwoWordLanguages(env);
        extractTwoWordSubtitleAffixes(env);
        extractSingleWordLanguages(env);
        extractLanguageSuffixes(env);
    }

    private AffixConfiguration loadAffixConfiguration(ParseContext ctx) {
        var section = ctx.config.section(LANGUAGE.name().toLowerCase());
        var subtitleAffixes = stringList(section.get("subtitle_affixes"));
        var subtitlePrefixes = combine(subtitleAffixes, stringList(section.get("subtitle_prefixes")));
        var subtitleSuffixes = combine(subtitleAffixes, stringList(section.get("subtitle_suffixes")));
        var languageAffixes = stringList(section.get("language_affixes"));
        var languagePrefixes = combine(languageAffixes, stringList(section.get("language_prefixes")));
        var languageSuffixes = combine(languageAffixes, stringList(section.get("language_suffixes")));

        return new AffixConfiguration(subtitlePrefixes, subtitleSuffixes, languageAffixes,
                languagePrefixes, languageSuffixes);
    }

    private void extractTwoWordLanguages(ExtractionEnv env) {
        var input = env.ctx().input;
        var words = env.words();

        for (int i = 0; i < words.size() - 1; i++) {
            if (env.pairConsumed().contains(i)) {
                continue;
            }

            var w1 = words.get(i);
            var w2 = words.get(i + 1);
            var combined = (w1.value() + " " + w2.value()).toLowerCase(Locale.ROOT);
            var pairLang = env.registry().find(combined).orElse(null);

            if (pairLang != null && isAllowed(pairLang, env.allowedLc())) {
                env.ctx().matches.add(new Match(MatchName.LANGUAGE, pairLang, w1.start(), w2.end(),
                        input.substring(w1.start(), w2.end()), 1000, Set.of(), false));

                env.pairConsumed().add(i);
                env.pairConsumed().add(i + 1);
            }
        }
    }

    private void extractTwoWordSubtitleAffixes(ExtractionEnv env) {
        var input = env.ctx().input;
        var words = env.words();

        for (int i = 0; i < words.size() - 1; i++) {
            if (env.pairConsumed().contains(i)) {
                continue;
            }

            var w1 = words.get(i);
            var w2 = words.get(i + 1);
            String gap = input.substring(w1.end(), w2.start());

            if (isAllSeparators(gap)) {
                var combined = (w1.value() + " " + w2.value()).toLowerCase(Locale.ROOT);

                if (matchesAny(combined, env.affixes().subtitlePrefixes()) || matchesAny(combined, env.affixes().subtitleSuffixes())) {
                    String substring = input.substring(w1.start(), w2.end());
                    env.ctx().matches.add(new Match(MARKER_PREFIX, substring,
                            w1.start(), w2.end(), substring,
                            1000, Set.of(), true));

                    env.pairConsumed().add(i);
                    env.pairConsumed().add(i + 1);
                }
            }
        }
    }

    private void extractSingleWordLanguages(ExtractionEnv env) {
        var words = env.words();
        for (int wi = 0; wi < words.size(); wi++) {
            if (env.pairConsumed().contains(wi) || env.countryWordConsumed().contains(wi)) continue;
            processSingleWord(env, wi);
        }
    }

    private void processSingleWord(ExtractionEnv env, int wi) {
        var word = env.words().get(wi);
        var lower = word.value().toLowerCase(Locale.ROOT);

        if (lower.chars().allMatch(Character::isDigit)) return;

        if (tryProcessSubtitleAffix(env, word, lower)) return;
        if (tryProcessLanguageAffix(env, word, lower)) return;
        if (tryProcessDirectLanguage(env, wi, word, lower)) return;

        tryProcessAffixStripping(env, word, lower);
    }

    private boolean tryProcessSubtitleAffix(ExtractionEnv env, Words.Word word, String lower) {
        if (matchesAny(lower, env.affixes().subtitlePrefixes()) || matchesAny(lower, env.affixes().subtitleSuffixes())) {
            env.ctx().matches.add(new Match(MARKER_PREFIX, word.value(),
                    word.start(), word.end(), word.value(), 1000, Set.of(), true));
            return true;
        }
        return false;
    }

    private boolean tryProcessLanguageAffix(ExtractionEnv env, Words.Word word, String lower) {
        if (matchesAny(lower, env.affixes().languageAffixes())) {
            var und = env.registry().find("und").orElse(null);
            if (und != null && isAllowed(und, env.allowedLc())) {
                env.ctx().matches.add(new Match(MatchName.LANGUAGE, und, word.start(), word.end(),
                        word.value(), 1000, Set.of(), false));
                return true;
            }
        }
        return false;
    }

    private boolean tryProcessDirectLanguage(ExtractionEnv env, int wi, Words.Word word, String lower) {
        var lang = env.registry().find(lower).orElse(null);
        if (lang == null || !isAllowed(lang, env.allowedLc())) {
            return false;
        }

        var countryMatchOpt = tryResolveCountryCode(env.ctx().input, word, env.registry());

        if (countryMatchOpt.isPresent()) {
            var cc = countryMatchOpt.get();
            Language langWithCountry = new Language(lang.alpha2(), lang.alpha3(), lang.name(), cc.country());

            env.ctx().matches.add(new Match(MatchName.LANGUAGE, langWithCountry, word.start(), cc.end(),
                    env.ctx().input.substring(word.start(), cc.end()), 1000, Set.of(), false));

            markCountryWordAsConsumedIfPresent(env, wi, cc.end());
        } else {
            env.ctx().matches.add(new Match(MatchName.LANGUAGE, lang, word.start(), word.end(),
                    env.ctx().input.substring(word.start(), word.end()), 1000, Set.of(), false));
        }

        return true;
    }

    private record ResolvedCountry(Country country, int end) {}

    private Optional<ResolvedCountry> tryResolveCountryCode(String input, Words.Word word, LanguageRegistry registry) {
        int expectedEnd = word.end() + 3;

        if (expectedEnd > input.length() || input.charAt(word.end()) != '-') {
            return Optional.empty();
        }

        String countryToken = input.substring(word.end() + 1, expectedEnd);
        String exactly2Letters = exactly(2).letters().shake();
        if (!countryToken.matches(exactly2Letters)) {
            return Optional.empty();
        }

        boolean hasValidSeparator = expectedEnd == input.length() || Seps.isSep(input.charAt(expectedEnd));
        if (!hasValidSeparator) {
            return Optional.empty();
        }

        return registry.findCountry(countryToken.toUpperCase(Locale.ROOT))
                .map(country -> new ResolvedCountry(country, expectedEnd));
    }

    private void markCountryWordAsConsumedIfPresent(ExtractionEnv env, int currentIndex, int matchEnd) {
        int nextIndex = currentIndex + 1;
        if (nextIndex < env.words().size() && env.words().get(nextIndex).end() == matchEnd) {
            env.countryWordConsumed().add(nextIndex);
        }
    }

    private void tryProcessAffixStripping(ExtractionEnv env, Words.Word word, String lower) {
        if (tryStripAffix(env, word, lower, env.affixes().subtitlePrefixes(), true, MatchName.SUBTITLE_LANGUAGE)) return;
        if (tryStripAffix(env, word, lower, env.affixes().languagePrefixes(), true, MatchName.LANGUAGE)) return;
        if (tryStripAffix(env, word, lower, env.affixes().subtitleSuffixes(), false, MatchName.SUBTITLE_LANGUAGE)) return;

        tryStripAffix(env, word, lower, env.affixes().languageSuffixes(), false, MatchName.LANGUAGE);
    }

    private void extractLanguageSuffixes(ExtractionEnv env) {
        var input = env.ctx().input;
        for (Words.Word word : env.words()) {
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (!matchesAny(lower, env.affixes().languageSuffixes())) continue;

            int ws = word.start();
            env.ctx().matches.named(MatchName.LANGUAGE)
                    .filter(m -> m.end() <= ws && Seps.betweenIsSeps(input, m.end(), ws))
                    .max(Comparator.comparingInt(Match::end))
                    .ifPresent(_ -> env.ctx().matches.add(new Match(MatchName.LANGUAGE_SUFFIX,
                            word.value(), word.start(), word.end(), word.value(), 1000, Set.of(), true)));
        }
    }

    private static boolean matchesAny(String word, List<String> affixes) {
        return affixes.stream().anyMatch(a -> word.equals(a.toLowerCase(Locale.ROOT)));
    }

    private static boolean tryStripAffix(ExtractionEnv env, Words.Word word, String lower,
                                         List<String> affixesList, boolean prefix, MatchName name) {

        var foundLang = affixesList.stream()
                .map(a -> a.toLowerCase(Locale.ROOT))
                .filter(al -> al.length() < lower.length())
                .map(al -> stripAffix(lower, al, prefix))
                .filter(Objects::nonNull)
                .map(rest -> env.registry().find(rest).orElse(null))
                .filter(lang -> lang != null && isAllowed(lang, env.allowedLc()))
                .findFirst();

        foundLang.ifPresent(lang -> {
            Set<String> tags = MatchName.SUBTITLE_LANGUAGE.equals(name) ? Set.of("attached-affix") : Set.of();
            env.ctx().matches.add(new Match(name, lang, word.start(), word.end(), word.value(), 1000, tags, false));
        });

        return foundLang.isPresent();
    }

    private static String stripAffix(String lower, String affix, boolean prefix) {
        if (prefix) {
            return lower.startsWith(affix) ? lower.substring(affix.length()) : null;
        }
        return lower.endsWith(affix) ? lower.substring(0, lower.length() - affix.length()) : null;
    }

    private static List<String> allowedLanguages(ParseContext ctx) {
        var explicit = ctx.options.allowedLanguages();
        return !explicit.isEmpty() ? explicit : ctx.config.topLevelList("allowed_languages");
    }

    private static boolean isAllowed(Language lang, Set<String> allowedLc) {
        if (lang.alpha2() != null && allowedLc.contains(lang.alpha2().toLowerCase(Locale.ROOT))) return true;
        if (lang.alpha3() != null && allowedLc.contains(lang.alpha3().toLowerCase(Locale.ROOT))) return true;
        return lang.name() != null && allowedLc.contains(lang.name().toLowerCase(Locale.ROOT));
    }

    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }

    private static List<String> combine(List<String> a, List<String> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }

    @Override
    public void postProcess(ParseContext ctx) {
        dropAudioProfileOverlap(ctx);
        dropCommonWordLanguages(ctx);
        renameStandaloneAffixes(ctx);
        renameWithSubtitleExtension(ctx);
        dropCommonWordLanguages(ctx);
        dropUndeterminedWhenRealLangPresent(ctx);
    }

    private void dropAudioProfileOverlap(ParseContext ctx) {
        var profiles = ctx.matches.named(MatchName.AUDIO_PROFILE).toList();
        if (profiles.isEmpty()) return;

        var toRemove = ctx.matches.all()
                .filter(m -> MatchName.LANGUAGE.equals(m.name()) || MatchName.SUBTITLE_LANGUAGE.equals(m.name()))
                .filter(lang -> profiles.stream().anyMatch(sp -> lang.start() == sp.start() && lang.end() == sp.end()))
                .toList();

        toRemove.forEach(ctx.matches::remove);
    }

    private void dropCommonWordLanguages(ParseContext ctx) {
        var lc = loadCommonWords(ctx);
        if (lc.isEmpty()) return;

        var langListMarkers = findLangListMarkers(ctx);

        var toRemove = ctx.matches.all()
                .filter(m -> MatchName.LANGUAGE.equals(m.name()) || MatchName.SUBTITLE_LANGUAGE.equals(m.name()))
                .filter(m -> lc.contains(m.raw().toLowerCase(Locale.ROOT)))
                .filter(m -> langListMarkers.stream().noneMatch(sp -> m.start() >= sp[0] && m.end() <= sp[1]))
                .toList();

        toRemove.forEach(ctx.matches::remove);
    }

    private static Set<String> loadCommonWords(ParseContext ctx) {
        var ac = ctx.config.raw().get("advanced_config");
        if (!(ac instanceof java.util.Map<?, ?> m)) return Collections.emptySet();

        var raw = m.get("common_words");
        if (!(raw instanceof List<?> l)) return Collections.emptySet();

        return l.stream()
                .map(s -> s.toString().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static List<int[]> findLangListMarkers(ParseContext ctx) {
        return ctx.markers.stream()
                .filter(g -> GROUP_MARKER.equals(g.name()))
                .filter(g -> hasMultipleLanguages(ctx, g.start(), g.end()))
                .map(g -> new int[]{g.start(), g.end()})
                .toList();
    }

    private static boolean hasMultipleLanguages(ParseContext ctx, int start, int end) {
        return ctx.matches.all()
                .filter(mm -> MatchName.LANGUAGE.equals(mm.name()) || MatchName.SUBTITLE_LANGUAGE.equals(mm.name()))
                .filter(mm -> mm.start() >= start && mm.end() <= end)
                .count() >= 2;
    }

    private void renameStandaloneAffixes(ParseContext ctx) {
        var markers = ctx.matches.named(MARKER_PREFIX).toList();
        if (markers.isEmpty()) return;

        var toDropMarker = new ArrayList<Match>();
        var registry = LanguageRegistry.instance();
        var und = registry.find("und").orElse(null);

        for (var marker : markers) {
            boolean renamed = renameAdjacentLanguagesAfter(ctx, marker) || renameAdjacentLanguagesBefore(ctx, marker);
            if (renamed) continue;

            if (isStandaloneAffix(ctx, marker) && und != null) {
                ctx.matches.add(new Match(MatchName.SUBTITLE_LANGUAGE, und, marker.start(), marker.end(),
                        marker.raw(), 1000, Set.of(), false));
            }
            toDropMarker.add(marker);
        }

        toDropMarker.forEach(ctx.matches::remove);
    }

    private static boolean renameAdjacentLanguagesAfter(ParseContext ctx, Match marker) {
        var input = ctx.input;
        var languages = ctx.matches.named(MatchName.LANGUAGE).toList();

        if (tryRenameLanguagesInAdjacentGroup(ctx, marker, languages, input)) {
            return true;
        }

        var next = findNextLanguageAfterMarker(languages, marker, input);
        if (next == null) return false;

        renameToSubtitle(ctx, next);
        renameConsecutiveLanguagesInEnclosingGroup(ctx, marker, next, languages, input);

        return true;
    }

    private static boolean tryRenameLanguagesInAdjacentGroup(ParseContext ctx, Match marker,
                                                             List<Match> languages, String input) {
        var nextGroup = findNextGroupMarker(ctx, marker);
        if (nextGroup == null || !Seps.betweenIsSeps(input, marker.end(), nextGroup.start())) {
            return false;
        }

        var inGroup = findLanguagesInGroup(languages, nextGroup);
        if (inGroup.isEmpty()) return false;

        inGroup.forEach(l -> renameToSubtitle(ctx, l));
        return true;
    }

    private static Marker findNextGroupMarker(ParseContext ctx, Match marker) {
        return ctx.markers.stream()
                .filter(g -> GROUP_MARKER.equals(g.name()) && g.start() >= marker.end())
                .min(Comparator.comparingInt(Marker::start))
                .orElse(null);
    }

    private static List<Match> findLanguagesInGroup(List<Match> languages, Marker group) {
        return languages.stream().filter(l -> l.start() >= group.start() && l.end() <= group.end()).toList();
    }

    private static Match findNextLanguageAfterMarker(List<Match> languages, Match marker, String input) {
        return languages.stream()
                .filter(l -> l.start() >= marker.end())
                .min(Comparator.comparingInt(Match::start))
                .filter(l -> Seps.betweenIsSeps(input, marker.end(), l.start()))
                .orElse(null);
    }

    private static void renameConsecutiveLanguagesInEnclosingGroup(ParseContext ctx, Match marker,
                                                                   Match firstLanguage,
                                                                   List<Match> languages, String input) {
        var enclosing = findSmallestEnclosingGroup(ctx, marker);
        if (enclosing == null) return;

        var sortedAfter = languages.stream()
                .filter(l -> l.start() > firstLanguage.start() && l.end() <= enclosing.end())
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        int prevEnd = firstLanguage.end();
        for (var l : sortedAfter) {
            if (!Seps.betweenIsSeps(input, prevEnd, l.start())) break;
            renameToSubtitle(ctx, l);
            prevEnd = l.end();
        }
    }

    private static Marker findSmallestEnclosingGroup(ParseContext ctx, Match marker) {
        return ctx.markers.stream()
                .filter(g -> GROUP_MARKER.equals(g.name()) && g.start() <= marker.start() && g.end() >= marker.end())
                .min(Comparator.comparingInt(g -> g.end() - g.start()))
                .orElse(null);
    }

    private static boolean isStandaloneAffix(ParseContext ctx, Match marker) {
        if (!isMarkerValidInGroups(ctx, marker)) return false;

        var bounds = findFilepartBounds(ctx, marker);
        var adjacentMatches = findAdjacentMatches(ctx, marker, bounds);

        String beforeGap = ctx.input.substring(adjacentMatches.prevEnd, marker.start());
        String afterGap = ctx.input.substring(marker.end(), adjacentMatches.nextStart);

        return isAllSeparators(beforeGap) &&
                (isAllSeparators(afterGap) || isTrailingReleaseGroup(afterGap, adjacentMatches.nextStart, bounds.rightBound));
    }

    private static boolean isMarkerValidInGroups(ParseContext ctx, Match marker) {
        return ctx.markers.stream()
                .filter(g -> GROUP_MARKER.equals(g.name()))
                .filter(g -> marker.start() >= g.start() && marker.end() <= g.end())
                .allMatch(g -> isMarkerStandaloneInGroup(ctx.input, marker, g));
    }

    private static boolean isMarkerStandaloneInGroup(String input, Match marker, Marker group) {
        int innerStart = Math.min(group.start() + 1, marker.start());
        int innerEnd = Math.max(group.end() - 1, marker.end());

        if (innerStart >= marker.start() && innerEnd <= marker.end()) return true;

        String before = innerStart < marker.start() ? input.substring(innerStart, marker.start()) : "";
        String after = innerEnd > marker.end() ? input.substring(marker.end(), innerEnd) : "";

        return isAllSeparators(before) && isAllSeparators(after);
    }

    private record FilePartBounds(int leftBound, int rightBound) {}

    private static FilePartBounds findFilepartBounds(ParseContext ctx, Match marker) {
        return ctx.markers.stream()
                .filter(fp -> "path".equals(fp.name()) && marker.start() >= fp.start() && marker.end() <= fp.end())
                .map(fp -> new FilePartBounds(fp.start(), fp.end()))
                .findFirst()
                .orElseGet(() -> new FilePartBounds(0, ctx.input.length()));
    }

    private record AdjacentMatches(int prevEnd, int nextStart) {}

    private static AdjacentMatches findAdjacentMatches(ParseContext ctx, Match marker, FilePartBounds bounds) {
        int prevEnd = ctx.matches.all()
                .filter(m -> m != marker && (!m.isPrivate() || MatchName.LANGUAGE.equals(m.name())))
                .filter(m -> m.end() <= marker.start() && m.end() > bounds.leftBound)
                .mapToInt(Match::end)
                .max().orElse(bounds.leftBound);

        int nextStart = ctx.matches.all()
                .filter(m -> m != marker && (!m.isPrivate() || MatchName.LANGUAGE.equals(m.name())))
                .filter(m -> m.start() >= marker.end() && m.start() < bounds.rightBound)
                .mapToInt(Match::start)
                .min().orElse(bounds.rightBound);

        return new AdjacentMatches(prevEnd, nextStart);
    }

    private static boolean isAllSeparators(String text) {
        return text.chars().allMatch(c -> Seps.isSep((char) c));
    }

    private static boolean isTrailingReleaseGroup(String afterGap, int nextStart, int rightBound) {
        if (nextStart != rightBound) return false;

        String trimmed = afterGap.replaceAll("^[\\s._\\[\\](){}+*|=~#/\\\\,;:]+", "");
        if (!trimmed.startsWith("-")) return false;

        String rest = trimmed.substring(1).replaceAll("^[\\s._\\[\\](){}+*|=~#/\\\\,;:]+", "");
        int wordEnd = findAlphanumericEnd(rest);

        return wordEnd > 0 && isAllSeparators(rest.substring(wordEnd));
    }

    private static int findAlphanumericEnd(String text) {
        int wordEnd = 0;
        while (wordEnd < text.length() && Character.isLetterOrDigit(text.charAt(wordEnd))) {
            wordEnd++;
        }
        return wordEnd;
    }

    private static boolean renameAdjacentLanguagesBefore(ParseContext ctx, Match marker) {
        var prev = ctx.matches.named(MatchName.LANGUAGE)
                .filter(l -> l.end() <= marker.start())
                .max(Comparator.comparingInt(Match::end))
                .filter(l -> Seps.betweenIsSeps(ctx.input, l.end(), marker.start()))
                .orElse(null);

        if (prev == null) return false;

        renameToSubtitle(ctx, prev);
        return true;
    }

    private static void renameToSubtitle(ParseContext ctx, Match lang) {
        ctx.matches.replace(lang, new Match(MatchName.SUBTITLE_LANGUAGE, lang.value(),
                lang.start(), lang.end(), lang.raw(), lang.priority() + 1, lang.tags(), false));
    }

    private void renameWithSubtitleExtension(ParseContext ctx) {
        ctx.matches.named(MatchName.CONTAINER)
                .filter(m -> m.tags().contains("subtitle") && m.tags().contains("extension"))
                .findFirst().flatMap(subtitleExt -> ctx.matches.named(MatchName.LANGUAGE)
                        .filter(l -> l.end() <= subtitleExt.start())
                        .max(Comparator.comparingInt(Match::end))).ifPresent(lang -> renameToSubtitle(ctx, lang));
    }

    private void dropUndeterminedWhenRealLangPresent(ParseContext ctx) {
        Stream.of(LANGUAGE, SUBTITLE_LANGUAGE).forEach(prop -> {
            var matches = ctx.matches.named(prop).toList();
            boolean hasReal = matches.stream().anyMatch(m -> m.value() instanceof Language l
                    && !UND_NAME.equals(l.name()) && !MUL_NAME.equals(l.name()));

            if (hasReal) {
                matches.stream()
                        .filter(m -> m.value() instanceof Language l && UND_NAME.equals(l.name()))
                        .forEach(ctx.matches::remove);
            }
        });
    }
}