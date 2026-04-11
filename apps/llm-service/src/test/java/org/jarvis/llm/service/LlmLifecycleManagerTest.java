package org.jarvis.llm.service;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmLifecycleManagerTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private MemoryClient memoryClient;

    private LlmLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new LlmLifecycleManager(llmClient, memoryClient);
    }

    @Test
    void initialStateIsDown() {
        assertEquals(LlmLifecycleManager.State.DOWN, manager.getState());
    }

    @Test
    void disabledLlmStaysDown() {
        ReflectionTestUtils.setField(manager, "llmEnabled", false);
        manager.refreshState();
        assertEquals(LlmLifecycleManager.State.DOWN, manager.getState());
        assertFalse(manager.isUsable());
    }

    @Test
    void healthyBackendTransitionsToReady() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryEnabled", false);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true, "healthy", "llamacpp", true, "cpu", false, null,
                "model", "/path", Collections.emptyMap(), null));

        manager.refreshState();
        assertEquals(LlmLifecycleManager.State.READY, manager.getState());
        assertTrue(manager.isReady());
        assertTrue(manager.isWarmupComplete());
    }

    @Test
    void unhealthyBackendTransitionsToError() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                false, "error", null, false, null, null, null,
                null, null, Collections.emptyMap(), "connection refused"));

        manager.refreshState();
        assertEquals(LlmLifecycleManager.State.ERROR, manager.getState());
        assertFalse(manager.isUsable());
    }

    @Test
    void memoryDownWithMemoryEnabledIsDegraded() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryEnabled", true);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true, "healthy", "llamacpp", true, "cpu", false, null,
                "model", "/path", Collections.emptyMap(), null));
        when(memoryClient.isHealthy()).thenReturn(false);

        manager.refreshState();
        assertEquals(LlmLifecycleManager.State.DEGRADED, manager.getState());
        assertTrue(manager.isUsable());
    }

    @Test
    void modelNotLoadedIsWarmingUp() {
        ReflectionTestUtils.setField(manager, "llmEnabled", true);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true, "loading", "llamacpp", false, "cpu", false, null,
                null, null, Collections.emptyMap(), null));

        manager.refreshState();
        assertEquals(LlmLifecycleManager.State.WARMING_UP, manager.getState());
        assertFalse(manager.isWarmupComplete());
    }
}
