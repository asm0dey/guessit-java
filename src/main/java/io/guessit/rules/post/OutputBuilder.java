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

public final class OutputBuilder implements Consumer<ParseContext> {
    @Override
    public void accept(ParseContext ctx) {
        var b = ctx.resultBuilder;
        var grouped = new LinkedHashMap<String, List<Match>>();
        ctx.matches.all().sorted(java.util.Comparator.comparingInt(Match::start)).forEach(m ->
            grouped.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m));

        var extras = new LinkedHashMap<String, Object>();
        for (var e : grouped.entrySet()) {
            switch (e.getKey()) {
                case "title" -> b.title(asString(e.getValue().get(0)));
                case "alternative_title" -> b.alternativeTitle(asString(e.getValue().get(0)));
                case "year" -> b.year(asInt(e.getValue().get(0)));
                case "date" -> b.date((LocalDate) e.getValue().get(0).value());
                case "season" -> applyIntList(e.getValue(), b::season, b::seasonList);
                case "episode" -> applyIntList(e.getValue(), b::episode, b::episodeList);
                case "episode_count" -> b.episodeCount(asInt(e.getValue().get(0)));
                case "season_count" -> b.seasonCount(asInt(e.getValue().get(0)));
                case "episode_title" -> b.episodeTitle(asString(e.getValue().get(0)));
                case "episode_format" -> b.episodeFormat(asString(e.getValue().get(0)));
                case "type" -> b.type(asString(e.getValue().get(0)));
                case "language" -> b.language(asLangList(e.getValue()));
                case "subtitle_language" -> b.subtitleLanguage(asLangList(e.getValue()));
                case "country" -> b.country(asCountryList(e.getValue()));
                case "source" -> b.source(asString(e.getValue().get(0)));
                case "other" -> b.other(asStringList(e.getValue()));
                case "video_codec" -> b.videoCodec(dedupedStringList(e.getValue()));
                case "audio_codec" -> b.audioCodec(dedupedStringList(e.getValue()));
                case "audio_channels" -> b.audioChannels(dedupedStringList(e.getValue()));
                case "audio_profile" -> b.audioProfile(dedupedStringList(e.getValue()));
                case "video_profile" -> b.videoProfile(dedupedStringList(e.getValue()));
                case "video_api" -> b.videoApi(dedupedStringList(e.getValue()));
                case "screen_size" -> b.screenSize(asString(e.getValue().get(0)));
                case "aspect_ratio" -> b.aspectRatio(asDouble(e.getValue().get(0)));
                case "frame_rate" -> b.frameRate(asFrameRate(e.getValue().get(0)));
                case "bit_rate" -> b.bitRate((Quantity) e.getValue().get(0).value());
                case "size" -> b.size((Quantity) e.getValue().get(0).value());
                case "container" -> b.container(asString(e.getValue().get(0)));
                case "mimetype" -> b.mimetype(asString(e.getValue().get(0)));
                case "release_group" -> b.releaseGroup(asString(e.getValue().get(0)));
                case "streaming_service" -> b.streamingService(asString(e.getValue().get(0)));
                case "website" -> b.website(asString(e.getValue().get(0)));
                case "edition" -> b.edition(asString(e.getValue().get(0)));
                case "cd" -> b.cd(asInt(e.getValue().get(0)));
                case "cd_count" -> b.cdCount(asInt(e.getValue().get(0)));
                case "part" -> b.part(asInt(e.getValue().get(0)));
                case "version" -> b.version(asInt(e.getValue().get(0)));
                case "film" -> b.film(asInt(e.getValue().get(0)));
                case "film_title" -> b.filmTitle(asString(e.getValue().get(0)));
                case "bonus" -> b.bonus(asInt(e.getValue().get(0)));
                case "bonus_title" -> b.bonusTitle(asString(e.getValue().get(0)));
                case "crc32" -> b.crc32(asString(e.getValue().get(0)));
                default -> extras.put(e.getKey(), e.getValue().size() == 1 ? e.getValue().get(0).value()
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
    private static List<Language> asLangList(List<Match> ms) { return ms.stream().map(m -> (Language) m.value()).toList(); }
    private static List<Country> asCountryList(List<Match> ms) { return ms.stream().map(m -> (Country) m.value()).toList(); }
    private static void applyIntList(List<Match> ms, java.util.function.Consumer<Integer> single,
                                     java.util.function.Consumer<List<Integer>> list) {
        if (ms.size() == 1) single.accept(asInt(ms.get(0)));
        else list.accept(ms.stream().map(OutputBuilder::asInt).toList());
    }
}
