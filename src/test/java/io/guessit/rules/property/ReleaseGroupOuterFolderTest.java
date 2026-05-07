package io.guessit.rules.property;

import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;

class ReleaseGroupOuterFolderTest {
    @Test
    void prefersUpperFolderCasing() {
        // Folder "Show.Name.S01.720p.HDTV.DD5.1.x264-Group" has release_group=Group;
        // filename "show.name.0106.720p-group.mkv" has release_group=group (lowercase).
        // Upper-folder casing "Group" should win.
        var r = parse("Show.Name.S01.720p.HDTV.DD5.1.x264-Group/show.name.0106.720p-group.mkv");
        assertThat(r.releaseGroup()).isEqualTo("Group");
    }
}
