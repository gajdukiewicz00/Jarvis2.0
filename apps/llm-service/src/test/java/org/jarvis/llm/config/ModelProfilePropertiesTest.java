package org.jarvis.llm.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelProfilePropertiesTest {

    private final ModelProfileProperties props = new ModelProfileProperties();

    @Test
    void voiceFastProfileDefaults() {
        ModelProfileProperties.Profile p = props.resolve("voice-fast");
        assertEquals(256, p.getMaxTokens());
        assertEquals(0.3, p.getTemperature(), 0.01);
        assertEquals(15, p.getTimeoutSeconds());
    }

    @Test
    void desktopGeneralProfileDefaults() {
        ModelProfileProperties.Profile p = props.resolve("desktop-general");
        assertEquals(512, p.getMaxTokens());
        assertEquals(0.7, p.getTemperature(), 0.01);
        assertEquals(120, p.getTimeoutSeconds());
    }

    @Test
    void backgroundSummaryProfileDefaults() {
        ModelProfileProperties.Profile p = props.resolve("background-summary");
        assertEquals(1024, p.getMaxTokens());
        assertEquals(0.5, p.getTemperature(), 0.01);
        assertEquals(300, p.getTimeoutSeconds());
    }

    @Test
    void orchestrationProfileDefaults() {
        ModelProfileProperties.Profile p = props.resolve("orchestration");
        assertEquals(700, p.getMaxTokens());
        assertEquals(0.2, p.getTemperature(), 0.01);
        assertEquals(60, p.getTimeoutSeconds());
    }

    @Test
    void nullProfileDefaultsToDesktop() {
        ModelProfileProperties.Profile p = props.resolve(null);
        assertEquals(512, p.getMaxTokens());
    }

    @Test
    void unknownProfileDefaultsToDesktop() {
        ModelProfileProperties.Profile p = props.resolve("unknown-profile");
        assertEquals(512, p.getMaxTokens());
    }

    @Test
    void shortAliasesWork() {
        assertEquals(props.resolve("voice").getMaxTokens(), props.resolve("voice-fast").getMaxTokens());
        assertEquals(props.resolve("desktop").getMaxTokens(), props.resolve("desktop-general").getMaxTokens());
        assertEquals(props.resolve("background").getMaxTokens(), props.resolve("background-summary").getMaxTokens());
    }
}
