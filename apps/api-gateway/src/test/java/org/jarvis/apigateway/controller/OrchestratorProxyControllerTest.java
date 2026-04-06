package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.client.OrchestratorClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorProxyControllerTest {

    @Mock
    private OrchestratorClient orchestratorClient;

    @InjectMocks
    private OrchestratorProxyController controller;

    @Test
    void executeDelegatesToOrchestratorClient() {
        Map<String, String> request = Map.of("intent", "hello", "text", "hello jarvis");
        when(orchestratorClient.execute(request)).thenReturn(ResponseEntity.ok("hi"));

        ResponseEntity<String> response = controller.execute("smoke-1", request);

        assertEquals(200, response.getStatusCode().value());
        verify(orchestratorClient).execute(request);
    }
}
