package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Recognises 3-4 digit runs that could plausibly be a {@code SSEE} compact
 * season+episode (e.g. "0102" → S01E02). Emitted at priority 700 with the
 * {@code weak-duplicate} + {@code coexist} tags so they don't fight stronger
 * matches in the conflict solver.
 */
public final class WeakDuplicateExtractor implements Extractor {

    public static final String WEAK_DUPLICATE = "weak-duplicate";
    public static final String WEAK_EPISODE = "weak-episode";
    public static final MatchName SEASON = MatchName.SEASON;
    public static final MatchName EPISODE = MatchName.EPISODE;

    private static final String COEXIST = "coexist";
    private static final String EPISODE_WORD = "episode-word";
    private static final String SEASON_WORD = "season-word";
    private static final String EXPECTED = "expected";

    private static final int PRIORITY = 700;

    private static final String GRP_S = "s";
    private static final String GRP_E = "e";

    private static final Pattern PATTERN = Pattern.compile(
            fromAnywhere()
                    .namedCapture(capture(GRP_S, between(1, 2).digits()))
                    .notPrecededBy(exactly(1).digits())
                    .then()
                    .namedCapture(capture(GRP_E, exactly(2).digits()))
                    .notFollowedBy(exactly(1).digits())
                    .shake()
    );

    @Override
    public String name() {
        return "weak_duplicate";
    }

    @Override
    public String description() {
        return "weak NN/NN duplicate (rejected unless year guards it)";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public void extract(ParseContext ctx) {
        if (WeakExtractorCommon.TYPE_MOVIE.equals(ctx.options.type())) return;
        if (Boolean.TRUE.equals(ctx.options.episodePreferNumber())) return;

        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        var tags = Set.of(WEAK_EPISODE, WEAK_DUPLICATE, COEXIST);

        while (m.find()) {
            var span = new Match(MatchName.WEAK, null, m.start(), m.end(), m.group(), PRIORITY, Set.of(), false);
            if (!seps.test(span)) continue;

            int s = Integer.parseInt(m.group(GRP_S));
            int e = Integer.parseInt(m.group(GRP_E));

            ctx.matches.add(new Match(SEASON, s, m.start(GRP_S), m.end(GRP_S), m.group(GRP_S), PRIORITY, tags, false));
            ctx.matches.add(new Match(EPISODE, e, m.start(GRP_E), m.end(GRP_E), m.group(GRP_E), PRIORITY, tags, false));
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var fileParts = Markers.named(ctx.markers, WeakExtractorCommon.MARKER_PATH).toList();
        boolean hasSxxExx = ctx.matches.tagged(WeakExtractorCommon.SXXEXX).findAny().isPresent();

        dropInAnimeContext(ctx);

        if (!hasSxxExx) {
            dropAnimeDashSeparatedPair(ctx);
            dropDuplicateInsideRangePair(ctx);
        }

        dropWeakIfMoviePerFilePart(ctx, fileParts);
        dropInsideExpectedTitle(ctx, false);
        dropOverlappingStrongerProperty(ctx);
        dropAllWeakEpisodeWhenDuplicate(ctx, fileParts);
        dedupKeepLastPerFilePart(ctx, fileParts);
        dropInsideExpectedTitle(ctx, true);
        dropDuplicateWhenStrongInFilePart(ctx, fileParts);
    }

    private static void dropInAnimeContext(ParseContext ctx) {
        boolean animeContext = !ctx.input.isEmpty()
                && (ctx.input.startsWith("[") || ctx.input.startsWith("("))
                && ctx.markers.stream().anyMatch(mk -> WeakExtractorCommon.MARKER_GROUP.equals(mk.name()) && mk.start() <= 1 && !isAllDigits(mk.raw()));

        if (!animeContext) {
            animeContext = WeakExtractorCommon.hasScreenSizeInGroup(ctx);
        }

        if (animeContext) {
            WeakExtractorCommon.removeMatches(ctx, ctx.matches.tagged(WEAK_DUPLICATE));
        }
    }

    private static void dropAnimeDashSeparatedPair(ParseContext ctx) {
        var seasonRuns = ctx.matches.named(SEASON)
                .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> isDashSeparatedBefore(ctx.input, m.start()))
                .toList();

        for (var seasonMatch : seasonRuns) {
            ctx.matches.named(EPISODE)
                    .filter(em -> em.tags().contains(WEAK_DUPLICATE) && em.start() == seasonMatch.end())
                    .findFirst()
                    .filter(matchingEpisode -> hasContentAfterPos(ctx.input, matchingEpisode.end()) && hasRangePartner(ctx, matchingEpisode.end()))
                    .ifPresent(matchingEpisode -> {
                        ctx.matches.remove(seasonMatch);
                        ctx.matches.remove(matchingEpisode);
                    });
        }
    }

    private static void dropDuplicateInsideRangePair(ParseContext ctx) {
        var weakEpisodes = ctx.matches.named(EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> m.raw() != null && m.raw().length() >= 3)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        for (int i = 0; i + 1 < weakEpisodes.size(); i++) {
            var a = weakEpisodes.get(i);
            var b = weakEpisodes.get(i + 1);

            if (a.value() instanceof Integer va && b.value() instanceof Integer vb && vb > va
                    && isRangeGap(ctx.input.substring(a.end(), b.start()))) {

                WeakExtractorCommon.removeMatches(ctx, ctx.matches.tagged(WEAK_DUPLICATE).filter(m -> WeakExtractorCommon.isInside(m, a.start(), b.end())));
            }
        }
    }

    private static void dropWeakIfMoviePerFilePart(ParseContext ctx, List<Marker> fileParts) {
        if (WeakExtractorCommon.TYPE_EPISODE.equals(ctx.options.type())) return;

        for (var fp : fileParts) {
            ctx.matches.named(MatchName.YEAR)
                    .filter(y -> WeakExtractorCommon.isInside(y, fp))
                    .findFirst()
                    .ifPresent(year -> {
                        var pairStarts = collectWeakDupPairStartsAfterYear(ctx, fp, year.end());
                        WeakExtractorCommon.removeMatches(ctx, ctx.matches.tagged(WEAK_DUPLICATE)
                                .filter(m -> WeakExtractorCommon.isInside(m, fp))
                                .filter(m -> !isExemptFromMovieDrop(ctx, m, pairStarts)));
                    });
        }
    }

    private static void dropInsideExpectedTitle(ParseContext ctx, boolean includeWeakEpisode) {
        var expectedTitles = ctx.matches.named(MatchName.TITLE).filter(m -> m.tags().contains(EXPECTED)).toList();
        if (expectedTitles.isEmpty()) return;

        WeakExtractorCommon.removeMatches(ctx, ctx.matches.all()
                .filter(m -> m.name() == SEASON || m.name() == EPISODE)
                .filter(m -> m.tags().contains(WEAK_DUPLICATE) || (includeWeakEpisode && m.tags().contains(WEAK_EPISODE)))
                .filter(m -> expectedTitles.stream().anyMatch(t -> WeakExtractorCommon.isInside(m, t))));
    }

    private static void dropOverlappingStrongerProperty(ParseContext ctx) {
        var groups = collectStrongerGroups(ctx);
        WeakExtractorCommon.removeMatches(ctx, ctx.matches.all()
                .filter(m -> m.name() == SEASON || m.name() == EPISODE)
                .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> overlapsAnyGroup(m, groups)));
    }

    private static void dropAllWeakEpisodeWhenDuplicate(ParseContext ctx, List<Marker> fileParts) {
        for (var fp : fileParts) {
            boolean hasDup = ctx.matches.tagged(WEAK_DUPLICATE).anyMatch(m -> WeakExtractorCommon.isInside(m, fp));
            if (hasDup && !filePartHasStrongMarker(ctx, fp)) {
                WeakExtractorCommon.removeMatches(ctx, ctx.matches.tagged(WEAK_EPISODE)
                        .filter(m -> !m.tags().contains(WEAK_DUPLICATE))
                        .filter(m -> WeakExtractorCommon.isInside(m, fp)));
            }
        }
    }

    private static void dedupKeepLastPerFilePart(ParseContext ctx, List<Marker> fileParts) {
        for (var fp : fileParts) {
            ctx.matches.tagged(WEAK_DUPLICATE)
                    .filter(m -> WeakExtractorCommon.isInside(m, fp))
                    .collect(Collectors.groupingBy(Match::name))
                    .values()
                    .forEach(group -> WeakExtractorCommon.removeMatches(ctx, group.stream()
                            .sorted(Comparator.comparingInt(Match::start).reversed())
                            .skip(1)));
        }
    }

    private static void dropDuplicateWhenStrongInFilePart(ParseContext ctx, List<Marker> fileParts) {
        for (var fp : fileParts) {
            if (filePartHasStrongMarker(ctx, fp)) {
                WeakExtractorCommon.removeMatches(ctx, ctx.matches.tagged(WEAK_DUPLICATE).filter(m -> WeakExtractorCommon.isInside(m, fp)));
            }
        }
    }

    private static boolean filePartHasStrongMarker(ParseContext ctx, Marker fp) {
        return ctx.matches.all()
                .filter(m -> WeakExtractorCommon.isInside(m, fp))
                .anyMatch(m -> m.tags().contains(WeakExtractorCommon.SXXEXX) || m.tags().contains(EPISODE_WORD) || m.tags().contains(SEASON_WORD));
    }

    private static List<List<Match>> collectStrongerGroups(ParseContext ctx) {
        return List.of(
                ctx.matches.named(MatchName.YEAR).toList(),
                ctx.matches.named(MatchName.DATE).toList(),
                ctx.matches.all().filter(x -> x.name() == MatchName.VIDEO_CODEC || x.name() == MatchName.AUDIO_CODEC).toList(),
                ctx.matches.named(MatchName.SCREEN_SIZE).toList(),
                ctx.matches.named(MatchName.OTHER).filter(m -> m.raw() != null && m.raw().length() >= 4 && m.raw().chars().anyMatch(Character::isDigit)).toList(),
                ctx.matches.all().filter(m -> m.name() == MatchName.AUDIO_BIT_RATE || m.name() == MatchName.VIDEO_BIT_RATE).toList()
        );
    }

    private static boolean overlapsAnyGroup(Match m, List<List<Match>> groups) {
        return groups.stream().flatMap(List::stream).anyMatch(o -> o.overlaps(m));
    }

    private static Set<Integer> collectWeakDupPairStartsAfterYear(ParseContext ctx, Marker fp, int yearEnd) {
        return ctx.matches.named(SEASON)
                .filter(s -> s.tags().contains(WEAK_DUPLICATE))
                .filter(s -> WeakExtractorCommon.isInside(s, fp))
                .map(Match::start)
                .filter(start -> start >= yearEnd)
                .filter(start -> ctx.input.substring(yearEnd, start).chars().allMatch(c -> Seps.isSep((char) c)))
                .collect(Collectors.toSet());
    }

    private static boolean isExemptFromMovieDrop(ParseContext ctx, Match m, Set<Integer> pairStarts) {
        if (pairStarts.contains(m.start())) return true;
        return pairStarts.stream()
                .filter(ps -> m.start() == ps + 1 || m.start() == ps + 2)
                .anyMatch(ps -> ctx.matches.named(SEASON)
                        .filter(s -> s.start() == ps && s.tags().contains(WEAK_DUPLICATE))
                        .anyMatch(s -> m.start() == s.end()));
    }

    private static boolean hasRangePartner(ParseContext ctx, int runEnd) {
        return ctx.matches.named(EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> m.start() >= runEnd)
                .anyMatch(m -> isRangeGap(ctx.input.substring(runEnd, m.start())));
    }

    private static boolean isRangeGap(String gap) {
        return !gap.isEmpty() && gap.length() <= 5 && WeakExtractorCommon.RANGE_SEP.matcher(gap).matches();
    }

    private static boolean isAllDigits(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static boolean hasContentAfterPos(String input, int pos) {
        return input.substring(pos).chars().anyMatch(c -> !Seps.isSep((char) c));
    }

    private static boolean isDashSeparatedBefore(String input, int pos) {
        if (!isSepAt(input, pos - 1)) return false;
        if (!isDashAt(input, pos - 2)) return false;
        int textPos = isSepAt(input, pos - 3) ? pos - 4 : pos - 3;

        return isValidTextAt(input, textPos);
    }

    private static boolean isSepAt(String input, int pos) {
        return pos >= 0 && pos < input.length() && Seps.isSep(input.charAt(pos));
    }

    private static boolean isDashAt(String input, int pos) {
        return pos >= 0 && pos < input.length() && input.charAt(pos) == '-';
    }

    private static boolean isValidTextAt(String input, int pos) {
        if (pos < 0 || pos >= input.length()) return false;
        char c = input.charAt(pos);
        return !Seps.isSep(c) && c != '-';
    }
}