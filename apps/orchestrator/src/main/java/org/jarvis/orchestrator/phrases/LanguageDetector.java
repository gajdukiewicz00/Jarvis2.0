package org.jarvis.orchestrator.phrases;

/**
 * Simple language detection utility based on character analysis.
 * 
 * <h2>Detection Rules</h2>
 * <ul>
 *   <li>If the text contains any Cyrillic characters → Russian (RU)</li>
 *   <li>Otherwise → English (EN)</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * Language lang = LanguageDetector.detect("сделай громче"); // RU
 * Language lang = LanguageDetector.detect("make it louder"); // EN
 * }</pre>
 */
public final class LanguageDetector {

    private LanguageDetector() {
        // Utility class
    }

    /**
     * Detect language based on Cyrillic character presence.
     * 
     * @param text The text to analyze (typically the user's spoken command)
     * @return Detected language (RU if Cyrillic found, EN otherwise)
     */
    public static Language detect(String text) {
        if (text == null || text.isBlank()) {
            return Language.RU; // Default to Russian
        }

        for (char c : text.toCharArray()) {
            if (isCyrillic(c)) {
                return Language.RU;
            }
        }

        return Language.EN;
    }

    /**
     * Check if a character is Cyrillic.
     * Includes both Russian uppercase (А-Я) and lowercase (а-я) letters,
     * as well as Ё/ё.
     */
    private static boolean isCyrillic(char c) {
        return (c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё';
    }

    /**
     * Check if text appears to be primarily Russian.
     * Useful when you need a confidence score rather than binary detection.
     * 
     * @param text The text to analyze
     * @return Ratio of Cyrillic characters (0.0 to 1.0)
     */
    public static double getCyrillicRatio(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        long cyrillicCount = text.chars()
                .filter(c -> isCyrillic((char) c))
                .count();

        long alphaCount = text.chars()
                .filter(Character::isLetter)
                .count();

        if (alphaCount == 0) {
            return 0.0;
        }

        return (double) cyrillicCount / alphaCount;
    }
}

