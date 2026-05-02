package io.guessit.rules;

import io.guessit.engine.*;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import io.guessit.rules.post.OutputBuilder;
import io.guessit.rules.post.PreferLastPath;
import io.guessit.rules.post.PrivateRemover;
import io.guessit.rules.post.TitleMarkerSelector;
import io.guessit.rules.property.AudioCodecExtractor;
import io.guessit.rules.property.ContainerExtractor;
import io.guessit.rules.property.CountryExtractor;
import io.guessit.rules.property.EpisodeDetailsExtractor;
import io.guessit.rules.property.EpisodeFormatExtractor;
import io.guessit.rules.property.VersionExtractor;
import io.guessit.rules.property.LanguageExtractor;
import io.guessit.rules.property.OtherExtractor;
import io.guessit.rules.property.ReleaseGroupExtractor;
import io.guessit.rules.property.ScreenSizeExtractor;
import io.guessit.rules.property.SourceExtractor;
import io.guessit.rules.property.StreamingServiceExtractor;
import io.guessit.rules.property.VideoCodecExtractor;
import io.guessit.rules.property.WebsiteExtractor;
import io.guessit.rules.property.YearExtractor;

import java.util.List;

public final class Rules {
    private Rules() {}

    public static List<Phase> defaultPipeline() {
        var extractors = allInOrder();
        return List.of(
            new MarkerPhase(List.of(new PathMarker(), new GroupMarker())),
            new ExtractorPhase(extractors),
            new ConflictPhase(),
            new ExtractorPostPhase(extractors),
            new PostPhase(List.of(
                new PreferLastPath(),
                new PrivateRemover(),
                new TitleMarkerSelector()
            )),
            new OutputPhase(new OutputBuilder())
        );
    }

    public static List<Extractor> allInOrder() {
        return List.of(
            new YearExtractor(),
            new ScreenSizeExtractor(),
            new VideoCodecExtractor(),
            new AudioCodecExtractor(),
            new ContainerExtractor(),
            new OtherExtractor(),
            new LanguageExtractor(),
            new CountryExtractor(),
            new StreamingServiceExtractor(),
            new SourceExtractor(),
            new WebsiteExtractor(),
            new EpisodeDetailsExtractor(),
            new EpisodeFormatExtractor(),
            new VersionExtractor(),
            new ReleaseGroupExtractor()
        );
    }
}
