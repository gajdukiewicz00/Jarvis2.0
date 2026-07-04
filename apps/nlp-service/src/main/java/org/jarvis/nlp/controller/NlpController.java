package org.jarvis.nlp.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.nlp.client.FastIntentClient;
import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.NlpResult;
import org.jarvis.nlp.service.EnhancedNlpService;
import org.jarvis.nlp.service.NlpService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/nlp")
@RequiredArgsConstructor
public class NlpController {

    private final EnhancedNlpService enhancedNlpService;
    private final NlpService nlpService; // Legacy support
    private final FastIntentClient fastIntentClient;

    @PostMapping("/analyze")
    public NlpResult analyze(@RequestBody AnalyzeRequest request) {
        return nlpService.infer(request.text(), request.locale());
    }

    @PostMapping("/analyze-enhanced")
    public EnhancedNlpResult analyzeEnhanced(@RequestBody AnalyzeRequest request) {
        return enhancedNlpService.analyzeWithConfidence(request.text(), request.locale());
    }

    /**
     * Phase 3 — fast intent classification.
     *
     * Tries the host router model first via llm-service. Falls back to the
     * deterministic regex engine when the daemon is unreachable or the
     * fast-intent feature flag is off.
     */
    @PostMapping("/intent-fast")
    public IntentFastResponse intentFast(@RequestBody IntentFastRequest request) {
        Optional<String> routed = fastIntentClient.classify(
                request.text(), request.locale(), request.candidates());
        if (routed.isPresent()) {
            return new IntentFastResponse(routed.get(), "router", 0.7);
        }
        NlpResult fallback = nlpService.infer(request.text(), request.locale());
        String intent = fallback != null ? fallback.intent() : "";
        return new IntentFastResponse(intent == null ? "" : intent, "fallback", 0.5);
    }

    public record AnalyzeRequest(String text, String locale) {
    }

    public record IntentFastRequest(String text, String locale, List<String> candidates) {
    }

    public record IntentFastResponse(String intent, String source, double confidence) {
    }
}
