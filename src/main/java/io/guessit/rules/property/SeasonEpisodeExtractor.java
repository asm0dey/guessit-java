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
    // Mirror python's `alt_dash` abbreviation: `@?` in source patterns expands
    // to `[seps_no_fs]?` (any separator except '/' and '\'). Apply only to the
    // head patterns so "s03-x01" parses as season=3+episode=1; tail keeps the
    // literal `@?` (effectively a no-op) to avoid linking a SxxExx head to
    // unrelated trailing digits across audio_channels/screen_size matches.
    private static final String SEP_OPT = "[ \\[\\](){}+*|=_~#.,;:\\-]?";
    private static final Pattern HEAD_S_E = Pattern.compile(
        "(?i)s(?<season>\\d+)" + SEP_OPT + "(?<episodeMarker>e|ex|xe|ep|x|d)" + SEP_OPT + "(?<episode>\\d+)");
    private static final Pattern TAIL_E = Pattern.compile(
        "(?i)(?<episodeSeparator>ex|xe|ep|and|et|to|e|x|d|\\.|_| |-|\\+|&|a|~)@?(?<episode>\\d{1,4})");
    private static final java.util.Set<String> STRONG_SEPS = Set.of("+", "&", "and", "et");
    private static final java.util.Set<String> RANGE_SEPS = Set.of("-", "~", "to", "a");
    private static final java.util.Set<String> MARKER_SEPS = Set.of("e", "ex", "xe", "ep", "x", "d", "s");
    private static final int MAX_RANGE_GAP = 1;
    private static final Pattern HEAD_NUM_X = Pattern.compile(
        "(?i)(?<season>\\d+)@?(?<episodeMarker>x)@?(?<episode>\\d+)");
    // Episode-only chain: matches "E01E02E03", "1e18", "e112" without an explicit
    // S<season> head. Optional season prefix captured but only emitted when present.
    private static final Pattern HEAD_E = Pattern.compile(
        "(?i)(?<season>\\d{1,2})?(?<episodeMarker>e)(?<episode>\\d{1,4})");
    private static final Pattern TAIL_E_ONLY = Pattern.compile(
            "(?i)(?<episodeSeparator>[ex\\-])(?<episode>\\d{1,4})");
    private static final Pattern HEAD_S = Pattern.compile(
        "(?i)s(?<season>\\d+)");
    private static final Pattern TAIL_S = Pattern.compile(
        "(?i)(?<seasonSeparator>s|and|et|to|a|[-+&~. _])(?<season>\\d+)");
    private static final Pattern HEAD_CAP = Pattern.compile(
        "(?i)(?<seasonMarker>cap)[ ._-]?(?<season>\\d{1,2})(?<episode>\\d{2})"
        + "(?:[_-](?<season2>\\d{1,2})(?<episode2>\\d{2}))?");
    public static final String SEASON = "season";
    public static final String SXX_EXX = "SxxExx";
    public static final String EPISODE = "episode";
    public static final String COEXIST = "coexist";
    public static final String SEE_PATTERN = "see-pattern";

    @Override public String name() { return SEASON; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        runChain(ctx, new Chain(HEAD_S_E).tail(TAIL_E, Chain.Repeater.STAR), input, true, false, false);
        runChain(ctx, new Chain(HEAD_NUM_X).tail(TAIL_E, Chain.Repeater.STAR), input, true, true, false);
        runChain(ctx, new Chain(HEAD_E).tail(TAIL_E_ONLY, Chain.Repeater.STAR), input, true, false, true);
        runChain(ctx, new Chain(HEAD_S).tail(TAIL_S, Chain.Repeater.STAR), input, false, false, false);
        runCap(ctx, input);
    }

    private void runChain(ParseContext ctx, Chain chain, String input, boolean withEpisode, boolean skipScreenSize, boolean isWeakEChain) {
        var seps = Validators.sepsSurround(input);
        var screenSizeSpans = skipScreenSize
            ? ctx.matches.named("screen_size").map(m -> new int[]{m.start(), m.end()}).toList()
            : List.<int[]>of();
        // The "e-only" chain (HEAD_E) is treated as a weak chain in Python — its
        // matches do not carry the SxxExx tag and therefore do not block weak-
        // episode promotion or trigger RemoveWeakIfSxxExx semantics. It also
        // skips runs that overlap an existing SxxExx span to avoid duplicating
        // episode matches already produced by HEAD_S_E / HEAD_NUM_X.
        var existingSxxExx = isWeakEChain
            ? ctx.matches.all()
                .filter(m -> m.tags().contains(SXX_EXX))
                .map(m -> new int[]{m.start(), m.end()})
                .toList()
            : List.<int[]>of();
        for (var run : chain.scan(input, this::effectiveRunEnd)) {
            if (skipScreenSize && overlapsAny(run.start(), run.end(), screenSizeSpans)) continue;
            if (isWeakEChain && overlapsAny(run.start(), run.end(), existingSxxExx)) continue;
            var seasonValues = run.captures(SEASON);
            var episodeValues = run.captures(EPISODE);
            var seasonSpans = run.spans(SEASON);
            var episodeSpans = run.spans(EPISODE);
            var episodeSeparators = run.captures("episodeSeparator");
            var episodeMarkers = run.captures("episodeMarker");
            var seasonSeparators = run.captures("seasonSeparator");
            // Note: descending values are pruned by longestValidTail below — let
            // it trim to the longest valid prefix instead of skipping the whole run.
            // Mirror Python rebulk's repeater('*'): when ordering_validator rejects the
            // full chain, fall back to the longest valid prefix of tail repeats.
            int validTails = longestValidTail(episodeValues, episodeSeparators);
            if (!episodeValues.isEmpty() && validTails < episodeValues.size() - 1) {
                int keep = validTails + 1;
                episodeValues = episodeValues.subList(0, keep);
                episodeSpans = episodeSpans.subList(0, keep);
                episodeSeparators = episodeSeparators.subList(0, Math.max(0, keep - 1));
            }
            int validSeasonTails = longestValidTail(seasonValues, seasonSeparators);
            if (!seasonValues.isEmpty() && validSeasonTails < seasonValues.size() - 1) {
                int keep = validSeasonTails + 1;
                seasonValues = seasonValues.subList(0, keep);
                seasonSpans = seasonSpans.subList(0, keep);
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
            String cg = withEpisode && !seasonValues.isEmpty() && !episodeValues.isEmpty()
                ? ctx.nextCoexistGroupTag() : null;
            for (int i = 0; i < seasonValues.size(); i++) {
                int[] sp = seasonSpans.get(i);
                var stags = cg != null ? Set.of(SXX_EXX, COEXIST, cg) : Set.of(SXX_EXX, COEXIST);
                ctx.matches.add(new Match(SEASON, Integer.valueOf(seasonValues.get(i)),
                    sp[0], sp[1], input.substring(sp[0], sp[1]), 1000, stags, false));
            }
            if (withEpisode) {
                Set<String> baseTags = cg != null ? Set.of(SXX_EXX, COEXIST, cg) : Set.of(SXX_EXX, COEXIST);
                Set<String> tags = discRun
                    ? java.util.stream.Stream.concat(baseTags.stream(), java.util.stream.Stream.of("disc-marker")).collect(java.util.stream.Collectors.toUnmodifiableSet())
                    : baseTags;
                for (int i = 0; i < episodeValues.size(); i++) {
                    int[] ep = episodeSpans.get(i);
                    ctx.matches.add(new Match(EPISODE, Integer.valueOf(episodeValues.get(i)),
                        ep[0], ep[1], input.substring(ep[0], ep[1]), 1000, tags, false));
                }
            }
        }
    }

    /**
     * Mirror of the validation done in {@link #runChain} but returning the
     * end position the next head search should resume from. When the longest
     * valid tail prefix is shorter than the captured run, return the position
     * just past the last valid tail; otherwise return -1 to let scan advance
     * past the full run cursor.
     */
    private int effectiveRunEnd(Chain.Run run) {
        var episodeValues = run.captures(EPISODE);
        var episodeSpans = run.spans(EPISODE);
        var episodeSeparators = run.captures("episodeSeparator");
        var seasonValues = run.captures(SEASON);
        var seasonSpans = run.spans(SEASON);
        var seasonSeparators = run.captures("seasonSeparator");
        int epKeep = episodeValues.isEmpty() ? 0
            : Math.min(longestValidTail(episodeValues, episodeSeparators) + 1, episodeValues.size());
        int seasonKeep = seasonValues.isEmpty() ? 0
            : Math.min(longestValidTail(seasonValues, seasonSeparators) + 1, seasonValues.size());
        int end;
        if (epKeep > 0) {
            end = episodeSpans.get(epKeep - 1)[1];
        } else if (seasonKeep > 0) {
            end = seasonSpans.get(seasonKeep - 1)[1];
        } else {
            return -1;
        }
        if (end >= run.end()) return -1;
        return end;
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
            } else if (RANGE_SEPS.contains(sep)) {
                // Range separator (-, ~, to, a) — reject huge value jumps that
                // would yield runaway range-fills (e.g. "s8e6-768660").
                int gap = cur - prev;
                if (gap <= 0 || gap > MAX_EXPAND_JUMP) return validTails;
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
                matcher.group(), 1000, Set.of(SXX_EXX, SEE_PATTERN), false);
            if (!seps.test(head)) continue;
            int sStart = matcher.start(SEASON);
            int sEnd = matcher.end(SEASON);
            int eStart = matcher.start(EPISODE);
            int eEnd = matcher.end(EPISODE);
            String cg = ctx.nextCoexistGroupTag();
            ctx.matches.add(new Match(SEASON, Integer.parseInt(matcher.group(SEASON)),
                sStart, sEnd, matcher.group(SEASON), 1000, Set.of(SXX_EXX, COEXIST, SEE_PATTERN, cg), false));
            ctx.matches.add(new Match(EPISODE, Integer.parseInt(matcher.group(EPISODE)),
                eStart, eEnd, matcher.group(EPISODE), 1000, Set.of(SXX_EXX, COEXIST, SEE_PATTERN, cg), false));
            if (matcher.group("season2") != null) {
                int e2Start = matcher.start("episode2");
                int e2End = matcher.end("episode2");
                int s1 = Integer.parseInt(matcher.group(SEASON));
                int s2 = Integer.parseInt(matcher.group("season2"));
                int e1 = Integer.parseInt(matcher.group(EPISODE));
                int e2 = Integer.parseInt(matcher.group("episode2"));
                ctx.matches.add(new Match(EPISODE, e2, e2Start, e2End,
                    matcher.group("episode2"), 1000, Set.of(SXX_EXX, COEXIST, SEE_PATTERN, cg), false));
                if (s2 == s1 && e2 > e1) {
                    for (int v = e1 + 1; v < e2; v++) {
                        ctx.matches.add(new Match(EPISODE, v, eEnd, e2Start,
                            String.valueOf(v), 1000,
                            Set.of(SXX_EXX, COEXIST, SEE_PATTERN, "range-fill", cg), false));
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

    @Override
    public void postProcess(ParseContext ctx) {
        dropTailOverCodec(ctx);
        expandRanges(ctx);
        removeInvalidSecondaryChain(ctx, SEASON);
        removeInvalidSecondaryChain(ctx, EPISODE);
    }

    /**
     * Drop SxxExx episode/season matches whose digit span overlaps a stronger
     * property (video_codec, audio_codec, source, screen_size, audio_channels).
     * Chain.scan tolerates a separator-class gap before the tail token, so
     * "S6.Ep5.X265" accidentally extends the chain into "X265" producing a
     * spurious episode=265 alongside the real video_codec=H.265. Drop the
     * tail match so the canonical codec stands alone. The first/leading
     * SxxExx pair (smallest start) is exempt — we only prune trailing tails.
     */
    private void dropTailOverCodec(ParseContext ctx) {
        var blockingNames = Set.of("video_codec", "audio_codec", "source",
                "screen_size", "audio_channels", "audio_profile", "video_profile");
        var blocking = ctx.matches.all()
                .filter(m -> blockingNames.contains(m.name()))
                .toList();
        if (blocking.isEmpty()) return;
        for (var name : new String[]{SEASON, EPISODE}) {
            var sxxExxList = ctx.matches.named(name)
                    .filter(m -> m.tags().contains(SXX_EXX))
                    .sorted(java.util.Comparator.comparingInt(Match::start))
                    .toList();
            if (sxxExxList.size() <= 1) continue;
            // Skip the first (head); only consider trailing tails.
            var toRemove = new ArrayList<Match>();
            for (int i = 1; i < sxxExxList.size(); i++) {
                var m = sxxExxList.get(i);
                for (var b : blocking) {
                    if (b.start() < m.end() && b.end() > m.start()) {
                        toRemove.add(m);
                        break;
                    }
                }
            }
            for (var m : toRemove) ctx.matches.remove(m);
        }
    }

    private static final int MAX_EXPAND_JUMP = 50;

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
                    int prevVal = (Integer) prev.value();
                    int nextVal = (Integer) next.value();
                    if (nextVal - prevVal > MAX_EXPAND_JUMP) continue;
                    boolean disc = prev.tags().contains("disc-marker") && next.tags().contains("disc-marker");
                    var fillTags = disc
                        ? Set.of(SXX_EXX, COEXIST, "range-fill", "disc-marker")
                        : Set.of(SXX_EXX, COEXIST, "range-fill");
                    for (int v = prevVal + 1; v < nextVal; v++) {
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
        // Latest SxxExx end — trailing weak matches (after this) MAY be absolute_episode
        // candidates. Exempt them only when no media property (screen_size, source,
        // codec, etc.) sits between the weak match and end of input — otherwise
        // they're title-bordering noise (e.g. "TEST.S01E10.24.1080p..."  where 24
        // is the episode_title, not an absolute episode).
        int strongMaxEnd = matches.stream()
            .filter(m -> m.tags().contains(SXX_EXX))
            .mapToInt(Match::end).max().orElse(Integer.MAX_VALUE);
        var mediaNames = Set.of("screen_size", "source", "video_codec",
            "audio_codec", "audio_channels", "audio_profile", "video_profile",
            "streaming_service");
        var mediaSpans = ctx.matches.all()
            .filter(m -> mediaNames.contains(m.name()))
            .toList();
        var toRemove = new ArrayList<Match>();
        for (var m : matches) {
            if (m.tags().contains(SXX_EXX)) continue;
            // weak-duplicate matches are paired (s+e from compact NNNN/NNN)
            // breaking the pair via this rule corrupts the pair logic in
            // WeakDuplicateExtractor.postProcess. Let that pass decide.
            if (m.tags().contains("weak-duplicate")) continue;
            boolean isWeak = m.tags().contains("weak-episode") || m.tags().contains("weak-duplicate");
            if (isWeak && m.start() >= strongMaxEnd) {
                final Match weak = m;
                boolean mediaAfter = mediaSpans.stream().anyMatch(media -> media.start() >= weak.end());
                if (!mediaAfter) continue;
            }
            toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
