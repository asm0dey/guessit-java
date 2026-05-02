package io.guessit.cli;

import io.guessit.GuessResult;

public final class YamlFormatter {
    private YamlFormatter() {}
    public static String format(GuessResult r) { return r.toYaml(); }
}
