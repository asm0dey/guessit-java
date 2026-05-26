package io.guessit.rules.property;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import io.guessit.engine.Abbreviations;

/**
 * Registry and Builder for Source extraction rules.
 * Separates rule definition and Regex compilation from the extraction logic.
 */
public final class SourceRuleRegistry {

    public static final String BLU_RAY = "Blu-ray";
    private static final Set<String> COMMON_TAGS = Set.of("video-codec-prefix", "streaming_service.suffix");
    private static final ConcurrentMap<String, Pattern> RULE_CACHE = new ConcurrentHashMap<>();

    public record Rule(List<String> patterns, String prefix, String suffix, String source,
                       String otherValue, String anotherValue, Set<String> tags, boolean weak, Pattern compiledPattern) {
    }

    public static List<Rule> buildRules(String ripPrefix, String ripSuffix, String optRipSuffix) {
        var definitions = List.of(
                ripRule("VHS", optRipSuffix, "VHS"),
                ripRule("Camera", optRipSuffix, "CAM"),
                ripRule("HD Camera", optRipSuffix, "HD-?CAM"),
                ripRule("Telesync", optRipSuffix, "TELESYNC", "TS"),
                ripRule("HD Telesync", optRipSuffix, "HD-?TELESYNC", "HD-?TS"),
                exactRule("Workprint", "WORKPRINT", "WP"),
                ripRule("Telecine", optRipSuffix, "TELECINE", "TC"),
                ripRule("HD Telecine", optRipSuffix, "HD-?TELECINE", "HD-?TC"),
                ripRule("Pay-per-view", optRipSuffix, "PPV"),
                ripRule("TV", optRipSuffix, "SD-?TV"),
                ripRule("TV", ripSuffix, "TV"),

                customRule("TV", "TV", "SD-?TV").prefix(ripPrefix).other("Rip"),

                exactRule("TV", "TV(?=-?Dub\\b)"),
                ripRule("Digital TV", optRipSuffix, "DVB", "PD-?TV"),
                ripRule("DVD", optRipSuffix, "DVD"),
                ripRule("Digital Master", optRipSuffix, "DM"),
                exactRule("DVD", "VIDEO-?TS", "DVD-?R(?:$|(?!E))", "DVD-?9", "DVD-?5"),
                ripRule("HDTV", optRipSuffix, "HD-?TV"),
                ripRule("HDTV", ripSuffix, "TV-?HD"),

                customRule("HDTV", "TV").suffix("-?(?<other>Rip-?HD)").other("Rip"),

                ripRule("Video on Demand", optRipSuffix, "VOD"),
                ripRule("Web", ripSuffix, "WEB", "WEB-?DL"),

                customRule("Web", "WEB-?(?<another>Cap)").suffix(optRipSuffix).other("Rip").another("Rip"),

                exactRule("Web", "WEB-?DL", "WEB-?U?HD", "DL-?WEB", "DL(?=-?Mux)"),

                customRule("Web", "WEB").tags(Set.of("weak.source")).weak(),

                ripRule("HD-DVD", optRipSuffix, "HD-?DVD"),
                ripRule(BLU_RAY, optRipSuffix, "Blu-?ray", "BD25", "BD50", "BD[59]", "BD"),

                customRule(BLU_RAY, "(?<another>BR)-?(?=Scr(?:eener)?|Mux)").another("Reencoded"),
                customRule(BLU_RAY, "(?<another>BR)").suffix(ripSuffix).other("Rip").another("Reencoded"),

                exactRule("Ultra HD Blu-ray", "Ultra-?Blu-?ray", "Blu-?ray-?Ultra"),
                exactRule("Analog HDTV", "AHDTV"),
                ripRule("Ultra HDTV", optRipSuffix, "UHD-?TV"),
                ripRule("Ultra HDTV", ripSuffix, "UHD"),
                ripRule("Satellite", optRipSuffix, "DSR", "DTH"),
                ripRule("Satellite", ripSuffix, "DSR?", "SAT")
        );

        return definitions.stream()
                .map(RuleBuilder::build)
                .filter(r -> r.compiledPattern() != null)
                .toList();
    }

    private static RuleBuilder ripRule(String source, String suffix, String... patterns) {
        return new RuleBuilder(source, patterns).suffix(suffix).other("Rip");
    }

    private static RuleBuilder exactRule(String source, String... patterns) {
        return new RuleBuilder(source, patterns);
    }

    private static RuleBuilder customRule(String source, String... patterns) {
        return new RuleBuilder(source, patterns);
    }

    private static class RuleBuilder {
        private final String source;
        private final List<String> patterns;
        private String prefix = "";
        private String suffix = "";
        private String otherValue = null;
        private String anotherValue = null;
        private Set<String> tags = COMMON_TAGS;
        private boolean weak = false;

        RuleBuilder(String source, String... patterns) {
            this.source = source;
            this.patterns = List.of(patterns);
        }

        RuleBuilder prefix(String p) { this.prefix = p; return this; }
        RuleBuilder suffix(String s) { this.suffix = s; return this; }
        RuleBuilder other(String o) { this.otherValue = o; return this; }
        RuleBuilder another(String a) { this.anotherValue = a; return this; }
        RuleBuilder tags(Set<String> t) { this.tags = t; return this; }
        RuleBuilder weak() { this.weak = true; return this; }

        Rule build() {
            var alt = String.join("|", patterns);
            var src = prefix + "(" + alt + ")" + suffix;
            var pattern = RULE_CACHE.computeIfAbsent(src, s -> {
                try { return Pattern.compile(Abbreviations.dash(s), Pattern.CASE_INSENSITIVE); }
                catch (PatternSyntaxException _) { return null; }
            });
            return new Rule(patterns, prefix, suffix, source, otherValue, anotherValue, tags, weak, pattern);
        }
    }
}