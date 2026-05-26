package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftPatterns;
import com.mirkoddd.sift.core.dsl.Connector;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Extracts {@code website} (domain-shaped substrings).
 *
 * <p>Three URL patterns, all built from the IANA TLD list bundled at
 * {@code /io/guessit/data/tlds-alpha-by-domain.txt}:
 * <ul>
 * <li><strong>pattern1</strong> — known safe subdomain ({@code www}) + any TLD.</li>
 * <li><strong>pattern2</strong> — any host + a small whitelist of safe TLDs.</li>
 * <li><strong>pattern3</strong> — known safe prefix (e.g. {@code .co.uk}) + any TLD.</li>
 * </ul>
 */
public final class WebsiteExtractor implements Extractor {

    public static final String WEBSITE = "website";
    @SuppressWarnings("java:S1075") // Classpath embedded resource, not a filesystem URI
    private static final String TLD_PATH = "/io/guessit/data/tlds-alpha-by-domain.txt";
    private static final String GRP_URL = "url";
    private static final String TAG_PREFIX = "website.prefix";

    private final Pattern pattern1;  // safe subdomain + TLD
    private final Pattern pattern2;  // safe TLD
    private final Pattern pattern3;  // safe prefix + TLD
    private final List<String> websitePrefixes;
    private final List<String> safeStarts;

    @SuppressWarnings("unchecked")
    public WebsiteExtractor() {
        var cfg = ConfigHolder.websiteConfig();
        var safeTlds = (List<String>) cfg.getOrDefault("safe_tlds", List.of("com", "net", "org"));
        List<String> safePrefixes = (List<String>) cfg.getOrDefault("safe_prefixes", List.of("co", "com", "net", "org"));
        List<String> safeSubdomains = (List<String>) cfg.getOrDefault("safe_subdomains", List.of("www"));
        this.websitePrefixes = (List<String>) cfg.getOrDefault("prefixes", List.of("from"));

        var ss = new ArrayList<>(safeSubdomains);
        ss.addAll(safePrefixes);
        this.safeStarts = List.copyOf(ss);

        var tlds = loadTlds();

        var tldOr = buildOrPattern(tlds);
        var safeTldOr = buildOrPattern(safeTlds);
        var safePrefixOr = buildOrPattern(safePrefixes);
        var safePrefixPart = fromAnywhere().of(safePrefixOr);

        var alphanumeric = exactly(1).alphanumeric();
        var dash = exactly(1).character('-');
        var domainPart = oneOrMore().of(anyOf(alphanumeric, dash));
        var dot = exactly(1).character('.');
        var domainPartWithTrailingDot = domainPart.followedBy(dot);
        var wwwDot = literal("www.");

        this.pattern1 = compileIsolatedUrl(
                oneOrMore().of(wwwDot)
                        .followedBy(oneOrMore().of(domainPartWithTrailingDot))
                        .followedBy(tldOr)
        );

        this.pattern2 = compileIsolatedUrl(
                zeroOrMore().of(wwwDot)
                        .followedBy(domainPartWithTrailingDot)
                        .followedBy(safeTldOr)
        );

        this.pattern3 = compileIsolatedUrl(
                zeroOrMore().of(wwwDot)
                        .followedBy(domainPartWithTrailingDot)
                        .followedBy(oneOrMore().of(safePrefixPart.followedBy(dot)))
                        .followedBy(tldOr)
        );
    }

    private static Pattern compileIsolatedUrl(Connector<Fragment> urlCore) {
        var alphanumeric = exactly(1).alphanumeric();
        return safeCompile(
                fromAnywhere()
                        .namedCapture(capture(GRP_URL, urlCore))
                        .notPrecededBy(alphanumeric)
                        .notFollowedBy(alphanumeric)
                        .shake()
        );
    }

    @Override
    public String name() { return WEBSITE; }

    @Override
    public String description() { return "source website (.com, .net, …)"; }

    @Override
    public int priority() { return 100; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        var hay = input.toLowerCase(Locale.ROOT);

        // Match website prefixes (e.g., "from")
        for (var prefix : websitePrefixes) {
            var needle = prefix.toLowerCase(Locale.ROOT);
            for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) {
                int end = i + needle.length();
                var m = new Match(MatchName.WEBSITE, prefix, i, end, input.substring(i, end), 0, Set.of(TAG_PREFIX), true);
                if (validator.test(m)) ctx.matches.add(m);
            }
        }

        matchPattern(ctx, input, pattern1);
        matchPattern(ctx, input, pattern2);
        matchPattern(ctx, input, pattern3);
    }

    private static void matchPattern(ParseContext ctx, String input, Pattern p) {
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start(GRP_URL);
            int e = matcher.end(GRP_URL);

            var raw = input.substring(s, e);
            if (!isValidDomainChars(raw)) continue;

            ctx.matches.add(new Match(MatchName.WEBSITE, raw, s, e, raw, 100, Set.of(), false));
        }
    }

    private static boolean isValidDomainChars(String s) {
        return s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '.');
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var sites = ctx.matches.named(MatchName.WEBSITE).filter(m -> !m.isPrivate()).toList();
        var toRemove = sites.stream()
                .filter(w -> shouldRemoveUnsafeWebsite(w, ctx))
                .collect(Collectors.toList());

        var prefixes = ctx.matches.all().filter(m -> m.tags().contains(TAG_PREFIX)).toList();
        toRemove.addAll(prefixes.stream()
                .filter(m -> shouldRemovePrefixMatch(m, ctx, toRemove))
                .toList());

        WeakExtractorCommon.removeMatches(ctx, toRemove.stream());
    }

    private boolean shouldRemoveUnsafeWebsite(Match w, ParseContext ctx) {
        if (isSafeWebsite(w)) return false;
        if (!hasFollowingSeasonEpisodeOrDate(w, ctx)) return false;

        return ctx.markers.stream().noneMatch(mk ->
                WeakExtractorCommon.MARKER_GROUP.equals(mk.name()) && WeakExtractorCommon.isInside(w, mk));
    }

    private boolean isSafeWebsite(Match w) {
        String val = w.value() instanceof String s ? s.toLowerCase(Locale.ROOT) : "";
        return safeStarts.stream().anyMatch(p -> val.startsWith(p.toLowerCase(Locale.ROOT)));
    }

    private boolean hasFollowingSeasonEpisodeOrDate(Match w, ParseContext ctx) {
        return ctx.matches.all().anyMatch(o ->
                (o.name() == MatchName.SEASON || o.name() == MatchName.EPISODE
                        || o.name() == MatchName.YEAR || o.name() == MatchName.DATE)
                        && o.start() >= w.end());
    }

    private boolean shouldRemovePrefixMatch(Match m, ParseContext ctx, List<Match> toRemove) {
        var websiteMatch = ctx.matches.named(MatchName.WEBSITE)
                .filter(w -> w.start() > m.end() && !toRemove.contains(w))
                .findFirst()
                .orElse(null);

        if (websiteMatch == null) return true;

        return ctx.input.substring(m.end(), websiteMatch.start()).chars()
                .anyMatch(c -> !Seps.isSep((char) c));
    }

    private static SiftPattern<Fragment> buildOrPattern(List<String> items) {
        if (items.isEmpty()) return literal("(?!)");
        return anyOf(items.stream().map(SiftPatterns::literal).toList());
    }

    private static Pattern safeCompile(String src) {
        try {
            return Pattern.compile(src, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            throw new IllegalStateException("Bad pattern: " + src, e);
        }
    }

    private static List<String> loadTlds() {
        try (var is = WebsiteExtractor.class.getResourceAsStream(TLD_PATH);
             var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {

            return reader.lines()
                    .skip(1)
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.contains("--"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .toList();

        } catch (Exception e) {
            throw new IllegalStateException("Failed to load TLD list from " + TLD_PATH, e);
        }
    }

    private static class ConfigHolder {
        private static Map<String, Object> websiteConfig() {
            var config = io.guessit.config.ConfigLoader.load(io.guessit.Options.defaults());
            return config.section(WEBSITE);
        }
    }
}