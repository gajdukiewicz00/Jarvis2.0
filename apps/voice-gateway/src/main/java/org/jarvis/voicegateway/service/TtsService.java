package org.jarvis.voicegateway.service;

import com.google.cloud.texttospeech.v1.*;
import com.google.protobuf.ByteString;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.audio.CanonicalWavAudio;
import org.jarvis.voicegateway.exception.TtsUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Text-to-Speech service with Google Cloud TTS and eSpeak fallback.
 */
@Slf4j
@Service
public class TtsService {

    private static final String PROVIDER_GOOGLE = "google";
    private static final String PROVIDER_ESPEAK = "espeak";
    private static final String PROVIDER_PIPER = "piper";
    @Value("${tts.enabled:true}")
    private boolean ttsEnabled;

    @Value("${tts.provider:espeak}")
    private String ttsProvider;

    @Value("${tts.espeak.binary-path:}")
    private String configuredEspeakBinaryPath;

    @Value("${tts.piper.url:}")
    private String piperUrl;

    @Value("${tts.piper.timeout-ms:15000}")
    private long piperTimeoutMs;

    @Value("${tts.piper.reprobe-interval-ms:15000}")
    private long piperReprobeIntervalMs;

    private volatile long lastPiperProbeAtMs = 0L;

    private volatile boolean piperAvailable = false;
    private volatile String piperInitStatus = "Piper TTS not initialized";
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

    private TextToSpeechClient googleTtsClient;
    private boolean googleTtsAvailable = false;
    private boolean espeakAvailable = false;
    private String espeakBinary = "espeak";
    private volatile String resolvedEspeakBinaryPath = "";
    private volatile String googleInitStatus = "Google TTS not initialized";

    @PostConstruct
    public void init() {
        if (!ttsEnabled) {
            log.info("TTS is disabled");
            googleInitStatus = "TTS disabled by configuration";
            return;
        }

        espeakAvailable = checkEspeakAvailable();

        if (PROVIDER_GOOGLE.equalsIgnoreCase(ttsProvider)) {
            try {
                googleTtsClient = TextToSpeechClient.create();
                googleTtsAvailable = true;
                googleInitStatus = "ready";
                log.info("Google Cloud TTS initialized successfully");
            } catch (IOException e) {
                googleInitStatus = "Google TTS init failed: " + e.getMessage();
                log.info("Google TTS not available ({}), checking eSpeak fallback...", e.getMessage());
                googleTtsAvailable = false;
                if (!espeakAvailable) {
                    log.warn("⚠️ TTS DEGRADED: Neither Google TTS nor espeak are available. " +
                            "Run ./scripts/setup-voice-local.sh to install the canonical local eSpeak path.");
                }
            }
        } else if (PROVIDER_ESPEAK.equalsIgnoreCase(ttsProvider)) {
            if (espeakAvailable) {
                log.info("Using eSpeak TTS provider (configured), binary={}", espeakBinary);
            } else {
                log.warn("⚠️ TTS DEGRADED: espeak binary not found on host. " +
                        "Run ./scripts/setup-voice-local.sh to install the canonical local eSpeak path.");
            }
        } else if (PROVIDER_PIPER.equalsIgnoreCase(ttsProvider)) {
            piperAvailable = checkPiperAvailable();
            if (piperAvailable) {
                log.info("Using Piper neural TTS provider (configured), url={}", piperUrl);
            } else {
                log.warn("⚠️ TTS DEGRADED: Piper daemon not reachable at {} ({}). Will use eSpeak if available.",
                        piperUrl, piperInitStatus);
            }
        } else {
            log.warn("Unsupported TTS provider configured: {}. Supported providers: google, espeak, piper", ttsProvider);
        }
    }

    private boolean checkEspeakAvailable() {
        for (String binary : espeakCandidates()) {
            try {
                Process p = new ProcessBuilder(binary, "--version")
                        .redirectErrorStream(true)
                        .start();
                boolean finished = p.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    continue;
                }
                if (p.exitValue() == 0) {
                    espeakBinary = binary;
                    resolvedEspeakBinaryPath = binary;
                    log.info("TTS binary selected: {}", binary);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        resolvedEspeakBinaryPath = "";
        return false;
    }

    private List<String> espeakCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (configuredEspeakBinaryPath != null && !configuredEspeakBinaryPath.isBlank()) {
            candidates.add(configuredEspeakBinaryPath.trim());
        }
        candidates.add("espeak-ng");
        candidates.add("espeak");
        return new ArrayList<>(candidates);
    }

    public boolean isTtsAvailable() {
        return resolveProviderSelection().available();
    }

    public Map<String, Object> describeRuntime() {
        ProviderSelection selection = resolveProviderSelection();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", ttsEnabled);
        status.put("configuredProvider", selection.configuredProvider());
        status.put("effectiveProvider", selection.actualProvider());
        status.put("status", selection.status());
        status.put("available", selection.available());
        status.put("reason", selection.reason());
        status.put("reasonCode", ttsReasonCode(selection.status()));
        status.put("espeakAvailable", espeakAvailable);
        status.put("googleAvailable", googleTtsAvailable);
        status.put("googleStatus", googleInitStatus);
        if (configuredEspeakBinaryPath != null && !configuredEspeakBinaryPath.isBlank()) {
            status.put("configuredBinaryPath", configuredEspeakBinaryPath);
            status.put("configuredBinaryExists", Files.isExecutable(Path.of(configuredEspeakBinaryPath)));
        }
        if (espeakAvailable) {
            status.put("espeakBinary", espeakBinary);
            status.put("resolvedBinaryPath", resolvedEspeakBinaryPath);
        }
        return status;
    }

    /**
     * Synthesize text to speech with caching.
     */
    @Cacheable(value = "tts",
            key = "#text + '-' + #languageCode + '-' + #voiceName + '-' + #speakingRate + '-' + #pitch")
    public byte[] synthesize(String text, String languageCode, String voiceName, Double speakingRate, Double pitch) {
        return synthesizeDetailed(text, languageCode, voiceName, speakingRate, pitch).audioData();
    }

    public SynthesisResult synthesizeDetailed(
            String text, String languageCode, String voiceName, Double speakingRate, Double pitch) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("TTS text must not be blank");
        }

        ProviderSelection selection = resolveProviderSelection();
        if (!selection.available()) {
            throw new TtsUnavailableException(selection.reason());
        }

        log.info("Synthesizing text (length: {}, language: {}, configuredProvider={}, effectiveProvider={})",
                text.length(), languageCode, selection.configuredProvider(), selection.actualProvider());

        if (PROVIDER_GOOGLE.equals(selection.actualProvider())) {
            try {
                return new SynthesisResult(
                        synthesizeWithGoogle(text, languageCode, voiceName, speakingRate, pitch),
                        selection.configuredProvider(),
                        PROVIDER_GOOGLE,
                        selection.status(),
                        selection.reason());
            } catch (RuntimeException e) {
                if (selection.canFallBackToEspeak()) {
                    String degradedReason = "Google TTS request failed: " + e.getMessage() + ". Using eSpeak fallback.";
                    log.warn(degradedReason);
                    return new SynthesisResult(
                            synthesizeWithEspeak(text, languageCode),
                            selection.configuredProvider(),
                            PROVIDER_ESPEAK,
                            "degraded",
                            degradedReason);
                }
                throw new TtsUnavailableException("Google TTS failed: " + e.getMessage(), e);
            }
        }

        if (PROVIDER_ESPEAK.equals(selection.actualProvider())) {
            return new SynthesisResult(
                    synthesizeWithEspeak(text, languageCode),
                    selection.configuredProvider(),
                    PROVIDER_ESPEAK,
                    selection.status(),
                    selection.reason());
        }

        if (PROVIDER_PIPER.equals(selection.actualProvider())) {
            return new SynthesisResult(
                    synthesizeWithPiper(text, languageCode, speakingRate),
                    selection.configuredProvider(),
                    PROVIDER_PIPER,
                    selection.status(),
                    selection.reason());
        }

        throw new TtsUnavailableException(selection.reason());
    }

    /**
     * Synthesize using Google Cloud TTS.
     */
    private byte[] synthesizeWithGoogle(String text, String languageCode, String voiceName,
            Double speakingRate, Double pitch) {
        // Set the text input
        SynthesisInput input = SynthesisInput.newBuilder()
                .setText(text)
                .build();

        // Build the voice request
        VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                .setLanguageCode(languageCode)
                .setName(voiceName)
                .setSsmlGender(SsmlVoiceGender.FEMALE)
                .build();

        // Select the audio config
        AudioConfig audioConfig = AudioConfig.newBuilder()
                .setAudioEncoding(AudioEncoding.LINEAR16) // WAV format
                .setSpeakingRate(speakingRate)
                .setPitch(pitch)
                .build();

        // Perform the text-to-speech request
        SynthesizeSpeechResponse response = googleTtsClient.synthesizeSpeech(input, voice, audioConfig);

        // Get the audio contents from the response
        ByteString audioContents = response.getAudioContent();

        log.info("Google TTS synthesis successful, audio size: {} bytes", audioContents.size());
        return audioContents.toByteArray();
    }

    private byte[] synthesizeWithEspeak(String text, String languageCode) {
        java.io.File tempFile = null;
        try {
            String espeakLang = languageCode != null && languageCode.startsWith("ru") ? "ru" : "en";

            tempFile = java.io.File.createTempFile("tts_", ".wav");

            ProcessBuilder pb = new ProcessBuilder(
                    espeakBinary,
                    "-v", espeakLang,
                    "-w", tempFile.getAbsolutePath(),
                    text);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException(espeakBinary + " timed out after 15s");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException(espeakBinary + " failed (exit " + exitCode + "): " + output.toString().trim());
            }

            byte[] audioData = java.nio.file.Files.readAllBytes(tempFile.toPath());
            byte[] normalizedAudio = normalizeEspeakAudio(audioData);
            log.info("{} TTS synthesis successful, rawSize={} bytes, normalizedSize={} bytes", espeakBinary,
                    audioData.length, normalizedAudio.length);
            return normalizedAudio;

        } catch (IOException e) {
            log.error("{} TTS failed (IO): {}", espeakBinary, e.getMessage());
            throw new RuntimeException("TTS synthesis failed: IO error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TTS synthesis failed: Interrupted", e);
        } catch (RuntimeException e) {
            log.error("{} TTS failed: {}", espeakBinary, e.getMessage());
            throw e;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /** Synthesize via the host-side Piper neural TTS daemon (HTTP). Returns canonical WAV. */
    private byte[] synthesizeWithPiper(String text, String languageCode, Double speakingRate) {
        try {
            String body = buildPiperRequestBody(text, languageCode, speakingRate);
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(piperUrl.replaceAll("/+$", "") + "/synthesize"))
                    .timeout(java.time.Duration.ofMillis(piperTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<byte[]> resp =
                    httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Piper daemon returned HTTP " + resp.statusCode());
            }
            byte[] wav = resp.body();
            byte[] normalized = normalizeEspeakAudio(wav);
            log.info("Piper TTS synthesis successful, rawSize={} bytes, normalizedSize={} bytes",
                    wav.length, normalized.length);
            return normalized;
        } catch (java.net.http.HttpTimeoutException e) {
            // Distinguish provider-timeout from a generic synthesis failure (task: TTS health
            // categories + diagnostic logging). No transcript is logged — only the timing/url.
            log.error("Piper TTS TIMEOUT after {}ms (url={}) [TTS_PROVIDER_TIMEOUT]", piperTimeoutMs, piperUrl);
            throw new RuntimeException("Piper TTS timed out after " + piperTimeoutMs + "ms [TTS_PROVIDER_TIMEOUT]", e);
        } catch (IOException e) {
            log.error("Piper TTS synthesis FAILED (IO): {} [TTS_SYNTHESIS_FAILED]", e.getMessage());
            throw new RuntimeException("Piper TTS synthesis failed: IO error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Piper TTS synthesis failed: Interrupted", e);
        }
    }

    /**
     * Build the JSON request body for the Piper daemon. {@code speakingRate} follows the same
     * convention as Google TTS (1.0 = normal, &gt;1.0 = faster) and is forwarded as the daemon's
     * "speed" field (scripts/jarvis-tts-daemon.py), which uses the identical convention.
     */
    private static String buildPiperRequestBody(String text, String languageCode, Double speakingRate) {
        String lang = languageCode != null ? languageCode : "";
        StringBuilder body = new StringBuilder("{\"text\":")
                .append(jsonString(text))
                .append(",\"language\":")
                .append(jsonString(lang));
        if (speakingRate != null && speakingRate > 0) {
            body.append(",\"speed\":").append(speakingRate);
        }
        return body.append("}").toString();
    }

    private boolean checkPiperAvailable() {
        if (piperUrl == null || piperUrl.isBlank()) {
            piperInitStatus = "tts.piper.url is not configured";
            return false;
        }
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(piperUrl.replaceAll("/+$", "") + "/health"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET().build();
            java.net.http.HttpResponse<String> resp =
                    httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200;
            piperInitStatus = ok ? "ready" : "Piper health HTTP " + resp.statusCode();
            return ok;
        } catch (Exception e) {
            piperInitStatus = "Piper health check failed: " + e.getMessage();
            return false;
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }

    /**
     * Stable machine-readable code for the current TTS availability category, so clients can
     * branch on the exact reason (provider unavailable vs degraded fallback vs disabled) without
     * string-matching the human-readable {@code reason}.
     */
    private static String ttsReasonCode(String status) {
        return switch (status == null ? "" : status) {
            case "available" -> "TTS_READY";
            case "degraded" -> "TTS_DEGRADED_FALLBACK";
            case "disabled" -> "TTS_DISABLED";
            default -> "TTS_PROVIDER_UNAVAILABLE";
        };
    }

    /**
     * Re-probe the Piper daemon when it is currently marked unavailable, throttled to at most once
     * per {@code tts.piper.reprobe-interval-ms}. {@link #checkPiperAvailable()} otherwise runs only
     * once at {@code @PostConstruct}, so a daemon that was down at boot — or whose host Endpoints
     * were corrected later — would stay {@code unavailable} until the pod restarts. This lets TTS
     * recover in place. Only probes while unavailable, so a healthy provider adds zero overhead.
     */
    private void maybeReprobePiper() {
        if (piperAvailable) {
            return;
        }
        if (!PROVIDER_PIPER.equalsIgnoreCase(normalizeProvider(ttsProvider))
                || piperUrl == null || piperUrl.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPiperProbeAtMs < piperReprobeIntervalMs) {
            return;
        }
        lastPiperProbeAtMs = now;
        if (checkPiperAvailable()) {
            piperAvailable = true;
            log.info("🔊 Piper TTS recovered — daemon now reachable at {} ({})", piperUrl, piperInitStatus);
        }
    }

    private ProviderSelection resolveProviderSelection() {
        maybeReprobePiper();
        String configured = normalizeProvider(ttsProvider);
        if (!ttsEnabled) {
            return new ProviderSelection(configured, "none", "disabled", false,
                    "TTS is disabled by configuration", false);
        }

        return switch (configured) {
            case PROVIDER_GOOGLE -> {
                if (googleTtsAvailable) {
                    yield new ProviderSelection(PROVIDER_GOOGLE, PROVIDER_GOOGLE, "available", true,
                            "Google Cloud TTS is ready", espeakAvailable);
                }
                if (espeakAvailable) {
                    yield new ProviderSelection(PROVIDER_GOOGLE, PROVIDER_ESPEAK, "degraded", true,
                            "Configured Google TTS is unavailable. Falling back to eSpeak.", true);
                }
                yield new ProviderSelection(PROVIDER_GOOGLE, "none", "unavailable", false,
                        googleInitStatus + ". No local eSpeak fallback detected.", false);
            }
            case PROVIDER_ESPEAK -> {
                if (espeakAvailable) {
                    yield new ProviderSelection(PROVIDER_ESPEAK, PROVIDER_ESPEAK, "available", true,
                            "eSpeak is ready", false);
                }
                yield new ProviderSelection(PROVIDER_ESPEAK, "none", "unavailable", false,
                        espeakUnavailableReason(), false);
            }
            case PROVIDER_PIPER -> {
                if (piperAvailable) {
                    yield new ProviderSelection(PROVIDER_PIPER, PROVIDER_PIPER, "available", true,
                            "Piper neural TTS is ready", espeakAvailable);
                }
                if (espeakAvailable) {
                    yield new ProviderSelection(PROVIDER_PIPER, PROVIDER_ESPEAK, "degraded", true,
                            "Configured Piper TTS is unavailable. Falling back to eSpeak.", true);
                }
                yield new ProviderSelection(PROVIDER_PIPER, "none", "unavailable", false,
                        piperInitStatus + ". No local eSpeak fallback detected.", false);
            }
            default -> new ProviderSelection(configured, "none", "unavailable", false,
                    "Unsupported TTS provider '" + configured + "'. Supported providers: google, espeak, piper.", false);
        };
    }

    private String espeakUnavailableReason() {
        if (configuredEspeakBinaryPath != null && !configuredEspeakBinaryPath.isBlank()) {
            return "Configured eSpeak provider is unavailable. Expected executable at " + configuredEspeakBinaryPath + ".";
        }
        return "Configured eSpeak provider is unavailable. Run ./scripts/setup-voice-local.sh or install espeak-ng.";
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return PROVIDER_ESPEAK;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private byte[] normalizeEspeakAudio(byte[] wavData) {
        try {
            return CanonicalWavAudio.normalizeToCanonicalWav(wavData);
        } catch (Exception e) {
            log.warn("Failed to normalize eSpeak WAV to canonical 16kHz mono PCM. Returning raw WAV: {}",
                    e.getMessage());
            return wavData;
        }
    }

    public record SynthesisResult(
            byte[] audioData,
            String configuredProvider,
            String actualProvider,
            String status,
            String reason) {
    }

    private record ProviderSelection(
            String configuredProvider,
            String actualProvider,
            String status,
            boolean available,
            String reason,
            boolean canFallBackToEspeak) {
    }
}
