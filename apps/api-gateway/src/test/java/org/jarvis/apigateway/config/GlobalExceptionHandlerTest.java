package org.jarvis.apigateway.config;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRetryableExceptionMapsConnectionRefusedToServiceUnavailable() {
        RetryableException exception = new RetryableException(
                -1,
                "Connection refused executing GET http://planner-service/api/v1/tasks",
                Request.HttpMethod.GET,
                new ConnectException("Connection refused"),
                (Long) null,
                feignRequest("http://planner-service/api/v1/tasks"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRetryableException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_CONNECTION_REFUSED", response.getBody().get("error"));
        assertEquals("planner-service", response.getBody().get("upstreamService"));
        assertEquals("/api/v1/tasks", response.getBody().get("upstreamPath"));
        assertEquals("/api/tasks", response.getBody().get("path"));
    }

    @Test
    void handleFeignExceptionMapsUpstreamServerErrorsToBadGateway() {
        FeignException exception = new StubFeignException(
                500,
                "[500] during [GET] to [http://planner-service/api/v1/tasks] [PlannerClient#getTasks()]: [{\"error\":\"boom\"}]",
                feignRequest("http://planner-service/api/v1/tasks"),
                "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_ERROR", response.getBody().get("error"));
        assertEquals(500, response.getBody().get("upstreamStatus"));
        assertEquals("planner-service", response.getBody().get("upstreamService"));
        assertEquals("/api/v1/tasks", response.getBody().get("upstreamPath"));
        assertEquals("{\"error\":\"boom\"}", response.getBody().get("upstreamMessage"));
    }

    @Test
    void handleMissingParameterReturnsStructuredBadRequest() {
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("userId", "String");

        ResponseEntity<Map<String, Object>> response =
                handler.handleMissingParameter(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_PARAMETER", response.getBody().get("error"));
        assertEquals("Required parameter 'userId' is missing", response.getBody().get("message"));
        assertEquals("/api/tasks", response.getBody().get("path"));
    }

    @Test
    void handleMethodNotSupportedIncludesSupportedMethods() {
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleMethodNotSupported(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("METHOD_NOT_ALLOWED", response.getBody().get("error"));
        assertEquals("Request method 'PATCH' is not supported", response.getBody().get("message"));
        @SuppressWarnings("unchecked")
        Collection<String> supportedMethods = (Collection<String>) response.getBody().get("supportedMethods");
        assertEquals(List.of("GET", "POST"), List.copyOf(supportedMethods));
    }

    private WebRequest webRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), uri);
        return new ServletWebRequest(request);
    }

    private Request feignRequest(String url) {
        return Request.create(Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private static final class StubFeignException extends FeignException {
        private StubFeignException(int status, String message, Request request, byte[] content) {
            super(status, message, request, content, Map.of());
        }
    }
}
