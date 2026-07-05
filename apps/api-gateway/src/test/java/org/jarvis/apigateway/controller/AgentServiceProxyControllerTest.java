package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceProxyControllerTest {

    @Mock
    private DownstreamProxyService downstreamProxyService;

    @InjectMocks
    private AgentServiceProxyController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "agentServiceUrl", "http://agent-service");
    }

    @Test
    void rolesRouteForwardsToAgentService() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents/roles");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("roles".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "agent-service", "http://agent-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("agent-service"), eq("http://agent-service"));
    }

    @Test
    void tasksRouteForwardsToAgentService() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/tasks");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("task".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "agent-service", "http://agent-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("agent-service"), eq("http://agent-service"));
    }

    @Test
    void swarmRouteForwardsToAgentService() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/swarm");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("swarm".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "agent-service", "http://agent-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("agent-service"), eq("http://agent-service"));
    }

    @Test
    void taskSubPathRouteForwardsToAgentService() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/tasks/task-42/cancel");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("cancelled".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "agent-service", "http://agent-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("agent-service"), eq("http://agent-service"));
    }
}
