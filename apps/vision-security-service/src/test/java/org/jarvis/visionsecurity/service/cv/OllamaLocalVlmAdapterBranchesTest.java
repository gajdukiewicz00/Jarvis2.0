package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extra {@link OllamaLocalVlmAdapter} branches not covered by
 * {@link OllamaLocalVlmAdapterTest}: {@code id()}/{@code model()}, {@code summarise()},
 * blank model, no-image-attached path, invalid endpoint syntax, empty response body,
 * timeout / generic-exception branches, and the {@code @Autowired} constructor
 * (which exercises {@code defaultHttpClient}).
 */
class OllamaLocalVlmAdapterBranchesTest {

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
    void idAndModelExposeConfiguredValues() {
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(baseConfig(), mock(HttpClient.class));

        assertThat(adapter.id()).isEqualTo("ollama");
        assertThat(adapter.model()).isEqualTo("llava");
    }

    @Test
    void summariseDelegatesToInvokeAndReadsImagePathFromContext(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"response\":\"A code editor is open.\"}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.summarise(sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.READY);
        assertThat(result.summary()).isEqualTo("A code editor is open.");
    }

    @Test
    void summariseHandlesNullContextByPassingNullImagePath() {
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(baseConfig(), mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.summarise(null);

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("no image to send to VLM");
    }

    @Test
    void answerReturnsUnavailableWhenModelBlank(@TempDir Path tmp) throws Exception {
        VisionSecurityProperties.Vlm config = baseConfig();
        config.setModel("  ");
        Path image = writePng(tmp.resolve("img.png"));
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("vlm.model is empty");
    }

    @Test
    void answerRunsTextOnlyWhenScreenshotAttachmentDisabled() throws Exception {
        VisionSecurityProperties.Vlm config = baseConfig();
        config.setIncludeScreenshot(false);
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"response\":\"text only answer\"}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", null, sampleContext(Path.of("/no/such.png")));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.READY);
        assertThat(result.summary()).isEqualTo("text only answer");
    }

    @Test
    void answerReturnsUnavailableWhenEndpointHasInvalidUriSyntax(@TempDir Path tmp) throws Exception {
        VisionSecurityProperties.Vlm config = baseConfig();
        config.setEndpoint("http://localhost:11434/bad uri");
        Path image = writePng(tmp.resolve("img.png"));
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(config, mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("invalid vlm.endpoint");
    }

    @Test
    void answerReturnsUnavailableWhenResponseFieldIsEmpty(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"response\":\"\"}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("empty 'response' field");
    }

    @Test
    void answerReturnsUnavailableWhenCallTimesOut(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        doThrow(new java.net.http.HttpTimeoutException("timed out"))
                .when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("timed out");
    }

    @Test
    void answerReturnsUnavailableOnUnexpectedException(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        doThrow(new RuntimeException("boom")).when(client).send(any(HttpRequest.class), any());
        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("RuntimeException").contains("boom");
    }

    @Test
    void autowiredConstructorBuildsDefaultHttpClientFromProperties() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getCv().getVlm().setEndpoint("http://127.0.0.1:11434");
        properties.getCv().getVlm().setModel("llava");
        properties.getCv().getVlm().setTimeout(null);

        OllamaLocalVlmAdapter adapter = new OllamaLocalVlmAdapter(properties);

        assertThat(adapter.id()).isEqualTo("ollama");
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
