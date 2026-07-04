package org.jarvis.nlp.controller;

import org.jarvis.nlp.client.FastIntentClient;
import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.NlpResult;
import org.jarvis.nlp.service.EnhancedNlpService;
import org.jarvis.nlp.service.NlpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@link NlpController}. All three collaborators are mocked
 * so each branch of {@code /intent-fast} (router hit vs. regex fallback, and the
 * null-intent edge case) can be driven deterministically.
 */
@WebMvcTest(controllers = NlpController.class)
@AutoConfigureMockMvc(addFilters = false)
class NlpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EnhancedNlpService enhancedNlpService;

    @MockBean
    private NlpService nlpService;

    @MockBean
    private FastIntentClient fastIntentClient;

    @Test
    void analyzeDelegatesToLegacyNlpService() throws Exception {
        when(nlpService.infer("привет", "ru"))
                .thenReturn(new NlpResult("hello", Map.of()));

        mockMvc.perform(post("/api/v1/nlp/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"привет\",\"locale\":\"ru\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("hello"));
    }

    @Test
    void analyzeEnhancedDelegatesToEnhancedNlpService() throws Exception {
        when(enhancedNlpService.analyzeWithConfidence("таймер 10", "ru"))
                .thenReturn(new EnhancedNlpResult(
                        "set_timer", Map.of("amount", "10", "unit", "min"), 0.7, false, null, "таймер 10"));

        mockMvc.perform(post("/api/v1/nlp/analyze-enhanced")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"таймер 10\",\"locale\":\"ru\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("set_timer"))
                .andExpect(jsonPath("$.entities.amount").value("10"))
                .andExpect(jsonPath("$.confidence").value(0.7));
    }

    @Test
    void intentFastReturnsRouterResultWhenFastIntentClientClassifies() throws Exception {
        when(fastIntentClient.classify(eq("включи свет"), eq("ru"), any()))
                .thenReturn(Optional.of("smart_home_action"));

        mockMvc.perform(post("/api/v1/nlp/intent-fast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"включи свет\",\"locale\":\"ru\",\"candidates\":[\"smart_home_action\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("smart_home_action"))
                .andExpect(jsonPath("$.source").value("router"))
                .andExpect(jsonPath("$.confidence").value(0.7));
    }

    @Test
    void intentFastFallsBackToRuleEngineWhenClientReturnsEmpty() throws Exception {
        when(fastIntentClient.classify(eq("привет"), eq("ru"), any()))
                .thenReturn(Optional.empty());
        when(nlpService.infer("привет", "ru"))
                .thenReturn(new NlpResult("hello", Map.of()));

        mockMvc.perform(post("/api/v1/nlp/intent-fast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"привет\",\"locale\":\"ru\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("hello"))
                .andExpect(jsonPath("$.source").value("fallback"))
                .andExpect(jsonPath("$.confidence").value(0.5));
    }

    @Test
    void intentFastReturnsEmptyIntentWhenFallbackResultHasNullIntent() throws Exception {
        when(fastIntentClient.classify(eq("???"), isNull(), any()))
                .thenReturn(Optional.empty());
        when(nlpService.infer("???", null))
                .thenReturn(new NlpResult(null, Map.of()));

        mockMvc.perform(post("/api/v1/nlp/intent-fast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"???\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value(""))
                .andExpect(jsonPath("$.source").value("fallback"));
    }

    @Test
    void intentFastReturnsEmptyIntentWhenFallbackResultIsNull() throws Exception {
        when(fastIntentClient.classify(eq("silence"), eq("en"), any()))
                .thenReturn(Optional.empty());
        when(nlpService.infer("silence", "en")).thenReturn(null);

        mockMvc.perform(post("/api/v1/nlp/intent-fast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"silence\",\"locale\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value(""))
                .andExpect(jsonPath("$.source").value("fallback"));
    }

    @Test
    void intentFastPassesCandidatesListThroughToFastIntentClient() throws Exception {
        when(fastIntentClient.classify(eq("play"), eq("en"), eq(List.of("play", "pause"))))
                .thenReturn(Optional.of("play"));

        mockMvc.perform(post("/api/v1/nlp/intent-fast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"play\",\"locale\":\"en\",\"candidates\":[\"play\",\"pause\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("play"))
                .andExpect(jsonPath("$.source").value("router"));
    }
}
