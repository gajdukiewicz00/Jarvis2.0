package org.jarvis.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Configurable model profiles for different channels/use cases.
 * Each profile specifies max_tokens, temperature, and timeout independently.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "llm.profiles")
public class ModelProfileProperties {

    private Profile voiceFast = new Profile(256, 0.3, 15);
    private Profile desktopGeneral = new Profile(512, 0.7, 120);
    private Profile backgroundSummary = new Profile(1024, 0.5, 300);
    private Profile orchestration = new Profile(700, 0.2, 60);
    private Profile embedding = new Profile(0, 0.0, 30);

    public Profile resolve(String profileName) {
        if (profileName == null) {
            return desktopGeneral;
        }
        return switch (profileName.toLowerCase()) {
            case "voice-fast", "voice" -> voiceFast;
            case "desktop-general", "desktop" -> desktopGeneral;
            case "background-summary", "background" -> backgroundSummary;
            case "orchestration" -> orchestration;
            case "embedding" -> embedding;
            default -> desktopGeneral;
        };
    }

    @Getter
    @Setter
    public static class Profile {
        private int maxTokens;
        private double temperature;
        private int timeoutSeconds;

        public Profile() {
        }

        public Profile(int maxTokens, double temperature, int timeoutSeconds) {
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
