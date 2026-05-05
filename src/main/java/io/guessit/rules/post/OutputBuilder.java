package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.util.Quantity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final java.util.Map<String, java.util.List<String>> CHILD_EXCLUSION = java.util.Map.of(
            "bonus", java.util.List.of("bonus_title"),
            "film", java.util.List.of("film_title"),
            "cd", java.util.List.of("cd_count")
    );

    @Override
    public void accept(ParseContext ctx) {
        var b = ctx.resultBuilder;
        var rawExcludes = new java.util.HashSet<>(ctx.options.excludes());
        var includes = ctx.options.includes();

        // Expand child exclusions (bonus → bonus_title, etc.)
        var childExpand = new java.util.HashSet<String>();
        for (var ex : rawExcludes) {
            var children = CHILD_EXCLUSION.get(ex);
            if (children != null) childExpand.addAll(children);
        }
        rawExcludes.addAll(childExpand);

        var excludes = rawExcludes;
        // Coupled exclusion via the SxxExx pair tag ("coexist"): excluding one half of
        // a compact season/episode pattern (e.g. excludes=season on "02x05") must drop
        // its sibling too, mirroring Python's pattern-level disable. Span-based coupling
        // does not work because season and episode have distinct spans inside the regex.
        boolean dropCoexistEpisode = excludes.contains("season");
        boolean dropCoexistSeason = excludes.contains("episode");

        // First pass: collect coexist-group ids whose exclusion must propagate.
        // A "cg:N" tag groups sibling matches produced by one rule pass (symmetric:
        // dropping any member drops the whole group, mirroring rebulk's rule-level
        // disable). A separate "derivedFrom:<name>" tag is one-way (asymmetric):
        // dropping the master drops the derivative, but not vice versa.
        var droppedGroups = new java.util.HashSet<String>();
        var droppedNames = new java.util.HashSet<String>();
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

        // Promote subtitle_language → language whenever subtitle_language is
        // filtered out and language survives (mirrors python: when
        // subtitle_language is disabled the SubtitlePrefixLanguageRule no
        // longer renames language→subtitle_language, so 'ENG.-.sub.FR' /
        // 'ST.FR' / 'ENG.-.FR Sub' all keep FR as language). Holds for both
        // --excludes subtitle_language and --includes-without-subtitle_language.
        boolean langKept = (includes.isEmpty() || includes.contains("language"))
                && !excludes.contains("language");
        boolean subFiltered = excludes.contains("subtitle_language")
                || (!includes.isEmpty() && !includes.contains("subtitle_language"));
        boolean subFilteredKeepLang = subFiltered && langKept;

        // LinkedHashMap preserves the first-seen name order, which makes the
        // order in `extras` deterministic when matches contribute unknown keys.
        var grouped = new LinkedHashMap<String, List<Match>>();
        ctx.matches.all().sorted(java.util.Comparator.comparingInt(Match::start)).forEach(m0 -> {
            Match m = m0;
            var name = m.name();
            // Promote subtitle_language → language when the latter is filtered
            // out — but skip "attached-affix" matches (e.g. "SubFR"). Python's
            // attached-prefix tokens are extracted as subtitle_language directly
            // and stay dropped under --includes language.
            if (subFilteredKeepLang && "subtitle_language".equals(name)
                    && !m.tags().contains("attached-affix")) {
                m = m.withName("language");
                name = "language";
            }
            // Respect --exclude / --include options from the caller.
            if (!excludes.isEmpty() && excludes.contains(name)) return;
            // Coupled tag exclusion: drop SxxExx-pair sibling when one half is excluded.
            if (dropCoexistEpisode && "episode".equals(name) && m.tags().contains("coexist")) return;
            if (dropCoexistSeason && "season".equals(name) && m.tags().contains("coexist")) return;
            if (!includes.isEmpty() && !includes.contains(name)) return;
            // Drop matches whose coexist-group sibling was filtered (symmetric).
            if (!droppedGroups.isEmpty()) {
                for (var t : m.tags()) {
                    if (droppedGroups.contains(t)) return;
                }
            }
            // Drop derivative matches whose master name was filtered (asymmetric).
            if (!droppedNames.isEmpty()) {
                for (var t : m.tags()) {
                    if (t.startsWith("derivedFrom:") && droppedNames.contains(t.substring(12))) return;
                }
            }
            grouped.computeIfAbsent(name, _ -> new ArrayList<>()).add(m);
        });

        var extras = new LinkedHashMap<String, Object>();
        for (var e : grouped.entrySet()) {
            switch (e.getKey()) {
                case "title" -> b.title(asString(e.getValue().getFirst()));
                case "alternative_title" -> b.alternativeTitleList(e.getValue().stream().map(OutputBuilder::asString).toList());
                case "year" -> b.year(asInt(e.getValue().getFirst()));
                case "date" -> b.date((LocalDate) e.getValue().getFirst().value());
                case "season" -> applyIntList(e.getValue(), b::season, b::seasonList);
                case "episode" -> applyIntList(e.getValue(), b::episode, b::episodeList);
                case "episode_count" -> b.episodeCount(asInt(e.getValue().getFirst()));
                case "season_count" -> b.seasonCount(asInt(e.getValue().getFirst()));
                case "episode_title" -> b.episodeTitle(asString(e.getValue().getFirst()));
                case "episode_format" -> b.episodeFormat(asString(e.getValue().getFirst()));
                case "type" -> b.type(asString(e.getValue().getFirst()));
                case "language" -> b.language(asLangList(e.getValue()));
                case "subtitle_language" -> b.subtitleLanguage(asLangList(e.getValue()));
                case "country" -> b.country(asCountryList(e.getValue()));
                case "source" -> applyStringList(e.getValue(), b::source, b::sourceList);
                case "other" -> b.other(dedupedStringList(e.getValue()));
                case "video_codec" -> b.videoCodec(dedupedStringList(e.getValue()));
                case "audio_codec" -> b.audioCodec(dedupedStringList(e.getValue()));
                case "audio_channels" -> b.audioChannels(dedupedStringList(e.getValue()));
                case "audio_profile" -> b.audioProfile(dedupedStringList(e.getValue()));
                case "video_profile" -> b.videoProfile(dedupedStringList(e.getValue()));
                case "video_api" -> b.videoApi(dedupedStringList(e.getValue()));
                case "screen_size" -> b.screenSize(asString(e.getValue().getFirst()));
                case "aspect_ratio" -> b.aspectRatio(asDouble(e.getValue().getFirst()));
                case "frame_rate" -> b.frameRate(asFrameRate(e.getValue().getFirst()));
                case "bit_rate" -> b.bitRate((Quantity) e.getValue().getFirst().value());
                case "audio_bit_rate" -> b.audioBitRate((Quantity) e.getValue().getFirst().value());
                case "video_bit_rate" -> b.videoBitRate((Quantity) e.getValue().getFirst().value());
                case "size" -> b.size((Quantity) e.getValue().getFirst().value());
                case "container" -> b.container(asString(e.getValue().getFirst()));
                case "mimetype" -> b.mimetype(asString(e.getValue().getFirst()));
                case "release_group" -> b.releaseGroup(asString(e.getValue().getFirst()));
                case "streaming_service" -> b.streamingService(asString(e.getValue().getFirst()));
                case "website" -> b.website(asString(e.getValue().getFirst()));
                case "edition" -> b.edition(dedupedStringList(e.getValue()));
                case "cd" -> b.cd(asInt(e.getValue().getFirst()));
                case "cd_count" -> b.cdCount(asInt(e.getValue().getFirst()));
                case "part" -> applyIntList(e.getValue(), b::part, b::partList);
                case "version" -> b.version(asInt(e.getValue().getFirst()));
                case "film" -> b.film(asInt(e.getValue().getFirst()));
                case "film_title" -> b.filmTitle(asString(e.getValue().getFirst()));
                case "bonus" -> b.bonus(asInt(e.getValue().getFirst()));
                case "bonus_title" -> b.bonusTitle(asString(e.getValue().getFirst()));
                case "crc32" -> b.crc32(asString(e.getValue().getFirst()));
                case "proper_count" -> b.properCount(asInt(e.getValue().getFirst()));
                default -> extras.put(e.getKey(), e.getValue().size() == 1 ? e.getValue().getFirst().value()
                    : e.getValue().stream().map(Match::value).toList());
            }
        }
        if (!extras.isEmpty()) b.extras(extras);

        ctx.result = b.build();
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
