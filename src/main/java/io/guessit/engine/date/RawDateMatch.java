package io.guessit.engine.date;

import java.util.List;

enum DateShape {
    COMPACT_8_DIGIT,
    COMPACT_6_DIGIT,
    MONTH_WORD,
    NUMERIC_SEPARATED
}

record RawDateMatch(int start, int end, String rawDate, List<String> parts, DateShape shape) {}