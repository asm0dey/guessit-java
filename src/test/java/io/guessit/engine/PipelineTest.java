package io.guessit.engine;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {

    @Test
    void runsPhasesInOrder() {
        var trace = new java.util.ArrayList<String>();
        var pipeline = new Pipeline(List.of(
            new MarkerPhase(List.of(c -> trace.add("marker"))),
            new ExtractorPhase(List.of(new Extractor() {
                public String name() { return "test"; }
                public void extract(ParseContext c) { trace.add("extract"); c.matches.add(Match.of("test", "x", 0, 1, "x")); }
            })),
            new ConflictPhase(),
            new PostPhase(List.of(c -> trace.add("post"))),
            new OutputPhase(c -> { trace.add("output"); c.result = c.resultBuilder.build(); })
        ));
        var ctx = new ParseContext("x", Options.defaults(), OptionsConfig.empty());
        pipeline.run(ctx);
        assertEquals(List.of("marker", "extract", "post", "output"), trace);
        assertNotNull(ctx.result);
    }
}
