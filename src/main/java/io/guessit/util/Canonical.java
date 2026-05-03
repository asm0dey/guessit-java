package io.guessit.util;

import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.lang.LanguageRegistry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical-form helpers for comparing GuessIt outputs and YAML expected scalars.
 *
 * <p>Mirrors Python {@code babelfish}/{@code guessit.test.test_yml} semantics: a Country
 * or Language compares equal to its name, alpha-2 or alpha-3 form. So that the
 * String "English", "en", "eng" and the parsed {@link Language} all canonicalize
 * to the same key.
 */
public final class Canonical {
    private Canonical() {}

    /**
     * Resolve {@code o} to a canonical form for equality comparison:
     *  <ul>
     *      <li>{@code null} → {@code "null"}</li>
     *      <li>{@link Language} / {@link Country} → their {@link Object#toString()} (alpha code)</li>
     *      <li>{@link String} → looked up in the language/country registries and rendered
     *          as the matching alpha code; ambiguous 2-letter codes prefer country (e.g. "au"
     *          → country Australia, not language Awadhi)</li>
     *      <li>anything else → {@link Object#toString()}</li>
     *  </ul>
     *  The returned key is lowercased so case differences in YAML scalars don't break parity.
     */
    public static String key(Object o) {
        return rawKey(o).toLowerCase(Locale.ROOT);
    }

    /** Returns the canonical key as a {@link Set}, expanding {@link Iterable} values element-wise. */
    public static Set<String> keySet(Object o) {
        var set = new LinkedHashSet<String>();
        if (o instanceof Iterable<?> iter) {
            for (var e : iter) set.add(key(e));
        } else {
            set.add(key(o));
        }
        return set;
    }

    /** True when {@code value} and {@code expected} canonicalize to the same multiset of keys. */
    public static boolean equivalent(Object value, Object expected) {
        return keySet(value).equals(keySet(expected));
    }

    private static String rawKey(Object o) {
        if (o == null) return "null";
        if (o instanceof LocalDate ld) {
            return ld.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (o instanceof java.util.Date d) {
            return d.toInstant().atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (o instanceof String s) {
            var lower = s.toLowerCase(Locale.ROOT);
            // Prefer country resolution for 2-letter codes that overlap with obscure language
            // alpha-3 codes (e.g. "au" is country Australia, not language Awadhi).
            if (lower.length() == 2) {
                var country = LanguageRegistry.instance().findCountry(lower).orElse(null);
                if (country != null) return country.toString();
            }
            var lang = LanguageRegistry.instance().find(lower).orElse(null);
            if (lang != null) return lang.toString();
            var country = LanguageRegistry.instance().findCountry(lower).orElse(null);
            if (country != null) return country.toString();
        }
        return o.toString();
    }
}
