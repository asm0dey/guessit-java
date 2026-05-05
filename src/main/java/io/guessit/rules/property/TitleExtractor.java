package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Port of Python {@code rules/properties/title.py}: emits {@code title} and
 * {@code alternative_title} matches.
 *
 * <p>Three sub-rules:
 * <ul>
 *   <li><b>expected_title functional</b> in {@link #extract} — emits a {@code title}
 *       match for each {@code Options#expectedTitle} substring found in the input.</li>
 *   <li><b>TitleFromPosition</b> in {@link #postProcess} — for the highest-scoring path
 *       marker, takes the cleaned hole as a {@code title} match. Splits the hole on
 *       {@link Seps#TITLE_CHARS} to produce {@code alternative_title} matches. Routes
 *       inner-filepart titles to {@code episode_title} when an outer "Show/Season N"
 *       filepart shape is detected.</li>
 *   <li><b>PreferTitleWithYear</b> in {@link #postProcess} — prefers titles in the
 *       filepart containing a year; drops the others.</li>
 * </ul>
 */
public final class TitleExtractor implements Extractor {
    static final Set<String> NON_SPECIFIC_LANGUAGES = Set.of("mul", "und");
    public static final String TITLE = "title";

    @Override
    public String name() { return TITLE; }

    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedTitle();
        if (expected.isEmpty()) {
            // Fall back to expected_title list from options.json so defaults
            // like "This is Us" / "OSS 117" are applied automatically.
            expected = ctx.config.topLevelList("expected_title");
        }
        if (expected.isEmpty()) return;
        var input = ctx.input;
        // Mirror python rules/common/expected.py: normalize seps in both input
        // and search to a single space before substring scanning, so a search
        // like "An Anime Show 100" matches "An_Anime_Show_100" in the input.
        // Spans stay valid because replacement is 1:1 by char.
        var normalizedInput = normalizeSeps(input);
        var sepsSurround = Validators.sepsSurround(input);
        for (var entry : ExpectedTitleRegex.parse(expected)) {
            if (entry.literalReplacement() != null) {
                // Literal entry: use original substring-scan behaviour (sep-normalised,
                // case-insensitive) so "Show Name" still matches "Show.Name".
                var search = normalizeSeps(entry.literalReplacement());
                var lcInput = normalizedInput.toLowerCase(java.util.Locale.ROOT);
                var lcSearch = search.toLowerCase(java.util.Locale.ROOT);
                int idx = 0;
                while ((idx = lcInput.indexOf(lcSearch, idx)) >= 0) {
                    var raw = input.substring(idx, idx + search.length());
                    var formatted = Formatters.titleText(raw);
                    var m = new Match(TITLE, formatted, idx, idx + search.length(), raw,
                        1000, Set.of("expected", TITLE), false);
                    if (sepsSurround.test(m)) ctx.matches.add(m);
                    idx += search.length();
                }
            } else {
                // Regex entry (re: prefix): match against the sep-normalised input so
                // word-boundary patterns work consistently, but preserve spans into
                // the original input.
                var matcher = entry.pattern().matcher(normalizedInput);
                while (matcher.find()) {
                    var raw = input.substring(matcher.start(), matcher.end());
                    var formatted = Formatters.titleText(raw);
                    var m = new Match(TITLE, formatted, matcher.start(), matcher.end(), raw,
                        1000, Set.of("expected", TITLE), false);
                    if (sepsSurround.test(m)) ctx.matches.add(m);
                }
            }
        }
    }

    private static String normalizeSeps(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            sb.append(Seps.isSep(c) ? ' ' : c);
        }
        return sb.toString();
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var hasExpected = ctx.matches.named(TITLE).anyMatch(m -> m.tags().contains("expected"));
        if (!hasExpected) {
            // Mirror python: Filepart3/2EpisodeTitle seed a title at the
            // outer/subdir hole BEFORE TitleFromPosition runs. Without this,
            // titleFromPosition would pick the directory's first hole as
            // title and leave the filename without one — preventing
            // EpisodeTitleExtractor.episodeTitleFromPosition from finding
            // the post-episode hole as episode_title (e.g. "Psy Vs Psy" in
            // "Psych.S02E03.Psy.Vs.Psy.Français.srt").
            EpisodeTitleExtractor.filepart3EpisodeTitleStatic(ctx);
            EpisodeTitleExtractor.filepart2EpisodeTitleStatic(ctx);
            titleFromPosition(ctx);
        }
        preferTitleWithYear(ctx);
    }

    private void titleFromPosition(ParseContext ctx) {
        var paths = ctx.markers.stream().filter(m -> "path".equals(m.name())).toList();
        if (paths.isEmpty()) return;
        var sorted = Markers.markerSorted(paths, ctx.matches);

        var serieNameFilepart = serieNameFilepart(ctx, paths);
        var toAppend = new ArrayList<Match>();
        var toRemove = new ArrayList<Match>();
        boolean filenameProvidesTitle = false;
        if (serieNameFilepart != null) {
            int holeCount = countUsableHoles(ctx, serieNameFilepart, this::serieNameIgnored);
            var titles = checkTitlesInFilepart(ctx, serieNameFilepart, this::serieNameIgnored);
            if (holeCount >= 2) {
                // Filename has 2+ title-eligible holes around episode: title comes from
                // the filename, not the outer dir (mirrors python rebulk behaviour).
                if (titles != null && !titles.titles.isEmpty()) {
                    var first = titles.titles.getFirst();
                    toAppend.add(new Match(TITLE, first.value(), first.start(), first.end(),
                        first.raw(), first.priority(), Set.of(TITLE, "filepart-title"), false));
                    for (int i = 1; i < titles.titles.size(); i++) {
                        var t = titles.titles.get(i);
                        toAppend.add(new Match("episode_title", t.value(), t.start(), t.end(),
                            t.raw(), t.priority(), Set.of(TITLE), false));
                    }
                    toRemove.addAll(titles.toRemove);
                    filenameProvidesTitle = true;
                }
            } else {
                if (titles != null && titles.titles.size() == 1) {
                    // Mirror python: filename hole positioned BEFORE the episode
                    // marker is the show title (e.g. "Show-E01.mkv"); a hole
                    // AFTER the episode marker is the episode title (e.g.
                    // "E01-episode title.mkv"). Without this split, both shapes
                    // emit episode_title, leaving the show title from an outer
                    // generic dir like "Some Dummy Directory" instead of the
                    // filename's own "Some Series".
                    var ep = ctx.matches.range(serieNameFilepart.start(), serieNameFilepart.end(),
                            m -> "episode".equals(m.name())).findFirst().orElse(null);
                    var t = titles.titles.getFirst();
                    var holeBeforeEpisode = ep != null && t.end() <= ep.start();
                    if (holeBeforeEpisode) {
                        toAppend.add(new Match(TITLE, t.value(), t.start(), t.end(),
                            t.raw(), t.priority(), Set.of(TITLE, "filepart-title"), false));
                        filenameProvidesTitle = true;
                    } else {
                        toAppend.add(new Match("episode_title", t.value(), t.start(), t.end(),
                            t.raw(), t.priority(), Set.of(TITLE), false));
                    }
                    toRemove.addAll(titles.toRemove);
                }
            }
        }

        var yearFileparts = paths.stream()
            .filter(fp -> ctx.matches.range(fp.start(), fp.end(), m -> "year".equals(m.name())).findAny().isPresent())
            .toList();
        var consumedYearFileparts = new HashSet<Marker>();

        if (!filenameProvidesTitle) {
            for (var fp : sorted) {
                if (fp == serieNameFilepart) continue;
                consumedYearFileparts.add(fp);
                var titles = checkTitlesInFilepart(ctx, fp, _ -> false);
                if (titles == null) continue;
                toAppend.addAll(titles.titles);
                toRemove.addAll(titles.toRemove);
                break;
            }
        }

        for (var fp : yearFileparts) {
            if (consumedYearFileparts.contains(fp)) continue;
            var titles = checkTitlesInFilepart(ctx, fp, _ -> false);
            if (titles == null) continue;
            toAppend.addAll(titles.titles);
            toRemove.addAll(titles.toRemove);
        }

        for (var r : toRemove) ctx.matches.remove(r);
        for (var t : toAppend) ctx.matches.add(t);
    }

    private void preferTitleWithYear(ParseContext ctx) {
        var titles = ctx.matches.named(TITLE).toList();
        if (titles.isEmpty()) return;
        var withYearInGroup = new ArrayList<Match>();
        var withYear = new ArrayList<Match>();
        for (var t : titles) {
            var fp = Markers.atMatch(ctx.markers, t, m -> "path".equals(m.name())).orElse(null);
            if (fp == null) continue;
            var year = ctx.matches.range(fp.start(), fp.end(), m -> "year".equals(m.name())).findFirst().orElse(null);
            if (year == null) continue;
            var inGroup = Markers.atMatch(ctx.markers, year, m -> "group".equals(m.name())).isPresent();
            (inGroup ? withYearInGroup : withYear).add(t);
        }
        Set<Object> keepValues;
        if (!withYearInGroup.isEmpty()) keepValues = withYearInGroup.stream().map(Match::value).collect(java.util.stream.Collectors.toSet());
        else if (!withYear.isEmpty()) keepValues = withYear.stream().map(Match::value).collect(java.util.stream.Collectors.toSet());
        else return;
        for (var t : titles) {
            if (!keepValues.contains(t.value())) {
                ctx.matches.remove(t);
            } else if (!t.tags().contains("equivalent-ignore")) {
                // Mirror python PreferTitleWithYear AppendTags: surviving
                // titles get "equivalent-ignore" so EquivalentHoles doesn't
                // overwrite their better-cased outer-folder value with a
                // titlecased filename hole (e.g. "Comme une Image" must not
                // be replaced by "Comme Une Image" from inner "Comme.Une.Image").
                var withTag = new HashSet<>(t.tags());
                withTag.add("equivalent-ignore");
                ctx.matches.replace(t, new Match(t.name(), t.value(),
                    t.start(), t.end(), t.raw(), t.priority(), withTag, t.isPrivate()));
            }
        }
    }

    private int countUsableHoles(ParseContext ctx, Marker filepart,
                                  java.util.function.Predicate<Match> additionalIgnore) {
        java.util.function.Predicate<Match> ignore = m ->
            isIgnored(m) || (additionalIgnore != null && additionalIgnore.test(m));
        var holes = Holes.compute(ctx.input, filepart.start(), filepart.end(),
            ctx.matches.snapshot(), ignore, null, Formatters::titleText);
        holes = holesProcess(ctx, holes);
        int n = 0;
        for (var h : holes) {
            if (h != null && !h.isEmpty() && !h.value().isEmpty()) n++;
        }
        return n;
    }

    private boolean serieNameIgnored(Match m) {
        for (var tag : m.tags()) {
            if ("weak".equals(tag) || tag.startsWith("weak-")) return true;
        }
        return false;
    }

    private Marker serieNameFilepart(ParseContext ctx, List<Marker> fileparts) {
        for (var index = 1; index < fileparts.size() - 1; index++) {
            var fp = fileparts.get(index);
            var inFp = ctx.matches.range(fp.start(), fp.end(), m -> !m.isPrivate()).toList();
            if (inFp.size() == 1 && "season".equals(inFp.getFirst().name())
                    && spansFilepartIgnoringSeps(inFp.getFirst(), fp, ctx.input)) {
                return fileparts.get(index + 1);
            }
            // The season head match is now private; check ALL matches (including private)
            // for a full-span season head.
            var allInFp = ctx.matches.range(fp.start(), fp.end(), _ -> true).toList();
            var seasonHeads = allInFp.stream().filter(m -> "season".equals(m.name()) && m.value() == null
                && spansFilepartIgnoringSeps(m, fp, ctx.input)).toList();
            if (seasonHeads.size() == 1) {
                return fileparts.get(index + 1);
            }
        }
        return null;
    }

    /** True when {@code m} occupies {@code fp} except for separator padding —
     *  mirrors python's parent.span match-or-equals tolerance. */
    private static boolean spansFilepartIgnoringSeps(Match m, Marker fp, String input) {
        if (m.start() < fp.start() || m.end() > fp.end()) return false;
        for (int i = fp.start(); i < m.start(); i++) if (!Seps.isSep(input.charAt(i))) return false;
        for (int i = m.end(); i < fp.end(); i++) if (!Seps.isSep(input.charAt(i))) return false;
        return true;
    }

    record TitlesInFilepart(List<Match> titles, List<Match> toRemove) {}

    /** Returns null when no usable hole was found. */
    TitlesInFilepart checkTitlesInFilepart(ParseContext ctx, Marker filepart,
                                            java.util.function.Predicate<Match> additionalIgnore) {
        var ignore = (java.util.function.Predicate<Match>) m ->
            isIgnored(m) || (additionalIgnore != null && additionalIgnore.test(m));
        return checkTitlesInFilepart(ctx, filepart, ignore, TITLE, List.of(TITLE), "alternative_title", false);
    }

    /**
     * Shared implementation used by EpisodeTitleExtractor too; emits {@code matchName}-named
     * matches and (when {@code alternativeMatchName != null}) splits the hole on title_seps
     * to spawn alternative-title matches.
     */
    TitlesInFilepart checkTitlesInFilepart(ParseContext ctx, Marker filepart,
                                           java.util.function.Predicate<Match> ignore,
                                           String matchName, List<String> matchTags,
                                           String alternativeMatchName,
                                           boolean episodeTitleContext) {
        var holes = computeProcessedHoles(ctx, filepart, ignore);

        for (var hole : holes) {
            if (hole == null) continue;

            var adjustedHole = adjustHoleAndCollectMatches(ctx, filepart, hole, episodeTitleContext);
            if (adjustedHole.hole().length() <= 0 || adjustedHole.hole().value().isEmpty()) continue;

            var titles = createTitleMatches(ctx, adjustedHole.hole(), matchName, matchTags, alternativeMatchName);
            if (titles == null) continue;

            return new TitlesInFilepart(titles, adjustedHole.toRemove());
        }
        return null;
    }

    private List<Holes.Hole> computeProcessedHoles(ParseContext ctx, Marker filepart,
                                                   java.util.function.Predicate<Match> ignore) {
        var allMatches = ctx.matches.snapshot();
        var holes = Holes.compute(ctx.input, filepart.start(), filepart.end(),
                allMatches, ignore, null, Formatters::titleText);
        return holesProcess(ctx, holes);
    }

    private record AdjustedHoleResult(Holes.Hole hole, List<Match> toRemove) {
    }

    private AdjustedHoleResult adjustHoleAndCollectMatches(ParseContext ctx, Marker filepart,
                                                           Holes.Hole hole, boolean episodeTitleContext) {
        var toRemove = new ArrayList<Match>();
        var toKeep = new ArrayList<Match>();
        var ignoredInHole = ctx.matches.range(hole.start, hole.end, TitleExtractor::isIgnored).toList();

        if (!ignoredInHole.isEmpty()) {
            adjustHoleBoundaries(ctx, filepart, hole, ignoredInHole, toKeep);
            collectMatchesToRemove(ctx, hole, ignoredInHole, toKeep, toRemove, episodeTitleContext);
        }

        return new AdjustedHoleResult(hole, toRemove);
    }

    private void adjustHoleBoundaries(ParseContext ctx, Marker filepart, Holes.Hole hole,
                                      List<Match> ignoredInHole, List<Match> toKeep) {
        // Process trailing matches (reversed)
        var reversed = new ArrayList<>(ignoredInHole).reversed();
        for (var m : reversed) {
            var trailing = ctx.matches.chainBefore(hole.end, ctx.input, Seps.CHARS, x -> x == m).orElse(null);
            if (trailing != null && shouldKeep(m, toKeep, ctx, filepart, hole, false)) {
                toKeep.add(m);
                hole.end = m.start();
            }
        }

        // Process starting matches
        for (var m : ignoredInHole) {
            if (toKeep.contains(m)) continue;
            var starting = ctx.matches.chainAfter(hole.start, ctx.input, Seps.CHARS, x -> x == m).orElse(null);
            if (starting != null && shouldKeep(m, toKeep, ctx, filepart, hole, true)) {
                toKeep.add(m);
                hole.start = m.end();
            }
        }
    }

    private void collectMatchesToRemove(ParseContext ctx, Holes.Hole hole, List<Match> ignoredInHole,
                                        List<Match> toKeep, List<Match> toRemove, boolean episodeTitleContext) {
        for (var m : ignoredInHole) {
            if (shouldRemove(m, ctx, hole, episodeTitleContext)) {
                toRemove.add(m);
            }
        }
        toRemove.removeAll(toKeep);
    }

    private List<Match> createTitleMatches(ParseContext ctx, Holes.Hole hole, String matchName,
                                           List<String> matchTags, String alternativeMatchName) {
        if (isRedundantSeasonWord(hole.value(), ctx)) return null;

        var titles = new ArrayList<Match>();
        titles.add(new Match(matchName, hole.value(), hole.start, hole.end, hole.raw(),
                1000, Set.copyOf(matchTags), false));

        if (alternativeMatchName != null) {
            var split = splitAndMergeHyphenatedWords(hole, ctx.input);
            return processSplitTitles(split, hole, matchName, matchTags, alternativeMatchName, ctx, titles);
        }

        return titles;
    }

    private List<Holes.Hole> splitAndMergeHyphenatedWords(Holes.Hole hole, String input) {
        var split = hole.split(Seps.TITLE_CHARS);
        if (split.size() <= 1) return split;

        var merged = new ArrayList<Holes.Hole>();
        merged.add(split.getFirst());

        for (int i = 1; i < split.size(); i++) {
            var prev = merged.getLast();
            var cur = split.get(i);

            if (isHyphenatedCompound(prev, cur, input)) {
                prev.end = cur.end;
            } else {
                merged.add(cur);
            }
        }
        return merged;
    }

    private boolean isHyphenatedCompound(Holes.Hole prev, Holes.Hole cur, String input) {
        var sep = input.substring(prev.end, cur.start);
        var prevRaw = prev.raw();
        var curRaw = cur.raw();

        return sep.length() == 1 && sep.charAt(0) == '-'
                && !prevRaw.isEmpty() && !Seps.isSep(prevRaw.charAt(prevRaw.length() - 1))
                && !curRaw.isEmpty() && !Seps.isSep(curRaw.charAt(0));
    }

    private List<Match> processSplitTitles(List<Holes.Hole> split, Holes.Hole originalHole,
                                           String matchName, List<String> matchTags,
                                           String alternativeMatchName, ParseContext ctx,
                                           List<Match> titles) {
        if (split.size() > 1) {
            return createMultipleTitleMatches(split, matchName, matchTags, alternativeMatchName, ctx);
        } else if (split.size() == 1 && !split.getFirst().equals(originalHole)) {
            return createSingleAdjustedMatch(split.getFirst(), matchName, matchTags);
        }
        return titles;
    }

    private List<Match> createMultipleTitleMatches(List<Holes.Hole> split, String matchName,
                                                   List<String> matchTags, String alternativeMatchName,
                                                   ParseContext ctx) {
        var titles = new ArrayList<Match>();
        var first = split.getFirst();
        titles.add(new Match(matchName, first.value(), first.start, first.end,
                first.raw(), 1000, Set.copyOf(matchTags), false));

        for (var i = 1; i < split.size(); i++) {
            var s = split.get(i);
            if (isRedundantSeasonWord(s.value(), ctx)) continue;
            titles.add(new Match(alternativeMatchName, s.value(), s.start, s.end, s.raw(),
                1000, Set.of(TITLE), false));
        }
        return titles;
    }
    
    private List<Match> createSingleAdjustedMatch(Holes.Hole hole, String matchName, List<String> matchTags) {
        var titles = new ArrayList<Match>();
        titles.add(new Match(matchName, hole.value(), hole.start, hole.end,
            hole.raw(), 1000, Set.copyOf(matchTags), false));
        return titles;
    }

    private List<Holes.Hole> holesProcess(ParseContext ctx, List<Holes.Hole> holes) {
        var groupMarkers = new ArrayList<>(Markers.named(ctx.markers, "group").toList());
        var iter = groupMarkers.iterator();
        while (iter.hasNext()) {
            var g = iter.next();
            var path = Markers.atMatch(ctx.markers, Match.of("g", null, g.start(), g.end(), g.raw()),
                m -> "path".equals(m.name())).orElse(null);
            // Mirror python title.holes_process: skip groups whose span equals
            // the enclosing filepart. Java's GroupMarker excludes the bracket
            // chars (s+1, e-1) while PathMarker includes them, so also accept
            // the "group + brackets == path" case.
            if (path != null
                && ((path.start() == g.start() && path.end() == g.end())
                    || (path.start() == g.start() - 1 && path.end() == g.end() + 1))) {
                iter.remove();
            }
        }
        var ret = new ArrayList<Holes.Hole>();
        for (var h : holes) ret.addAll(h.crop(groupMarkers));
        return ret;
    }

    private static final java.util.regex.Pattern SEASON_WORD_PATTERN = java.util.regex.Pattern.compile(
        "(?i)^(?:season|seasons|saison|saisons|seizoen|serie|series|temp|temporada|temporadas|"
        + "staffel|staffeln|stagione|stagioni)[ ._-]*(\\d+)$");

    /**
     * True when {@code value} is a season-word followed by a number that already
     * matches an existing season match. Used to drop dangling alt-title /
     * episode-title splits that just restate the season (e.g. "Temporada 4"
     * paired with a Cap.408 SxxExx match).
     */
    private static boolean isRedundantSeasonWord(String value, ParseContext ctx) {
        if (value == null || value.isEmpty()) return false;
        var m = SEASON_WORD_PATTERN.matcher(value.trim());
        if (!m.matches()) return false;
        int n;
        try { n = Integer.parseInt(m.group(1)); } catch (NumberFormatException _) { return false; }
        return ctx.matches.named("season")
            .anyMatch(s -> Integer.valueOf(n).equals(s.value()));
    }

    static boolean isIgnored(Match m) {
        if (!Set.of("language", "country", "episode_details").contains(m.name())) return false;
        var raw = m.raw();
        if (raw == null) return true;
        var upper = raw.equals(raw.toUpperCase(java.util.Locale.ROOT))
            && raw.chars().anyMatch(Character::isLetter);
        return !(raw.length() > 3 && upper);
    }

    private boolean shouldKeep(Match m, List<Match> toKeep, ParseContext ctx, Marker filepart,
                               Holes.Hole hole, boolean starting) {
        if (Set.of("language", "country").contains(m.name())) {
            if (hole.value().length() == m.raw().length()) return true;
            var others = ctx.matches.range(filepart.start(), filepart.end(),
                x -> x.name().equals(m.name()) && !toKeep.contains(x)
                    && !NON_SPECIFIC_LANGUAGES.contains(String.valueOf(x.value()))
                    && (x.end() <= hole.start || x.start() >= hole.end));
            return others.findAny().isEmpty() && (!starting || m.raw().length() <= 3);
        }
        return false;
    }

    private boolean shouldRemove(Match m, ParseContext ctx, Holes.Hole hole, boolean episodeTitleContext) {
        if ("episode_details".equals(m.name())) {
            if (episodeTitleContext) return false;
            if ("episode".equals(ctx.options.type())) {
                return m.start() >= hole.start && m.end() <= hole.end;
            }
        }
        // In episode-title context (computing the trailing title), keep
        // language/country matches: removing them produces an episode_title
        // that swallows "ENG - sub" etc. and loses the language info entirely.
        return !episodeTitleContext || !Set.of("language", "country").contains(m.name());
    }
}
