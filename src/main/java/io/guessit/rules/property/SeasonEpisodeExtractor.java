package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts {@code season} and {@code episode} from compound forms:
 * {@code S01E02}, {@code 1x02}, {@code S01E02E03}, {@code S01-S03},
 * {@code Cap0102}, and the various separator/range expansions.
 *
 * <p>Implementation hinges on {@link Chain}: a head pattern matches the
 * first season+episode combo, then a star-repeated tail consumes any
 * adjacent additional episodes / seasons. Each emitted episode match is
 * tagged {@code "SxxExx"} so {@link AbsoluteEpisodeRule} can recognise the
 * canonical form and rule out leading numerics as episode candidates.
 *
 * <p>Range expansion ({@code S01E02-E04} → episodes [2, 3, 4]) and
 * separator-class classification ({@link #STRONG_SEPS} for additive,
 * {@link #RANGE_SEPS} for ranged) are kept in helper sets so the matching
 * code stays declarative.
 */
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
        "(?i)(?<seasonMarker>cap)[ ._-]?(?<season>\\d{1,2})(?<episode>\\d{2})"
        + "(?:[_-](?<season2>\\d{1,2})(?<episode2>\\d{2}))?");
    public static final String SEASON = "season";
    public static final String SXX_EXX = "SxxExx";
    public static final String EPISODE = "episode";
    public static final String COEXIST = "coexist";

    @Override public String name() { return SEASON; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        runChain(ctx, new Chain(HEAD_S_E).tail(TAIL_E, Chain.Repeater.STAR), input, true, false);
        runChain(ctx, new Chain(HEAD_NUM_X).tail(TAIL_E, Chain.Repeater.STAR), input, true, true);
        runChain(ctx, new Chain(HEAD_S).tail(TAIL_S, Chain.Repeater.STAR), input, false, false);
        runCap(ctx, input);
    }

    private void runChain(ParseContext ctx, Chain chain, String input, boolean withEpisode, boolean skipScreenSize) {
        var seps = Validators.sepsSurround(input);
        var screenSizeSpans = skipScreenSize
            ? ctx.matches.named("screen_size").map(m -> new int[]{m.start(), m.end()}).toList()
            : List.<int[]>of();
        for (var run : chain.scan(input)) {
            if (skipScreenSize && overlapsAny(run.start(), run.end(), screenSizeSpans)) continue;
            var seasonValues = run.captures(SEASON);
            var episodeValues = run.captures(EPISODE);
            var seasonSpans = run.spans(SEASON);
            var episodeSpans = run.spans(EPISODE);
            var episodeSeparators = run.captures("episodeSeparator");
            var episodeMarkers = run.captures("episodeMarker");
            if (isDescending(seasonValues) || isDescending(episodeValues)) continue;
            // Mirror Python rebulk's repeater('*'): when ordering_validator rejects the
            // full chain, fall back to the longest valid prefix of tail repeats.
            int validTails = longestValidTail(episodeValues, episodeSeparators);
            if (!episodeValues.isEmpty() && validTails < episodeValues.size() - 1) {
                int keep = validTails + 1;
                episodeValues = episodeValues.subList(0, keep);
                episodeSpans = episodeSpans.subList(0, keep);
                episodeSeparators = episodeSeparators.subList(0, Math.max(0, keep - 1));
            }
            int runEnd = run.end();
            if (!episodeSpans.isEmpty()) {
                runEnd = Math.min(runEnd, episodeSpans.getLast()[1]);
            } else if (!seasonSpans.isEmpty()) {
                runEnd = Math.min(runEnd, seasonSpans.getLast()[1]);
            }
            var headMatch = new Match("seasonHead", null, run.start(), runEnd,
                input.substring(run.start(), runEnd), 1000, Set.of(SXX_EXX), true);
            if (!seps.test(headMatch)) continue;
            ctx.matches.add(headMatch);
            boolean discRun = episodeMarkers.stream().anyMatch(s -> s.equalsIgnoreCase("d"))
                || episodeSeparators.stream().anyMatch(s -> s.equalsIgnoreCase("d"));
            for (int i = 0; i < seasonValues.size(); i++) {
                int[] sp = seasonSpans.get(i);
                ctx.matches.add(new Match(SEASON, Integer.valueOf(seasonValues.get(i)),
                    sp[0], sp[1], input.substring(sp[0], sp[1]), 1000, Set.of(SXX_EXX, COEXIST), false));
            }
            if (withEpisode) {
                var tags = discRun
                    ? Set.of(SXX_EXX, COEXIST, "disc-marker")
                    : Set.of(SXX_EXX, COEXIST);
                for (int i = 0; i < episodeValues.size(); i++) {
                    int[] ep = episodeSpans.get(i);
                    ctx.matches.add(new Match(EPISODE, Integer.valueOf(episodeValues.get(i)),
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
    private static int longestValidTail(List<String> values, List<String> seps) {
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
            var head = new Match(SEASON, null, matcher.start(), matcher.end(),
                matcher.group(), 1000, Set.of(SXX_EXX, "see-pattern"), false);
            if (!seps.test(head)) continue;
            int sStart = matcher.start(SEASON);
            int sEnd = matcher.end(SEASON);
            int eStart = matcher.start(EPISODE);
            int eEnd = matcher.end(EPISODE);
            ctx.matches.add(new Match(SEASON, Integer.parseInt(matcher.group(SEASON)),
                sStart, sEnd, matcher.group(SEASON), 1000, Set.of(SXX_EXX, COEXIST, "see-pattern"), false));
            ctx.matches.add(new Match(EPISODE, Integer.parseInt(matcher.group(EPISODE)),
                eStart, eEnd, matcher.group(EPISODE), 1000, Set.of(SXX_EXX, COEXIST, "see-pattern"), false));
            if (matcher.group("season2") != null) {
                int s2Start = matcher.start("season2");
                int s2End = matcher.end("season2");
                int e2Start = matcher.start("episode2");
                int e2End = matcher.end("episode2");
                int s1 = Integer.parseInt(matcher.group(SEASON));
                int s2 = Integer.parseInt(matcher.group("season2"));
                int e1 = Integer.parseInt(matcher.group(EPISODE));
                int e2 = Integer.parseInt(matcher.group("episode2"));
                ctx.matches.add(new Match(EPISODE, e2, e2Start, e2End,
                    matcher.group("episode2"), 1000, Set.of(SXX_EXX, COEXIST, "see-pattern"), false));
                if (s2 == s1 && e2 > e1) {
                    for (int v = e1 + 1; v < e2; v++) {
                        ctx.matches.add(new Match(EPISODE, v, eEnd, e2Start,
                            String.valueOf(v), 1000,
                            Set.of(SXX_EXX, COEXIST, "see-pattern", "range-fill"), false));
                    }
                }
            }
        }
    }

    private static boolean overlapsAny(int start, int end, java.util.List<int[]> spans) {
        for (var s : spans) {
            if (start < s[1] && end > s[0]) return true;
        }
        return false;
    }

    private static boolean isDescending(List<String> values) {
        int prev = Integer.MIN_VALUE;
        for (var v : values) {
            int n = Integer.parseInt(v);
            if (n < prev) return true;
            prev = n;
        }
        return false;
    }

    @Override
    public void postProcess(ParseContext ctx) {
        expandRanges(ctx);
        removeInvalidSecondaryChain(ctx, SEASON);
        removeInvalidSecondaryChain(ctx, EPISODE);
    }

    private void expandRanges(ParseContext ctx) {
        var input = ctx.input;
        var episodes = ctx.matches.named(EPISODE)
            .filter(m -> m.tags().contains(SXX_EXX))
            .sorted(java.util.Comparator.comparingInt(Match::start))
            .toList();
        for (int i = 0; i + 1 < episodes.size(); i++) {
            var prev = episodes.get(i);
            var next = episodes.get(i + 1);
            if (next.start() >= prev.end() && next.start() <= prev.end() + 3) {
                var gap = input.substring(prev.end(), next.start());
                if (containsRange(gap)) {
                    boolean disc = prev.tags().contains("disc-marker") && next.tags().contains("disc-marker");
                    var fillTags = disc
                        ? Set.of(SXX_EXX, COEXIST, "range-fill", "disc-marker")
                        : Set.of(SXX_EXX, COEXIST, "range-fill");
                    int a = ((Integer) prev.value()) + 1;
                    int b = ((Integer) next.value()) - 1;
                    for (int v = a; v <= b; v++) {
                        ctx.matches.add(new Match(EPISODE, v, prev.end(), next.start(),
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
        boolean strongSeen = matches.stream().anyMatch(m -> m.tags().contains(SXX_EXX));
        if (!strongSeen) return;
        var toRemove = new ArrayList<Match>();
        for (var m : matches) {
            if (!m.tags().contains(SXX_EXX)) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
