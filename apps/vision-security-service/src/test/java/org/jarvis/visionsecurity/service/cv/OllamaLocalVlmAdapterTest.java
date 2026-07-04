package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaLocalVlmAdapterTest {

    private final VisionSecurityProperties.Vlm config = baseConfig();

    private static VisionSecurityProperties.Vlm baseConfig() {
        VisionSecurityProperties.Vlm v = new VisionSecurityProperties.Vlm();
        v.setEnabled(true);
        v.setProvider("ollama");
        v.setEndpoint("http://localhost:11434");
        v.setModel("llava");
        v.setTimeout(Duration.ofSeconds(5));
        return v;
    }

    @Test
    void answerReturnsReadyWhenBackendRespondsWithJson(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"response\":\"A code editor is open.\"}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, client);

        LocalVlmAdapter.VlmResult result = adapter.answer("what is on screen?", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.READY);
        assertThat(result.summary()).isEqualTo("A code editor is open.");
        assertThat(result.engine()).isEqualTo("ollama");
        assertThat(result.error()).isNull();
    }

    @Test
    void answerReturnsUnavailableWhenBackendReturnsNon2xx(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("boom");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("HTTP 500").contains("boom");
        assertThat(result.summary()).isNull();
    }

    @Test
    void answerReturnsUnavailableWhenBackendUnreachable(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        doThrow(new ConnectException("Connection refused"))
                .when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("cannot connect").contains("Connection refused");
    }

    @Test
    void answerReturnsUnavailableWhenImageMissing() {
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.answer("q",
                Path.of("/no/such/file.png"), sampleContext(Path.of("/no/such/file.png")));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("image file not found");
    }

    @Test
    void answerReturnsUnavailableWhenEndpointBlank(@TempDir Path tmp) throws Exception {
        VisionSecurityProperties.Vlm bad = baseConfig();
        bad.setEndpoint("");
        Path image = writePng(tmp.resolve("img.png"));
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(bad, mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("vlm.endpoint is empty");
    }

    @Test
    void constructorRejectsObviouslyNonLocalEndpoints() {
        VisionSecurityProperties.Vlm bad = baseConfig();
        bad.setEndpoint("https://api.openai.com/v1");

        assertThatThrownBy(() -> new OllamaLocalVlmAdapter(bad, mock(HttpClient.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-local host")
                .hasMessageContaining("openai");
    }

    @Test
    void requestUsesConfiguredModelAndContainsBase64Image(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"response\":\"ok\"}");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(response).when(client).send(captor.capture(), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, client);

        adapter.answer("what is this?", image, sampleContext(image));

        HttpRequest req = captor.getValue();
        assertThat(req.uri().toString()).isEqualTo("http://localhost:11434/api/generate");
        String body = captureBody(req);
        assertThat(body).contains("\"model\":\"llava\"");
        assertThat(body).contains("\"images\":[");
        assertThat(body).contains("\"stream\":false");
    }

    private static String captureBody(HttpRequest req) {
        // BodyPublishers.ofString stores the payload in a private subscriber; the
        // simplest way to recover the string for assertion is via the publisher's
        // contentLength()-based read. We avoid that complexity by reusing
        // a small helper: pull bytes via a subscriber.
        StringBuilder sink = new StringBuilder();
        req.bodyPublisher().ifPresent(pub -> pub.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override
            public void onNext(java.nio.ByteBuffer buf) {
                byte[] arr = new byte[buf.remaining()];
                buf.get(arr);
                sink.append(new String(arr));
            }
            @Override
            public void onError(Throwable throwable) { /* test helper */ }
            @Override
            public void onComplete() { /* test helper */ }
        }));
        return sink.toString();
    }

    private static Path writePng(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", target.toFile());
        return target;
    }

    private static CvAnalysisResult sampleContext(Path imagePath) {
        return new CvAnalysisResult(
                "screenshot", imagePath.toString(), 64, 64,
                "some OCR text", List.of(),
                "tesseract", "eng", 5L,
                Instant.parse("2026-05-25T10:00:00Z"),
                true, null);
    }
}
