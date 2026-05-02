package io.guessit.lang;

public record Country(String alpha2, String name) {
    @Override public String toString() { return name; }
}
