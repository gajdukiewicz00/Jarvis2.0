package org.jarvis.nlp.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.NlpResult;
import org.jarvis.nlp.service.EnhancedNlpService;
import org.jarvis.nlp.service.NlpService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/nlp")
@RequiredArgsConstructor
public class NlpController {

    private final EnhancedNlpService enhancedNlpService;
    private final NlpService nlpService; // Legacy support

    @PostMapping("/analyze")
    public NlpResult analyze(@RequestBody AnalyzeRequest request) {
        return nlpService.infer(request.text(), request.locale());
    }

    @PostMapping("/analyze-enhanced")
    public EnhancedNlpResult analyzeEnhanced(@RequestBody AnalyzeRequest request) {
        return enhancedNlpService.analyzeWithConfidence(request.text(), request.locale());
    }

    public record AnalyzeRequest(String text, String locale) {
    }
}
