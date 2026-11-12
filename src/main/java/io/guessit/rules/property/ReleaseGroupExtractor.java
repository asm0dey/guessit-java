package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftPatterns;
import io.guessit.engine.*;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.mirkoddd.sift.core.Sift.fromAnywhere;
import static com.mirkoddd.sift.core.Sift.oneOrMore;
import static com.mirkoddd.sift.core.SiftPatterns.*;
import static io.guessit.engine.MatchName.*;

public final class ReleaseGroupExtractor implements Extractor {
    private static final String EXPECTED_TAG = "expected";
    private static final String EXTENSION_TAG = "extension";
    private static final String SCENE_TAG = "scene";
    private static final String NOT_A_RG_TAG = "not-a-release-group";

    public static final MatchName LANGUAGE = MatchName.LANGUAGE;
    public static final MatchName SUBTITLE_LANGUAGE = MatchName.SUBTITLE_LANGUAGE;
    public static final MatchName OTHER = MatchName.OTHER;
    public static final MatchName CONTAINER = MatchName.CONTAINER;

    private static final int PRIORITY_EXPECTED = 2000;
    private static final int PRIORITY_SCENE = 1500;

    private static final Set<MatchName> SCENE_PREV = Set.of(
            VIDEO_CODEC, SOURCE, VIDEO_API, AUDIO_CODEC, AUDIO_PROFILE, MatchName.VIDEO_PROFILE,
            MatchName.AUDIO_CHANNELS, MatchName.SCREEN_SIZE, OTHER, CONTAINER,
            LANGUAGE, SUBTITLE_LANGUAGE, LANGUAGE_SUFFIX, MatchName.YEAR);

    private static final List<String> FORBIDDEN_NAMES = List.of("bonus", "by", "for", "par", "pour", "rip");
    private static final String IGNORED_SEPS = "[]{}()";

    private static final String TAG_MAIN = "main";
    private static final String TAG_SUB = "sub";

    private static final Pattern PARENS_BRACKETS = buildParensBracketsPattern();
    public static final String PATH_TAG = "path";
    public static final String SUB_TAG = "sub";

    private static Pattern buildParensBracketsPattern() {
        var mainCapture = capture(TAG_MAIN, oneOrMore().anyCharacter());
        var subCapture = capture(TAG_SUB, oneOrMore().anyCharacter());

        var pattern = fromAnywhere()
                .namedCapture(mainCapture)
                .then().character(')')
                .then().optional().whitespace()
                .then().character('[')
                .then().namedCapture(subCapture)
                .then().character(']');

        return Pattern.compile(pattern.shake());
    }
    private static final ConcurrentMap<String, Pattern> EXPECTED_RE_CACHE = new ConcurrentHashMap<>();

    private static final Set<String> RG_INTERIOR_OTHER = Set.of(
            "HD", "Ultra HD", "Full HD", "HDR10", "Dolby Vision", "BT.2020",
            "Standard Dynamic Range", "High Resolution");

    private static final List<String> TRAILING_EXTENSIONS = List.of(
            "mkv", "mp4", "avi", "mov", "m4v", "mpeg", "mpg", "ts", "m2ts",
            "wmv", "webm", "flv", "ogg", "ogm", "ogv", "iso", "3gp", "3g2",
            "3gp2", "asf", "divx", "mka", "mk2", "mk3d", "mp4a", "qt", "ra",
            "ram", "rm", "vob", "wav", "wma", "srt", "idx", SUB_TAG, "ssa",
            "ass", "nfo", "torrent", "nzb"
    );

    private static final Pattern KNOWN_TRAILING_EXT = buildKnownTrailingExtPattern();

    private static Pattern buildKnownTrailingExtPattern() {
        var extFragments = TRAILING_EXTENSIONS.stream()
                .map(SiftPatterns::literal)
                .toList();

        var pattern = fromAnywhere()
                .character('.')
                .followedBy(anyOf(extFragments))
                .andNothingElse();

        return Pattern.compile(pattern.shake(), Pattern.CASE_INSENSITIVE);
    }

    private record FilePartEnv(ParseContext ctx, Marker filePart, String input) {}

    @Override
    public String name() {
        return "release_group";
    }

    @Override
    public String description() {
        return "release group (trailing dash token, bracketed group)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedGroup();
        if (expected.isEmpty()) return;

        var input = ctx.input;
        var validator = Validators.sepsSurround(input);

        for (var name : expected) {
            if (processIfReRegex(ctx, name, input, validator)) continue;
            processLiteralExpected(ctx, name, input, validator);
        }
    }

    private void processLiteralExpected(ParseContext ctx, String name, String input, Predicate<Match> validator) {
        var hay = input.toLowerCase();
        var n = name.toLowerCase();
        int from = 0;
        int idx;

        while ((idx = hay.indexOf(n, from)) >= 0) {
            int end = idx + name.length();
            var m = new Match(MatchName.RELEASE_GROUP, name, idx, end, input.substring(idx, end),
                    PRIORITY_EXPECTED, Set.of(EXPECTED_TAG), false);

            if (validator.test(m)) ctx.matches.add(m);
            from = idx + 1;
        }
    }

    private static boolean processIfReRegex(ParseContext ctx, String name, String input, Predicate<Match> validator) {
        if (!name.startsWith("re:")) return false;

        var rxSrc = name.substring(3);
        var pat = EXPECTED_RE_CACHE.computeIfAbsent(rxSrc, s -> {
            try { return Pattern.compile(s, Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException _) { return null; }
        });

        if (pat == null) return true;

        pat.matcher(input).results()
                .filter(res -> res.end() > res.start())
                .map(res -> {
                    var raw = input.substring(res.start(), res.end());
                    return new Match(MatchName.RELEASE_GROUP, raw, res.start(), res.end(), raw, PRIORITY_EXPECTED, Set.of(EXPECTED_TAG), false);
                })
                .filter(validator)
                .forEach(ctx.matches::add);

        return true;
    }

    @Override
    public void postProcess(ParseContext ctx) {
        if (ctx.matches.named(MatchName.RELEASE_GROUP).findAny().isPresent()) return;
        if (detectScene(ctx)) return;
        if (detectDashSeparated(ctx)) return;
        detectAnimeBrackets(ctx);
    }

    private boolean detectDashSeparated(ParseContext ctx) {
        return pathFilePartsLeftmostFirst(ctx).stream()
                .map(fp -> new FilePartEnv(ctx, fp, ctx.input))
                .anyMatch(env -> tryDetectTrailingDashGroup(env) || tryDetectLeadingDashGroup(env));
    }

    private boolean tryDetectTrailingDashGroup(FilePartEnv env) {
        int end = calculateEndBeforeTrim(env);
        int endBeforeTrim = end;
        end = trimNotAReleaseGroupTail(env, end);

        int dash = env.input().lastIndexOf('-', end - 1);

        if (shouldSkipForTitleSlot(env.ctx(), env.filePart(), end, endBeforeTrim, dash)) return false;
        if (!isValidDashPosition(dash, env.filePart().start(), end)) return false;

        var candidateSpan = extractCandidateSpan(env.input(), dash + 1, end);
        if (candidateSpan == null) return false;

        var raw = env.input().substring(candidateSpan.start, candidateSpan.end);
        var candidate = cleanGroupName(raw);

        if (!isValidTrailingCandidate(env.ctx(), env.filePart(), candidate, candidateSpan.start, candidateSpan.end)) return false;

        addReleaseGroupMatch(env.ctx(), candidate, raw, candidateSpan.start, candidateSpan.end);
        return true;
    }

    private boolean tryDetectLeadingDashGroup(FilePartEnv env) {
        var part = env.input().substring(env.filePart().start(), env.filePart().end());
        int firstDash = part.indexOf('-');

        if (!isValidLeadingDashPosition(part, firstDash)) return false;

        var rawCandidate = part.substring(0, firstDash);
        var candidate = cleanGroupName(rawCandidate);
        int absDashEnd = env.filePart().start() + firstDash;

        int firstMatchAfter = findFirstMatchAfter(env.ctx(), env.filePart(), absDashEnd);
        var restToFirstMatch = env.ctx().input.substring(absDashEnd + 1, firstMatchAfter);

        if (!isValidLeadingCandidate(candidate, restToFirstMatch)) return false;

        int absStart = env.filePart().start();
        if (overlapsAnyLanguage(env.ctx(), absStart, absDashEnd)) return false;

        removeOverlappingLanguages(env.ctx(), absStart, absDashEnd);
        env.ctx().matches.add(new Match(MatchName.RELEASE_GROUP, candidate, absStart, absDashEnd,
                rawCandidate, PRIORITY_SCENE, Set.of(SCENE_TAG), false));
        return true;
    }

    private int calculateEndBeforeTrim(FilePartEnv env) {
        return env.ctx().matches.named(MatchName.CONTAINER)
                .filter(m -> env.filePart().covers(m.start(), m.end()) && m.tags().contains(EXTENSION_TAG))
                .findFirst()
                .map(Match::start)
                .orElseGet(() -> trimKnownExtension(env.ctx(), env.filePart()));
    }

    private boolean shouldSkipForTitleSlot(ParseContext ctx, Marker filePart, int end, int endBeforeTrim, int dash) {
        return end < endBeforeTrim && dash > filePart.start() && filePartIsTitleOnly(ctx, filePart, dash);
    }

    private boolean isValidDashPosition(int dash, int start, int end) {
        return dash > start && dash < end - 1;
    }

    private record CandidateSpan(int start, int end) {}

    private CandidateSpan extractCandidateSpan(String input, int start, int end) {
        return trimGroupSepsToSpan(input, start, end);
    }

    private CandidateSpan trimGroupSepsToSpan(String input, int s, int e) {
        while (s < e && isGroupSep(input.charAt(s))) s++;
        while (e > s && isGroupSep(input.charAt(e - 1))) e--;
        return s < e ? new CandidateSpan(s, e) : null;
    }

    private boolean isValidTrailingCandidate(ParseContext ctx, Marker filePart, String candidate, int s, int e) {
        if (!validGroupName(candidate, false, true)) return false;
        if (overlapsNonLanguageExceptHd(ctx, s, e)) return false;
        if (overlapsSubtitleLanguage(ctx, s, e)) return false;
        if (!hasDotSeparatedPredecessors(ctx, filePart.start(), s)) return false;
        return isNotProbableLanguagePrefix(candidate);
    }

    private void addReleaseGroupMatch(ParseContext ctx, String candidate, String raw, int s, int e) {
        dropHdInsideCandidate(ctx, s, e);
        removeOverlappingLanguages(ctx, s, e);
        ctx.matches.add(new Match(MatchName.RELEASE_GROUP, candidate, s, e, raw, PRIORITY_SCENE, Set.of(SCENE_TAG), false));
    }

    private boolean isValidLeadingDashPosition(String part, int firstDash) {
        return firstDash > 0 && firstDash < part.length() - 1 && part.charAt(0) != '[' && part.charAt(0) != '(';
    }

    private int findFirstMatchAfter(ParseContext ctx, Marker filePart, int absDashEnd) {
        int firstMatchAfter = ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.start() > absDashEnd && m.end() <= filePart.end())
                .mapToInt(Match::start).min().orElse(filePart.end());
        return Math.max(firstMatchAfter, absDashEnd + 1);
    }

    private boolean isValidLeadingCandidate(String candidate, String restToFirstMatch) {
        return validGroupName(candidate, false)
                && !candidate.contains(".") && !candidate.contains(" ")
                && restToFirstMatch.contains(".") && !restToFirstMatch.contains(" ")
                && restToFirstMatch.indexOf('-') < 0;
    }

    private boolean overlapsAnyLanguage(ParseContext ctx, int start, int end) {
        return overlapsNonLanguage(ctx, start, end) || overlapsSubtitleLanguage(ctx, start, end) || overlapsLanguage(ctx, start, end);
    }

    private static boolean hasDotSeparatedPredecessors(ParseContext ctx, int filePartStart, int candidateStart) {
        int boundary = candidateStart;
        int count = 0;
        while (true) {
            int curBoundary = boundary;
            var prev = ctx.matches.all()
                    .filter(m -> !m.isPrivate() && !m.tags().contains(EXPECTED_TAG))
                    .filter(m -> m.start() >= filePartStart && m.end() <= curBoundary)
                    .reduce((a, b) -> a.end() >= b.end() ? a : b)
                    .orElse(null);

            if (prev == null) return false;

            String sep = ctx.input.substring(prev.end(), boundary);
            if (count == 0) {
                if (!"-".equals(sep)) return false;
                count++;
                boundary = prev.start();
                continue;
            }
            return ".".equals(sep);
        }
    }

    private static boolean overlapsSubtitleLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.named(MatchName.SUBTITLE_LANGUAGE).anyMatch(m -> m.start() < e && s < m.end());
    }

    private static boolean overlapsLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.named(MatchName.LANGUAGE).anyMatch(m -> m.start() < e && s < m.end());
    }

    private static void promoteTrailingSourceToReleaseGroup(ParseContext ctx, Marker filePart, int rangeEnd) {
        var sources = ctx.matches.named(SOURCE)
                .filter(m -> m.start() >= filePart.start() && m.end() <= rangeEnd)
                .sorted(Comparator.comparingInt(m -> -m.end()))
                .toList();

        if (sources.size() < 2) return;
        var trailing = sources.getFirst();

        var tail = ctx.input.substring(trailing.end(), rangeEnd);
        if (!tail.isEmpty() && tail.chars().anyMatch(c -> Character.isLetterOrDigit((char) c))) return;
        if (trailing.start() == 0 || ctx.input.charAt(trailing.start() - 1) != '-') return;

        ctx.matches.remove(trailing);
    }

    private boolean detectScene(ParseContext ctx) {
        return pathFilePartsRightmostFirst(ctx).stream()
                .map(fp -> tryExtractSceneCandidateFromFilePart(new FilePartEnv(ctx, fp, ctx.input)))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(match -> {
                    ctx.matches.add(match);
                    return true;
                }).orElse(false);
    }

    private Match tryExtractSceneCandidateFromFilePart(FilePartEnv env) {
        int rangeEnd = calculateRangeEnd(env);
        promoteTrailingSourceToReleaseGroup(env.ctx(), env.filePart(), rangeEnd);

        var prev = findRightmostScenePrev(env.ctx(), env.filePart(), rangeEnd);
        if (prev == null || shouldSkipLoneLanguageScenePrev(env.ctx(), env.filePart(), rangeEnd, prev)) {
            return null;
        }

        var candidateSpan = extractCandidateSpanAfterScenePrev(env.ctx(), env.input(), prev, rangeEnd);
        if (candidateSpan == null) {
            return tryPromoteScenePrevToReleaseGroup(env, prev, rangeEnd);
        }

        return buildSceneReleaseGroupMatch(env, prev, candidateSpan);
    }

    private int calculateRangeEnd(FilePartEnv env) {
        var ext = env.ctx().matches.named(MatchName.CONTAINER)
                .filter(m -> env.filePart().covers(m.start(), m.end()) && m.tags().contains(EXTENSION_TAG))
                .findFirst().orElse(null);
        int rangeEnd = ext != null ? ext.start() : trimKnownExtension(env.ctx(), env.filePart());
        return trimNotAReleaseGroupTail(env, rangeEnd);
    }

    private Match findRightmostScenePrev(ParseContext ctx, Marker filePart, int rangeEnd) {
        return ctx.matches.all()
                .filter(m -> SCENE_PREV.contains(m.name()))
                .filter(m -> m.start() >= filePart.start() && m.end() <= rangeEnd)
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
    }

    private boolean shouldSkipLoneLanguageScenePrev(ParseContext ctx, Marker filePart, int rangeEnd, Match prev) {
        if (isNotLanguageOrYearMatch(prev)) return false;
        return countNonLanguageSiblings(ctx, filePart, rangeEnd, prev) == 0;
    }

    private boolean isNotLanguageOrYearMatch(Match match) {
        return match.name() != MatchName.LANGUAGE && match.name() != MatchName.SUBTITLE_LANGUAGE && match.name() != MatchName.YEAR;
    }

    private long countNonLanguageSiblings(ParseContext ctx, Marker filePart, int rangeEnd, Match prev) {
        return ctx.matches.all()
                .filter(m -> SCENE_PREV.contains(m.name()) && m != prev)
                .filter(m -> m.start() >= filePart.start() && m.end() <= rangeEnd)
                .filter(this::isNotLanguageOrYearMatch)
                .count();
    }

    private CandidateSpan extractCandidateSpanAfterScenePrev(ParseContext ctx, String input, Match prev, int rangeEnd) {
        var gap = input.substring(prev.end(), rangeEnd);
        int leadSeps = countLeadingSeparators(gap);
        int trailSeps = countTrailingSeparators(gap, leadSeps);
        int s = prev.end() + leadSeps;
        int e = rangeEnd - trailSeps;

        if (e <= s) return null;
        s = skipStrayClosingBracket(input, s, e);
        if (s >= e) return null;

        var bracketAdjusted = extractBracketWrappedCandidate(input, s, e);
        if (bracketAdjusted == null) return null;

        s = skipSubtitleLanguagePrefix(ctx, input, bracketAdjusted.start, bracketAdjusted.end);
        return new CandidateSpan(s, bracketAdjusted.end);
    }

    private int countLeadingSeparators(String gap) {
        int count = 0;
        while (count < gap.length() && isGroupSep(gap.charAt(count))) count++;
        return count;
    }

    private int countTrailingSeparators(String gap, int leadSeps) {
        int count = 0;
        while (count < gap.length() - leadSeps && isGroupSep(gap.charAt(gap.length() - 1 - count))) count++;
        return count;
    }

    private int skipStrayClosingBracket(String input, int s, int e) {
        while (s < e && (input.charAt(s) == ']' || isGroupSep(input.charAt(s)))) s++;
        return s;
    }

    private CandidateSpan extractBracketWrappedCandidate(String input, int s, int e) {
        if (input.charAt(s) != '[') return new CandidateSpan(s, e);
        int closeIdx = input.indexOf(']', s + 1);
        if (closeIdx <= s || closeIdx < e - 1) return null;
        return trimGroupSepsToSpan(input, s + 1, closeIdx);
    }

    private int skipSubtitleLanguagePrefix(ParseContext ctx, String input, int s, int e) {
        int sFinal = s;
        boolean advanced;
        do {
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
        } while (advanced);
        return sFinal;
    }

    private Match findSubtitleLanguageAtStart(ParseContext ctx, int s, int e) {
        return ctx.matches.named(MatchName.SUBTITLE_LANGUAGE).filter(m -> m.start() == s && m.end() < e).findFirst().orElse(null);
    }

    private Match findSubtitlePrefixMarkerAtStart(ParseContext ctx, int s, int e) {
        return ctx.matches.all().filter(m -> m.isPrivate() && m.name() == SUBTITLE_LANGUAGE_PREFIX).filter(m -> m.start() == s && m.end() < e).findFirst().orElse(null);
    }

    private int skipPastMatchAndSeparators(String input, int start, int end) {
        int pos = start;
        while (pos < end && isGroupSep(input.charAt(pos))) pos++;
        return pos;
    }

    private Match tryPromoteScenePrevToReleaseGroup(FilePartEnv env, Match prev, int rangeEnd) {
        if (!canPromoteScenePrevToReleaseGroup(env.ctx(), env.input(), env.filePart(), prev, rangeEnd)) return null;

        var rawPrev = env.input().substring(prev.start(), prev.end());
        if (!validGroupName(rawPrev, false, true)) return null;

        env.ctx().matches.remove(prev);
        return new Match(MatchName.RELEASE_GROUP, rawPrev, prev.start(), prev.end(), rawPrev, PRIORITY_SCENE, Set.of(SCENE_TAG), false);
    }

    private boolean canPromoteScenePrevToReleaseGroup(ParseContext ctx, String input, Marker filePart, Match prev, int rangeEnd) {
        return prev.end() == rangeEnd && isPromotableLanguageMatch(prev) && prev.start() > filePart.start() && input.charAt(prev.start() - 1) == '-' && hasDotSeparatedPredecessors(ctx, filePart.start(), prev.start());
    }

    private boolean isPromotableLanguageMatch(Match match) {
        return match.name() == MatchName.LANGUAGE || match.name() == MatchName.SUBTITLE_LANGUAGE || match.name() == MatchName.COUNTRY;
    }

    private Match buildSceneReleaseGroupMatch(FilePartEnv env, Match prev, CandidateSpan span) {
        var raw = env.input().substring(span.start, span.end);
        var candidate = cleanGroupName(raw);

        if (!isValidSceneCandidate(env.ctx(), env.filePart(), prev, candidate, span)) return null;

        dropHdInsideCandidate(env.ctx(), span.start, span.end);
        removeOverlappingLanguages(env.ctx(), span.start, span.end);
        return new Match(MatchName.RELEASE_GROUP, candidate, span.start, span.end, raw, PRIORITY_SCENE, Set.of(SCENE_TAG), false);
    }

    private boolean isValidSceneCandidate(ParseContext ctx, Marker filePart, Match prev, String candidate, CandidateSpan span) {
        return validGroupName(candidate, true) && isNotProbableLanguagePrefix(candidate) && !overlapsNonLanguageExceptHd(ctx, span.start, span.end) && !overlapsSubtitleLanguage(ctx, span.start, span.end) && !candidateIsLikelyTitle(ctx, filePart, prev, span.end);
    }

    private void detectAnimeBrackets(ParseContext ctx) {
        ctx.markers.stream()
                .filter(m -> "group".equals(m.name()))
                .map(m -> tryCreateAnimeBracketMatch(ctx, m))
                .flatMap(Optional::stream)
                .findFirst()
                .ifPresent(ctx.matches::add);
    }

    private Optional<Match> tryCreateAnimeBracketMatch(ParseContext ctx, Marker marker) {
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

        if (trimmed.isEmpty() || trimmed.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }

        boolean hasAnyInside = ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .anyMatch(m -> m.start() >= fInnerS && m.end() <= fInnerE);

        if (hasAnyInside) {
            return Optional.empty();
        }

        return Optional.of(new Match(MatchName.RELEASE_GROUP, trimmed, fInnerS, fInnerE,
                innerStr, PRIORITY_SCENE, Set.of("anime"), false));
    }

    private static boolean candidateIsLikelyTitle(ParseContext ctx, Marker filePart, Match prev, int candidateEnd) {
        var notRgAfter = ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains(NOT_A_RG_TAG))
                .filter(m -> m.start() >= candidateEnd && m.end() <= filePart.end())
                .findFirst().orElse(null);
        return notRgAfter != null && noLeadingTitleHole(ctx, filePart, prev.start());
    }

    private static boolean filePartIsTitleOnly(ParseContext ctx, Marker filePart, int rightBoundary) {
        var notRgAfter = ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains(NOT_A_RG_TAG))
                .filter(m -> m.start() >= rightBoundary && m.end() <= filePart.end())
                .findFirst().orElse(null);
        return notRgAfter != null && noLeadingTitleHole(ctx, filePart, rightBoundary);
    }

    private static boolean noLeadingTitleHole(ParseContext ctx, Marker filePart, int rightBoundary) {
        int hs = filePart.start();
        if (rightBoundary <= hs) return true;

        var prevMatches = ctx.matches.all()
                .filter(m -> m.end() <= rightBoundary && m.start() >= filePart.start())
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        int cursor = hs;
        for (var m : prevMatches) {
            if (m.start() > cursor) {
                var gap = ctx.input.substring(cursor, m.start());
                if (gap.chars().anyMatch(c -> !isGroupSep((char) c))) return false;
            }
            if (m.end() > cursor) cursor = m.end();
        }

        if (cursor < rightBoundary) {
            var gap = ctx.input.substring(cursor, rightBoundary);
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

    private static boolean overlapsNonLanguageExceptHd(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.name() != MatchName.LANGUAGE && m.name() != MatchName.SUBTITLE_LANGUAGE)
                .filter(m -> !(m.name() == MatchName.OTHER && RG_INTERIOR_OTHER.contains(m.value().toString())))
                .anyMatch(m -> m.start() < e && s < m.end());
    }

    private static List<Marker> pathFilePartsRightmostFirst(ParseContext ctx) {
        var paths = ctx.markers.stream().filter(m -> m.name().equals(PATH_TAG)).toList();
        return markerSortedWithEpisodeTitleHint(paths, ctx);
    }

    private static final Set<MatchName> EP_TITLE_NEXT_NAMES = Set.of(
            MatchName.SCREEN_SIZE, SOURCE, VIDEO_CODEC, AUDIO_CODEC, MatchName.OTHER,
            MatchName.CONTAINER, MatchName.STREAMING_SERVICE);

    private static Predicate<Match> markerWeightPredicate() {
        return m -> !m.isPrivate()
                && m.name() != MatchName.PROPER_COUNT
                && m.name() != MatchName.TITLE
                && !(m.name() == MatchName.CONTAINER && m.tags().contains(EXTENSION_TAG))
                && !(m.name() == MatchName.OTHER && "Rip".equals(m.value()));
    }

    private static int filePartWeight(Marker fp, ParseContext ctx) {
        var pred = markerWeightPredicate();
        var weight = (int) ctx.matches.range(fp.start(), fp.end(), pred).map(Match::name).distinct().count();
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
        return gap.chars().anyMatch(Character::isLetter);
    }

    private static List<Marker> markerSortedWithEpisodeTitleHint(List<Marker> paths, ParseContext ctx) {
        return paths.stream()
                .sorted((a, b) -> {
                    var byWeight = Integer.compare(filePartWeight(b, ctx), filePartWeight(a, ctx));
                    return byWeight != 0 ? byWeight : Integer.compare(paths.indexOf(b), paths.indexOf(a));
                })
                .toList();
    }

    private static List<Marker> pathFilePartsLeftmostFirst(ParseContext ctx) {
        return ctx.markers.stream().filter(m -> m.name().equals(PATH_TAG)).toList();
    }

    private static int trimKnownExtension(ParseContext ctx, Marker filePart) {
        var part = ctx.input.substring(filePart.start(), filePart.end());
        var m = KNOWN_TRAILING_EXT.matcher(part);
        return m.find() ? filePart.start() + m.start() : filePart.end();
    }

    private static int trimNotAReleaseGroupTail(FilePartEnv env, int rangeEnd) {
        int previousEnd;
        do {
            previousEnd = rangeEnd;
            rangeEnd = applySingleTrimPass(env, rangeEnd);
        } while (rangeEnd < previousEnd);

        return rangeEnd;
    }

    private static int applySingleTrimPass(FilePartEnv env, int rangeEnd) {
        int end = trimTrailingSeparators(env.input(), env.filePart(), rangeEnd);
        if (end < rangeEnd) return end;

        end = trimNotReleaseGroupMatch(env.ctx(), env.filePart(), rangeEnd);
        if (end < rangeEnd) return end;

        end = trimTrailingNamed(env.ctx(), env.filePart(), rangeEnd, MatchName.WEBSITE);
        if (end < rangeEnd) return end;

        end = trimTrailingNamed(env.ctx(), env.filePart(), rangeEnd, MatchName.DATE);
        if (end < rangeEnd) return end;

        return trimTrailingLanguageTail(env.ctx(), env.filePart(), env.input(), rangeEnd);
    }

    private static int trimTrailingSeparators(String input, Marker filePart, int rangeEnd) {
        while (rangeEnd > filePart.start() && isGroupSep(input.charAt(rangeEnd - 1))) rangeEnd--;
        return rangeEnd;
    }

    private static int trimNotReleaseGroupMatch(ParseContext ctx, Marker filePart, int rangeEnd) {
        return ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains(NOT_A_RG_TAG))
                .filter(m -> m.start() >= filePart.start() && m.end() <= filePart.end())
                .filter(m -> m.end() == rangeEnd)
                .findFirst().map(Match::start).orElse(rangeEnd);
    }

    private static int trimTrailingLanguageTail(ParseContext ctx, Marker filePart, String input, int rangeEnd) {
        var trailingTail = findTrailingLanguageOrAudioMatch(ctx, filePart, rangeEnd);
        if (trailingTail == null) return rangeEnd;

        int tailStart = trailingTail.start();
        int beforeTail = calculatePositionBeforeTail(input, filePart, tailStart);
        var sceneBefore = findSceneMatchBeforeTail(ctx, filePart, beforeTail);

        return calculateGapLength(sceneBefore, tailStart) >= 2 ? tailStart : rangeEnd;
    }

    private static Match findTrailingLanguageOrAudioMatch(ParseContext ctx, Marker filePart, int rangeEnd) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate() && m.start() >= filePart.start() && m.end() == rangeEnd)
                .filter(ReleaseGroupExtractor::isLanguageOrAudioMatch)
                .findFirst().orElse(null);
    }

    private static boolean isLanguageOrAudioMatch(Match m) {
        if (m.name() == MatchName.SUBTITLE_LANGUAGE || m.name() == MatchName.LANGUAGE) return true;
        if (m.name() == MatchName.OTHER && m.value() != null) {
            String vs = m.value().toString();
            return vs.contains("Audio") || "Dual Audio".equals(vs);
        }
        return false;
    }

    private static int calculatePositionBeforeTail(String input, Marker filePart, int tailStart) {
        int tailDepth = 1;
        while (tailStart - tailDepth > filePart.start() && isGroupSep(input.charAt(tailStart - tailDepth))) tailDepth++;
        return tailStart - tailDepth + 1;
    }

    private static Match findSceneMatchBeforeTail(ParseContext ctx, Marker filePart, int beforeTail) {
        return ctx.matches.all()
                .filter(m -> !m.isPrivate() && SCENE_PREV.contains(m.name()))
                .filter(m -> m.start() >= filePart.start() && m.end() <= beforeTail)
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
    }

    private static int calculateGapLength(Match sceneBefore, int tailStart) {
        return sceneBefore == null ? Integer.MAX_VALUE : (tailStart - sceneBefore.end());
    }

    private static int trimTrailingNamed(ParseContext ctx, Marker filePart, int rangeEnd, MatchName name) {
        final int p = rangeEnd > filePart.start() && (ctx.input.charAt(rangeEnd - 1) == ']' || ctx.input.charAt(rangeEnd - 1) == ')') ? rangeEnd - 1 : rangeEnd;

        return ctx.matches.named(name)
                .filter(m -> m.start() >= filePart.start() && m.end() == p)
                .findFirst()
                .map(hit -> {
                    int newEnd = hit.start();
                    return newEnd > filePart.start() && (ctx.input.charAt(newEnd - 1) == '[' || ctx.input.charAt(newEnd - 1) == '(') ? newEnd - 1 : newEnd;
                }).orElse(rangeEnd);
    }

    private static void dropHdInsideCandidate(ParseContext ctx, int s, int e) {
        ctx.matches.all()
                .filter(m -> m.name() == MatchName.OTHER && RG_INTERIOR_OTHER.contains(String.valueOf(m.value())))
                .filter(m -> m.start() >= s && m.end() <= e)
                .toList()
                .forEach(ctx.matches::remove);
    }

    private static void removeOverlappingLanguages(ParseContext ctx, int s, int e) {
        ctx.matches.all()
                .filter(m -> m.name() == MatchName.LANGUAGE || m.name() == MatchName.SUBTITLE_LANGUAGE)
                .filter(m -> m.start() < e && s < m.end())
                .toList()
                .forEach(ctx.matches::remove);
    }

    private static boolean isGroupSep(char c) {
        return IGNORED_SEPS.indexOf(c) < 0 && Seps.isSep(c);
    }

    static String cleanGroupName(String input) {
        String s = stripGroupSeps(input);

        s = unwrapGroupSeps(s);
        s = stripGroupSeps(s);
        s = stripForbiddenNames(s);
        s = flattenBracketTags(s);

        return s.trim();
    }

    private static String unwrapGroupSeps(String s) {
        if (isProperlyWrapped(s) && doesNotContainIgnored(s.substring(1, s.length() - 1))) {
            return s.substring(1, s.length() - 1);
        }

        boolean hasEdges = startsWithIgnored(s) && endsWithIgnored(s);

        if (!hasEdges && doesNotContainIgnored(stripIgnoredSeps(s))) {
            return stripIgnoredSeps(s);
        }

        if (hasEdges) {
            var trimmed = stripIgnoredSeps(stripGroupSeps(s));
            if (!trimmed.isEmpty() && doesNotContainIgnored(trimmed)) {
                return trimmed;
            }
        }

        return s;
    }

    private static boolean isProperlyWrapped(String s) {
        if (s.length() < 2) return false;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        return (first == '[' && last == ']') || (first == '(' && last == ')');
    }

    private static String stripForbiddenNames(String s) {
        String result = s;
        for (var forbidden : FORBIDDEN_NAMES) {
            result = stripForbiddenPrefix(result, forbidden);
            result = stripForbiddenSuffix(result, forbidden);
        }
        return result;
    }

    private static String stripForbiddenPrefix(String s, String forbidden) {
        if (s.toLowerCase().startsWith(forbidden)
                && s.length() > forbidden.length()
                && Seps.isSep(s.charAt(forbidden.length()))) {
            return stripGroupSeps(s.substring(forbidden.length()));
        }
        return s;
    }

    private static String stripForbiddenSuffix(String s, String forbidden) {
        if (s.toLowerCase().endsWith(forbidden)
                && s.length() > forbidden.length()
                && Seps.isSep(s.charAt(s.length() - forbidden.length() - 1))) {
            return stripGroupSeps(s.substring(0, s.length() - forbidden.length()));
        }
        return s;
    }

    private static String flattenBracketTags(String s) {
        var m = PARENS_BRACKETS.matcher(s.trim());
        return m.matches() ? m.group(TAG_MAIN) + " " + m.group(TAG_SUB) : s;
    }

    private static String stripGroupSeps(String s) {
        int i = 0;
        int j = s.length();
        while (i < j && isGroupSep(s.charAt(i))) i++;
        while (j > i && isGroupSep(s.charAt(j - 1))) j--;
        return s.substring(i, j);
    }

    private static String stripIgnoredSeps(String s) {
        int i = 0;
        int j = s.length();
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

    private static boolean validGroupName(String s, boolean allowSpaces, boolean atEnd) {
        var t = s.trim();
        if (t.isEmpty() || (!atEnd && t.length() < 2) || (!allowSpaces && t.contains(" "))) return false;
        return !t.chars().allMatch(Character::isDigit);
    }

    private static boolean isNotProbableLanguagePrefix(String candidate) {
        var lower = candidate.toLowerCase();
        return !lower.startsWith(SUB_TAG) && !lower.endsWith(SUB_TAG);
    }
}