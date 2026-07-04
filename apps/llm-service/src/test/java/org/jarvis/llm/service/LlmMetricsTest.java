package org.jarvis.llm.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMetricsTest {

    private SimpleMeterRegistry registry;
    private LlmAdmissionController admissionController;
    private LlmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        admissionController = new LlmAdmissionController(2, 4);
        metrics = new LlmMetrics(registry, admissionController);
    }

    @Test
    void recordsChatRequestsFailuresAndTimeouts() {
        metrics.recordChatRequest();
        metrics.recordChatRequest();
        metrics.recordChatFailure();
        metrics.recordChatTimeout();
        metrics.recordChatLatency(150);

        assertThat(registry.get("llm.chat.requests").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("llm.chat.failures").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.chat.timeouts").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.chat.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsOrchestrationRequestsAndFailures() {
        metrics.recordOrchestrationRequest();
        metrics.recordOrchestrationFailure();
        metrics.recordOrchestrationLatency(75);

        assertThat(registry.get("llm.orchestration.requests").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.orchestration.failures").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.orchestration.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsToolCallOutcomes() {
        metrics.recordToolCallPlanned();
        metrics.recordToolCallPlanned();
        metrics.recordToolCallRejected();

        assertThat(registry.get("llm.tool.calls.planned").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("llm.tool.calls.rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsAdmissionRejectionsAndTimeouts() {
        metrics.recordAdmissionRejected();
        metrics.recordAdmissionTimeout();

        assertThat(registry.get("llm.admission.rejected").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.admission.timeout").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsTokensInAndOut() {
        metrics.recordTokens(10, 20);
        metrics.recordTokens(5, 7);

        assertThat(registry.get("llm.tokens.in").counter().count()).isEqualTo(15.0);
        assertThat(registry.get("llm.tokens.out").counter().count()).isEqualTo(27.0);
    }

    @Test
    void recordsFallbackAndStartCounters() {
        metrics.recordFallbackUsed();
        metrics.recordWarmStart();
        metrics.recordColdStart();
        metrics.recordColdStart();

        assertThat(registry.get("llm.fallback.used").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.starts.warm").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("llm.starts.cold").counter().count()).isEqualTo(2.0);
    }

    @Test
    void gaugesReflectAdmissionControllerState() throws Exception {
        try (LlmAdmissionController.AdmissionTicket ignored =
                admissionController.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 1)) {
            assertThat(registry.get("llm.admission.active").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("llm.admission.available").gauge().value()).isEqualTo(1.0);
        }
        assertThat(registry.get("llm.admission.active").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("llm.admission.queue").gauge().value()).isEqualTo(0.0);
    }
}
