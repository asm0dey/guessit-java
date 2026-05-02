package io.guessit.config;

import java.util.Map;

public record OptionsConfig(Map<String, Object> raw) {
    public OptionsConfig { raw = raw == null ? Map.of() : Map.copyOf(raw); }
    public static OptionsConfig empty() { return new OptionsConfig(Map.of()); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        var v = raw.get(name);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
