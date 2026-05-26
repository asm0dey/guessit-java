package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.GuessResultBuilder;
import io.guessit.util.Quantity;

import java.time.LocalDate;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Assembles {@link ParseContext#result} from the surviving matches.
 */
public final class OutputBuilder implements Consumer<ParseContext> {

    private static final Map<String, List<String>> CHILD_EXCLUSION = Map.of(
            "bonus", List.of("bonus_title"),
            "film", List.of("film_title"),
            "cd", List.of("cd_count")
    );

    private static final Map<MatchName, BiConsumer<GuessResultBuilder, List<Match>>> DISPATCHER = new EnumMap<>(MatchName.class);

    static {
        DISPATCHER.put(MatchName.TITLE, (b, ms) -> b.title(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.EPISODE_TITLE, (b, ms) -> b.episodeTitle(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.EPISODE_FORMAT, (b, ms) -> b.episodeFormat(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.TYPE, (b, ms) -> b.type(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.SCREEN_SIZE, (b, ms) -> b.screenSize(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.CONTAINER, (b, ms) -> b.container(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.MIMETYPE, (b, ms) -> b.mimetype(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.RELEASE_GROUP, (b, ms) -> b.releaseGroup(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.STREAMING_SERVICE, (b, ms) -> b.streamingService(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.WEBSITE, (b, ms) -> b.website(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.FILM_TITLE, (b, ms) -> b.filmTitle(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.BONUS_TITLE, (b, ms) -> b.bonusTitle(asString(ms.getFirst())));
        DISPATCHER.put(MatchName.CRC32, (b, ms) -> b.crc32(asString(ms.getFirst())));

        DISPATCHER.put(MatchName.YEAR, (b, ms) -> b.year(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.EPISODE_COUNT, (b, ms) -> b.episodeCount(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.SEASON_COUNT, (b, ms) -> b.seasonCount(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.CD, (b, ms) -> b.cd(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.CD_COUNT, (b, ms) -> b.cdCount(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.VERSION, (b, ms) -> b.version(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.FILM, (b, ms) -> b.film(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.BONUS, (b, ms) -> b.bonus(asInt(ms.getFirst())));
        DISPATCHER.put(MatchName.PROPER_COUNT, (b, ms) -> b.properCount(asInt(ms.getFirst())));

        DISPATCHER.put(MatchName.ALTERNATIVE_TITLE, (b, ms) -> b.alternativeTitleList(ms.stream().map(OutputBuilder::asString).toList()));
        DISPATCHER.put(MatchName.OTHER, (b, ms) -> b.other(dedupedStringList(ms)));
        DISPATCHER.put(MatchName.VIDEO_CODEC, (b, ms) -> b.videoCodec(dedupedStringList(ms)));
        DISPATCHER.put(MatchName.AUDIO_CODEC, (b, ms) -> b.audioCodec(dedupedStringList(ms)));
        DISPATCHER.put(MatchName.AUDIO_CHANNELS, (b, ms) -> b.audioChannels(dedupedStringList(ms)));
        DISPATCHER.put(MatchName.AUDIO_PROFILE, (b, ms) -> b.audioProfile(dedupedStringList(ms)));
        DISPATCHER.put(MatchName.VIDEO_PROFILE, (b, ms) -> b.videoProfile(dedupedStringList(ms)));
        DISPATCHER.put(MatchName.VIDEO_API, (b, ms) -> b.videoApi(dedupedStringList(ms)));
        DISPATCHER.put(MatchName.EDITION, (b, ms) -> b.edition(dedupedStringList(ms)));

        DISPATCHER.put(MatchName.DATE, (b, ms) -> { if (ms.getFirst().value() instanceof LocalDate d) b.date(d); });
        DISPATCHER.put(MatchName.LANGUAGE, (b, ms) -> b.language(asLangList(ms)));
        DISPATCHER.put(MatchName.SUBTITLE_LANGUAGE, (b, ms) -> b.subtitleLanguage(asLangList(ms)));
        DISPATCHER.put(MatchName.COUNTRY, (b, ms) -> b.country(asCountryList(ms)));
        DISPATCHER.put(MatchName.ASPECT_RATIO, (b, ms) -> b.aspectRatio(asDouble(ms.getFirst())));
        DISPATCHER.put(MatchName.FRAME_RATE, (b, ms) -> b.frameRate(asFrameRate(ms.getFirst())));

        DISPATCHER.put(MatchName.BIT_RATE, (b, ms) -> b.bitRate((Quantity) ms.getFirst().value()));
        DISPATCHER.put(MatchName.AUDIO_BIT_RATE, (b, ms) -> b.audioBitRate((Quantity) ms.getFirst().value()));
        DISPATCHER.put(MatchName.VIDEO_BIT_RATE, (b, ms) -> b.videoBitRate((Quantity) ms.getFirst().value()));
        DISPATCHER.put(MatchName.SIZE, (b, ms) -> b.size((Quantity) ms.getFirst().value()));

        DISPATCHER.put(MatchName.SEASON, (b, ms) -> applyIntList(ms, b::season, b::seasonList));
        DISPATCHER.put(MatchName.EPISODE, (b, ms) -> applyIntList(ms, b::episode, b::episodeList));
        DISPATCHER.put(MatchName.PART, (b, ms) -> applyIntList(ms, b::part, b::partList));
        DISPATCHER.put(MatchName.SOURCE, (b, ms) -> applyStringList(ms, b::source, b::sourceList));
    }

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
        var extras = dispatchToBuilder(ctx.resultBuilder, grouped, ctx.trace);
        if (!extras.isEmpty()) ctx.resultBuilder.extras(extras);
        ctx.result = ctx.resultBuilder.build();
    }

    private record DroppedSet(Set<String> groups, Set<MatchName> names) {}

    private static FilterState computeFilterState(ParseContext ctx) {
        var excludes = expandExcludesWithChildren(ctx.options.excludes());
        var includes = ctx.options.includes();

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

    private static DroppedSet collectDropped(ParseContext ctx, Set<String> excludes, List<String> includes) {
        var groups = new HashSet<String>();
        var names = new HashSet<MatchName>();

        ctx.matches.all().forEach(m -> {
            var name = m.name();
            var nameStr = name.name().toLowerCase();
            boolean filtered = (!excludes.isEmpty() && excludes.contains(nameStr))
                    || (!includes.isEmpty() && !includes.contains(nameStr));

            if (filtered) {
                names.add(name);
                m.tags().stream().filter(t -> t.startsWith("cg:")).forEach(groups::add);
            }
        });

        return new DroppedSet(groups, names);
    }

    private static boolean computeSubFilteredKeepLang(Set<String> excludes, List<String> includes) {
        boolean langKept = (includes.isEmpty() || includes.contains(MatchName.LANGUAGE.name().toLowerCase()))
                && !excludes.contains(MatchName.LANGUAGE.name().toLowerCase());
        boolean subFiltered = excludes.contains(MatchName.SUBTITLE_LANGUAGE.name().toLowerCase())
                || (!includes.isEmpty() && !includes.contains(MatchName.SUBTITLE_LANGUAGE.name().toLowerCase()));
        return subFiltered && langKept;
    }

    private static Map<MatchName, List<Match>> groupSurvivingMatches(ParseContext ctx, FilterState s) {
        var grouped = new LinkedHashMap<MatchName, List<Match>>();

        ctx.matches.all().sorted(Comparator.comparingInt(Match::start)).forEach(m0 -> {
            var m = maybePromoteSubtitleToLanguage(m0, s);
            if (!isFiltered(m, s)) {
                grouped.computeIfAbsent(m.name(), _ -> new ArrayList<>()).add(m);
            }
        });

        return grouped;
    }

    private static Match maybePromoteSubtitleToLanguage(Match m, FilterState s) {
        if (!s.subFilteredKeepLang || m.name() != MatchName.SUBTITLE_LANGUAGE || m.tags().contains("attached-affix")) {
            return m;
        }
        return m.withName(MatchName.LANGUAGE);
    }

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
        return !s.droppedGroups.isEmpty() && m.tags().stream().anyMatch(s.droppedGroups::contains);
    }

    private static boolean isDerivedFromDropped(Match m, FilterState s) {
        if (s.droppedNames.isEmpty()) return false;

        return m.tags().stream()
                .filter(t -> t.startsWith("derivedFrom:"))
                .anyMatch(t -> {
                    try {
                        var derivedName = MatchName.valueOf(t.substring(12).toUpperCase());
                        return s.droppedNames.contains(derivedName);
                    } catch (IllegalArgumentException _) {
                        return false;
                    }
                });
    }

    private static Map<String, Object> dispatchToBuilder(GuessResultBuilder b, Map<MatchName, List<Match>> grouped, io.guessit.engine.Trace trace) {
        var extras = new LinkedHashMap<String, Object>();

        for (var e : grouped.entrySet()) {
            var matchName = e.getKey();
            var matches = e.getValue();

            traceAssignment(trace, matchName, matches);

            var action = DISPATCHER.get(matchName);
            if (action != null) {
                action.accept(b, matches);
            } else {
                extras.put(matchName.name().toLowerCase(), matches.size() == 1 ? matches.getFirst().value() : matches.stream().map(Match::value).toList());
            }
        }
        return extras;
    }

    private static void traceAssignment(io.guessit.engine.Trace trace, MatchName name, List<Match> ms) {
        var key = name.name().toLowerCase();
        if (ms.size() == 1) {
            var m = ms.getFirst();
            trace.subStep("Set " + key + " ← " + renderValue(m.value()) + " from match at " + m.start() + "-" + m.end());
        } else {
            var values = ms.stream().map(m -> renderValue(m.value())).toList();
            var first = ms.getFirst();
            var last = ms.getLast();
            trace.subStep("Set " + key + " ← " + values + " from " + ms.size() + " matches at " + first.start() + "-" + last.end());
        }
    }

    private static String renderValue(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }

    private static String asString(Match m) { return m.value() == null ? null : m.value().toString(); }

    private static Integer asInt(Match m) {
        var v = m.value();
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException _) { return null; }
        }
        return null;
    }

    private static List<String> dedupedStringList(List<Match> ms) {
        return ms.stream().map(OutputBuilder::asString).distinct().toList();
    }

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
            try { return Double.parseDouble(s); } catch (NumberFormatException _) { return null; }
        }
        return null;
    }

    private static List<Language> asLangList(List<Match> ms) {
        return ms.stream().map(m -> (Language) m.value()).distinct().toList();
    }

    private static List<Country> asCountryList(List<Match> ms) {
        return ms.stream().map(m -> (Country) m.value()).distinct().toList();
    }

    private static void applyIntList(List<Match> ms, IntConsumer single, Consumer<List<Integer>> list) {
        if (ms.size() == 1) {
            Integer val = asInt(ms.getFirst());
            if (val != null) {
                single.accept(val);
            }
            return;
        }

        var values = ms.stream()
                .map(OutputBuilder::asInt)
                .filter(Objects::nonNull)
                .toList();

        if (values.isEmpty()) return;

        var distinct = values.stream().distinct().toList();

        if (distinct.size() == 1) {
            single.accept(distinct.getFirst());
        } else {
            list.accept(values);
        }
    }

    private static void applyStringList(List<Match> ms, Consumer<String> single, Consumer<List<String>> list) {
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