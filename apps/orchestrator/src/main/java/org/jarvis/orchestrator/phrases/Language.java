package org.jarvis.orchestrator.phrases;

/**
 * Supported languages for Jarvis responses.
 */
public enum Language {
    RU("ru"),
    EN("en");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * Parse language from code string.
     * Defaults to RU if unrecognized.
     */
    public static Language fromCode(String code) {
        if (code == null || code.isBlank()) {
            return RU;
        }
        String lowerCode = code.toLowerCase();
        if (lowerCode.startsWith("en")) {
            return EN;
        }
        return RU;
    }
}

