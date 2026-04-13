package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/nlp")
@RequiredArgsConstructor
public class NlpProxyController {

    private final DownstreamProxyService downstreamProxyService;

    @Value("${services.nlp-service.url}")
    private String nlpServiceUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return downstreamProxyService.forward(request, "nlp-service", nlpServiceUrl);
    }
}
