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
        var input = ctx.input;

        var section = ctx.config.section(LANGUAGE);
        var subtitleAffixes = stringList(section.get("subtitle_affixes"));
        var subtitlePrefixes = combine(subtitleAffixes, stringList(section.get("subtitle_prefixes")));
        var subtitleSuffixes = combine(subtitleAffixes, stringList(section.get("subtitle_suffixes")));
        var languageAffixes = stringList(section.get("language_affixes"));
        var languagePrefixes = combine(languageAffixes, stringList(section.get("language_prefixes")));
        var languageSuffixes = combine(languageAffixes, stringList(section.get("language_suffixes")));

        var words = Words.iter(input);

        // Two-word language alias pass: registry knows combos like
        // "espanol castellano" → ca. Try every consecutive pair before
        // single-word lookup so the pair wins (otherwise espanol→es and
        // castellano→ca emit as two separate matches).
        var pairConsumed = new java.util.HashSet<Integer>();
        var countryWordConsumed = new java.util.HashSet<Integer>();
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

        for (int wi = 0; wi < words.size(); wi++) {
            if (pairConsumed.contains(wi)) continue;
            if (countryWordConsumed.contains(wi)) continue;
            Words.Word word = words.get(wi);
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (lower.chars().allMatch(Character::isDigit)) continue;

            // 1. Standalone-word affix → emit a private prefix marker; rename happens in postprocess.
            if (matchesAny(lower, subtitlePrefixes) || matchesAny(lower, subtitleSuffixes)) {
                ctx.matches.add(new Match(MARKER_PREFIX, word.value(),
                        word.start(), word.end(), word.value(), 1000, Set.of(), true));
                continue;
            }

            // 1a. Standalone language-affix (e.g. "dublado", "dubbed") with no language
            // attached → emit language=Undetermined. Mirrors python's behavior.
            if (matchesAny(lower, languageAffixes)) {
                var und = registry.find("und").orElse(null);
                if (und != null && isAllowed(und, allowed)) {
                    ctx.matches.add(new Match(LANGUAGE, und, word.start(), word.end(),
                            word.value(), 1000, Set.of(), false));
                    continue;
                }
            }

            // 2. Direct match on the whole word.
            var lang = registry.find(lower).orElse(null);
            if (lang != null && isAllowed(lang, allowed)) {
                // 2a. Check for a trailing country code via hyphen: '-' + exactly 2 alpha chars at word
                // boundary. Only '-' is accepted as separator — dots/underscores/spaces can separate
                // unrelated tokens (e.g. "en.va" in "Elle.s.en.va"). Mirrors Python guessit's
                // LanguageCountry rule which uses '-' as the explicit locale connector.
                Language langWithCountry = lang;
                int matchEnd = word.end();
                if (word.end() + 3 <= input.length() && input.charAt(word.end()) == '-') {
                    String countryToken = input.substring(word.end() + 1, word.end() + 3);
                    if (countryToken.matches("[A-Za-z]{2}")
                            && (word.end() + 3 == input.length() || Seps.isSep(input.charAt(word.end() + 3)))) {
                        var resolved = registry.findCountry(countryToken.toUpperCase(Locale.ROOT)).orElse(null);
                        if (resolved != null) {
                            langWithCountry = new Language(lang.alpha2(), lang.alpha3(), lang.name(), resolved);
                            matchEnd = word.end() + 3;
                            // Mark the word covering the country token as consumed so it isn't
                            // also matched as a separate language (e.g. "br" → Portuguese alias).
                            if (wi + 1 < words.size()) {
                                var nextWord = words.get(wi + 1);
                                if (nextWord.start() == word.end() + 1 && nextWord.end() == word.end() + 3) {
                                    countryWordConsumed.add(wi + 1);
                                }
                            }
                        }
                    }
                }
                ctx.matches.add(new Match(LANGUAGE, langWithCountry, word.start(), matchEnd,
                        input.substring(word.start(), matchEnd), 1000, Set.of(), false));
                continue;
            }

            // 3. In-word prefix / suffix strip → emit at the whole-word span (raw includes affix).
            if (tryStripAffix(ctx, word, lower, subtitlePrefixes, true, allowed, registry, SUBTITLE_LANGUAGE))
                continue;
            if (tryStripAffix(ctx, word, lower, languagePrefixes, true, allowed, registry, LANGUAGE)) continue;
            if (tryStripAffix(ctx, word, lower, subtitleSuffixes, false, allowed, registry, SUBTITLE_LANGUAGE))
                continue;
            tryStripAffix(ctx, word, lower, languageSuffixes, false, allowed, registry, LANGUAGE);
        }

        // Standalone language-suffix word (e.g. "AUDIO" in "SPANISH.AUDIO-NEWPCT"):
        // emit a private language.suffix match abutting the prior language match
        // so RG detection treats it as the scene-prev boundary.
        for (Words.Word word : words) {
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (!matchesAny(lower, languageSuffixes)) continue;
            int ws = word.start();
            ctx
                    .matches
                    .named(LANGUAGE)
                    .filter(m -> m.end() <= ws)
                    .filter(m -> betweenIsSeps(input, m.end(), ws))
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
                ctx.matches.add(new Match(name, lang, word.start(), word.end(),
                        word.value(), 1000, Set.of(), false));
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
        // Find languages immediately after the marker.
        var input = ctx.input;
        var langs = ctx.matches.named(LANGUAGE).toList();

        // Group-aware: when the marker abuts a bracketed group, the releaser
        // intended every language inside that group to be a subtitle language
        // (e.g. "VOSTFR [EN.FR.DE]"), so promote them all rather than just the
        // first one.
        Marker nextGroup = null;
        for (var g : ctx.markers) {
            if (!"group".equals(g.name())) continue;
            if (g.start() < marker.end()) continue;
            if (nextGroup == null || g.start() < nextGroup.start()) nextGroup = g;
        }
        if (nextGroup != null && betweenIsSeps(input, marker.end(), nextGroup.start())) {
            final Marker fg = nextGroup;
            var inGroup = langs.stream()
                    .filter(l -> l.start() >= fg.start() && l.end() <= fg.end())
                    .toList();
            if (!inGroup.isEmpty()) {
                for (var l : inGroup) renameToSubtitle(ctx, l);
                return true;
            }
        }

        var next = langs.stream()
                .filter(l -> l.start() >= marker.end())
                .min(Comparator.comparingInt(Match::start))
                .orElse(null);
        if (next == null) return false;
        if (!betweenIsSeps(input, marker.end(), next.start())) return false;
        renameToSubtitle(ctx, next);
        return true;
    }

    private static boolean isStandaloneAffix(ParseContext ctx, Match marker) {
        var input = ctx.input;
        // Reject when inside a group that contains additional non-sep content
        // beyond the marker itself (e.g. "[ShinBunBu-Subs]").
        for (var g : ctx.markers) {
            if (!"group".equals(g.name())) continue;
            if (marker.start() < g.start() || marker.end() > g.end()) continue;
            int innerStart = Math.min(g.start() + 1, marker.start());
            int innerEnd = Math.max(g.end() - 1, marker.end());
            if (innerStart >= marker.start() && innerEnd <= marker.end()) {
                // marker fills the entire group — fall through to outer-bound check
                continue;
            }
            String before = innerStart < marker.start()
                ? input.substring(innerStart, marker.start()) : "";
            String after = innerEnd > marker.end()
                ? input.substring(marker.end(), innerEnd) : "";
            if (!before.chars().allMatch(c -> Seps.isSep((char) c))
                || !after.chars().allMatch(c -> Seps.isSep((char) c))) {
                return false;
            }
        }
        // Determine bounds: nearest match end before, nearest match start after.
        int leftBound = 0;
        int rightBound = input.length();
        for (var fp : ctx.markers) {
            if (!"path".equals(fp.name())) continue;
            if (marker.start() >= fp.start() && marker.end() <= fp.end()) {
                leftBound = fp.start();
                rightBound = fp.end();
                break;
            }
        }
        int prevEnd = leftBound;
        int nextStart = rightBound;
        for (var m : ctx.matches.all().toList()) {
            if (m == marker) continue;
            if (m.isPrivate() && !LANGUAGE.equals(m.name())) {
                // ignore private markers (incl. self) for this check
                continue;
            }
            if (m.end() <= marker.start() && m.end() > prevEnd) prevEnd = m.end();
            if (m.start() >= marker.end() && m.start() < nextStart) nextStart = m.start();
        }
        String beforeGap = input.substring(prevEnd, marker.start());
        String afterGap = input.substring(marker.end(), nextStart);
        return beforeGap.chars().allMatch(c -> Seps.isSep((char) c))
            && afterGap.chars().allMatch(c -> Seps.isSep((char) c));
    }

    private static boolean renameAdjacentLanguagesBefore(ParseContext ctx, Match marker) {
        var input = ctx.input;
        var prev = ctx.matches.named(LANGUAGE)
                .filter(l -> l.end() <= marker.start())
                .max(Comparator.comparingInt(Match::end))
                .orElse(null);
        if (prev == null) return false;
        if (!betweenIsSeps(input, prev.end(), marker.start())) return false;
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

    private static boolean betweenIsSeps(String input, int start, int end) {
        if (start >= end) return true;
        for (int i = start; i < end; i++) if (!Seps.isSep(input.charAt(i))) return false;
        return true;
    }
}
