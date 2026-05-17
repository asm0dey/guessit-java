package io.guessit.engine.numerals;

import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import com.mirkoddd.sift.core.engine.SiftCompiledPattern;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

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

    private static final DigitNumerals DIGIT_PARSER = new DigitNumerals();
    private static final RomanNumerals ROMAN_PARSER = new RomanNumerals();
    private static final WordNumerals WORD_PARSER = new WordNumerals();

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
    public static int parse(String value, Set<Type> enabledTypes) {

        if (enabledTypes.contains(Type.DIGIT)) {
            Integer result = DIGIT_PARSER.tryParse(value);
            if (result != null) return result;
        }

        if (enabledTypes.contains(Type.ROMAN) || enabledTypes.contains(Type.WORD)) {
            var words = WS_SPLIT_PATTERN.splitBy(value);

            return Stream.of(Type.ROMAN, Type.WORD)
                    .filter(enabledTypes::contains)
                    .map(type -> type == Type.ROMAN ? ROMAN_PARSER : WORD_PARSER)
                    .map(parser -> parser.tryParse(words))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Invalid numeral: " + value));
        }

        throw new IllegalArgumentException("Invalid numeral: " + value);
    }
}