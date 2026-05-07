package io.guessit.engine;

/**
 * One-line human-readable description of a pipeline component. Used by the
 * upcoming DebugTrace when emitting prose narration. Implementations should
 * return a short noun phrase or sentence (no trailing punctuation), e.g.
 * {@code "4-digit year (19xx/20xx)"}.
 */
public interface Described {
    String description();
}
