package io.guessit.engine.numerals;

import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import com.mirkoddd.sift.core.engine.SiftCompiledPattern;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;
import static com.mirkoddd.sift.core.SiftPatterns.literal;

/**
 * Handles Roman Numbers using Sift.
 * Pure stateless parser without enforced Singleton pattern.
 */
final class RomanNumerals implements TokenNumeralParser {

    RomanNumerals() {
    }

    private enum RomanSymbol {
        I(1), V(5), X(10), L(50), C(100), D(500), M(1000);

        final int value;

        RomanSymbol(int value) {
            this.value = value;
        }

        char asChar() {
            return name().charAt(0);
        }

        private static final int[] LOOKUP = new int[128];

        static {
            for (RomanSymbol symbol : values()) {
                LOOKUP[symbol.asChar()] = symbol.value;
            }
        }

        static int getValue(char c) {
            return (c < 128) ? LOOKUP[c] : 0;
        }
    }

    static final SiftPattern<Fragment> PATTERN = buildPattern();

    private static final SiftCompiledPattern FULL_PATTERN = Sift.fromStart()
            .of(PATTERN)
            .andNothingElse()
            .sieve();

    private static SiftPattern<Fragment> buildPattern() {
        var thousands = between(0, 4).character(RomanSymbol.M.asChar());
        var hundreds = buildDigitGroup(RomanSymbol.C, RomanSymbol.D, RomanSymbol.M);
        var tens = buildDigitGroup(RomanSymbol.X, RomanSymbol.L, RomanSymbol.C);
        var ones = buildDigitGroup(RomanSymbol.I, RomanSymbol.V, RomanSymbol.X);

        return Sift.fromAnywhere()
                .mustBeFollowedBy(oneOrMore().of(romanChars()))
                .followedBy(List.of(thousands, hundreds, tens, ones));
    }

    private static SiftPattern<Fragment> romanChars() {
        var literals = Arrays.stream(RomanSymbol.values())
                .map(symbol -> literal(symbol.name()))
                .toList();

        return anyOf(literals);
    }

    private static SiftPattern<Fragment> buildDigitGroup(RomanSymbol base, RomanSymbol mid, RomanSymbol top) {
        String highSubtractive = base.name() + top.name();
        String lowSubtractive = base.name() + mid.name();

        return anyOf(
                literal(highSubtractive),
                literal(lowSubtractive),
                optional().character(mid.asChar()).then().between(0, 3).character(base.asChar())
        );
    }

    @Override
    public Integer tryParse(List<String> words) {
        return words.stream()
                .map(word -> parse(word.toUpperCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    static Integer parse(String value) {
        if (!FULL_PATTERN.matchesEntire(value)) return null;

        int total = 0;
        int rightValue = 0;

        for (int i = value.length() - 1; i >= 0; i--) {
            int currentValue = RomanSymbol.getValue(value.charAt(i));

            boolean isSubtractive = currentValue < rightValue;
            total += isSubtractive ? -currentValue : currentValue;

            rightValue = currentValue;
        }

        return total;
    }
}