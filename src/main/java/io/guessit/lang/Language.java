package io.guessit.lang;

public record Language(String alpha2, String alpha3, String name, Country country) {
    /** Three-field constructor for backward compatibility — country defaults to null. */
    public Language(String alpha2, String alpha3, String name) {
        this(alpha2, alpha3, name, null);
    }

    /** Canonical short code: alpha-2 if available, else alpha-3. Mirrors babelfish.Language.__str__. */
    @Override public String toString() { return alpha2 != null ? alpha2 : alpha3; }
}
