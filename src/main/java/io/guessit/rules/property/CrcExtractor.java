package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

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

    public static final String EXTRACTOR_NAME = "crc32";
    private static final int EXTRACTOR_PRIORITY = 500;
    private static final String GRP_VALUE = "val";

    private static final Pattern CRC = buildCrcPattern();
    private static final Pattern UUID = buildUuidPattern();
    private static final Pattern SXX_EXX_INSIDE = buildSxxExxPattern();

    private static Pattern buildCrcPattern() {
        var hex = exactly(8).hexDigits();
        var sift = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere()
                .namedCapture(capture(GRP_VALUE, hex));
        return Pattern.compile(sift.shake());
    }

    private static Pattern buildUuidPattern() {
        var alphaNumeric = exactly(1).alphanumeric();
        var dash = exactly(1).character('-');
        var validChars = anyOf(alphaNumeric, dash);
        var uuidFragment = atLeast(20).of(validChars);
        var sift = fromAnywhere().namedCapture(capture(GRP_VALUE, uuidFragment));
        return Pattern.compile(sift.shake());
    }

    private static Pattern buildSxxExxPattern() {
        var digits = between(1, 3).digits();
        var sxx = exactly(1).character('s').followedBy(digits);
        var exx = exactly(1).character('e').followedBy(digits);
        var sift = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere()
                .of(sxx).followedBy(exx);

        return Pattern.compile(sift.shake());
    }

    @Override
    public String name() {
        return EXTRACTOR_NAME;
    }

    @Override
    public int priority() {
        return EXTRACTOR_PRIORITY;
    }

    @Override
    public String description() {
        return "CRC32 checksum (8 hex chars)";
    }

    @Override
    public void extract(ParseContext ctx) {
        extractCrc32(ctx);
        extractUuid(ctx);
        dropSeasonEpisodeInsideCrc(ctx);
    }

    private void dropSeasonEpisodeInsideCrc(ParseContext ctx) {
        var crcSpans = ctx.matches.named(MatchName.CRC32)
                .map(m -> new int[]{m.start(), m.end()})
                .toList();

        if (crcSpans.isEmpty()) return;

        var toRemove = ctx.matches.all()
                .filter(m -> {
                    var n = m.name();
                    return n == MatchName.SEASON || n == MatchName.EPISODE || n == MatchName.SEASON_HEAD;
                })
                .filter(m -> crcSpans.stream().anyMatch(s -> m.start() >= s[0] && m.end() <= s[1]))
                .toList();

        toRemove.forEach(ctx.matches::remove);
    }

    private void extractCrc32(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = CRC.matcher(input);

        while (m.find()) {
            var val = m.group(GRP_VALUE);
            var head = new Match(MatchName.CRC32, null, m.start(GRP_VALUE), m.end(GRP_VALUE), val, priority(), Set.of(), false);

            if (seps.test(head)) {
                ctx.matches.add(new Match(MatchName.CRC32, val,
                        m.start(GRP_VALUE), m.end(GRP_VALUE), val, priority(), Set.of(), false));
            }
        }
    }

    private void extractUuid(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = UUID.matcher(input);

        while (m.find()) {
            var raw = m.group(GRP_VALUE);

            if (isLikelyIdNumber(raw) && !SXX_EXX_INSIDE.matcher(raw).find()) {
                var head = new Match(MatchName.UUID, null, m.start(GRP_VALUE), m.end(GRP_VALUE), raw, priority(), Set.of(), false);

                if (seps.test(head)) {
                    ctx.matches.add(new Match(MatchName.UUID, raw,
                            m.start(GRP_VALUE), m.end(GRP_VALUE), raw, priority(), Set.of(), false));
                }
            }
        }
    }

    private enum CharType { DIGIT, LETTER, OTHER }

    private static boolean isLikelyIdNumber(String s) {
        CharType lastType = CharType.LETTER;
        int switchCount = 0;
        int switchLetterCount = 0;
        int letterCount = 0;
        char lastLetter = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            CharType currentType = classifyChar(c);

            if (currentType == CharType.LETTER) {
                if (c != lastLetter) switchLetterCount++;
                lastLetter = c;
                letterCount++;
            }

            if (currentType != lastType) switchCount++;
            lastType = currentType;
        }

        double switchRatio = (double) switchCount / s.length();
        double lettersRatio = letterCount == 0 ? 1.0 : (double) switchLetterCount / letterCount;
        return switchRatio > 0.4 && lettersRatio > 0.4;
    }

    private static CharType classifyChar(char c) {
        if (Character.isDigit(c)) return CharType.DIGIT;
        if (Character.isLetter(c)) return CharType.LETTER;
        return CharType.OTHER;
    }
}