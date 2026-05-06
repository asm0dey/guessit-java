package io.guessit.rules;

import io.guessit.engine.*;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import io.guessit.rules.post.*;
import io.guessit.rules.property.*;

import java.util.List;

/**
 * Composition layer: wires {@link io.guessit.engine.Phase}s and
 * {@link Extractor}s into a working parser.
 *
 * <p>The order in {@link #allInOrder()} is part of the contract — extractors
 * routinely depend on tags or matches added by earlier ones (e.g.
 * {@code WeakEpisodeExtractor} reads the {@code "SxxExx"} tag set by
 * {@code SeasonEpisodeExtractor} when promoting trailing weaks to
 * {@code absolute_episode}). A flat ordered list keeps that dependency
 * visible at the call site instead of hidden in metadata.
 */
public final class Rules {
    private Rules() {}

    /**
     * The default six-phase pipeline used by {@code Guessit.parse}. Both
     * {@link ExtractorPhase} and {@link ExtractorPostPhase} are fed from the
     * same {@link #allInOrder()} list, so registration order is single-sourced.
     */
    public static List<Phase> defaultPipeline() {
        var extractors = allInOrder();
        return List.of(
            new MarkerPhase(List.of(new PathMarker(), new GroupMarker())),
            new ExtractorPhase(extractors),
            new ConflictPhase(),
            new ExtractorPostPhase(extractors),
            new PostPhase(List.of(
                new EnlargeGroupMatches(),
                new BitRateTypeRule(),
                new LanguageCountryAttach(),
                new EquivalentHoles(),
                new PreferLastPath(),
                new RangeFiller(),
                new EpisodeNumberSeparatorRange(),
                new AbsoluteEpisodePromoter(),
                new SeasonYearLink(),
                new SeasonYear(),
                new YearSeason(),
                new RemoveLessSpecificSeasonEpisode(MatchName.SEASON),
                new RemoveLessSpecificSeasonEpisode(MatchName.EPISODE),
                new RemoveAmbiguous(),
                new ProperCountRule(),
                new TypeProcessor(),
                new MimetypeProcessor(),
                new StripSeparators(),
                new PrivateRemover()
            )),
            new OutputPhase(new OutputBuilder())
        );
    }

    /**
     * Canonical extractor registration order. Strong, unambiguous extractors
     * run first so their matches are present when later, weaker ones make
     * scoping decisions; weak fallback rules ({@code WeakEpisode},
     * {@code WeakDuplicate}) run after the strong episode rules so they yield
     * to anything more specific. {@code ReleaseGroup} runs last because it
     * needs every other extractor's matches to identify the trailing group.
     */
    public static List<Extractor> allInOrder() {
        return List.of(
            new YearExtractor(),
            new ScreenSizeExtractor(),
            new VideoCodecExtractor(),
            new AudioCodecExtractor(),
            new ContainerExtractor(),
            new SizeExtractor(),
            new BitRateExtractor(),
            new OtherExtractor(),
            new EditionExtractor(),
            new CdExtractor(),
            new LanguageExtractor(),
            new CountryExtractor(),
            new StreamingServiceExtractor(),
            new SourceExtractor(),
            new WebsiteExtractor(),
            new EpisodeDetailsExtractor(),
            new EpisodeFormatExtractor(),
            new VersionExtractor(),
            new SeasonEpisodeExtractor(),
            new EpisodeWordExtractor(),
            new BonusExtractor(),
            new FilmExtractor(),
            new PartExtractor(),
            new CrcExtractor(),
            new WeakEpisodeExtractor(),
            new WeakDuplicateExtractor(),
            new DiscRule(),
            new DateExtractor(),
            new WeekExtractor(),
            new ReleaseGroupExtractor(),
            new TitleExtractor(),
            new EpisodeTitleExtractor()
        );
    }
}
