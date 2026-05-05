package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Validators;
import io.guessit.util.BitRate;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts bit rate values from filenames.
 *
 * <p>All matches are initially tagged as {@code audio_bit_rate}. A subsequent
 * {@link io.guessit.rules.post.BitRateTypeRule} (registered in {@link io.guessit.rules.Rules}) promotes matches
 * to {@code video_bit_rate} when they are preceded by a video-context match
 * ({@code screen_size}, {@code source}, or {@code video_codec}) with no non-separator gap.
 *
 * <p>This mirrors Python guessit's {@code bit_rate.py} / {@code BitRateTypeRule}.
 */
public final class BitRateExtractor implements Extractor {
    // Two regexes mirror python rebulk emitting both. The simpler match wins
    // when the dotted form overlaps an audio_channels match (handled in postProcess).
    private static final Pattern P_INT = Pattern.compile(
        "(?i)(\\d+[ ._-]?[kmg]b(?:ps|its?))");
    private static final Pattern P_DEC = Pattern.compile(
        "(?i)(\\d+\\.\\d+[ ._-]?[kmg]b(?:ps|its?))");

    @Override public String name() { return "audio_bit_rate"; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        // Mirror python rebulk's per-pattern conflict_solver: skip a candidate
        // bit_rate match when it overlaps an existing non-weak audio_channels
        // match so "5.1.448kbps" → channels=5.1 + bit_rate=448kbps, not the
        // longer "1.448kbps" winning by length in ConflictSolver.
        var channels = ctx.matches.named("audio_channels")
            .filter(m -> !m.tags().contains("weak-audio_channels"))
            .toList();
        for (var p : new Pattern[]{P_INT, P_DEC}) {
            var m = p.matcher(input);
            while (m.find()) {
                int s = m.start(1), e = m.end(1);
                var head = new Match("audio_bit_rate", null, s, e, m.group(1), priority(), Set.of(), false);
                if (!seps.test(head)) continue;
                boolean overlapsChannels = false;
                for (var ch : channels) {
                    if (s < ch.end() && ch.start() < e) { overlapsChannels = true; break; }
                }
                if (overlapsChannels) continue;
                var raw = m.group(1);
                ctx.matches.add(new Match("audio_bit_rate", BitRate.fromString(raw), s, e, raw,
                    priority(), Set.of("release-group-prefix"), false));
            }
        }
    }
}
