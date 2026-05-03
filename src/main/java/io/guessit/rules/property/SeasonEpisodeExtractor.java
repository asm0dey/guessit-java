package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

public final class SeasonEpisodeExtractor implements Extractor {
    private static final Pattern HEAD_S_E = Pattern.compile(
        "(?i)s(?<season>\\d+)@?(?<episodeMarker>e|ex|xe|ep|x|d)@?(?<episode>\\d+)");
    private static final Pattern TAIL_E = Pattern.compile(
        "(?i)(?<episodeSeparator>ex|xe|ep|and|et|to|e|x|d|\\.|_| |-|\\+|&|a|~)@?(?<episode>\\d+)");
    private static final java.util.Set<String> STRONG_SEPS = java.util.Set.of("+", "&", "and", "et");
    private static final java.util.Set<String> RANGE_SEPS = java.util.Set.of("-", "~", "to", "a");
    private static final java.util.Set<String> MARKER_SEPS = java.util.Set.of("e", "ex", "xe", "ep", "x", "d");
    private static final int MAX_RANGE_GAP = 1;
    private static final Pattern HEAD_NUM_X = Pattern.compile(
        "(?i)(?<season>\\d+)@?(?<episodeMarker>x)@?(?<episode>\\d+)");
    private static final Pattern TAIL_NUM_X = Pattern.compile(
        "(?i)[ ._\\-]+(?<season>\\d+)@?(?<episodeMarker>x)@?(?<episode>\\d+)");
    private static final Pattern HEAD_S = Pattern.compile(
        "(?i)s(?<season>\\d+)");
    private static final Pattern TAIL_S = Pattern.compile(
        "(?i)(?<seasonSeparator>s|-|\\+|&|to|a|and|et|~)(?<season>\\d+)");
    private static final Pattern HEAD_CAP = Pattern.compile(
        "(?i)(?<seasonMarker>cap)[ ._-]?(?<season>\\d{1,2})(?<episode>\\d{2})");

    @Override public String name() { return "season"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        runChain(ctx, new Chain(HEAD_S_E).tail(TAIL_E, Chain.Repeater.STAR), input, true);
        runChain(ctx, new Chain(HEAD_NUM_X).tail(TAIL_NUM_X, Chain.Repeater.STAR), input, true);
        runChain(ctx, new Chain(HEAD_S).tail(TAIL_S, Chain.Repeater.STAR), input, false);
        runCap(ctx, input);
    }

    private void runChain(ParseContext ctx, Chain chain, String input, boolean withEpisode) {
        var seps = Validators.sepsSurround(input);
        for (var run : chain.scan(input)) {
            var headMatch = new Match("seasonHead", null, run.start(), run.end(),
                input.substring(run.start(), run.end()), 1000, Set.of("SxxExx"), false);
            if (!seps.test(headMatch)) continue;
            var seasonValues = run.captures("season");
            var episodeValues = run.captures("episode");
            var seasonSpans = run.spans("season");
            var episodeSpans = run.spans("episode");
            var episodeSeparators = run.captures("episodeSeparator");
            var episodeMarkers = run.captures("episodeMarker");
            if (!isAscending(seasonValues) || !isAscending(episodeValues)) continue;
            // Mirror Python rebulk's repeater('*'): when ordering_validator rejects the
            // full chain, fall back to the longest valid prefix of tail repeats.
            int validTails = longestValidTail(episodeValues, episodeSeparators);
            if (validTails < episodeValues.size() - 1) {
                int keep = validTails + 1;
                episodeValues = episodeValues.subList(0, keep);
                episodeSpans = episodeSpans.subList(0, keep);
                episodeSeparators = episodeSeparators.subList(0, Math.max(0, keep - 1));
            }
            boolean discRun = episodeMarkers.stream().anyMatch(s -> s.equalsIgnoreCase("d"))
                || episodeSeparators.stream().anyMatch(s -> s.equalsIgnoreCase("d"));
            for (int i = 0; i < seasonValues.size(); i++) {
                int[] sp = seasonSpans.get(i);
                ctx.matches.add(new Match("season", Integer.valueOf(seasonValues.get(i)),
                    sp[0], sp[1], input.substring(sp[0], sp[1]), 1000, Set.of("SxxExx", "coexist"), false));
            }
            if (withEpisode) {
                var tags = discRun
                    ? Set.of("SxxExx", "coexist", "disc-marker")
                    : Set.of("SxxExx", "coexist");
                for (int i = 0; i < episodeValues.size(); i++) {
                    int[] ep = episodeSpans.get(i);
                    ctx.matches.add(new Match("episode", Integer.valueOf(episodeValues.get(i)),
                        ep[0], ep[1], input.substring(ep[0], ep[1]), 1000, tags, false));
                }
            }
        }
    }

    /**
     * Returns the largest tail count whose ordering is valid per Python ordering_validator.
     * Weak discrete separators (".", "_", " ") require value gap ≤ MAX_RANGE_GAP+1; strong
     * discrete separators ("&", "+", "and", "et") short-circuit acceptance.
     */
    private static int longestValidTail(java.util.List<String> values, java.util.List<String> seps) {
        int validTails = 0;
        for (int i = 1; i < values.size() && i - 1 < seps.size(); i++) {
            String sep = seps.get(i - 1).toLowerCase(java.util.Locale.ROOT);
            int prev = Integer.parseInt(values.get(i - 1));
            int cur = Integer.parseInt(values.get(i));
            if (STRONG_SEPS.contains(sep)) return values.size() - 1;
            boolean isWeak = !RANGE_SEPS.contains(sep) && !MARKER_SEPS.contains(sep);
            if (isWeak) {
                int gap = cur - prev;
                if (gap <= 0 || gap > MAX_RANGE_GAP + 1) return validTails;
            }
            validTails = i;
        }
        return validTails;
    }

    private void runCap(ParseContext ctx, String input) {
        var seps = Validators.sepsSurround(input);
        var matcher = HEAD_CAP.matcher(input);
        while (matcher.find()) {
            var head = new Match("season", null, matcher.start(), matcher.end(),
                matcher.group(), 1000, Set.of("SxxExx", "see-pattern"), false);
            if (!seps.test(head)) continue;
            int sStart = matcher.start("season");
            int sEnd = matcher.end("season");
            int eStart = matcher.start("episode");
            int eEnd = matcher.end("episode");
            ctx.matches.add(new Match("season", Integer.parseInt(matcher.group("season")),
                sStart, sEnd, matcher.group("season"), 1000, Set.of("SxxExx", "coexist", "see-pattern"), false));
            ctx.matches.add(new Match("episode", Integer.parseInt(matcher.group("episode")),
                eStart, eEnd, matcher.group("episode"), 1000, Set.of("SxxExx", "coexist", "see-pattern"), false));
        }
    }

    private static boolean isAscending(java.util.List<String> values) {
        int prev = Integer.MIN_VALUE;
        for (var v : values) {
            int n = Integer.parseInt(v);
            if (n < prev) return false;
            prev = n;
        }
        return true;
    }

    @Override
    public void postProcess(ParseContext ctx) {
        expandRanges(ctx);
        removeInvalidSecondaryChain(ctx, "season");
        removeInvalidSecondaryChain(ctx, "episode");
    }

    private void expandRanges(ParseContext ctx) {
        var input = ctx.input;
        var episodes = ctx.matches.named("episode")
            .filter(m -> m.tags().contains("SxxExx"))
            .sorted(java.util.Comparator.comparingInt(Match::start))
            .toList();
        for (int i = 0; i + 1 < episodes.size(); i++) {
            var prev = episodes.get(i);
            var next = episodes.get(i + 1);
            if (next.start() <= prev.end() + 3) {
                var gap = input.substring(prev.end(), next.start());
                if (containsRange(gap)) {
                    boolean disc = prev.tags().contains("disc-marker") && next.tags().contains("disc-marker");
                    var fillTags = disc
                        ? Set.of("SxxExx", "coexist", "range-fill", "disc-marker")
                        : Set.of("SxxExx", "coexist", "range-fill");
                    int a = ((Integer) prev.value()) + 1;
                    int b = ((Integer) next.value()) - 1;
                    for (int v = a; v <= b; v++) {
                        ctx.matches.add(new Match("episode", v, prev.end(), next.start(),
                            String.valueOf(v), 1000, fillTags, false));
                    }
                }
            }
        }
    }

    private static boolean containsRange(String gap) {
        var lc = gap.toLowerCase(java.util.Locale.ROOT).strip();
        return lc.equals("-") || lc.equals("~") || lc.equals("to") || lc.equals("a");
    }

    private void removeInvalidSecondaryChain(ParseContext ctx, String prop) {
        var matches = ctx.matches.named(prop)
            .sorted(java.util.Comparator.comparingInt(Match::start)).toList();
        if (matches.size() <= 1) return;
        boolean strongSeen = matches.stream().anyMatch(m -> m.tags().contains("SxxExx"));
        if (!strongSeen) return;
        var toRemove = new ArrayList<Match>();
        for (var m : matches) {
            if (!m.tags().contains("SxxExx")) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
