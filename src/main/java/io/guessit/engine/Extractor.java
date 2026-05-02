package io.guessit.engine;

public interface Extractor {
    String name();
    default int priority() { return 1000; }
    void extract(ParseContext ctx);
    default void postProcess(ParseContext ctx) {}
}
