package org.jarvis.apigateway.capability;

import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Covers {@link GatewayCapabilityService#describeDesktopControlStatus()},
 * the real-vs-stub flag backing {@code GET /api/v1/pc/desktop/status}.
 */
@ExtendWith(MockitoExtension.class)
class GatewayCapabilityServiceDesktopStatusTest {

    @Mock
    private RuntimeModeResolver runtimeModeResolver;

    @Mock
    private PcControlWebSocketHandler pcControlWebSocketHandler;

    private GatewayCapabilityService service(boolean stubMode, boolean localBridge) {
        return new GatewayCapabilityService(
                runtimeModeResolver,
                pcControlWebSocketHandler,
                false,
                false,
                false,
                false,
                stubMode,
                localBridge);
    }

    @Test
    void realControlActiveWhenNotStubAndNotK8s() {
        when(runtimeModeResolver.currentMode()).thenReturn(RuntimeMode.LOCAL);

        Map<String, Object> status = service(false, false).describeDesktopControlStatus();

        assertTrue((Boolean) status.get("realControlActive"));
        assertEquals("host-pc-control", status.get("executor"));
        assertEquals("local", status.get("runtimeMode"));
    }

    @Test
    void stubModeAlwaysMeansNotRealRegardlessOfRuntime() {
        when(runtimeModeResolver.currentMode()).thenReturn(RuntimeMode.LOCAL);

        Map<String, Object> status = service(true, false).describeDesktopControlStatus();

        assertFalse((Boolean) status.get("realControlActive"));
        assertEquals("stub", status.get("executor"));
        assertTrue((Boolean) status.get("stubMode"));
    }

    @Test
    void k8sModeWithoutLocalBridgeIsNotReal() {
        when(runtimeModeResolver.currentMode()).thenReturn(RuntimeMode.K8S);

        Map<String, Object> status = service(false, false).describeDesktopControlStatus();

        assertFalse((Boolean) status.get("realControlActive"));
        assertFalse((Boolean) status.get("localBridge"));
    }

    @Test
    void k8sModeWithLocalBridgeIsReal() {
        when(runtimeModeResolver.currentMode()).thenReturn(RuntimeMode.K8S);

        Map<String, Object> status = service(false, true).describeDesktopControlStatus();

        assertTrue((Boolean) status.get("realControlActive"));
        assertEquals("host-pc-control", status.get("executor"));
    }
}
