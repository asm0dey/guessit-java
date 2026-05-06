package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code season}, {@code episode}, and {@code episode_count} from
 * word forms: "Episode 5", "Season 2", "Ep 12 of 24", "Saison 3", "Capitulo 7".
 *
 * <p>Episode numerals can be parsed as Roman/word numerals only when the
 * caller hinted that the input is an {@code episode} type — in the general
 * case, accepting "Episode V" would conflict with movie titles like "Rocky V".
 * The {@code episode-word} tag distinguishes these matches from the more
 * structured {@code SxxExx} matches; the conflict solver and downstream
 * rules use that distinction.
 *
 * <p>{@link #or} sorts alternation entries longest-first to defeat regex
 * left-to-right alternation greediness — without it "ep" would match before
 * "episode" and the match span would be too short.
 */
public final class EpisodeWordExtractor implements Extractor {
    public static final String EPISODE = "episode";
    private static final int MAX_RANGE_GAP = 1;
    private static final List<String> EPISODE_WORDS = List.of(EPISODE, "episodes", "ep", "eps", "episodio", "episodios", "capitulo", "capitulos", "part", "parts", "ch", "chapter", "chapters", "e");
    /** Episode words that may be matched as a single character — these must be
     *  followed by digits with NO separator between, otherwise titles with
     *  trailing latinised vowels ("Fumetsu no Anata e - 03") get clipped. */
    private static final java.util.Set<String> SHORT_EPISODE_WORDS = java.util.Set.of("e");
    public static final String SEASON = "season";
    // Mirrors python's `season_words` config — note "serie"/"series" are
    // intentionally excluded; they're too common in show titles ("Date Series",
    // "FlexGet Series", "DS9 Series") and python relies on the year/SxxExx
    // patterns to pick season instead.
    private static final List<String> SEASON_WORDS = List.of(SEASON, "seasons", "saison", "saisons", "seizoen", "temp", "temporada", "temporadas", "staffel", "staffeln", "stagione", "stagioni");
    private static final List<String> OF_WORDS = List.of("of", "sur", "de");
    private static final String SEASON_WORD_TAG = "season-word";
    private static final String OF_COUNT_TAIL = ")[ ._-]*(\\d+))?";

    private static final Pattern SEASON_RE = Pattern.compile("(?i)\\b(" + or(SEASON_WORDS) + ")[ ._-]*(" + Numerals.NUMERAL + ")(?:[ ._-]*(?:" + or(OF_WORDS) + OF_COUNT_TAIL);
    private static final Pattern SEASON_TAIL_RE = Pattern.compile(
        "(?i)([ ._]*(?:and|et|to|a|[-~&+])[ ._]*|[ ._-]+)(\\d+)");
    private static final Pattern AFTER_OF_RE = Pattern.compile(
        "(?i)^[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*\\d+");
    private static final Pattern EP_RE_EPISODE_TYPE = Pattern.compile(
        "(?i)(?:^|(?<=[^a-zA-Z0-9]))(" + or(EPISODE_WORDS) + ")[ ._-]*"
        + "(" + Numerals.NUMERAL + ")(?:v(\\d+))?(?:[ ._-]*(?:" + or(OF_WORDS) + OF_COUNT_TAIL);
    private static final Pattern EP_RE_DEFAULT = Pattern.compile(
        "(?i)(?:^|(?<=[^a-zA-Z0-9]))(" + or(EPISODE_WORDS) + ")[ ._-]*"
        + "(\\d+)(?:v(\\d+))?(?:[ ._-]*(?:" + or(OF_WORDS) + OF_COUNT_TAIL);
    private static final Pattern DETACHED_EP_COUNT_RE = Pattern.compile(
        "(?i)(\\d+)[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*(\\d+)"
        + "(?:[ ._-]*(?:" + or(EPISODE_WORDS) + "))?");

    @Override public String name() { return "episode_word"; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;

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
        int valStart = seasonMatcher.start(2);
        int valEnd = seasonMatcher.end(2);
        int n = parseSafe(seasonMatcher.group(2));
        if (n < 0) return -1;

        ctx.matches.add(new Match(MatchName.SEASON, n, valStart, valEnd,
                ctx.input.substring(valStart, valEnd), 1000, Set.of(SEASON_WORD_TAG), false));
        return n;
    }

    private void addSeasonCount(ParseContext ctx, Matcher seasonMatcher) {
        if (seasonMatcher.group(3) == null) return;

        int countStart = seasonMatcher.start(3);
        int countEnd = seasonMatcher.end(3);
        int c = parseSafe(seasonMatcher.group(3));
        if (c >= 0) {
            ctx.matches.add(new Match(MatchName.SEASON_COUNT, c, countStart, countEnd,
                    seasonMatcher.group(3), 1000, Set.of(), false));
        }
    }

    private void processSeasonTail(ParseContext ctx, Matcher seasonMatcher, int firstSeasonValue) {
        var input = ctx.input;
        var blockSpans = getBlockedSpans(ctx);

        int prevVal = firstSeasonValue;
        int scanFrom = seasonMatcher.end();
        var tail = SEASON_TAIL_RE.matcher(input);

        while (true) {
            tail.region(scanFrom, input.length());
            if (!tail.lookingAt()) break;

            var tailResult = processSeasonTailMatch(ctx, tail, prevVal, blockSpans);
            if (!tailResult.isValid) break;

            prevVal = tailResult.value;
            scanFrom = tail.end();
        }
    }

    private List<int[]> getBlockedSpans(ParseContext ctx) {
        return ctx.matches.all()
                .filter(m -> isBlockedMatchType(m.name()))
                .map(m -> new int[]{m.start(), m.end()})
                .toList();
    }

    private boolean isBlockedMatchType(MatchName name) {
        return name == MatchName.SCREEN_SIZE || name == MatchName.YEAR
                || name == MatchName.SOURCE || name == MatchName.VIDEO_CODEC
                || name == MatchName.AUDIO_CODEC || name == MatchName.VIDEO_PROFILE
                || name == MatchName.FRAME_RATE;
    }

    private static class TailResult {
        final boolean isValid;
        final int value;

        TailResult(boolean isValid, int value) {
            this.isValid = isValid;
            this.value = value;
        }
    }

    private TailResult processSeasonTailMatch(ParseContext ctx, Matcher tail,
                                              int prevVal, List<int[]> blockSpans) {
        String sepToken = tail.group(1).strip().toLowerCase(java.util.Locale.ROOT);
        String op = sepToken.replaceAll("^[. _]+|[. _]+$", "");
        boolean strong = op.equals("&") || op.equals("+") || op.equals("and") || op.equals("et");
        boolean range = op.equals("-") || op.equals("~") || op.equals("to") || op.equals("a");
        int v = parseSafe(tail.group(2));

        if (v < 0 || v <= prevVal) return new TailResult(false, prevVal);

        int tStart = tail.start(2);
        int tEnd = tail.end(2);

        if (blockSpans.stream().anyMatch(sp -> sp[0] < tEnd && tStart < sp[1])) return new TailResult(false, prevVal);
        if (!strong && !range && v - prevVal > MAX_RANGE_GAP + 1) return new TailResult(false, prevVal);
        if (isFollowedByOfClause(ctx.input, tEnd)) return new TailResult(false, prevVal);

        if (range) {
            addRangeSeasons(ctx, prevVal, v, tStart);
        }

        ctx.matches.add(new Match(MatchName.SEASON, v, tStart, tEnd,
                ctx.input.substring(tStart, tEnd), 1000, Set.of(SEASON_WORD_TAG), false));

        return new TailResult(true, v);
    }

    private boolean isFollowedByOfClause(String input, int position) {
        return AFTER_OF_RE.matcher(input).region(position, input.length()).lookingAt();
    }

    private void addRangeSeasons(ParseContext ctx, int prevVal, int v, int tStart) {
        for (int x = prevVal + 1; x < v; x++) {
            ctx.matches.add(new Match(MatchName.SEASON, x, tStart, tStart, "",
                    1000, Set.of(SEASON_WORD_TAG), false));
        }
    }

    private void extractEpisodeMatches(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        boolean episodeType = MatchName.EPISODE.toString().toLowerCase().equals(ctx.options.type());
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
        int markerStart = epMatcher.start(1);
        int markerEnd = epMatcher.end(1);
        String mw = epMatcher.group(1);

        if (mw != null && !SHORT_EPISODE_WORDS.contains(mw.toLowerCase())
                && markerEnd < ctx.input.length()
                && Seps.isSep(ctx.input.charAt(markerEnd))) {
            ctx.matches.add(new Match(MatchName.EPISODE_WORD_MARKER, null,
                    markerStart, markerEnd, mw, 1000, Set.of(), true));
        }
    }

    private boolean validateShortEpisodeMarker(Matcher epMatcher) {
        String marker = epMatcher.group(1);
        if (marker != null && SHORT_EPISODE_WORDS.contains(marker.toLowerCase())) {
            int afterMarker = epMatcher.start(1) + marker.length();
            return afterMarker == epMatcher.start(2);
        }
        return true;
    }

    private Integer parseEpisodeNumber(Matcher epMatcher,
                                       Predicate<Match> seps, boolean episodeType) {
        int epStart = epMatcher.start(2);
        int epEnd = epMatcher.end(2);
        String epToken = epMatcher.group(2);

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

        int epStart = epMatcher.start(2);
        int epEnd = epMatcher.end(2);
        ctx.matches.add(new Match(MatchName.EPISODE, ep, epStart, epEnd,
                ctx.input.substring(epStart, epEnd), 1000, Set.of("episode-word"), false));

        addEpisodeVersion(ctx, epMatcher);
        addEpisodeCountFromMatch(ctx, epMatcher);
    }

    private void addEpisodeVersion(ParseContext ctx, Matcher epMatcher) {
        if (epMatcher.group(3) != null) {
            int v = Integer.parseInt(epMatcher.group(3));
            ctx.matches.add(new Match(MatchName.VERSION, v, epMatcher.start(3), epMatcher.end(3),
                    epMatcher.group(3), 1000, Set.of(), false));
        }
    }

    private void addEpisodeCountFromMatch(ParseContext ctx, Matcher epMatcher) {
        if (epMatcher.group(4) != null) {
            int c = Integer.parseInt(epMatcher.group(4));
            ctx.matches.add(new Match(MatchName.EPISODE_COUNT, c, epMatcher.start(4), epMatcher.end(4),
                    epMatcher.group(4), 1000, Set.of(), false));
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

        int dStart = dm.start(1);
        int dEnd = dm.end(1);
        boolean overlapsSeason = ctx.matches.range(dStart, dEnd,
            m -> m.name() == MatchName.SEASON && m.value() != null).findAny().isPresent();
        if (overlapsSeason) return;

        int e = Integer.parseInt(dm.group(1));
        int c = Integer.parseInt(dm.group(2));

        ctx.matches.add(new Match(MatchName.EPISODE, e, dm.start(1), dm.end(1),
            dm.group(1), 1000, Set.of("episode-word"), false));
        ctx.matches.add(new Match(MatchName.EPISODE_COUNT, c, dm.start(2), dm.end(2),
            dm.group(2), 1000, Set.of(), false));
        ctx.matches.add(new Match(MatchName.EP_COUNT_SPAN, null, dm.start(), dm.end(),
            raw, 1000, Set.of(), true));
    }

    @Override
    public void postProcess(ParseContext ctx) {
        // Conflict resolution can drop the season-VALUE match (e.g. "Series.10"
        // overlapping a date) while leaving the private head match behind.
        // Title hole compute treats every match — private or not — as a block,
        // so an orphan head consumes its span (e.g. "Series" in
        // "Date.Series.10-11-2008.XViD") and steals title text. Mirror python's
        // parent/children cascade by dropping heads with no surviving value.
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
        try { return Numerals.parse(token); }
        catch (RuntimeException _) { return -1; }
    }

    private static boolean isPureDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static String or(List<String> items) {
        var sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return String.join("|", sorted);
    }
}
