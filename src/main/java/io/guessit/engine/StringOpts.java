package io.guessit.engine;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Knobs for {@link PatternMatcher#string}. Defaults: case-insensitive,
 * whole-word required.
 *
 * <p>{@code wholeWord} = true means a needle only matches when both sides
 * are non-alphanumeric (or at the input boundary), so {@code "HD"} matches
 * {@code "video.HD.mkv"} but not {@code "AHD"}.
 */
public record StringOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    boolean caseSensitive,
    boolean wholeWord,
    Predicate<Match> validator
) {
    public StringOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (validator == null) validator = _ -> true;
    }
    public static StringOpts defaults() {
        return new StringOpts(1000, Set.of(), false, false, true, _ -> true);
    }
    public StringOpts withPriority(int p) { return new StringOpts(p, tags, isPrivate, caseSensitive, wholeWord, validator); }
    public StringOpts withTags(Set<String> t) { return new StringOpts(priority, t, isPrivate, caseSensitive, wholeWord, validator); }
    public StringOpts caseSensitive(boolean v) { return new StringOpts(priority, tags, isPrivate, v, wholeWord, validator); }
    public StringOpts wholeWord(boolean v) { return new StringOpts(priority, tags, isPrivate, caseSensitive, v, validator); }
    public StringOpts withValidator(Predicate<Match> v) { return new StringOpts(priority, tags, isPrivate, caseSensitive, wholeWord, v); }
}
