package io.guessit.rules;

import io.guessit.engine.*;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import io.guessit.rules.post.OutputBuilder;
import io.guessit.rules.post.PrivateRemover;
import io.guessit.rules.post.TitleMarkerSelector;

import java.util.List;

public final class Rules {
    private Rules() {}

    public static List<Phase> defaultPipeline() {
        return List.of(
            new MarkerPhase(List.of(new PathMarker(), new GroupMarker())),
            new ExtractorPhase(allInOrder()),
            new ConflictPhase(),
            new PostPhase(List.of(
                new PrivateRemover(),
                new TitleMarkerSelector()
            )),
            new OutputPhase(new OutputBuilder())
        );
    }

    public static List<Extractor> allInOrder() {
        return List.of(); // Plan 1+ append here
    }
}
