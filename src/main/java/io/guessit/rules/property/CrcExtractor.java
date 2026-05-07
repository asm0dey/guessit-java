package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Validators;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects 8-hex-digit CRC32 values, e.g. {@code [ABCD1234]} or {@code .ABCD1234.},
 * plus UUID/hash-like id numbers via the same heuristic used by python guessit
 * ({@code guess_idnumber}).
 *
 * <p>Priority is 500 (lower than season/episode at 1000) so ConflictPhase will
 * favour season/episode over crc32 when they overlap. uuid uses the default
 * priority but its conflict_solver in python keeps the uuid; here we lower it
 * just enough to lose to strong matches but win against bare digit weak ones.
 */
public final class CrcExtractor implements Extractor {
    private static final Pattern CRC = Pattern.compile("(?i)([0-9a-f]{8})");
    private static final Pattern UUID = Pattern.compile("([a-zA-Z0-9-]{20,})");
    private static final Pattern SXX_EXX_INSIDE = Pattern.compile("(?i)S\\d{1,3}E\\d{1,3}");

    @Override public String name() { return "crc32"; }
    @Override public int priority() { return 500; }

    @Override
    public void extract(ParseContext ctx) {
        extractCrc32(ctx);
        extractUuid(ctx);
        dropSeasonEpisodeInsideCrc(ctx);
    }

    /**
     * Drop season/episode (and the private {@code seasonHead} chain marker)
     * that fall entirely inside a crc32 span. Without this, an 8-hex-digit
     * checksum like {@code 99E8E009} is also matched by the
     * {@code (\d{1,2})?E(\d{1,4})} chain → false season=99, episode=[8,9].
     * Python guessit's _default_conflict_solver drops the shorter match
     * inside the crc32 span; the {@code coexist} tag on Java's SxxExx pairs
     * skips that comparison so mirror the drop explicitly. Run inside
     * {@link #extract} (after the SeasonEpisodeExtractor has already
     * emitted its chain) to keep
     * {@link io.guessit.rules.property.SeasonEpisodeExtractor#removeInvalidSecondaryChain}
     * from later mistaking the false SxxExx pair for a real one and
     * dropping legitimate weak-episode candidates outside the crc32 span.
     */
    @SuppressWarnings("JavadocReference")
    private void dropSeasonEpisodeInsideCrc(ParseContext ctx) {
        var crcSpans = ctx.matches.named(MatchName.CRC32)
            .map(m -> new int[]{m.start(), m.end()})
            .toList();
        if (crcSpans.isEmpty()) return;
        var toRemove = new java.util.ArrayList<Match>();
        ctx.matches.all().forEach(m -> {
            MatchName n = m.name();
            if (n != MatchName.SEASON && n != MatchName.EPISODE && n != MatchName.SEASON_HEAD) return;
            for (var s : crcSpans) {
                if (m.start() >= s[0] && m.end() <= s[1]) {
                    toRemove.add(m);
                    return;
                }
            }
        });
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void extractCrc32(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = CRC.matcher(input);
        while (m.find()) {
            var head = new Match(MatchName.CRC32, null, m.start(1), m.end(1), m.group(1), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            ctx.matches.add(new Match(MatchName.CRC32, m.group(1),
                m.start(1), m.end(1), m.group(1), priority(), Set.of(), false));
        }
    }

    private void extractUuid(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = UUID.matcher(input);
        while (m.find()) {
            var raw = m.group(1);
            if (!isLikelyIdNumber(raw)) continue;
            // Skip uuid candidates that swallow an SxxExx season+episode token.
            // The COEXIST tag on season/episode means ConflictSolver leaves the
            // uuid in place, so without this guard a string like
            // "S01E01E07-FooBar-Group" becomes uuid AND blocks episode_title.
            if (SXX_EXX_INSIDE.matcher(raw).find()) continue;
            var head = new Match(MatchName.UUID, null, m.start(1), m.end(1), raw, priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            ctx.matches.add(new Match(MatchName.UUID, raw,
                m.start(1), m.end(1), raw, priority(), Set.of(), false));
        }
    }

    /**
     * Heuristic: count character-type switches (digit/letter/other) and
     * adjacent-letter switches; accept when both ratios exceed 0.4. Mirrors
     * python {@code guess_idnumber}.
     */
    private static final int CC_DIGIT = 0;
    private static final int CC_LETTER = 1;
    private static final int CC_OTHER = 2;

    private static boolean isLikelyIdNumber(String s) {
        int last = CC_LETTER;
        int switchCount = 0;
        int switchLetterCount = 0;
        int letterCount = 0;
        char lastLetter = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int ci = classifyChar(c);
            if (ci == CC_LETTER) {
                if (c != lastLetter) switchLetterCount++;
                lastLetter = c;
                letterCount++;
            }
            if (ci != last) switchCount++;
            last = ci;
        }
        double switchRatio = (double) switchCount / s.length();
        double lettersRatio = letterCount == 0 ? 1.0 : (double) switchLetterCount / letterCount;
        return switchRatio > 0.4 && lettersRatio > 0.4;
    }

    private static int classifyChar(char c) {
        if (c >= '0' && c <= '9') return CC_DIGIT;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return CC_LETTER;
        return CC_OTHER;
    }
}
