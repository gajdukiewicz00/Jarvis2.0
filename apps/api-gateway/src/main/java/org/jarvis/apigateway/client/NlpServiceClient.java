package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "nlp-service", url = "${services.nlp-service.url:http://localhost:8082}")
public interface NlpServiceClient {

    @PostMapping("/api/v1/nlp/analyze")
    ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> request);
}
