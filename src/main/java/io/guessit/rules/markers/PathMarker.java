package io.guessit.rules.markers;

import io.guessit.engine.Marker;
import io.guessit.engine.MarkerPhase.MarkerProducer;
import io.guessit.engine.ParseContext;

/**
 * Emits one {@code whole} marker spanning the entire input plus one
 * {@code path} marker per {@code /} or {@code \\}-separated segment.
 *
 * <p>Path markers scope rules to a single filepart so a year in the parent
 * directory (e.g. {@code "Movies (2020)/Foo.mkv"}) doesn't fight a year in
 * the filename. The {@code whole} marker is a fallback used when no path
 * separators are present.
 */
public final class PathMarker implements MarkerProducer {
    @Override
    public void produce(ParseContext ctx) {
        var input = ctx.input;
        ctx.markers.add(new Marker("whole", 0, input.length(), input));
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '/' || c == '\\') {
                // Skip empty segments from leading or doubled separators.
                if (i > start) ctx.markers.add(new Marker("path", start, i, input.substring(start, i)));
                start = i + 1;
            }
        }
        // Trailing segment (no separator after the last filepart).
        if (start < input.length()) ctx.markers.add(new Marker("path", start, input.length(), input.substring(start)));
    }
}
