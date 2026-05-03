package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class EpisodeWordExtractor implements Extractor {
    private static final List<String> EPISODE_WORDS = List.of("episode", "episodes", "ep", "eps", "episodio", "episodios", "capitulo", "capitulos", "part", "parts", "ch", "chapter", "chapters", "e");
    private static final List<String> SEASON_WORDS = List.of("season", "seasons", "saison", "saisons", "serie", "series", "temp", "temporada", "temporadas", "staffel", "staffeln");
    private static final List<String> OF_WORDS = List.of("of", "sur", "de");

    @Override public String name() { return "episode_word"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        var seasonRe = Pattern.compile("(?i)\\b(" + or(SEASON_WORDS) + ")[ ._-]*(" + Numerals.NUMERAL + ")");
        var seasonMatcher = seasonRe.matcher(input);
        while (seasonMatcher.find()) {
            var raw = seasonMatcher.group();
            var headMatch = new Match("season", null, seasonMatcher.start(), seasonMatcher.end(), raw, 1000, Set.of(), false);
            if (!seps.test(headMatch)) continue;
            int valStart = seasonMatcher.start(2);
            int valEnd = seasonMatcher.end(2);
            int n = parseSafe(seasonMatcher.group(2), ctx);
            if (n < 0) continue;
            ctx.matches.add(new Match("season", n, valStart, valEnd,
                input.substring(valStart, valEnd), 1000, Set.of("season-word"), false));
        }

        boolean episodeType = "episode".equals(ctx.options.type());
        String numToken = episodeType ? "(" + Numerals.NUMERAL + ")" : "(\\d+)";
        var epRe = Pattern.compile("(?i)(?:^|(?<=[^a-zA-Z0-9]))(" + or(EPISODE_WORDS) + ")[ ._-]*"
            + numToken + "(?:v(\\d+))?(?:[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*(\\d+))?");
        var epMatcher = epRe.matcher(input);
        while (epMatcher.find()) {
            var raw = epMatcher.group();
            var headMatch = new Match("episode", null, epMatcher.start(), epMatcher.end(), raw, 1000, Set.of(), false);
            if (!seps.test(headMatch)) {
                continue;
            }
            int epStart = epMatcher.start(2);
            int epEnd = epMatcher.end(2);
            String epToken = epMatcher.group(2);
            int ep;
            if (episodeType) {
                ep = parseSafe(epToken, ctx);
                if (ep < 0) continue;
                if (!isPureDigits(epToken)) {
                    var token = new Match("episode", ep, epStart, epEnd, epToken, 1000, Set.of(), false);
                    if (!seps.test(token)) continue;
                }
            } else {
                ep = Integer.parseInt(epToken);
            }
            ctx.matches.add(new Match("episode", ep, epStart, epEnd,
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
            var headMatch = new Match("episode", null, dm.start(), dm.end(), raw, 1000, Set.of(), false);
            if (!seps.test(headMatch)) continue;
            int e = Integer.parseInt(dm.group(1));
            int c = Integer.parseInt(dm.group(2));
            ctx.matches.add(new Match("episode", e, dm.start(1), dm.end(1),
                dm.group(1), 1000, Set.of("episode-word"), false));
            ctx.matches.add(new Match("episode_count", c, dm.start(2), dm.end(2),
                dm.group(2), 1000, Set.of(), false));
        }
    }

    private static int parseSafe(String token, ParseContext ctx) {
        try { return Numerals.parse(token); }
        catch (RuntimeException e) { return -1; }
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
