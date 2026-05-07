package io.guessit.rules.post;

import io.guessit.engine.*;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Objects;
import java.util.stream.Stream;

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
            Stream
                    .of(MatchName.TITLE, MatchName.ALTERNATIVE_TITLE, MatchName.EPISODE_TITLE)
                    .flatMap(name -> holes
                            .stream()
                            .filter(hole -> hole != null && hole.value() != null && !hole.value().isEmpty())
                            .flatMap(hole -> ctx.matches.named(name)
                                    .filter(m -> m.value() instanceof String)
                                    .filter(m -> !m.tags().contains("equivalent-ignore"))
                                    .filter(m -> hole.value().equalsIgnoreCase((String) m.value()))
                                    .map(m -> {
                                        var mv = (String) m.value();
                                        var preferred = preferredString(hole.value(), mv);
                                        return preferred.equals(mv) ? null :
                                                new Object[]{
                                                        m,
                                                        new Match(
                                                                m.name(),
                                                                preferred,
                                                                m.start(),
                                                                m.end(),
                                                                m.raw(),
                                                                m.priority(),
                                                                m.tags(),
                                                                m.isPrivate())
                                                };
                                    })
                                    .filter(Objects::nonNull)))
                    .forEach(pair -> ctx.matches.replace((Match) pair[0], (Match) pair[1]));
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

    /**
     * Mirror of python {@code str.istitle}.
     */
    static boolean isTitleCase(String s) {
        var hasCased = false;
        var prevCased = false;
        for (var c : s.toCharArray()) {
            if (!Character.isLetter(c)) {
                prevCased = false;
                continue;
            }
            hasCased = true;
            if (!isExpectedTitleCase(c, prevCased)) return false;
            prevCased = true;
        }
        return hasCased;
    }

    private static boolean isExpectedTitleCase(char c, boolean prevCased) {
        return prevCased ? Character.isLowerCase(c) : Character.isUpperCase(c);
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
