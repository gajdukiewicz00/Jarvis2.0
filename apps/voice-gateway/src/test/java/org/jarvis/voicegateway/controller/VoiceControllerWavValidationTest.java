package org.jarvis.voicegateway.controller;

import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.service.SttService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers VoiceController's WAV-format validation branches (transcribeAudio /
 * validateAndExtractWav) plus the orchestrator-unreachable and multipart-empty
 * failure paths not already covered by VoiceControllerTest.
 */
@ExtendWith(MockitoExtension.class)
class VoiceControllerWavValidationTest {

    @Mock
    private SttService sttService;
    @Mock
    private OrchestratorClient orchestratorClient;

    private VoiceController controller() {
        return new VoiceController(sttService, orchestratorClient);
    }

    @Test
    void transcribeAudioThrowsWhenFileIsEmpty() {
        VoiceController controller = controller();
        MockMultipartFile empty = new MockMultipartFile("file", "a.wav", "audio/wav", new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> controller.transcribeAudio(empty, "ru-RU"));
    }

    @Test
    void transcribeAudioThrowsWhenFileTooSmall() {
        VoiceController controller = controller();
        MockMultipartFile tiny = new MockMultipartFile("file", "a.wav", "audio/wav", new byte[10]);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.transcribeAudio(tiny, "ru-RU"));
        assertTrue(ex.getMessage().contains("too small"));
    }

    @Test
    void transcribeAudioThrowsWhenMissingRiffHeader() throws IOException {
        byte[] wav = wav(header("JUNK", "WAVE"), fmtChunk(1, 1, 16000, 16), dataChunk(new byte[10]));
        assertRejected(wav, "RIFF header");
    }

    @Test
    void transcribeAudioThrowsWhenMissingWaveMarker() throws IOException {
        byte[] wav = wav(header("RIFF", "JUNK"), fmtChunk(1, 1, 16000, 16), dataChunk(new byte[10]));
        assertRejected(wav, "WAVE marker");
    }

    @Test
    void transcribeAudioThrowsWhenAudioFormatIsNotPcm() throws IOException {
        byte[] wav = wav(header("RIFF", "WAVE"), fmtChunk(3, 1, 16000, 16), dataChunk(new byte[10]));
        assertRejected(wav, "must be PCM");
    }

    @Test
    void transcribeAudioThrowsWhenSampleRateIsWrong() throws IOException {
        byte[] wav = wav(header("RIFF", "WAVE"), fmtChunk(1, 1, 44100, 16), dataChunk(new byte[10]));
        assertRejected(wav, "16000 Hz");
    }

    @Test
    void transcribeAudioThrowsWhenNotMono() throws IOException {
        byte[] wav = wav(header("RIFF", "WAVE"), fmtChunk(1, 2, 16000, 16), dataChunk(new byte[10]));
        assertRejected(wav, "mono");
    }

    @Test
    void transcribeAudioThrowsWhenDataChunkMissing() throws IOException {
        // The file must stay >= 44 bytes (the WAV-file-size floor check runs before format
        // parsing), so pad with a skippable non-"data" chunk after fmt instead of a bare fmt.
        byte[] wav = wav(header("RIFF", "WAVE"), fmtChunk(1, 1, 16000, 16), chunk("fact", new byte[10]));
        assertRejected(wav, "missing data chunk");
    }

    @Test
    void transcribeAudioThrowsWhenFmtChunkMissing() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header("RIFF", "WAVE"));
        out.writeBytes(chunk("JUNK", new byte[28]));
        assertRejected(out.toByteArray(), "missing fmt chunk");
    }

    @Test
    void transcribeAudioSkipsUnknownChunkBeforeFmtAndSucceeds() throws IOException {
        when(sttService.transcribe(any(), org.mockito.ArgumentMatchers.eq("ru-RU"))).thenReturn("привет");
        when(sttService.describeRuntime()).thenReturn(Map.of("configuredProvider", "vosk"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header("RIFF", "WAVE"));
        out.writeBytes(chunk("JUNK", new byte[] {9, 9, 9, 9}));
        out.writeBytes(fmtChunk(1, 1, 16000, 16));
        out.writeBytes(dataChunk(new byte[] {1, 2, 3, 4}));

        MockMultipartFile file = new MockMultipartFile("file", "a.wav", "audio/wav", out.toByteArray());
        ResponseEntity<Map<String, Object>> response = controller().transcribeAudio(file, "ru-RU");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("wavValidated"));
    }

    @Test
    void transcribeAudioSkipsUnknownChunkBetweenFmtAndDataAndSucceeds() throws IOException {
        when(sttService.transcribe(any(), org.mockito.ArgumentMatchers.eq("ru-RU"))).thenReturn("привет");
        when(sttService.describeRuntime()).thenReturn(Map.of("configuredProvider", "vosk"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header("RIFF", "WAVE"));
        out.writeBytes(fmtChunk(1, 1, 16000, 16));
        out.writeBytes(chunk("fact", new byte[] {0, 0, 0, 0}));
        out.writeBytes(dataChunk(new byte[] {5, 6, 7, 8}));

        MockMultipartFile file = new MockMultipartFile("file", "a.wav", "audio/wav", out.toByteArray());
        ResponseEntity<Map<String, Object>> response = controller().transcribeAudio(file, "ru-RU");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void transcribeAudioAcceptsExtensibleFmtChunkWithExtraBytes() throws IOException {
        when(sttService.transcribe(any(), org.mockito.ArgumentMatchers.eq("ru-RU"))).thenReturn("привет");
        when(sttService.describeRuntime()).thenReturn(Map.of("configuredProvider", "vosk"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header("RIFF", "WAVE"));
        // fmt chunk with 2 extra trailing bytes (extensible-style cbSize=0), chunkSize=18.
        out.writeBytes(chunk("fmt ", fmtBody(1, 1, 16000, 16, 2)));
        out.writeBytes(dataChunk(new byte[] {1, 2}));

        MockMultipartFile file = new MockMultipartFile("file", "a.wav", "audio/wav", out.toByteArray());
        ResponseEntity<Map<String, Object>> response = controller().transcribeAudio(file, "ru-RU");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void transcribeAudioReturnsBadGatewayWhenOrchestratorForwardingFails() throws IOException {
        when(sttService.transcribe(any(), org.mockito.ArgumentMatchers.eq("ru-RU"))).thenReturn("привет");
        when(sttService.describeRuntime()).thenReturn(Map.of("configuredProvider", "vosk"));
        org.mockito.Mockito.doThrow(new RuntimeException("orchestrator down"))
                .when(orchestratorClient).sendCommand(any(), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header("RIFF", "WAVE"));
        out.writeBytes(fmtChunk(1, 1, 16000, 16));
        out.writeBytes(dataChunk(new byte[] {1, 2, 3, 4}));

        MockMultipartFile file = new MockMultipartFile("file", "a.wav", "audio/wav", out.toByteArray());
        ResponseEntity<Map<String, Object>> response = controller().transcribeAudio(file, "ru-RU");

        assertEquals(502, response.getStatusCode().value());
        assertEquals("ORCHESTRATOR_UNAVAILABLE", response.getBody().get("errorCode"));
    }

    private void assertRejected(byte[] wavBytes, String expectedMessageFragment) {
        VoiceController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "a.wav", "audio/wav", wavBytes);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.transcribeAudio(file, "ru-RU"));
        assertTrue(ex.getMessage().contains(expectedMessageFragment),
                "expected message to contain '" + expectedMessageFragment + "' but was '" + ex.getMessage() + "'");
    }

    // ==================== WAV byte-building helpers ====================

    private static byte[] wav(byte[] header, byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        for (byte[] c : chunks) {
            out.writeBytes(c);
        }
        return out.toByteArray();
    }

    private static byte[] header(String riff, String wave) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii4(riff));
        out.writeBytes(intLE(36));
        out.writeBytes(ascii4(wave));
        return out.toByteArray();
    }

    private static byte[] fmtChunk(int audioFormat, int channels, int sampleRate, int bitsPerSample) {
        return chunk("fmt ", fmtBody(audioFormat, channels, sampleRate, bitsPerSample, 0));
    }

    private static byte[] fmtBody(int audioFormat, int channels, int sampleRate, int bitsPerSample, int extraBytes) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(shortLE(audioFormat));
        body.writeBytes(shortLE(channels));
        body.writeBytes(intLE(sampleRate));
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        body.writeBytes(intLE(byteRate));
        body.writeBytes(shortLE(channels * bitsPerSample / 8));
        body.writeBytes(shortLE(bitsPerSample));
        for (int i = 0; i < extraBytes; i++) {
            body.write(0);
        }
        return body.toByteArray();
    }

    private static byte[] dataChunk(byte[] pcm) {
        return chunk("data", pcm);
    }

    private static byte[] chunk(String id, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii4(id));
        out.writeBytes(intLE(payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] ascii4(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        assertEquals(4, bytes.length, "chunk id must be exactly 4 ASCII bytes");
        return bytes;
    }

    private static byte[] intLE(int v) {
        return new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) };
    }

    private static byte[] shortLE(int v) {
        return new byte[] { (byte) v, (byte) (v >> 8) };
    }
}
