package org.jarvis.orchestrator.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "nlp-service", url = "${jarvis.nlp.url:http://localhost:8082}")
public interface NlpClient {

    @PostMapping("/api/v1/nlp/analyze")
    NlpResult analyze(@RequestBody AnalyzeRequest request);

    record AnalyzeRequest(String text) {
    }

    record NlpResult(String intent, Map<String, String> slots) {
    }
}
