package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Comparator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;

import static com.mirkoddd.sift.core.Sift.fromAnywhere;
import static com.mirkoddd.sift.core.Sift.optional;
import static com.mirkoddd.sift.core.SiftPatterns.capture;
import static com.mirkoddd.sift.core.SiftPatterns.literal;

public final class SourceExtractor implements Extractor {

    private static final String SOURCE = "source";
    private static final String OTHER = "other";
    public static final String BLU_RAY = "Blu-ray";

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public String description() {
        return "source / medium (BluRay, WEB-DL, HDTV, DVD, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(SOURCE);

        var otherCapture = capture(OTHER, literal("Rip"));
        var optionalDash = optional().character('-');
        var s1 = fromAnywhere().namedCapture(otherCapture).followedBy(optionalDash);
        var s2 = fromAnywhere().of(optionalDash).then().namedCapture(otherCapture);

        var ripPrefix = String.valueOf(section.getOrDefault("rip_prefix", s1.shake()));
        var ripSuffix = String.valueOf(section.getOrDefault("rip_suffix", s2.shake()));
        var optRipSuffix = "(?:" + ripSuffix + ")?";

        var rules = SourceRuleRegistry.buildRules(ripPrefix, ripSuffix, optRipSuffix);

        for (var rule : rules) {
            apply(ctx, rule);
        }
    }

    private static void apply(ParseContext ctx, SourceRuleRegistry.Rule rule) {
        var input = ctx.input;
        var validator = Validators.sepsBefore(input).or(Validators.sepsAfter(input));
        var matcher = rule.compiledPattern().matcher(input);

        while (matcher.find()) {
            applyOneMatch(ctx, input, rule, validator, matcher);
        }
    }

    private static void applyOneMatch(ParseContext ctx, String input, SourceRuleRegistry.Rule rule,
                                      Predicate<Match> validator, Matcher matcher) {
        int s = matcher.start();
        int e = matcher.end();
        var sourceMatch = new Match(MatchName.SOURCE, rule.source(), s, e,
                input.substring(s, e), 1000, rule.tags(), false);

        if (!validator.test(sourceMatch) || overlapsExtension(ctx, s, e)) return;

        boolean insideStream = ctx.matches.named(MatchName.STREAMING_SERVICE)
                .anyMatch(ss -> ss.start() <= s && e <= ss.end() && (ss.start() < s || e < ss.end()));

        if (insideStream) {
            sourceMatch = new Match(MatchName.SOURCE, rule.source(), s, e,
                    input.substring(s, e), 1000, rule.tags(), true);
        }

        ctx.matches.add(sourceMatch);
        addDerivedOther(ctx, input, matcher, OTHER, rule.otherValue());
        addDerivedOther(ctx, input, matcher, "another", rule.anotherValue());
    }

    private static void addDerivedOther(ParseContext ctx, String input, Matcher matcher,
                                        String groupName, String value) {
        if (value == null) return;
        int gs = groupStart(matcher, groupName);
        int ge = groupEnd(matcher, groupName);
        if (gs >= 0 && ge > gs) {
            ctx.matches.add(new Match(MatchName.OTHER, value, gs, ge,
                    input.substring(gs, ge), 1000, Set.of("coexist", "derivedFrom:source"), false));
        }
    }

    private static boolean overlapsExtension(ParseContext ctx, int s, int e) {
        return ctx.matches.named(MatchName.CONTAINER)
                .anyMatch(m -> m.tags().contains("extension") && m.start() < e && s < m.end());
    }

    private static int groupStart(Matcher m, String name) {
        try { return m.start(name); }
        catch (IllegalArgumentException | IllegalStateException _) { return -1; }
    }

    private static int groupEnd(Matcher m, String name) {
        try { return m.end(name); }
        catch (IllegalArgumentException | IllegalStateException _) { return -1; }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        validatePrefixSuffix(ctx);
        validateWeakSource(ctx);
        upgradeUltraHdBluray(ctx);
    }

    private void validatePrefixSuffix(ParseContext ctx) {
        var sepsBefore = Validators.sepsBefore(ctx.input);
        var sepsAfter = Validators.sepsAfter(ctx.input);

        ctx.matches.named(MatchName.SOURCE)
                .filter(s -> (!sepsBefore.test(s) && noNeighborTag(ctx, s.start() - 1, "source-prefix")) ||
                        (!sepsAfter.test(s) && noNeighborTag(ctx, s.end(), "source-suffix")))
                .toList()
                .forEach(ctx.matches::remove);
    }

    private void validateWeakSource(ParseContext ctx) {
        var pathMarkers = ctx.markers.stream()
                .filter(m -> "path".equals(m.name()))
                .toList();

        ctx.matches.named(MatchName.SOURCE)
                .filter(m -> m.tags().contains("weak.source"))
                .filter(weak -> pathMarkers.stream().anyMatch(fp -> shouldRemoveWeakSource(ctx, fp, weak)))
                .toList()
                .forEach(ctx.matches::remove);
    }

    private static boolean shouldRemoveWeakSource(ParseContext ctx, Marker filePart, Match weak) {
        if (!filePart.covers(weak.start(), weak.end())) return false;

        boolean later = ctx.matches.named(MatchName.SOURCE)
                .anyMatch(m -> m != weak && m.start() >= weak.end() && m.end() <= filePart.end());

        if (!later) return false;
        return !ctx.input.substring(filePart.start(), weak.start()).isBlank();
    }

    private void upgradeUltraHdBluray(ParseContext ctx) {
        var pathMarkers = ctx.markers.stream()
                .filter(m -> "path".equals(m.name()))
                .toList();

        ctx.matches.named(MatchName.SOURCE)
                .filter(m -> BLU_RAY.equals(m.value()))
                .toList()
                .forEach(bd -> pathMarkers.stream()
                        .filter(fp -> fp.covers(bd.start(), bd.end()))
                        .findFirst()
                        .ifPresent(fp -> tryUpgradeBluray(ctx, fp, bd)));
    }

    private void tryUpgradeBluray(ParseContext ctx, Marker filePart, Match bd) {
        var uhdOther = findUltraHd(ctx, filePart.start(), bd.start(), true);
        boolean ok = uhdOther != null && validRange(ctx, uhdOther.end(), bd.start());

        if (!ok) {
            uhdOther = findUltraHd(ctx, bd.end(), filePart.end(), false);
            ok = uhdOther != null && validRange(ctx, bd.end(), uhdOther.start());
        }

        if (!ok) {
            if (!has2160p(ctx, filePart)) return;
            uhdOther = null;
        }

        if (uhdOther != null) ctx.matches.remove(uhdOther);

        ctx.matches.replace(bd, new Match(MatchName.SOURCE, "Ultra HD Blu-ray",
                bd.start(), bd.end(), bd.raw(), bd.priority(), bd.tags(), bd.isPrivate()));
    }

    private static boolean has2160p(ParseContext ctx, Marker filePart) {
        return ctx.matches.named(MatchName.SCREEN_SIZE)
                .anyMatch(m -> "2160p".equals(m.value()) && filePart.covers(m.start(), m.end()));
    }

    private static Match findUltraHd(ParseContext ctx, int start, int end, boolean preferLast) {
        var candidates = ctx.matches.named(MatchName.OTHER)
                .filter(m -> isUltraHdCandidateInRange(m, start, end));

        return preferLast
                ? candidates.max(Comparator.comparingInt(Match::end)).orElse(null)
                : candidates.min(Comparator.comparingInt(Match::start)).orElse(null);
    }

    private static boolean isUltraHdCandidateInRange(Match m, int start, int end) {
        return !m.isPrivate() && "Ultra HD".equals(m.value()) && m.start() >= start && m.end() <= end;
    }

    private static boolean validRange(ParseContext ctx, int s, int e) {
        return s >= e || (allMatchesAreAllowed(ctx, s, e) && !hasNonSeparatorHoles(ctx, s, e));
    }

    private static boolean allMatchesAreAllowed(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate() && m.start() >= s && m.end() <= e)
                .allMatch(SourceExtractor::isAllowedMatch);
    }

    private static boolean isAllowedMatch(Match m) {
        return m.name() == MatchName.SCREEN_SIZE
                || m.name() == MatchName.COLOR_DEPTH
                || (m.name() == MatchName.OTHER && m.tags().contains("uhdbluray-neighbor"));
    }

    private static boolean hasNonSeparatorHoles(ParseContext ctx, int s, int e) {
        if (s >= e) return false;

        boolean[] covered = new boolean[e - s];

        ctx.matches.all()
                .filter(m -> !m.isPrivate() && m.start() < e && m.end() > s)
                .forEach(m -> {
                    int from = Math.max(m.start(), s) - s;
                    int to = Math.min(m.end(), e) - s;
                    for (int i = from; i < to; i++) covered[i] = true;
                });

        for (int i = 0; i < covered.length; i++) {
            if (!covered[i] && !Seps.isSep(ctx.input.charAt(s + i))) return true;
        }

        return false;
    }

    private static boolean noNeighborTag(ParseContext ctx, int pos, String tag) {
        return ctx.matches.all().noneMatch(m -> m.tags().contains(tag) && m.start() <= pos && pos <= m.end());
    }
}