package org.jarvis.voicegateway.service;

import com.sun.net.httpserver.HttpServer;
import org.jarvis.voicegateway.audio.CanonicalWavAudio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers TtsService branches not already exercised by TtsServiceTest / TtsServiceAdvancedTest:
 * the {@code synthesize()} cache-facing entry point, Piper JSON string escaping, the
 * normalizeEspeakAudio() failure fallback, checkPiperAvailable()'s non-200 health branch,
 * espeakCandidates() deduplication and espeakUnavailableReason()'s two messages.
 */
class TtsServiceGapsTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== synthesize() ====================

    @Test
    void synthesizeDelegatesToSynthesizeDetailedAndReturnsAudioData() throws Exception {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "espeak");
        ReflectionTestUtils.setField(service, "espeakAvailable", true);
        ReflectionTestUtils.setField(service, "espeakBinary", fakeEspeakScript(0, canonicalWavFixture()).toString());

        byte[] audio = service.synthesize("Hello there", "ru-RU", "voice", 1.0, 0.0);

        assertTrue(audio.length > 0);
        assertTrue(CanonicalWavAudio.inspect(audio).valid());
    }

    // ==================== jsonString() escaping (via buildPiperRequestBody) ====================

    @Test
    void piperRequestBodyEscapesQuotesAndBackslashes() {
        String textWithQuotesAndBackslash = "say " + '"' + "hi" + '"' + '\\' + "done";

        String body = (String) ReflectionTestUtils.invokeMethod(
                TtsService.class, "buildPiperRequestBody", textWithQuotesAndBackslash, "ru-RU", null);

        String expectedTextValue = "say \\\"hi\\\"\\\\done";
        assertEquals("{\"text\":\"" + expectedTextValue + "\",\"language\":\"ru-RU\"}", body);
    }

    @Test
    void piperRequestBodyEscapesNewlineTabAndCarriageReturn() {
        String textWithWhitespaceControls = "line1" + '\n' + "line2" + '\t' + "end" + '\r';

        String body = (String) ReflectionTestUtils.invokeMethod(
                TtsService.class, "buildPiperRequestBody", textWithWhitespaceControls, "en-US", null);

        assertEquals("{\"text\":\"line1\\nline2\\tend\\r\",\"language\":\"en-US\"}", body);
    }

    @Test
    void piperRequestBodyEscapesLowControlCharacterAsUnicode() {
        // Built via explicit char concatenation (rather than an in-source escape sequence) so
        // the raw control byte the test depends on is unambiguous when reading this file.
        char bellControlChar = (char) 7;
        String textWithControlChar = "bell" + bellControlChar;

        String body = (String) ReflectionTestUtils.invokeMethod(
                TtsService.class, "buildPiperRequestBody", textWithControlChar, "en-US", null);

        assertEquals("{\"text\":\"bell\\u0007\",\"language\":\"en-US\"}", body);
    }

    // ==================== normalizeEspeakAudio() ====================

    @Test
    void normalizeEspeakAudioReturnsRawBytesWhenNormalizationFails() {
        TtsService service = new TtsService();
        byte[] garbage = {1, 2, 3, 4, 5};

        byte[] result = (byte[]) ReflectionTestUtils.invokeMethod(service, "normalizeEspeakAudio", (Object) garbage);

        assertArrayEquals(garbage, result);
    }

    @Test
    void normalizeEspeakAudioReturnsCanonicalBytesWhenInputIsAlreadyValidWav() throws Exception {
        TtsService service = new TtsService();
        byte[] valid = canonicalWavFixture();

        byte[] result = (byte[]) ReflectionTestUtils.invokeMethod(service, "normalizeEspeakAudio", (Object) valid);

        assertTrue(CanonicalWavAudio.inspect(result).valid());
    }

    // ==================== checkPiperAvailable() ====================

    @Test
    void checkPiperAvailableReportsUnavailableWhenHealthEndpointReturnsNon200() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "piperUrl", "http://127.0.0.1:" + server.getAddress().getPort());

        boolean available = (Boolean) ReflectionTestUtils.invokeMethod(service, "checkPiperAvailable");

        assertFalse(available);
        String status = (String) ReflectionTestUtils.getField(service, "piperInitStatus");
        assertTrue(status.contains("503"));
    }

    @Test
    void checkPiperAvailableReportsUnavailableWhenUrlBlank() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "piperUrl", "   ");

        boolean available = (Boolean) ReflectionTestUtils.invokeMethod(service, "checkPiperAvailable");

        assertFalse(available);
        String status = (String) ReflectionTestUtils.getField(service, "piperInitStatus");
        assertTrue(status.contains("not configured"));
    }

    // ==================== espeakCandidates() ====================

    @Test
    void espeakCandidatesDeduplicatesConfiguredPathAgainstBuiltins() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "configuredEspeakBinaryPath", "espeak");

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) ReflectionTestUtils.invokeMethod(service, "espeakCandidates");

        assertEquals(List.of("espeak", "espeak-ng"), candidates);
    }

    @Test
    void espeakCandidatesOmitsConfiguredPathWhenBlank() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "configuredEspeakBinaryPath", "   ");

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) ReflectionTestUtils.invokeMethod(service, "espeakCandidates");

        assertEquals(List.of("espeak-ng", "espeak"), candidates);
    }

    @Test
    void espeakCandidatesIncludesConfiguredPathFirstWhenDistinct() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "configuredEspeakBinaryPath", "/opt/tools/espeak-custom");

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) ReflectionTestUtils.invokeMethod(service, "espeakCandidates");

        assertEquals(List.of("/opt/tools/espeak-custom", "espeak-ng", "espeak"), candidates);
    }

    // ==================== espeakUnavailableReason() ====================

    @Test
    void espeakUnavailableReasonMentionsConfiguredPathWhenSet() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "configuredEspeakBinaryPath", "/opt/tools/espeak-custom");

        String reason = (String) ReflectionTestUtils.invokeMethod(service, "espeakUnavailableReason");

        assertTrue(reason.contains("/opt/tools/espeak-custom"));
    }

    @Test
    void espeakUnavailableReasonSuggestsSetupScriptWhenPathNotConfigured() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "configuredEspeakBinaryPath", "");

        String reason = (String) ReflectionTestUtils.invokeMethod(service, "espeakUnavailableReason");

        assertTrue(reason.contains("setup-voice-local.sh"));
    }

    // ==================== fixtures ====================

    private static byte[] canonicalWavFixture() throws Exception {
        byte[] pcm = new byte[640];
        AudioFormat format = CanonicalWavAudio.canonicalFormat();
        try (AudioInputStream audioInputStream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize());
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            javax.sound.sampled.AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        }
    }

    private Path fakeEspeakScript(int exitCode, byte[] fixtureWav) throws Exception {
        Path fixture = tempDir.resolve("fixture-" + System.nanoTime() + ".wav");
        Files.write(fixture, fixtureWav);

        Path script = tempDir.resolve("fake-espeak-" + System.nanoTime() + ".sh");
        String content = "#!/bin/sh\n"
                + "wav=\"\"\n"
                + "prev=\"\"\n"
                + "for arg in \"$@\"; do\n"
                + "  if [ \"$prev\" = \"-w\" ]; then wav=\"$arg\"; fi\n"
                + "  prev=\"$arg\"\n"
                + "done\n"
                + "if [ -n \"$wav\" ]; then cp \"" + fixture + "\" \"$wav\"; fi\n"
                + "exit " + exitCode + "\n";
        Files.writeString(script, content, StandardCharsets.US_ASCII);

        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, perms);
        return script;
    }
}
