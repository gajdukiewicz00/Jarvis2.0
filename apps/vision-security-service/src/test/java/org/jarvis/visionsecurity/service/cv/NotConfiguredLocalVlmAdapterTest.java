package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotConfiguredLocalVlmAdapterTest {

    private final NotConfiguredLocalVlmAdapter adapter = new NotConfiguredLocalVlmAdapter();

    @Test
    void alwaysReturnsNotConfiguredAvailability() {
        CvAnalysisResult context = new CvAnalysisResult(
                "file", "/x.png", 100, 100, "hello",
                List.of(), "tesseract", "eng", 5L, Instant.now(),
                true, null);

        LocalVlmAdapter.VlmResult result = adapter.summarise(context);

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.NOT_CONFIGURED);
        assertThat(result.summary()).isNull();
        assertThat(result.error()).contains("Local VLM not configured");
        assertThat(result.error()).doesNotContainIgnoringCase("openai")
                .doesNotContainIgnoringCase("gemini")
                .doesNotContainIgnoringCase("claude");
        assertThat(result.engine()).isEqualTo(NotConfiguredLocalVlmAdapter.ID);
    }

    @Test
    void neverFabricatesASummary() {
        CvAnalysisResult success = new CvAnalysisResult(
                "screenshot", "/s.png", 1920, 1080,
                "rich text content " + "x".repeat(500),
                List.of(), "tesseract", "eng", 1234L, Instant.now(),
                true, null);

        LocalVlmAdapter.VlmResult result = adapter.summarise(success);

        assertThat(result.summary()).isNull();
        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.NOT_CONFIGURED);
    }
}
