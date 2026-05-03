package io.guessit.lang;

public record Country(String alpha2, String name) {
    /** Canonical short code (alpha-2). Mirrors Python babelfish.Country.__str__. */
    @Override public String toString() { return alpha2; }
}
