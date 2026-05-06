package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static io.guessit.Options.defaults;
import static io.guessit.config.OptionsConfig.empty;
import static io.guessit.engine.MatchName.EDITION;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PipelineTest {

    @Test
    void runsPhasesInOrder() {
        var trace = new ArrayList<String>();
        var pipeline = new Pipeline(of(
                new MarkerPhase(of(c -> trace.add("marker"))),
                new ExtractorPhase(of(new Extractor() {
                    public String name() {
                        return "test";
                    }

                    public void extract(ParseContext c) {
                        trace.add("extract");
                        c.matches.add(Match.of(EDITION, "x", 0, 1, "x"));
                    }
                })),
                new ConflictPhase(),
                new PostPhase(of(c -> trace.add("post"))),
                new OutputPhase(c -> {
                    trace.add("output");
                    c.result = c.resultBuilder.build();
                })
        ));
        var ctx = new ParseContext("x", defaults(), empty());
        pipeline.run(ctx);
        assertThat(trace).isEqualTo(of("marker", "extract", "post", "output"));
        assertNotNull(ctx.result);
    }
}
