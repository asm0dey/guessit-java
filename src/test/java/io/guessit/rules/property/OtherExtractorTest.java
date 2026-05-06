package io.guessit.rules.property;

import io.guessit.config.OptionsConfig;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.guessit.Options.defaults;
import static io.guessit.engine.ConflictSolver.solve;
import static io.guessit.engine.MatchName.OTHER;
import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;

class OtherExtractorTest {
    private static OptionsConfig cfg(Map<String, Object> entries) {
        return new OptionsConfig(Map.of("advanced_config",
            Map.of("other", Map.of("other", entries))));
    }

    @Test void extractsString3dPattern() {
        var ctx = new ParseContext("Movie.3D.2015",
                defaults(),
                cfg(of("3D", "3D")));
        new OtherExtractor().extract(ctx);
        var values = ctx.matches.named(OTHER).map(m -> m.value().toString()).toList();
        assertThat(values).isEqualTo(List.of("3D"));
    }

    @Test void extractsRegexEntryWithRegexKey() {
        var ctx = new ParseContext("Movie.HDRip.2015",
                defaults(),
                cfg(of("Rip", of("regex", List.of("(?:HD)Rip")))));
        new OtherExtractor().extract(ctx);
        var values = ctx.matches.named(OTHER).map(m -> m.value().toString()).toList();
        assertThat(values).isEqualTo(List.of("Rip"));
    }

    @Test void multipleStringSynonymsAllMatch() {
        var ctx = new ParseContext("Movie.Proper.2015 Repack",
                defaults(),
                cfg(of("Proper", List.of("Proper", "Repack"))));
        new OtherExtractor().extract(ctx);
        solve(ctx.matches);
        var values = ctx.matches.named(OTHER).map(m -> m.value().toString()).sorted().toList();
        assertThat(values).isEqualTo(List.of("Proper", "Proper"));
    }
}
