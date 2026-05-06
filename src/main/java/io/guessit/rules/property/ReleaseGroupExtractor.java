package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static io.guessit.engine.MatchName.*;

/**
 * Extracts {@code release_group} (the scene/p2p group name appended to the
 * release).
 *
 * <p>Last extractor in the registry order — release-group detection looks
 * at <em>everything else</em> the pipeline already produced (codecs, source,
 * languages, the container extension, structural markers) to find the
 * untaken span at the end of the filepart.
 *
 * <p>Three detection strategies, tried in order until one succeeds:
 * <ol>
 *   <li>{@link #detectDashSeparated} — trailing {@code -Group.ext}, the
 *       most common scene shape.</li>
 *   <li>{@link #detectScene} — token after a known scene-property tail
 *       ({@code video_codec}, {@code audio_codec}, {@code source}, etc.).</li>
 *   <li>{@link #detectAnimeBrackets} — group inside leading or trailing
 *       brackets, the typical anime fansub shape.</li>
 * </ol>
 *
 * <p>{@link #FORBIDDEN_NAMES} is the small denylist of false-positive
 * candidates ("rip", "by", "for", …) — words that look like group names but
 * are actually release-language fragments. {@link #cleanGroupName} normalises
 * the {@code "name) [tag]"} → {@code "name tag"} pattern that releasers use
 * when combining a group name with a sub-tag.
 *
 * <p>The {@code expected_group} option short-circuits all heuristics: any
 * configured name found in the input becomes a {@code release_group} match
 * with priority 2000 so nothing else can displace it.
 */
public final class ReleaseGroupExtractor implements Extractor {
    private static final String EXPECTED_TAG = "expected";
    private static final String EXTENSION_TAG = "extension";
    private static final String SCENE_TAG = "scene";
    private static final String NOT_A_RG_TAG = "not-a-release-group";
    public static final MatchName LANGUAGE = MatchName.LANGUAGE;
    public static final MatchName SUBTITLE_LANGUAGE = MatchName.SUBTITLE_LANGUAGE;
    public static final MatchName OTHER = MatchName.OTHER;
    public static final MatchName CONTAINER = MatchName.CONTAINER;
    private static final Set<MatchName> SCENE_PREV = Set.of(
            VIDEO_CODEC, SOURCE, VIDEO_API, AUDIO_CODEC, AUDIO_PROFILE, MatchName.VIDEO_PROFILE,
            MatchName.AUDIO_CHANNELS, MatchName.SCREEN_SIZE, OTHER, CONTAINER,
            LANGUAGE, SUBTITLE_LANGUAGE, LANGUAGE_SUFFIX, MatchName.YEAR);

    /**
     * Forbidden release-group prefix/suffix names (from config: release_group.forbidden_names).
     */
    private static final List<String> FORBIDDEN_NAMES = List.of("bonus", "by", "for", "par", "pour", "rip");

    /**
     * Separators that are NOT stripped from group names (config: release_group.ignored_seps).
     */
    private static final String IGNORED_SEPS = "[]{}()";

    /**
     * Combined parens+brackets pattern: "name) [tag]" → "name tag". Mirrors Python clean_groupname.
     */
    private static final Pattern PARENS_BRACKETS = Pattern.compile("(.+)\\)\\s?\\[(.+)]");

    private static final ConcurrentMap<String, Pattern> EXPECTED_RE_CACHE = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "release_group";
    }

    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedGroup();
        if (expected.isEmpty()) return;
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        for (var name : expected) {
            if (processIfReRegex(ctx, name, input, validator)) continue;
            int from = 0;
            var hay = input.toLowerCase();
            var n = name.toLowerCase();
            while (true) {
                int idx = hay.indexOf(n, from);
                if (idx < 0) break;
                int end = idx + name.length();
                var m = new Match(MatchName.RELEASE_GROUP, name, idx, end, input.substring(idx, end),
                        2000, Set.of(EXPECTED_TAG), false);
                if (validator.test(m)) ctx.matches.add(m);
                from = idx + 1;
            }
        }
    }

    private static boolean processIfReRegex(ParseContext ctx, String name, String input, Predicate<Match> validator) {
        if (name.startsWith("re:")) {
            // Regex entry: case-insensitive scan; raw is the matched span.
            var rxSrc = name.substring(3);
            var pat = EXPECTED_RE_CACHE.computeIfAbsent(rxSrc, s -> {
                try {
                    return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException _) {
                    return null;
                }
            });
            if (pat == null) return true;
            var matcher = pat.matcher(input);
            while (matcher.find()) {
                int s = matcher.start();
                int e = matcher.end();
                if (e <= s) continue;
                var raw = input.substring(s, e);
                var m = new Match(MatchName.RELEASE_GROUP, raw, s, e, raw, 2000, Set.of(EXPECTED_TAG), false);
                if (validator.test(m)) ctx.matches.add(m);
            }
            return true;
        }
        return false;
    }

    @Override
    public void postProcess(ParseContext ctx) {
        if (ctx.matches.named(MatchName.RELEASE_GROUP).findAny().isPresent()) return;
        if (detectScene(ctx)) return;
        if (detectDashSeparated(ctx)) return;
        detectAnimeBrackets(ctx);
    }

    private boolean detectDashSeparated(ParseContext ctx) {
        // Mirror python DashSeparatedReleaseGroup: walks fileparts in spatial
        // order (outermost first) and bails globally on first hit. Reverse
        // (innermost-first) order misclassifies inputs like
        // ".../How.To.Be.Single.2016...-BLOW/blow-how.to.be.single.2016...mkv"
        // where the inner filepart's "blow-" matches leading-dash before the
        // outer's trailing "-BLOW" is seen.
        for (var filepart : pathFilepartsLeftmostFirst(ctx)) {
            if (tryDetectTrailingDashGroup(ctx, filepart)) return true;
            if (tryDetectLeadingDashGroup(ctx, filepart)) return true;
        }
        return false;
    }

    private boolean tryDetectTrailingDashGroup(ParseContext ctx, Marker filepart) {
        var input = ctx.input;

        // Find the end boundary (container extension or known extension)
        var ext = ctx.matches.named(MatchName.CONTAINER)
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains(EXTENSION_TAG))
                .findFirst().orElse(null);
        int end = ext != null ? ext.start() : trimKnownExtension(ctx, filepart);
        int endBeforeTrim = end;
        end = trimNotAReleaseGroupTail(ctx, filepart, end);

        int dash = input.lastIndexOf('-', end - 1);

        // Guard: when not-a-release-group was trimmed and filepart is title-only, skip
        if (shouldSkipForTitleSlot(ctx, filepart, end, endBeforeTrim, dash)) {
            return false;
        }

        if (!isValidDashPosition(dash, filepart.start(), end)) {
            return false;
        }

        // Extract and validate candidate between dash and end
        var candidateSpan = extractCandidateSpan(input, dash + 1, end);
        if (candidateSpan == null) return false;

        var raw = input.substring(candidateSpan.start, candidateSpan.end);
        var candidate = cleanGroupName(raw);

        if (!isValidTrailingCandidate(ctx, filepart, candidate, candidateSpan.start, candidateSpan.end)) {
            return false;
        }

        addReleaseGroupMatch(ctx, candidate, raw, candidateSpan.start, candidateSpan.end);
        return true;
    }

    private boolean tryDetectLeadingDashGroup(ParseContext ctx, Marker filepart) {
        var input = ctx.input;
        var part = input.substring(filepart.start(), filepart.end());

        // Skip when filepart starts with bracket/paren — leading-bracket group
        // belongs to detectAnimeBrackets, not dash-leading.
        int firstDash = part.indexOf('-');
        if (!isValidLeadingDashPosition(part, firstDash)) {
            return false;
        }

        var rawCandidate = part.substring(0, firstDash);
        var candidate = cleanGroupName(rawCandidate);
        int absDashEnd = filepart.start() + firstDash;

        int firstMatchAfter = findFirstMatchAfter(ctx, filepart, absDashEnd);
        var restToFirstMatch = ctx.input.substring(absDashEnd + 1, firstMatchAfter);

        // Python guessit's candidate stops at the first sep; we use the
        // dash-prefix shape so reject candidates that already contain a
        // word separator ("Show.Name." → reject for inputs like
        // "Show.Name.-.07.(2016).[Group]..." where the title sits where
        // we'd otherwise call it a release_group).
        if (!isValidLeadingCandidate(candidate, restToFirstMatch)) {
            return false;
        }

        int absStart = filepart.start();
        if (overlapsAnyLanguage(ctx, absStart, absDashEnd)) {
            return false;
        }

        removeOverlappingLanguages(ctx, absStart, absDashEnd);
        ctx.matches.add(new Match(MatchName.RELEASE_GROUP, candidate, absStart, absDashEnd,
                rawCandidate, 1500, Set.of(SCENE_TAG), false));
        return true;
    }

    private boolean shouldSkipForTitleSlot(ParseContext ctx, Marker filepart,
                                           int end, int endBeforeTrim, int dash) {
        return end < endBeforeTrim
                && dash > filepart.start()
                && filepartIsTitleOnly(ctx, filepart, dash);
    }

    private boolean isValidDashPosition(int dash, int start, int end) {
        return dash > start && dash < end - 1;
    }

    private static class CandidateSpan {
        final int start;
        final int end;

        CandidateSpan(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private CandidateSpan extractCandidateSpan(String input, int start, int end) {
        int s = start;
        int e = end;
        while (s < e && isGroupSep(input.charAt(s))) s++;
        while (e > s && isGroupSep(input.charAt(e - 1))) e--;
        return s < e ? new CandidateSpan(s, e) : null;
    }

    private boolean isValidTrailingCandidate(ParseContext ctx, Marker filepart,
                                             String candidate, int s, int e) {
        if (!validGroupName(candidate, false, true)) return false;
        if (overlapsNonLanguageExceptHd(ctx, s, e)) return false;
        if (overlapsSubtitleLanguage(ctx, s, e)) return false;
        if (!hasDotSeparatedPredecessors(ctx, filepart.start(), s)) return false;
        return isNotProbableLanguagePrefix(candidate);
    }

    private void addReleaseGroupMatch(ParseContext ctx, String candidate, String raw, int s, int e) {
        dropHdInsideCandidate(ctx, s, e);
        removeOverlappingLanguages(ctx, s, e);
        ctx.matches.add(new Match(MatchName.RELEASE_GROUP, candidate, s, e,
                raw, 1500, Set.of(SCENE_TAG), false));
    }

    private boolean isValidLeadingDashPosition(String part, int firstDash) {
        return firstDash > 0
                && firstDash < part.length() - 1
                && part.charAt(0) != '['
                && part.charAt(0) != '(';
    }

    private int findFirstMatchAfter(ParseContext ctx, Marker filepart, int absDashEnd) {
        int firstMatchAfter = ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.start() > absDashEnd && m.end() <= filepart.end())
                .mapToInt(Match::start).min().orElse(filepart.end());
        if (firstMatchAfter < absDashEnd + 1) firstMatchAfter = absDashEnd + 1;
        return firstMatchAfter;
    }

    private boolean isValidLeadingCandidate(String candidate, String restToFirstMatch) {
        return validGroupName(candidate, false)
                && !candidate.contains(".")
                && !candidate.contains(" ")
                && restToFirstMatch.contains(".")
                && !restToFirstMatch.contains(" ")
                && restToFirstMatch.indexOf('-') < 0;
    }

    private boolean overlapsAnyLanguage(ParseContext ctx, int start, int end) {
        return overlapsNonLanguage(ctx, start, end)
                || overlapsSubtitleLanguage(ctx, start, end)
                || overlapsLanguage(ctx, start, end);
    }

    /**
     * Mirror python's DashSeparatedReleaseGroup.is_valid for at_end=True:
     * walking left from the candidate's start, the immediately preceding
     * non-private match must be `-`-separated from the candidate, and any
     * earlier matches must be `.`-separated from the next-rightward match.
     * Rejects "Title-s03-e02-EpName" where the chain uses dashes throughout
     * (the trailing word is episode_title, not a release_group).
     */
    private static boolean hasDotSeparatedPredecessors(ParseContext ctx, int filepartStart, int candidateStart) {
        int boundary = candidateStart;
        int count = 0;
        while (true) {
            int curBoundary = boundary;
            var prev = ctx.matches.all()
                    .filter(m -> !m.isPrivate())
                    .filter(m -> !m.tags().contains(EXPECTED_TAG))
                    .filter(m -> m.start() >= filepartStart && m.end() <= curBoundary)
                    .reduce((a, b) -> a.end() >= b.end() ? a : b)
                    .orElse(null);
            if (prev == null) return false;
            String sep = ctx.input.substring(prev.end(), boundary);
            // Strip nothing; python checks raw separator literal.
            if (count == 0) {
                if (!"-".equals(sep)) return false;
                count++;
                boundary = prev.start();
                continue;
            }
            return ".".equals(sep);
            // Otherwise keep walking — but python breaks here. Match python:
            // any non-`.` separator after the first chain step disqualifies.
        }
    }

    private static boolean overlapsSubtitleLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.named(MatchName.SUBTITLE_LANGUAGE)
                .anyMatch(m -> m.start() < e && s < m.end());
    }

    private static boolean overlapsLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.named(MatchName.LANGUAGE)
                .anyMatch(m -> m.start() < e && s < m.end());
    }

    /**
     * Mirrors python's demote_other conflict_solver: when a source match sits
     * at the trailing position of the filepart (no separator-only suffix)
     * AND another non-tail source already exists earlier, the trailing one
     * is reclassified as a release_group candidate. The match is removed from
     * source so detectScene can pick the previous source/codec as scene_prev
     * and emit the trailing chunk as RELEASE_GROUP.
     */
    private static void promoteTrailingSourceToReleaseGroup(ParseContext ctx, io.guessit.engine.Marker filepart, int rangeEnd) {
        var input = ctx.input;
        var sources = ctx.matches.named(SOURCE)
                .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
                .sorted(java.util.Comparator.comparingInt(m -> -m.end()))
                .toList();
        if (sources.size() < 2) return;
        var trailing = sources.getFirst();
        // Trailing source must abut rangeEnd via separator-only suffix.
        var tail = input.substring(trailing.end(), rangeEnd);
        if (!tail.isEmpty() && tail.chars().anyMatch(c -> Character.isLetterOrDigit((char) c))) return;
        // Must be preceded by a dash (i.e. dash form like "...x264-SDTV").
        // Other separators (space, dot) don't carry the demote-to-RG signal —
        // python only treats trailing source as RG candidate in dash form.
        // Without this restriction, "dvd ts" loses its source=Telesync.
        if (trailing.start() == 0) return;
        char prevCh = input.charAt(trailing.start() - 1);
        if (prevCh != '-') return;
        ctx.matches.remove(trailing);
    }

    private boolean detectScene(ParseContext ctx) {
        var input = ctx.input;
        for (var filepart : pathFilepartsRightmostFirst(ctx)) {
            var candidateMatch = tryExtractSceneCandidateFromFilepart(ctx, filepart, input);
            if (candidateMatch != null) {
                ctx.matches.add(candidateMatch);
                return true;
            }
        }
        return false;
    }

    private Match tryExtractSceneCandidateFromFilepart(ParseContext ctx, Marker filepart, String input) {
        int rangeEnd = calculateRangeEnd(ctx, filepart);
        promoteTrailingSourceToReleaseGroup(ctx, filepart, rangeEnd);

        var prev = findRightmostScenePrev(ctx, filepart, rangeEnd);
        if (prev == null || shouldSkipLoneLanguageScenePrev(ctx, filepart, rangeEnd, prev)) {
            return null;
        }

        var candidateSpan = extractCandidateSpanAfterScenePrev(ctx, input, prev, rangeEnd);
        if (candidateSpan == null) {
            return tryPromoteScenePrevToReleaseGroup(ctx, input, filepart, prev, rangeEnd);
        }

        return buildSceneReleaseGroupMatch(ctx, input, filepart, prev, candidateSpan);
    }

    private int calculateRangeEnd(ParseContext ctx, Marker filepart) {
        var ext = ctx.matches.named(MatchName.CONTAINER)
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains(EXTENSION_TAG))
                .findFirst().orElse(null);
        int rangeEnd = ext != null ? ext.start() : trimKnownExtension(ctx, filepart);
        return trimNotAReleaseGroupTail(ctx, filepart, rangeEnd);
    }

    private Match findRightmostScenePrev(ParseContext ctx, Marker filepart, int rangeEnd) {
        return ctx.matches.all()
                .filter(m -> SCENE_PREV.contains(m.name()))
                .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
    }

    private boolean shouldSkipLoneLanguageScenePrev(ParseContext ctx, Marker filepart, int rangeEnd, Match prev) {
        if (isNotLanguageOrYearMatch(prev)) {
            return false;
        }

        long siblingScenePrev = countNonLanguageSiblings(ctx, filepart, rangeEnd, prev);
        return siblingScenePrev == 0;
    }

    private boolean isNotLanguageOrYearMatch(Match match) {
        return match.name() != MatchName.LANGUAGE
                && match.name() != MatchName.SUBTITLE_LANGUAGE
                && match.name() != MatchName.YEAR;
    }

    private long countNonLanguageSiblings(ParseContext ctx, Marker filepart, int rangeEnd, Match prev) {
        return ctx.matches.all()
                .filter(m -> SCENE_PREV.contains(m.name()) && m != prev)
                .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
                .filter(this::isNotLanguageOrYearMatch)
                .count();
    }

    private CandidateSpan extractCandidateSpanAfterScenePrev(ParseContext ctx, String input,
                                                             Match prev, int rangeEnd) {
        var gap = input.substring(prev.end(), rangeEnd);
        int leadSeps = countLeadingSeparators(gap);
        int trailSeps = countTrailingSeparators(gap, leadSeps);
        int s = prev.end() + leadSeps;
        int e = rangeEnd - trailSeps;

        if (e <= s) {
            return null;
        }

        s = skipStrayClosingBracket(input, s, e);
        if (s >= e) {
            return null;
        }

        var bracketAdjusted = extractBracketWrappedCandidate(input, s, e);
        if (bracketAdjusted == null) {
            return null;
        }
        s = bracketAdjusted.start;
        e = bracketAdjusted.end;

        s = skipSubtitleLanguagePrefix(ctx, input, s, e);

        return new CandidateSpan(s, e);
    }

    private int countLeadingSeparators(String gap) {
        int count = 0;
        while (count < gap.length() && isGroupSep(gap.charAt(count))) {
            count++;
        }
        return count;
    }

    private int countTrailingSeparators(String gap, int leadSeps) {
        int count = 0;
        while (count < gap.length() - leadSeps && isGroupSep(gap.charAt(gap.length() - 1 - count))) {
            count++;
        }
        return count;
    }

    private int skipStrayClosingBracket(String input, int s, int e) {
        while (s < e && (input.charAt(s) == ']' || isGroupSep(input.charAt(s)))) {
            s++;
        }
        return s;
    }

    private CandidateSpan extractBracketWrappedCandidate(String input, int s, int e) {
        if (input.charAt(s) != '[') {
            return new CandidateSpan(s, e);
        }

        int closeIdx = input.indexOf(']', s + 1);
        if (closeIdx <= s || closeIdx < e - 1) {
            return null;
        }

        int innerS = s + 1;
        int innerE = closeIdx;
        while (innerS < innerE && isGroupSep(input.charAt(innerS))) innerS++;
        while (innerE > innerS && isGroupSep(input.charAt(innerE - 1))) innerE--;

        return innerE > innerS ? new CandidateSpan(innerS, innerE) : null;
    }

    private int skipSubtitleLanguagePrefix(ParseContext ctx, String input, int s, int e) {
        int sFinal = s;
        boolean advanced = true;

        while (advanced) {
            advanced = false;
            var subtitleLang = findSubtitleLanguageAtStart(ctx, sFinal, e);
            if (subtitleLang != null) {
                sFinal = skipPastMatchAndSeparators(input, subtitleLang.end(), e);
                advanced = true;
                continue;
            }

            var marker = findSubtitlePrefixMarkerAtStart(ctx, sFinal, e);
            if (marker != null) {
                sFinal = skipPastMatchAndSeparators(input, marker.end(), e);
                advanced = true;
            }
        }

        return sFinal;
    }

    private Match findSubtitleLanguageAtStart(ParseContext ctx, int s, int e) {
        return ctx.matches.named(MatchName.SUBTITLE_LANGUAGE)
                .filter(m -> m.start() == s && m.end() < e)
                .findFirst().orElse(null);
    }

    private Match findSubtitlePrefixMarkerAtStart(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
                .filter(m -> m.isPrivate() && m.name()==SUBTITLE_LANGUAGE_PREFIX)
                .filter(m -> m.start() == s && m.end() < e)
                .findFirst().orElse(null);
    }

    private int skipPastMatchAndSeparators(String input, int start, int end) {
        int pos = start;
        while (pos < end && isGroupSep(input.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private Match tryPromoteScenePrevToReleaseGroup(ParseContext ctx, String input,
                                                    Marker filepart, Match prev, int rangeEnd) {
        if (!canPromoteScenePrevToReleaseGroup(ctx, input, filepart, prev, rangeEnd)) {
            return null;
        }

        var rawPrev = input.substring(prev.start(), prev.end());
        if (!validGroupName(rawPrev, false, true)) {
            return null;
        }

        ctx.matches.remove(prev);
        return new Match(MatchName.RELEASE_GROUP, rawPrev, prev.start(), prev.end(),
                rawPrev, 1500, Set.of(SCENE_TAG), false);
    }

    private boolean canPromoteScenePrevToReleaseGroup(ParseContext ctx, String input,
                                                      Marker filepart, Match prev, int rangeEnd) {
        return prev.end() == rangeEnd
                && isPromotableLanguageMatch(prev)
                && prev.start() > filepart.start()
                && input.charAt(prev.start() - 1) == '-'
                && hasDotSeparatedPredecessors(ctx, filepart.start(), prev.start());
    }

    private boolean isPromotableLanguageMatch(Match match) {
        return match.name() == MatchName.LANGUAGE
                || match.name() == MatchName.SUBTITLE_LANGUAGE
                || match.name() == MatchName.COUNTRY;
    }

    private Match buildSceneReleaseGroupMatch(ParseContext ctx, String input, Marker filepart,
                                              Match prev, CandidateSpan span) {
        var raw = input.substring(span.start, span.end);
        var candidate = cleanGroupName(raw);

        if (!isValidSceneCandidate(ctx, filepart, prev, candidate, span)) {
            return null;
        }

        dropHdInsideCandidate(ctx, span.start, span.end);
        removeOverlappingLanguages(ctx, span.start, span.end);
        return new Match(MatchName.RELEASE_GROUP, candidate, span.start, span.end, raw, 1500, Set.of(SCENE_TAG), false);
    }

    private boolean isValidSceneCandidate(ParseContext ctx, Marker filepart, Match prev,
                                          String candidate, CandidateSpan span) {
        return validGroupName(candidate, true)
                && isNotProbableLanguagePrefix(candidate)
                && !overlapsNonLanguageExceptHd(ctx, span.start, span.end)
                && !overlapsSubtitleLanguage(ctx, span.start, span.end)
                && !candidateIsLikelyTitle(ctx, filepart, prev, span.end);
    }

    private boolean detectAnimeBrackets(ParseContext ctx) {
        // Mirror python AnimeReleaseGroup: emit the FIRST empty group marker
        // (in input order) whose contents are not all-digits and contain no
        // recognized properties. This handles both `[Fansub] One Piece 603`
        // (leading) and `One.Piece.[Group]` (trailing) — whichever appears
        // first in the path filepart.
        var groups = new java.util.ArrayList<io.guessit.engine.Marker>();
        for (var marker : ctx.markers) if ("group".equals(marker.name())) groups.add(marker);
        for (var marker : groups) {
            if (tryEmitAnimeBracket(ctx, marker)) return true;
        }
        return false;
    }

    private boolean tryEmitAnimeBracket(ParseContext ctx, io.guessit.engine.Marker marker) {
        var raw = marker.raw();
        String innerStr = raw;
        int innerS = marker.start();
        int innerE = marker.end();
        if (raw.length() >= 2 && (raw.charAt(0) == '[' || raw.charAt(0) == '(')) {
            innerStr = raw.substring(1, raw.length() - 1);
            innerS = marker.start() + 1;
            innerE = marker.end() - 1;
        }
        final int fInnerS = innerS;
        final int fInnerE = innerE;
        var trimmed = innerStr.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.chars().allMatch(Character::isDigit)) return false;
        // Reject if the bracket contains *any* recognized property — including
        // language matches: "[ENG+RU+PT]" is a language list, not a group.
        boolean hasAnyInside = ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .anyMatch(m -> m.start() >= fInnerS && m.end() <= fInnerE);
        if (hasAnyInside) return false;
        ctx.matches.add(new Match(MatchName.RELEASE_GROUP, trimmed, fInnerS, fInnerE,
                innerStr, 1500, Set.of("anime"), false));
        return true;
    }

    /**
     * True when (a) a {@code not-a-release-group} {@code other} sits between
     * the candidate end and the filepart's container/extension and (b) the
     * filepart has no usable alpha-text hole before {@code prev} that would
     * become the title. In that shape the candidate is the only viable title
     * slot, so RG should yield to {@code TitleExtractor.postProcess}.
     */
    private static boolean candidateIsLikelyTitle(ParseContext ctx, Marker filepart, Match prev, int candidateEnd) {
        var notRgAfter = ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains(NOT_A_RG_TAG))
                .filter(m -> m.start() >= candidateEnd && m.end() <= filepart.end())
                .findFirst().orElse(null);
        if (notRgAfter == null) return false;
        return noLeadingTitleHole(ctx, filepart, prev.start());
    }

    /**
     * Same shape as {@link #candidateIsLikelyTitle} but used by
     * {@link #detectDashSeparated} where there is no scene_prev pointer —
     * treat the dash position as the right boundary.
     */
    private static boolean filepartIsTitleOnly(ParseContext ctx, Marker filepart, int rightBoundary) {
        var notRgAfter = ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains(NOT_A_RG_TAG))
                .filter(m -> m.start() >= rightBoundary && m.end() <= filepart.end())
                .findFirst().orElse(null);
        if (notRgAfter == null) return false;
        return noLeadingTitleHole(ctx, filepart, rightBoundary);
    }

    private static boolean noLeadingTitleHole(ParseContext ctx, Marker filepart, int rightBoundary) {
        var input = ctx.input;
        int hs = filepart.start();
        if (rightBoundary <= hs) return true;
        // Include private matches (e.g. SxxExx episodeMarker "e", chain heads)
        // so leading marker chars don't count as a title hole. Need to gauge
        // truly unmatched input.
        var prevMatches = ctx.matches.all()
                .filter(m -> m.end() <= rightBoundary && m.start() >= filepart.start())
                .sorted(java.util.Comparator.comparingInt(Match::start))
                .toList();
        int cursor = hs;
        for (var m : prevMatches) {
            if (m.start() > cursor) {
                var gap = input.substring(cursor, m.start());
                if (gap.chars().anyMatch(c -> !isGroupSep((char) c))) return false;
            }
            if (m.end() > cursor) cursor = m.end();
        }
        if (cursor < rightBoundary) {
            var gap = input.substring(cursor, rightBoundary);
            return gap.chars().noneMatch(Character::isLetter);
        }
        return true;
    }

    private static boolean overlapsNonLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.name() != MatchName.LANGUAGE && m.name() != MatchName.SUBTITLE_LANGUAGE)
                .anyMatch(m -> m.start() < e && s < m.end());
    }

    /**
     * Like {@link #overlapsNonLanguage} but ignores {@code other} matches whose
     * values are video-format flags ({@link #RG_INTERIOR_OTHER}) — those get
     * dropped by {@link #dropHdInsideCandidate} once the group commits.
     */
    private static boolean overlapsNonLanguageExceptHd(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.name() != MatchName.LANGUAGE && m.name() != MatchName.SUBTITLE_LANGUAGE)
                .filter(m -> !(m.name() == MatchName.OTHER && RG_INTERIOR_OTHER.contains(m.value())))
                .anyMatch(m -> m.start() < e && s < m.end());
    }

    /**
     * Path markers in marker_sorted order: most-valuable filepart first
     * (most distinct match names; rightmost wins on tie). Mirrors python's
     * marker_sorted used by SceneReleaseGroup so the filepart with the
     * richest match set claims the release_group, including its casing.
     *
     * <p>Adds a synthetic "episode_title" weight when a filepart has a
     * text-bearing hole between an episode/season match and the next
     * source/codec/screen_size/other match. Java's RG postProcess runs
     * before EpisodeTitleExtractor.postProcess, so episode_title isn't yet
     * emitted; python's RG sees it because EpisodeTitleFromPosition runs
     * first. Counting the hole equalizes the comparison.
     */
    private static List<Marker> pathFilepartsRightmostFirst(ParseContext ctx) {
        var paths = new java.util.ArrayList<Marker>();
        for (var m : ctx.markers) if (m.name().equals("path")) paths.add(m);
        return markerSortedWithEpisodeTitleHint(paths, ctx);
    }

    private static final Set<MatchName> EP_TITLE_NEXT_NAMES = Set.of(
            MatchName.SCREEN_SIZE, SOURCE, VIDEO_CODEC, AUDIO_CODEC, MatchName.OTHER,
            MatchName.CONTAINER, MatchName.STREAMING_SERVICE);

    private static java.util.function.Predicate<Match> markerWeightPredicate() {
        return m -> !m.isPrivate()
                && m.name() != MatchName.PROPER_COUNT
                && m.name() != MatchName.TITLE
                && !(m.name() == MatchName.CONTAINER && m.tags().contains(EXTENSION_TAG))
                && !(m.name() == MatchName.OTHER && "Rip".equals(m.value()));
    }

    private static int filepartWeight(Marker fp, ParseContext ctx) {
        var pred = markerWeightPredicate();
        var weight = (int) ctx.matches.range(fp.start(), fp.end(), pred)
                .map(Match::name).distinct().count();
        if (hasEpisodeTitleHole(fp, ctx)) weight++;
        return weight;
    }

    private static boolean hasEpisodeTitleHole(Marker fp, ParseContext ctx) {
        var ep = ctx.matches.all()
                .filter(m -> m.name() == MatchName.EPISODE || m.name() == MatchName.SEASON)
                .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
        if (ep == null) return false;
        var next = ctx.matches.all()
                .filter(m -> EP_TITLE_NEXT_NAMES.contains(m.name()))
                .filter(m -> m.start() >= ep.end() && m.end() <= fp.end())
                .reduce((a, b) -> a.start() <= b.start() ? a : b)
                .orElse(null);
        if (next == null) return false;
        var gap = ctx.input.substring(ep.end(), next.start());
        for (int i = 0; i < gap.length(); i++) {
            if (Character.isLetter(gap.charAt(i))) return true;
        }
        return false;
    }

    private static List<Marker> markerSortedWithEpisodeTitleHint(List<Marker> paths, ParseContext ctx) {
        var indexed = new java.util.ArrayList<Integer>();
        for (var i = 0; i < paths.size(); i++) indexed.add(i);
        indexed.sort((a, b) -> {
            var wa = filepartWeight(paths.get(a), ctx);
            var wb = filepartWeight(paths.get(b), ctx);
            var byWeight = Integer.compare(wb, wa);
            if (byWeight != 0) return byWeight;
            return Integer.compare(b, a);
        });
        var ret = new java.util.ArrayList<Marker>();
        for (var i : indexed) ret.add(paths.get(i));
        return ret;
    }

    private static List<Marker> pathFilepartsLeftmostFirst(ParseContext ctx) {
        var paths = new java.util.ArrayList<Marker>();
        for (var m : ctx.markers) if (m.name().equals("path")) paths.add(m);
        return paths;
    }

    /**
     * Walks back over trailing {@code other} matches tagged
     * {@code not-a-release-group} (Sample, Proof, Obfuscated, Repost suffixes)
     * so they don't block release-group detection. Mirrors Python guessit's
     * predicate filter in {@code release_group.detect}.
     */
    private static final java.util.regex.Pattern KNOWN_TRAILING_EXT = java.util.regex.Pattern.compile(
            "(?i)\\.(?:mkv|mp4|avi|mov|m4v|mpeg|mpg|ts|m2ts|wmv|webm|flv|ogg|ogm|ogv|iso|3gp|3g2|3gp2|asf|divx|mka|mk2|mk3d|mp4a|qt|ra|ram|rm|vob|wav|wma|srt|idx|sub|ssa|ass|nfo|torrent|nzb)$");

    /**
     * When no container match is present (e.g. dropped by ConflictPhase in favor
     * of an overlapping source), still trim a recognised trailing extension so
     * RG detection doesn't include the dot-suffix in the group name.
     */
    private static int trimKnownExtension(ParseContext ctx, Marker filepart) {
        var input = ctx.input;
        int s = filepart.start();
        int e = filepart.end();
        var part = input.substring(s, e);
        var m = KNOWN_TRAILING_EXT.matcher(part);
        if (m.find()) return s + m.start();
        return e;
    }

    private static int trimNotAReleaseGroupTail(ParseContext ctx, Marker filepart, int rangeEnd) {
        var input = ctx.input;
        boolean changed = true;
        while (changed) {
            changed = false;

            rangeEnd = trimTrailingSeparators(input, filepart, rangeEnd);
            if (rangeEnd != trimTrailingSeparators(input, filepart, rangeEnd)) {
                changed = true;
                continue;
            }

            int afterNotRg = trimNotReleaseGroupMatch(ctx, filepart, rangeEnd);
            if (afterNotRg < rangeEnd) {
                rangeEnd = afterNotRg;
                changed = true;
                continue;
            }

            int afterWebsite = trimTrailingWebsite(ctx, filepart, rangeEnd);
            if (afterWebsite < rangeEnd) {
                rangeEnd = afterWebsite;
                changed = true;
                continue;
            }

            int afterDate = trimTrailingDate(ctx, filepart, rangeEnd);
            if (afterDate < rangeEnd) {
                rangeEnd = afterDate;
                changed = true;
                continue;
            }

            int afterLanguageTail = trimTrailingLanguageTail(ctx, filepart, input, rangeEnd);
            if (afterLanguageTail < rangeEnd) {
                rangeEnd = afterLanguageTail;
                changed = true;
            }
        }
        return rangeEnd;
    }

    private static int trimTrailingSeparators(String input, Marker filepart, int rangeEnd) {
        while (rangeEnd > filepart.start() && isGroupSep(input.charAt(rangeEnd - 1))) {
            rangeEnd--;
        }
        return rangeEnd;
    }

    private static int trimNotReleaseGroupMatch(ParseContext ctx, Marker filepart, int rangeEnd) {
        var notRg = ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains(NOT_A_RG_TAG))
                .filter(m -> m.start() >= filepart.start() && m.end() <= filepart.end())
                .filter(m -> m.end() == rangeEnd)
                .findFirst().orElse(null);

        return notRg != null ? notRg.start() : rangeEnd;
    }

    private static int trimTrailingLanguageTail(ParseContext ctx, Marker filepart, String input, int rangeEnd) {
        var trailingTail = findTrailingLanguageOrAudioMatch(ctx, filepart, rangeEnd);
        if (trailingTail == null) {
            return rangeEnd;
        }

        int tailStart = trailingTail.start();
        int beforeTail = calculatePositionBeforeTail(input, filepart, tailStart);

        var sceneBefore = findSceneMatchBeforeTail(ctx, filepart, beforeTail);
        int gapLen = calculateGapLength(sceneBefore, tailStart);

        return gapLen >= 2 ? tailStart : rangeEnd;
    }

    private static Match findTrailingLanguageOrAudioMatch(ParseContext ctx, Marker filepart, int rangeEnd) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.start() >= filepart.start() && m.end() <= filepart.end())
                .filter(m -> m.end() == rangeEnd)
                .filter(ReleaseGroupExtractor::isLanguageOrAudioMatch)
                .findFirst().orElse(null);
    }

    private static boolean isLanguageOrAudioMatch(Match m) {
        if (m.name() == MatchName.SUBTITLE_LANGUAGE) return true;
        if (m.name() == MatchName.LANGUAGE) return true;
        if (m.name() == MatchName.OTHER) {
            Object v = m.value();
            if (v == null) return false;
            String vs = v.toString();
            return vs.contains("Audio") || "Dual Audio".equals(vs);
        }
        return false;
    }

    private static int calculatePositionBeforeTail(String input, Marker filepart, int tailStart) {
        int tailDepth = 1;
        while (tailStart - tailDepth > filepart.start()
                && isGroupSep(input.charAt(tailStart - tailDepth))) {
            tailDepth++;
        }
        return tailStart - tailDepth + 1;
    }

    private static Match findSceneMatchBeforeTail(ParseContext ctx, Marker filepart, int beforeTail) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> SCENE_PREV.contains(m.name()))
                .filter(m -> m.start() >= filepart.start() && m.end() <= beforeTail)
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
    }

    private static int calculateGapLength(Match sceneBefore, int tailStart) {
        return sceneBefore == null ? Integer.MAX_VALUE : (tailStart - sceneBefore.end());
    }

    /**
     * Trim a trailing date match (optionally wrapped in brackets/parens) so
     * the dash/scene detection sees the real group span. Mirrors
     * trimTrailingWebsite for `date` instead of `website`.
     */
    private static int trimTrailingDate(ParseContext ctx, Marker filepart, int rangeEnd) {
        var input = ctx.input;
        int probe = rangeEnd;
        if (probe > filepart.start() && (input.charAt(probe - 1) == ']' || input.charAt(probe - 1) == ')')) {
            probe--;
        }
        final int p = probe;
        var date = ctx.matches.named(MatchName.DATE)
                .filter(m -> m.start() >= filepart.start() && m.end() <= filepart.end())
                .filter(m -> m.end() == p)
                .findFirst().orElse(null);
        if (date == null) return rangeEnd;
        int newEnd = date.start();
        if (newEnd > filepart.start() && (input.charAt(newEnd - 1) == '[' || input.charAt(newEnd - 1) == '(')) {
            newEnd--;
        }
        return newEnd;
    }

    /**
     * Walk back over a trailing website match (with any wrapping group marker
     * and abutting separators), so detection sees the real group span instead
     * of treating "[tvu.org.ru]" as the trailing token.
     */
    private static int trimTrailingWebsite(ParseContext ctx, Marker filepart, int rangeEnd) {
        var input = ctx.input;
        int probe = rangeEnd;
        // Skip a trailing close-bracket so we can match websites wrapped in [..] or (..).
        if (probe > filepart.start() && (input.charAt(probe - 1) == ']' || input.charAt(probe - 1) == ')')) {
            probe--;
        }
        final int p = probe;
        var website = ctx.matches.named(MatchName.WEBSITE)
                .filter(m -> m.start() >= filepart.start() && m.end() <= filepart.end())
                .filter(m -> m.end() == p)
                .findFirst().orElse(null);
        if (website == null) return rangeEnd;
        int newEnd = website.start();
        // Strip an opening bracket immediately preceding the website span.
        if (newEnd > filepart.start() && (input.charAt(newEnd - 1) == '[' || input.charAt(newEnd - 1) == '(')) {
            newEnd--;
        }
        return newEnd;
    }

    /**
     * Drop {@code other="HD"} matches fully contained in {@code [s, e]} so a trailing
     * "...HD" suffix on a scene group ("CtrlHD", "HDClub") doesn't block group detection.
     * "HD" alone has no seps_surround validator (it can fire inside any word), so the
     * pipeline emits it inside group names — Python guessit suppresses it through a
     * group-context post-rule; here we remove it just-in-time before overlap checks.
     */
    private static final Set<String> RG_INTERIOR_OTHER = Set.of(
            "HD", "Ultra HD", "Full HD", "HDR10", "Dolby Vision", "BT.2020",
            "Standard Dynamic Range", "High Resolution");

    private static void dropHdInsideCandidate(ParseContext ctx, int s, int e) {
        var toRemove = ctx.matches.all()
                .filter(m -> m.name() == MatchName.OTHER && RG_INTERIOR_OTHER.contains(String.valueOf(m.value())))
                .filter(m -> m.start() >= s && m.end() <= e)
                .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void removeOverlappingLanguages(ParseContext ctx, int s, int e) {
        var toRemove = ctx.matches.all()
                .filter(m -> m.name() == MatchName.LANGUAGE || m.name() == MatchName.SUBTITLE_LANGUAGE)
                .filter(m -> m.start() < e && s < m.end())
                .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /**
     * Group-aware separator: standard seps minus {@link #IGNORED_SEPS} (brackets/parens).
     */
    private static boolean isGroupSep(char c) {
        return IGNORED_SEPS.indexOf(c) < 0 && Seps.isSep(c);
    }

    /**
     * Mirrors Python guessit's clean_groupname: strips group-seps, drops forbidden
     * prefix/suffix words (by, rip, …), and converts "name) [tag]" → "name tag".
     */
    static String cleanGroupName(String input) {
        var s = stripGroupSeps(input);
        // Strip a balanced outer bracket/paren pair when the contents have no
        // inner brackets ("[NY2]" → "NY2", "(rartv)" → "rartv"). Mixed shapes
        // like "name) [tag]" are preserved here and rewritten by PARENS_BRACKETS.
        if (s.length() >= 2
                && ((s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']')
                || (s.charAt(0) == '(' && s.charAt(s.length() - 1) == ')'))
                && doesNotContainIgnored(s.substring(1, s.length() - 1))) {
            s = s.substring(1, s.length() - 1);
        } else if (!(endsWithIgnored(s) && startsWithIgnored(s))
                && doesNotContainIgnored(stripIgnoredSeps(s))) {
            s = stripIgnoredSeps(s);
        } else if (endsWithIgnored(s) && startsWithIgnored(s)) {
            // Edges are unbalanced ignored-sep chars (e.g. "}.Chaps]"): strip
            // both ends iteratively until the result has no surrounding
            // ignored-sep noise, then drop residual group seps.
            var trimmed = stripIgnoredSeps(stripGroupSeps(s));
            if (!trimmed.isEmpty() && doesNotContainIgnored(trimmed)) s = trimmed;
        }
        // Strip any residual group seps that were exposed by bracket removal above.
        s = stripGroupSeps(s);
        // Drop forbidden prefix/suffix names if separated from rest by a sep.
        for (var forbidden : FORBIDDEN_NAMES) {
            if (s.toLowerCase().startsWith(forbidden) && s.length() > forbidden.length()
                    && Seps.isSep(s.charAt(forbidden.length()))) {
                s = stripGroupSeps(s.substring(forbidden.length()));
            }
            if (s.toLowerCase().endsWith(forbidden) && s.length() > forbidden.length()
                    && Seps.isSep(s.charAt(s.length() - forbidden.length() - 1))) {
                s = stripGroupSeps(s.substring(0, s.length() - forbidden.length()));
            }
        }
        // "Individual) [Group]" → "Individual Group".
        var m = PARENS_BRACKETS.matcher(s.trim());
        if (m.matches()) {
            s = m.group(1) + " " + m.group(2);
        }
        return s.trim();
    }

    private static String stripGroupSeps(String s) {
        int i = 0, j = s.length();
        while (i < j && isGroupSep(s.charAt(i))) i++;
        while (j > i && isGroupSep(s.charAt(j - 1))) j--;
        return s.substring(i, j);
    }

    private static String stripIgnoredSeps(String s) {
        int i = 0, j = s.length();
        while (i < j && IGNORED_SEPS.indexOf(s.charAt(i)) >= 0) i++;
        while (j > i && IGNORED_SEPS.indexOf(s.charAt(j - 1)) >= 0) j--;
        return s.substring(i, j);
    }

    private static boolean startsWithIgnored(String s) {
        return !s.isEmpty() && IGNORED_SEPS.indexOf(s.charAt(0)) >= 0;
    }

    private static boolean endsWithIgnored(String s) {
        return !s.isEmpty() && IGNORED_SEPS.indexOf(s.charAt(s.length() - 1)) >= 0;
    }

    private static boolean doesNotContainIgnored(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (IGNORED_SEPS.indexOf(s.charAt(i)) >= 0) return false;
        }
        return true;
    }

    private static boolean validGroupName(String s, boolean allowSpaces) {
        return validGroupName(s, allowSpaces, false);
    }

    /**
     * When {@code atEnd} is true, single-character candidates are accepted
     * (mirrors python's DashSeparatedReleaseGroup.is_valid: the {@code len <= 1}
     * reject only fires for the not-at-end branch — trailing-dash candidates
     * rely on the dot-separated-chain walk for validity).
     */
    private static boolean validGroupName(String s, boolean allowSpaces, boolean atEnd) {
        var t = s.trim();
        if (t.isEmpty()) return false;
        if (!atEnd && t.length() < 2) return false;
        if (!allowSpaces && t.contains(" ")) return false;
        return !t.chars().allMatch(Character::isDigit);
    }

    private static boolean isNotProbableLanguagePrefix(String candidate) {
        var lower = candidate.toLowerCase();
        return !lower.startsWith("sub") && !lower.endsWith("sub");
    }
}
