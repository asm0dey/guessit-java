package io.guessit.engine;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Numeral parsing and regex sources for digits, Roman numerals, and English /
 * French number words.
 *
 * <p>Supports episode/season parsers that must accept "Episode V" or
 * "Episode three" alongside "Episode 5". {@link #NUMERAL} composes the three
 * sub-patterns into a single alternation suitable for embedding in larger
 * regexes; {@link #parse} converts any of the three forms to {@code int}.
 */
public final class Numerals {
    private Numerals() {}

    public static final String DIGITAL = "\\d{1,4}";
    public static final String ROMAN = "(?=[MCDLXVI]+)M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})";

    public static final List<String> ENGLISH_WORDS = List.of(
        "zero","one","two","three","four","five","six","seven","eight","nine","ten",
        "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen","twenty");
    public static final List<String> FRENCH_WORDS = List.of(
        "zéro","un","deux","trois","quatre","cinq","six","sept","huit","neuf","dix",
        "onze","douze","treize","quatorze","quinze","seize","dix-sept","dix-huit","dix-neuf","vingt");
    public static final List<String> FRENCH_ALT_WORDS = List.of(
        "zero","une","deux","trois","quatre","cinq","six","sept","huit","neuf","dix",
        "onze","douze","treize","quatorze","quinze","seize","dixsept","dixhuit","dixneuf","vingt");

    private static final Pattern ROMAN_FULL = Pattern.compile("^" + ROMAN + "$");
    private static final Pattern CLEAN = Pattern.compile("[^\\d]*(\\d+)[^\\d]*");
    private static final Pattern WS_SPLIT = Pattern.compile("\\s+");

    private static final List<List<String>> WORD_LISTS = List.of(ENGLISH_WORDS, FRENCH_WORDS, FRENCH_ALT_WORDS);

    public static final String WORD = buildWordSource();
    public static final String NUMERAL = "(?:" + DIGITAL + "|" + ROMAN + "|" + WORD + ")";

    /** Parse digits, Roman, or word numerals — whichever matches first. */
    public static int parse(String value) { return parse(value, true, true, true); }

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
        try {
            var m = CLEAN.matcher(value);
            if (m.matches()) return Integer.parseInt(m.group(1));
            return Integer.parseInt(value);
        } catch (NumberFormatException _) { return null; }
    }

    private static Integer tryParseRomanWords(String value) {
        for (var word : WS_SPLIT.split(value)) {
            try { return parseRoman(word.toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException _) {}
        }
        return null;
    }

    private static Integer tryParseWordNumerals(String value) {
        for (var word : WS_SPLIT.split(value)) {
            int idx = wordIndex(word);
            if (idx >= 0) return idx;
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
        if (!ROMAN_FULL.matcher(value).matches()) throw new IllegalArgumentException("Not roman: " + value);
        String[] tokens = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        int[] values = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
        int result = 0, i = 0;
        for (int k = 0; k < tokens.length; k++) {
            while (value.startsWith(tokens[k], i)) {
                result += values[k];
                i += tokens[k].length();
            }
        }
        return result;
    }

    private static String buildWordSource() {
        var sb = new StringBuilder("(?:(?=\\w+)");
        boolean first = true;
        for (var list : WORD_LISTS) {
            for (var w : list) {
                if (!first) sb.append('|');
                sb.append(java.util.regex.Pattern.quote(w));
                first = false;
            }
        }
        sb.append(')');
        return sb.toString();
    }
}
