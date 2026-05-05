package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class EpisodeTitleExtractor implements Extractor {
    public static final String TITLE = "title";
    public static final String EPISODE = "episode";
    public static final String SEASON = "season";
    public static final String EPISODE_COUNT = "episode_count";
    private static final Set<String> PREVIOUS_NAMES = Set.of(
            EPISODE, EPISODE_COUNT, SEASON, "season_count", "date", TITLE, "year");
    /**
     * Subset of {@link #PREVIOUS_NAMES} excluding {@code title}: in Python,
     * RemoveConflictsWithEpisodeTitle (priority 64) fires before
     * TitleFromPosition (priority 0), so {@code title} is never in the match
     * set when it runs. Java's pipeline runs TitleExtractor.postProcess first
     * and would otherwise see {@code title} as the {@code before} match,
     * incorrectly dropping a valid {@code year} that sits between the title
     * hole and downstream markers (e.g. {@code Show.Name.2015.Nice.Title.1080p.PBS...}).
     */
    private static final Set<String> CONFLICT_PREVIOUS_NAMES = Set.of(
            EPISODE, EPISODE_COUNT, SEASON, "season_count", "date", "year");
    private static final Set<String> NEXT_NAMES = Set.of(
        "streaming_service", "screen_size", "source", "video_codec",
        "audio_codec", "other", "container");
    private static final Set<String> AFFECTED_NAMES = Set.of("part", "year");
    private static final Set<String> AFFECTED_IF_HOLES_AFTER = Set.of("part");
    public static final String EPISODE_TITLE = "episode_title";

    @Override
    public String name() { return EPISODE_TITLE; }

    @Override
    public void extract(ParseContext ctx) { /* no extraction phase */ }

    @Override
    public void postProcess(ParseContext ctx) {
        removeConflictsWithEpisodeTitle(ctx);
        filepart3EpisodeTitle(ctx);
        filepart2EpisodeTitle(ctx);
        titleToEpisodeTitle(ctx);
        episodeTitleFromPosition(ctx);
        alternativeTitleReplace(ctx);
        dropLanguagesInsideTitleHoles(ctx);
    }

    /**
     * Drop short {@code language} / {@code subtitle_language} matches that
     * fall entirely inside a {@code title}, {@code alternative_title}, or
     * {@code episode_title} span. Mirrors python guessit's outcome where
     * "En" inside "En Close, Yet En Far" episode_title is not emitted as
     * language=English.
     */
    private static void dropLanguagesInsideTitleHoles(ParseContext ctx) {
        var titleSpans = ctx.matches.all()
            .filter(m -> TITLE.equals(m.name()) || "alternative_title".equals(m.name())
                    || EPISODE_TITLE.equals(m.name()))
            .map(m -> new int[]{m.start(), m.end()})
            .toList();
        if (titleSpans.isEmpty()) return;
        var toRemove = new java.util.ArrayList<Match>();
        for (var m : ctx.matches.all().toList()) {
            if (!"language".equals(m.name()) && !"subtitle_language".equals(m.name())) continue;
            if (m.length() > 3) continue;
            for (var sp : titleSpans) {
                if (m.start() >= sp[0] && m.end() <= sp[1]
                        && (m.start() > sp[0] || m.end() < sp[1])) {
                    toRemove.add(m);
                    break;
                }
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void removeConflictsWithEpisodeTitle(ParseContext ctx) {
        var toRemove = new ArrayList<Match>();
        for (var fp : Markers.named(ctx.markers, "path").toList()) {
            var inFp = ctx.matches.range(fp.start(), fp.end(), m -> AFFECTED_NAMES.contains(m.name())).toList();
            for (var m : inFp) {
                var before = ctx.matches.range(fp.start(), m.start(), x -> !x.isPrivate())
                    .max(java.util.Comparator.comparingInt(Match::end))
                    .orElse(null);
                if (before == null || !CONFLICT_PREVIOUS_NAMES.contains(before.name())) continue;
                var after = ctx.matches.range(m.end(), fp.end(), x -> !x.isPrivate())
                    .min(java.util.Comparator.comparingInt(Match::start))
                    .orElse(null);
                if (after == null || !NEXT_NAMES.contains(after.name())) continue;
                var holesBefore = Holes.compute(ctx.input, before.end(), m.start(),
                    ctx.matches.snapshot(), _ -> false, null, Formatters::cleanup);
                var holesAfter = Holes.compute(ctx.input, m.end(), after.start(),
                    ctx.matches.snapshot(), _ -> false, null, Formatters::cleanup);
                if (holesBefore.isEmpty() && holesAfter.isEmpty()) continue;
                if (AFFECTED_IF_HOLES_AFTER.contains(m.name()) && holesAfter.isEmpty()) continue;
                toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void titleToEpisodeTitle(ParseContext ctx) {
        var titles = ctx.matches.named(TITLE).toList();
        var values = new java.util.HashSet<>();
        for (var t : titles) values.add(t.value());
        if (values.size() < 2) return;
        for (var t : titles) {
            var prev = ctx.matches.previous(t, m -> EPISODE.equals(m.name()));
            if (prev.isPresent()) {
                ctx.matches.replace(t, new Match(EPISODE_TITLE, t.value(), t.start(), t.end(),
                    t.raw(), t.priority(), t.tags(), t.isPrivate()));
            }
        }
    }

    private void episodeTitleFromPosition(ParseContext ctx) {
        if (ctx.matches.named(EPISODE_TITLE).findAny().isPresent()) return;
        var paths = ctx.markers.stream().filter(m -> "path".equals(m.name())).toList();
        var titleExtractor = new TitleExtractor();
        var hasCrc = ctx.matches.named("crc32").findAny().isPresent();
        boolean isMovie = "movie".equals(predictedType(ctx));
        for (var fp : Markers.markerSorted(paths, ctx.matches)) {
            var hasTitle = ctx.matches.range(fp.start(), fp.end(), m -> TITLE.equals(m.name())).findAny().isPresent();
            if (!hasTitle) continue;
            var titles = titleExtractor.checkTitlesInFilepart(ctx, fp,
                TitleExtractor::isIgnored, EPISODE_TITLE, List.of(TITLE), null, true);
            if (titles == null) continue;
            for (var t : titles.titles()) {
                var prev = ctx.matches.previous(t, m -> PREVIOUS_NAMES.contains(m.name()));
                if (prev.isEmpty() && !hasCrc) continue;
                // Movie context: skip holes wedged between two structural
                // properties (e.g. "...source SPLIT SCENES container..."
                // → SPLIT SCENES is noise, not an alt-title). Holes that
                // are still in the "title region" (before any source/codec/
                // etc. trailing property) survive and reach TypeProcessor's
                // demote-to-alt path.
                if (isMovie && wedgedBetweenProperties(ctx, fp, t)) continue;
                ctx.matches.add(t);
            }
            for (var r : titles.toRemove()) ctx.matches.remove(r);
        }
    }

    /** True when both sides of a candidate hole within {@code filepart} are
     *  bounded by trailing structural property matches (source / video_codec /
     *  audio_codec / screen_size / etc.). Such holes are noise, not titles. */
    private static boolean wedgedBetweenProperties(ParseContext ctx, Marker filepart, Match candidate) {
        Set<String> trailingNames = Set.of("source", "video_codec", "audio_codec",
            "screen_size", "audio_channels", "audio_profile", "video_profile",
            "streaming_service", "container", "part", "release_group", "website");
        boolean propBefore = ctx.matches.all().anyMatch(m -> trailingNames.contains(m.name())
                && m.start() >= filepart.start() && m.end() <= candidate.start());
        boolean propAfter = ctx.matches.all().anyMatch(m -> trailingNames.contains(m.name())
                && m.start() >= candidate.end() && m.end() <= filepart.end());
        return propBefore && propAfter;
    }

    /** Mirror of TypeProcessor.decide for early type prediction inside
     *  EpisodeTitleExtractor.postProcess (TypeProcessor itself runs later in
     *  the PostPhase). Returns "movie" or "episode". */
    private static String predictedType(ParseContext ctx) {
        var optType = ctx.options.type();
        if (optType != null) return optType;
        if (anyNamed(ctx, EPISODE) || anyNamed(ctx, SEASON)
                || anyNamed(ctx, "episode_details") || anyNamed(ctx, "absolute_episode")) {
            return EPISODE;
        }
        if (anyNamed(ctx, "film")) return "movie";
        boolean hasYear = anyNamed(ctx, "year");
        if (anyNamed(ctx, "date") && !hasYear) return EPISODE;
        if (anyNamed(ctx, "bonus") && !hasYear) return EPISODE;
        boolean hasCrc = anyNamed(ctx, "crc32");
        boolean anyAnimeRg = ctx.matches.named("release_group").anyMatch(m -> m.tags().contains("anime"));
        if (hasCrc && anyAnimeRg) return EPISODE;
        return "movie";
    }

    private static java.util.Optional<Match> previousAdjacent(ParseContext ctx, int startPos,
                                                               java.util.function.Predicate<Match> predicate) {
        for (int pos = startPos; pos >= 0; pos--) {
            final int p = pos;
            var ending = ctx.matches.all().filter(m -> m.end() == p).toList();
            if (ending.isEmpty()) continue;
            return ending.stream().filter(predicate).findFirst();
        }
        return java.util.Optional.empty();
    }

    private static boolean anyNamed(ParseContext ctx, String name) {
        return ctx.matches.named(name).anyMatch(m -> !m.isPrivate());
    }

    private void alternativeTitleReplace(ParseContext ctx) {
        if (ctx.matches.named(EPISODE_TITLE).findAny().isPresent()) return;
        var alt = ctx.matches.named("alternative_title").findFirst().orElse(null);
        if (alt == null) return;
        var mainTitle = ctx.matches.chainBefore(alt.start(), ctx.input, Seps.CHARS,
            m -> m.tags().contains(TITLE)).orElse(null);
        if (mainTitle == null) return;
        // Mirror python rebulk previous(): walk back position-by-position;
        // the FIRST position with any match ending there decides — if those
        // matches don't satisfy the predicate, return None (don't keep walking).
        // Java's MatchSet.previous() flat-scans, which finds far-away outer
        // titles/episodes and wrongly converts alt titles to episode_title.
        var prev = previousAdjacent(ctx, mainTitle.start(), m -> PREVIOUS_NAMES.contains(m.name()));
        var hasCrc = ctx.matches.named("crc32").findAny().isPresent();
        if (prev.isPresent() || hasCrc) {
            var newTags = new java.util.HashSet<>(alt.tags());
            newTags.add("alternative-replaced");
            ctx.matches.replace(alt, new Match(EPISODE_TITLE, alt.value(), alt.start(), alt.end(),
                alt.raw(), alt.priority(), Set.copyOf(newTags), alt.isPrivate()));
        }
    }

    static void filepart3EpisodeTitleStatic(ParseContext ctx) { new EpisodeTitleExtractor().filepart3EpisodeTitle(ctx); }
    static void filepart2EpisodeTitleStatic(ParseContext ctx) { new EpisodeTitleExtractor().filepart2EpisodeTitle(ctx); }

    private void filepart3EpisodeTitle(ParseContext ctx) {
        if (ctx.matches.tagged("filepart-title").findAny().isPresent()) return;
        var paths = Markers.named(ctx.markers, "path").toList();
        if (paths.size() < 3) return;
        var filename = paths.getLast();
        var directory = paths.get(paths.size() - 2);
        var subdirectory = paths.get(paths.size() - 3);
        if (ctx.matches.range(filename.start(), filename.end(), m -> EPISODE.equals(m.name())).findAny().isEmpty()) return;
        if (ctx.matches.range(directory.start(), directory.end(), m -> SEASON.equals(m.name())).findAny().isEmpty()) return;
        // Skip if filename already produced a title — the file gave the show
        // name, the subdir is generic ("/series/").
        var hasFilenameTitle = ctx.matches.named(TITLE)
            .anyMatch(m -> m.start() >= filename.start() && m.end() <= filename.end());
        if (hasFilenameTitle) return;
        var h = findEpisodeTitleHoles(ctx, subdirectory);
        if (h == null) return;
        ctx.matches.add(new Match(TITLE, h.value(), h.start, h.end, h.raw(), 1000, Set.of(), false));
    }

    private static Holes.Hole findEpisodeTitleHoles(ParseContext ctx, Marker subdirectory) {
        java.util.function.Predicate<Match> ignore = m -> m.tags().contains("weak-episode") || TitleExtractor.isIgnored(m);
        var holes = Holes.compute(ctx.input, subdirectory.start(), subdirectory.end(),
            ctx.matches.snapshot(), ignore, Seps.TITLE_CHARS, Formatters::cleanup);
        if (holes.isEmpty()) return null;
        return holes.getFirst();
    }

    private void filepart2EpisodeTitle(ParseContext ctx) {
        if (ctx.matches.tagged("filepart-title").findAny().isPresent()) return;
        var paths = Markers.named(ctx.markers, "path").toList();
        if (paths.size() < 2) return;
        var filename = paths.getLast();
        var directory = paths.get(paths.size() - 2);
        if (ctx.matches.range(filename.start(), filename.end(), m -> EPISODE.equals(m.name())).findAny().isEmpty()) return;
        var hasSeason = ctx.matches.range(directory.start(), directory.end(), m -> SEASON.equals(m.name())).findAny().isPresent()
            || ctx.matches.range(filename.start(), filename.end(), m -> SEASON.equals(m.name())).findAny().isPresent();
        if (!hasSeason) return;
        var h = findEpisodeTitleHoles(ctx, directory);
        if (h == null) return;
        var tags = Set.of("filepart-title");
        ctx.matches.add(new Match(TITLE, h.value(), h.start, h.end, h.raw(), 1000, tags, false));
    }
}
