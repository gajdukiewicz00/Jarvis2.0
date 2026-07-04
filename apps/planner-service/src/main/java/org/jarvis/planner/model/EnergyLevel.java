package org.jarvis.planner.model;

import java.util.Locale;

/**
 * B3 — user's current energy state, drives energy-aware task recommendations.
 */
public enum EnergyLevel {
    HIGH,
    NORMAL,
    LOW,
    EXHAUSTED;

    /** Map free / spoken text ("я устал", "выжат", "норм", "полон сил") to a level. */
    public static EnergyLevel fromText(String s) {
        if (s == null || s.isBlank()) {
            return NORMAL;
        }
        String t = s.toLowerCase(Locale.ROOT).trim();
        try {
            return EnergyLevel.valueOf(t.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignore) {
            // fall through to keyword matching
        }
        if (containsAny(t, "выжат", "вымотан", "истощ", "без сил", "exhausted", "drained")) {
            return EXHAUSTED;
        }
        if (containsAny(t, "устал", "уставш", "нет сил", "tired", "low energy", "low")) {
            return LOW;
        }
        if (containsAny(t, "полон сил", "бодр", "энергич", "прилив", "high energy", "energized", "high")) {
            return HIGH;
        }
        return NORMAL;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String n : needles) {
            if (value.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
