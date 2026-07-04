package org.jarvis.visionsecurity.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenContextResultSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roundTripsThroughJsonWithoutDataLoss() throws Exception {
        CvBlock block = new CvBlock("Hello", 90.5, new RectBox(10, 20, 30, 40));
        CvAnalysisResult analysis = new CvAnalysisResult(
                "screenshot", "/tmp/x.png", 800, 600, "Hello",
                List.of(block), "tesseract", "eng", 42L,
                Instant.parse("2026-05-25T10:00:00Z"), true, null);
        ScreenContextResult.VlmSection vlm = new ScreenContextResult.VlmSection(
                "NOT_CONFIGURED", "local-vlm-not-configured",
                null, "Local VLM not configured");
        ScreenContextResult ctx = new ScreenContextResult(
                "owner", Instant.parse("2026-05-25T10:00:05Z"), 1234L,
                "/tmp/x.png", "x11", "Terminal", "gnome-terminal",
                List.of("GENERAL_DESKTOP"),
                List.of(new RectBox(0, 0, 800, 600)),
                List.of(new DetectedElement("button", 0.91,
                        new RectBox(1, 2, 3, 4), DetectedElement.SOURCE_LOCAL_MODEL)),
                List.of(),
                new ScreenContextResult.DetectionSection("READY", "NOT_CONFIGURED"),
                analysis, vlm, true, null);

        String json = mapper.writeValueAsString(ctx);
        ScreenContextResult parsed = mapper.readValue(json, ScreenContextResult.class);

        assertThat(parsed.userId()).isEqualTo("owner");
        assertThat(parsed.success()).isTrue();
        assertThat(parsed.analysis().ocrText()).isEqualTo("Hello");
        assertThat(parsed.analysis().blocks()).hasSize(1);
        assertThat(parsed.analysis().blocks().get(0).bbox().width()).isEqualTo(30);
        assertThat(parsed.vlm().availability()).isEqualTo("NOT_CONFIGURED");
        assertThat(parsed.vlm().summary()).isNull();
        assertThat(parsed.regions()).hasSize(1);
        assertThat(parsed.semanticTags()).containsExactly("GENERAL_DESKTOP");
        assertThat(parsed.displayServer()).isEqualTo("x11");
        assertThat(parsed.durationMs()).isEqualTo(1234L);
        assertThat(parsed.uiElements()).hasSize(1);
        assertThat(parsed.uiElements().get(0).label()).isEqualTo("button");
        assertThat(parsed.uiElements().get(0).source()).isEqualTo("local-model");
        assertThat(parsed.uiElements().get(0).bbox().width()).isEqualTo(3);
        assertThat(parsed.objects()).isEmpty();
        assertThat(parsed.detection().uiAvailability()).isEqualTo("READY");
        assertThat(parsed.detection().objectAvailability()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void preservesFailureFields() throws Exception {
        CvAnalysisResult analysis = new CvAnalysisResult(
                "screenshot", null, null, null, "", List.of(),
                "tesseract", "eng", 0L,
                Instant.parse("2026-05-25T10:00:00Z"),
                false, "Screenshot capture failed: headless");
        ScreenContextResult ctx = new ScreenContextResult(
                null, Instant.parse("2026-05-25T10:00:01Z"), 5L, null,
                "headless", "", "", List.of(), List.of(),
                List.of(), List.of(),
                new ScreenContextResult.DetectionSection("NOT_CONFIGURED", "NOT_CONFIGURED"),
                analysis,
                new ScreenContextResult.VlmSection("NOT_CONFIGURED", "local-vlm-not-configured",
                        null, "Local VLM not configured"),
                false, "Screenshot capture failed: headless");

        String json = mapper.writeValueAsString(ctx);
        ScreenContextResult back = mapper.readValue(json, ScreenContextResult.class);

        assertThat(back.success()).isFalse();
        assertThat(back.error()).contains("Screenshot capture failed");
        assertThat(back.analysis().success()).isFalse();
        assertThat(back.regions()).isEmpty();
        assertThat(back.uiElements()).isEmpty();
        assertThat(back.objects()).isEmpty();
        assertThat(back.detection().uiAvailability()).isEqualTo("NOT_CONFIGURED");
    }
}
