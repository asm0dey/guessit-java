package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class EpisodeTitleExtractor implements Extractor {
    public static final String TITLE = "title";
    private static final Set<String> PREVIOUS_NAMES = Set.of(
        "episode", "episode_count", "season", "season_count", "date", TITLE, "year");
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
    }

    private void removeConflictsWithEpisodeTitle(ParseContext ctx) {
        var toRemove = new ArrayList<Match>();
        for (var fp : Markers.named(ctx.markers, "path").toList()) {
            var inFp = ctx.matches.range(fp.start(), fp.end(), m -> AFFECTED_NAMES.contains(m.name())).toList();
            for (var m : inFp) {
                var before = ctx.matches.range(fp.start(), m.start(), x -> !x.isPrivate())
                    .max(java.util.Comparator.comparingInt(Match::end))
                    .orElse(null);
                if (before == null || !PREVIOUS_NAMES.contains(before.name())) continue;
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
            var prev = ctx.matches.previous(t, m -> "episode".equals(m.name()));
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
        for (var fp : Markers.markerSorted(paths, ctx.matches)) {
            var hasTitle = ctx.matches.range(fp.start(), fp.end(), m -> TITLE.equals(m.name())).findAny().isPresent();
            if (!hasTitle) continue;
            var titles = titleExtractor.checkTitlesInFilepart(ctx, fp,
                TitleExtractor::isIgnored, EPISODE_TITLE, List.of(TITLE), null, true);
            if (titles == null) continue;
            for (var t : titles.titles()) {
                var prev = ctx.matches.previous(t, m -> PREVIOUS_NAMES.contains(m.name()));
                if (prev.isPresent() || hasCrc) ctx.matches.add(t);
            }
            for (var r : titles.toRemove()) ctx.matches.remove(r);
        }
    }

    private void alternativeTitleReplace(ParseContext ctx) {
        if (ctx.matches.named(EPISODE_TITLE).findAny().isPresent()) return;
        var alt = ctx.matches.named("alternative_title").findFirst().orElse(null);
        if (alt == null) return;
        var mainTitle = ctx.matches.chainBefore(alt.start(), ctx.input, Seps.CHARS,
            m -> m.tags().contains(TITLE)).orElse(null);
        if (mainTitle == null) return;
        var prev = ctx.matches.previous(mainTitle, m -> PREVIOUS_NAMES.contains(m.name()));
        var hasCrc = ctx.matches.named("crc32").findAny().isPresent();
        if (prev.isPresent() || hasCrc) {
            var newTags = new java.util.HashSet<>(alt.tags());
            newTags.add("alternative-replaced");
            ctx.matches.replace(alt, new Match(EPISODE_TITLE, alt.value(), alt.start(), alt.end(),
                alt.raw(), alt.priority(), Set.copyOf(newTags), alt.isPrivate()));
        }
    }

    private void filepart3EpisodeTitle(ParseContext ctx) {
        if (ctx.matches.tagged("filepart-title").findAny().isPresent()) return;
        var paths = Markers.named(ctx.markers, "path").toList();
        if (paths.size() < 3) return;
        var filename = paths.getLast();
        var directory = paths.get(paths.size() - 2);
        var subdirectory = paths.get(paths.size() - 3);
        if (ctx.matches.range(filename.start(), filename.end(), m -> "episode".equals(m.name())).findAny().isEmpty()) return;
        if (ctx.matches.range(directory.start(), directory.end(), m -> "season".equals(m.name())).findAny().isEmpty()) return;
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
        if (ctx.matches.range(filename.start(), filename.end(), m -> "episode".equals(m.name())).findAny().isEmpty()) return;
        var hasSeason = ctx.matches.range(directory.start(), directory.end(), m -> "season".equals(m.name())).findAny().isPresent()
            || ctx.matches.range(filename.start(), filename.end(), m -> "season".equals(m.name())).findAny().isPresent();
        if (!hasSeason) return;
        var h = findEpisodeTitleHoles(ctx, directory);
        if (h == null) return;
        var tags = Set.of("filepart-title");
        ctx.matches.add(new Match(TITLE, h.value(), h.start, h.end, h.raw(), 1000, tags, false));
    }
}
