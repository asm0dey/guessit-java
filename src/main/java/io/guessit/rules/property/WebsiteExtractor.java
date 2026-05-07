package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Seps;
import io.guessit.engine.Validators;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts {@code website} (domain-shaped substrings).
 *
 * <p>Three URL patterns, all built from the IANA TLD list bundled at
 * {@code /io/guessit/data/tlds-alpha-by-domain.txt}:
 * <ul>
 *   <li><strong>pattern1</strong> — known safe subdomain ({@code www}) + any TLD.</li>
 *   <li><strong>pattern2</strong> — any host + a small whitelist of safe TLDs.
 *       Restricting the TLD here is what keeps random "{@code title.foo}" tokens
 *       from being read as a URL.</li>
 *   <li><strong>pattern3</strong> — known safe prefix (e.g. {@code .co.uk}) + any TLD.</li>
 * </ul>
 *
 * <p>Priority is 100 (well below default 1000) so an overlapping title or
 * other property always wins. The {@code website.prefix} feature emits a
 * private match for prefix words like "from"; {@link #postProcess} drops
 * those when no website actually follows them.
 */
public final class WebsiteExtractor implements Extractor {

    public static final String WEBSITE = "website";

    @Override
    public String name() {
        return WEBSITE;
    }

    @Override
    public int priority() {
        return 100;
    }

    private static final String TLD_PATH = "/io/guessit/data/tlds-alpha-by-domain.txt";

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
        var ss = new ArrayList<String>();
        ss.addAll(safeSubdomains);
        ss.addAll(safePrefixes);
        this.safeStarts = List.copyOf(ss);

        var tlds = loadTlds();

        var tldOr = buildOrPattern(tlds);
        var safeTldOr = buildOrPattern(safeTlds);
        var safePrefixOr = buildOrPattern(safePrefixes);

        // Pattern 1: safe subdomain + any TLD
        this.pattern1 = safeCompile(
                "(?:[^a-z0-9]|^)((?:www\\.)+(?:[a-z0-9-]+\\.)+" + tldOr + ")(?:[^a-z0-9]|$)");

        // Pattern 2: safe TLD with optional subdomains
        this.pattern2 = safeCompile(
                "(?:[^a-z0-9]|^)((?:www\\.)*[a-z0-9-]+\\.(?:" + safeTldOr + "))(?:[^a-z0-9]|$)");

        // Pattern 3: safe prefix + any TLD
        this.pattern3 = safeCompile(
                "(?:[^a-z0-9]|^)((?:www\\.)*[a-z0-9-]+\\.(?:" + safePrefixOr + "\\.)+(?:" + tldOr + "))(?:[^a-z0-9]|$)");
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);

        // Match website prefixes (e.g., "from") as private website.prefix tags
        for (var prefix : websitePrefixes) {
            var idxFrom = 0;
            var hay = input.toLowerCase(java.util.Locale.ROOT);
            var needle = prefix.toLowerCase(java.util.Locale.ROOT);
            while (true) {
                int i = hay.indexOf(needle, idxFrom);
                if (i < 0) break;
                int end = i + needle.length();
                var m = new Match(MatchName.WEBSITE, prefix, i, end, input.substring(i, end), 0, Set.of("website.prefix"), true);
                if (validator.test(m)) ctx.matches.add(m);
                idxFrom = i + 1;
            }
        }

        // Match website URL patterns
        matchPattern(ctx, input, pattern1);
        matchPattern(ctx, input, pattern2);
        matchPattern(ctx, input, pattern3);
    }

    private static void matchPattern(ParseContext ctx, String input, Pattern p) {
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start(1);
            int e = matcher.end(1);
            if (s < 0) continue;
            var raw = input.substring(s, e);
            // The pattern's outer `[^a-z0-9]|^` and `[^a-z0-9]|$` already
            // enforce non-alphanum boundaries; no extra sepsSurround needed.
            // check inner match has valid chars
            if (!isValidDomainChars(raw)) continue;
            var m = new Match(MatchName.WEBSITE, raw, s, e, raw, 100, Set.of(), false);
            ctx.matches.add(m);
        }
    }

    private static boolean isValidDomainChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '.') return false;
        }
        return true;
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var toRemove = new ArrayList<Match>();

        // PreferTitleOverWebsite (port of python rule): drop website matches
        // that don't start with a known safe subdomain/prefix when followed
        // by season/episode/year (and not enclosed in a group marker).
        // Suppresses false positives like "Title.com" before SxxExx.
        // Skip private website.prefix matches; ValidateWebsitePrefix below
        // handles their lifecycle. Removing them here strips the "from" span
        // before TitleExtractor sees it, leaving "From" as a usable title hole.
        var sites = ctx.matches.named(MatchName.WEBSITE).filter(m -> !m.isPrivate()).toList();
        for (var w : sites) {
            if (shouldRemoveUnsafeWebsite(w, ctx)) {
                toRemove.add(w);
            }
        }

        // ValidateWebsitePrefix: remove website.prefix matches that don't have
        // a website match following them without holes
        for (var m : ctx.matches.all().filter(m -> m.tags().contains("website.prefix")).toList()) {
            if (shouldRemovePrefixMatch(m, ctx, toRemove)) {
                toRemove.add(m);
            }
        }

        for (var m : toRemove) ctx.matches.remove(m);
    }

    private boolean shouldRemoveUnsafeWebsite(Match w, ParseContext ctx) {
        if (isSafeWebsite(w)) {
            return false;
        }
        if (!hasFollowingSeasonEpisodeOrDate(w, ctx)) {
            return false;
        }
        return !isEnclosedInGroup(w, ctx);
    }

    private boolean isSafeWebsite(Match w) {
        String val = w.value() instanceof String s ? s.toLowerCase(java.util.Locale.ROOT) : "";
        return safeStarts.stream().anyMatch(p -> val.startsWith(p.toLowerCase(java.util.Locale.ROOT)));
    }

    private boolean hasFollowingSeasonEpisodeOrDate(Match w, ParseContext ctx) {
        return ctx.matches.all().anyMatch(o ->
                (o.name() == MatchName.SEASON || o.name() == MatchName.EPISODE
                        || o.name() == MatchName.YEAR || o.name() == MatchName.DATE)
                        && o.start() >= w.end());
    }

    private boolean isEnclosedInGroup(Match w, ParseContext ctx) {
        return ctx.markers.stream().anyMatch(mk -> "group".equals(mk.name())
                && mk.start() <= w.start() && mk.end() >= w.end());
    }

    private boolean shouldRemovePrefixMatch(Match m, ParseContext ctx, ArrayList<Match> toRemove) {
        var websiteMatch = ctx.matches.named(MatchName.WEBSITE)
                .filter(w -> w.start() > m.end() && !toRemove.contains(w))
                .findFirst().orElse(null);

        if (websiteMatch == null) {
            return true;
        }

        return hasNonSeparatorContent(ctx.input, m.end(), websiteMatch.start());
    }

    private boolean hasNonSeparatorContent(String input, int start, int end) {
        var raw = input.substring(start, end);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Seps.isSep(c)) {
                return true;
            }
        }
        return false;
    }

    private static String buildOrPattern(List<String> items) {
        if (items.isEmpty()) return "(?!)";
        return "(?:" + items.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")";
    }

    private static Pattern safeCompile(String src) {
        try {
            return Pattern.compile(src, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            throw new IllegalStateException("Bad pattern: " + src, e);
        }
    }

    private static List<String> loadTlds() {
        var tlds = new ArrayList<String>();
        try (var is = WebsiteExtractor.class.getResourceAsStream(TLD_PATH);
             var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (first) {
                    first = false;
                    continue;
                } // skip header
                if (line.isEmpty() || line.contains("--")) continue;
                tlds.add(line.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load TLD list from " + TLD_PATH, e);
        }
        return tlds;
    }

    /**
     * Lazily loads config so the static initializer works in tests.
     */
    private static class ConfigHolder {
        private static java.util.Map<String, Object> websiteConfig() {
            var config = io.guessit.config.ConfigLoader.load(io.guessit.Options.defaults());
            return config.section(WEBSITE);
        }
    }
}
