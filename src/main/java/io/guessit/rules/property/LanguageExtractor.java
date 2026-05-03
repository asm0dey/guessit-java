package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.lang.Language;
import io.guessit.lang.LanguageRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LanguageExtractor implements Extractor {
    @Override public String name() { return "language"; }
    @Override public int priority() { return 1000; }

    private static final String UND_NAME = "Undetermined";
    private static final String MUL_NAME = "Multiple languages";
    private static final String MARKER_PREFIX = "subtitle_language.prefix";

    @Override
    public void extract(ParseContext ctx) {
        var allowed = allowedLanguages(ctx);
        if (allowed.isEmpty()) return;

        var registry = LanguageRegistry.instance();
        var input = ctx.input;

        var section = ctx.config.section("language");
        var subtitleAffixes = stringList(section.get("subtitle_affixes"));
        var subtitlePrefixes = combine(subtitleAffixes, stringList(section.get("subtitle_prefixes")));
        var subtitleSuffixes = combine(subtitleAffixes, stringList(section.get("subtitle_suffixes")));
        var languageAffixes = stringList(section.get("language_affixes"));
        var languagePrefixes = combine(languageAffixes, stringList(section.get("language_prefixes")));
        var languageSuffixes = combine(languageAffixes, stringList(section.get("language_suffixes")));

        var words = Words.iter(input);
        for (int wi = 0; wi < words.size(); wi++) {
            var word = words.get(wi);
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (lower.chars().allMatch(Character::isDigit)) continue;

            // 1. Standalone-word affix → emit a private prefix marker; rename happens in postprocess.
            if (matchesAny(lower, subtitlePrefixes) || matchesAny(lower, subtitleSuffixes)) {
                ctx.matches.add(new Match(MARKER_PREFIX, word.value(),
                    word.start(), word.end(), word.value(), 1000, Set.of(), true));
                continue;
            }

            // 2. Direct match on the whole word.
            var lang = registry.find(lower).orElse(null);
            if (lang != null && isAllowed(lang, allowed)) {
                ctx.matches.add(new Match("language", lang, word.start(), word.end(),
                    word.value(), 1000, Set.of(), false));
                continue;
            }

            // 3. In-word prefix / suffix strip → emit at the whole-word span (raw includes affix).
            if (tryStripAffix(ctx, word, lower, subtitlePrefixes, true, allowed, registry, "subtitle_language")) continue;
            if (tryStripAffix(ctx, word, lower, languagePrefixes, true, allowed, registry, "language")) continue;
            if (tryStripAffix(ctx, word, lower, subtitleSuffixes, false, allowed, registry, "subtitle_language")) continue;
            if (tryStripAffix(ctx, word, lower, languageSuffixes, false, allowed, registry, "language")) continue;
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
        var lc = new HashSet<String>(allowed.size());
        for (var s : allowed) lc.add(s.toLowerCase(Locale.ROOT));
        if (lang.alpha2() != null && lc.contains(lang.alpha2().toLowerCase(Locale.ROOT))) return true;
        if (lang.alpha3() != null && lc.contains(lang.alpha3().toLowerCase(Locale.ROOT))) return true;
        if (lang.name() != null && lc.contains(lang.name().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
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
        out.addAll(a); out.addAll(b);
        return out;
    }

    @Override
    public void postProcess(ParseContext ctx) {
        dropCommonWordLanguages(ctx);
        renameStandaloneAffixes(ctx);
        renameWithSubtitleExtension(ctx);
        dropUndeterminedWhenRealLangPresent(ctx);
        dropPrivateAffixes(ctx);
        cleanupReleaseGroups(ctx);
    }

    private void cleanupReleaseGroups(ParseContext ctx) {
        var groups = ctx.matches.named("release_group").toList();
        for (var g : groups) {
            var langs = ctx.matches.named("subtitle_language")
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
        var toRemove = new ArrayList<Match>();
        for (var name : List.of("language", "subtitle_language")) {
            for (var match : ctx.matches.named(name).toList()) {
                if (lc.contains(match.raw().toLowerCase(Locale.ROOT))) toRemove.add(match);
            }
        }
        for (var match : toRemove) ctx.matches.remove(match);
    }

    private void renameStandaloneAffixes(ParseContext ctx) {
        var markers = ctx.matches.all()
            .filter(m -> MARKER_PREFIX.equals(m.name()))
            .toList();
        if (markers.isEmpty()) return;

        for (var marker : markers) {
            var renamed = renameAdjacentLanguagesAfter(ctx, marker);
            if (!renamed) renameAdjacentLanguagesBefore(ctx, marker);
        }
    }

    private static boolean renameAdjacentLanguagesAfter(ParseContext ctx, Match marker) {
        // Find languages immediately after the marker.
        var input = ctx.input;
        var langs = ctx.matches.named("language").toList();

        // Group-aware: if the next group starts adjacent to the marker, rename ALL languages in it.
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
            .min((a, b) -> Integer.compare(a.start(), b.start()))
            .orElse(null);
        if (next == null) return false;
        if (!betweenIsSeps(input, marker.end(), next.start())) return false;
        renameToSubtitle(ctx, next);
        return true;
    }

    private static boolean renameAdjacentLanguagesBefore(ParseContext ctx, Match marker) {
        var input = ctx.input;
        var prev = ctx.matches.named("language")
            .filter(l -> l.end() <= marker.start())
            .max((a, b) -> Integer.compare(a.end(), b.end()))
            .orElse(null);
        if (prev == null) return false;
        if (!betweenIsSeps(input, prev.end(), marker.start())) return false;
        renameToSubtitle(ctx, prev);
        return true;
    }

    private static void renameToSubtitle(ParseContext ctx, Match lang) {
        ctx.matches.replace(lang, new Match("subtitle_language", lang.value(),
            lang.start(), lang.end(), lang.raw(), lang.priority() + 1, lang.tags(), false));
    }

    private void renameWithSubtitleExtension(ParseContext ctx) {
        var subtitleExt = ctx.matches.named("container")
            .filter(m -> m.tags().contains("subtitle") && m.tags().contains("extension"))
            .findFirst()
            .orElse(null);
        if (subtitleExt == null) return;
        var lang = ctx.matches.named("language")
            .filter(l -> l.end() <= subtitleExt.start())
            .max((a, b) -> Integer.compare(a.end(), b.end()))
            .orElse(null);
        if (lang == null) return;
        renameToSubtitle(ctx, lang);
    }

    private void dropUndeterminedWhenRealLangPresent(ParseContext ctx) {
        for (var prop : List.of("language", "subtitle_language")) {
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

    private void dropPrivateAffixes(ParseContext ctx) {
        var toRemove = ctx.matches.all()
            .filter(m -> m.name().equals(MARKER_PREFIX))
            .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean betweenIsSeps(String input, int start, int end) {
        if (start >= end) return true;
        for (int i = start; i < end; i++) if (!Seps.isSep(input.charAt(i))) return false;
        return true;
    }
}
