package io.guessit.rules.markers;

import io.guessit.engine.Marker;
import io.guessit.engine.MarkerPhase.MarkerProducer;
import io.guessit.engine.ParseContext;

public final class PathMarker implements MarkerProducer {
    @Override
    public void produce(ParseContext ctx) {
        var input = ctx.input;
        ctx.markers.add(new Marker("whole", 0, input.length(), input));
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '/' || c == '\\') {
                if (i > start) ctx.markers.add(new Marker("path", start, i, input.substring(start, i)));
                start = i + 1;
            }
        }
        if (start < input.length()) ctx.markers.add(new Marker("path", start, input.length(), input.substring(start)));
    }
}
