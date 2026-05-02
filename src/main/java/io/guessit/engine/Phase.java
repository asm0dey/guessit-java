package io.guessit.engine;

public sealed interface Phase permits MarkerPhase, ExtractorPhase, ConflictPhase, PostPhase, OutputPhase {
    void apply(ParseContext ctx);
}
