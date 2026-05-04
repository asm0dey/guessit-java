package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

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
    public static final String LANGUAGE = "language";
    public static final String SUBTITLE_LANGUAGE = "subtitle_language";
    private static final Set<String> SCENE_PREV = Set.of(
        "video_codec", "source", "video_api", "audio_codec", "audio_profile", "video_profile",
        "audio_channels", "screen_size", "other", "container",
            LANGUAGE, SUBTITLE_LANGUAGE, "language.suffix", "year");

    /** Forbidden release-group prefix/suffix names (from config: release_group.forbidden_names). */
    private static final List<String> FORBIDDEN_NAMES = List.of("bonus", "by", "for", "par", "pour", "rip");

    /** Separators that are NOT stripped from group names (config: release_group.ignored_seps). */
    private static final String IGNORED_SEPS = "[]{}()";

    /** Combined parens+brackets pattern: "name) [tag]" → "name tag". Mirrors Python clean_groupname. */
    private static final Pattern PARENS_BRACKETS = Pattern.compile("(.+)\\)\\s?\\[(.+)]");
    public static final String RELEASE_GROUP = "release_group";

    @Override public String name() { return RELEASE_GROUP; }

    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedGroup();
        if (expected.isEmpty()) return;
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        for (var name : expected) {
            int from = 0;
            var hay = input.toLowerCase();
            var n = name.toLowerCase();
            while (true) {
                int idx = hay.indexOf(n, from);
                if (idx < 0) break;
                int end = idx + name.length();
                var m = new Match(RELEASE_GROUP, name, idx, end, input.substring(idx, end),
                    2000, Set.of("expected"), false);
                if (validator.test(m)) ctx.matches.add(m);
                from = idx + 1;
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        if (ctx.matches.named(RELEASE_GROUP).findAny().isPresent()) return;
        if (detectScene(ctx)) { preferOuterFolderCasing(ctx); return; }
        if (detectDashSeparated(ctx)) { preferOuterFolderCasing(ctx); return; }
        if (detectAnimeBrackets(ctx)) preferOuterFolderCasing(ctx);
    }

    /**
     * After a release_group match has been emitted (by any detect* method), check
     * whether an outer filepart (smaller start) also contains the same name with
     * different casing. If so, replace the value with the outer-folder casing.
     * Mirrors Python's ScenicReleaseGroup walking all fileparts and preferring
     * the upper folder when both have the same name.
     */
    private static void preferOuterFolderCasing(ParseContext ctx) {
        var rg = ctx.matches.named(RELEASE_GROUP).findFirst().orElse(null);
        if (rg == null) return;
        String currentVal = rg.value().toString();
        String lowerVal = currentVal.toLowerCase(Locale.ROOT);
        // Walk all fileparts; prefer the one with the smallest start (upper folder)
        // that also contains the same group name case-insensitively.
        String bestCasing = null;
        int bestStart = rg.start(); // default: current match position
        for (var filepart : pathFilepartsRightmostFirst(ctx)) {
            // Only check fileparts that are outer (smaller start) than the current match
            if (filepart.start() >= rg.start()) continue;
            var part = ctx.input.substring(filepart.start(), filepart.end());
            int idx = part.toLowerCase(Locale.ROOT).indexOf(lowerVal);
            if (idx >= 0) {
                // Verify it's a word boundary (surrounded by seps or filepart edges)
                int absStart = filepart.start() + idx;
                int absEnd = absStart + currentVal.length();
                boolean leftOk = absStart == filepart.start() || Seps.isSep(ctx.input.charAt(absStart - 1));
                boolean rightOk = absEnd == filepart.end() || Seps.isSep(ctx.input.charAt(absEnd));
                if (leftOk && rightOk && absStart < bestStart) {
                    bestCasing = ctx.input.substring(absStart, absEnd);
                    bestStart = absStart;
                }
            }
        }
        if (bestCasing != null && !bestCasing.equals(currentVal)) {
            ctx.matches.remove(rg);
            ctx.matches.add(rg.withValue(bestCasing));
        }
    }

    private boolean detectDashSeparated(ParseContext ctx) {
        var input = ctx.input;
        for (var filepart : pathFilepartsRightmostFirst(ctx)) {
            var part = input.substring(filepart.start(), filepart.end());

            // Trailing dash group: "...-Group.ext"
            var ext = ctx.matches.named("container")
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains("extension"))
                .findFirst().orElse(null);
            int end = ext != null ? ext.start() : trimKnownExtension(ctx, filepart);
            end = trimNotAReleaseGroupTail(ctx, filepart, end);
            int dash = input.lastIndexOf('-', end - 1);
            if (dash > filepart.start() && dash < end - 1) {
                int s = dash + 1;
                int e = end;
                while (s < e && isGroupSep(input.charAt(s))) s++;
                while (e > s && isGroupSep(input.charAt(e - 1))) e--;
                if (s < e) {
                    var raw = input.substring(s, e);
                    var candidate = cleanGroupName(raw);
                    if (validGroupName(candidate, false) && !overlapsNonLanguageExceptHd(ctx, s, e)
                        && !overlapsSubtitleLanguage(ctx, s, e)
                        && hasDotSeparatedPredecessors(ctx, filepart.start(), s)) {
                        if (isProbableLanguagePrefix(candidate)) continue;
                        dropHdInsideCandidate(ctx, s, e);
                        removeOverlappingLanguages(ctx, s, e);
                        ctx.matches.add(new Match(RELEASE_GROUP, candidate, s, e,
                            raw, 1500, Set.of("scene"), false));
                        return true;
                    }
                }
            }

            // Leading dash group: "abc-the.title..."
            // Skip when filepart starts with bracket/paren — leading-bracket group
            // belongs to detectAnimeBrackets, not dash-leading.
            int firstDash = part.indexOf('-');
            if (firstDash > 0 && firstDash < part.length() - 1
                    && part.charAt(0) != '[' && part.charAt(0) != '(') {
                var rawCandidate = part.substring(0, firstDash);
                var candidate = cleanGroupName(rawCandidate);
                int absDashEnd = filepart.start() + firstDash;
                int firstMatchAfter = ctx.matches.all()
                    .filter(m -> !m.isPrivate())
                    .filter(m -> m.start() > absDashEnd && m.end() <= filepart.end())
                    .mapToInt(Match::start).min().orElse(filepart.end());
                if (firstMatchAfter < absDashEnd + 1) firstMatchAfter = absDashEnd + 1;
                var restToFirstMatch = ctx.input.substring(absDashEnd + 1, firstMatchAfter);
                // Python guessit's candidate stops at the first sep; we use the
                // dash-prefix shape so reject candidates that already contain a
                // word separator ("Show.Name." → reject for inputs like
                // "Show.Name.-.07.(2016).[Group]..." where the title sits where
                // we'd otherwise call it a release_group).
                if (validGroupName(candidate, false)
                    && !candidate.contains(".") && !candidate.contains(" ")
                    && restToFirstMatch.contains(".")
                    && !restToFirstMatch.contains(" ")
                    && restToFirstMatch.indexOf('-') < 0) {
                    int absStart = filepart.start();
                    if (!overlapsNonLanguage(ctx, absStart, absDashEnd)
                        && !overlapsSubtitleLanguage(ctx, absStart, absDashEnd)
                        && !overlapsLanguage(ctx, absStart, absDashEnd)) {
                        removeOverlappingLanguages(ctx, absStart, absDashEnd);
                        ctx.matches.add(new Match(RELEASE_GROUP, candidate, absStart, absDashEnd,
                            rawCandidate, 1500, Set.of("scene"), false));
                        return true;
                    }
                }
            }
        }
        return false;
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
                    .filter(m -> !m.tags().contains("expected"))
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
        return ctx.matches.named(SUBTITLE_LANGUAGE)
            .anyMatch(m -> m.start() < e && s < m.end());
    }

    private static boolean overlapsLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.named(LANGUAGE)
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
        var sources = ctx.matches.named("source")
            .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
            .sorted(java.util.Comparator.comparingInt(m -> -m.end()))
            .toList();
        if (sources.size() < 2) return;
        var trailing = sources.getFirst();
        // Trailing source must abut rangeEnd via separator-only suffix.
        var tail = input.substring(trailing.end(), rangeEnd);
        if (!tail.isEmpty() && tail.chars().anyMatch(c -> Character.isLetterOrDigit((char) c))) return;
        // Must be preceded by a separator (i.e. dash form like "...x264-SDTV").
        if (trailing.start() == 0) return;
        char prevCh = input.charAt(trailing.start() - 1);
        if (Character.isLetterOrDigit(prevCh)) return;
        ctx.matches.remove(trailing);
    }

    private boolean detectScene(ParseContext ctx) {
        var input = ctx.input;
        for (var filepart : pathFilepartsRightmostFirst(ctx)) {
            var ext = ctx.matches.named("container")
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains("extension"))
                .findFirst().orElse(null);
            final int rangeEnd = trimNotAReleaseGroupTail(ctx, filepart, ext != null ? ext.start() : trimKnownExtension(ctx, filepart));

            // Demote-other: if the rightmost source/other in the filepart has
            // no separator gap to the trim end (or is the rightmost-end match)
            // and at least one earlier sibling exists, treat it as the release
            // group candidate. Mirrors python's demote_other conflict_solver.
            promoteTrailingSourceToReleaseGroup(ctx, filepart, rangeEnd);

            var prev = ctx.matches.all()
                .filter(m -> SCENE_PREV.contains(m.name()))
                .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
            if (prev == null) continue;
            // Lone-language scene_prev: when prev is the ONLY scene_prev in the
            // filepart and it's a language/country/year, skip — python's
            // SceneReleaseGroup runs after TitleFromPosition and the title
            // would already have absorbed both the language and the trailing
            // word. Without similar phase ordering, a bare "Title_Lang_Word"
            // (e.g. The_Italian_Job.mkv) wrongly emits release_group=Word.
            if (LANGUAGE.equals(prev.name()) || SUBTITLE_LANGUAGE.equals(prev.name())
                    || "year".equals(prev.name())) {
                final var prevFinal = prev;
                long siblingScenePrev = ctx.matches.all()
                    .filter(m -> SCENE_PREV.contains(m.name()) && m != prevFinal)
                    .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
                    .filter(m -> !LANGUAGE.equals(m.name()) && !SUBTITLE_LANGUAGE.equals(m.name())
                            && !"year".equals(m.name()))
                    .count();
                if (siblingScenePrev == 0) continue;
            }

            var gap = input.substring(prev.end(), rangeEnd);
            int leadSeps = 0;
            while (leadSeps < gap.length() && isGroupSep(gap.charAt(leadSeps))) leadSeps++;
            int trailSeps = 0;
            while (trailSeps < gap.length() - leadSeps && isGroupSep(gap.charAt(gap.length() - 1 - trailSeps))) trailSeps++;
            int s = prev.end() + leadSeps;
            int e = rangeEnd - trailSeps;
            if (e <= s) continue;
            // When the gap starts with a stray closing bracket, the scene_prev
            // sat inside a preceding "[...]" decoration. Skip past the bracket
            // and any seps; if what follows is just a plain trailing token
            // (e.g. "[Dublado].RK"), use that as the candidate. If the next
            // non-sep is another '[', fall through to detectAnimeBrackets.
            while (s < e && (input.charAt(s) == ']' || isGroupSep(input.charAt(s)))) s++;
            if (s >= e) continue;
            if (input.charAt(s) == '[') continue;
            var raw = input.substring(s, e);
            var candidate = cleanGroupName(raw);
            if (!validGroupName(candidate, true)) continue;
            if (isProbableLanguagePrefix(candidate)) continue;
            if (overlapsNonLanguageExceptHd(ctx, s, e)) continue;
            if (overlapsSubtitleLanguage(ctx, s, e)) continue;
            dropHdInsideCandidate(ctx, s, e);
            removeOverlappingLanguages(ctx, s, e);
            ctx.matches.add(new Match(RELEASE_GROUP, candidate, s, e, raw, 1500, Set.of("scene"), false));
            return true;
        }
        return false;
    }

    private boolean detectAnimeBrackets(ParseContext ctx) {
        // Collect group markers; iterate rightmost-first so trailing brackets
        // (which typically hold the actual group when multiple "[...]" decorations
        // are present) win over a leading-bracket fansub when both are bare.
        var groups = new java.util.ArrayList<io.guessit.engine.Marker>();
        for (var marker : ctx.markers) if ("group".equals(marker.name())) groups.add(marker);
        // Try trailing-first; if no trailing bracket has empty content, fall back
        // to leading-bracket (covers `[Fansub] One Piece 603` shape).
        for (int dir = 0; dir < 2; dir++) {
            for (int i = groups.size() - 1; i >= 0; i--) {
                var marker = groups.get(i);
                if (dir == 0 && i == 0 && groups.size() > 1) continue; // skip leading on first pass
                if (tryEmitAnimeBracket(ctx, marker)) return true;
            }
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
        ctx.matches.add(new Match(RELEASE_GROUP, trimmed, fInnerS, fInnerE,
            innerStr, 1500, Set.of("anime"), false));
        return true;
    }

    private static boolean overlapsNonLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> !m.name().equals(LANGUAGE) && !m.name().equals(SUBTITLE_LANGUAGE))
            .anyMatch(m -> m.start() < e && s < m.end());
    }

    /** Like {@link #overlapsNonLanguage} but ignores {@code other} matches whose
     *  values are video-format flags ({@link #RG_INTERIOR_OTHER}) — those get
     *  dropped by {@link #dropHdInsideCandidate} once the group commits. */
    private static boolean overlapsNonLanguageExceptHd(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> !m.name().equals(LANGUAGE) && !m.name().equals(SUBTITLE_LANGUAGE))
            .filter(m -> !("other".equals(m.name()) && RG_INTERIOR_OTHER.contains(m.value())))
            .anyMatch(m -> m.start() < e && s < m.end());
    }

    /** Path markers in marker_sorted order (rightmost first when counts tie),
     *  so filename wins over parent dir for release-group detection. */
    private static List<Marker> pathFilepartsRightmostFirst(ParseContext ctx) {
        var paths = new java.util.ArrayList<Marker>();
        for (var m : ctx.markers) if ("path".equals(m.name())) paths.add(m);
        java.util.Collections.reverse(paths);
        return paths;
    }

    /**
     * Walks back over trailing {@code other} matches tagged
     * {@code not-a-release-group} (Sample, Proof, Obfuscated, Repost suffixes)
     * so they don't block release-group detection. Mirrors Python guessit's
     * predicate filter in {@code release_group.detect}.
     */
    private static final java.util.regex.Pattern KNOWN_TRAILING_EXT = java.util.regex.Pattern.compile(
        "(?i)\\.(?:mkv|mp4|avi|mov|m4v|mpeg|mpg|ts|m2ts|wmv|webm|flv|ogg|ogm|ogv|"
        + "iso|3gp|3g2|3gp2|asf|divx|mka|mk2|mk3d|mp4a|qt|ra|ram|rm|vob|wav|wma|"
        + "srt|idx|sub|ssa|ass|nfo|torrent|nzb)$");

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
            while (rangeEnd > filepart.start() && isGroupSep(input.charAt(rangeEnd - 1))) {
                rangeEnd--;
                changed = true;
            }
            final int fe = rangeEnd;
            var notRg = ctx.matches.named(OtherExtractor.OTHER)
                .filter(m -> m.tags().contains("not-a-release-group"))
                .filter(m -> m.start() >= filepart.start() && m.end() <= filepart.end())
                .filter(m -> m.end() == fe)
                .findFirst().orElse(null);
            if (notRg != null) {
                rangeEnd = notRg.start();
                changed = true;
                continue;
            }
            // Trailing website matches ("tvu.org.ru", "sharethefiles.com") aren't
            // part of the release group either — skip them (and any wrapping group
            // marker) so the actual group span sits at the new range end.
            int strip = trimTrailingWebsite(ctx, filepart, rangeEnd);
            if (strip < rangeEnd) {
                rangeEnd = strip;
                changed = true;
            }
            // Trailing subtitle_language / language / "Dual Audio"-style
            // other match (e.g. "...xvid-2hd.eng.srt", "...x264-Belex.-.Dual.Audio.+.Legendas"):
            // python excludes these from RG candidate territory. Pull rangeEnd
            // back so prev/candidate computation sees the real group span
            // before the language/audio tail. Skip when the language match
            // sits flush against the prior scene-prev (no real RG token
            // between them) — in that shape the language IS the RG ("x264-CS",
            // "H.264-NL").
            var trailingTail = ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.start() >= filepart.start() && m.end() <= filepart.end())
                .filter(m -> m.end() == fe)
                .filter(m -> {
                    String n = m.name();
                    if (LanguageExtractor.SUBTITLE_LANGUAGE.equals(n)) return true;
                    if (LanguageExtractor.LANGUAGE.equals(n)) return true;
                    if (OtherExtractor.OTHER.equals(n)) {
                        Object v = m.value();
                        if (v == null) return false;
                        String vs = v.toString();
                        return vs.contains("Audio") || "Dual Audio".equals(vs);
                    }
                    return false;
                })
                .findFirst().orElse(null);
            if (trailingTail != null) {
                final int tailStart = trailingTail.start();
                int tailDepth = 1;
                while (tailStart - tailDepth > filepart.start()
                    && isGroupSep(input.charAt(tailStart - tailDepth))) tailDepth++;
                final int beforeTailFinal = tailStart - tailDepth + 1;
                var sceneBefore = ctx.matches.all()
                    .filter(m -> !m.isPrivate())
                    .filter(m -> SCENE_PREV.contains(m.name()))
                    .filter(m -> m.start() >= filepart.start() && m.end() <= beforeTailFinal)
                    .reduce((a, b) -> a.end() >= b.end() ? a : b)
                    .orElse(null);
                int gapLen = sceneBefore == null ? Integer.MAX_VALUE : (tailStart - sceneBefore.end());
                if (gapLen >= 2) {
                    rangeEnd = tailStart;
                    changed = true;
                }
            }
        }
        return rangeEnd;
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
        var website = ctx.matches.named("website")
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
        "Standard Dynamic Range");

    private static void dropHdInsideCandidate(ParseContext ctx, int s, int e) {
        var toRemove = ctx.matches.all()
            .filter(m -> "other".equals(m.name()) && RG_INTERIOR_OTHER.contains(m.value()))
            .filter(m -> m.start() >= s && m.end() <= e)
            .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void removeOverlappingLanguages(ParseContext ctx, int s, int e) {
        var toRemove = ctx.matches.all()
            .filter(m -> m.name().equals(LANGUAGE) || m.name().equals(SUBTITLE_LANGUAGE))
            .filter(m -> m.start() < e && s < m.end())
            .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /** Group-aware separator: standard seps minus {@link #IGNORED_SEPS} (brackets/parens). */
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
        var t = s.trim();
        if (t.length() < 2) return false;
        if (!allowSpaces && t.contains(" ")) return false;
        return !t.chars().allMatch(Character::isDigit);
    }

    private static boolean isProbableLanguagePrefix(String candidate) {
        var lower = candidate.toLowerCase();
        return lower.startsWith("sub") || lower.endsWith("sub");
    }
}
