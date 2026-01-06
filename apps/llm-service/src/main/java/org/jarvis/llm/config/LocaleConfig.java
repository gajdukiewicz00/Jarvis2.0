package org.jarvis.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Localization configuration for LLM service
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm.locale")
public class LocaleConfig {
    
    /**
     * Default locale for responses
     * Default: ru_RU (Russian)
     */
    private String defaultLocale = "ru_RU";
    
    /**
     * Force Russian language in all responses
     */
    private boolean forceRussian = true;
    
    /**
     * Get Locale object
     */
    public Locale getLocale() {
        String[] parts = defaultLocale.split("_");
        if (parts.length == 2) {
            return Locale.forLanguageTag(parts[0] + "-" + parts[1]);
        }
        return Locale.forLanguageTag(parts[0]);
    }
    
    /**
     * Get language code (e.g. "ru")
     */
    public String getLanguageCode() {
        return getLocale().getLanguage();
    }
    
    /**
     * Get display name (e.g. "Русский")
     */
    public String getDisplayName() {
        return getLocale().getDisplayLanguage(getLocale());
    }
}
