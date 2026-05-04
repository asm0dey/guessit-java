package io.guessit.parity;

import io.guessit.Options;

import java.util.Map;

public record YmlCase(
    String file,
    int line,
    String input,
    Map<String, Object> expected,
    Options options,
    boolean negative
) {
    @Override
    public String toString() {
        var opts = optionsSummary(options);
        var base = file + ":" + line + " \"" + input + "\"";
        return opts.isEmpty() ? base : base + " opts=" + opts;
    }

    private static String optionsSummary(Options o) {
        if (o == null) return "";
        var sb = new StringBuilder();
        if (o.type() != null) append(sb, "type", o.type());
        if (o.name() != null) append(sb, "name", o.name());
        if (!o.expectedTitle().isEmpty()) append(sb, "expectedTitle", o.expectedTitle());
        if (!o.expectedGroup().isEmpty()) append(sb, "expectedGroup", o.expectedGroup());
        if (!o.excludes().isEmpty()) append(sb, "excludes", o.excludes());
        if (!o.includes().isEmpty()) append(sb, "includes", o.includes());
        if (!o.allowedLanguages().isEmpty()) append(sb, "allowedLanguages", o.allowedLanguages());
        if (!o.allowedCountries().isEmpty()) append(sb, "allowedCountries", o.allowedCountries());
        if (o.dateYearFirst() != null) append(sb, "dateYearFirst", o.dateYearFirst());
        if (o.dateDayFirst() != null) append(sb, "dateDayFirst", o.dateDayFirst());
        if (o.episodePreferNumber() != null) append(sb, "episodePreferNumber", o.episodePreferNumber());
        if (o.enforceListWhenSingle() != null) append(sb, "enforceListWhenSingle", o.enforceListWhenSingle());
        return sb.isEmpty() ? "" : "{" + sb + "}";
    }

    private static void append(StringBuilder sb, String key, Object value) {
        if (!sb.isEmpty()) sb.append(", ");
        sb.append(key).append('=').append(value);
    }
}
