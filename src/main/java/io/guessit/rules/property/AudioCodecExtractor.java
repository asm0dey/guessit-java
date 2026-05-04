package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Extracts {@code audio_codec}, {@code audio_profile}, and {@code audio_channels}.
 *
 * <p>Pattern definitions are not hardcoded — they are loaded from the
 * {@code audio_codec} section of the config so additions ride on options
 * updates rather than code changes. {@link #flattenPatterns} accepts a
 * flexible YAML/JSON shape (string, list, or map with {@code string} /
 * {@code regex} / {@code tags} keys) so config can express weighting and
 * tagging without inventing new schemas.
 *
 * <p>Edge validation is deferred to {@link #postProcess} rather than enforced
 * by per-match validators, because audio matches frequently sit flush against
 * each other (e.g. {@code "True-HD51"}, {@code "AAC2.0"}). The post-pass
 * accepts a non-separator on either side as long as another audio match is
 * abutted there.
 */
public final class AudioCodecExtractor implements Extractor {

    public static final String AUDIO_CODEC = "audio_codec";
    public static final String AUDIO_PROFILE = "audio_profile";
    private static final Set<String> AUDIO_PROPS = Set.of(AUDIO_CODEC, AUDIO_PROFILE, "audio_channels");

    @Override public String name() { return AUDIO_CODEC; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(AUDIO_CODEC);
        loadGroup(ctx, AUDIO_CODEC, asMap(section.get(AUDIO_CODEC)));
        loadGroup(ctx, AUDIO_PROFILE, asMap(section.get(AUDIO_PROFILE)));
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

        // RemoveWeakAudioChannels: drop weak-audio_channels matches unless adjacent
        // to an audio_codec match on the left side.
        var weakChannels = ctx.matches.all()
            .filter(m -> m.name().equals("audio_channels") && m.tags().contains("weak-audio_channels"))
            .toList();
        for (var wc : weakChannels) {
            boolean hasCodecBefore = audio.stream().anyMatch(o ->
                o.name().equals(AUDIO_CODEC) && o.end() == wc.start());
            if (!hasCodecBefore) ctx.matches.remove(wc);
        }

        // AudioProfileRule: drop audio_profile matches tagged audio_profile.rule when there's
        // no matching audio_codec (specified in the same tag set) anywhere in the input.
        // AudioProfileRule: profiles like "Master Audio" only make sense alongside their
        // declared parent codec (e.g. DTS-HD). The config tags such matches with
        // both "audio_profile.rule" and the required codec name; remove the
        // profile when the parent codec didn't survive.
        var profilesWithRule = ctx.matches.named(AUDIO_PROFILE)
            .filter(m -> m.tags().contains("audio_profile.rule"))
            .toList();
        var codecValues = ctx.matches.named(AUDIO_CODEC).map(m -> m.value().toString()).collect(java.util.stream.Collectors.toSet());
        for (var prof : profilesWithRule) {
            String requiredCodec = null;
            for (var t : prof.tags()) {
                if ("audio_profile.rule".equals(t)) continue;
                requiredCodec = t;
                break;
            }
            if (requiredCodec != null && !codecValues.contains(requiredCodec)) {
                ctx.matches.remove(prof);
            }
        }

        // HqConflictRule: drop other=High Quality matches whose span overlaps a
        // surviving audio_profile=High Quality (e.g. AC3.HQ has both "other"
        // and audio_profile candidates over the same "HQ" — the audio profile
        // wins).
        var hqProfileSpans = ctx.matches.named(AUDIO_PROFILE)
            .filter(m -> "High Quality".equals(m.value()))
            .map(m -> new int[]{m.start(), m.end()})
            .toList();
        if (!hqProfileSpans.isEmpty()) {
            var hqOthers = ctx.matches.named("other")
                .filter(m -> "High Quality".equals(m.value()))
                .filter(m -> hqProfileSpans.stream()
                    .anyMatch(sp -> sp[0] == m.start() && sp[1] == m.end()))
                .toList();
            for (var m : hqOthers) ctx.matches.remove(m);
        }
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
                Set<String> tags = pattern.tags() != null
                    ? new HashSet<>(pattern.tags()) : new HashSet<>();
                if (pattern.regex()) {
                    var p = Pattern.compile(Abbreviations.dash(pattern.source()), Pattern.CASE_INSENSITIVE);
                    var opts = RegexOpts.defaults().withValue(_ -> value).withPriority(priority);
                    if (!tags.isEmpty()) opts = opts.withTags(tags);
                    for (var m : PatternMatcher.regex(ctx.input, p, propName, opts)) ctx.matches.add(m);
                } else {
                    // Disable whole-word boundary; AudioValidatorRule checks edges later
                    // (allowing audio matches to touch other audio matches).
                    var opts = StringOpts.defaults().wholeWord(false).withPriority(priority);
                    for (var m : PatternMatcher.string(ctx.input, Set.of(pattern.source()), propName, opts)) {
                        ctx.matches.add(new Match(propName, value, m.start(), m.end(), m.raw(),
                            m.priority(), mergeTags(m.tags(), tags), m.isPrivate()));
                    }
                }
            }
        }
    }

    private static Set<String> mergeTags(Set<String> existing, Set<String> additional) {
        if (additional.isEmpty()) return existing;
        var merged = new HashSet<>(existing);
        merged.addAll(additional);
        return merged;
    }

    private static boolean entryHasConflictSolver(Object def) {
        if (def instanceof Map<?, ?> m) return m.containsKey("conflict_solver");
        if (def instanceof List<?> l) return l.stream().anyMatch(AudioCodecExtractor::entryHasConflictSolver);
        return false;
    }

    /** Pattern config can be: String → string match, list of {String|Map}, Map with "string"/"regex" keys. */
    private record PatternEntry(String source, boolean regex, List<String> tags) {
        PatternEntry(String source, boolean regex) { this(source, regex, null); }
    }

    @SuppressWarnings("unchecked")
    private static List<PatternEntry> flattenPatterns(Object def) {
        var out = new ArrayList<PatternEntry>();
        if (def instanceof String s) {
            if (s.startsWith("re:")) out.add(new PatternEntry(s.substring(3), true));
            else out.add(new PatternEntry(s, false));
        } else if (def instanceof List<?> list) {
            for (var item : list) out.addAll(flattenPatterns(item));
        } else if (def instanceof Map<?, ?> map) {
            var m = (Map<String, Object>) map;
            // Extract tags from the map (can be String or List<String>)
            List<String> tags = null;
            if (m.get("tags") instanceof String ts) tags = List.of(ts);
            else if (m.get("tags") instanceof List<?> tl) tags = (List<String>) tl;
            if (m.get("string") instanceof String ss) out.add(new PatternEntry(ss, false, tags));
            if (m.get("string") instanceof List<?> sl) for (var s : sl) out.add(new PatternEntry((String) s, false, tags));
            if (m.get("regex") instanceof String rs) out.add(new PatternEntry(rs, true, tags));
            if (m.get("regex") instanceof List<?> rl) for (var s : rl) out.add(new PatternEntry((String) s, true, tags));
        }
        return out;
    }
}
