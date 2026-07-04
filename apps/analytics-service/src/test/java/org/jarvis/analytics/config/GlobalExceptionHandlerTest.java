package org.jarvis.analytics.config;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRetryableExceptionReturnsServiceUnavailableWithUpstreamContext() {
        RetryableException exception = new RetryableException(
                -1,
                "Connection refused executing GET http://life-tracker:8085/api/v1/life/finance/expenses",
                Request.HttpMethod.GET,
                new ConnectException("Connection refused"),
                (Long) null,
                feignRequest("http://life-tracker:8085/api/v1/life/finance/expenses"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRetryableException(exception, webRequest("/api/v1/analytics/raw/expenses"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_TIMEOUT", response.getBody().get("error"));
        assertEquals("life-tracker", response.getBody().get("upstreamService"));
        assertEquals("/api/v1/life/finance/expenses", response.getBody().get("upstreamPath"));
        assertEquals("analytics-service", response.getBody().get("service"));
        assertEquals("/api/v1/analytics/raw/expenses", response.getBody().get("path"));
    }

    @Test
    void handleRetryableExceptionHandlesUnparseableMessageGracefully() {
        RetryableException exception = new RetryableException(
                -1,
                "boom",
                Request.HttpMethod.GET,
                new ConnectException("boom"),
                (Long) null,
                feignRequest("http://life-tracker:8085/api/v1/life/time/records"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRetryableException(exception, webRequest("/api/v1/analytics/raw/time-records"));

        assertEquals("unknown", response.getBody().get("upstreamService"));
        assertEquals("", response.getBody().get("upstreamPath"));
    }

    @Test
    void handleFeignExceptionMapsConnectionErrorToServiceUnavailable() {
        FeignException exception = stubFeignException(-1,
                "connection error executing GET http://life-tracker:8085/api/v1/life/finance/expenses",
                new byte[0]);

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/v1/analytics/raw/expenses"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_UNAVAILABLE", response.getBody().get("error"));
        assertEquals(-1, response.getBody().get("upstreamStatus"));
        assertEquals("No details available", response.getBody().get("upstreamMessage"));
    }

    @Test
    void handleFeignExceptionMapsServerErrorToBadGateway() {
        FeignException exception = stubFeignException(500,
                "[500] during [GET] to [http://life-tracker:8085/api/v1/life/finance/expenses]",
                "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/v1/analytics/raw/expenses"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_ERROR", response.getBody().get("error"));
        assertEquals(500, response.getBody().get("upstreamStatus"));
        assertEquals("{\"error\":\"boom\"}", response.getBody().get("upstreamMessage"));
    }

    @Test
    void handleFeignExceptionPassesThroughUpstreamClientErrorStatus() {
        FeignException exception = stubFeignException(404,
                "[404] during [GET] to [http://life-tracker:8085/api/v1/life/finance/expenses]",
                new byte[0]);

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/v1/analytics/raw/expenses"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("UPSTREAM_CLIENT_ERROR", response.getBody().get("error"));
        assertEquals(404, response.getBody().get("upstreamStatus"));
    }

    @Test
    void handleFeignExceptionMapsUnexpectedStatusToInternalServerError() {
        FeignException exception = stubFeignException(302,
                "[302] during [GET] to [http://life-tracker:8085/api/v1/life/finance/expenses]",
                new byte[0]);

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/v1/analytics/raw/expenses"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("UPSTREAM_UNKNOWN_ERROR", response.getBody().get("error"));
    }

    @Test
    void handleFeignExceptionTruncatesLongUpstreamMessages() {
        String longMessage = "e".repeat(600);
        FeignException exception = stubFeignException(500,
                "[500] during [GET] to [http://life-tracker:8085/api/v1/life/finance/expenses]",
                longMessage.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/v1/analytics/raw/expenses"));

        String upstreamMessage = (String) response.getBody().get("upstreamMessage");
        assertEquals(503, upstreamMessage.length()); // 500 chars + "..."
        assertTrue(upstreamMessage.endsWith("..."));
    }

    @Test
    void handleIllegalArgumentExceptionReturnsBadRequest() {
        IllegalArgumentException exception = new IllegalArgumentException("trailingDays must be greater than zero");

        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalArgumentException(exception, webRequest("/api/v1/analytics/habits/sleep-average"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().get("error"));
        assertEquals("trailingDays must be greater than zero", response.getBody().get("message"));
        assertEquals("analytics-service", response.getBody().get("service"));
    }

    @Test
    void handleGenericExceptionReturnsInternalServerErrorWithoutLeakingDetails() {
        Exception exception = new RuntimeException("some internal detail that should not leak");

        ResponseEntity<Map<String, Object>> response =
                handler.handleGenericException(exception, webRequest("/api/v1/analytics/overview"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().get("error"));
        assertEquals("An unexpected error occurred", response.getBody().get("message"));
    }

    private WebRequest webRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), uri);
        return new ServletWebRequest(request);
    }

    private Request feignRequest(String url) {
        return Request.create(Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private FeignException stubFeignException(int status, String message, byte[] content) {
        return new StubFeignException(status, message, feignRequest("http://life-tracker:8085/x"), content);
    }

    private static final class StubFeignException extends FeignException {
        private StubFeignException(int status, String message, Request request, byte[] content) {
            super(status, message, request, content, Map.of());
        }
    }
}
