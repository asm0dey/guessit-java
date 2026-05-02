package io.guessit.cli;

import io.guessit.GuessResult;

public final class PlainFormatter {
    private PlainFormatter() {}

    public static String format(GuessResult r) {
        var sb = new StringBuilder();
        for (var e : r.toMap().entrySet()) {
            var v = e.getValue();
            String rendered = v instanceof java.util.List<?> l
                ? String.join(", ", l.stream().map(Object::toString).toList())
                : v.toString();
            sb.append(e.getKey()).append(": ").append(rendered).append("\n");
        }
        return sb.toString();
    }
}
