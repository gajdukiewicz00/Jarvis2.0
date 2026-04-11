package org.jarvis.llm.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the lifecycle state of the AI runtime within llm-service.
 * States: DOWN, STARTING, WARMING_UP, READY, DEGRADED, ERROR.
 */
@Slf4j
@Component
public class LlmLifecycleManager {

    public enum State {
        DOWN,
        STARTING,
        WARMING_UP,
        READY,
        DEGRADED,
        ERROR
    }

    private final LlmClient llmClient;
    private final MemoryClient memoryClient;

    @Value("${jarvis.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${memory.enabled:false}")
    private boolean memoryEnabled;

    @Getter
    private final AtomicReference<State> currentState = new AtomicReference<>(State.DOWN);

    @Getter
    private volatile String stateReason = "not started";

    @Getter
    private volatile Instant lastStateChange = Instant.now();

    @Getter
    private volatile boolean warmupComplete = false;

    public LlmLifecycleManager(LlmClient llmClient, MemoryClient memoryClient) {
        this.llmClient = llmClient;
        this.memoryClient = memoryClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!llmEnabled) {
            transition(State.DOWN, "LLM disabled (jarvis.llm.enabled=false)");
            return;
        }

        transition(State.STARTING, "application ready, checking LLM backend");
        refreshState();
    }

    public State getState() {
        return currentState.get();
    }

    /**
     * Refresh lifecycle state from actual backend health.
     * Called by health endpoint and periodically.
     */
    public void refreshState() {
        if (!llmEnabled) {
            transition(State.DOWN, "LLM disabled");
            return;
        }

        try {
            LlmClient.LlmServerHealth health = llmClient.getHealth();

            if (!health.available()) {
                String reason = health.error() != null ? health.error() : "llm-server unavailable";
                transition(State.ERROR, reason);
                warmupComplete = false;
                return;
            }

            if (!health.modelLoaded()) {
                transition(State.WARMING_UP, "model loading");
                warmupComplete = false;
                return;
            }

            boolean memoryOk = !memoryEnabled || memoryClient.isHealthy();

            if (health.modelLoaded() && !warmupComplete) {
                warmupComplete = true;
                log.info("LLM warmup complete (model loaded and responding)");
            }

            if (!memoryOk) {
                transition(State.DEGRADED, "memory-service unavailable");
            } else {
                transition(State.READY, "all systems operational");
            }
        } catch (Exception e) {
            transition(State.ERROR, "health check failed: " + e.getMessage());
        }
    }

    public boolean isReady() {
        return currentState.get() == State.READY;
    }

    public boolean isUsable() {
        State s = currentState.get();
        return s == State.READY || s == State.DEGRADED;
    }

    private void transition(State newState, String reason) {
        State old = currentState.getAndSet(newState);
        if (old != newState) {
            this.stateReason = reason;
            this.lastStateChange = Instant.now();
            log.info("LLM lifecycle: {} -> {} ({})", old, newState, reason);
        }
    }
}
