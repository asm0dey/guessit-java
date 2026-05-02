package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

public final class AudioCodecExtractor implements Extractor {

    private static final Set<String> AUDIO_PROPS = Set.of("audio_codec", "audio_profile", "audio_channels");

    @Override public String name() { return "audio_codec"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("audio_codec");
        loadGroup(ctx, "audio_codec", asMap(section.get("audio_codec")));
        loadGroup(ctx, "audio_profile", asMap(section.get("audio_profile")));
        loadGroup(ctx, "audio_channels", asMap(section.get("audio_channels")));
    }

    /** AudioValidatorRule — drop audio matches not surrounded by seps unless touching another audio match. */
    @Override
    public void postProcess(ParseContext ctx) {
        var input = ctx.input;
        var audio = ctx.matches.all().filter(m -> AUDIO_PROPS.contains(m.name())).toList();
        var toRemove = new ArrayList<Match>();
        for (var a : audio) {
            boolean before = a.start() == 0 || Seps.isSep(input.charAt(a.start() - 1))
                || audio.stream().anyMatch(o -> o != a && o.end() == a.start());
            boolean after = a.end() == input.length() || Seps.isSep(input.charAt(a.end()))
                || audio.stream().anyMatch(o -> o != a && o.start() == a.end());
            if (!before || !after) toRemove.add(a);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private void loadGroup(ParseContext ctx, String propName, Map<String, Object> group) {
        // No edge validator at extract time: AudioValidatorRule re-checks both sides in
        // postProcess and allows audio matches to touch other audio matches
        // (e.g. "True-HD51", "AAC2.0").
        for (var entry : group.entrySet()) {
            String value = entry.getKey();
            // Entries that declare a `conflict_solver` in config (e.g. "DTS-HD") should
            // win over generic audio_codec matches (e.g. "DTS") covering the same span.
            int priority = entryHasConflictSolver(entry.getValue()) ? 1100 : 1000;
            for (var pattern : flattenPatterns(entry.getValue())) {
                if (pattern.regex()) {
                    var p = Pattern.compile(Abbreviations.dash(pattern.source()), Pattern.CASE_INSENSITIVE);
                    var opts = RegexOpts.defaults().withValue(s -> value).withPriority(priority);
                    for (var m : PatternMatcher.regex(ctx.input, p, propName, opts)) ctx.matches.add(m);
                } else {
                    // Disable whole-word boundary; AudioValidatorRule checks edges later
                    // (allowing audio matches to touch other audio matches).
                    var opts = StringOpts.defaults().wholeWord(false).withPriority(priority);
                    for (var m : PatternMatcher.string(ctx.input, Set.of(pattern.source()), propName, opts)) {
                        ctx.matches.add(new Match(propName, value, m.start(), m.end(), m.raw(),
                            m.priority(), m.tags(), m.isPrivate()));
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean entryHasConflictSolver(Object def) {
        if (def instanceof Map<?, ?> m) return ((Map<String, Object>) m).containsKey("conflict_solver");
        if (def instanceof List<?> l) return l.stream().anyMatch(AudioCodecExtractor::entryHasConflictSolver);
        return false;
    }

    /** Pattern config can be: String → string match, list of {String|Map}, Map with "string"/"regex" keys. */
    private record PatternEntry(String source, boolean regex) {}

    @SuppressWarnings("unchecked")
    private static List<PatternEntry> flattenPatterns(Object def) {
        var out = new ArrayList<PatternEntry>();
        if (def instanceof String s) {
            out.add(new PatternEntry(s, false));
        } else if (def instanceof List<?> list) {
            for (var item : list) out.addAll(flattenPatterns(item));
        } else if (def instanceof Map<?, ?> map) {
            var m = (Map<String, Object>) map;
            if (m.get("string") instanceof String ss) out.add(new PatternEntry(ss, false));
            if (m.get("string") instanceof List<?> sl) for (var s : sl) out.add(new PatternEntry((String) s, false));
            if (m.get("regex") instanceof String rs) out.add(new PatternEntry(rs, true));
            if (m.get("regex") instanceof List<?> rl) for (var s : rl) out.add(new PatternEntry((String) s, true));
        }
        return out;
    }
}
