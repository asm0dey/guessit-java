package io.guessit.cli;

import io.guessit.GuessResult;

public final class JsonFormatter {
    private JsonFormatter() {}
    public static String format(GuessResult r) { return r.toJson(); }
}
