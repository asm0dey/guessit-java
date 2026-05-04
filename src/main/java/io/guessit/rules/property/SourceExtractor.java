package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code source} (BluRay, WEB-DL, HDTV, DVD, …).
 *
 * <p>Each row in {@link #buildRules} is a {@link Rule}: a list of base
 * patterns, optional rip-prefix/suffix that may carry an {@code other=Rip}
 * companion match, the canonical source value, and the optional secondary
 * value emitted from the {@code (?<another>...)} capture (e.g. "Remux"
 * piggy-backed onto a BluRay match).
 *
 * <p>The rule table is the source of truth for parity with Python
 * {@code guessit.rules.properties.source}; per-rule emission is centralised
 * in {@link #apply} so the table stays declarative.
 */
public final class SourceExtractor implements Extractor {

    public static final String SOURCE = "source";
    public static final String OTHER = "other";

    @Override
    public String name() {
        return SOURCE;
    }

    /**
     * One row of the source rule table.
     *
     * @param patterns     base alternation entries (will be wrapped with prefix/suffix)
     * @param prefix       optional regex prefix (e.g. rip prefix capturing {@code Rip})
     * @param suffix       optional regex suffix (analogous)
     * @param source       canonical {@code source} value emitted on the main span
     * @param otherValue   when set, value to emit as a sibling {@code other} match
     *                     pulled from the {@code (?<other>...)} capture
     * @param anotherValue when set, value to emit as a sibling {@code other} match
     *                     pulled from the {@code (?<another>...)} capture
     * @param tags         tags applied to every emitted match for this rule
     * @param weak         marks the rule as a weak candidate (yields to overlap losers)
     */
    private record Rule(List<String> patterns, String prefix, String suffix, String source,
                        String otherValue, String anotherValue, Set<String> tags, boolean weak) {
    }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(SOURCE);
        var ripPrefix = String.valueOf(section.getOrDefault("rip_prefix", "(?<other>Rip)-?"));
        var ripSuffix = String.valueOf(section.getOrDefault("rip_suffix", "-?(?<other>Rip)"));
        var optRipSuffix = "(?:" + ripSuffix + ")?";

        var rules = buildRules(ripPrefix, ripSuffix, optRipSuffix);

        for (var rule : rules) {
            var pattern = compileRule(rule);
            if (pattern == null) continue;
            apply(ctx, pattern, rule);
        }
    }

    private static List<Rule> buildRules(String ripPrefix, String ripSuffix,
                                         String optRipSuffix) {
        // Tag every source match as a video-codec-prefix so a video_codec
        // immediately following it (e.g. "PDTVx264") survives validateVideoCodec.
        var common = Set.of("video-codec-prefix", "streaming_service.suffix");
        var rules = new ArrayList<Rule>();
        rules.add(new Rule(List.of("VHS"), "", optRipSuffix, "VHS", "Rip", null, common, false));
        rules.add(new Rule(List.of("CAM"), "", optRipSuffix, "Camera", "Rip", null, common, false));
        rules.add(new Rule(List.of("HD-?CAM"), "", optRipSuffix, "HD Camera", "Rip", null, common, false));
        rules.add(new Rule(List.of("TELESYNC", "TS"), "", optRipSuffix, "Telesync", "Rip", null, common, false));
        rules.add(new Rule(List.of("HD-?TELESYNC", "HD-?TS"), "", optRipSuffix, "HD Telesync", "Rip", null, common, false));
        rules.add(new Rule(List.of("WORKPRINT", "WP"), "", "", "Workprint", null, null, common, false));
        rules.add(new Rule(List.of("TELECINE", "TC"), "", optRipSuffix, "Telecine", "Rip", null, common, false));
        rules.add(new Rule(List.of("HD-?TELECINE", "HD-?TC"), "", optRipSuffix, "HD Telecine", "Rip", null, common, false));
        rules.add(new Rule(List.of("PPV"), "", optRipSuffix, "Pay-per-view", "Rip", null, common, false));
        rules.add(new Rule(List.of("SD-?TV"), "", optRipSuffix, "TV", "Rip", null, common, false));
        rules.add(new Rule(List.of("TV"), "", ripSuffix, "TV", "Rip", null, common, false));
        rules.add(new Rule(List.of("TV", "SD-?TV"), ripPrefix, "", "TV", "Rip", null, common, false));
        rules.add(new Rule(List.of("TV-?(?=Dub)"), "", "", "TV", null, null, common, false));
        rules.add(new Rule(List.of("DVB", "PD-?TV"), "", optRipSuffix, "Digital TV", "Rip", null, common, false));
        rules.add(new Rule(List.of("DVD"), "", optRipSuffix, "DVD", "Rip", null, common, false));
        rules.add(new Rule(List.of("DM"), "", optRipSuffix, "Digital Master", "Rip", null, common, false));
        rules.add(new Rule(List.of("VIDEO-?TS", "DVD-?R(?:$|(?!E))", "DVD-?9", "DVD-?5"),
                "", "", "DVD", null, null, common, false));
        rules.add(new Rule(List.of("HD-?TV"), "", optRipSuffix, "HDTV", "Rip", null, common, false));
        rules.add(new Rule(List.of("TV-?HD"), "", ripSuffix, "HDTV", "Rip", null, common, false));
        rules.add(new Rule(List.of("TV"), "", "-?(?<other>Rip-?HD)", "HDTV", "Rip", null, common, false));
        rules.add(new Rule(List.of("VOD"), "", optRipSuffix, "Video on Demand", "Rip", null, common, false));
        rules.add(new Rule(List.of("WEB", "WEB-?DL"), "", ripSuffix, "Web", "Rip", null, common, false));
        // WEBCap → Web source + 'another' (Cap text) becomes other=Rip too.
        rules.add(new Rule(List.of("WEB-?(?<another>Cap)"), "", optRipSuffix, "Web", "Rip", "Rip", common, false));
        rules.add(new Rule(List.of("WEB-?DL", "WEB-?U?HD", "DL-?WEB", "DL(?=-?Mux)"),
                "", "", "Web", null, null, common, false));
        rules.add(new Rule(List.of("WEB"), "", "", "Web", null, null, Set.of("weak.source"), true));
        rules.add(new Rule(List.of("HD-?DVD"), "", optRipSuffix, "HD-DVD", "Rip", null, common, false));
        rules.add(new Rule(List.of("Blu-?ray", "BD", "BD[59]", "BD25", "BD50"),
                "", optRipSuffix, "Blu-ray", "Rip", null, common, false));
        rules.add(new Rule(List.of("(?<another>BR)-?(?=Scr(?:eener)?)", "(?<another>BR)-?(?=Mux)"),
                "", "", "Blu-ray", null, "Reencoded", common, false));
        rules.add(new Rule(List.of("(?<another>BR)"), "", ripSuffix, "Blu-ray", "Rip", "Reencoded", common, false));
        rules.add(new Rule(List.of("Ultra-?Blu-?ray", "Blu-?ray-?Ultra"), "", "", "Ultra HD Blu-ray", null, null, common, false));
        rules.add(new Rule(List.of("AHDTV"), "", "", "Analog HDTV", null, null, common, false));
        rules.add(new Rule(List.of("UHD-?TV"), "", optRipSuffix, "Ultra HDTV", "Rip", null, common, false));
        rules.add(new Rule(List.of("UHD"), "", ripSuffix, "Ultra HDTV", "Rip", null, common, false));
        rules.add(new Rule(List.of("DSR", "DTH"), "", optRipSuffix, "Satellite", "Rip", null, common, false));
        rules.add(new Rule(List.of("DSR?", "SAT"), "", ripSuffix, "Satellite", "Rip", null, common, false));
        return rules;
    }

    private static Pattern compileRule(Rule rule) {
        var alt = String.join("|", rule.patterns());
        var src = rule.prefix() + "(" + alt + ")" + rule.suffix();
        try {
            return Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE);
        } catch (Exception _) {
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
            var sourceMatch = new Match(SOURCE, rule.source(), s, e,
                    input.substring(s, e), 1000, rule.tags(), false);
            if (!validator.test(sourceMatch)) continue;
            ctx.matches.add(sourceMatch);
            if (rule.otherValue() != null) {
                int os = groupStart(matcher, OTHER);
                int oe = groupEnd(matcher, OTHER);
                if (os >= 0 && oe > os) {
                    ctx.matches.add(new Match(OTHER, rule.otherValue(), os, oe,
                            input.substring(os, oe), 1000, Set.of("coexist"), false));
                }
            }
            if (rule.anotherValue() != null) {
                int as = groupStart(matcher, "another");
                int ae = groupEnd(matcher, "another");
                if (as >= 0 && ae > as) {
                    ctx.matches.add(new Match(OTHER, rule.anotherValue(), as, ae,
                            input.substring(as, ae), 1000, Set.of("coexist"), false));
                }
            }
        }
    }

    private static int groupStart(Matcher m, String name) {
        try {
            return m.start(name);
        } catch (IllegalArgumentException | IllegalStateException _) {
            return -1;
        }
    }

    private static int groupEnd(Matcher m, String name) {
        try {
            return m.end(name);
        } catch (IllegalArgumentException | IllegalStateException _) {
            return -1;
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        validatePrefixSuffix(ctx);
        validateWeakSource(ctx);
        upgradeUltraHdBluray(ctx);
    }

    private void validatePrefixSuffix(ParseContext ctx) {
        var input = ctx.input;
        var sources = ctx.matches.named(SOURCE).toList();
        var toRemove = new ArrayList<Match>();
        var sepsBefore = Validators.sepsBefore(input);
        var sepsAfter = Validators.sepsAfter(input);
        for (var s : sources) {
            if (!sepsBefore.test(s) && !hasNeighborTag(ctx, s.start() - 1, "source-prefix")) {
                toRemove.add(s);
                continue;
            }
            if (!sepsAfter.test(s) && !hasNeighborTag(ctx, s.end(), "source-suffix")) toRemove.add(s);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void validateWeakSource(ParseContext ctx) {
        var weaks = ctx.matches.named(SOURCE)
                .filter(m -> m.tags().contains("weak.source"))
                .toList();
        if (weaks.isEmpty()) return;
        var toRemove = new ArrayList<Match>();
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            for (var weak : weaks) {
                if (!filepart.covers(weak.start(), weak.end())) continue;
                boolean later = ctx.matches.named(SOURCE)
                        .anyMatch(m -> m != weak && m.start() >= weak.end() && m.end() <= filepart.end());
                if (!later) continue;
                var pre = ctx.input.substring(filepart.start(), weak.start());
                if (!pre.isBlank()) toRemove.add(weak);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void upgradeUltraHdBluray(ParseContext ctx) {
        var bds = ctx.matches.named(SOURCE)
                .filter(m -> "Blu-ray".equals(m.value()))
                .toList();
        if (bds.isEmpty()) return;
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            for (var bd : bds) {
                if (!filepart.covers(bd.start(), bd.end())) continue;
                // Find an Ultra HD other before the Blu-ray with no blocking matches in between.
                var uhdOther = findUltraHd(ctx, filepart.start(), bd.start(), true);
                boolean ok = uhdOther != null && validRange(ctx, uhdOther.end(), bd.start());
                if (!ok) {
                    uhdOther = findUltraHd(ctx, bd.end(), filepart.end(), false);
                    ok = uhdOther != null && validRange(ctx, bd.end(), uhdOther.start());
                }
                if (!ok) {
                    boolean has2160p = ctx.matches.named("screen_size")
                            .anyMatch(m -> "2160p".equals(m.value()) && filepart.covers(m.start(), m.end()));
                    if (!has2160p) continue;
                    uhdOther = null;
                }
                if (uhdOther != null) {
                    ctx.matches.remove(uhdOther);
                }
                ctx.matches.replace(bd, new Match(SOURCE, "Ultra HD Blu-ray",
                        bd.start(), bd.end(), bd.raw(), bd.priority(), bd.tags(), bd.isPrivate()));
            }
        }
    }

    private static Match findUltraHd(ParseContext ctx, int start, int end, boolean preferLast) {
        Match best = null;
        for (var m : ctx.matches.named(OTHER).toList()) {
            if (m.isPrivate()) continue;
            if (!"Ultra HD".equals(m.value())) continue;
            if (m.start() < start || m.end() > end) continue;
            if (best == null) {
                best = m;
                continue;
            }
            if (preferLast) {
                if (m.end() > best.end()) best = m;
            } else {
                if (m.start() < best.start()) best = m;
            }
        }
        return best;
    }

    private static boolean validRange(ParseContext ctx, int s, int e) {
        if (s >= e) return true;
        // Reject if any non-private public match in this range is not a screen_size, color_depth,
        // or another 'uhdbluray-neighbor' tagged other.
        for (var m : ctx.matches.all().toList()) {
            if (m.isPrivate()) continue;
            if (m.start() < s || m.end() > e) continue;
            if ("screen_size".equals(m.name())) continue;
            if ("color_depth".equals(m.name())) continue;
            if (OTHER.equals(m.name()) && m.tags().contains("uhdbluray-neighbor")) continue;
            return false;
        }
        // Reject if there is a non-sep "hole" not covered by any match
        var input = ctx.input;
        int pos = s;
        var matchesInRange = new ArrayList<Match>();
        for (var m : ctx.matches.all().toList()) {
            if (m.start() >= s && m.end() <= e) matchesInRange.add(m);
        }
        matchesInRange.sort((a, b) -> Integer.compare(a.start(), b.start()));
        for (var m : matchesInRange) {
            while (pos < m.start()) {
                if (!Seps.isSep(input.charAt(pos))) return false;
                pos++;
            }
            pos = Math.max(pos, m.end());
        }
        while (pos < e) {
            if (!Seps.isSep(input.charAt(pos))) return false;
            pos++;
        }
        return true;
    }

    private static boolean hasNeighborTag(ParseContext ctx, int pos, String tag) {
        return ctx.matches.all().anyMatch(m -> m.tags().contains(tag) && m.start() <= pos && pos <= m.end());
    }
}
