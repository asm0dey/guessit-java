package io.guessit.engine;

import java.util.function.Predicate;

public final class Validators {
    private Validators() {}

    public static Predicate<Match> sepsBefore(String input) {
        return m -> m.start() == 0 || Seps.isSep(input.charAt(m.start() - 1));
    }

    public static Predicate<Match> sepsAfter(String input) {
        return m -> m.end() == input.length() || Seps.isSep(input.charAt(m.end()));
    }

    public static Predicate<Match> sepsSurround(String input) {
        var before = sepsBefore(input);
        var after = sepsAfter(input);
        return m -> before.test(m) && after.test(m);
    }
}
