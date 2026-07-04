package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the {@code include-ocr-context} / {@code include-screenshot}
 * VLM knobs, plus the local-only / no-cloud guarantees.
 */
class VlmContextOptionsTest {

    private final CvAnalysisResult context = new CvAnalysisResult(
            "screenshot", "/tmp/x.png", 64, 64, "SECRET OCR TEXT", List.of(),
            "tesseract", "eng", 5L, Instant.parse("2026-05-25T10:00:00Z"), true, null);

    @Test
    void buildPromptIncludesOcrWhenEnabled() {
        String prompt = OllamaLocalVlmAdapter.buildPrompt("what?", context, true);
        assertThat(prompt).contains("OCR text:").contains("SECRET OCR TEXT");
    }

    @Test
    void buildPromptOmitsOcrWhenDisabled() {
        String prompt = OllamaLocalVlmAdapter.buildPrompt("what?", context, false);
        assertThat(prompt).doesNotContain("OCR text:").doesNotContain("SECRET OCR TEXT");
        assertThat(prompt).contains("Question: what?");
    }

    @Test
    void includeScreenshotFalseSendsNoImageBytesAndRunsTextOnly() throws Exception {
        VisionSecurityProperties.Vlm cfg = baseConfig();
        cfg.setIncludeScreenshot(false);

        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"response\":\"text-only answer\"}");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(response).when(client).send(captor.capture(), any());

        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(cfg, client);
        // No image path at all — text-only must still succeed.
        LocalVlmAdapter.VlmResult result = adapter.answer("what?", null, context);

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.READY);
        assertThat(result.summary()).isEqualTo("text-only answer");
        String body = OllamaBodyReader.read(captor.getValue());
        assertThat(body).contains("\"images\":[]");
    }

    @Test
    void defaultConfigIsLocalOnly() {
        VisionSecurityProperties.Vlm cfg = new VisionSecurityProperties.Vlm();
        assertThat(cfg.isEnabled()).isFalse();
        assertThat(cfg.getProvider()).isEqualTo("disabled");
        assertThat(cfg.getEndpoint()).isEmpty();
        assertThat(cfg.isIncludeOcrContext()).isTrue();
        assertThat(cfg.isIncludeScreenshot()).isTrue();
    }

    @Test
    void guardRejectsKnownCloudHostsButAllowsLoopback() {
        // Loopback / private endpoints are fine.
        OllamaLocalVlmAdapter.guardLocalOnly("http://127.0.0.1:11434");
        OllamaLocalVlmAdapter.guardLocalOnly("http://localhost:8080");
        OllamaLocalVlmAdapter.guardLocalOnly("");

        for (String cloud : List.of(
                "https://api.openai.com/v1",
                "https://generativelanguage.googleapis.com",
                "https://api.anthropic.com",
                "https://huggingface.co/models")) {
            try {
                OllamaLocalVlmAdapter.guardLocalOnly(cloud);
                throw new AssertionError("expected rejection for " + cloud);
            } catch (IllegalArgumentException expected) {
                assertThat(expected).hasMessageContaining("non-local host");
            }
        }
    }

    private static VisionSecurityProperties.Vlm baseConfig() {
        VisionSecurityProperties.Vlm v = new VisionSecurityProperties.Vlm();
        v.setEnabled(true);
        v.setProvider("ollama");
        v.setEndpoint("http://localhost:11434");
        v.setModel("llava");
        v.setTimeout(Duration.ofSeconds(5));
        return v;
    }

    /** Recovers the request body string from a BodyPublisher for assertions. */
    static final class OllamaBodyReader {
        static String read(HttpRequest req) {
            StringBuilder sink = new StringBuilder();
            req.bodyPublisher().ifPresent(pub -> pub.subscribe(
                    new java.util.concurrent.Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                            s.request(Long.MAX_VALUE);
                        }
                        @Override
                        public void onNext(java.nio.ByteBuffer buf) {
                            byte[] arr = new byte[buf.remaining()];
                            buf.get(arr);
                            sink.append(new String(arr));
                        }
                        @Override
                        public void onError(Throwable t) { }
                        @Override
                        public void onComplete() { }
                    }));
            return sink.toString();
        }
    }
}
