package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "pc-control", url = "${services.pc-control.url:http://localhost:8084}")
public interface PcControlClient {

    @PostMapping("/api/v1/pc/action")
    ResponseEntity<String> executeAction(@RequestBody Map<String, Object> request);
}
