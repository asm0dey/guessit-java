package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.lang.Country;
import io.guessit.lang.LanguageRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CountryExtractor implements Extractor {
    @Override public String name() { return "country"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var allowed = allowedCountries(ctx);
        if (allowed.isEmpty()) return;

        var allowedLc = new HashSet<String>(allowed.size());
        for (var s : allowed) allowedLc.add(s.toLowerCase(Locale.ROOT));

        var registry = LanguageRegistry.instance();
        var input = ctx.input;

        for (var word : Words.iter(input)) {
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (lower.chars().allMatch(Character::isDigit)) continue;
            var country = registry.findCountry(lower).orElse(null);
            if (country == null) continue;
            if (!allowedLc.contains(country.alpha2().toLowerCase(Locale.ROOT))
                    && !allowedLc.contains(country.name().toLowerCase(Locale.ROOT))) continue;
            ctx.matches.add(new Match("country", country, word.start(), word.end(),
                input.substring(word.start(), word.end()), 1000, Set.of(), false));
        }
    }

    private static List<String> allowedCountries(ParseContext ctx) {
        var explicit = ctx.options.allowedCountries();
        if (!explicit.isEmpty()) return explicit;
        return ctx.config.topLevelList("allowed_countries");
    }

    /** Resolve "country vs language" conflict: prefer language unless country is US/GB. */
    @Override
    public void postProcess(ParseContext ctx) {
        var countries = ctx.matches.named("country").toList();
        var langs = ctx.matches.named("language").toList();
        var toRemove = new ArrayList<Match>();
        for (var c : countries) {
            for (var l : langs) {
                if (c.start() == l.start() && c.end() == l.end()) {
                    if (c.value() instanceof Country cc
                            && !"US".equals(cc.alpha2()) && !"GB".equals(cc.alpha2())) {
                        toRemove.add(c);
                    } else {
                        toRemove.add(l);
                    }
                }
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
