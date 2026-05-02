package io.guessit.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record OptionsConfig(Map<String, Object> raw) {
    public OptionsConfig { raw = raw == null ? Map.of() : Map.copyOf(raw); }
    public static OptionsConfig empty() { return new OptionsConfig(Map.of()); }

    /** Returns the inner advanced_config map for property lookups, or empty. */
    private Map<String, Object> advancedConfig() {
        var ac = raw.get("advanced_config");
        return ac instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        // Unwrap advanced_config to match Python guessit: rules_builder receives only advanced_config
        var ac = advancedConfig();
        var v = ac.get(name);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> topLevelList(String name) {
        var v = raw.get(name);
        if (v instanceof List<?> list) {
            return (List<String>) list;
        }
        return Collections.emptyList();
    }
}
