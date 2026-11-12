package io.guessit.rules.property;

import com.mirkoddd.sift.core.dsl.Connector;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.Marker;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;

/**
 * Shared utilities, constants, and Sift patterns for weak extractors
 * (WeakDuplicateExtractor and WeakEpisodeExtractor).
 */
final class WeakExtractorCommon {

    private WeakExtractorCommon() {
    }

    static final String TYPE_MOVIE = "movie";
    static final String TYPE_EPISODE = "episode";

    static final String WEAK_EPISODE = "weak-episode";
    static final String WEAK_DUPLICATE = "weak-duplicate";
    static final String SXXEXX = "SxxExx";

    static final String MARKER_PATH = "path";
    static final String MARKER_GROUP = "group";

    static SiftPattern<Fragment> anyOfTheseChars(String chars) {
        return anyOf(chars.chars().mapToObj(c -> exactly(1).character((char) c)).toList());
    }

    static final Connector<Fragment> SPACING = zeroOrMore().of(anyOfTheseChars(" ._"));
    static final SiftPattern<Fragment> RANGE_DELIMITER = anyOfTheseChars("-~");

    static final Pattern RANGE_SEP = Pattern.compile(
            SPACING.followedBy(RANGE_DELIMITER, SPACING).shake()
    );

    static boolean isInside(Match target, int start, int end) {
        return target.start() >= start && target.end() <= end;
    }

    static boolean isInside(Match target, Match container) {
        return isInside(target, container.start(), container.end());
    }

    static boolean isInside(Match target, Marker container) {
        return isInside(target, container.start(), container.end());
    }

    static void removeMatches(ParseContext ctx, Stream<Match> streamToRemove) {
        streamToRemove.toList().forEach(ctx.matches::remove);
    }

    static boolean hasScreenSizeInGroup(ParseContext ctx) {
        return ctx.markers.stream()
                .filter(mk -> MARKER_GROUP.equals(mk.name()))
                .anyMatch(mk -> ctx.matches.named(MatchName.SCREEN_SIZE).anyMatch(m -> isInside(m, mk)));
    }
}