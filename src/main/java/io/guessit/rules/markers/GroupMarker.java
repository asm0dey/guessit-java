package io.guessit.rules.markers;

import io.guessit.engine.Marker;
import io.guessit.engine.MarkerPhase.MarkerProducer;
import io.guessit.engine.ParseContext;

/**
 * Emits one {@code group} marker per balanced {@code ()}, {@code []}, or
 * {@code {}} pair longer than two characters. The marker span is the
 * <em>contents</em> of the brackets — the brackets themselves are excluded.
 *
 * <p>Used by release-group, language, and other extractors to recognise
 * content that the releaser explicitly grouped together.
 */
public final class GroupMarker implements MarkerProducer {
    @Override
    public void produce(ParseContext ctx) {
        var input = ctx.input;
        var open = "([{";
        var close = ")]}";
        // Stack entries: {openCharIndex, indexOfOpenCharWithinOpen}.
        // Storing the open char's slot lets us require the closer to match
        // (e.g. "(" must close with ")", not "]").
        var stack = new java.util.ArrayDeque<int[]>();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int oi = open.indexOf(c);
            int ci = close.indexOf(c);
            if (oi >= 0) {
                stack.push(new int[]{i, oi});
            } else if (ci >= 0 && !stack.isEmpty() && stack.peek()[1] == ci) {
                var openInfo = stack.pop();
                int s = openInfo[0];
                int e = i + 1;
                // Skip empty / single-char groups; nothing useful inside.
                if (e - s > 2) {
                    ctx.markers.add(new Marker("group", s + 1, e - 1, input.substring(s + 1, e - 1)));
                }
            }

        }
    }
}
