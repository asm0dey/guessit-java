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

    @Override
    public String name() { return "title"; }

    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedTitle();
        if (expected.isEmpty()) return;
        var input = ctx.input;
        var sepsSurround = Validators.sepsSurround(input);
        for (var word : expected) {
            int idx = 0;
            while ((idx = input.indexOf(word, idx)) >= 0) {
                var raw = input.substring(idx, idx + word.length());
                var formatted = Formatters.titleText(raw);
                var m = new Match("title", formatted, idx, idx + word.length(), raw,
                    1000, Set.of("expected", "title"), false);
                if (sepsSurround.test(m)) ctx.matches.add(m);
                idx += word.length();
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var hasExpected = ctx.matches.named("title").anyMatch(m -> m.tags().contains("expected"));
        if (!hasExpected) {
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
        if (serieNameFilepart != null) {
            var titles = checkTitlesInFilepart(ctx, serieNameFilepart, this::serieNameIgnored);
            if (titles != null && titles.titles.size() == 1) {
                for (var t : titles.titles) {
                    toAppend.add(new Match("episode_title", t.value(), t.start(), t.end(),
                        t.raw(), t.priority(), Set.of("title"), false));
                }
                toRemove.addAll(titles.toRemove);
            }
        }

        var yearFileparts = paths.stream()
            .filter(fp -> ctx.matches.range(fp.start(), fp.end(), m -> "year".equals(m.name())).findAny().isPresent())
            .toList();
        var consumedYearFileparts = new HashSet<Marker>();

        for (var fp : sorted) {
            if (fp == serieNameFilepart) continue;
            consumedYearFileparts.add(fp);
            var titles = checkTitlesInFilepart(ctx, fp, m -> false);
            if (titles == null) continue;
            toAppend.addAll(titles.titles);
            toRemove.addAll(titles.toRemove);
            break;
        }

        for (var fp : yearFileparts) {
            if (consumedYearFileparts.contains(fp)) continue;
            var titles = checkTitlesInFilepart(ctx, fp, m -> false);
            if (titles == null) continue;
            toAppend.addAll(titles.titles);
            toRemove.addAll(titles.toRemove);
        }

        for (var r : toRemove) ctx.matches.remove(r);
        for (var t : toAppend) ctx.matches.add(t);
    }

    private void preferTitleWithYear(ParseContext ctx) {
        var titles = ctx.matches.named("title").toList();
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
        for (var t : titles) if (!keepValues.contains(t.value())) ctx.matches.remove(t);
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
            if (inFp.size() == 1 && "season".equals(inFp.get(0).name())
                    && inFp.get(0).start() == fp.start() && inFp.get(0).end() == fp.end()) {
                return fileparts.get(index + 1);
            }
            // The season head match is now private; check ALL matches (including private)
            // for a full-span season head.
            var allInFp = ctx.matches.range(fp.start(), fp.end(), m -> true).toList();
            var seasonHeads = allInFp.stream().filter(m -> "season".equals(m.name()) && m.value() == null
                && m.start() == fp.start() && m.end() == fp.end()).toList();
            if (seasonHeads.size() == 1) {
                return fileparts.get(index + 1);
            }
        }
        return null;
    }

    record TitlesInFilepart(List<Match> titles, List<Match> toRemove) {}

    /** Returns null when no usable hole was found. */
    TitlesInFilepart checkTitlesInFilepart(ParseContext ctx, Marker filepart,
                                            java.util.function.Predicate<Match> additionalIgnore) {
        var ignore = (java.util.function.Predicate<Match>) m ->
            isIgnored(m) || (additionalIgnore != null && additionalIgnore.test(m));
        return checkTitlesInFilepart(ctx, filepart, ignore, "title", List.of("title"), "alternative_title", false);
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
        var allMatches = ctx.matches.snapshot();
        var holes = Holes.compute(ctx.input, filepart.start(), filepart.end(),
            allMatches, ignore, null, Formatters::titleText);
        holes = holesProcess(ctx, holes);

        for (var hole : holes) {
            if (hole == null) continue;
            var toRemove = new ArrayList<Match>();
            var toKeep = new ArrayList<Match>();
            var ignoredInHole = ctx.matches.range(hole.start, hole.end, TitleExtractor::isIgnored).toList();
            if (!ignoredInHole.isEmpty()) {
                var reversed = new ArrayList<>(ignoredInHole);
                java.util.Collections.reverse(reversed);
                for (var m : reversed) {
                    var trailing = ctx.matches.chainBefore(hole.end, ctx.input, Seps.CHARS, x -> x == m).orElse(null);
                    if (trailing != null && shouldKeep(m, toKeep, ctx, filepart, hole, false)) {
                        toKeep.add(m);
                        hole.end = m.start();
                    }
                }
                for (var m : ignoredInHole) {
                    if (toKeep.contains(m)) continue;
                    var starting = ctx.matches.chainAfter(hole.start, ctx.input, Seps.CHARS, x -> x == m).orElse(null);
                    if (starting != null && shouldKeep(m, toKeep, ctx, filepart, hole, true)) {
                        toKeep.add(m);
                        hole.start = m.end();
                    }
                }
            }
            for (var m : ignoredInHole) {
                if (shouldRemove(m, ctx, hole, episodeTitleContext)) toRemove.add(m);
            }
            toRemove.removeAll(toKeep);

            if (hole.length() <= 0 || hole.value().isEmpty()) continue;

            var titles = new ArrayList<Match>();
            var raw = hole.raw();
            var value = hole.value();
            titles.add(new Match(matchName, value, hole.start, hole.end, raw, 1000, Set.copyOf(matchTags), false));

            if (alternativeMatchName != null) {
                var split = hole.split(Seps.TITLE_CHARS);
                if (split.size() > 1) {
                    titles.clear();
                    titles.add(new Match(matchName, split.get(0).value(), split.get(0).start, split.get(0).end,
                        split.get(0).raw(), 1000, Set.copyOf(matchTags), false));
                    for (var i = 1; i < split.size(); i++) {
                        var s = split.get(i);
                        titles.add(new Match(alternativeMatchName, s.value(), s.start, s.end, s.raw(),
                            1000, Set.of("title"), false));
                    }
                }
            }
            return new TitlesInFilepart(titles, toRemove);
        }
        return null;
    }

    private List<Holes.Hole> holesProcess(ParseContext ctx, List<Holes.Hole> holes) {
        var groupMarkers = new ArrayList<>(Markers.named(ctx.markers, "group").toList());
        var iter = groupMarkers.iterator();
        while (iter.hasNext()) {
            var g = iter.next();
            var path = Markers.atMatch(ctx.markers, Match.of("g", null, g.start(), g.end(), g.raw()),
                m -> "path".equals(m.name())).orElse(null);
            if (path != null && path.start() == g.start() && path.end() == g.end()) iter.remove();
        }
        var ret = new ArrayList<Holes.Hole>();
        for (var h : holes) ret.addAll(h.crop(groupMarkers));
        return ret;
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
        return true;
    }
}
