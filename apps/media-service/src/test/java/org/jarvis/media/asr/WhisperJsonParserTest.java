package org.jarvis.media.asr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhisperJsonParserTest {

    private final WhisperJsonParser parser = new WhisperJsonParser();

    @Test
    void parsesTranscriptionSegmentsWithOffsetsAndDetectedLanguage() {
        String json = """
                {
                  "result": {"language": "en"},
                  "transcription": [
                    {"offsets": {"from": 0, "to": 2500}, "text": " Good evening."},
                    {"offsets": {"from": 2600, "to": 6000}, "text": " Welcome back."}
                  ]
                }
                """;

        Transcript transcript = parser.parse(json, null);

        assertThat(transcript.language()).isEqualTo("en");
        assertThat(transcript.segments()).hasSize(2);
        assertThat(transcript.segments().get(0).index()).isEqualTo(0);
        assertThat(transcript.segments().get(0).text()).isEqualTo("Good evening.");
        assertThat(transcript.segments().get(0).startMs()).isEqualTo(0);
        assertThat(transcript.segments().get(0).endMs()).isEqualTo(2500);
        assertThat(transcript.segments().get(1).index()).isEqualTo(1);
        assertThat(transcript.segments().get(1).endMs()).isEqualTo(6000);
    }

    @Test
    void fallsBackToLanguageHintWhenResultLanguageMissing() {
        Transcript transcript = parser.parse("{\"transcription\": []}", "fr");

        assertThat(transcript.language()).isEqualTo("fr");
        assertThat(transcript.isEmpty()).isTrue();
    }

    @Test
    void defaultsToEnglishWhenNoLanguageOrHintAvailable() {
        Transcript transcript = parser.parse("{\"transcription\": []}", null);

        assertThat(transcript.language()).isEqualTo("en");
    }

    @Test
    void skipsBlankTextSegmentsAndReindexesRemaining() {
        String json = """
                {"transcription": [
                    {"offsets": {"from": 0, "to": 100}, "text": "   "},
                    {"offsets": {"from": 100, "to": 200}, "text": "Hi"}
                ]}
                """;

        Transcript transcript = parser.parse(json, null);

        assertThat(transcript.segments()).hasSize(1);
        assertThat(transcript.segments().get(0).index()).isEqualTo(0);
        assertThat(transcript.segments().get(0).text()).isEqualTo("Hi");
    }

    @Test
    void missingTranscriptionArrayYieldsEmptyTranscript() {
        Transcript transcript = parser.parse("{\"result\": {\"language\": \"en\"}}", null);

        assertThat(transcript.isEmpty()).isTrue();
    }

    @Test
    void blankInputThrowsAsrException() {
        assertThatThrownBy(() -> parser.parse("", null)).isInstanceOf(AsrException.class);
        assertThatThrownBy(() -> parser.parse(null, null)).isInstanceOf(AsrException.class);
    }

    @Test
    void malformedJsonThrowsAsrException() {
        assertThatThrownBy(() -> parser.parse("{not valid json", null)).isInstanceOf(AsrException.class);
    }
}
