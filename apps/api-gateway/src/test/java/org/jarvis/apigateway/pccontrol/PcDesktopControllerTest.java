package org.jarvis.apigateway.pccontrol;

import org.jarvis.apigateway.capability.CapabilityUnavailableException;
import org.jarvis.apigateway.capability.GatewayCapabilityService;
import org.jarvis.apigateway.capability.RuntimeMode;
import org.jarvis.apigateway.client.PcControlClient;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PcDesktopControllerTest {

    @Mock
    private PcControlClient pcControlClient;

    @Mock
    private GatewayCapabilityService gatewayCapabilityService;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private ObjectProvider<AuditPublisher> auditProvider;

    private PcDesktopController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        // lenient(): the status() test never reaches an audit() call, so this
        // stub would otherwise be flagged as unnecessary for that one test.
        lenient().when(auditProvider.getIfAvailable()).thenReturn(auditPublisher);
        controller = new PcDesktopController(pcControlClient, gatewayCapabilityService, auditProvider);
    }

    // --- SAFE actions ---

    @Test
    void safeActionForwardsToPcControlAndAuditsExecuted() {
        when(pcControlClient.executeAction(any())).thenReturn(ResponseEntity.ok("{\"success\":true}"));

        ResponseEntity<Map<String, Object>> response = controller.executeAction(
                new DesktopActionRequest("OPEN_APP", Map.of("app", "code"), false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OPEN_APP", response.getBody().get("type"));
        assertEquals("SAFE", response.getBody().get("classification"));

        ArgumentCaptor<Map<String, Object>> requestCaptor = captor();
        verify(pcControlClient).executeAction(requestCaptor.capture());
        assertEquals("OPEN_APP", requestCaptor.getValue().get("actionType"));
        assertEquals(Map.of("app", "code"), requestCaptor.getValue().get("parameters"));

        verify(gatewayCapabilityService).requireDirectPcControlSupport("desktop-action");
        verify(auditPublisher).audit(eq(AuditEventType.COMMAND_EXECUTED), any(), any(), any(), any(), any());
    }

    @Test
    void safeActionAcceptsLowercaseAndHyphenatedType() {
        when(pcControlClient.executeAction(any())).thenReturn(ResponseEntity.ok("ok"));

        ResponseEntity<Map<String, Object>> response = controller.executeAction(
                new DesktopActionRequest("open-url", Map.of("url", "https://example.com"), false));

        assertEquals("OPEN_URL", response.getBody().get("type"));
    }

    // --- GUARDED actions ---

    @Test
    void guardedActionWithoutConfirmIsBlockedAndNeverForwarded() {
        ResponseEntity<Map<String, Object>> response = controller.executeAction(
                new DesktopActionRequest("TYPE_TEXT", Map.of("text", "hello"), false));

        assertEquals(HttpStatus.PRECONDITION_REQUIRED, response.getStatusCode());
        assertEquals("GUARDED", response.getBody().get("classification"));
        assertTrue((Boolean) response.getBody().get("refused"));

        verify(pcControlClient, never()).executeAction(any());
        verify(gatewayCapabilityService, never()).requireDirectPcControlSupport(any());
        verify(auditPublisher).audit(eq(AuditEventType.CONFIRMATION_REQUESTED), any(), any(), any(), any(), any());
    }

    @Test
    void guardedActionWithConfirmTrueForwards() {
        when(pcControlClient.executeAction(any())).thenReturn(ResponseEntity.ok("ok"));

        ResponseEntity<Map<String, Object>> response = controller.executeAction(
                new DesktopActionRequest("HOTKEY", Map.of("keyCombination", "Alt+Tab"), true));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(pcControlClient).executeAction(any());
        verify(auditPublisher).audit(eq(AuditEventType.COMMAND_EXECUTED), any(), any(), any(), any(), any());
    }

    // --- DANGEROUS / UNKNOWN actions ---

    @Test
    void dangerousActionIsRefusedAndNeverForwarded() {
        ResponseEntity<Map<String, Object>> response = controller.executeAction(
                new DesktopActionRequest("RUN_SHELL", Map.of("command", "rm -rf /"), true));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("DANGEROUS", response.getBody().get("classification"));
        assertTrue((Boolean) response.getBody().get("refused"));

        verify(pcControlClient, never()).executeAction(any());
        verify(gatewayCapabilityService, never()).requireDirectPcControlSupport(any());
        verify(auditPublisher).audit(eq(AuditEventType.COMMAND_FAILED), any(), any(), any(), any(), any());
    }

    @Test
    void unrecognizedActionIsRefusedAsUnknown() {
        ResponseEntity<Map<String, Object>> response = controller.executeAction(
                new DesktopActionRequest("SOMETHING_MADE_UP", Map.of(), true));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("UNKNOWN", response.getBody().get("classification"));
        verify(pcControlClient, never()).executeAction(any());
    }

    @Test
    void missingTypeIsRefusedAsUnknown() {
        ResponseEntity<Map<String, Object>> response = controller.executeAction(
                new DesktopActionRequest(null, Map.of(), true));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("UNKNOWN", response.getBody().get("classification"));
        assertNull(response.getBody().get("type"));
        verify(pcControlClient, never()).executeAction(any());
    }

    // --- Capability gate ---

    @Test
    void capabilityUnavailableBubblesUpWithoutForwarding() {
        CapabilityUnavailableException exception = new CapabilityUnavailableException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "UNSUPPORTED_RUNTIME_MODE",
                "Direct PC control REST routes require a workstation runtime with non-stub pc-control",
                "pc-control",
                "desktop-action",
                RuntimeMode.K8S,
                List.of("local", "dev"),
                Map.of("stubMode", true));
        doThrow(exception).when(gatewayCapabilityService).requireDirectPcControlSupport("desktop-action");

        CapabilityUnavailableException thrown = assertThrows(CapabilityUnavailableException.class, () ->
                controller.executeAction(new DesktopActionRequest("OPEN_APP", Map.of("app", "code"), false)));

        assertEquals("UNSUPPORTED_RUNTIME_MODE", thrown.errorCode());
        verify(pcControlClient, never()).executeAction(any());
    }

    // --- Downstream failure ---

    @Test
    void downstreamFailureAuditsCommandFailedAndRethrows() {
        RuntimeException upstreamFailure = new RuntimeException("connection refused");
        when(pcControlClient.executeAction(any())).thenThrow(upstreamFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                controller.executeAction(new DesktopActionRequest("OPEN_URL", Map.of("url", "https://example.com"), false)));

        assertEquals("connection refused", thrown.getMessage());
        verify(auditPublisher).audit(eq(AuditEventType.COMMAND_FAILED), any(), any(), any(), any(), any());
    }

    // --- Status endpoint ---

    @Test
    void statusDelegatesToGatewayCapabilityService() {
        Map<String, Object> expected = Map.of("realControlActive", false, "stubMode", true);
        when(gatewayCapabilityService.describeDesktopControlStatus()).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.status();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
    }

    // --- Audit user propagation ---

    @Test
    void authenticatedUserIsPropagatedToAuditEvent() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner-1", null, List.of()));
        when(pcControlClient.executeAction(any())).thenReturn(ResponseEntity.ok("ok"));

        controller.executeAction(new DesktopActionRequest("SCREENSHOT", Map.of(), false));

        verify(auditPublisher).audit(eq(AuditEventType.COMMAND_EXECUTED), any(), any(), eq("owner-1"), any(), any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> captor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
