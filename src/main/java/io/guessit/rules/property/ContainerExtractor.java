package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Extracts {@code container} (mkv, mp4, srt, …).
 *
 * <p>Two passes inside {@link #extract}:
 * <ol>
 *   <li><em>Extension</em> — trailing {@code .<ext>$}. Tagged {@code extension}
 *       plus a kind tag ({@code video}, {@code subtitle}, {@code info},
 *       {@code torrent}, {@code nzb}) so downstream code can branch on what
 *       kind of file is being parsed.</li>
 *   <li><em>Body</em> — same alias set anywhere in the input, separator-bound,
 *       tagged {@code body}. Body matches yield to extensions (which are
 *       always more authoritative) and to overlapping {@code video_codec},
 *       {@code audio_codec}, or {@code screen_size} matches (e.g. avoid
 *       reading the {@code "x264"} as a container).</li>
 * </ol>
 */
public final class ContainerExtractor implements Extractor {

    public static final String CONTAINER = "container";

    private static final ConcurrentMap<String, Pattern> EXT_CACHE = new ConcurrentHashMap<>();

    @Override public String name() { return CONTAINER; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(CONTAINER);
        var subtitles = stringList(section.get("subtitles"));
        var info       = stringList(section.get("info"));
        var videos     = stringList(section.get("videos"));
        var torrent    = stringList(section.get("torrent"));
        var nzb        = stringList(section.get("nzb"));

        var input = ctx.input;

        // 1. Extension matches: trailing `.<ext>` only; tagged "extension" + kind.
        addExtensionRegex(ctx, input, subtitles, "subtitle");
        addExtensionRegex(ctx, input, info,      "info");
        addExtensionRegex(ctx, input, videos,    "video");
        addExtensionRegex(ctx, input, torrent,   "torrent");
        addExtensionRegex(ctx, input, nzb,       "nzb");

        // 2. Body matches: same words but anywhere, requires seps_surround.
        var body = new HashSet<>(subtitles);
        body.remove("sub"); body.remove("ass");  // matches Python carve-out
        body.addAll(videos); body.addAll(torrent); body.addAll(nzb);
        var opts = StringOpts.defaults()
            .withValidator(Validators.sepsSurround(input))
            .withTags(Set.of("body"));
        for (var m : PatternMatcher.string(input, body, MatchName.CONTAINER, opts)) {
            // Skip body matches that overlap an extension match — extension wins.
            boolean overlapsExt = ctx.matches.named(MatchName.CONTAINER)
                .anyMatch(other -> other.tags().contains("extension")
                                && other.start() < m.end() && m.start() < other.end());
            // Skip body matches that overlap a video_codec/audio_codec/screen_size — codec wins.
            boolean overlapsCodec = ctx.matches.all()
                .anyMatch(other -> (other.name() == MatchName.VIDEO_CODEC
                                 || other.name() == MatchName.AUDIO_CODEC
                                 || other.name() == MatchName.SCREEN_SIZE)
                                && other.start() < m.end() && m.start() < other.end());
            if (!overlapsExt && !overlapsCodec) ctx.matches.add(m);
        }
    }

    private static void addExtensionRegex(ParseContext ctx, String input, List<String> exts, String kindTag) {
        if (exts.isEmpty()) return;
        var or = String.join("|", exts.stream().map(Pattern::quote).toList());
        var src = "\\.(?:" + or + ")$";
        var p = EXT_CACHE.computeIfAbsent(src, s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE));
        var opts = RegexOpts.defaults()
            .withValue(s -> s.startsWith(".") ? s.substring(1).toLowerCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT))
            .withTags(Set.of("extension", kindTag));
        for (var m : PatternMatcher.regex(input, p, MatchName.CONTAINER, opts)) {
            ctx.matches.add(m);
        }
    }

    private static List<String> stringList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        var out = new java.util.ArrayList<String>(l.size());
        for (var v : l) if (v != null) out.add(v.toString());
        return out;
    }
}
