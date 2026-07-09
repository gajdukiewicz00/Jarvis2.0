package org.jarvis.llm.service;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmLifecycleManagerEdgeCasesTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private MemoryClient memoryClient;

    private LlmLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new LlmLifecycleManager(llmClient, memoryClient);
    }

    private static LlmClient.LlmServerHealth healthy(boolean modelLoaded) {
        return new LlmClient.LlmServerHealth(
                true, "healthy", "llamacpp", modelLoaded, "cpu", false, null,
                "model", "/path", Collections.emptyMap(), null);
    }

    @Test
    void onApplicationReadyWithLlmDisabledStaysDown() {
        ReflectionTestUtils.setField(manager, "llmEnabled", false);

        manager.onApplicationReady();

        // Disabled at startup -> stays DOWN. (The manager initializes to DOWN, and
        // transition() only updates the reason on an actual state change, so the
        // reason remains the initial "not started" for a DOWN->DOWN transition.)
        assertEquals(LlmLifecycleManager.State.DOWN, manager.getState());
    }

    @Test
    void onApplicationReadyWithHealthyBackendReachesReady() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryEnabled", false);
        when(llmClient.getHealth()).thenReturn(healthy(true));

        Instant before = manager.getLastStateChange();
        manager.onApplicationReady();

        assertEquals(LlmLifecycleManager.State.READY, manager.getState());
        assertTrue(manager.isReady());
        assertEquals("all systems operational", manager.getStateReason());
        assertNotNull(manager.getLastStateChange());
        assertTrue(!manager.getLastStateChange().isBefore(before));
    }

    @Test
    void refreshStateWithMemoryEnabledAndHealthyMemoryReachesReady() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryEnabled", true);
        when(llmClient.getHealth()).thenReturn(healthy(true));
        when(memoryClient.isHealthy()).thenReturn(true);

        manager.refreshState();

        assertEquals(LlmLifecycleManager.State.READY, manager.getState());
        assertTrue(manager.isWarmupComplete());
    }

    @Test
    void refreshStateWithUnavailableBackendUsesFallbackReason() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);
        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                false, "down", null, false, null, null, null,
                null, null, Collections.emptyMap(), null));

        manager.refreshState();

        assertEquals(LlmLifecycleManager.State.ERROR, manager.getState());
        assertEquals("host-model-daemon unavailable", manager.getStateReason());
    }

    @Test
    void refreshStateSwallowsHealthCheckException() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);
        when(llmClient.getHealth()).thenThrow(new RuntimeException("boom"));

        manager.refreshState();

        assertEquals(LlmLifecycleManager.State.ERROR, manager.getState());
        assertTrue(manager.getStateReason().contains("health check failed"));
        assertTrue(manager.getStateReason().contains("boom"));
        assertFalse(manager.isReady());
    }
}
