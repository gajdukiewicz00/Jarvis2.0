package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.client.LifeTrackerClient;
import org.jarvis.apigateway.client.MemoryServiceClient;
import org.jarvis.apigateway.client.PlannerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ToolProxyControllerTest {

    @Mock
    private PlannerClient plannerClient;

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    @Mock
    private MemoryServiceClient memoryServiceClient;

    @InjectMocks
    private ToolProxyController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchMemoryReturns503WhenOptionalMemoryRuntimeIsDisabled() {
        authenticateAs("user-1");
        ReflectionTestUtils.setField(controller, "memoryServiceEnabled", false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.searchMemory(Map.of("query", "plan"))
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        verifyNoInteractions(memoryServiceClient);
    }

    @Test
    void listTodosUsesAuthenticatedUserAsToolSourceOfTruth() {
        authenticateAs("user-42");

        controller.listTodos(Map.of("status", "OPEN"));

        verify(plannerClient).listTodos("user-42", Map.of("status", "OPEN"));
        verifyNoInteractions(lifeTrackerClient, memoryServiceClient);
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId,
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
    }
}
