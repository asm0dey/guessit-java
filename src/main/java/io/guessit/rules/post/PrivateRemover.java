package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.PostPhase.PostProcessor;
import io.guessit.engine.ParseContext;

import java.util.List;

public final class PrivateRemover implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        List<Match> privates = ctx.matches.all().filter(Match::isPrivate).toList();
        for (var m : privates) ctx.matches.remove(m);
    }
}
