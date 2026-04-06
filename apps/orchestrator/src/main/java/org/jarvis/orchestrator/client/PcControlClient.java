package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@FeignClient(
        name = "pc-control",
        url = "${jarvis.pc-control.url:http://localhost:8084}",
        configuration = ServiceAuthFeignConfig.class)
public interface PcControlClient {

    @PostMapping("/api/v1/pc/action")
    void executeAction(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody ActionRequest request);

    record ActionRequest(String actionType, Map<String, String> parameters) {
    }
}
