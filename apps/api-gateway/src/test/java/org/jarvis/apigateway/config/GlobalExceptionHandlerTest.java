package org.jarvis.apigateway.config;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.jarvis.apigateway.capability.CapabilityUnavailableException;
import org.jarvis.apigateway.capability.RuntimeMode;
import org.jarvis.apigateway.proxy.UpstreamProxyException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void handleRetryableExceptionMapsUnknownHostToServiceUnavailable() {
        RetryableException exception = new RetryableException(
                -1,
                "Host not found executing GET http://memory-service/api/v1/facts",
                Request.HttpMethod.GET,
                new UnknownHostException("memory-service"),
                (Long) null,
                feignRequest("http://memory-service/api/v1/facts"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRetryableException(exception, webRequest("/api/facts"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_HOST_NOT_FOUND", response.getBody().get("error"));
        assertEquals("memory-service", response.getBody().get("upstreamService"));
    }

    @Test
    void handleRetryableExceptionMapsReadTimeoutMessage() {
        RetryableException exception = new RetryableException(
                -1,
                "Read timed out executing GET http://planner-service/api/v1/tasks",
                Request.HttpMethod.GET,
                null,
                (Long) null,
                feignRequest("http://planner-service/api/v1/tasks"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRetryableException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_TIMEOUT", response.getBody().get("error"));
    }

    @Test
    void handleRetryableExceptionFallsBackToGenericUnavailable() {
        RetryableException exception = new RetryableException(
                -1,
                "something odd happened executing GET http://planner-service/api/v1/tasks",
                Request.HttpMethod.GET,
                null,
                (Long) null,
                feignRequest("http://planner-service/api/v1/tasks"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRetryableException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_UNAVAILABLE", response.getBody().get("error"));
    }

    @Test
    void handleFeignExceptionPassesThroughUpstreamClientError() {
        FeignException exception = new StubFeignException(
                404,
                "[404] during [GET] to [http://planner-service/api/v1/tasks/9] [PlannerClient#getTask()]: [not found]",
                feignRequest("http://planner-service/api/v1/tasks/9"),
                "not found".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/tasks/9"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("UPSTREAM_CLIENT_ERROR", response.getBody().get("error"));
        assertEquals(404, response.getBody().get("upstreamStatus"));
    }

    @Test
    void handleFeignExceptionDelegatesToUpstreamUnavailableWhenStatusIsMinusOne() {
        FeignException exception = new StubFeignException(
                -1,
                "connection refused executing GET http://planner-service/api/v1/tasks",
                feignRequest("http://planner-service/api/v1/tasks"),
                new byte[0]);

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_UNAVAILABLE", response.getBody().get("error"));
        assertEquals("planner-service", response.getBody().get("upstreamService"));
    }

    @Test
    void handleFeignExceptionMapsUnexpectedStatusToInternalError() {
        FeignException exception = new StubFeignException(
                100,
                "[100] during [GET] to [http://planner-service/api/v1/tasks] [PlannerClient#getTasks()]: []",
                feignRequest("http://planner-service/api/v1/tasks"),
                new byte[0]);

        ResponseEntity<Map<String, Object>> response =
                handler.handleFeignException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("UPSTREAM_UNKNOWN", response.getBody().get("error"));
    }

    @Test
    void handleCapabilityUnavailableBuildsStructuredBody() {
        CapabilityUnavailableException exception = new CapabilityUnavailableException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "VISION_UNAVAILABLE",
                "vision-security is not reachable in this runtime mode",
                "vision-security",
                "face-recognition",
                RuntimeMode.LOCAL,
                List.of("k8s"),
                Map.of("hint", "start the vision stack"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleCapabilityUnavailable(exception, webRequest("/api/vision"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("VISION_UNAVAILABLE", response.getBody().get("error"));
        assertEquals("face-recognition", response.getBody().get("capability"));
        assertEquals("vision-security", response.getBody().get("upstreamService"));
        assertEquals("local", response.getBody().get("runtimeMode"));
        assertEquals(List.of("k8s"), response.getBody().get("supportedRuntimeModes"));
        assertEquals(Map.of("hint", "start the vision stack"), response.getBody().get("details"));
    }

    @Test
    void handleUpstreamProxyExceptionBuildsStructuredBody() {
        UpstreamProxyException exception = new UpstreamProxyException(
                HttpStatus.BAD_GATEWAY,
                "PROXY_ERROR",
                "proxy failed",
                "planner-service",
                "/api/v1/tasks",
                502,
                "boom",
                new RuntimeException("cause"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleUpstreamProxyException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("PROXY_ERROR", response.getBody().get("error"));
        assertEquals("planner-service", response.getBody().get("upstreamService"));
        assertEquals("/api/v1/tasks", response.getBody().get("upstreamPath"));
        assertEquals(502, response.getBody().get("upstreamStatus"));
        assertEquals("boom", response.getBody().get("upstreamBody"));
    }

    @Test
    void handleResponseStatusExceptionUsesReasonWhenPresent() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.CONFLICT, "duplicate request");

        ResponseEntity<Map<String, Object>> response =
                handler.handleResponseStatusException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("REQUEST_REJECTED", response.getBody().get("error"));
        assertEquals("duplicate request", response.getBody().get("message"));
    }

    @Test
    void handleResponseStatusExceptionFallsBackToStatusCodeWhenReasonMissing() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.CONFLICT);

        ResponseEntity<Map<String, Object>> response =
                handler.handleResponseStatusException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(((String) response.getBody().get("message")).contains("409"));
    }

    @Test
    void handleValidationExceptionJoinsFieldErrors() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "intent", "must not be blank"));
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", String.class), 0);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidationException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().get("error"));
        assertEquals("intent: must not be blank", response.getBody().get("message"));
    }

    @Test
    void handleNotFoundBuildsStructuredMessage() {
        NoHandlerFoundException exception =
                new NoHandlerFoundException("GET", "/api/unknown", new HttpHeaders());

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(exception, webRequest("/api/unknown"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().get("error"));
        assertTrue(((String) response.getBody().get("message")).contains("/api/unknown"));
    }

    @Test
    void handleMaxUploadSizeExceededReturnsPayloadTooLarge() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(1024L);

        ResponseEntity<Map<String, Object>> response =
                handler.handleMaxUploadSizeExceeded(exception, webRequest("/api/upload"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("PAYLOAD_TOO_LARGE", response.getBody().get("error"));
    }

    @Test
    void handleIllegalArgumentReturnsBadRequest() {
        IllegalArgumentException exception = new IllegalArgumentException("bad input");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_ARGUMENT", response.getBody().get("error"));
        assertEquals("bad input", response.getBody().get("message"));
    }

    @Test
    void handleGenericExceptionReturnsInternalServerError() {
        Exception exception = new RuntimeException("kaboom");

        ResponseEntity<Map<String, Object>> response = handler.handleGenericException(exception, webRequest("/api/tasks"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().get("error"));
        assertEquals("An unexpected error occurred", response.getBody().get("message"));
    }

    @SuppressWarnings("unused")
    private void dummyTarget(String intent) {
        // Reflection target only, used to build a MethodParameter for validation tests.
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
