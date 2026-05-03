package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class WeakEpisodeExtractor implements Extractor {
    private static final Pattern TWO_DIGIT = Pattern.compile("(?<!\\d)(\\d{2})(?!\\d)");
    private static final Pattern THREE_OR_FOUR = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)");
    private static final Pattern SINGLE = Pattern.compile("(?<!\\d)(\\d)(?!\\d)");

    @Override public String name() { return "weak_episode"; }
    @Override public int priority() { return 800; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        // Pre-compute SxxExx episodes; skip weak matches that overlap them.
        var protectedEpisodes = ctx.matches.named("episode")
            .filter(m -> m.tags().contains("SxxExx"))
            .toList();

        emit(ctx, input, TWO_DIGIT, seps, protectedEpisodes);
        emit(ctx, input, THREE_OR_FOUR, seps, protectedEpisodes);
        if ("episode".equals(ctx.options.type())) {
            emit(ctx, input, SINGLE, seps, protectedEpisodes);
        }
    }

    private void emit(ParseContext ctx, String input, Pattern p, java.util.function.Predicate<Match> seps,
                      java.util.List<Match> protectedEpisodes) {
        var m = p.matcher(input);
        outer:
        while (m.find()) {
            int ms = m.start(1);
            int me = m.end(1);
            // Skip weak matches that overlap any SxxExx episode — they would steal
            // the shorter SxxExx span's priority in conflict resolution.
            for (var pe : protectedEpisodes) {
                if (ms < pe.end() && me > pe.start()) continue outer;
            }
            var head = new Match("episode", null, ms, me, m.group(1), 800, Set.of("weak-episode"), false);
            if (!seps.test(head)) continue;
            int v = Integer.parseInt(m.group(1));
            ctx.matches.add(new Match("episode", v, ms, me,
                m.group(1), 800, Set.of("weak-episode"), false));
        }
    }

    /** Replicates RemoveWeakIfMovie + RemoveWeak (drop weak-episode after audio/video/source). */
    @Override
    public void postProcess(ParseContext ctx) {
        if (ctx.matches.named("year").findAny().isPresent() && !"episode".equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return;
        }
        if ("movie".equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return;
        }

        // Drop weak episodes that directly follow an audio_codec/source/screen_size/streaming_service match.
        var blockingNames = Set.of("audio_codec", "screen_size", "streaming_service",
            "source", "video_profile", "audio_channels", "audio_profile");
        var blocking = ctx.matches.all().filter(m -> blockingNames.contains(m.name())).toList();
        var weaks = ctx.matches.named("episode").filter(m -> m.tags().contains("weak-episode")).toList();
        var toRemove = new ArrayList<Match>();
        for (var weak : weaks) {
            for (var b : blocking) {
                if (b.end() <= weak.start() && weak.start() - b.end() <= 3) {
                    String gap = ctx.input.substring(b.end(), weak.start());
                    if (gap.chars().allMatch(c -> Seps.isSep((char) c))) {
                        toRemove.add(weak);
                        break;
                    }
                }
            }
        }
        // Drop weaks if a SxxExx-tagged episode exists in the same filepart (RemoveWeakIfSxxExx).
        boolean strongPresent = ctx.matches.named("episode").anyMatch(m -> m.tags().contains("SxxExx"));
        if (strongPresent) {
            for (var weak : weaks) {
                if (weak.start() != 0) toRemove.add(weak);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void removeAllWeak(ParseContext ctx) {
        var weaks = ctx.matches.named("episode").filter(m -> m.tags().contains("weak-episode")).toList();
        for (var m : weaks) ctx.matches.remove(m);
    }
}
