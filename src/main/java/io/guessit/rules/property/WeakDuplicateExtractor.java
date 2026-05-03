package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Validators;

import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises 3-4 digit runs that could plausibly be a {@code SSEE} compact
 * season+episode (e.g. "0102" → S01E02). Emitted at priority 700 with the
 * {@code weak-duplicate} + {@code coexist} tags so they don't fight stronger
 * matches in the conflict solver.
 *
 * <p>The post-pass kills these candidates whenever a real {@code SxxExx} or
 * word-form season/episode exists; the compact form is only useful when no
 * canonical form is present.
 *
 * <p>Skipped entirely when the user hinted {@code movie} or set
 * {@code episode_prefer_number}, since both indicate the digits are not a
 * compact season+episode.
 */
public final class WeakDuplicateExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})(\\d{2})(?!\\d)");
    public static final String WEAK_DUPLICATE = "weak-duplicate";

    @Override
    public String name() {
        return "weak_duplicate";
    }

    @Override
    public int priority() {
        return 700;
    }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        if (Boolean.TRUE.equals(ctx.options.episodePreferNumber())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        while (m.find()) {
            var span = new Match("weak", null, m.start(), m.end(), m.group(), 700, Set.of(), false);
            if (!seps.test(span)) continue;
            int s = Integer.parseInt(m.group(1));
            int e = Integer.parseInt(m.group(2));
            ctx.matches.add(new Match("season", s, m.start(1), m.end(1),
                    m.group(1), 700, Set.of("weak-episode", WEAK_DUPLICATE, "coexist"), false));
            ctx.matches.add(new Match("episode", e, m.start(2), m.end(2),
                    m.group(2), 700, Set.of("weak-episode", WEAK_DUPLICATE, "coexist"), false));
        }
    }

    /**
     * Replicates RemoveWeakDuplicate: drop the weak-duplicate pair when a strong SxxExx exists.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        // Drop weak-episode matches that overlap with weak-duplicate matches
        var weakDuplicates = ctx.matches.all().filter(m -> m.tags().contains(WEAK_DUPLICATE)).toList();
        if (!weakDuplicates.isEmpty()) {
            var toRemove = ctx.matches.all()
                    .filter(m -> m.tags().contains("weak-episode") && !m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> weakDuplicates.stream().anyMatch(wd -> wd.overlaps(m)))
                    .toList();
            for (var m : toRemove) ctx.matches.remove(m);
        }

        boolean strongPresent = ctx.matches.named("episode").anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("episode-word"))
                || ctx.matches.named("season").anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("season-word"));
        var years = ctx.matches.named("year").toList();
        var codecs = ctx.matches.all()
                .filter(x -> x.name().equals("video_codec") || x.name().equals("audio_codec"))
                .toList();
        var toRemove = new ArrayList<Match>();
        for (var name : new String[]{"season", "episode"}) {
            for (var m : ctx.matches.named(name).toList()) {
                if (!m.tags().contains(WEAK_DUPLICATE)) continue;
                if (strongPresent) {
                    toRemove.add(m);
                    continue;
                }
                if (years.stream().anyMatch(y -> y.overlaps(m))) {
                    toRemove.add(m);
                    continue;
                }
                if (codecs.stream().anyMatch(c -> c.overlaps(m))) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
