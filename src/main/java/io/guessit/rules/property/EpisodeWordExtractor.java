package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private static final List<String> SEASON_WORDS = List.of("season", "seasons", "saison", "saisons", "seizoen", "serie", "series", "temp", "temporada", "temporadas", "staffel", "staffeln", "stagione", "stagioni");
    private static final List<String> OF_WORDS = List.of("of", "sur", "de");

    @Override public String name() { return "episode_word"; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        // Season-word head accepts the standard separator boundaries OR a
        // chain-tail boundary ('&', '+', '-', '~', 'a', 'and', 'et', 'to')
        // that isn't in Seps.CHARS but is still a valid chain continuation
        // (mirrors python's chain validator or_(seps_before, seps_after) but
        // restricted to known season-chain operators to avoid greedily
        // matching "Temple" as season-word "temp" + Roman L).
        var sepsBefore = Validators.sepsBefore(input);
        var sepsAfter = Validators.sepsAfter(input);
        java.util.function.Predicate<Match> seasonHeadValidator = m -> {
            if (!sepsBefore.test(m)) return false;
            if (sepsAfter.test(m)) return true;
            int e = m.end();
            if (e >= input.length()) return true;
            char c = input.charAt(e);
            return c == '&' || c == '+' || c == '~';
        };

        var seasonRe = Pattern.compile("(?i)\\b(" + or(SEASON_WORDS) + ")[ ._-]*(" + Numerals.NUMERAL + ")"
            + "(?:[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*(\\d+))?");
        // Tail captures one separator token (group 1) and the next digits (group 2).
        // Strong:  &, +, and, et — emits as additive list, terminates further weak checks.
        // Range:   -, ~, to, a   — expands inclusive between prev and v.
        // Weak:    .,  , _       — only valid when 0 < v-prev <= MAX_RANGE_GAP+1.
        var seasonTailRe = Pattern.compile(
            "(?i)([ ._]*(?:and|et|to|a|[-~&+])[ ._]*|[ ._-]+)(\\d+)");
        var seasonMatcher = seasonRe.matcher(input);
        while (seasonMatcher.find()) {
            var raw = seasonMatcher.group();
            var headMatch = new Match("season", null, seasonMatcher.start(), seasonMatcher.end(), raw, 1000, Set.of(), true);
            if (!seasonHeadValidator.test(headMatch)) continue;
            ctx.matches.add(headMatch);
            int valStart = seasonMatcher.start(2);
            int valEnd = seasonMatcher.end(2);
            int n = parseSafe(seasonMatcher.group(2));
            if (n < 0) continue;
            ctx.matches.add(new Match("season", n, valStart, valEnd,
                input.substring(valStart, valEnd), 1000, Set.of("season-word"), false));
            // "Season N of M" → season_count=M.
            if (seasonMatcher.group(3) != null) {
                int countStart = seasonMatcher.start(3);
                int countEnd = seasonMatcher.end(3);
                int c = parseSafe(seasonMatcher.group(3));
                if (c >= 0) {
                    ctx.matches.add(new Match("season_count", c, countStart, countEnd,
                        seasonMatcher.group(3), 1000, Set.of(), false));
                }
            }
            // Continue scanning after the first season number for additional
            // season values: "Season 1 to 3", "Season.1.3.4", "Saison 1 a 3",
            // "Season.1.3&5" → [1,3,5], "Season.1.2.3-5" → [1,2,3,4,5].
            int prevVal = n;
            int scanFrom = seasonMatcher.end();
            // Tail digits inside an existing strong match (screen_size, year,
            // source, video_codec, audio_codec) are part of that property,
            // not a new season number.
            var blockSpans = ctx.matches.all()
                .filter(m -> {
                    String nm = m.name();
                    return "screen_size".equals(nm) || "year".equals(nm)
                        || "source".equals(nm) || "video_codec".equals(nm)
                        || "audio_codec".equals(nm) || "video_profile".equals(nm)
                        || "audio_channels".equals(nm) || "frame_rate".equals(nm);
                })
                .map(m -> new int[]{m.start(), m.end()})
                .toList();
            while (true) {
                var tail = seasonTailRe.matcher(input);
                tail.region(scanFrom, input.length());
                if (!tail.lookingAt()) break;
                String sepToken = tail.group(1).strip().toLowerCase();
                // Trim leading/trailing weak chars to extract the operator (if any).
                String op = sepToken.replaceAll("^[. _]+|[. _]+$", "");
                boolean strong = op.equals("&") || op.equals("+") || op.equals("and") || op.equals("et");
                boolean range = op.equals("-") || op.equals("~") || op.equals("to") || op.equals("a");
                int v = parseSafe(tail.group(2));
                if (v < 0 || v <= prevVal) break;
                int tStart = tail.start(2);
                int tEnd = tail.end(2);
                final int ts = tStart, te = tEnd;
                if (blockSpans.stream().anyMatch(sp -> sp[0] < te && ts < sp[1])) break;
                if (!strong && !range) {
                    // weak: must be near-consecutive
                    if (v - prevVal > MAX_RANGE_GAP + 1) break;
                }
                if (range) {
                    // expand intermediate values privately so seasonList captures full range
                    for (int x = prevVal + 1; x < v; x++) {
                        ctx.matches.add(new Match("season", x, tStart, tStart, "",
                            1000, Set.of("season-word"), false));
                    }
                }
                ctx.matches.add(new Match("season", v, tStart, tEnd,
                    input.substring(tStart, tEnd), 1000, Set.of("season-word"), false));
                prevVal = v;
                scanFrom = tail.end();
            }
        }

        boolean episodeType = EPISODE.equals(ctx.options.type());
        String numToken = episodeType ? "(" + Numerals.NUMERAL + ")" : "(\\d+)";
        var epRe = Pattern.compile("(?i)(?:^|(?<=[^a-zA-Z0-9]))(" + or(EPISODE_WORDS) + ")[ ._-]*"
            + numToken + "(?:v(\\d+))?(?:[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*(\\d+))?");
        var epMatcher = epRe.matcher(input);
        while (epMatcher.find()) {
            var raw = epMatcher.group();
            var headMatch = new Match(EPISODE, null, epMatcher.start(), epMatcher.end(), raw, 1000, Set.of(), true);
            if (!seps.test(headMatch)) {
                continue;
            }
            ctx.matches.add(headMatch);
            int epStart = epMatcher.start(2);
            int epEnd = epMatcher.end(2);
            String epToken = epMatcher.group(2);
            int ep;
            if (episodeType) {
                ep = parseSafe(epToken);
                if (ep < 0) continue;
                if (!isPureDigits(epToken)) {
                    var token = new Match(EPISODE, ep, epStart, epEnd, epToken, 1000, Set.of(), false);
                    if (!seps.test(token)) continue;
                }
            } else {
                ep = Integer.parseInt(epToken);
            }
            ctx.matches.add(new Match(EPISODE, ep, epStart, epEnd,
                input.substring(epStart, epEnd), 1000, Set.of("episode-word"), false));
            if (epMatcher.group(3) != null) {
                int v = Integer.parseInt(epMatcher.group(3));
                ctx.matches.add(new Match("version", v, epMatcher.start(3), epMatcher.end(3),
                    epMatcher.group(3), 1000, Set.of(), false));
            }
            if (epMatcher.group(4) != null) {
                int c = Integer.parseInt(epMatcher.group(4));
                ctx.matches.add(new Match("episode_count", c, epMatcher.start(4), epMatcher.end(4),
                    epMatcher.group(4), 1000, Set.of(), false));
            }
        }

        // Detached: \d+ of \d+
        var detached = Pattern.compile("(?i)(\\d+)[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*(\\d+)");
        var dm = detached.matcher(input);
        while (dm.find()) {
            var raw = dm.group();
            var headMatch = new Match(EPISODE, null, dm.start(), dm.end(), raw, 1000, Set.of(), false);
            if (!seps.test(headMatch)) continue;
            int e = Integer.parseInt(dm.group(1));
            int c = Integer.parseInt(dm.group(2));
            ctx.matches.add(new Match(EPISODE, e, dm.start(1), dm.end(1),
                dm.group(1), 1000, Set.of("episode-word"), false));
            ctx.matches.add(new Match("episode_count", c, dm.start(2), dm.end(2),
                dm.group(2), 1000, Set.of(), false));
            // Private span covering the entire "N of M" so the connector "of"
            // doesn't surface as the first hole and steal episode_title.
            // Title/EpisodeTitle hole compute treats non-language non-country
            // private matches as hole-blockers; OutputBuilder drops unknown
            // names; ConflictSolver skips private matches.
            ctx.matches.add(new Match("ep_count_span", null, dm.start(), dm.end(),
                raw, 1000, Set.of(), true));
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
