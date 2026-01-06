package org.jarvis.voicegateway.util;

/**
 * Simple language detection utility based on character analysis.
 * 
 * <h2>Detection Rules</h2>
 * <ul>
 *   <li>If the text contains any Cyrillic characters → Russian (ru)</li>
 *   <li>Otherwise → English (en)</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * String lang = LanguageDetector.detect("сделай громче"); // "ru"
 * String lang = LanguageDetector.detect("make it louder"); // "en"
 * }</pre>
 */
public final class LanguageDetector {

    public static final String RUSSIAN = "ru";
    public static final String ENGLISH = "en";

    private LanguageDetector() {
        // Utility class
    }

    /**
     * Detect language based on Cyrillic character presence.
     * 
     * @param text The text to analyze (typically the user's spoken command)
     * @return Language code: "ru" if Cyrillic found, "en" otherwise
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return RUSSIAN; // Default to Russian
        }

        for (char c : text.toCharArray()) {
            if (isCyrillic(c)) {
                return RUSSIAN;
            }
        }

        return ENGLISH;
    }

    /**
     * Check if a character is Cyrillic.
     * Includes both Russian uppercase (А-Я) and lowercase (а-я) letters,
     * as well as Ё/ё.
     */
    private static boolean isCyrillic(char c) {
        return (c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё';
    }
}

