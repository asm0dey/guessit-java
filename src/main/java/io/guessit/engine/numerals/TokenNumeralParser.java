package io.guessit.engine.numerals;

import java.util.List;

/**
 * Strategy for parsing pre-tokenized numeral strings (e.g., ["Episode", "IV"]).
 */
interface TokenNumeralParser {
    Integer tryParse(List<String> words);
}