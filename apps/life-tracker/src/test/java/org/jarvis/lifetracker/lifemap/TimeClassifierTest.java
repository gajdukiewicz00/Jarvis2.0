package org.jarvis.lifetracker.lifemap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeClassifierTest {

    private LifeMapProperties properties;
    private TimeClassifier classifier;

    @BeforeEach
    void setUp() {
        properties = new LifeMapProperties();
        classifier = new TimeClassifier(properties);
    }

    @Test
    void ideClassifiedAsWork() {
        assertThat(classifier.classify("IntelliJ IDEA", "MyProject [main]")).isEqualTo(TimeCategory.WORK);
        assertThat(classifier.classify("Cursor", "AgentMain.kt")).isEqualTo(TimeCategory.WORK);
        assertThat(classifier.classify("Visual Studio Code", "")).isEqualTo(TimeCategory.WORK);
    }

    @Test
    void streamingClassifiedAsRest() {
        assertThat(classifier.classify("Firefox", "YouTube — JavaFX tutorial")).isEqualTo(TimeCategory.REST);
        assertThat(classifier.classify("Spotify", "Lo-fi beats")).isEqualTo(TimeCategory.REST);
        assertThat(classifier.classify("Steam", "CS:GO")).isEqualTo(TimeCategory.REST);
    }

    @Test
    void learningClassifiedAsStudy() {
        assertThat(classifier.classify("Chrome", "Coursera — ML basics")).isEqualTo(TimeCategory.STUDY);
        assertThat(classifier.classify("Anki", "vocab deck")).isEqualTo(TimeCategory.STUDY);
        assertThat(classifier.classify("Acrobat", "diploma.pdf")).isEqualTo(TimeCategory.STUDY);
    }

    @Test
    void sportClassifiedAsSport() {
        assertThat(classifier.classify("Strava", "morning run")).isEqualTo(TimeCategory.SPORT);
        assertThat(classifier.classify("Nike Training", "")).isEqualTo(TimeCategory.SPORT);
    }

    @Test
    void sleepClassifiedAsSleep() {
        assertThat(classifier.classify("Sleep Cycle", "")).isEqualTo(TimeCategory.SLEEP);
        assertThat(classifier.classify("Oura", "")).isEqualTo(TimeCategory.SLEEP);
    }

    @Test
    void unknownDefaultsToCustom() {
        assertThat(classifier.classify("Unknown App", "Strange title")).isEqualTo(TimeCategory.CUSTOM);
        assertThat(classifier.classify("", "")).isEqualTo(TimeCategory.CUSTOM);
        assertThat(classifier.classify(null, null)).isEqualTo(TimeCategory.CUSTOM);
    }

    @Test
    void rulesAreReloadedWhenPropertiesChange() {
        // First — Steam is REST per default rules.
        assertThat(classifier.classify("Steam", "")).isEqualTo(TimeCategory.REST);
        // Hot-swap: rebrand Steam as STUDY for this user's setup.
        properties.getTimeClassification().getRules().put("STUDY", java.util.List.of("(?i)steam|steam-classroom"));
        assertThat(classifier.classify("Steam", "")).isEqualTo(TimeCategory.STUDY);
    }

    @Test
    void invalidRegexIsSkippedNotFatal() {
        properties.getTimeClassification().getRules().put("WORK",
                java.util.List.of("(?i)valid", "[unclosed"));
        // forces recompile by mutating same key — classifier should not throw.
        assertThat(classifier.classify("valid app", "")).isEqualTo(TimeCategory.WORK);
    }
}
