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

    @Override
    public void extract(ParseContext ctx) {
        var allowed = allowedLanguages(ctx);
        if (allowed.isEmpty()) return;

        var registry = LanguageRegistry.instance();
        var input = ctx.input;

        emitAffixes(ctx);

        for (var word : Words.iter(input)) {
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (lower.chars().allMatch(Character::isDigit)) continue;
            var lang = registry.find(lower).orElse(null);
            if (lang == null) continue;
            if (!isAllowed(lang, allowed)) continue;
            ctx.matches.add(new Match("language", lang, word.start(), word.end(),
                input.substring(word.start(), word.end()), 1000, Set.of(), false));
        }
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

    private static void emitAffixes(ParseContext ctx) {
        var section = ctx.config.section("language");
        var subtitleAffixes  = stringList(section.get("subtitle_affixes"));
        var subtitlePrefixes = combine(subtitleAffixes, stringList(section.get("subtitle_prefixes")));
        var subtitleSuffixes = combine(subtitleAffixes, stringList(section.get("subtitle_suffixes")));
        var languageAffixes  = stringList(section.get("language_affixes"));
        var languageSuffixes = combine(languageAffixes, stringList(section.get("language_suffixes")));
        var languagePrefixes = combine(languageAffixes, stringList(section.get("language_prefixes")));

        emitAffixGroup(ctx, subtitlePrefixes, "subtitle_language.prefix");
        emitAffixGroup(ctx, subtitleSuffixes, "subtitle_language.suffix");
        emitAffixGroup(ctx, languagePrefixes, "language.prefix");
        emitAffixGroup(ctx, languageSuffixes, "language.suffix");
    }

    private static void emitAffixGroup(ParseContext ctx, List<String> affixes, String name) {
        if (affixes.isEmpty()) return;
        var validator = Validators.sepsSurround(ctx.input);
        var hay = ctx.input.toLowerCase(Locale.ROOT);
        for (var aff : affixes) {
            var n = aff.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int i = hay.indexOf(n, from);
                if (i < 0) break;
                int e = i + n.length();
                var m = new Match(name, aff, i, e, ctx.input.substring(i, e), 1000, Set.of(), true);
                if (validator.test(m)) ctx.matches.add(m);
                from = i + 1;
            }
        }
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
        renameWithSubtitlePrefix(ctx);
        renameWithSubtitleSuffix(ctx);
        renameWithSubtitleExtension(ctx);
        dropUndeterminedWhenRealLangPresent(ctx);
        dropPrivateAffixes(ctx);
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

    private void renameWithSubtitlePrefix(ParseContext ctx) {
        var prefixes = ctx.matches.all()
            .filter(m -> "subtitle_language.prefix".equals(m.name()))
            .toList();
        if (prefixes.isEmpty()) return;
        var langs = ctx.matches.named("language").toList();
        for (var prefix : prefixes) {
            var lang = langs.stream()
                .filter(l -> l.start() >= prefix.end())
                .min((a, b) -> Integer.compare(a.start(), b.start()))
                .orElse(null);
            if (lang == null) continue;
            if (!betweenIsSeps(ctx.input, prefix.end(), lang.start())) continue;
            ctx.matches.replace(lang, new Match("subtitle_language", lang.value(),
                lang.start(), lang.end(), lang.raw(), lang.priority(), lang.tags(), false));
        }
    }

    private void renameWithSubtitleSuffix(ParseContext ctx) {
        var suffixes = ctx.matches.all()
            .filter(m -> "subtitle_language.suffix".equals(m.name()))
            .toList();
        if (suffixes.isEmpty()) return;
        var langs = ctx.matches.named("language").toList();
        for (var suffix : suffixes) {
            var lang = langs.stream()
                .filter(l -> l.end() <= suffix.start())
                .max((a, b) -> Integer.compare(a.end(), b.end()))
                .orElse(null);
            if (lang == null) continue;
            if (!betweenIsSeps(ctx.input, lang.end(), suffix.start())) continue;
            ctx.matches.replace(lang, new Match("subtitle_language", lang.value(),
                lang.start(), lang.end(), lang.raw(), lang.priority(), lang.tags(), false));
        }
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
        ctx.matches.replace(lang, new Match("subtitle_language", lang.value(),
            lang.start(), lang.end(), lang.raw(), lang.priority(), lang.tags(), false));
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
            .filter(m -> m.name().endsWith(".prefix") || m.name().endsWith(".suffix"))
            .filter(m -> m.name().startsWith("language") || m.name().startsWith("subtitle_language"))
            .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean betweenIsSeps(String input, int start, int end) {
        if (start >= end) return true;
        for (int i = start; i < end; i++) if (!Seps.isSep(input.charAt(i))) return false;
        return true;
    }
}
