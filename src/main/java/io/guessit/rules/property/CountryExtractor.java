package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Words;
import io.guessit.lang.Country;
import io.guessit.lang.LanguageRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts {@code country} via {@link LanguageRegistry}'s country lookup.
 *
 * <p>Filtered against {@code allowed_countries} at extract time. The
 * post-pass resolves the common ambiguity where a token (e.g. "US", "JP")
 * matches both a country code and a language: keep the language unless the
 * country is US or GB, where the country reading is overwhelmingly the
 * intended one in release names.
 */
public final class CountryExtractor implements Extractor {

    @Override
    public String name() { return "country"; }

    @Override
    public String description() {
        return "country tags (US, UK, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var allowed = allowedCountries(ctx);
        if (allowed.isEmpty()) return;

        var allowedLc = allowed.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        var registry = LanguageRegistry.instance();
        var input = ctx.input;

        for (var word : Words.iter(input)) {
            var raw = word.value();
            var lower = raw.toLowerCase(Locale.ROOT);

            if (lower.chars().allMatch(Character::isDigit)) {
                continue;
            }

            registry.findCountry(lower)
                    .filter(country -> isCountryAllowed(country, allowedLc))
                    .ifPresent(country -> ctx.matches.add(
                            new Match(MatchName.COUNTRY, country, word.start(), word.end(),
                                    input.substring(word.start(), word.end()), 1000, Set.of(), false)
                    ));
        }
    }

    private static List<String> allowedCountries(ParseContext ctx) {
        var explicit = ctx.options.allowedCountries();
        return !explicit.isEmpty() ? explicit : ctx.config.topLevelList("allowed_countries");
    }

    private boolean isCountryAllowed(Country country, Set<String> allowedLc) {
        return allowedLc.contains(country.alpha2().toLowerCase(Locale.ROOT))
                || allowedLc.contains(country.name().toLowerCase(Locale.ROOT));
    }

    /** Resolve "country vs language" conflict: prefer language unless country is US/GB. */
    @Override
    public void postProcess(ParseContext ctx) {
        var countries = ctx.matches.named(MatchName.COUNTRY).toList();
        var languages = ctx.matches.named(MatchName.LANGUAGE).toList();
        var toRemove = new ArrayList<Match>();

        for (var countryMatch : countries) {
            languages.stream()
                    .filter(langMatch -> countryMatch.start() == langMatch.start() && countryMatch.end() == langMatch.end())
                    .forEach(langMatch -> resolveConflict(countryMatch, langMatch, toRemove));
        }

        toRemove.forEach(ctx.matches::remove);
    }

    private void resolveConflict(Match countryMatch, Match langMatch, List<Match> toRemove) {
        if (countryMatch.value() instanceof Country cc
                && ("US".equals(cc.alpha2()) || "GB".equals(cc.alpha2()))) {
            toRemove.add(langMatch);
        } else {
            toRemove.add(countryMatch);
        }
    }
}