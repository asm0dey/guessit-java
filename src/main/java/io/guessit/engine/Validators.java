package io.guessit.engine;

import java.util.function.Predicate;

/**
 * Reusable {@link Match} predicates wired into {@link RegexOpts#validator}.
 *
 * <p>Most extractors require their match to stand as a separate token; the
 * separator-surround validators express that without baking word-boundary
 * assertions into every regex. Separators are defined by {@link Seps#CHARS}
 * (a verbatim copy of Python guessit's {@code seps}).
 */
@SuppressWarnings("JavadocReference")
public final class Validators {
    private Validators() {}

    /** True when the match starts at index 0 or is preceded by a separator. */
    public static Predicate<Match> sepsBefore(String input) {
        return m -> m.start() == 0 || Seps.isSep(input.charAt(m.start() - 1));
    }

    /** True when the match ends at the input end or is followed by a separator. */
    public static Predicate<Match> sepsAfter(String input) {
        return m -> m.end() == input.length() || Seps.isSep(input.charAt(m.end()));
    }

    /** Conjunction of {@link #sepsBefore} and {@link #sepsAfter}. */
    public static Predicate<Match> sepsSurround(String input) {
        var before = sepsBefore(input);
        var after = sepsAfter(input);
        return m -> before.test(m) && after.test(m);
    }
}
