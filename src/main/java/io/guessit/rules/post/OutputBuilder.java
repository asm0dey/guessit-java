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
    @Override
    public void accept(ParseContext ctx) {
        var b = ctx.resultBuilder;
        // LinkedHashMap preserves the first-seen name order, which makes the
        // order in `extras` deterministic when matches contribute unknown keys.
        var grouped = new LinkedHashMap<String, List<Match>>();
        ctx.matches.all().sorted(java.util.Comparator.comparingInt(Match::start)).forEach(m ->
            grouped.computeIfAbsent(m.name(), _ -> new ArrayList<>()).add(m));

        var extras = new LinkedHashMap<String, Object>();
        for (var e : grouped.entrySet()) {
            switch (e.getKey()) {
                case "title" -> b.title(asString(e.getValue().getFirst()));
                case "alternative_title" -> b.alternativeTitle(asString(e.getValue().getFirst()));
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
                case "source" -> b.source(asString(e.getValue().getFirst()));
                case "other" -> b.other(asStringList(e.getValue()));
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
                case "size" -> b.size((Quantity) e.getValue().getFirst().value());
                case "container" -> b.container(asString(e.getValue().getFirst()));
                case "mimetype" -> b.mimetype(asString(e.getValue().getFirst()));
                case "release_group" -> b.releaseGroup(asString(e.getValue().getFirst()));
                case "streaming_service" -> b.streamingService(asString(e.getValue().getFirst()));
                case "website" -> b.website(asString(e.getValue().getFirst()));
                case "edition" -> b.edition(asString(e.getValue().getFirst()));
                case "cd" -> b.cd(asInt(e.getValue().getFirst()));
                case "cd_count" -> b.cdCount(asInt(e.getValue().getFirst()));
                case "part" -> b.part(asInt(e.getValue().getFirst()));
                case "version" -> b.version(asInt(e.getValue().getFirst()));
                case "film" -> b.film(asInt(e.getValue().getFirst()));
                case "film_title" -> b.filmTitle(asString(e.getValue().getFirst()));
                case "bonus" -> b.bonus(asInt(e.getValue().getFirst()));
                case "bonus_title" -> b.bonusTitle(asString(e.getValue().getFirst()));
                case "crc32" -> b.crc32(asString(e.getValue().getFirst()));
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
    private static List<String> asStringList(List<Match> ms) { return ms.stream().map(OutputBuilder::asString).toList(); }
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
}
