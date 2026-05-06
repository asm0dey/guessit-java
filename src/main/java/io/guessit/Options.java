package io.guessit;

import org.jilt.Builder;
import org.jilt.BuilderStyle;
import org.jilt.Opt;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Builder(style = BuilderStyle.CLASSIC, factoryMethod = "options")
public record Options(
    @Opt String type,                       // "movie"|"episode"|null
    @Opt String name,                       // override input
    List<String> expectedTitle,
    List<String> expectedGroup,
    List<String> excludes,
    List<String> includes,
    List<String> allowedLanguages,
    List<String> allowedCountries,
    @Opt Boolean dateYearFirst,
    @Opt Boolean dateDayFirst,
    @Opt Boolean episodePreferNumber,
    @Opt Boolean enforceListWhenSingle,
    List<Path> configPaths,
    boolean noUserConfig,
    boolean noDefaultConfig,
    boolean nameOnly,
    Map<String, Object> raw
) {
    public Options {
        expectedTitle    = expectedTitle    == null ? List.of() : List.copyOf(expectedTitle);
        expectedGroup    = expectedGroup    == null ? List.of() : List.copyOf(expectedGroup);
        excludes         = excludes         == null ? List.of() : List.copyOf(excludes);
        includes         = includes         == null ? List.of() : List.copyOf(includes);
        allowedLanguages = allowedLanguages == null ? List.of() : List.copyOf(allowedLanguages);
        allowedCountries = allowedCountries == null ? List.of() : List.copyOf(allowedCountries);
        configPaths      = configPaths      == null ? List.of() : List.copyOf(configPaths);
        // unmodifiableMap (vs Map.copyOf) so configs with explicit-null entries
        // — legitimate in YAML/JSON — don't NPE in the canonical constructor.
        raw              = raw              == null ? Map.of()  : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    public static Options defaults() { return OptionsBuilder.options().build(); }

}
