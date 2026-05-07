package io.guessit.engine;

import io.guessit.rules.Rules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionsCoverageTest {

    @Test
    void everyExtractorHasNonDefaultDescription() {
        for (var e : Rules.allInOrder()) {
            assertThat(e.description())
                .as("extractor %s description", e.name())
                .isNotNull()
                .isNotEmpty()
                .isNotEqualTo(e.name());
        }
    }

    @Test
    void everyPostProcessorHasNonDefaultDescription() {
        for (var p : Rules.defaultPostProcessors()) {
            assertThat(p.description())
                .as("processor %s description", p.getClass().getSimpleName())
                .isNotNull()
                .isNotEmpty()
                .isNotEqualTo(p.getClass().getSimpleName());
        }
    }

    @Test
    void everyMarkerProducerHasNonDefaultDescription() {
        for (var m : Rules.defaultMarkerProducers()) {
            assertThat(m.description())
                .as("marker %s description", m.getClass().getSimpleName())
                .isNotNull()
                .isNotEmpty()
                .isNotEqualTo(m.getClass().getSimpleName());
        }
    }
}
