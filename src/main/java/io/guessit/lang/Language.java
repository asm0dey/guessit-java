package io.guessit.lang;

public record Language(String alpha2, String alpha3, String name) {
    /** Canonical short code: alpha-2 if available, else alpha-3. Mirrors babelfish.Language.__str__. */
    @Override public String toString() { return alpha2 != null ? alpha2 : alpha3; }
}
