package io.guessit.engine;

import io.guessit.Options;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDiffSpansTest {

    @Test
    void firesSpansEventWhenSetChanged() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() {
            @Override public void added(Match m)   { fired.add("+" + m.raw()); }
            @Override public void removed(Match m) { fired.add("-" + m.raw()); }
            @Override public void noChanges()      { fired.add("nochg"); }
            @Override public void spans(String i, List<Match> ms, List<Marker> mk) { fired.add("spans:" + ms.size() + "/" + mk.size()); }
        };
        var ctx = new ParseContext("XxX.2020.mkv", Options.defaults(), null, tr);
        var year = Match.of(MatchName.YEAR, 2020, 4, 8, "2020");
        var before = ctx.matches.snapshot();
        ctx.matches.add(year);
        TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
        assertThat(fired).containsExactly("+2020", "spans:1/0");
    }

    @Test
    void firesNoChangesAndNoSpansWhenSetUnchanged() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() {
            @Override public void noChanges() { fired.add("nochg"); }
            @Override public void spans(String i, List<Match> ms, List<Marker> mk) { fired.add("spans"); }
        };
        var ctx = new ParseContext("XxX.2020.mkv", Options.defaults(), null, tr);
        var snap = ctx.matches.snapshot();
        TraceDiff.emit(snap, snap, ctx);
        assertThat(fired).containsExactly("nochg");
    }

    @Test
    void existingTraceOverloadStillEmitsDiffOnly() {
        // Backwards compat: phases that haven't migrated to the ctx overload
        // continue to call emit(before, after, trace) and get added/removed/noChanges
        // but no spans event.
        var fired = new ArrayList<String>();
        Trace tr = new Trace() {
            @Override public void added(Match m)  { fired.add("+" + m.raw()); }
            @Override public void noChanges()     { fired.add("nochg"); }
            @Override public void spans(String i, List<Match> ms, List<Marker> mk) { fired.add("spans"); }
        };
        var year = Match.of(MatchName.YEAR, 2020, 0, 4, "2020");
        TraceDiff.emit(List.of(), List.of(year), tr);
        assertThat(fired).containsExactly("+2020");
    }
}
