package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class EpisodeTitleExtractor implements Extractor {
    public static final String TITLE = "title";
    public static final String SEASON = "season";
    private static final Set<MatchName> PREVIOUS_NAMES = Set.of(
            MatchName.EPISODE, MatchName.EPISODE_COUNT, MatchName.SEASON, MatchName.SEASON_COUNT, MatchName.DATE, MatchName.TITLE, MatchName.YEAR);
    /**
     * Subset of {@link #PREVIOUS_NAMES} excluding {@code title}: in Python,
     * RemoveConflictsWithEpisodeTitle (priority 64) fires before
     * TitleFromPosition (priority 0), so {@code title} is never in the match
     * set when it runs. Java's pipeline runs TitleExtractor.postProcess first
     * and would otherwise see {@code title} as the {@code before} match,
     * incorrectly dropping a valid {@code year} that sits between the title
     * hole and downstream markers (e.g. {@code Show.Name.2015.Nice.Title.1080p.PBS...}).
     */
    private static final Set<MatchName> CONFLICT_PREVIOUS_NAMES = Set.of(
            MatchName.EPISODE, MatchName.EPISODE_COUNT, MatchName.SEASON, MatchName.SEASON_COUNT, MatchName.DATE, MatchName.YEAR);
    private static final Set<MatchName> NEXT_NAMES = Set.of(
            MatchName.STREAMING_SERVICE, MatchName.SCREEN_SIZE, MatchName.SOURCE, MatchName.VIDEO_CODEC,
            MatchName.AUDIO_CODEC, MatchName.OTHER, MatchName.CONTAINER);
    private static final Set<MatchName> AFFECTED_NAMES = Set.of(MatchName.PART, MatchName.YEAR);
    private static final Set<MatchName> AFFECTED_IF_HOLES_AFTER = Set.of(MatchName.PART);
    public static final String EPISODE_TITLE = "episode_title";
    private static final String MOVIE_TYPE = "movie";
    private static final String FILEPART_TITLE_TAG = "filepart-title";

    @Override
    public String name() {
        return EPISODE_TITLE;
    }

    @Override
    public String description() {
        return "episode title (text after season/episode tokens)";
    }

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
                .filter(m -> m.name() == MatchName.TITLE || m.name() == MatchName.ALTERNATIVE_TITLE
                        || m.name() == MatchName.EPISODE_TITLE)
                .map(m -> new int[]{m.start(), m.end()})
                .toList();
        if (titleSpans.isEmpty()) return;
        var toRemove = new java.util.ArrayList<Match>();
        for (var m : ctx.matches.all().toList()) {
            if (m.name() != MatchName.LANGUAGE && m.name() != MatchName.SUBTITLE_LANGUAGE) continue;
            if (m.length() > 3) continue;
            if (isStrictlyInsideAnySpan(m, titleSpans)) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean isStrictlyInsideAnySpan(Match m, List<int[]> spans) {
        for (var sp : spans) {
            if (m.start() >= sp[0] && m.end() <= sp[1]
                    && (m.start() > sp[0] || m.end() < sp[1])) return true;
        }
        return false;
    }

    private void removeConflictsWithEpisodeTitle(ParseContext ctx) {
        var toRemove = new ArrayList<Match>();
        for (var fp : Markers.named(ctx.markers, "path").toList()) {
            var inFp = ctx.matches.range(fp.start(), fp.end(), m -> AFFECTED_NAMES.contains(m.name())).toList();
            for (var m : inFp) {
                if (conflictsWithEpisodeTitle(ctx, fp, m)) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean conflictsWithEpisodeTitle(ParseContext ctx, Marker fp, Match m) {
        var before = ctx.matches.range(fp.start(), m.start(), x -> !x.isPrivate())
                .max(java.util.Comparator.comparingInt(Match::end))
                .orElse(null);
        if (before == null || !CONFLICT_PREVIOUS_NAMES.contains(before.name())) return false;
        var after = ctx.matches.range(m.end(), fp.end(), x -> !x.isPrivate())
                .min(java.util.Comparator.comparingInt(Match::start))
                .orElse(null);
        if (after == null || !NEXT_NAMES.contains(after.name())) return false;
        var holesBefore = Holes.compute(ctx.input, before.end(), m.start(),
                ctx.matches.snapshot(), _ -> false, null, Formatters::cleanup);
        var holesAfter = Holes.compute(ctx.input, m.end(), after.start(),
                ctx.matches.snapshot(), _ -> false, null, Formatters::cleanup);
        if (holesBefore.isEmpty() && holesAfter.isEmpty()) return false;
        return !AFFECTED_IF_HOLES_AFTER.contains(m.name()) || !holesAfter.isEmpty();
    }

    private void titleToEpisodeTitle(ParseContext ctx) {
        var titles = ctx.matches.named(MatchName.TITLE).toList();
        var values = new java.util.HashSet<>();
        for (var t : titles) values.add(t.value());
        if (values.size() < 2) return;
        for (var t : titles) {
            // Mirror python rebulk's previous(): only the matches ending at
            // the *immediately preceding* end position count. Anything else
            // (e.g. an outer-dir episode marker masked by a release_group
            // ending closer to the title) does not qualify. Without this,
            // titles in a filename whose own episode marker comes AFTER them
            // get demoted because Java's MatchSet.previous() walks past
            // intervening matches to find the episode anywhere upstream.
            int prevEnd = ctx.matches.snapshot().stream()
                    .filter(m -> !m.isPrivate())
                    .mapToInt(Match::end)
                    .filter(e -> e <= t.start())
                    .max().orElse(-1);
            if (prevEnd < 0) continue;
            final int pe = prevEnd;
            var hasEpisodeAtPrevEnd = ctx.matches.snapshot().stream()
                    .filter(m -> !m.isPrivate())
                    .anyMatch(m -> m.end() == pe && m.name() == MatchName.EPISODE);
            if (hasEpisodeAtPrevEnd) {
                ctx.matches.replace(t, new Match(MatchName.EPISODE_TITLE, t.value(), t.start(), t.end(),
                        t.raw(), t.priority(), t.tags(), t.isPrivate()));
            }
        }
    }

    private void episodeTitleFromPosition(ParseContext ctx) {
        if (ctx.matches.named(MatchName.EPISODE_TITLE).findAny().isPresent()) return;
        var paths = ctx.markers.stream().filter(m -> m.name().equals("path")).toList();
        var titleExtractor = new TitleExtractor();
        var hasCrc = ctx.matches.named(MatchName.CRC32).findAny().isPresent();
        boolean isMovie = MOVIE_TYPE.equals(predictedType(ctx));
        for (var fp : Markers.markerSorted(paths, ctx.matches)) {
            if (extractEpisodeTitlesInFilepart(ctx, fp, titleExtractor, hasCrc, isMovie)) break;
        }
    }

    /** Returns true when at least one episode_title was added (callers should stop). */
    private boolean extractEpisodeTitlesInFilepart(ParseContext ctx, Marker fp,
                                                   TitleExtractor titleExtractor,
                                                   boolean hasCrc, boolean isMovie) {
        var hasTitle = ctx.matches.range(fp.start(), fp.end(), m -> m.name() == MatchName.TITLE).findAny().isPresent();
        if (!hasTitle) return false;
        var titles = titleExtractor.checkTitlesInFilepart(ctx, fp,
                TitleExtractor::isIgnored, MatchName.EPISODE_TITLE, List.of(TITLE), null, true);
        if (titles == null) return false;
        boolean addedAny = false;
        for (var t : titles.titles()) {
            if (shouldKeepEpisodeTitleCandidate(ctx, fp, t, hasCrc, isMovie)) {
                ctx.matches.add(t);
                addedAny = true;
            }
        }
        for (var r : titles.toRemove()) ctx.matches.remove(r);
        return addedAny;
    }

    private boolean shouldKeepEpisodeTitleCandidate(ParseContext ctx, Marker fp, Match t,
                                                    boolean hasCrc, boolean isMovie) {
        var prev = ctx.matches.previous(t, m -> PREVIOUS_NAMES.contains(m.name()));
        if (prev.isEmpty() && !hasCrc) return false;
        // Movie context: skip holes wedged between two structural properties.
        return !(isMovie && wedgedBetweenProperties(ctx, fp, t));
    }

    /**
     * True when both sides of a candidate hole within {@code filepart} are
     * bounded by trailing structural property matches (source / video_codec /
     * audio_codec / screen_size / etc.). Such holes are noise, not titles.
     */
    private static boolean wedgedBetweenProperties(ParseContext ctx, Marker filepart, Match candidate) {
        Set<MatchName> trailingNames = Set.of(MatchName.SOURCE, MatchName.VIDEO_CODEC, MatchName.AUDIO_CODEC,
                MatchName.SCREEN_SIZE, MatchName.AUDIO_CHANNELS, MatchName.AUDIO_PROFILE, MatchName.VIDEO_PROFILE,
                MatchName.STREAMING_SERVICE, MatchName.CONTAINER, MatchName.PART, MatchName.RELEASE_GROUP, MatchName.WEBSITE);
        boolean propBefore = ctx.matches.all().anyMatch(m -> trailingNames.contains(m.name())
                && m.start() >= filepart.start() && m.end() <= candidate.start());
        boolean propAfter = ctx.matches.all().anyMatch(m -> trailingNames.contains(m.name())
                && m.start() >= candidate.end() && m.end() <= filepart.end());
        return propBefore && propAfter;
    }

    /** Early type prediction; delegates to TypeProcessor (which runs later in PostPhase). */
    private static String predictedType(ParseContext ctx) {
        return io.guessit.rules.post.TypeProcessor.predictType(ctx);
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

    private void alternativeTitleReplace(ParseContext ctx) {
        if (ctx.matches.named(MatchName.EPISODE_TITLE).findAny().isPresent()) return;
        var alt = ctx.matches.named(MatchName.ALTERNATIVE_TITLE).findFirst().orElse(null);
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
        var hasCrc = ctx.matches.named(MatchName.CRC32).findAny().isPresent();
        if (prev.isPresent() || hasCrc) {
            var newTags = new java.util.HashSet<>(alt.tags());
            newTags.add("alternative-replaced");
            ctx.matches.replace(alt, new Match(MatchName.EPISODE_TITLE, alt.value(), alt.start(), alt.end(),
                    alt.raw(), alt.priority(), Set.copyOf(newTags), alt.isPrivate()));
        }
    }

    static void filepart3EpisodeTitleStatic(ParseContext ctx) {
        new EpisodeTitleExtractor().filepart3EpisodeTitle(ctx);
    }

    static void filepart2EpisodeTitleStatic(ParseContext ctx) {
        new EpisodeTitleExtractor().filepart2EpisodeTitle(ctx);
    }

    private void filepart3EpisodeTitle(ParseContext ctx) {
        if (ctx.matches.tagged(FILEPART_TITLE_TAG).findAny().isPresent()) return;
        var paths = Markers.named(ctx.markers, "path").toList();
        if (paths.size() < 3) return;
        var filename = paths.getLast();
        var directory = paths.get(paths.size() - 2);
        var subdirectory = paths.get(paths.size() - 3);
        if (ctx.matches.range(filename.start(), filename.end(), m -> m.name() == MatchName.EPISODE).findAny().isEmpty())
            return;
        if (ctx.matches.range(directory.start(), directory.end(), m -> m.name() == MatchName.SEASON).findAny().isEmpty())
            return;
        // Skip if filename already produced a title — the file gave the show
        // name, the subdir is generic ("/series/").
        var hasFilenameTitle = ctx.matches.named(MatchName.TITLE)
                .anyMatch(m -> m.start() >= filename.start() && m.end() <= filename.end());
        if (hasFilenameTitle) return;
        var h = findEpisodeTitleHoles(ctx, subdirectory);
        if (h == null) return;
        ctx.matches.add(new Match(MatchName.TITLE, h.value(), h.start, h.end, h.raw(), 1000, Set.of(), false));
    }

    private static Holes.Hole findEpisodeTitleHoles(ParseContext ctx, Marker subdirectory) {
        // Mirror python: a country/language match wrapped in (..)/[..] (e.g.
        // "(US)") has raw length 4 in python so isIgnored(False); the hole
        // splits at the bracket. Java's CountryExtractor stores raw="US"
        // (length 2), so isIgnored returns True and the hole swallows "US"
        // into the title (e.g. "The Office US"). Treat those as not-ignored
        // when bracket-wrapped.
        java.util.function.Predicate<Match> ignore = m -> {
            if (m.tags().contains("weak-episode")) return true;
            if (!TitleExtractor.isIgnored(m)) return false;
            return (m.name() != MatchName.COUNTRY && m.name() != MatchName.LANGUAGE)
                    || !isBracketWrapped(ctx.input, m);
        };
        var holes = Holes.compute(ctx.input, subdirectory.start(), subdirectory.end(),
                ctx.matches.snapshot(), ignore, Seps.TITLE_CHARS, Formatters::cleanup);
        if (holes.isEmpty()) return null;
        return holes.getFirst();
    }

    private static boolean isBracketWrapped(String input, Match m) {
        int s = m.start();
        int e = m.end();
        if (s <= 0 || e >= input.length()) return false;
        char before = input.charAt(s - 1);
        char after = input.charAt(e);
        return (before == '(' && after == ')') || (before == '[' && after == ']');
    }

    private void filepart2EpisodeTitle(ParseContext ctx) {
        if (ctx.matches.tagged(FILEPART_TITLE_TAG).findAny().isPresent()) return;
        var paths = Markers.named(ctx.markers, "path").toList();
        if (paths.size() < 2) return;
        var filename = paths.getLast();
        var directory = paths.get(paths.size() - 2);
        if (ctx.matches.range(filename.start(), filename.end(), m -> m.name() == MatchName.EPISODE).findAny().isEmpty())
            return;
        var hasSeason = ctx.matches.range(directory.start(), directory.end(), m -> m.name() == MatchName.SEASON).findAny().isPresent()
                || ctx.matches.range(filename.start(), filename.end(), m -> m.name() == MatchName.SEASON).findAny().isPresent();
        if (!hasSeason) return;
        var h = findEpisodeTitleHoles(ctx, directory);
        if (h == null) return;
        var tags = Set.of(FILEPART_TITLE_TAG);
        ctx.matches.add(new Match(MatchName.TITLE, h.value(), h.start, h.end, h.raw(), 1000, tags, false));
    }
}
