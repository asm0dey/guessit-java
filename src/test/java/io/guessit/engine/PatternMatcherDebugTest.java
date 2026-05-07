package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PatternMatcherDebugTest {

    @Test
    void regexEmitsTryAndAcceptedSubsteps() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var opts = RegexOpts.defaults().withValue(Integer::valueOf);
        PatternMatcher.regex("XxX.2020.mkv", Pattern.compile("\\d{4}"), MatchName.YEAR, opts, tr);
        assertThat(fired).anyMatch(s -> s.startsWith("Trying regex "));
        assertThat(fired).anyMatch(s -> s.startsWith("Considered '2020' at 4-8 — accepted"));
    }

    @Test
    void regexEmitsRejectedSubstepWhenValidatorFails() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var opts = RegexOpts.defaults()
            .withValue(Integer::valueOf)
            .withValidator(m -> false);
        PatternMatcher.regex("foo 2020 bar", Pattern.compile("\\d{4}"), MatchName.YEAR, opts, tr);
        assertThat(fired).anyMatch(s -> s.contains("rejected"));
    }

    @Test
    void stringEmitsTryAndAccepted() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var opts = StringOpts.defaults();
        PatternMatcher.string("Foo.1080p.bar", Set.of("1080p", "720p"), MatchName.SCREEN_SIZE, opts, tr);
        assertThat(fired).anyMatch(s -> s.startsWith("Trying needles"));
        assertThat(fired).anyMatch(s -> s.contains("'1080p'") && s.contains("accepted"));
    }

    @Test
    void backwardsCompatibleNoTraceOverloadStillWorks() {
        var opts = RegexOpts.defaults().withValue(Integer::valueOf);
        List<Match> matches = PatternMatcher.regex("XxX.2020.mkv", Pattern.compile("\\d{4}"), MatchName.YEAR, opts);
        assertThat(matches).hasSize(1);
    }
}
