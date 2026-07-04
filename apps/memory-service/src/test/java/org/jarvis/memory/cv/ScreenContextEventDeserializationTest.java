package org.jarvis.memory.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer must parse the producer's full {@code ScreenContextResult}
 * JSON, keeping the fields we persist and ignoring the rest (vlm, regions,
 * detection, …) so the event contract can evolve safely.
 */
class ScreenContextEventDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // Mirrors a real vision-security-service ScreenContextResult payload.
    private static final String PRODUCER_JSON = """
            {
              "userId": "owner",
              "capturedAt": "2026-05-25T10:00:05Z",
              "durationMs": 1234,
              "screenshotPath": "/tmp/jarvis-cv/screen.png",
              "displayServer": "x11",
              "activeWindowTitle": "Terminal — bash",
              "activeProcessName": "gnome-terminal",
              "semanticTags": ["DEV", "TERMINAL"],
              "regions": [{"x":0,"y":0,"width":800,"height":600}],
              "uiElements": [],
              "objects": [],
              "detection": {"uiAvailability":"NOT_CONFIGURED","objectAvailability":"NOT_CONFIGURED"},
              "analysis": {
                "source": "screenshot",
                "imagePath": "/tmp/jarvis-cv/screen.png",
                "width": 800, "height": 600,
                "ocrText": "Hello Jarvis",
                "blocks": [{"text":"Hello","confidence":96.1,"bbox":{"x":1,"y":2,"width":3,"height":4}}],
                "engine": "tesseract",
                "language": "eng",
                "durationMs": 97,
                "capturedAt": "2026-05-25T10:00:05Z",
                "success": true,
                "error": null
              },
              "vlm": {"availability":"NOT_CONFIGURED","engine":"local-vlm-not-configured","summary":null,"error":"x"},
              "success": true,
              "error": null
            }
            """;

    @Test
    void parsesPersistedFieldsAndIgnoresTheRest() throws Exception {
        ScreenContextEvent event = mapper.readValue(PRODUCER_JSON, ScreenContextEvent.class);

        assertThat(event.userId()).isEqualTo("owner");
        assertThat(event.durationMs()).isEqualTo(1234L);
        assertThat(event.screenshotPath()).isEqualTo("/tmp/jarvis-cv/screen.png");
        assertThat(event.displayServer()).isEqualTo("x11");
        assertThat(event.activeWindowTitle()).contains("Terminal");
        assertThat(event.activeProcessName()).isEqualTo("gnome-terminal");
        assertThat(event.semanticTags()).containsExactly("DEV", "TERMINAL");
        assertThat(event.success()).isTrue();
        assertThat(event.ocrText()).isEqualTo("Hello Jarvis");
        assertThat(event.analysis().engine()).isEqualTo("tesseract");
        assertThat(event.analysis().language()).isEqualTo("eng");
        assertThat(event.analysis().blocks()).hasSize(1);
    }
}
