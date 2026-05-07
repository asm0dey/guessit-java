package io.guessit.rules.property;

import io.guessit.engine.*;

import static io.guessit.rules.property.ConfigPatternHelpers.compileDashedCi;
import static io.guessit.rules.property.ConfigPatternHelpers.forEachString;

import java.util.*;

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
    private static final Set<MatchName> AUDIO_PROPS = Set.of(MatchName.AUDIO_CODEC, MatchName.AUDIO_PROFILE, MatchName.AUDIO_CHANNELS);

    @Override public String name() { return AUDIO_CODEC; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(AUDIO_CODEC);
        loadGroup(ctx, MatchName.AUDIO_CODEC, asMap(section.get(AUDIO_CODEC)));
        loadGroup(ctx, MatchName.AUDIO_PROFILE, asMap(section.get(AUDIO_PROFILE)));
        loadGroup(ctx, MatchName.AUDIO_CHANNELS, asMap(section.get("audio_channels")));
    }

    /**
     * AudioValidatorRule — drop audio matches not surrounded by seps unless touching another audio match.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        var audio = ctx.matches.all().filter(m -> AUDIO_PROPS.contains(m.name())).toList();

        removeInvalidAudioMatches(ctx, audio);
        removeWeakAudioChannels(ctx, audio);
        removeOrphanedAudioProfiles(ctx);
        removeConflictingHighQualityMatches(ctx);
    }

    private void removeInvalidAudioMatches(ParseContext ctx, List<Match> audio) {
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        for (var a : audio) {
            boolean before = isValidBoundary(input, a, audio, true);
            boolean after = isValidBoundary(input, a, audio, false);
            if (!before || !after) toRemove.add(a);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private boolean isValidBoundary(String input, Match match, List<Match> audio, boolean checkStart) {
        if (checkStart) {
            return match.start() == 0
                    || Seps.isSep(input.charAt(match.start() - 1))
                    || audio.stream().anyMatch(o -> o != match && o.end() == match.start());
        } else {
            return match.end() == input.length()
                    || Seps.isSep(input.charAt(match.end()))
                    || audio.stream().anyMatch(o -> o != match && o.start() == match.end());
        }
    }

    private void removeWeakAudioChannels(ParseContext ctx, List<Match> audio) {
        var weakChannels = ctx.matches.all()
                .filter(m -> m.name() == MatchName.AUDIO_CHANNELS && m.tags().contains("weak-audio_channels"))
                .toList();
        for (var wc : weakChannels) {
            boolean hasCodecBefore = audio.stream().anyMatch(o ->
                    o.name() == MatchName.AUDIO_CODEC && o.end() == wc.start());
            if (!hasCodecBefore) ctx.matches.remove(wc);
        }
    }

    private void removeOrphanedAudioProfiles(ParseContext ctx) {
        var profilesWithRule = ctx.matches.named(MatchName.AUDIO_PROFILE)
                .filter(m -> m.tags().contains("audio_profile.rule"))
                .toList();
        var codecMatches = ctx.matches.named(MatchName.AUDIO_CODEC).toList();

        for (var prof : profilesWithRule) {
            String requiredCodec = extractRequiredCodec(prof);
            if (requiredCodec == null) continue;

            if (!hasAdjacentRequiredCodec(ctx, prof, codecMatches, requiredCodec)) {
                ctx.matches.remove(prof);
            }
        }
    }

    private String extractRequiredCodec(Match profile) {
        for (var t : profile.tags()) {
            if (!"audio_profile.rule".equals(t)) {
                return t;
            }
        }
        return null;
    }

    private boolean hasAdjacentRequiredCodec(ParseContext ctx, Match prof, List<Match> codecMatches, String reqCodec) {
        if (codecAtSameSpan(prof, codecMatches, reqCodec)) return true;
        if (codecAtPreviousPosition(ctx, prof, codecMatches, reqCodec)) return true;
        return codecAtNextPosition(ctx, prof, codecMatches, reqCodec);
    }

    private boolean codecAtSameSpan(Match prof, List<Match> codecMatches, String reqCodec) {
        return codecMatches.stream().anyMatch(c ->
                c.start() == prof.start() && c.end() == prof.end()
                        && reqCodec.equals(String.valueOf(c.value())));
    }

    private boolean codecAtPreviousPosition(ParseContext ctx, Match prof, List<Match> codecMatches, String reqCodec) {
        Integer prevIdx = ctx.matches.all()
                .filter(o -> o != prof && o.end() <= prof.start())
                .map(Match::end)
                .max(Integer::compareTo).orElse(null);
        return prevIdx != null && codecMatches.stream().anyMatch(c ->
                c.end() == prevIdx && reqCodec.equals(String.valueOf(c.value())));
    }

    private boolean codecAtNextPosition(ParseContext ctx, Match prof, List<Match> codecMatches, String reqCodec) {
        Integer nextIdx = ctx.matches.all()
                .filter(o -> o != prof && o.start() >= prof.end())
                .map(Match::start)
                .min(Integer::compareTo).orElse(null);
        return nextIdx != null && codecMatches.stream().anyMatch(c ->
                c.start() == nextIdx && reqCodec.equals(String.valueOf(c.value())));
    }

    private void removeConflictingHighQualityMatches(ParseContext ctx) {
        var hqProfileSpans = ctx.matches.named(MatchName.AUDIO_PROFILE)
                .filter(m -> "High Quality".equals(m.value()))
                .map(m -> new int[]{m.start(), m.end()})
            .toList();
    
        if (hqProfileSpans.isEmpty()) return;
    
        var hqOthers = ctx.matches.named(MatchName.OTHER)
            .filter(m -> "High Quality".equals(m.value()))
            .filter(m -> hqProfileSpans.stream()
                .anyMatch(sp -> sp[0] == m.start() && sp[1] == m.end()))
            .toList();
        for (var m : hqOthers) ctx.matches.remove(m);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private void loadGroup(ParseContext ctx, MatchName propName, Map<String, Object> group) {
        // No edge validator at extract time: AudioValidatorRule re-checks both sides in
        // postProcess and allows audio matches to touch other audio matches
        // (e.g. "True-HD51", "AAC2.0").
        for (var entry : group.entrySet()) {
            String value = entry.getKey();
            // Entries that declare a `conflict_solver` in config (e.g. "DTS-HD") should
            // win over generic audio_codec matches (e.g. "DTS") covering the same span.
            int priority = entryHasConflictSolver(entry.getValue()) ? 1100 : 1000;
            for (var pattern : flattenPatterns(entry.getValue())) {
                addPatternMatches(ctx, propName, value, priority, pattern);
            }
        }
    }

    private void addPatternMatches(ParseContext ctx, MatchName propName, String value,
                                   int priority, PatternEntry pattern) {
        Set<String> tags = pattern.tags() != null ? new HashSet<>(pattern.tags()) : new HashSet<>();
        if (pattern.regex()) {
            addRegexMatches(ctx, propName, value, priority, pattern, tags);
        } else {
            addStringMatches(ctx, propName, value, priority, pattern, tags);
        }
    }

    private void addRegexMatches(ParseContext ctx, MatchName propName, String value,
                                 int priority, PatternEntry pattern, Set<String> tags) {
        var p = compileDashedCi(pattern.source());
        if (p == null) return;
        var opts = RegexOpts.defaults().withValue(_ -> value).withPriority(priority);
        if (!tags.isEmpty()) opts = opts.withTags(tags);
        for (var m : PatternMatcher.regex(ctx.input, p, propName, opts)) ctx.matches.add(m);
    }

    /** Disable whole-word boundary; AudioValidatorRule checks edges later
     *  (allowing audio matches to touch other audio matches). */
    private void addStringMatches(ParseContext ctx, MatchName propName, String value,
                                  int priority, PatternEntry pattern, Set<String> tags) {
        var opts = StringOpts.defaults().wholeWord(false).withPriority(priority);
        for (var m : PatternMatcher.string(ctx.input, Set.of(pattern.source()), propName, opts)) {
            ctx.matches.add(new Match(propName, value, m.start(), m.end(), m.raw(),
                m.priority(), mergeTags(m.tags(), tags), m.isPrivate()));
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
            // Tags can be a String or a List<String>.
            List<String> tags = null;
            if (m.get("tags") instanceof String ts) tags = List.of(ts);
            else if (m.get("tags") instanceof List<?> tl) tags = (List<String>) tl;
            var finalTags = tags;
            forEachString(m.get("string"), s -> out.add(new PatternEntry(s, false, finalTags)));
            forEachString(m.get("regex"),  s -> out.add(new PatternEntry(s, true,  finalTags)));
        }
        return out;
    }
}
