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
 * Extra {@link LlamaCppLocalVlmAdapter} branches not covered by
 * {@link LlamaCppLocalVlmAdapterTest}: {@code id()}/{@code model()},
 * {@code summarise()}, endpoint-blank, no-image / text-only, invalid endpoint
 * syntax, empty message content, timeout / connect / generic-exception
 * branches, {@code guessMimeType} variants, and the {@code @Autowired}
 * constructor (which exercises {@code defaultHttpClient}).
 */
class LlamaCppLocalVlmAdapterBranchesTest {

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
    void idAndModelExposeConfiguredValues() {
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), mock(HttpClient.class));

        assertThat(adapter.id()).isEqualTo("llamacpp");
        assertThat(adapter.model()).isEqualTo("llava-mistral-7b");
    }

    @Test
    void summariseDelegatesToInvokeAndReadsImagePathFromContext(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"A terminal window is open.\"}}]}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.summarise(sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.READY);
        assertThat(result.summary()).isEqualTo("A terminal window is open.");
    }

    @Test
    void summariseHandlesNullContextByPassingNullImagePath() {
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.summarise(null);

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("no image to send to VLM");
    }

    @Test
    void answerReturnsUnavailableWhenEndpointBlank(@TempDir Path tmp) throws Exception {
        VisionSecurityProperties.Vlm config = baseConfig();
        config.setEndpoint("");
        Path image = writePng(tmp.resolve("img.png"));
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(config, mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("vlm.endpoint is empty");
    }

    @Test
    void answerRunsTextOnlyWhenScreenshotAttachmentDisabled() throws Exception {
        VisionSecurityProperties.Vlm config = baseConfig();
        config.setIncludeScreenshot(false);
        config.setModel(null);
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"text only answer\"}}]}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(config, client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", null, sampleContext(Path.of("/no/such.png")));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.READY);
        assertThat(result.summary()).isEqualTo("text only answer");
    }

    @Test
    void answerReturnsUnavailableWhenImagePathIsNotRegularFile(@TempDir Path tmp) throws Exception {
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.answer("q", tmp, sampleContext(tmp));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("image file not found");
    }

    @Test
    void answerReturnsUnavailableWhenEndpointHasInvalidUriSyntax(@TempDir Path tmp) throws Exception {
        VisionSecurityProperties.Vlm config = baseConfig();
        config.setEndpoint("http://127.0.0.1:8080/bad uri");
        Path image = writePng(tmp.resolve("img.png"));
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(config, mock(HttpClient.class));

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("invalid vlm.endpoint");
    }

    @Test
    void answerReturnsUnavailableWhenHttpStatusIsNon2xx(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenReturn("server overloaded");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("HTTP 503").contains("server overloaded");
    }

    @Test
    void answerReturnsUnavailableWhenMessageContentIsEmpty(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("empty message.content");
    }

    @Test
    void answerReturnsUnavailableWhenCallTimesOut(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        doThrow(new java.net.http.HttpTimeoutException("timed out"))
                .when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("timed out");
    }

    @Test
    void answerReturnsUnavailableWhenConnectionRefused(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        doThrow(new java.net.ConnectException("Connection refused"))
                .when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("cannot connect").contains("Connection refused");
    }

    @Test
    void answerReturnsUnavailableOnUnexpectedException(@TempDir Path tmp) throws Exception {
        Path image = writePng(tmp.resolve("img.png"));
        HttpClient client = mock(HttpClient.class);
        doThrow(new RuntimeException("boom")).when(client).send(any(HttpRequest.class), any());
        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(baseConfig(), client);

        LocalVlmAdapter.VlmResult result = adapter.answer("q", image, sampleContext(image));

        assertThat(result.availability()).isEqualTo(LocalVlmAdapter.Availability.UNAVAILABLE);
        assertThat(result.error()).contains("RuntimeException").contains("boom");
    }

    @Test
    void guessMimeTypeCoversAllKnownExtensions() {
        assertThat(LlamaCppLocalVlmAdapter.guessMimeType(Path.of("a.jpg"))).isEqualTo("image/jpeg");
        assertThat(LlamaCppLocalVlmAdapter.guessMimeType(Path.of("a.jpeg"))).isEqualTo("image/jpeg");
        assertThat(LlamaCppLocalVlmAdapter.guessMimeType(Path.of("a.webp"))).isEqualTo("image/webp");
        assertThat(LlamaCppLocalVlmAdapter.guessMimeType(Path.of("a.gif"))).isEqualTo("image/gif");
        assertThat(LlamaCppLocalVlmAdapter.guessMimeType(Path.of("a.png"))).isEqualTo("image/png");
        assertThat(LlamaCppLocalVlmAdapter.guessMimeType(Path.of("a.unknown"))).isEqualTo("image/png");
    }

    @Test
    void autowiredConstructorBuildsDefaultHttpClientFromProperties() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getCv().getVlm().setEndpoint("http://127.0.0.1:8080");
        properties.getCv().getVlm().setModel("llava");
        properties.getCv().getVlm().setTimeout(null);

        LlamaCppLocalVlmAdapter adapter = new LlamaCppLocalVlmAdapter(properties);

        assertThat(adapter.id()).isEqualTo("llamacpp");
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
