package io.guessit.engine;

public sealed interface Phase permits MarkerPhase, ExtractorPhase, ConflictPhase, ExtractorPostPhase, PostPhase, OutputPhase {
    void apply(ParseContext ctx);
}
