package io.guessit.engine.numerals;

import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import com.mirkoddd.sift.core.engine.SiftCompiledPattern;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.mirkoddd.sift.core.Sift.oneOrMore;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;

/**
 * Public Facade for numeral parsing. Composes digital, Roman, and localized word patterns
 * and orchestrates the multi-strategy parsing flow.
 */
public final class Numerals {

    /**
     * Defines the supported numeral parsing strategies.
     */
    public enum Type {
        DIGIT, ROMAN, WORD
    }

    private Numerals() {
    }

    public static final SiftPattern<Fragment> NUMERAL_PATTERN = anyOf(
            DigitNumerals.PATTERN,
            RomanNumerals.PATTERN,
            WordNumerals.PATTERN
    );

    private static final SiftCompiledPattern WS_SPLIT_PATTERN = oneOrMore().whitespace().sieve();

    /**
     * Parse digits, Roman, or word numerals — whichever matches first.
     */
    public static int parse(String value) {
        return parse(value, EnumSet.allOf(Type.class));
    }

    /**
     * Parse {@code value} as a numeral, trying enabled forms in order:
     * decimal digits → Roman → number words. Throws {@link IllegalArgumentException}
     * when no enabled form matches.
     */
    public static int parse(String value, EnumSet<Type> enabledTypes) {

        if (enabledTypes.contains(Type.DIGIT)) {
            Integer result = DigitNumerals.INSTANCE.tryParse(value);
            if (result != null) return result;
        }

        List<TokenNumeralParser> tokenParsers = new ArrayList<>();
        if (enabledTypes.contains(Type.ROMAN)) tokenParsers.add(RomanNumerals.INSTANCE);
        if (enabledTypes.contains(Type.WORD)) tokenParsers.add(WordNumerals.INSTANCE);

        if (!tokenParsers.isEmpty()) {
            var words = WS_SPLIT_PATTERN.splitBy(value);

            for (TokenNumeralParser parser : tokenParsers) {
                Integer result = parser.tryParse(words);
                if (result != null) return result;
            }
        }

        throw new IllegalArgumentException("Invalid numeral: " + value);
    }
}