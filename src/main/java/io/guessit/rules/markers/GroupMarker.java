package io.guessit.rules.markers;

import io.guessit.engine.Marker;
import io.guessit.engine.MarkerPhase.MarkerProducer;
import io.guessit.engine.ParseContext;

public final class GroupMarker implements MarkerProducer {
    @Override
    public void produce(ParseContext ctx) {
        var input = ctx.input;
        var open = "([{";
        var close = ")]}";
        var stack = new java.util.ArrayDeque<int[]>(); // {openIdx, openCharIdxInOpen}
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int oi = open.indexOf(c);
            int ci = close.indexOf(c);
            if (oi >= 0) {
                stack.push(new int[]{i, oi});
            } else if (ci >= 0) {
                if (!stack.isEmpty() && stack.peek()[1] == ci) {
                    var openInfo = stack.pop();
                    int s = openInfo[0];
                    int e = i + 1;
                    if (e - s > 2) {
                        ctx.markers.add(new Marker("group", s + 1, e - 1, input.substring(s + 1, e - 1)));
                    }
                }
            }
        }
    }
}
