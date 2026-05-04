package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RemoveAmbiguousTest {
    @Test void smokeTest() {
        // Verify that parsing a multi-filepart path doesn't throw; precise de-dup tested via YML
        var r = Guessit.parse("Show.S01.Group/Show.S01E01.OtherGroup.mkv");
        // title should be resolved from the filename part
        assertThat(r.title()).isNotNull();
    }

    @Test void singleFilepartProducesReleaseGroup() {
        // Single-filepart: release group should be extracted normally
        var r = Guessit.parse("Some.Title.XViD-ReleaseGroup.mkv");
        assertThat(r.releaseGroup()).isEqualTo("ReleaseGroup");
    }
}
