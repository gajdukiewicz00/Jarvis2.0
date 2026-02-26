package org.jarvis.voicegateway.service;

import com.google.cloud.texttospeech.v1.*;
import com.google.protobuf.ByteString;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Text-to-Speech service with Google Cloud TTS and eSpeak fallback.
 */
@Slf4j
@Service
public class TtsService {

    @Value("${tts.enabled:true}")
    private boolean ttsEnabled;

    @Value("${tts.provider:google}")
    private String ttsProvider;

    private TextToSpeechClient googleTtsClient;
    private boolean googleTtsAvailable = false;

    @PostConstruct
    public void init() {
        if (!ttsEnabled) {
            log.info("TTS is disabled");
            return;
        }

        if ("google".equalsIgnoreCase(ttsProvider)) {
            try {
                googleTtsClient = TextToSpeechClient.create();
                googleTtsAvailable = true;
                log.info("Google Cloud TTS initialized successfully");
            } catch (IOException e) {
                log.info("Using eSpeak TTS fallback (Google credentials not configured)");
                log.debug("Google TTS initialization failed: {}", e.getMessage());
                googleTtsAvailable = false;
            }
        } else {
            log.info("Using eSpeak TTS provider (configured)");
        }
    }

    /**
     * Synthesize text to speech with caching.
     */
    @Cacheable(value = "tts", key = "#text + '-' + #languageCode + '-' + #voiceName")
    public byte[] synthesize(String text, String languageCode, String voiceName, Double speakingRate, Double pitch) {
        if (!ttsEnabled) {
            throw new IllegalStateException("TTS is disabled");
        }

        log.info("Synthesizing text (length: {}, language: {})", text.length(), languageCode);

        if (googleTtsAvailable) {
            try {
                return synthesizeWithGoogle(text, languageCode, voiceName, speakingRate, pitch);
            } catch (RuntimeException e) {
                log.error("Google TTS failed, falling back to eSpeak: {}", e.getMessage());
                return synthesizeWithEspeak(text, languageCode);
            }
        } else {
            return synthesizeWithEspeak(text, languageCode);
        }
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

    /**
     * Synthesize using eSpeak (system command fallback).
     */
    private byte[] synthesizeWithEspeak(String text, String languageCode) {
        try {
            // Determine language for eSpeak
            String espeakLang = languageCode.startsWith("ru") ? "ru" : "en";

            // Create temporary file for output
            java.io.File tempFile = java.io.File.createTempFile("tts_", ".wav");
            tempFile.deleteOnExit();

            // Execute eSpeak command
            ProcessBuilder pb = new ProcessBuilder(
                    "espeak",
                    "-v", espeakLang,
                    "-w", tempFile.getAbsolutePath(),
                    text);

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                String errorLine = errorReader.readLine();
                throw new RuntimeException("eSpeak failed with exit code " + exitCode + ": " + errorLine);
            }

            // Read the generated WAV file
            byte[] audioData = java.nio.file.Files.readAllBytes(tempFile.toPath());
            log.info("eSpeak TTS synthesis successful, audio size: {} bytes", audioData.length);

            return audioData;

        } catch (IOException e) {
            log.error("eSpeak TTS failed due to IO error: {}", e.getMessage());
            throw new RuntimeException("TTS synthesis failed: IO error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("eSpeak TTS process interrupted: {}", e.getMessage());
            throw new RuntimeException("TTS synthesis failed: Interrupted", e);
        } catch (RuntimeException e) {
            log.error("eSpeak TTS failed: {}", e.getMessage());
            throw e;
        }
    }
}
