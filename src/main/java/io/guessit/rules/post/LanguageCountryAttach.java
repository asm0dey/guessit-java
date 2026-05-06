package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase;
import io.guessit.engine.Seps;
import io.guessit.lang.Language;
import io.guessit.lang.LanguageRegistry;

import java.util.Comparator;
import java.util.Locale;

/**
 * Port of Python guessit's {@code LanguageCountry} post-processor.
 *
 * <p>For each {@code language} match, inspects what immediately follows its
 * span in the input. If the next character is a separator and the two
 * characters after that are a valid ISO-3166-1 alpha-2 country code (and the
 * token ends at a word boundary), the language match is:
 * <ul>
 *   <li>replaced with a new {@link Language} that carries the resolved
 *       {@link io.guessit.lang.Country}; and</li>
 *   <li>its end is extended past the separator + 2-letter token.</li>
 * </ul>
 * Any standalone {@code country} match covering that same 2-letter token is
 * removed to avoid duplicate output.
 *
 * <p>Examples: {@code pt-BR} → Portuguese with country=Brazil,
 * {@code de-CH} → German with country=Switzerland.
 */
public final class LanguageCountryAttach implements PostPhase.PostProcessor {

    @Override
    public void process(ParseContext ctx) {
        var langs = ctx.matches.named(MatchName.LANGUAGE)
            .filter(m -> !m.isPrivate())
            .sorted(Comparator.comparingInt(Match::start))
            .toList();

        for (var l : langs) {
            int s = l.end();
            // Need at least sep + 2 alpha chars
            if (s + 3 > ctx.input.length()) continue;

            // Only accept '-' (hyphen) as the locale connector, not arbitrary separators like dots
            if (ctx.input.charAt(s) != '-') continue;

            String token = ctx.input.substring(s + 1, s + 3);
            if (!token.matches("[A-Za-z]{2}")) continue;

            // Must be at a word boundary: either end-of-string or followed by a separator
            if (s + 3 < ctx.input.length() && !Seps.isSep(ctx.input.charAt(s + 3))) continue;

            // Resolve country via LanguageRegistry (full ISO-3166-1 table)
            var countryOpt = LanguageRegistry.instance().findCountry(token.toUpperCase(Locale.ROOT));
            if (countryOpt.isEmpty()) continue;

            var country = countryOpt.get();

            // Remove any standalone country match already covering this 2-letter token
            ctx.matches.named(MatchName.COUNTRY)
                .filter(m -> m.start() >= s + 1 && m.end() <= s + 3)
                .toList()
                .forEach(ctx.matches::remove);

            // Replace language match with country-attached variant, span extended
            var oldLang = (Language) l.value();
            var newLang = new Language(oldLang.alpha2(), oldLang.alpha3(), oldLang.name(), country);
            ctx.matches.replace(l, l.withValue(newLang).withEnd(s + 3));
        }
    }
}
