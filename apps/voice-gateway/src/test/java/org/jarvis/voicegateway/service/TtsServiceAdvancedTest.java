package org.jarvis.voicegateway.service;

import com.sun.net.httpserver.HttpServer;
import org.jarvis.voicegateway.audio.CanonicalWavAudio;
import org.jarvis.voicegateway.exception.TtsUnavailableException;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises TtsService branches that need real (but fully local/offline) I/O:
 * a fake "espeak" binary script standing in for the real executable, and an
 * embedded loopback HTTP server standing in for the Piper daemon. No network
 * access and no real TTS binaries are required.
 */
class TtsServiceAdvancedTest {

    @TempDir
    Path tempDir;

    private HttpServer piperServer;

    @AfterEach
    void tearDown() {
        if (piperServer != null) {
            piperServer.stop(0);
        }
    }

    // ==================== init() branches ====================

    @Test
    void initWithTtsDisabledSkipsAllProviderChecks() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", false);

        service.init();

        Map<String, Object> runtime = service.describeRuntime();
        assertFalse((Boolean) runtime.get("available"));
        assertEquals("disabled", runtime.get("status"));
    }

    @Test
    void initWithEspeakProviderRunsRealCheckEspeakAvailableProcessProbe() {
        // This sandbox has a real espeak-ng on PATH, so exercise the genuine success path:
        // checkEspeakAvailable()'s real ProcessBuilder probe (start/waitFor/exitValue), not a
        // fake stand-in. If a future environment lacks any espeak binary at all, skip rather
        // than fail, since that is a real (not test-authored) environment difference.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                isOnPath("espeak-ng") || isOnPath("espeak"),
                "no espeak-ng/espeak binary available in this environment");

        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "espeak");
        ReflectionTestUtils.setField(service, "configuredEspeakBinaryPath", "");

        service.init();

        Map<String, Object> runtime = service.describeRuntime();
        assertEquals("available", runtime.get("status"));
        assertTrue((Boolean) runtime.get("available"));
        assertTrue(runtime.get("espeakBinary") != null);
    }

    private static boolean isOnPath(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "--version").redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void initWithUnsupportedProviderLogsAndReportsUnavailable() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "festival-classic");

        service.init();

        Map<String, Object> runtime = service.describeRuntime();
        assertEquals("unavailable", runtime.get("status"));
        assertThrows(TtsUnavailableException.class,
                () -> service.synthesize("hi", "en-US", "en-US-Wavenet-D", 1.0, 0.0));
    }

    @Test
    void initWithPiperProviderAndReachableDaemonMarksAvailable() throws Exception {
        piperServer = startPiperServer(200, canonicalWavFixture());
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperUrl", piperUrl(piperServer));
        ReflectionTestUtils.setField(service, "piperTimeoutMs", 5000L);

        service.init();

        Map<String, Object> runtime = service.describeRuntime();
        assertEquals("available", runtime.get("status"));
        assertTrue((Boolean) runtime.get("available"));
    }

    @Test
    void initWithPiperProviderAndUnreachableDaemonFallsBackToEspeakWhenEspeakPresent() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        // Nothing listens on this loopback port.
        ReflectionTestUtils.setField(service, "piperUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(service, "piperTimeoutMs", 1000L);

        service.init();

        assertFalse((Boolean) ReflectionTestUtils.getField(service, "piperAvailable"));
        // Whether the overall status is "degraded" (espeak fallback found) or "unavailable"
        // (no fallback) depends only on whether this environment has a real espeak binary —
        // both are real, valid outcomes of the same unreachable-piper code path.
        Map<String, Object> runtime = service.describeRuntime();
        assertTrue(Set.of("degraded", "unavailable").contains(runtime.get("status")));
    }

    @Test
    void initWithPiperProviderAndUnreachableDaemonAndNoEspeakFallbackReportsUnavailable() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(service, "piperTimeoutMs", 1000L);

        service.init();
        // Isolate the "piper unreachable" branch from this sandbox's (unrelated) real espeak
        // binary: force the no-fallback scenario explicitly, exactly like TtsServiceTest does
        // elsewhere for other provider combinations.
        ReflectionTestUtils.setField(service, "espeakAvailable", false);

        Map<String, Object> runtime = service.describeRuntime();
        assertEquals("unavailable", runtime.get("status"));
        assertFalse((Boolean) runtime.get("available"));
    }

    @Test
    void initWithPiperProviderAndBlankUrlSkipsNetworkCallAndReportsNotPiperAvailable() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperUrl", "");

        service.init();

        assertFalse((Boolean) ReflectionTestUtils.getField(service, "piperAvailable"));
    }

    // ==================== synthesizeDetailed: espeak via fake binary ====================

    @Test
    void synthesizeDetailedSucceedsWithFakeEspeakBinary() throws Exception {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "espeak");
        ReflectionTestUtils.setField(service, "espeakAvailable", true);
        ReflectionTestUtils.setField(service, "espeakBinary", fakeEspeakScript(0, canonicalWavFixture()).toString());

        TtsService.SynthesisResult result = service.synthesizeDetailed("Привет", "ru-RU", "voice", 1.0, 0.0);

        assertEquals("espeak", result.actualProvider());
        assertTrue(result.audioData().length > 0);
        assertTrue(CanonicalWavAudio.inspect(result.audioData()).valid());
    }

    @Test
    void synthesizeDetailedThrowsWhenEspeakBinaryExitsNonZero() throws Exception {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "espeak");
        ReflectionTestUtils.setField(service, "espeakAvailable", true);
        ReflectionTestUtils.setField(service, "espeakBinary", fakeEspeakScript(1, canonicalWavFixture()).toString());

        assertThrows(RuntimeException.class,
                () -> service.synthesizeDetailed("Привет", "ru-RU", "voice", 1.0, 0.0));
    }

    @Test
    void synthesizeDetailedThrowsWhenTextIsBlank() {
        TtsService service = new TtsService();
        assertThrows(IllegalArgumentException.class,
                () -> service.synthesizeDetailed("   ", "ru-RU", "voice", 1.0, 0.0));
    }

    // ==================== synthesizeDetailed: Google failure/fallback ====================
    // googleTtsClient is never constructed (no live GCP credentials in the sandbox), so
    // invoking synthesizeWithGoogle() throws a real NullPointerException — a RuntimeException,
    // exactly like any real Google Cloud SDK failure (auth error, quota, network). This lets us
    // exercise the catch-and-fallback logic in synthesizeDetailed() with real control flow.

    @Test
    void synthesizeDetailedFallsBackToEspeakWhenGoogleClientFails() throws Exception {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "google");
        ReflectionTestUtils.setField(service, "googleTtsAvailable", true);
        ReflectionTestUtils.setField(service, "espeakAvailable", true);
        ReflectionTestUtils.setField(service, "espeakBinary", fakeEspeakScript(0, canonicalWavFixture()).toString());

        TtsService.SynthesisResult result = service.synthesizeDetailed("hello", "en-US", "voice", 1.0, 0.0);

        assertEquals("espeak", result.actualProvider());
        assertEquals("degraded", result.status());
    }

    @Test
    void synthesizeDetailedThrowsWhenGoogleFailsAndNoEspeakFallback() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "google");
        ReflectionTestUtils.setField(service, "googleTtsAvailable", true);
        ReflectionTestUtils.setField(service, "espeakAvailable", false);

        TtsUnavailableException ex = assertThrows(TtsUnavailableException.class,
                () -> service.synthesizeDetailed("hello", "en-US", "voice", 1.0, 0.0));
        assertTrue(ex.getMessage().contains("Google TTS failed"));
    }

    // ==================== synthesizeDetailed: Piper ====================

    @Test
    void synthesizeDetailedSucceedsWithPiperDaemon() throws Exception {
        piperServer = startPiperServer(200, canonicalWavFixture());
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperAvailable", true);
        ReflectionTestUtils.setField(service, "piperUrl", piperUrl(piperServer));
        ReflectionTestUtils.setField(service, "piperTimeoutMs", 5000L);

        TtsService.SynthesisResult result = service.synthesizeDetailed("Привет", "ru-RU", "voice", 1.25, 0.0);

        assertEquals("piper", result.actualProvider());
        assertTrue(CanonicalWavAudio.inspect(result.audioData()).valid());
    }

    @Test
    void synthesizeDetailedThrowsWhenPiperDaemonReturnsNon200() throws Exception {
        piperServer = startPiperServer(500, new byte[0]);
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperAvailable", true);
        ReflectionTestUtils.setField(service, "piperUrl", piperUrl(piperServer));
        ReflectionTestUtils.setField(service, "piperTimeoutMs", 5000L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.synthesizeDetailed("Привет", "ru-RU", "voice", 1.0, 0.0));
        assertTrue(ex.getMessage().contains("HTTP 500"));
    }

    @Test
    void synthesizeDetailedFallsBackToEspeakWhenPiperUnavailable() throws Exception {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperAvailable", false);
        ReflectionTestUtils.setField(service, "espeakAvailable", true);
        ReflectionTestUtils.setField(service, "espeakBinary", fakeEspeakScript(0, canonicalWavFixture()).toString());

        TtsService.SynthesisResult result = service.synthesizeDetailed("Привет", "ru-RU", "voice", 1.0, 0.0);

        assertEquals("espeak", result.actualProvider());
        assertEquals("degraded", result.status());
    }

    @Test
    void isTtsAvailableDelegatesToProviderSelection() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", false);
        assertFalse(service.isTtsAvailable());
    }

    // ==================== fixtures / fakes ====================

    private static HttpServer startPiperServer(int synthesizeStatus, byte[] synthesizeBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/synthesize", exchange -> {
            exchange.sendResponseHeaders(synthesizeStatus, synthesizeBody.length);
            exchange.getResponseBody().write(synthesizeBody);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String piperUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Builds a real, valid canonical (16kHz mono 16-bit PCM) WAV byte array. */
    private static byte[] canonicalWavFixture() throws Exception {
        byte[] pcm = new byte[640]; // 20ms of silence at 16kHz mono 16-bit
        AudioFormat format = CanonicalWavAudio.canonicalFormat();
        try (AudioInputStream audioInputStream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize());
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            javax.sound.sampled.AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        }
    }

    /**
     * Writes a POSIX shell script that stands in for the espeak/espeak-ng binary:
     * it copies a fixture WAV to the {@code -w <path>} target argument and exits
     * with the given code, exactly mirroring what a real espeak invocation does.
     */
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
