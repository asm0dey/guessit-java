package io.guessit.engine.date;

enum DateShape {
    COMPACT_8_DIGIT,
    COMPACT_6_DIGIT,
    MONTH_WORD,
    NUMERIC_SEPARATED
}

record RawDateMatch(int start, int end, String rawDate, String[] parts, DateShape shape) {}