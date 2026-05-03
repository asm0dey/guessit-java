package io.guessit.rules;

import io.guessit.engine.*;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import io.guessit.rules.post.OutputBuilder;
import io.guessit.rules.post.PreferLastPath;
import io.guessit.rules.post.PrivateRemover;
import io.guessit.rules.post.TitleMarkerSelector;
import io.guessit.rules.property.*;

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
            new AbsoluteEpisodeRule(),
            new SeasonEpisodeExtractor(),
            new EpisodeWordExtractor(),
            new WeakEpisodeExtractor(),
            new WeakDuplicateExtractor(),
            new DiscRule(),
            new DateExtractor(),
            new WeekExtractor(),
            new ReleaseGroupExtractor()
        );
    }
}
