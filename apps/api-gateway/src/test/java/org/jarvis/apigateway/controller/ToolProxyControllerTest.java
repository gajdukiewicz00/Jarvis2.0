package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.jarvis.apigateway.capability.CapabilityUnavailableException;
import org.jarvis.apigateway.capability.GatewayCapabilityService;
import org.jarvis.apigateway.capability.RuntimeMode;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolProxyControllerTest {

    @Mock
    private DownstreamProxyService downstreamProxyService;

    @Mock
    private GatewayCapabilityService gatewayCapabilityService;

    @InjectMocks
    private ToolProxyController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "plannerServiceUrl", "http://planner-service");
        ReflectionTestUtils.setField(controller, "lifeTrackerUrl", "http://life-tracker");
        ReflectionTestUtils.setField(controller, "memoryServiceUrl", "http://memory-service");
    }

    @Test
    void todoRoutesForwardToPlannerService() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tools/todo/list");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("planner".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "planner-service", "http://planner-service"))
                .thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(request, "planner-service", "http://planner-service");
        verify(gatewayCapabilityService, never()).requireMemorySupport(any());
    }

    @Test
    void calendarRoutesForwardToLifeTracker() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tools/calendar/upcoming");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("calendar".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "life-tracker", "http://life-tracker"))
                .thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(request, "life-tracker", "http://life-tracker");
        verify(gatewayCapabilityService, never()).requireMemorySupport(any());
    }

    @Test
    void memoryRoutesRequireExplicitCapabilitySupport() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tools/memory/search");
        CapabilityUnavailableException exception = new CapabilityUnavailableException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "FEATURE_DISABLED",
                "Memory tooling is disabled in this runtime",
                "memory-service",
                "memory-tooling",
                RuntimeMode.LOCAL,
                List.of("local", "dev", "k8s"),
                Map.of("serviceEnabled", false));
        doThrow(exception).when(gatewayCapabilityService).requireMemorySupport("memory-tooling");

        CapabilityUnavailableException thrown = assertThrows(
                CapabilityUnavailableException.class,
                () -> controller.proxy(request));

        assertEquals("FEATURE_DISABLED", thrown.errorCode());
        verify(gatewayCapabilityService).requireMemorySupport("memory-tooling");
        verify(downstreamProxyService, never()).forward(any(HttpServletRequest.class), eq("memory-service"), any());
    }

    @Test
    void unsupportedToolRouteReturnsNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tools/unknown");

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> controller.proxy(request));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
        assertEquals("Unsupported tool route", thrown.getReason());
        verifyNoDownstreamCalls();
    }

    private void verifyNoDownstreamCalls() {
        verify(downstreamProxyService, never()).forward(any(HttpServletRequest.class), any(), any());
        verify(gatewayCapabilityService, never()).requireMemorySupport("memory-tooling");
    }
}
