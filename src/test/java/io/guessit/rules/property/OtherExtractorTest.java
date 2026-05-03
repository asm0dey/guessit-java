package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OtherExtractorTest {
    private static OptionsConfig cfg(Map<String, Object> entries) {
        return new OptionsConfig(Map.of("advanced_config",
            Map.of("other", Map.of("other", entries))));
    }

    @Test void extractsString3dPattern() {
        var ctx = new ParseContext("Movie.3D.2015",
            Options.defaults(),
            cfg(Map.of("3D", "3D")));
        new OtherExtractor().extract(ctx);
        var values = ctx.matches.named("other").map(m -> m.value().toString()).toList();
        assertEquals(List.of("3D"), values);
    }

    @Test void extractsRegexEntryWithRegexKey() {
        var ctx = new ParseContext("Movie.HDRip.2015",
            Options.defaults(),
            cfg(Map.of("Rip", Map.of("regex", List.of("(?:HD)Rip")))));
        new OtherExtractor().extract(ctx);
        var values = ctx.matches.named("other").map(m -> m.value().toString()).toList();
        assertEquals(List.of("Rip"), values);
    }

    @Test void multipleStringSynonymsAllMatch() {
        var ctx = new ParseContext("Movie.Proper.2015 Repack",
            Options.defaults(),
            cfg(Map.of("Proper", List.of("Proper", "Repack"))));
        new OtherExtractor().extract(ctx);
        ConflictSolver.solve(ctx.matches);
        var values = ctx.matches.named("other").map(m -> m.value().toString()).sorted().toList();
        assertEquals(List.of("Proper", "Proper"), values);
    }
}
