package io.guessit.lang;

public record Language(String alpha2, String alpha3, String name) {
    @Override public String toString() { return name; }
}
