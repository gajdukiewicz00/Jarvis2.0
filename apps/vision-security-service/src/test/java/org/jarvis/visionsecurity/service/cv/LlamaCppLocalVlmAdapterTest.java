package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlamaCppLocalVlmAdapterTest {

    private static VisionSecurityProperties.Vlm baseConfig() {
        VisionSecurityProperties.Vlm v = new VisionSecurityProperties.Vlm();
        v.setEnabled(true);
        v.setProvider("llamacpp");
        v.setEndpoint("http://127.0.0.1:8080");
        v.setModel("llava-mistral-7b");
        v.setTimeout(Duration.ofSeconds(5));
        return v;
    }

    @Test
    void answerReturnsReadyWhenServerRespondsWithOpenAiBody(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"You are looking at a terminal.\"}}]}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("what is this?", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.READY);
        assertThat(result.summary()).isEqualTo("You are looking at a terminal.");
        assertThat(result.engine()).isEqualTo("llamacpp");
    }

    @Test
    void requestPostsImageUrlAsBase64DataUri(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        doReturn(response).when(client).send(captor.capture(), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        adapter.answer("q", image, sampleContext(image));

        HttpRequest req = captor.getValue();
        assertThat(req.uri().toString()).isEqualTo("http://127.0.0.1:8080/v1/chat/completions");
        String body = captureBody(req);
        assertThat(body).contains("\"image_url\"")
                .contains("data:image/png;base64,")
                .contains("\"model\":\"llava-mistral-7b\"");
    }

    @Test
    void answerReturnsUnavailableWhenChoicesEmpty(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[]}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("no choices");
    }

    @Test
    void constructorRejectsCloudHosts() {
        VisionSecurityProperties.Vlm bad = baseConfig();
        bad.setEndpoint("https://generativelanguage.googleapis.com");
        assertThatThrownBy(() -> new LlamaCppLocalVlmAdapter(bad, mock(HttpClient.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-local host");
    }

    private static String captureBody(HttpRequest req) {
        StringBuilder sink = new StringBuilder();
        req.bodyPublisher().ifPresent(pub -> pub.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer buf) {
                byte[] arr = new byte[buf.remaining()];
                buf.get(arr);
                sink.append(new String(arr));
            }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
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
