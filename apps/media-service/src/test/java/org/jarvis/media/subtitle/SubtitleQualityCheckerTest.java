package org.jarvis.media.subtitle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleQualityCheckerTest {

    private final SubtitleQualityChecker checker = new SubtitleQualityChecker();

    @Test
    void flagsLongSegment() {
        List<TranslatedSegment> cues = List.of(
                new TranslatedSegment(0, 0, 20_000, "длинно", "S1", 0.9)); // 20s > 7s
        List<SubtitleWarning> warnings = checker.check(cues, 7, 0.5);
        assertThat(warnings).anyMatch(w -> w.type().equals(SubtitleWarning.LONG_SEGMENT));
    }

    @Test
    void flagsLowConfidence() {
        List<TranslatedSegment> cues = List.of(
                new TranslatedSegment(0, 0, 2000, "текст", "S1", 0.2));
        assertThat(checker.check(cues, 7, 0.5))
                .anyMatch(w -> w.type().equals(SubtitleWarning.LOW_CONFIDENCE));
    }

    @Test
    void flagsOverlap() {
        List<TranslatedSegment> cues = List.of(
                new TranslatedSegment(0, 0, 3000, "a", "S1", 0.9),
                new TranslatedSegment(1, 2000, 4000, "b", "S2", 0.9)); // starts before prev ends
        assertThat(checker.check(cues, 7, 0.5))
                .anyMatch(w -> w.type().equals(SubtitleWarning.OVERLAP));
    }

    @Test
    void flagsEmptyTranslation() {
        List<TranslatedSegment> cues = List.of(
                new TranslatedSegment(0, 0, 2000, "  ", "S1", 0.9));
        assertThat(checker.check(cues, 7, 0.5))
                .anyMatch(w -> w.type().equals(SubtitleWarning.EMPTY_TRANSLATION));
    }

    @Test
    void cleanSegmentsProduceNoWarnings() {
        List<TranslatedSegment> cues = List.of(
                new TranslatedSegment(0, 0, 2000, "ок", "S1", 0.9),
                new TranslatedSegment(1, 2000, 4000, "норм", "S1", 0.9));
        assertThat(checker.check(cues, 7, 0.5)).isEmpty();
    }
}
