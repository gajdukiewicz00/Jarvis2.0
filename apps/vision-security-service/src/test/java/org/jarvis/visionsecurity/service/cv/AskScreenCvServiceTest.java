package org.jarvis.visionsecurity.service.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.AskScreenResult;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.CvBlock;
import org.jarvis.visionsecurity.model.RectBox;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AskScreenCvServiceTest {

    private final VisionSecurityProperties properties = baseProperties();

    private static VisionSecurityProperties baseProperties() {
        VisionSecurityProperties p = new VisionSecurityProperties();
        p.getCv().getVlm().setEnabled(true);
        p.getCv().getVlm().setProvider("ollama");
        p.getCv().getVlm().setEndpoint("http://localhost:11434");
        p.getCv().getVlm().setModel("llava");
        return p;
    }

    @Test
    void disabledVlmReturnsNotConfiguredButScreenContextStillSucceeds() {
        ScreenContextCvService screen = mock(ScreenContextCvService.class);
        ScreenContextResult ctx = successCtx();
        when(screen.capture("owner", null)).thenReturn(ctx);
        LocalVlmAdapter vlm = new NotConfiguredLocalVlmAdapter();
        AskScreenCvService service = new AskScreenCvService(screen, vlm, properties);

        AskScreenResult result = service.ask("owner", "what is on screen?", null);

        assertThat(result.success()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.vlm().availability()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.vlm().error()).contains("Local VLM not configured");
        assertThat(result.screenContext()).isSameAs(ctx);
        assertThat(result.question()).isEqualTo("what is on screen?");
    }

    @Test
    void unavailableVlmReturnsUnavailableAndPropagatesError() {
        ScreenContextCvService screen = mock(ScreenContextCvService.class);
        when(screen.capture(anyString(), any())).thenReturn(successCtx());
        LocalVlmAdapter vlm = mock(LocalVlmAdapter.class);
        when(vlm.id()).thenReturn("ollama");
        when(vlm.model()).thenReturn("llava");
        when(vlm.answer(anyString(), any(), any())).thenReturn(
                LocalVlmAdapter.VlmResult.unavailable("ollama",
                        "cannot connect to local Ollama", 12L));
        AskScreenCvService service = new AskScreenCvService(screen, vlm, properties);

        AskScreenResult result = service.ask("owner", "q", null);

        assertThat(result.success()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.vlm().availability()).isEqualTo("UNAVAILABLE");
        assertThat(result.vlm().error()).contains("cannot connect");
        assertThat(result.vlm().provider()).isEqualTo("ollama");
        assertThat(result.vlm().model()).isEqualTo("llava");
    }

    @Test
    void readyVlmReturnsAnswerVerbatim() {
        ScreenContextCvService screen = mock(ScreenContextCvService.class);
        when(screen.capture(anyString(), any())).thenReturn(successCtx());
        LocalVlmAdapter vlm = mock(LocalVlmAdapter.class);
        when(vlm.id()).thenReturn("ollama");
        when(vlm.model()).thenReturn("llava");
        when(vlm.answer(anyString(), any(), any())).thenReturn(
                LocalVlmAdapter.VlmResult.success("ollama",
                        "A terminal window showing OCR output.", 110L));
        AskScreenCvService service = new AskScreenCvService(screen, vlm, properties);

        AskScreenResult result = service.ask("owner", "what do you see?", null);

        assertThat(result.success()).isTrue();
        assertThat(result.answer()).isEqualTo("A terminal window showing OCR output.");
        assertThat(result.vlm().availability()).isEqualTo("READY");
        assertThat(result.vlm().error()).isNull();
        assertThat(result.vlm().durationMs()).isEqualTo(110L);
    }

    @Test
    void screenContextFailurePropagatesAndMarksFailure() {
        ScreenContextCvService screen = mock(ScreenContextCvService.class);
        when(screen.capture(anyString(), any())).thenReturn(failedCtx());
        AskScreenCvService service = new AskScreenCvService(screen,
                new NotConfiguredLocalVlmAdapter(), properties);

        AskScreenResult result = service.ask("owner", "q", null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Screenshot capture failed");
        assertThat(result.vlm().availability()).isEqualTo("UNAVAILABLE");
        assertThat(result.vlm().error()).contains("screen-context capture failed");
    }

    @Test
    void askScreenResultRoundTripsThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AskScreenResult result = new AskScreenResult(
                "what is on screen?",
                "Code editor",
                successCtx(),
                new AskScreenResult.VlmInfo("ollama", "llava", "READY", 110L, null),
                Instant.parse("2026-05-25T10:00:00Z"),
                123L,
                true,
                null);

        String json = mapper.writeValueAsString(result);
        AskScreenResult parsed = mapper.readValue(json, AskScreenResult.class);

        assertThat(parsed.question()).isEqualTo("what is on screen?");
        assertThat(parsed.answer()).isEqualTo("Code editor");
        assertThat(parsed.success()).isTrue();
        assertThat(parsed.vlm().provider()).isEqualTo("ollama");
        assertThat(parsed.vlm().availability()).isEqualTo("READY");
        assertThat(parsed.screenContext().analysis().ocrText()).isEqualTo("hello");
    }

    private static ScreenContextResult successCtx() {
        CvAnalysisResult analysis = new CvAnalysisResult(
                "screenshot", "/tmp/x.png", 800, 600, "hello",
                List.of(new CvBlock("hello", 95.0, new RectBox(0, 0, 50, 30))),
                "tesseract", "eng", 50L,
                Instant.parse("2026-05-25T10:00:00Z"), true, null);
        return new ScreenContextResult(
                "owner", Instant.parse("2026-05-25T10:00:00Z"), 5L,
                "/tmp/x.png", "x11", "Term", "term", List.of("DEV"),
                List.of(new RectBox(0, 0, 100, 100)),
                List.of(), List.of(),
                new ScreenContextResult.DetectionSection("NOT_CONFIGURED", "NOT_CONFIGURED"),
                analysis,
                new ScreenContextResult.VlmSection("NOT_CONFIGURED",
                        "local-vlm-not-configured", null, "Local VLM not configured"),
                true, null);
    }

    private static ScreenContextResult failedCtx() {
        CvAnalysisResult analysis = new CvAnalysisResult(
                "screenshot", null, null, null, "", List.of(),
                "tesseract", "eng", 0L,
                Instant.parse("2026-05-25T10:00:00Z"),
                false, "Screenshot capture failed: headless");
        return new ScreenContextResult(
                "owner", Instant.parse("2026-05-25T10:00:00Z"), 0L,
                null, "headless", "", "", List.of(), List.of(),
                List.of(), List.of(),
                new ScreenContextResult.DetectionSection("NOT_CONFIGURED", "NOT_CONFIGURED"),
                analysis,
                new ScreenContextResult.VlmSection("NOT_CONFIGURED",
                        "local-vlm-not-configured", null, "Local VLM not configured"),
                false, "Screenshot capture failed: headless");
    }
}
