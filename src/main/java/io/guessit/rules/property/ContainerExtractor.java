package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

public final class ContainerExtractor implements Extractor {

    @Override public String name() { return "container"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("container");
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
        var body = new HashSet<String>();
        body.addAll(subtitles); body.remove("sub"); body.remove("ass");  // matches Python carve-out
        body.addAll(videos); body.addAll(torrent); body.addAll(nzb);
        var opts = StringOpts.defaults()
            .withValidator(Validators.sepsSurround(input))
            .withTags(Set.of("body"));
        for (var m : PatternMatcher.string(input, body, "container", opts)) {
            // Skip body matches that overlap an extension match — extension wins.
            boolean overlapsExt = ctx.matches.named("container")
                .anyMatch(other -> other.tags().contains("extension")
                                && other.start() < m.end() && m.start() < other.end());
            if (!overlapsExt) ctx.matches.add(m);
        }
    }

    private static void addExtensionRegex(ParseContext ctx, String input, List<String> exts, String kindTag) {
        if (exts.isEmpty()) return;
        var or = String.join("|", exts.stream().map(Pattern::quote).toList());
        var p = Pattern.compile("\\.(?:" + or + ")$", Pattern.CASE_INSENSITIVE);
        var opts = RegexOpts.defaults()
            .withValue(s -> s.startsWith(".") ? s.substring(1).toLowerCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT))
            .withTags(Set.of("extension", kindTag));
        for (var m : PatternMatcher.regex(input, p, "container", opts)) {
            ctx.matches.add(m);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) return (List<String>) l;
        return List.of();
    }
}
