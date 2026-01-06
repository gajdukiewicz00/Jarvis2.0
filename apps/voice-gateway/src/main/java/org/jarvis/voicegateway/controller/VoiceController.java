package org.jarvis.voicegateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.service.SttService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final SttService sttService;
    private final OrchestratorClient orchestratorClient;

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribeAudio(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Received audio file: {} (size: {} bytes)", file.getOriginalFilename(), file.getSize());

            // Validate file
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("error", "File is empty");
                return ResponseEntity.badRequest().body(response);
            }

            byte[] fileData = file.getBytes();

            // Validate WAV format and extract PCM data
            WavValidationResult validation = validateAndExtractWav(fileData);
            if (!validation.isValid()) {
                response.put("success", false);
                response.put("error", validation.getError());
                return ResponseEntity.badRequest().body(response);
            }

            log.info("WAV validation successful: {}Hz, {} channel(s), PCM",
                    validation.getSampleRate(), validation.getChannels());

            // Transcribe using Vosk
            String transcribedText = sttService.transcribe(validation.getPcmData(), "ru");
            log.info("Transcribed text: {}", transcribedText);

            // Forward to orchestrator
            try {
                orchestratorClient.sendCommand(transcribedText);
                log.info("Forwarded to orchestrator: {}", transcribedText);
            } catch (Exception e) {
                log.warn("Failed to forward to orchestrator: {}", e.getMessage());
            }

            response.put("success", true);
            response.put("text", transcribedText);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("Failed to read audio file", e);
            response.put("success", false);
            response.put("error", "Failed to read audio file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (Exception e) {
            log.error("Transcription failed", e);
            response.put("success", false);
            response.put("error", "Transcription failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Validates WAV file format and extracts PCM data
     * Requirements: 16kHz, mono, PCM format
     */
    private WavValidationResult validateAndExtractWav(byte[] wavData) {
        if (wavData.length < 44) {
            return WavValidationResult.invalid("File too small to be a valid WAV file");
        }

        ByteBuffer buffer = ByteBuffer.wrap(wavData).order(ByteOrder.LITTLE_ENDIAN);

        // Check RIFF header
        byte[] riff = new byte[4];
        buffer.get(riff);
        if (!new String(riff).equals("RIFF")) {
            return WavValidationResult.invalid("Not a WAV file (missing RIFF header)");
        }

        buffer.getInt(); // Skip file size

        // Check WAVE format
        byte[] wave = new byte[4];
        buffer.get(wave);
        if (!new String(wave).equals("WAVE")) {
            return WavValidationResult.invalid("Not a WAV file (missing WAVE marker)");
        }

        // Find fmt chunk
        while (buffer.remaining() >= 8) {
            byte[] chunkId = new byte[4];
            buffer.get(chunkId);
            int chunkSize = buffer.getInt();

            if (new String(chunkId).equals("fmt ")) {
                short audioFormat = buffer.getShort();
                short channels = buffer.getShort();
                int sampleRate = buffer.getInt();
                buffer.getInt(); // byte rate
                buffer.getShort(); // block align
                buffer.getShort(); // bits per sample

                // Skip remaining fmt data
                int remaining = chunkSize - 16;
                if (remaining > 0) {
                    buffer.position(buffer.position() + remaining);
                }

                // Validate format
                if (audioFormat != 1) {
                    return WavValidationResult.invalid("Audio format must be PCM (format code 1), got: " + audioFormat);
                }
                if (sampleRate != 16000) {
                    return WavValidationResult.invalid("Sample rate must be 16000 Hz, got: " + sampleRate + " Hz");
                }
                if (channels != 1) {
                    return WavValidationResult.invalid("Must be mono (1 channel), got: " + channels + " channels");
                }

                // Find data chunk
                while (buffer.remaining() >= 8) {
                    byte[] dataChunkId = new byte[4];
                    buffer.get(dataChunkId);
                    int dataSize = buffer.getInt();

                    if (new String(dataChunkId).equals("data")) {
                        byte[] pcmData = new byte[dataSize];
                        buffer.get(pcmData);
                        return WavValidationResult.valid(pcmData, sampleRate, channels);
                    } else {
                        // Skip unknown chunk
                        buffer.position(buffer.position() + dataSize);
                    }
                }

                return WavValidationResult.invalid("WAV file missing data chunk");
            } else {
                // Skip unknown chunk
                if (buffer.remaining() >= chunkSize) {
                    buffer.position(buffer.position() + chunkSize);
                } else {
                    break;
                }
            }
        }

        return WavValidationResult.invalid("WAV file missing fmt chunk");
    }

    @PostMapping(value = "/transcribe/stream", consumes = "application/octet-stream")
    public String transcribeStream(java.io.InputStream inputStream) throws IOException {
        log.info("Received audio stream");
        byte[] bytes = inputStream.readAllBytes();
        String text = sttService.transcribe(bytes, "ru");
        log.info("Transcribed text: {}", text);
        orchestratorClient.sendCommand(text);
        return text;
    }

    @PostMapping("/command")
    public String processTextCommand(@RequestBody TextCommandRequest request) {
        log.info("Received text command: {}", request.text());
        // Forward to Orchestrator
        orchestratorClient.sendCommand(request.text());
        return "Processed: " + request.text();
    }

    public record TextCommandRequest(String text) {
    }

    // Helper class for WAV validation results
    private static class WavValidationResult {
        private final boolean valid;
        private final String error;
        private final byte[] pcmData;
        private final int sampleRate;
        private final int channels;

        private WavValidationResult(boolean valid, String error, byte[] pcmData, int sampleRate, int channels) {
            this.valid = valid;
            this.error = error;
            this.pcmData = pcmData;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        static WavValidationResult valid(byte[] pcmData, int sampleRate, int channels) {
            return new WavValidationResult(true, null, pcmData, sampleRate, channels);
        }

        static WavValidationResult invalid(String error) {
            return new WavValidationResult(false, error, null, 0, 0);
        }

        boolean isValid() {
            return valid;
        }

        String getError() {
            return error;
        }

        byte[] getPcmData() {
            return pcmData;
        }

        int getSampleRate() {
            return sampleRate;
        }

        int getChannels() {
            return channels;
        }
    }
}
