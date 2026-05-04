package io.guessit.rules.property;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ExpectedTitleRegex {
    public record Entry(Pattern pattern, String literalReplacement) {}

    public static List<Entry> parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        var out = new ArrayList<Entry>(raw.size());
        for (var s : raw) {
            if (s.startsWith("re:")) {
                out.add(new Entry(Pattern.compile(s.substring(3), Pattern.CASE_INSENSITIVE), null));
            } else {
                out.add(new Entry(Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE), s));
            }
        }
        return out;
    }
}
