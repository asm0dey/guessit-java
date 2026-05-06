package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.engine.MatchName;

import static io.guessit.rules.property.ConfigPatternHelpers.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts the {@code edition} property — release edition tags such as
 * "Collector", "Director's Cut", "Extended", "Remastered", etc.
 *
 * <p>Patterns are loaded from the {@code edition.edition} config section
 * (under {@code advanced_config} in {@code options.json}). The config shape
 * mirrors the {@code other} section: each entry can be a plain string, a list
 * of specs, or a map with {@code string}/{@code regex}/{@code tags}/{@code value}
 * keys. Multi-value entries (keys starting with {@code _}) emit multiple
 * edition matches from one span.
 *
 * <p>Post-processing mirrors {@link OtherExtractor}'s {@code has-neighbor}
 * validation: matches tagged {@code has-neighbor} are dropped unless an
 * adjacent non-private match exists on either side (or the configured side).
 */
public final class EditionExtractor implements Extractor {

    public static final String EDITION = "edition";

    @Override
    public String name() { return EDITION; }

    @Override
    public void extract(ParseContext ctx) {
        forEachSpec(ctx, EDITION, EditionExtractor::emitSpec);
    }

    private static void emitSpec(ParseContext ctx, String input, String key, Object spec) {
        if (spec instanceof String s) {
            handleStringSpec(ctx, input, key, s);
            return;
        }
        if (!(spec instanceof Map<?, ?> m)) return;

        Object valueOverride = m.get("value");
        if (valueOverride instanceof List<?> multiValues) {
            handleMultiValueSpec(ctx, input, m, multiValues);
            return;
        }

        String editionValue = determineEditionValue(key, valueOverride);
        if (editionValue == null) return;

        var tags = parseTags(m.get("tags"));
        Object validatorSrc = m.containsKey("validator") ? m.get("validator") : SENTINEL;

        emitPatterns(ctx, input, editionValue, m.get("string"), m.get("regex"), validatorSrc, tags);
    }

    private static void handleStringSpec(ParseContext ctx, String input, String key, String s) {
        if (s.startsWith("re:")) {
            emitRegex(ctx, input, key, s.substring(3), SENTINEL, defaultTags());
        } else {
            emitString(ctx, MatchName.EDITION, input, key, s, SENTINEL, defaultTags());
        }
    }

    private static void handleMultiValueSpec(ParseContext ctx, String input, Map<?, ?> m, List<?> multiValues) {
        var tags = parseTags(m.get("tags"));
        Object regexList = m.get("regex");
        for (var val : multiValues) {
            var v = val.toString();
            emitRegexPatterns(ctx, input, v, regexList, tags);
        }
    }

    private static String determineEditionValue(String key, Object valueOverride) {
        String editionValue = key.startsWith("_") ? null : key;
        if (valueOverride instanceof String s) editionValue = s;
        return editionValue;
    }

    private static void emitPatterns(ParseContext ctx, String input, String editionValue,
                                     Object stringList, Object regexList,
                                     Object validatorSrc, Set<String> tags) {
        forEachString(stringList, s -> emitString(ctx, MatchName.EDITION, input, editionValue, s, validatorSrc, tags));
        forEachString(regexList, s -> emitRegex(ctx, input, editionValue, s, validatorSrc, tags));
    }

    private static void emitRegexPatterns(ParseContext ctx, String input, String editionValue,
                                          Object regexList, Set<String> tags) {
        forEachString(regexList, s -> emitRegex(ctx, input, editionValue, s, ConfigPatternHelpers.SENTINEL, tags));
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src,
                                   Object validatorSrc, Set<String> tags) {
        var p = compileDashedCi(src);
        if (p == null) return;
        var validator = resolveValidator(input, validatorSrc);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var m = createMatch(MatchName.EDITION, input, value, tags, s, e);
            if (!validator.test(m)) continue;
            ctx.matches.add(m);
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        removeUnlessNeighbor(ctx, MatchName.EDITION, "has-neighbor", true, true);
        removeUnlessNeighbor(ctx, MatchName.EDITION, "has-neighbor-before", true, false);
        removeUnlessNeighbor(ctx, MatchName.EDITION, "has-neighbor-after", false, true);
        dropOverlappingStreamingService(ctx);
        dedupSameSpan(ctx, MatchName.EDITION);
    }

    private static void dropOverlappingStreamingService(ParseContext ctx) {
        var services = ctx.matches.named(MatchName.STREAMING_SERVICE).toList();
        if (services.isEmpty()) return;
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        for (var ed : ctx.matches.named(MatchName.EDITION).toList()) {
            for (var svc : services) {
                if (svc.start() != ed.start() || svc.end() != ed.end()) continue;
                if (!streamingServiceWillSurvive(ctx, input, svc)) continue;
                toRemove.add(ed);
                break;
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /**
     * Mirror of {@link StreamingServiceExtractor}'s post-pass: a streaming-service
     * match survives only when an adjacent source (suffix) or other (prefix)
     * neighbour with the right tag is separated by sep chars. We replicate the
     * check here because the edition pass runs before the streaming-service
     * pass — without it we would drop a CC-edition match in standalone "CC"
     * input where the streaming-service ends up dropped too.
     */
    private static boolean streamingServiceWillSurvive(ParseContext ctx, String input, Match s) {
        return ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.tags().contains("streaming_service.suffix"))
            .filter(m -> m.start() >= s.end())
            .min(Comparator.comparingInt(Match::start))
            .map(n -> Seps.betweenIsSeps(input, s.end(), n.start())
                    && (s.start() == 0 || Seps.isSep(input.charAt(s.start() - 1))))
            .orElse(false);
    }
}
