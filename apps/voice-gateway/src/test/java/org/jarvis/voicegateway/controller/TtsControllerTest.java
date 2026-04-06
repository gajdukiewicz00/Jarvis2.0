package org.jarvis.voicegateway.controller;

import org.jarvis.voicegateway.config.GlobalExceptionHandler;
import org.jarvis.voicegateway.exception.TtsUnavailableException;
import org.jarvis.voicegateway.service.TtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TtsControllerTest {

    @Mock
    private TtsService ttsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TtsController(ttsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void synthesizeReturnsProviderHeadersOnSuccess() throws Exception {
        when(ttsService.synthesizeDetailed(
                eq("Привет"),
                eq("ru-RU"),
                eq("ru-RU-Wavenet-A"),
                eq(1.0),
                eq(0.0)))
                .thenReturn(new TtsService.SynthesisResult(
                        new byte[] {1, 2, 3},
                        "google",
                        "espeak",
                        "degraded",
                        "Configured Google TTS is unavailable. Falling back to eSpeak."));

        mockMvc.perform(post("/api/v1/voice/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Привет"}
                                """.trim()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Jarvis-Tts-Configured-Provider", "google"))
                .andExpect(header().string("X-Jarvis-Tts-Actual-Provider", "espeak"))
                .andExpect(header().string("X-Jarvis-Tts-Status", "degraded"));
    }

    @Test
    void synthesizeReturns503WhenTtsProviderIsUnavailable() throws Exception {
        when(ttsService.synthesizeDetailed(
                eq("Привет"),
                eq("ru-RU"),
                eq("ru-RU-Wavenet-A"),
                eq(1.0),
                eq(0.0)))
                .thenThrow(new TtsUnavailableException("Configured eSpeak provider is unavailable."));

        mockMvc.perform(post("/api/v1/voice/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Привет"}
                                """.trim()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TTS_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Configured eSpeak provider is unavailable."));
    }
}
