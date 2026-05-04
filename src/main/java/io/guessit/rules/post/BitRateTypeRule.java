package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase;
import io.guessit.engine.Seps;
import io.guessit.util.BitRate;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * PostPhase processor that converts {@code audio_bit_rate} matches to
 * {@code video_bit_rate} when they are preceded by a video-context match
 * ({@code screen_size}, {@code source}, or {@code video_codec}) with only
 * separator characters in the gap between them.
 *
 * <p>Exception: if an {@code audio_codec} match immediately follows the bit
 * rate (gap is all separators) and the bit rate value is in Kbps or is
 * Mbps&lt;10, it stays as {@code audio_bit_rate} (e.g. {@code DD5.1.448kbps}).
 *
 * <p>Mirrors Python guessit's {@code BitRateTypeRule}.
 */
public final class BitRateTypeRule implements PostPhase.PostProcessor {

    private static final java.util.Set<String> VIDEO_CONTEXT = java.util.Set.of(
            "source", "screen_size", "video_codec");

    @Override
    public void process(ParseContext ctx) {
        var bitRates = ctx.matches.named("audio_bit_rate")
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        if (bitRates.isEmpty()) return;

        var allMatches = ctx.matches.snapshot().stream()
                .filter(m -> !m.isPrivate())
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        var toRename = new ArrayList<Match>();

        for (var br : bitRates) {
            // Find the nearest preceding match with a video-context name.
            Match prev = null;
            for (var m : allMatches) {
                if (m.end() > br.start()) break;
                if (VIDEO_CONTEXT.contains(m.name())) prev = m;
            }
            if (prev == null) continue;

            // Gap between prev and br must be all separators.
            var gap1 = ctx.input.substring(prev.end(), br.start());
            if (!allSeps(gap1)) continue;

            // Check if there's an audio_codec immediately after.
            Match nextAudioCodec = null;
            for (var m : allMatches) {
                if (m.start() < br.end()) continue;
                if ("audio_codec".equals(m.name())) {
                    var gap2 = ctx.input.substring(br.end(), m.start());
                    if (allSeps(gap2)) nextAudioCodec = m;
                }
                break; // only look at the very next match
            }

            if (nextAudioCodec != null && br.value() instanceof BitRate bitRate) {
                var fmt = bitRate.format();
                if (fmt.endsWith("Kbps")) continue; // keep as audio
                if (fmt.endsWith("Mbps")) {
                    double mag = bitRate.value();
                    if (mag < 10.0) continue; // keep as audio
                }
            }


            toRename.add(br);
        }

        for (var m : toRename) {
            ctx.matches.replace(m, m.withName("video_bit_rate"));
        }
    }

    private static boolean allSeps(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Seps.isSep(s.charAt(i))) return false;
        }
        return true;
    }
}
