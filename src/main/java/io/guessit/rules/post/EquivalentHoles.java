package io.guessit.rules.post;

import io.guessit.engine.*;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.List;

/**
 * Port of python {@code processors.EquivalentHoles}: when a hole in any
 * filepart has the same case-insensitive text as an existing string-valued
 * match, update the existing match's value to the better-cased variant.
 *
 * <p>Drives the {@code title.yml} dir-vs-filename casing fixtures
 * ({@code Some.title/SOME TITLE.mkv} → {@code Some title}, etc).
 */
public final class EquivalentHoles implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        var paths = ctx.markers.stream().filter(m -> "path".equals(m.name())).toList();
        if (paths.isEmpty()) return;
        var sorted = Markers.markerSorted(paths, ctx.matches);
        for (var fp : sorted) {
            var snapshot = ctx.matches.snapshot();
            var holes = Holes.compute(ctx.input, fp.start(), fp.end(),
                snapshot, Match::isPrivate, null, Formatters::cleanup);
            // Limited to title-family names: python applies this to all names,
            // but our extractors miss some matches (e.g. lowercase release_group)
            // that python finds, so a hole appears where python has a match —
            // letting the rule flip the better-cased existing value to the worse
            // one. Title casing is the primary win here anyway.
            var names = List.of("title", "alternative_title", "episode_title");
            for (var name : names) {
                for (var hole : holes) {
                    if (hole == null) continue;
                    var hv = hole.value();
                    if (hv == null || hv.isEmpty()) continue;
                    var current = ctx.matches.named(name).toList();
                    for (var m : current) {
                        if (!(m.value() instanceof String mv)) continue;
                        if (m.tags().contains("equivalent-ignore")) continue;
                        if (!hv.equalsIgnoreCase(mv)) continue;
                        var preferred = preferredString(hv, mv);
                        if (!preferred.equals(mv)) {
                            ctx.matches.replace(m, new Match(m.name(), preferred,
                                m.start(), m.end(), m.raw(), m.priority(), m.tags(), m.isPrivate()));
                        }
                    }
                }
            }
        }
    }

    static String preferredString(String v1, String v2) {
        if (v1.equals(v2)) return v1;
        if (isTitleCase(v1) && !isTitleCase(v2)) return v1;
        if (!isUpper(v1) && isUpper(v2)) return v1;
        if (!isUpper(v1) && !v1.isEmpty() && Character.isUpperCase(v1.charAt(0))
                && !v2.isEmpty() && !Character.isUpperCase(v2.charAt(0))) return v1;
        if (countTitleWords(v1) > countTitleWords(v2)) return v1;
        return v2;
    }

    static boolean isUpper(String s) {
        var hasLetter = false;
        for (var c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c)) return false;
            }
        }
        return hasLetter;
    }

    /** Mirror of python {@code str.istitle}. */
    static boolean isTitleCase(String s) {
        var hasCased = false;
        var prevCased = false;
        for (var c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                hasCased = true;
                if (!prevCased) {
                    if (!Character.isUpperCase(c)) return false;
                } else {
                    if (!Character.isLowerCase(c)) return false;
                }
                prevCased = true;
            } else {
                prevCased = false;
            }
        }
        return hasCased;
    }

    static int countTitleWords(String s) {
        var n = 0;
        var sb = new StringBuilder();
        for (var c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (!sb.isEmpty()) {
                if (isTitleCase(sb.toString())) n++;
                sb.setLength(0);
            }
        }
        if (!sb.isEmpty() && isTitleCase(sb.toString())) n++;
        return n;
    }
}
