package io.guessit.rules.post;

import io.guessit.engine.Match;
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
            Set<String> droppedNames,
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

    /** Build excludes (with child cascade), includes, dropped coexist groups,
     *  and the language-promotion flag. */
    private static FilterState computeFilterState(ParseContext ctx) {
        var excludes = new HashSet<>(ctx.options.excludes());
        for (var ex : new HashSet<>(excludes)) {
            var children = CHILD_EXCLUSION.get(ex);
            if (children != null) excludes.addAll(children);
        }
        var includes = ctx.options.includes();

        // Coupled exclusion via the SxxExx pair tag ("coexist"): excluding one half
        // of a compact season/episode pattern (e.g. excludes=season on "02x05") must
        // drop its sibling too, mirroring Python's pattern-level disable. Span-based
        // coupling does not work because season and episode have distinct spans.
        boolean dropCoexistEpisode = excludes.contains("season");
        boolean dropCoexistSeason = excludes.contains("episode");

        // Collect coexist-group ids whose exclusion must propagate. A "cg:N" tag
        // groups sibling matches produced by one rule pass (symmetric: dropping any
        // member drops the whole group). A separate "derivedFrom:<name>" tag is
        // one-way (asymmetric): dropping the master drops the derivative only.
        var droppedGroups = new HashSet<String>();
        var droppedNames = new HashSet<String>();
        ctx.matches.all().forEach(m -> {
            var name = m.name();
            boolean filtered = (!excludes.isEmpty() && excludes.contains(name))
                    || (!includes.isEmpty() && !includes.contains(name));
            if (!filtered) return;
            droppedNames.add(name);
            for (var t : m.tags()) {
                if (t.startsWith("cg:")) droppedGroups.add(t);
            }
        });

        // Promote subtitle_language → language when subtitle_language is filtered
        // out and language survives (mirrors python: with subtitle_language disabled,
        // SubtitlePrefixLanguageRule no longer renames language→subtitle_language,
        // so 'ENG.-.sub.FR' / 'ST.FR' / 'ENG.-.FR Sub' all keep FR as language).
        boolean langKept = (includes.isEmpty() || includes.contains("language"))
                && !excludes.contains("language");
        boolean subFiltered = excludes.contains("subtitle_language")
                || (!includes.isEmpty() && !includes.contains("subtitle_language"));
        boolean subFilteredKeepLang = subFiltered && langKept;

        return new FilterState(excludes, includes, droppedGroups, droppedNames,
                dropCoexistEpisode, dropCoexistSeason, subFilteredKeepLang);
    }

    /** Sort by start, apply filters/promotions, and group surviving matches by name. */
    private static Map<String, List<Match>> groupSurvivingMatches(ParseContext ctx, FilterState s) {
        // LinkedHashMap preserves first-seen name order so `extras` is deterministic.
        var grouped = new LinkedHashMap<String, List<Match>>();
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
        if (!"subtitle_language".equals(m.name())) return m;
        if (m.tags().contains("attached-affix")) return m;
        return m.withName("language");
    }

    /** Apply --excludes / --includes / coexist-pair / dropped-group / derivedFrom rules. */
    private static boolean isFiltered(Match m, FilterState s) {
        var name = m.name();
        if (!s.excludes.isEmpty() && s.excludes.contains(name)) return true;
        if (s.dropCoexistEpisode && "episode".equals(name) && m.tags().contains("coexist")) return true;
        if (s.dropCoexistSeason && "season".equals(name) && m.tags().contains("coexist")) return true;
        if (!s.includes.isEmpty() && !s.includes.contains(name)) return true;
        if (!s.droppedGroups.isEmpty()) {
            for (var t : m.tags()) {
                if (s.droppedGroups.contains(t)) return true;
            }
        }
        if (!s.droppedNames.isEmpty()) {
            for (var t : m.tags()) {
                if (t.startsWith("derivedFrom:") && s.droppedNames.contains(t.substring(12))) return true;
            }
        }
        return false;
    }

    /** Route grouped matches to typed builder setters; unknown names go to extras. */
    private static Map<String, Object> dispatchToBuilder(GuessResultBuilder b, Map<String, List<Match>> grouped) {
        var extras = new LinkedHashMap<String, Object>();
        for (var e : grouped.entrySet()) {
            var ms = e.getValue();
            switch (e.getKey()) {
                case "title" -> b.title(asString(ms.getFirst()));
                case "alternative_title" -> b.alternativeTitleList(ms.stream().map(OutputBuilder::asString).toList());
                case "year" -> b.year(asInt(ms.getFirst()));
                case "date" -> b.date((LocalDate) ms.getFirst().value());
                case "season" -> applyIntList(ms, b::season, b::seasonList);
                case "episode" -> applyIntList(ms, b::episode, b::episodeList);
                case "episode_count" -> b.episodeCount(asInt(ms.getFirst()));
                case "season_count" -> b.seasonCount(asInt(ms.getFirst()));
                case "episode_title" -> b.episodeTitle(asString(ms.getFirst()));
                case "episode_format" -> b.episodeFormat(asString(ms.getFirst()));
                case "type" -> b.type(asString(ms.getFirst()));
                case "language" -> b.language(asLangList(ms));
                case "subtitle_language" -> b.subtitleLanguage(asLangList(ms));
                case "country" -> b.country(asCountryList(ms));
                case "source" -> applyStringList(ms, b::source, b::sourceList);
                case "other" -> b.other(dedupedStringList(ms));
                case "video_codec" -> b.videoCodec(dedupedStringList(ms));
                case "audio_codec" -> b.audioCodec(dedupedStringList(ms));
                case "audio_channels" -> b.audioChannels(dedupedStringList(ms));
                case "audio_profile" -> b.audioProfile(dedupedStringList(ms));
                case "video_profile" -> b.videoProfile(dedupedStringList(ms));
                case "video_api" -> b.videoApi(dedupedStringList(ms));
                case "screen_size" -> b.screenSize(asString(ms.getFirst()));
                case "aspect_ratio" -> b.aspectRatio(asDouble(ms.getFirst()));
                case "frame_rate" -> b.frameRate(asFrameRate(ms.getFirst()));
                case "bit_rate" -> b.bitRate((Quantity) ms.getFirst().value());
                case "audio_bit_rate" -> b.audioBitRate((Quantity) ms.getFirst().value());
                case "video_bit_rate" -> b.videoBitRate((Quantity) ms.getFirst().value());
                case "size" -> b.size((Quantity) ms.getFirst().value());
                case "container" -> b.container(asString(ms.getFirst()));
                case "mimetype" -> b.mimetype(asString(ms.getFirst()));
                case "release_group" -> b.releaseGroup(asString(ms.getFirst()));
                case "streaming_service" -> b.streamingService(asString(ms.getFirst()));
                case "website" -> b.website(asString(ms.getFirst()));
                case "edition" -> b.edition(dedupedStringList(ms));
                case "cd" -> b.cd(asInt(ms.getFirst()));
                case "cd_count" -> b.cdCount(asInt(ms.getFirst()));
                case "part" -> applyIntList(ms, b::part, b::partList);
                case "version" -> b.version(asInt(ms.getFirst()));
                case "film" -> b.film(asInt(ms.getFirst()));
                case "film_title" -> b.filmTitle(asString(ms.getFirst()));
                case "bonus" -> b.bonus(asInt(ms.getFirst()));
                case "bonus_title" -> b.bonusTitle(asString(ms.getFirst()));
                case "crc32" -> b.crc32(asString(ms.getFirst()));
                case "proper_count" -> b.properCount(asInt(ms.getFirst()));
                default -> extras.put(e.getKey(), ms.size() == 1 ? ms.getFirst().value()
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
        if (v instanceof String s) return Integer.parseInt(s);
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
        if (v instanceof String s) return Double.parseDouble(s);
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
