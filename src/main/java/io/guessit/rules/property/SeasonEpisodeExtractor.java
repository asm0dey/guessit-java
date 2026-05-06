package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static io.guessit.engine.MatchName.*;

/**
 * Extracts {@code season} and {@code episode} from compound forms:
 * {@code S01E02}, {@code 1x02}, {@code S01E02E03}, {@code S01-S03},
 * {@code Cap0102}, and the various separator/range expansions.
 *
 * <p>Implementation hinges on {@link Chain}: a head pattern matches the
 * first season+episode combo, then a star-repeated tail consumes any
 * adjacent additional episodes / seasons. Each emitted episode match is
 * tagged {@code "SxxExx"} so downstream rules
 * ({@link WeakEpisodeExtractor#postProcess},
 * {@link io.guessit.rules.post.AbsoluteEpisodePromoter}) can recognise the
 * canonical form and route leading/trailing numerics accordingly.
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
            "(?i)(?<season>\\d+) ?(?<episodeMarker>x) ?(?<episode>\\d+)");
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
    public static final String SXX_EXX = "SxxExx";
    public static final String COEXIST = "coexist";
    public static final String SEE_PATTERN = "see-pattern";
    private static final String SEASON_GROUP = "season";
    private static final String EPISODE_GROUP = "episode";
    private static final String EPISODE2_GROUP = "episode2";
    private static final String EXTRAS_GROUP = "extras";
    private static final String RANGE_FILL_TAG = "range-fill";
    private static final String DISC_MARKER_TAG = "disc-marker";
    private static final String WEAK_DUPLICATE_TAG = "weak-duplicate";
    private static final String WEAK_EPISODE_TAG = "weak-episode";

    @Override public String name() { return SEASON.name().toLowerCase(); }

    private static final Pattern S_EXTRAS = Pattern.compile(
        "(?i)s(?<season>\\d+)(?<extras>Extras?)");

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        runChain(ctx, new Chain(HEAD_S_E).tail(TAIL_E, Chain.Repeater.STAR), input, true, false, false);
        runChain(ctx, new Chain(HEAD_NUM_X).tail(TAIL_E, Chain.Repeater.STAR), input, true, true, false);
        runChain(ctx, new Chain(HEAD_E).tail(TAIL_E_ONLY, Chain.Repeater.STAR), input, true, false, true);
        runChain(ctx, new Chain(HEAD_S).tail(TAIL_S, Chain.Repeater.STAR), input, false, false, false);
        runSExtras(ctx, input);
        runCap(ctx, input);
        runSxxAll(ctx, input);
    }

    /**
     * "1xAll" / "S01EAll" form: digit + season-episode marker + "All".
     * Mirrors python's <code>S?(\d+)-?(?:xE|Ex|E|x)-?All</code> rule (line 262
     * of episodes.py) — yields season=N and other=Complete with the SxxExx
     * tag. Without this, "Something.1xAll-FlexGet" parses no season.
     */
    private static final Pattern SXX_ALL = Pattern.compile(
        "(?i)S?(?<season>\\d+)-?(?:xE|Ex|E|x)-?(?<all>All)");

    private void runSxxAll(ParseContext ctx, String input) {
        var seps = Validators.sepsSurround(input);
        var m = SXX_ALL.matcher(input);
        while (m.find()) {
            var head = new Match(MatchName.SEASON, null, m.start(), m.end(),
                m.group(), 1000, Set.of(SXX_EXX), false);
            if (!seps.test(head)) continue;
            String cg = ctx.nextCoexistGroupTag();
            int sStart = m.start(SEASON_GROUP);
            int sEnd = m.end(SEASON_GROUP);
            int aStart = m.start("all");
            int aEnd = m.end("all");
            ctx.matches.add(new Match(MatchName.SEASON, Integer.parseInt(m.group(SEASON_GROUP)),
                sStart, sEnd, m.group(SEASON_GROUP), 1000,
                Set.of(SXX_EXX, COEXIST, cg), false));
            ctx.matches.add(new Match(MatchName.OTHER, "Complete",
                aStart, aEnd, m.group("all"), 1000,
                Set.of(SXX_EXX, COEXIST, cg), false));
        }
    }

    /**
     * "S01Extras" form: emits season + other=Extras tied via a coexist
     * group tag. Mirrors python's chain at episodes.py line 209-214 where
     * "(season_markers)(season)(Extras)?" produces both fields.
     */
    private void runSExtras(ParseContext ctx, String input) {
        var seps = Validators.sepsSurround(input);
        var m = S_EXTRAS.matcher(input);
        while (m.find()) {
            var head = new Match(MatchName.SEASON, null, m.start(), m.end(),
                m.group(), 1000, Set.of(SXX_EXX), false);
            if (!seps.test(head)) continue;
            String cg = ctx.nextCoexistGroupTag();
            // Private head spans "S<digits>Extras?" so the leading "S" is
            // consumed and doesn't leak into the title hole.
            ctx.matches.add(new Match(MatchName.SEASON_HEAD, null, m.start(), m.end(),
                m.group(), 1000, Set.of(SXX_EXX), true));
            ctx.matches.add(new Match(MatchName.SEASON, Integer.parseInt(m.group(SEASON_GROUP)),
                m.start(SEASON_GROUP), m.end(SEASON_GROUP), m.group(SEASON_GROUP), 1000,
                Set.of(SXX_EXX, COEXIST, cg), false));
            ctx.matches.add(new Match(MatchName.OTHER, "Extras",
                m.start(EXTRAS_GROUP), m.end(EXTRAS_GROUP), m.group(EXTRAS_GROUP), 1000,
                Set.of(SXX_EXX, COEXIST, cg, "no-release-group-prefix"), false));
        }
    }

    private void runChain(ParseContext ctx, Chain chain, String input, boolean withEpisode, boolean skipScreenSize, boolean isWeakEChain) {
        var seps = Validators.sepsSurround(input);
        var screenSizeSpans = getScreenSizeSpans(ctx, skipScreenSize);
        var existingSxxExx = getExistingSxxExxSpans(ctx, isWeakEChain);

        for (var run : chain.scan(input, this::effectiveRunEnd)) {
            if (shouldSkipRun(run, skipScreenSize, screenSizeSpans, isWeakEChain, existingSxxExx)) continue;

            var trimmedRun = trimRunToValidTails(run);
            int runEnd = calculateRunEnd(run, trimmedRun.episodeSpans, trimmedRun.seasonSpans);

            var headMatch = new Match(MatchName.SEASON_HEAD, null, run.start(), runEnd,
                    input.substring(run.start(), runEnd), 1000, Set.of(SXX_EXX), true);
            if (!seps.test(headMatch)) continue;

            ctx.matches.add(headMatch);
            emitSeasonAndEpisodeMatches(ctx, input, withEpisode, trimmedRun);
        }
    }

    private List<int[]> getScreenSizeSpans(ParseContext ctx, boolean skipScreenSize) {
        return skipScreenSize
                ? ctx.matches.named(MatchName.SCREEN_SIZE).map(m -> new int[]{m.start(), m.end()}).toList()
                : List.of();
    }

    private List<int[]> getExistingSxxExxSpans(ParseContext ctx, boolean isWeakEChain) {
        return isWeakEChain
                ? ctx.matches.all()
                  .filter(m -> m.tags().contains(SXX_EXX))
                  .map(m -> new int[]{m.start(), m.end()})
                  .toList()
                : List.of();
    }

    private boolean shouldSkipRun(Chain.Run run, boolean skipScreenSize, List<int[]> screenSizeSpans,
                                  boolean isWeakEChain, List<int[]> existingSxxExx) {
        if (skipScreenSize && overlapsAny(run.start(), run.end(), screenSizeSpans)) return true;
        return isWeakEChain && overlapsAny(run.start(), run.end(), existingSxxExx);
    }

    private static class TrimmedRunData {
        List<String> seasonValues;
        List<String> episodeValues;
        List<int[]> seasonSpans;
        List<int[]> episodeSpans;
        List<String> episodeSeparators;
        List<String> episodeMarkers;

        TrimmedRunData(List<String> seasonValues, List<String> episodeValues,
                       List<int[]> seasonSpans, List<int[]> episodeSpans,
                       List<String> episodeSeparators, List<String> episodeMarkers) {
            this.seasonValues = seasonValues;
            this.episodeValues = episodeValues;
            this.seasonSpans = seasonSpans;
            this.episodeSpans = episodeSpans;
            this.episodeSeparators = episodeSeparators;
            this.episodeMarkers = episodeMarkers;
        }
    }

    private TrimmedRunData trimRunToValidTails(Chain.Run run) {
        var seasonValues = run.captures(SEASON_GROUP);
        var episodeValues = run.captures(EPISODE_GROUP);
        var seasonSpans = run.spans(SEASON_GROUP);
        var episodeSpans = run.spans(EPISODE_GROUP);
        var episodeSeparators = run.captures("episodeSeparator");
        var episodeMarkers = run.captures("episodeMarker");
        var seasonSeparators = run.captures("seasonSeparator");

        // Trim episode values to longest valid tail
        int validTails = longestValidTail(episodeValues, episodeSeparators);
        if (!episodeValues.isEmpty() && validTails < episodeValues.size() - 1) {
            int keep = validTails + 1;
            episodeValues = episodeValues.subList(0, keep);
            episodeSpans = episodeSpans.subList(0, keep);
            episodeSeparators = episodeSeparators.subList(0, Math.max(0, keep - 1));
        }

        // Trim season values to longest valid tail
        int validSeasonTails = longestValidTail(seasonValues, seasonSeparators);
        if (!seasonValues.isEmpty() && validSeasonTails < seasonValues.size() - 1) {
            int keep = validSeasonTails + 1;
            seasonValues = seasonValues.subList(0, keep);
            seasonSpans = seasonSpans.subList(0, keep);
        }

        return new TrimmedRunData(seasonValues, episodeValues, seasonSpans,
                episodeSpans, episodeSeparators, episodeMarkers);
    }

    private int calculateRunEnd(Chain.Run run, List<int[]> episodeSpans, List<int[]> seasonSpans) {
        int runEnd = run.end();
        if (!episodeSpans.isEmpty()) {
            runEnd = Math.min(runEnd, episodeSpans.getLast()[1]);
        } else if (!seasonSpans.isEmpty()) {
            runEnd = Math.min(runEnd, seasonSpans.getLast()[1]);
        }
        return runEnd;
    }

    private void emitSeasonAndEpisodeMatches(ParseContext ctx, String input, boolean withEpisode, TrimmedRunData data) {
        boolean discRun = data.episodeMarkers.stream().anyMatch(s -> s.equalsIgnoreCase("d"))
                || data.episodeSeparators.stream().anyMatch(s -> s.equalsIgnoreCase("d"));
        String cg = withEpisode && !data.seasonValues.isEmpty() && !data.episodeValues.isEmpty()
                ? ctx.nextCoexistGroupTag() : null;

        emitSeasonMatches(ctx, input, data.seasonValues, data.seasonSpans, cg);

        if (withEpisode) {
            emitEpisodeMatches(ctx, input, data.episodeValues, data.episodeSpans, cg, discRun);
        }
    }

    private void emitSeasonMatches(ParseContext ctx, String input, List<String> seasonValues,
                                   List<int[]> seasonSpans, String cg) {
        for (int i = 0; i < seasonValues.size(); i++) {
            int[] sp = seasonSpans.get(i);
            var stags = cg != null ? Set.of(SXX_EXX, COEXIST, cg) : Set.of(SXX_EXX, COEXIST);
            ctx.matches.add(new Match(MatchName.SEASON, Integer.valueOf(seasonValues.get(i)),
                    sp[0], sp[1], input.substring(sp[0], sp[1]), 1000, stags, false));
        }
    }

    private void emitEpisodeMatches(ParseContext ctx, String input, List<String> episodeValues,
                                    List<int[]> episodeSpans, String cg, boolean discRun) {
        Set<String> baseTags = cg != null ? Set.of(SXX_EXX, COEXIST, cg) : Set.of(SXX_EXX, COEXIST);
        Set<String> tags = discRun
            ? java.util.stream.Stream.concat(baseTags.stream(), java.util.stream.Stream.of(DISC_MARKER_TAG))
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
            : baseTags;
    
        for (int i = 0; i < episodeValues.size(); i++) {
            int[] ep = episodeSpans.get(i);
            ctx.matches.add(new Match(MatchName.EPISODE, Integer.valueOf(episodeValues.get(i)),
                ep[0], ep[1], input.substring(ep[0], ep[1]), 1000, tags, false));
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
        var episodeValues = run.captures(EPISODE_GROUP);
        var episodeSpans = run.spans(EPISODE_GROUP);
        var episodeSeparators = run.captures("episodeSeparator");
        var seasonValues = run.captures(SEASON_GROUP);
        var seasonSpans = run.spans(SEASON_GROUP);
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
            var head = new Match(MatchName.SEASON, null, matcher.start(), matcher.end(),
                matcher.group(), 1000, Set.of(SXX_EXX, SEE_PATTERN), false);
            if (!seps.test(head)) continue;
            int sStart = matcher.start(SEASON_GROUP);
            int sEnd = matcher.end(SEASON_GROUP);
            int eStart = matcher.start(EPISODE_GROUP);
            int eEnd = matcher.end(EPISODE_GROUP);
            String cg = ctx.nextCoexistGroupTag();
            ctx.matches.add(new Match(MatchName.SEASON, Integer.parseInt(matcher.group(SEASON_GROUP)),
                sStart, sEnd, matcher.group(SEASON_GROUP), 1000, Set.of(SXX_EXX, COEXIST, SEE_PATTERN, cg), false));
            ctx.matches.add(new Match(MatchName.EPISODE, Integer.parseInt(matcher.group(EPISODE_GROUP)),
                eStart, eEnd, matcher.group(EPISODE_GROUP), 1000, Set.of(SXX_EXX, COEXIST, SEE_PATTERN, cg), false));
            if (matcher.group("season2") != null) {
                int e2Start = matcher.start(EPISODE2_GROUP);
                int e2End = matcher.end(EPISODE2_GROUP);
                int s1 = Integer.parseInt(matcher.group(SEASON_GROUP));
                int s2 = Integer.parseInt(matcher.group("season2"));
                int e1 = Integer.parseInt(matcher.group(EPISODE_GROUP));
                int e2 = Integer.parseInt(matcher.group(EPISODE2_GROUP));
                ctx.matches.add(new Match(MatchName.EPISODE, e2, e2Start, e2End,
                    matcher.group(EPISODE2_GROUP), 1000, Set.of(SXX_EXX, COEXIST, SEE_PATTERN, cg), false));
                if (s2 == s1 && e2 > e1) {
                    for (int v = e1 + 1; v < e2; v++) {
                        ctx.matches.add(new Match(MatchName.EPISODE, v, eEnd, e2Start,
                            String.valueOf(v), 1000,
                            Set.of(SXX_EXX, COEXIST, SEE_PATTERN, RANGE_FILL_TAG, cg), false));
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
        removeInvalidSecondaryChain(ctx, MatchName.SEASON);
        removeInvalidSecondaryChain(ctx, MatchName.EPISODE);
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
        var blockingNames = Set.of(MatchName.VIDEO_CODEC, MatchName.AUDIO_CODEC, MatchName.SOURCE,
                MatchName.SCREEN_SIZE, MatchName.AUDIO_CHANNELS, MatchName.AUDIO_PROFILE, MatchName.VIDEO_PROFILE);
        var blocking = ctx.matches.all()
                .filter(m -> blockingNames.contains(m.name()))
                .toList();
        if (blocking.isEmpty()) return;
        for (var name : new MatchName[]{MatchName.SEASON, MatchName.EPISODE}) {
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
        var episodes = ctx.matches.named(MatchName.EPISODE)
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
                    boolean disc = prev.tags().contains(DISC_MARKER_TAG) && next.tags().contains(DISC_MARKER_TAG);
                    var fillTags = disc
                        ? Set.of(SXX_EXX, COEXIST, RANGE_FILL_TAG, DISC_MARKER_TAG)
                        : Set.of(SXX_EXX, COEXIST, RANGE_FILL_TAG);
                    for (int v = prevVal + 1; v < nextVal; v++) {
                        ctx.matches.add(new Match(MatchName.EPISODE, v, prev.end(), next.start(),
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

    private void removeInvalidSecondaryChain(ParseContext ctx, MatchName prop) {
        var matches = ctx.matches.named(prop)
                .sorted(java.util.Comparator.comparingInt(Match::start)).toList();
        if (matches.size() <= 1) return;

        boolean strongSeen = matches.stream().anyMatch(m -> m.tags().contains(SXX_EXX));
        if (!strongSeen) return;

        int strongMaxEnd = calculateStrongMaxEnd(matches);
        var mediaSpans = collectMediaSpans(ctx);
        var toRemove = new ArrayList<Match>();

        for (var m : matches) {
            if (shouldRemoveMatch(m, matches, strongMaxEnd, mediaSpans, ctx.input)) {
                toRemove.add(m);
            }
        }

        for (var m : toRemove) ctx.matches.remove(m);
    }

    private int calculateStrongMaxEnd(List<Match> matches) {
        return matches.stream()
                .filter(m -> m.tags().contains(SXX_EXX))
                .mapToInt(Match::end)
                .max()
                .orElse(Integer.MAX_VALUE);
    }

    private List<Match> collectMediaSpans(ParseContext ctx) {
        var mediaNames = Set.of(MatchName.SCREEN_SIZE, MatchName.SOURCE, MatchName.VIDEO_CODEC,
                MatchName.AUDIO_CODEC, MatchName.AUDIO_CHANNELS, MatchName.AUDIO_PROFILE, MatchName.VIDEO_PROFILE,
                MatchName.STREAMING_SERVICE);
        return ctx.matches.all()
                .filter(m -> mediaNames.contains(m.name()))
                .toList();
    }

    private boolean shouldRemoveMatch(Match m, List<Match> allMatches,
                                      int strongMaxEnd, List<Match> mediaSpans, String input) {
        if (m.tags().contains(SXX_EXX)) return false;
        if (m.tags().contains(WEAK_DUPLICATE_TAG)) return false;

        boolean isWeak = m.tags().contains(WEAK_EPISODE_TAG) || m.tags().contains(WEAK_DUPLICATE_TAG);

        if (isWeak && m.start() >= strongMaxEnd && !hasMediaAfter(m, mediaSpans)) return false;


        return !isHighValueRangePaired(m, allMatches, input);
    }

    private boolean hasMediaAfter(Match weak, List<Match> mediaSpans) {
        return mediaSpans.stream().anyMatch(media -> media.start() >= weak.end());
    }

    private boolean isHighValueRangePaired(Match m, List<Match> matches, String input) {
        boolean isWeak = m.tags().contains(WEAK_EPISODE_TAG) || m.tags().contains(WEAK_DUPLICATE_TAG);
        if (!isWeak) return false;
        if (!(m.value() instanceof Integer iv) || iv < 100) return false;

        return matches.stream().anyMatch(other -> isValidRangePair(m, other, input));
    }

    private boolean isValidRangePair(Match cur, Match other, String input) {
        if (other == cur) return false;
        if (!other.tags().contains(WEAK_EPISODE_TAG)) return false;
        if (other.tags().contains(WEAK_DUPLICATE_TAG)) return false;
        if (!(other.value() instanceof Integer ov) || ov < 100) return false;

        int gapStart = Math.min(cur.end(), other.end());
        int gapEnd = Math.max(cur.start(), other.start());
        if (gapEnd <= gapStart) return false;
        if (gapEnd - gapStart > 5) return false;
    
        String gap = input.substring(gapStart, gapEnd);
        return gap.matches("[ ._]*[-~][ ._]*");
    }
}
