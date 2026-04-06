package org.jarvis.pccontrol.client;

import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "vision-service",
        url = "${jarvis.vision-service.url:http://localhost:8094}",
        configuration = ServiceAuthFeignConfig.class)
public interface VisionServiceClient {

    @PostMapping("/api/v1/vision/face/verify-owner")
    VisionVerifyOwnerResponse verifyOwner(@RequestHeader("X-Request-ID") String requestId,
                                          @RequestHeader("X-Correlation-ID") String correlationId,
                                          @RequestBody VisionVerifyOwnerRequest request);

    @PostMapping("/api/v1/vision/screen/analyze")
    VisionScreenAnalysisResponse analyzeScreen(@RequestHeader("X-Request-ID") String requestId,
                                               @RequestHeader("X-Correlation-ID") String correlationId,
                                               @RequestBody VisionScreenAnalysisRequest request);
}
