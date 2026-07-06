package org.jarvis.apigateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proxies {@code /api/v1/media/**} to media-service: the synchronous ffprobe
 * stream-detection endpoint ({@code POST /api/v1/media/probe}), the async pipeline
 * endpoints ({@code /api/v1/media/jobs/extract-audio}, {@code /transcribe},
 * {@code /russian-subtitles}, {@code /russian-dub-audio}, {@code /mux}), and job
 * lifecycle / artifact download ({@code /api/v1/media/jobs/**}).
 */
@Tag(name = "Media Service Proxy",
        description = "Proxies /api/v1/media/** to media-service: ffprobe stream detection, the "
                + "async transcode/subtitle/dub/mux pipeline, and job lifecycle / artifact download. "
                + "Requires an authenticated gateway user; the internal X-Service-Token is attached "
                + "automatically before forwarding.")
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaProxyController {

    private final DownstreamProxyService downstreamProxyService;

    @Value("${services.media-service.url}")
    private String mediaServiceUrl;

    @Operation(summary = "Forward request to media-service",
            description = "Wildcard proxy for GET/POST/PUT/PATCH/DELETE under /api/v1/media/**, including "
                    + "probe, jobs/extract-audio, jobs/transcribe, jobs/russian-subtitles, "
                    + "jobs/russian-dub-audio, jobs/mux, and jobs/** lifecycle/artifact download.")
    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return downstreamProxyService.forward(request, "media-service", mediaServiceUrl);
    }
}
