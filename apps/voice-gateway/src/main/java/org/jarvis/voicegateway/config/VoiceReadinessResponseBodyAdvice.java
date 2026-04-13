package org.jarvis.voicegateway.config;

import org.jarvis.voicegateway.health.VoiceReadinessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice
public class VoiceReadinessResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private static final String READY_STATUS = "UP";
    private static final String DEGRADED_STATUS = "DEGRADED";
    private static final String DOWN_STATUS = "DOWN";

    private final VoiceReadinessService voiceReadinessService;

    @Value("${management.endpoints.web.base-path:/actuator}")
    private String actuatorBasePath;

    public VoiceReadinessResponseBodyAdvice(VoiceReadinessService voiceReadinessService) {
        this.voiceReadinessService = voiceReadinessService;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!isReadinessPath(request)) {
            return body;
        }

        VoiceReadinessService.Snapshot snapshot = voiceReadinessService.currentSnapshot();
        applyStatus(response, snapshot.status());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", snapshot.status());
        payload.put("components", snapshot.components());
        return payload;
    }

    private boolean isReadinessPath(ServerHttpRequest request) {
        return normalizedPath(request.getURI().getPath()).equals(readinessPath());
    }

    private String readinessPath() {
        return normalizedPath(actuatorBasePath) + "/health/readiness";
    }

    private String normalizedPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private void applyStatus(ServerHttpResponse response, String status) {
        if (!(response instanceof ServletServerHttpResponse servletResponse)) {
            return;
        }

        int httpStatus = switch (status) {
            case READY_STATUS, DEGRADED_STATUS -> HttpServletResponse.SC_OK;
            case DOWN_STATUS -> HttpServletResponse.SC_SERVICE_UNAVAILABLE;
            default -> HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        };
        servletResponse.getServletResponse().setStatus(httpStatus);
    }
}
