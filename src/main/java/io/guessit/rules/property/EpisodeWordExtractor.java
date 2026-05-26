package io.guessit.rules.property;

import io.guessit.engine.*;
import com.mirkoddd.sift.core.NamedCapture;
import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.numerals.Numerals;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.oneOrMore;
import static com.mirkoddd.sift.core.Sift.zeroOrMore;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;
import static com.mirkoddd.sift.core.SiftPatterns.capture;
import static com.mirkoddd.sift.core.SiftPatterns.literal;
import static com.mirkoddd.sift.core.SiftPatterns.withFlags;

/**
 * Extracts {@code season}, {@code episode}, and {@code episode_count} from
 * word forms: "Episode 5", "Season 2", "Ep 12 of 24", "Saison 3", "Capitulo 7".
 */
public final class EpisodeWordExtractor implements Extractor {

    public static final String EPISODE = "episode";
    public static final String SEASON = "season";

    private static final String TYPE_MOVIE = "movie";
    private static final String TAG_SEASON_WORD = "season-word";
    private static final String TAG_EPISODE_WORD = "episode-word";

    private static final String GRP_COUNT = "count";
    private static final String GRP_SEASON_WORD = "seasonWord";
    private static final String GRP_SEASON_VAL = "seasonVal";
    private static final String GRP_OP = "op";
    private static final String GRP_VAL = "val";
    private static final String GRP_EP_WORD = "epWord";
    private static final String GRP_EP_VAL = "epVal";
    private static final String GRP_VERSION = "version";

    private static final int MAX_RANGE_GAP = 1;

    private static final List<String> EPISODE_WORDS = List.of(EPISODE, "episodes", "ep", "eps", "episodio", "episodios", "capitulo", "capitulos", "part", "parts", "ch", "chapter", "chapters", "e");
    private static final Set<String> SHORT_EPISODE_WORDS = Set.of("e");
    private static final List<String> SEASON_WORDS = List.of(SEASON, "seasons", "saison", "saisons", "seizoen", "temp", "temporada", "temporadas", "staffel", "staffeln", "stagione", "stagioni");
    private static final List<String> OF_WORDS = List.of("of", "sur", "de");

    private static final Set<MatchName> BLOCKED_MATCH_TYPES = EnumSet.of(
            MatchName.SCREEN_SIZE, MatchName.YEAR, MatchName.SOURCE,
            MatchName.VIDEO_CODEC, MatchName.AUDIO_CODEC,
            MatchName.VIDEO_PROFILE, MatchName.FRAME_RATE
    );

    private static final SiftPattern<Fragment> SEP_CHAR = anyOf(literal(" "), literal("."), literal("_"), literal("-"));
    private static final SiftPattern<Fragment> SEP_OPT = zeroOrMore().of(SEP_CHAR);
    private static final SiftPattern<Fragment> SEP_REQ = oneOrMore().of(SEP_CHAR);

    private static final NamedCapture COUNT_GROUP = capture(GRP_COUNT, oneOrMore().digits());
    private static final SiftPattern<Fragment> OF_CLAUSE = Sift.fromAnywhere()
            .of(SEP_OPT)
            .followedBy(List.of(buildOrPattern(OF_WORDS), SEP_OPT))
            .then().namedCapture(COUNT_GROUP);

    private static final NamedCapture SEASON_WORD_GROUP = capture(GRP_SEASON_WORD, buildOrPattern(SEASON_WORDS));
    private static final NamedCapture SEASON_VAL_GROUP = capture(GRP_SEASON_VAL, Numerals.NUMERAL_PATTERN);

    private static final Pattern SEASON_RE = Pattern.compile(
            withFlags(Sift.fromWordBoundary()
                    .then().namedCapture(SEASON_WORD_GROUP)
                    .then().of(SEP_OPT)
                    .then().namedCapture(SEASON_VAL_GROUP)
                    .then().optional().of(OF_CLAUSE), SiftGlobalFlag.CASE_INSENSITIVE).shake()
    );

    private static final SiftPattern<Fragment> TAIL_SEP_CHAR = anyOf(literal(" "), literal("."), literal("_"));
    private static final SiftPattern<Fragment> TAIL_SEP_OPT = Sift.fromAnywhere().zeroOrMore().of(TAIL_SEP_CHAR);
    private static final SiftPattern<Fragment> TAIL_OPS = anyOf(
            literal("and"), literal("et"), literal("to"), literal("a"),
            literal("-"), literal("~"), literal("&"), literal("+")
    );
    private static final SiftPattern<Fragment> TAIL_OP_BLOCK = Sift.fromAnywhere()
            .of(TAIL_SEP_OPT).then().of(TAIL_OPS).then().of(TAIL_SEP_OPT);

    private static final NamedCapture TAIL_OP_GROUP = capture(GRP_OP, anyOf(TAIL_OP_BLOCK, SEP_REQ));
    private static final NamedCapture TAIL_VAL_GROUP = capture(GRP_VAL, Sift.fromAnywhere().oneOrMore().digits());

    private static final Pattern SEASON_TAIL_RE = Pattern.compile(
            withFlags(Sift.fromAnywhere()
                    .namedCapture(TAIL_OP_GROUP)
                    .then().namedCapture(TAIL_VAL_GROUP), SiftGlobalFlag.CASE_INSENSITIVE).shake()
    );

    private static final Pattern AFTER_OF_RE = Pattern.compile(
            Sift.filteringWith(SiftGlobalFlag.CASE_INSENSITIVE).fromStart()
                    .of(SEP_OPT).then().of(buildOrPattern(OF_WORDS)).then().of(SEP_OPT).then().oneOrMore().digits()
                    .shake()
    );

    private static final NamedCapture EP_WORD_GROUP = capture(GRP_EP_WORD, buildOrPattern(EPISODE_WORDS));
    private static final NamedCapture EP_VAL_GROUP = capture(GRP_EP_VAL, Numerals.NUMERAL_PATTERN);
    private static final NamedCapture EP_VAL_DIGITS_GROUP = capture(GRP_EP_VAL, Sift.fromAnywhere().oneOrMore().digits());
    private static final NamedCapture VERSION_GROUP = capture(GRP_VERSION, Sift.fromAnywhere().oneOrMore().digits());

    private static final SiftPattern<Fragment> VERSION_CLAUSE = Sift.fromAnywhere()
            .character('v').then().namedCapture(VERSION_GROUP);

    private static final SiftPattern<Fragment> EP_BASE = Sift.fromAnywhere()
            .namedCapture(EP_WORD_GROUP)
            .notPrecededBy(Sift.fromAnywhere().exactly(1).alphanumeric())
            .then().of(SEP_OPT);

    private static final Pattern EP_RE_EPISODE_TYPE = Pattern.compile(
            withFlags(Sift.fromAnywhere()
                    .of(EP_BASE)
                    .then().namedCapture(EP_VAL_GROUP)
                    .then().optional().of(VERSION_CLAUSE)
                    .then().optional().of(OF_CLAUSE), SiftGlobalFlag.CASE_INSENSITIVE).shake()
    );

    private static final Pattern EP_RE_DEFAULT = Pattern.compile(
            withFlags(Sift.fromAnywhere()
                    .of(EP_BASE)
                    .then().namedCapture(EP_VAL_DIGITS_GROUP)
                    .then().optional().of(VERSION_CLAUSE)
                    .then().optional().of(OF_CLAUSE), SiftGlobalFlag.CASE_INSENSITIVE).shake()
    );

    private static final NamedCapture DETACHED_EP_GROUP = capture(GRP_EP_VAL, Sift.fromAnywhere().oneOrMore().digits());

    private static final Pattern DETACHED_EP_COUNT_RE = Pattern.compile(
            withFlags(Sift.fromAnywhere()
                            .namedCapture(DETACHED_EP_GROUP)
                            .then().of(SEP_OPT)
                            .then().of(buildOrPattern(OF_WORDS))
                            .then().of(SEP_OPT)
                            .then().namedCapture(COUNT_GROUP)
                            .then().optional().of(Sift.fromAnywhere().of(SEP_OPT).then().of(buildOrPattern(EPISODE_WORDS))),
                    SiftGlobalFlag.CASE_INSENSITIVE).shake()
    );

    private static final Set<String> STRONG_OPS = Set.of("&", "+", "and", "et");
    private static final Set<String> RANGE_OPS = Set.of("-", "~", "to", "a");

    private static String trimOpJunk(String s) {
        int start = 0;
        int end = s.length() - 1;

        while (start <= end && isOpJunk(s.charAt(start))) start++;
        while (end >= start && isOpJunk(s.charAt(end))) end--;

        return (start > 0 || end < s.length() - 1) ? s.substring(start, end + 1) : s;
    }

    private static boolean isOpJunk(char c) {
        return c == '.' || c == ' ' || c == '_';
    }

    @Override
    public String name() {
        return "episode_word";
    }

    @Override
    public String description() {
        return "weak episode word (E12, EP12, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        if (TYPE_MOVIE.equals(ctx.options.type())) return;

        extractSeasonMatches(ctx);
        extractEpisodeMatches(ctx);
        extractDetachedEpisodeCount(ctx);
    }

    private void extractSeasonMatches(ParseContext ctx) {
        var input = ctx.input;
        var seasonHeadValidator = createSeasonHeadValidator(input);
        var seasonMatcher = SEASON_RE.matcher(input);

        while (seasonMatcher.find()) {
            processSeasonMatch(ctx, seasonMatcher, seasonHeadValidator);
        }
    }

    private Predicate<Match> createSeasonHeadValidator(String input) {
        var sepsBefore = Validators.sepsBefore(input);
        var sepsAfter = Validators.sepsAfter(input);
        return m -> {
            if (!sepsBefore.test(m)) return false;
            if (sepsAfter.test(m)) return true;
            int e = m.end();
            if (e >= input.length()) return true;
            char c = input.charAt(e);
            return c == '&' || c == '+' || c == '~';
        };
    }

    private void processSeasonMatch(ParseContext ctx, Matcher seasonMatcher,
                                    Predicate<Match> seasonHeadValidator) {
        var raw = seasonMatcher.group();
        var headMatch = new Match(MatchName.SEASON, null, seasonMatcher.start(), seasonMatcher.end(), raw, 1000, Set.of(), true);
        if (!seasonHeadValidator.test(headMatch)) return;

        ctx.matches.add(headMatch);

        int n = addSeasonValue(ctx, seasonMatcher);
        if (n < 0) return;

        addSeasonCount(ctx, seasonMatcher);
        processSeasonTail(ctx, seasonMatcher, n);
    }

    private int addSeasonValue(ParseContext ctx, Matcher seasonMatcher) {
        int valStart = seasonMatcher.start(GRP_SEASON_VAL);
        int valEnd = seasonMatcher.end(GRP_SEASON_VAL);
        int n = parseSafe(seasonMatcher.group(GRP_SEASON_VAL));
        if (n < 0) return -1;

        ctx.matches.add(new Match(MatchName.SEASON, n, valStart, valEnd,
                ctx.input.substring(valStart, valEnd), 1000, Set.of(TAG_SEASON_WORD), false));
        return n;
    }

    private void addSeasonCount(ParseContext ctx, Matcher seasonMatcher) {
        if (seasonMatcher.group(GRP_COUNT) == null) return;

        int countStart = seasonMatcher.start(GRP_COUNT);
        int countEnd = seasonMatcher.end(GRP_COUNT);
        int c = parseSafe(seasonMatcher.group(GRP_COUNT));
        if (c >= 0) {
            ctx.matches.add(new Match(MatchName.SEASON_COUNT, c, countStart, countEnd,
                    seasonMatcher.group(GRP_COUNT), 1000, Set.of(), false));
        }
    }

    private void processSeasonTail(ParseContext ctx, Matcher seasonMatcher, int firstSeasonValue) {
        var input = ctx.input;
        var blockSpans = getBlockedSpans(ctx);
        var tail = SEASON_TAIL_RE.matcher(input);

        int prevVal = firstSeasonValue;

        tail.region(seasonMatcher.end(), input.length());

        while (tail.lookingAt()) {
            var tailResult = processSeasonTailMatch(ctx, tail, prevVal, blockSpans);

            if (!tailResult.isValid()) {
                return;
            }

            prevVal = tailResult.value();
            tail.region(tail.end(), input.length());
        }
    }

    private List<int[]> getBlockedSpans(ParseContext ctx) {
        return ctx.matches.all()
                .filter(m -> isBlockedMatchType(m.name()))
                .map(m -> new int[]{m.start(), m.end()})
                .toList();
    }

    private boolean isBlockedMatchType(MatchName name) {
        return BLOCKED_MATCH_TYPES.contains(name);
    }

    private record TailResult(boolean isValid, int value) {
    }

    private TailResult processSeasonTailMatch(ParseContext ctx, Matcher tail,
                                              int prevVal, List<int[]> blockSpans) {

        String sepToken = tail.group(GRP_OP).strip().toLowerCase(java.util.Locale.ROOT);

        String op = trimOpJunk(sepToken);

        boolean strong = STRONG_OPS.contains(op);
        boolean range = RANGE_OPS.contains(op);

        int v = parseSafe(tail.group(GRP_VAL));

        if (v < 0 || v <= prevVal) return new TailResult(false, prevVal);

        int tStart = tail.start(GRP_VAL);
        int tEnd = tail.end(GRP_VAL);

        if (blockSpans.stream().anyMatch(sp -> sp[0] < tEnd && tStart < sp[1])) return new TailResult(false, prevVal);
        if (!strong && !range && v - prevVal > MAX_RANGE_GAP + 1) return new TailResult(false, prevVal);
        if (isFollowedByOfClause(ctx.input, tEnd)) return new TailResult(false, prevVal);

        if (range) {
            addRangeSeasons(ctx, prevVal, v, tStart);
        }

        ctx.matches.add(new Match(MatchName.SEASON, v, tStart, tEnd,
                ctx.input.substring(tStart, tEnd), 1000, Set.of(TAG_SEASON_WORD), false));

        return new TailResult(true, v);
    }

    private boolean isFollowedByOfClause(String input, int position) {
        return AFTER_OF_RE.matcher(input).region(position, input.length()).lookingAt();
    }

    private void addRangeSeasons(ParseContext ctx, int prevVal, int v, int tStart) {
        for (int x = prevVal + 1; x < v; x++) {
            ctx.matches.add(new Match(MatchName.SEASON, x, tStart, tStart, "",
                    1000, Set.of(TAG_SEASON_WORD), false));
        }
    }

    private void extractEpisodeMatches(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        boolean episodeType = MatchName.EPISODE.name().equalsIgnoreCase(ctx.options.type());

        var epMatcher = (episodeType ? EP_RE_EPISODE_TYPE : EP_RE_DEFAULT).matcher(input);

        while (epMatcher.find()) {
            processEpisodeMatch(ctx, epMatcher, seps, episodeType);
        }
    }

    private void processEpisodeMatch(ParseContext ctx, Matcher epMatcher,
                                     Predicate<Match> seps, boolean episodeType) {
        var raw = epMatcher.group();
        var headMatch = new Match(MatchName.EPISODE, null, epMatcher.start(), epMatcher.end(), raw, 1000, Set.of(), true);

        if (!seps.test(headMatch)) {
            handleInvalidEpisodeHead(ctx, epMatcher);
            return;
        }

        if (!validateShortEpisodeMarker(epMatcher)) return;

        Integer ep = parseEpisodeNumber(epMatcher, seps, episodeType);
        if (ep == null) return;

        addEpisodeMatches(ctx, epMatcher, headMatch, ep);
    }

    private void handleInvalidEpisodeHead(ParseContext ctx, Matcher epMatcher) {
        int markerStart = epMatcher.start(GRP_EP_WORD);
        int markerEnd = epMatcher.end(GRP_EP_WORD);
        String mw = epMatcher.group(GRP_EP_WORD);

        if (mw != null && !SHORT_EPISODE_WORDS.contains(mw.toLowerCase())
                && markerEnd < ctx.input.length()
                && Seps.isSep(ctx.input.charAt(markerEnd))) {
            ctx.matches.add(new Match(MatchName.EPISODE_WORD_MARKER, null,
                    markerStart, markerEnd, mw, 1000, Set.of(), true));
        }
    }

    private boolean validateShortEpisodeMarker(Matcher epMatcher) {
        String marker = epMatcher.group(GRP_EP_WORD);
        if (marker != null && SHORT_EPISODE_WORDS.contains(marker.toLowerCase())) {
            int afterMarker = epMatcher.start(GRP_EP_WORD) + marker.length();
            return afterMarker == epMatcher.start(GRP_EP_VAL);
        }
        return true;
    }

    private Integer parseEpisodeNumber(Matcher epMatcher,
                                       Predicate<Match> seps, boolean episodeType) {
        int epStart = epMatcher.start(GRP_EP_VAL);
        int epEnd = epMatcher.end(GRP_EP_VAL);
        String epToken = epMatcher.group(GRP_EP_VAL);

        if (episodeType) {
            int ep = parseSafe(epToken);
            if (ep < 0) return null;

            if (!isPureDigits(epToken)) {
                var token = new Match(MatchName.EPISODE, ep, epStart, epEnd, epToken, 1000, Set.of(), false);
                if (!seps.test(token)) return null;
            }
            return ep;
        } else {
            return Integer.parseInt(epToken);
        }
    }

    private void addEpisodeMatches(ParseContext ctx, Matcher epMatcher, Match headMatch, int ep) {
        ctx.matches.add(headMatch);

        int epStart = epMatcher.start(GRP_EP_VAL);
        int epEnd = epMatcher.end(GRP_EP_VAL);
        ctx.matches.add(new Match(MatchName.EPISODE, ep, epStart, epEnd,
                ctx.input.substring(epStart, epEnd), 1000, Set.of(TAG_EPISODE_WORD), false));

        addEpisodeVersion(ctx, epMatcher);
        addEpisodeCountFromMatch(ctx, epMatcher);
    }

    private void addEpisodeVersion(ParseContext ctx, Matcher epMatcher) {
        if (epMatcher.group(GRP_VERSION) != null) {
            int v = Integer.parseInt(epMatcher.group(GRP_VERSION));
            ctx.matches.add(new Match(MatchName.VERSION, v, epMatcher.start(GRP_VERSION), epMatcher.end(GRP_VERSION),
                    epMatcher.group(GRP_VERSION), 1000, Set.of(), false));
        }
    }

    private void addEpisodeCountFromMatch(ParseContext ctx, Matcher epMatcher) {
        if (epMatcher.group(GRP_COUNT) != null) {
            int c = Integer.parseInt(epMatcher.group(GRP_COUNT));
            ctx.matches.add(new Match(MatchName.EPISODE_COUNT, c, epMatcher.start(GRP_COUNT), epMatcher.end(GRP_COUNT),
                    epMatcher.group(GRP_COUNT), 1000, Set.of(), false));
        }
    }

    private void extractDetachedEpisodeCount(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var dm = DETACHED_EP_COUNT_RE.matcher(input);

        while (dm.find()) {
            processDetachedMatch(ctx, dm, seps);
        }
    }

    private void processDetachedMatch(ParseContext ctx, Matcher dm,
                                      Predicate<Match> seps) {
        var raw = dm.group();
        var headMatch = new Match(MatchName.EPISODE, null, dm.start(), dm.end(), raw, 1000, Set.of(), false);
        if (!seps.test(headMatch)) return;

        int dStart = dm.start(GRP_EP_VAL);
        int dEnd = dm.end(GRP_EP_VAL);
        boolean overlapsSeason = ctx.matches.range(dStart, dEnd,
                m -> m.name() == MatchName.SEASON && m.value() != null).findAny().isPresent();
        if (overlapsSeason) return;

        int e = Integer.parseInt(dm.group(GRP_EP_VAL));
        int c = Integer.parseInt(dm.group(GRP_COUNT));

        ctx.matches.add(new Match(MatchName.EPISODE, e, dm.start(GRP_EP_VAL), dm.end(GRP_EP_VAL),
                dm.group(GRP_EP_VAL), 1000, Set.of(TAG_EPISODE_WORD), false));
        ctx.matches.add(new Match(MatchName.EPISODE_COUNT, c, dm.start(GRP_COUNT), dm.end(GRP_COUNT),
                dm.group(GRP_COUNT), 1000, Set.of(), false));
        ctx.matches.add(new Match(MatchName.EP_COUNT_SPAN, null, dm.start(), dm.end(),
                raw, 1000, Set.of(), true));
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var heads = ctx.matches.all()
                .filter(m -> (m.name() == MatchName.SEASON || m.name() == MatchName.EPISODE)
                        && m.value() == null && m.isPrivate())
                .toList();
        for (var head : heads) {
            boolean hasValue = ctx.matches.range(head.start(), head.end(),
                    m -> m.name() == head.name() && m.value() != null).findAny().isPresent();
            if (!hasValue) ctx.matches.remove(head);
        }
    }

    private static int parseSafe(String token) {
        try {
            return Numerals.parse(token);
        } catch (IllegalArgumentException _) {
            return -1;
        }
    }

    private static boolean isPureDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.chars().allMatch(Character::isDigit);
    }

    private static SiftPattern<Fragment> buildOrPattern(List<String> words) {
        return anyOf(words.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(com.mirkoddd.sift.core.SiftPatterns::literal)
                .toList());
    }
}