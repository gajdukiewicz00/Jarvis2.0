package org.jarvis.media.subtitle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleFormatterTest {

    private final SubtitleFormatter formatter = new SubtitleFormatter();

    private final List<TranslatedSegment> cues = List.of(
            new TranslatedSegment(0, 0, 2500, "Привет", "S1", 0.9),
            new TranslatedSegment(1, 2600, 3661000, "Мир", "S1", 0.9));

    @Test
    void srtUsesCommaMillisAndSequentialNumbering() {
        String srt = formatter.toSrt(cues);
        assertThat(srt).contains("1\n00:00:00,000 --> 00:00:02,500\nПривет");
        assertThat(srt).contains("2\n00:00:02,600 --> 01:01:01,000\nМир");
    }

    @Test
    void vttStartsWithHeaderAndUsesDotMillis() {
        String vtt = formatter.toVtt(cues);
        assertThat(vtt).startsWith("WEBVTT\n\n");
        assertThat(vtt).contains("00:00:00.000 --> 00:00:02.500");
        assertThat(vtt).contains("Привет");
    }

    @Test
    void timeFormattingIsZeroPadded() {
        assertThat(formatter.srtTime(0)).isEqualTo("00:00:00,000");
        assertThat(formatter.srtTime(3_661_001)).isEqualTo("01:01:01,001");
        assertThat(formatter.vttTime(59_999)).isEqualTo("00:00:59.999");
    }
}
