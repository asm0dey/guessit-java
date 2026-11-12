package io.guessit.engine.numerals;

/**
 * Strategy for parsing raw numeral strings (e.g., "123").
 */
interface RawNumeralParser {
    Integer tryParse(String rawValue);
}