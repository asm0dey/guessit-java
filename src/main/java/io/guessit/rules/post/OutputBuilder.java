package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.GuessResultBuilder;
import io.guessit.util.Quantity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Assembles {@link ParseContext#result} from the surviving matches.
 *
 * <p>Matches are first grouped by name and ordered by start position, so that
 * properties expressed as lists (multiple seasons, multiple episodes,
 * multiple languages) preserve their input order. Each group is routed to a
 * typed setter on {@code GuessResultBuilder}; unrecognised names land in
 * {@code extras}.
 *
 * <p>Type coercion (string → int, raw value → {@code Language} /
 * {@code Country} / {@code Quantity} / {@code LocalDate}) and field shape
 * (scalar vs list) live in this single class, deliberately decoupled from
 * the extractors that produced the values.
 */
public final class OutputBuilder implements Consumer<ParseContext> {

    /**
     * Properties that always cascade: excluding the parent always excludes the child
     * (regardless of span), mirroring Python's child-match behaviour.
     */
    private static final Map<String, List<String>> CHILD_EXCLUSION = Map.of(
            "bonus", List.of("bonus_title"),
            "film", List.of("film_title"),
            "cd", List.of("cd_count")
    );

    /** Computed filter state for one accept() call. */
    private record FilterState(
            Set<String> excludes,
            List<String> includes,
            Set<String> droppedGroups,
            Set<MatchName> droppedNames,
            boolean dropCoexistEpisode,
            boolean dropCoexistSeason,
            boolean subFilteredKeepLang) {}

    @Override
    public void accept(ParseContext ctx) {
        var state = computeFilterState(ctx);
        var grouped = groupSurvivingMatches(ctx, state);
        var extras = dispatchToBuilder(ctx.resultBuilder, grouped);
        if (!extras.isEmpty()) ctx.resultBuilder.extras(extras);
        ctx.result = ctx.resultBuilder.build();
    }

    private record DroppedSet(Set<String> groups, Set<MatchName> names) {}

    /** Build excludes (with child cascade), includes, dropped coexist groups,
     *  and the language-promotion flag. */
    private static FilterState computeFilterState(ParseContext ctx) {
        var excludes = expandExcludesWithChildren(ctx.options.excludes());
        var includes = ctx.options.includes();

        // Coupled exclusion via the SxxExx pair tag ("coexist"): excluding one half
        // of a compact season/episode pattern must drop its sibling too.
        boolean dropCoexistEpisode = excludes.contains("season");
        boolean dropCoexistSeason = excludes.contains("episode");

        var dropped = collectDropped(ctx, excludes, includes);
        boolean subFilteredKeepLang = computeSubFilteredKeepLang(excludes, includes);

        return new FilterState(excludes, includes, dropped.groups(), dropped.names(),
                dropCoexistEpisode, dropCoexistSeason, subFilteredKeepLang);
    }

    private static Set<String> expandExcludesWithChildren(List<String> raw) {
        var out = new HashSet<>(raw);
        for (var ex : new HashSet<>(out)) {
            var children = CHILD_EXCLUSION.get(ex);
            if (children != null) out.addAll(children);
        }
        return out;
    }

    /** Collect coexist-group ids and dropped names. */
    private static DroppedSet collectDropped(ParseContext ctx, Set<String> excludes, List<String> includes) {
        var groups = new HashSet<String>();
        var names = new HashSet<MatchName>();
        ctx.matches.all().forEach(m -> {
            var name = m.name();
            var nameStr = name.name().toLowerCase();
            boolean filtered = (!excludes.isEmpty() && excludes.contains(nameStr))
                    || (!includes.isEmpty() && !includes.contains(nameStr));
            if (!filtered) return;
            names.add(name);
            for (var t : m.tags()) {
                if (t.startsWith("cg:")) groups.add(t);
            }
        });
        return new DroppedSet(groups, names);
    }

    /** Promote subtitle_language → language when subtitle_language is filtered
     *  out and language survives. */
    private static boolean computeSubFilteredKeepLang(Set<String> excludes, List<String> includes) {
        boolean langKept = (includes.isEmpty() || includes.contains(MatchName.LANGUAGE.name().toLowerCase()))
                && !excludes.contains(MatchName.LANGUAGE.name().toLowerCase());
        boolean subFiltered = excludes.contains(MatchName.SUBTITLE_LANGUAGE.name().toLowerCase())
                || (!includes.isEmpty() && !includes.contains(MatchName.SUBTITLE_LANGUAGE.name().toLowerCase()));
        return subFiltered && langKept;
    }

    /** Sort by start, apply filters/promotions, and group surviving matches by name. */
    private static Map<MatchName, List<Match>> groupSurvivingMatches(ParseContext ctx, FilterState s) {
        // LinkedHashMap preserves first-seen name order so `extras` is deterministic.
        var grouped = new LinkedHashMap<MatchName, List<Match>>();
        ctx.matches.all().sorted(Comparator.comparingInt(Match::start)).forEach(m0 -> {
            var m = maybePromoteSubtitleToLanguage(m0, s);
            if (isFiltered(m, s)) return;
            grouped.computeIfAbsent(m.name(), _ -> new ArrayList<>()).add(m);
        });
        return grouped;
    }

    /** Promote subtitle_language → language when latter is filtered out, except
     *  for "attached-affix" matches (e.g. "SubFR") which stay dropped. */
    private static Match maybePromoteSubtitleToLanguage(Match m, FilterState s) {
        if (!s.subFilteredKeepLang) return m;
        if (m.name() != MatchName.SUBTITLE_LANGUAGE) return m;
        if (m.tags().contains("attached-affix")) return m;
        return m.withName(MatchName.LANGUAGE);
    }

    /** Apply --excludes / --includes / coexist-pair / dropped-group / derivedFrom rules. */
    private static boolean isFiltered(Match m, FilterState s) {
        var name = m.name();
        var nameStr = name.name().toLowerCase();
        if (!s.excludes.isEmpty() && s.excludes.contains(nameStr)) return true;
        if (isFilteredByCoexist(m, name, s)) return true;
        if (!s.includes.isEmpty() && !s.includes.contains(nameStr)) return true;
        if (isInDroppedGroup(m, s)) return true;
        return isDerivedFromDropped(m, s);
    }

    private static boolean isFilteredByCoexist(Match m, MatchName name, FilterState s) {
        if (s.dropCoexistEpisode && name == MatchName.EPISODE && m.tags().contains("coexist")) return true;
        return s.dropCoexistSeason && name == MatchName.SEASON && m.tags().contains("coexist");
    }

    private static boolean isInDroppedGroup(Match m, FilterState s) {
        if (s.droppedGroups.isEmpty()) return false;
        for (var t : m.tags()) {
            if (s.droppedGroups.contains(t)) return true;
        }
        return false;
    }

    private static boolean isDerivedFromDropped(Match m, FilterState s) {
        if (s.droppedNames.isEmpty()) return false;
        for (var t : m.tags()) {
            if (!t.startsWith("derivedFrom:")) continue;
            try {
                var derivedName = MatchName.valueOf(t.substring(12).toUpperCase());
                if (s.droppedNames.contains(derivedName)) return true;
            } catch (IllegalArgumentException _) {
                // tag refers to a name no longer in the enum — treat as not derived
            }
        }
        return false;
    }

    /** Route grouped matches to typed builder setters; unknown names go to extras. */
    private static Map<String, Object> dispatchToBuilder(GuessResultBuilder b, Map<MatchName, List<Match>> grouped) {
        var extras = new LinkedHashMap<String, Object>();
        for (var e : grouped.entrySet()) {
            var ms = e.getValue();
            switch (e.getKey()) {
                case TITLE -> b.title(asString(ms.getFirst()));
                case ALTERNATIVE_TITLE -> b.alternativeTitleList(ms.stream().map(OutputBuilder::asString).toList());
                case YEAR -> b.year(asInt(ms.getFirst()));
                case DATE -> { if (ms.getFirst().value() instanceof LocalDate d) b.date(d); }
                case SEASON -> applyIntList(ms, b::season, b::seasonList);
                case EPISODE -> applyIntList(ms, b::episode, b::episodeList);
                case EPISODE_COUNT -> b.episodeCount(asInt(ms.getFirst()));
                case SEASON_COUNT -> b.seasonCount(asInt(ms.getFirst()));
                case EPISODE_TITLE -> b.episodeTitle(asString(ms.getFirst()));
                case EPISODE_FORMAT -> b.episodeFormat(asString(ms.getFirst()));
                case TYPE -> b.type(asString(ms.getFirst()));
                case LANGUAGE -> b.language(asLangList(ms));
                case SUBTITLE_LANGUAGE -> b.subtitleLanguage(asLangList(ms));
                case COUNTRY -> b.country(asCountryList(ms));
                case SOURCE -> applyStringList(ms, b::source, b::sourceList);
                case OTHER -> b.other(dedupedStringList(ms));
                case VIDEO_CODEC -> b.videoCodec(dedupedStringList(ms));
                case AUDIO_CODEC -> b.audioCodec(dedupedStringList(ms));
                case AUDIO_CHANNELS -> b.audioChannels(dedupedStringList(ms));
                case AUDIO_PROFILE -> b.audioProfile(dedupedStringList(ms));
                case VIDEO_PROFILE -> b.videoProfile(dedupedStringList(ms));
                case VIDEO_API -> b.videoApi(dedupedStringList(ms));
                case SCREEN_SIZE -> b.screenSize(asString(ms.getFirst()));
                case ASPECT_RATIO -> b.aspectRatio(asDouble(ms.getFirst()));
                case FRAME_RATE -> b.frameRate(asFrameRate(ms.getFirst()));
                case BIT_RATE -> b.bitRate((Quantity) ms.getFirst().value());
                case AUDIO_BIT_RATE -> b.audioBitRate((Quantity) ms.getFirst().value());
                case VIDEO_BIT_RATE -> b.videoBitRate((Quantity) ms.getFirst().value());
                case SIZE -> b.size((Quantity) ms.getFirst().value());
                case CONTAINER -> b.container(asString(ms.getFirst()));
                case MIMETYPE -> b.mimetype(asString(ms.getFirst()));
                case RELEASE_GROUP -> b.releaseGroup(asString(ms.getFirst()));
                case STREAMING_SERVICE -> b.streamingService(asString(ms.getFirst()));
                case WEBSITE -> b.website(asString(ms.getFirst()));
                case EDITION -> b.edition(dedupedStringList(ms));
                case CD -> b.cd(asInt(ms.getFirst()));
                case CD_COUNT -> b.cdCount(asInt(ms.getFirst()));
                case PART -> applyIntList(ms, b::part, b::partList);
                case VERSION -> b.version(asInt(ms.getFirst()));
                case FILM -> b.film(asInt(ms.getFirst()));
                case FILM_TITLE -> b.filmTitle(asString(ms.getFirst()));
                case BONUS -> b.bonus(asInt(ms.getFirst()));
                case BONUS_TITLE -> b.bonusTitle(asString(ms.getFirst()));
                case CRC32 -> b.crc32(asString(ms.getFirst()));
                case PROPER_COUNT -> b.properCount(asInt(ms.getFirst()));
                default -> extras.put(e.getKey().name().toLowerCase(), ms.size() == 1 ? ms.getFirst().value()
                    : ms.stream().map(Match::value).toList());
            }
        }
        return extras;
    }

    private static String asString(Match m) { return m.value() == null ? null : m.value().toString(); }
    private static Integer asInt(Match m) {
        var v = m.value();
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException _) { return null; }
        }
        return null;
    }

    private static List<String> dedupedStringList(List<Match> ms) { return ms.stream().map(OutputBuilder::asString).distinct().toList(); }
    private static String asFrameRate(Match m) {
        var v = m.value();
        if (v == null) return null;
        if (v instanceof String s && s.endsWith("fps")) return s;
        return v + "fps";
    }
    private static Double asDouble(Match m) {
        var v = m.value();
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException _) { return null; }
        }
        return null;
    }
    private static List<Language> asLangList(List<Match> ms) { return ms.stream().map(m -> (Language) m.value()).distinct().toList(); }
    private static List<Country> asCountryList(List<Match> ms) { return ms.stream().map(m -> (Country) m.value()).distinct().toList(); }
    /**
     * Routes a single integer match to the scalar setter and 2+ matches to the
     * list setter. Mirrors guessit's behaviour where a single episode appears
     * as {@code episode: 5} but multiple episodes appear as {@code episode: [5, 6]}.
     */
    private static void applyIntList(List<Match> ms, Consumer<Integer> single,
                                     Consumer<List<Integer>> list) {
        if (ms.size() == 1) {
            single.accept(asInt(ms.getFirst()));
            return;
        }
        var values = ms.stream().map(OutputBuilder::asInt).toList();
        var distinct = values.stream().distinct().toList();
        if (distinct.size() == 1) single.accept(distinct.getFirst());
        else list.accept(values);
    }

    private static void applyStringList(List<Match> ms, Consumer<String> single,
                                        Consumer<List<String>> list) {
        if (ms.size() == 1) {
            single.accept(asString(ms.getFirst()));
            return;
        }
        var values = ms.stream().map(OutputBuilder::asString).toList();
        var distinct = values.stream().distinct().toList();
        if (distinct.size() == 1) single.accept(distinct.getFirst());
        else list.accept(values);
    }
}
