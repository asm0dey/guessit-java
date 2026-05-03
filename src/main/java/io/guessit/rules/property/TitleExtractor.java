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
        if (expected.isEmpty()) return;
        var input = ctx.input;
        // Mirror python rules/common/expected.py: normalize seps in both input
        // and search to a single space before substring scanning, so a search
        // like "An Anime Show 100" matches "An_Anime_Show_100" in the input.
        // Spans stay valid because replacement is 1:1 by char.
        var normalizedInput = normalizeSeps(input);
        var sepsSurround = Validators.sepsSurround(input);
        for (var word : expected) {
            var search = normalizeSeps(word);
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
            if (holeCount >= 2) {
                // Filename has 2+ title-eligible holes around episode: title comes from
                // the filename, not the outer dir (mirrors python rebulk behaviour).
                var titles = checkTitlesInFilepart(ctx, serieNameFilepart, this::serieNameIgnored);
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
                var titles = checkTitlesInFilepart(ctx, serieNameFilepart, this::serieNameIgnored);
                if (titles != null && titles.titles.size() == 1) {
                    for (var t : titles.titles) {
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
        for (var t : titles) if (!keepValues.contains(t.value())) ctx.matches.remove(t);
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
                    && inFp.getFirst().start() == fp.start() && inFp.getFirst().end() == fp.end()) {
                return fileparts.get(index + 1);
            }
            // The season head match is now private; check ALL matches (including private)
            // for a full-span season head.
            var allInFp = ctx.matches.range(fp.start(), fp.end(), _ -> true).toList();
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
                var reversed = new ArrayList<>(ignoredInHole).reversed();
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
                // Mirrors Python title.py: re-merge adjacent splits joined by a
                // bare '-' with non-sep on each side (e.g. "Ant-Man") so the
                // hyphen-compound stays in title rather than spawning altTitle.
                if (split.size() > 1) {
                    var merged = new ArrayList<Holes.Hole>();
                    merged.add(split.getFirst());
                    for (int i = 1; i < split.size(); i++) {
                        var prev = merged.getLast();
                        var cur = split.get(i);
                        var sep = ctx.input.substring(prev.end, cur.start);
                        var prevRaw = prev.raw();
                        var curRaw = cur.raw();
                        if (sep.length() == 1 && sep.charAt(0) == '-'
                            && !prevRaw.isEmpty() && !Seps.isSep(prevRaw.charAt(prevRaw.length() - 1))
                            && !curRaw.isEmpty() && !Seps.isSep(curRaw.charAt(0))) {
                            prev.end = cur.end;
                        } else {
                            merged.add(cur);
                        }
                    }
                    split = merged;
                }
                if (split.size() > 1) {
                    titles.clear();
                    titles.add(new Match(matchName, split.getFirst().value(), split.getFirst().start, split.getFirst().end,
                        split.getFirst().raw(), 1000, Set.copyOf(matchTags), false));
                    for (var i = 1; i < split.size(); i++) {
                        var s = split.get(i);
                        titles.add(new Match(alternativeMatchName, s.value(), s.start, s.end, s.raw(),
                            1000, Set.of(TITLE), false));
                    }
                } else if (split.size() == 1 && (split.getFirst().start != hole.start || split.getFirst().end != hole.end)) {
                    titles.clear();
                    titles.add(new Match(matchName, split.getFirst().value(), split.getFirst().start, split.getFirst().end,
                        split.getFirst().raw(), 1000, Set.copyOf(matchTags), false));
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
