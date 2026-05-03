package io.guessit.engine;

/**
 * Token-separator character set.
 *
 * <p>Defines what counts as "between tokens" throughout the parser —
 * separator-surround validation in {@link Validators}, the small whitelist of
 * inter-tail characters in {@link Chain}, and any rule that needs to test
 * adjacency. The character set is a verbatim copy of Python guessit's
 * {@code seps}; keep them in lockstep to preserve YML parity.
 */
public final class Seps {
    private Seps() {}

    /** Verbatim copy of Python guessit's seps constant (rules/common/__init__.py). */
    public static final String CHARS = " [](){}+*|=-_~#/\\.,;:";

    /** Python guessit's title_seps — the subset used to split a hole into title + alternative_title. */
    public static final String TITLE_CHARS = "-+/\\|";

    private static final boolean[] LOOKUP = new boolean[128];
    static {
        for (char c : CHARS.toCharArray()) {
            LOOKUP[c] = true;
        }
    }

    public static boolean isSep(char c) {
        return c < 128 && LOOKUP[c];
    }

    /** Returns the separator chars escaped for use inside a `[...]` regex character class. */
    public static String regexCharClass() {
        var sb = new StringBuilder(CHARS.length() * 2);
        for (char c : CHARS.toCharArray()) {
            // Inside [...]: ] \ ^ - require escaping. Other regex metas are literal in classes.
            if (c == ']' || c == '\\' || c == '^' || c == '-' || c == '[') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
