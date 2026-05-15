package io.guessit.engine;

import com.mirkoddd.sift.core.NamedCapture;
import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import com.mirkoddd.sift.core.engine.SiftCompiledPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mirkoddd.sift.core.Sift.between;
import static com.mirkoddd.sift.core.Sift.oneOrMore;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Numeral parsing and regex sources for digits, Roman numerals, and English /
 * French number words.
 *
 * <p>Supports episode/season parsers that must accept "Episode V" or
 * "Episode three" alongside "Episode 5". {@link #NUMERAL_PATTERN} composes the three
 * sub-patterns into a single alternation suitable for embedding in larger
 * regexes; {@link #parse} converts any of the three forms to {@code int}.
 */
public final class Numerals {
    private Numerals() {
    }

    public static final SiftPattern<Fragment> DIGITAL_PATTERN = between(1, 4).digits();

    private static final SiftPattern<Fragment> ROMAN_CHARS = anyOf(
            literal("M"), literal("C"), literal("D"), literal("L"), literal("X"), literal("V"), literal("I")
    );

    private static final SiftPattern<Fragment> THOUSANDS = between(0, 4).character('M');

    private static final SiftPattern<Fragment> HUNDREDS = anyOf(
            literal("CM"),
            literal("CD"),
            Sift.fromAnywhere().optional().character('D').then().between(0, 3).character('C')
    );

    private static final SiftPattern<Fragment> TENS = anyOf(
            literal("XC"),
            literal("XL"),
            Sift.fromAnywhere().optional().character('L').then().between(0, 3).character('X')
    );

    private static final SiftPattern<Fragment> ONES = anyOf(
            literal("IX"),
            literal("IV"),
            Sift.fromAnywhere().optional().character('V').then().between(0, 3).character('I')
    );

    public static final SiftPattern<Fragment> ROMAN_PATTERN = Sift.fromAnywhere()
            .mustBeFollowedBy(oneOrMore().of(ROMAN_CHARS))
            .followedBy(List.of(THOUSANDS, HUNDREDS, TENS, ONES));

    public static final List<String> ENGLISH_WORDS = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty");
    public static final List<String> FRENCH_WORDS = List.of(
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
            "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf", "vingt");
    public static final List<String> FRENCH_ALT_WORDS = List.of(
            "zero", "une", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
            "onze", "douze", "treize", "quatorze", "quinze", "seize", "dixsept", "dixhuit", "dixneuf", "vingt");

    private static final SiftCompiledPattern ROMAN_FULL_PATTERN = Sift.fromStart()
            .of(ROMAN_PATTERN)
            .andNothingElse()
            .sieve();

    private static final String CLEAN_GROUP_NAME = "number";
    private static final NamedCapture CLEAN_GROUP = capture(CLEAN_GROUP_NAME, oneOrMore().digits());
    private static final SiftCompiledPattern CLEAN_PATTERN = Sift.fromStart()
            .zeroOrMore().nonDigits().withoutBacktracking()
            .then().namedCapture(CLEAN_GROUP)
            .then().zeroOrMore().nonDigits().withoutBacktracking()
            .andNothingElse()
            .sieve();

    private static final SiftCompiledPattern WS_SPLIT_PATTERN = oneOrMore().whitespace().sieve();

    private static final List<List<String>> WORD_LISTS = List.of(ENGLISH_WORDS, FRENCH_WORDS, FRENCH_ALT_WORDS);

    public static final SiftPattern<Fragment> WORD_PATTERN = buildWordPattern();

    public static final SiftPattern<Fragment> NUMERAL_PATTERN = anyOf(
            DIGITAL_PATTERN,
            ROMAN_PATTERN,
            WORD_PATTERN
    );

    /**
     * Parse digits, Roman, or word numerals — whichever matches first.
     */
    public static int parse(String value) {
        return parse(value, true, true, true);
    }

    /**
     * Parse {@code value} as a numeral, trying enabled forms in order:
     * decimal digits → Roman → number words. Throws {@link IllegalArgumentException}
     * when no enabled form matches.
     */
    public static int parse(String value, boolean intEnabled, boolean romanEnabled, boolean wordEnabled) {
        if (intEnabled) {
            var i = tryParseInt(value);
            if (i != null) return i;
        }
        if (romanEnabled) {
            var r = tryParseRomanWords(value);
            if (r != null) return r;
        }
        if (wordEnabled) {
            var w = tryParseWordNumerals(value);
            if (w != null) return w;
        }
        throw new IllegalArgumentException("Invalid numeral: " + value);
    }

    private static Integer tryParseInt(String value) {
        String cleanValue = extractCleanValue(value);
        try {
            return Integer.valueOf(cleanValue);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static String extractCleanValue(String value) {
        Map<String, String> groups = CLEAN_PATTERN.extractGroups(value);

        if (groups.containsKey(CLEAN_GROUP_NAME)) {
            return groups.get(CLEAN_GROUP_NAME);
        }

        return value;
    }

    private static Integer tryParseRomanWords(String value) {
        for (var word : WS_SPLIT_PATTERN.splitBy(value)) {
            try {
                return (Integer) parseRoman(word.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException _) {
            }
        }
        return null;
    }

    private static Integer tryParseWordNumerals(String value) {
        for (var word : WS_SPLIT_PATTERN.splitBy(value)) {
            int idx = wordIndex(word);
            if (idx >= 0) return (Integer) idx;
        }
        return null;
    }

    private static int wordIndex(String word) {
        var lower = word.toLowerCase(java.util.Locale.ROOT);
        for (var list : WORD_LISTS) {
            int idx = list.indexOf(lower);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    /**
     * Standard left-to-right Roman parser: tokens are sorted by descending
     * value with subtractive pairs (CM, CD, XC, …) listed before their base
     * letters, so "IX" is consumed as 9 before "I" can match.
     */
    private static int parseRoman(String value) {
        if (!ROMAN_FULL_PATTERN.matchesEntire(value)) throw new IllegalArgumentException("Not roman: " + value);
        String[] tokens = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        int result = 0;
        int i = 0;
        for (int k = 0; k < tokens.length; k++) {
            while (value.startsWith(tokens[k], i)) {
                result += values[k];
                i += tokens[k].length();
            }
        }
        return result;
    }

    private static SiftPattern<Fragment> buildWordPattern() {
        List<SiftPattern<Fragment>> wordPatterns = new ArrayList<>();
        for (var list : WORD_LISTS) {
            for (var w : list) {
                wordPatterns.add(literal(w));
            }
        }

        SiftPattern<Fragment> allWordsOr = anyOf(wordPatterns);

        return Sift.fromAnywhere()
                .mustBeFollowedBy(oneOrMore().wordCharacters())
                .then().of(allWordsOr);
    }
}
