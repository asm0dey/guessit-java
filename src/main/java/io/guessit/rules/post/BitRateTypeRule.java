package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase;
import io.guessit.engine.Seps;
import io.guessit.util.BitRate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    private static final java.util.Set<MatchName> VIDEO_CONTEXT = java.util.Set.of(
            MatchName.SOURCE, MatchName.SCREEN_SIZE, MatchName.VIDEO_CODEC);

    @Override
    public String description() {
        return "split bit_rate into audio_bit_rate / video_bit_rate";
    }

    @Override
    public void process(ParseContext ctx) {
        var bitRates = ctx.matches.named(MatchName.AUDIO_BIT_RATE)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        if (bitRates.isEmpty()) return;

        var allMatches = ctx.matches.snapshot().stream()
                .filter(m -> !m.isPrivate())
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        var toRename = new ArrayList<Match>();
        for (var br : bitRates) {
            if (shouldRenameToVideo(ctx, br, allMatches)) toRename.add(br);
        }
        for (var m : toRename) {
            ctx.matches.replace(m, m.withName(MatchName.VIDEO_BIT_RATE));
        }
    }

    /** True iff {@code br} sits in a video context (preceded by source/screen_size/codec
     *  with sep-only gap) AND the trailing audio_codec exception doesn't keep it as audio. */
    private static boolean shouldRenameToVideo(ParseContext ctx, Match br, List<Match> allMatches) {
        var prev = nearestPrecedingVideoContext(allMatches, br);
        if (prev == null) return false;
        if (!allSeps(ctx.input.substring(prev.end(), br.start()))) return false;

        var nextAudioCodec = adjacentTrailingAudioCodec(ctx, allMatches, br);
        return !shouldKeepAsAudio(br, nextAudioCodec);
    }

    private static Match nearestPrecedingVideoContext(List<Match> allMatches, Match br) {
        Match prev = null;
        for (var m : allMatches) {
            if (m.end() > br.start()) break;
            if (VIDEO_CONTEXT.contains(m.name())) prev = m;
        }
        return prev;
    }

    /** First match starting at or after {@code br.end()}; returns it only if
     *  it is an {@code audio_codec} reachable through a sep-only gap. */
    private static Match adjacentTrailingAudioCodec(ParseContext ctx, List<Match> allMatches, Match br) {
        for (var m : allMatches) {
            if (m.start() < br.end()) continue;
            if (m.name() != MatchName.AUDIO_CODEC) return null;
            return allSeps(ctx.input.substring(br.end(), m.start())) ? m : null;
        }
        return null;
    }

    /** Audio-codec exception: trailing audio_codec + Kbps or Mbps&lt;10 keeps as audio. */
    private static boolean shouldKeepAsAudio(Match br, Match nextAudioCodec) {
        if (nextAudioCodec == null) return false;
        if (!(br.value() instanceof BitRate bitRate)) return false;
        var fmt = bitRate.format();
        if (fmt.endsWith("Kbps")) return true;
        return fmt.endsWith("Mbps") && bitRate.value() < 10.0;
    }

    private static boolean allSeps(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Seps.isSep(s.charAt(i))) return false;
        }
        return true;
    }
}
