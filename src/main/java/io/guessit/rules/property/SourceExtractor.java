package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceExtractor implements Extractor {
    @Override public String name() { return "source"; }
    @Override public int priority() { return 1000; }

    private record Rule(List<String> patterns, String prefix, String suffix, String source,
                        String otherValue, Set<String> tags, boolean weak) {}

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("source");
        var ripPrefix = String.valueOf(section.getOrDefault("rip_prefix", "(?<other>Rip)-?"));
        var ripSuffix = String.valueOf(section.getOrDefault("rip_suffix", "-?(?<other>Rip)"));
        var optRipSuffix = "(?:" + ripSuffix + ")?";
        var optRipPrefix = "(?:" + ripPrefix + ")?";

        var rules = buildRules(ripPrefix, ripSuffix, optRipPrefix, optRipSuffix);

        for (var rule : rules) {
            var pattern = compileRule(rule);
            if (pattern == null) continue;
            apply(ctx, pattern, rule);
        }
    }

    private static List<Rule> buildRules(String ripPrefix, String ripSuffix,
                                         String optRipPrefix, String optRipSuffix) {
        var common = Set.<String>of();
        var rules = new ArrayList<Rule>();
        rules.add(new Rule(List.of("VHS"), "", optRipSuffix, "VHS", "Rip", common, false));
        rules.add(new Rule(List.of("CAM"), "", optRipSuffix, "Camera", "Rip", common, false));
        rules.add(new Rule(List.of("HD-?CAM"), "", optRipSuffix, "HD Camera", "Rip", common, false));
        rules.add(new Rule(List.of("TELESYNC", "TS"), "", optRipSuffix, "Telesync", "Rip", common, false));
        rules.add(new Rule(List.of("HD-?TELESYNC", "HD-?TS"), "", optRipSuffix, "HD Telesync", "Rip", common, false));
        rules.add(new Rule(List.of("WORKPRINT", "WP"), "", "", "Workprint", null, common, false));
        rules.add(new Rule(List.of("TELECINE", "TC"), "", optRipSuffix, "Telecine", "Rip", common, false));
        rules.add(new Rule(List.of("HD-?TELECINE", "HD-?TC"), "", optRipSuffix, "HD Telecine", "Rip", common, false));
        rules.add(new Rule(List.of("PPV"), "", optRipSuffix, "Pay-per-view", "Rip", common, false));
        rules.add(new Rule(List.of("SD-?TV"), "", optRipSuffix, "TV", "Rip", common, false));
        rules.add(new Rule(List.of("TV"), "", ripSuffix, "TV", "Rip", common, false));
        rules.add(new Rule(List.of("TV", "SD-?TV"), ripPrefix, "", "TV", "Rip", common, false));
        rules.add(new Rule(List.of("TV-?(?=Dub)"), "", "", "TV", null, common, false));
        rules.add(new Rule(List.of("DVB", "PD-?TV"), "", optRipSuffix, "Digital TV", "Rip", common, false));
        rules.add(new Rule(List.of("DVD"), "", optRipSuffix, "DVD", "Rip", common, false));
        rules.add(new Rule(List.of("DM"), "", optRipSuffix, "Digital Master", "Rip", common, false));
        rules.add(new Rule(List.of("VIDEO-?TS", "DVD-?R(?:$|(?!E))", "DVD-?9", "DVD-?5"),
            "", "", "DVD", null, common, false));
        rules.add(new Rule(List.of("HD-?TV"), "", optRipSuffix, "HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("TV-?HD"), "", ripSuffix, "HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("TV"), "", "-?(?<other>Rip-?HD)", "HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("VOD"), "", optRipSuffix, "Video on Demand", "Rip", common, false));
        rules.add(new Rule(List.of("WEB", "WEB-?DL"), "", ripSuffix, "Web", "Rip", common, false));
        rules.add(new Rule(List.of("WEB-?(?<another>Cap)"), "", optRipSuffix, "Web", "Rip", common, false));
        rules.add(new Rule(List.of("WEB-?DL", "WEB-?U?HD", "DL-?WEB", "DL(?=-?Mux)"),
            "", "", "Web", null, common, false));
        rules.add(new Rule(List.of("WEB"), "", "", "Web", null, Set.of("weak.source"), true));
        rules.add(new Rule(List.of("HD-?DVD"), "", optRipSuffix, "HD-DVD", "Rip", common, false));
        rules.add(new Rule(List.of("Blu-?ray", "BD", "BD[59]", "BD25", "BD50"),
            "", optRipSuffix, "Blu-ray", "Rip", common, false));
        rules.add(new Rule(List.of("(?<another>BR)-?(?=Scr(?:eener)?)", "(?<another>BR)-?(?=Mux)"),
            "", "", "Blu-ray", null, common, false));
        rules.add(new Rule(List.of("(?<another>BR)"), "", ripSuffix, "Blu-ray", "Rip", common, false));
        rules.add(new Rule(List.of("Ultra-?Blu-?ray", "Blu-?ray-?Ultra"), "", "", "Ultra HD Blu-ray", null, common, false));
        rules.add(new Rule(List.of("AHDTV"), "", "", "Analog HDTV", null, common, false));
        rules.add(new Rule(List.of("UHD-?TV"), "", optRipSuffix, "Ultra HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("UHD"), "", ripSuffix, "Ultra HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("DSR", "DTH"), "", optRipSuffix, "Satellite", "Rip", common, false));
        rules.add(new Rule(List.of("DSR?", "SAT"), "", ripSuffix, "Satellite", "Rip", common, false));
        return rules;
    }

    private static Pattern compileRule(Rule rule) {
        var alt = String.join("|", rule.patterns());
        var src = rule.prefix() + "(" + alt + ")" + rule.suffix();
        try {
            return Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void apply(ParseContext ctx, Pattern p, Rule rule) {
        var input = ctx.input;
        var validator = Validators.sepsBefore(input).or(Validators.sepsAfter(input));
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var sourceMatch = new Match("source", rule.source(), s, e,
                input.substring(s, e), 1000, rule.tags(), false);
            if (!validator.test(sourceMatch)) continue;
            ctx.matches.add(sourceMatch);
            if (rule.otherValue() != null) {
                int os = groupStart(matcher, "other");
                int oe = groupEnd(matcher, "other");
                if (os >= 0 && oe > os) {
                    ctx.matches.add(new Match("other", rule.otherValue(), os, oe,
                        input.substring(os, oe), 1000, Set.of("coexist"), false));
                }
            }
        }
    }

    private static int groupStart(Matcher m, String name) {
        try { return m.start(name); } catch (IllegalArgumentException | IllegalStateException e) { return -1; }
    }
    private static int groupEnd(Matcher m, String name) {
        try { return m.end(name); } catch (IllegalArgumentException | IllegalStateException e) { return -1; }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        validatePrefixSuffix(ctx);
        validateWeakSource(ctx);
        upgradeUltraHdBluray(ctx);
    }

    private void validatePrefixSuffix(ParseContext ctx) {
        var input = ctx.input;
        var sources = ctx.matches.named("source").toList();
        var toRemove = new ArrayList<Match>();
        var sepsBefore = Validators.sepsBefore(input);
        var sepsAfter = Validators.sepsAfter(input);
        for (var s : sources) {
            if (!sepsBefore.test(s)) {
                if (!hasNeighborTag(ctx, s.start() - 1, "source-prefix")) { toRemove.add(s); continue; }
            }
            if (!sepsAfter.test(s)) {
                if (!hasNeighborTag(ctx, s.end(), "source-suffix")) toRemove.add(s);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void validateWeakSource(ParseContext ctx) {
        var weaks = ctx.matches.named("source")
            .filter(m -> m.tags().contains("weak.source"))
            .toList();
        if (weaks.isEmpty()) return;
        var toRemove = new ArrayList<Match>();
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            for (var weak : weaks) {
                if (!filepart.covers(weak.start(), weak.end())) continue;
                boolean later = ctx.matches.named("source")
                    .anyMatch(m -> m != weak && m.start() >= weak.end() && m.end() <= filepart.end());
                if (!later) continue;
                var pre = ctx.input.substring(filepart.start(), weak.start());
                if (!pre.isBlank()) toRemove.add(weak);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void upgradeUltraHdBluray(ParseContext ctx) {
        var bds = ctx.matches.named("source")
            .filter(m -> "Blu-ray".equals(m.value()))
            .toList();
        if (bds.isEmpty()) return;
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            for (var bd : bds) {
                if (!filepart.covers(bd.start(), bd.end())) continue;
                boolean has2160p = ctx.matches.named("screen_size")
                    .anyMatch(m -> "2160p".equals(m.value()) && filepart.covers(m.start(), m.end()));
                if (!has2160p) continue;
                ctx.matches.replace(bd, new Match("source", "Ultra HD Blu-ray",
                    bd.start(), bd.end(), bd.raw(), bd.priority(), bd.tags(), bd.isPrivate()));
            }
        }
    }

    private static boolean hasNeighborTag(ParseContext ctx, int pos, String tag) {
        return ctx.matches.all().anyMatch(m -> m.tags().contains(tag) && m.start() <= pos && pos <= m.end());
    }
}
