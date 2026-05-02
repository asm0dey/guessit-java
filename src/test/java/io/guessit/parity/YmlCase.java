package io.guessit.parity;

import io.guessit.Options;

import java.util.Map;

public record YmlCase(
    String file,
    int line,
    String input,
    Map<String, Object> expected,
    Options options,
    boolean negative
) {
    @Override
    public String toString() {
        return file + ":" + line + " \"" + input + "\"";
    }
}
