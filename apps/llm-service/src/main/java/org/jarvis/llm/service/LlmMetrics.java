package org.jarvis.llm.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for the LLM inference path.
 * Exposes request counts, latency, tokens, queue depth, and failure modes.
 */
@Component
public class LlmMetrics {

    private final Timer chatLatency;
    private final Timer orchestrationLatency;
    private final Counter chatRequests;
    private final Counter chatFailures;
    private final Counter chatTimeouts;
    private final Counter orchestrationRequests;
    private final Counter orchestrationFailures;
    private final Counter toolCallsPlanned;
    private final Counter toolCallsRejected;
    private final Counter admissionRejected;
    private final Counter admissionTimeout;
    private final Counter tokensIn;
    private final Counter tokensOut;
    private final Counter fallbackUsed;
    private final Counter warmStarts;
    private final Counter coldStarts;

    public LlmMetrics(MeterRegistry registry, LlmAdmissionController admissionController) {
        this.chatLatency = Timer.builder("llm.chat.latency")
                .description("Chat request latency").register(registry);
        this.orchestrationLatency = Timer.builder("llm.orchestration.latency")
                .description("Orchestration request latency").register(registry);

        this.chatRequests = Counter.builder("llm.chat.requests")
                .description("Total chat requests").register(registry);
        this.chatFailures = Counter.builder("llm.chat.failures")
                .description("Chat request failures").register(registry);
        this.chatTimeouts = Counter.builder("llm.chat.timeouts")
                .description("Chat request timeouts").register(registry);

        this.orchestrationRequests = Counter.builder("llm.orchestration.requests")
                .description("Total orchestration requests").register(registry);
        this.orchestrationFailures = Counter.builder("llm.orchestration.failures")
                .description("Orchestration request failures").register(registry);

        this.toolCallsPlanned = Counter.builder("llm.tool.calls.planned")
                .description("Tool calls planned by LLM").register(registry);
        this.toolCallsRejected = Counter.builder("llm.tool.calls.rejected")
                .description("Tool calls rejected by validation").register(registry);

        this.admissionRejected = Counter.builder("llm.admission.rejected")
                .description("Requests rejected by admission control").register(registry);
        this.admissionTimeout = Counter.builder("llm.admission.timeout")
                .description("Requests timed out waiting for admission").register(registry);

        this.tokensIn = Counter.builder("llm.tokens.in")
                .description("Input tokens").register(registry);
        this.tokensOut = Counter.builder("llm.tokens.out")
                .description("Output tokens").register(registry);

        this.fallbackUsed = Counter.builder("llm.fallback.used")
                .description("Deterministic fallback used instead of LLM").register(registry);
        this.warmStarts = Counter.builder("llm.starts.warm")
                .description("Warm start count").register(registry);
        this.coldStarts = Counter.builder("llm.starts.cold")
                .description("Cold start count").register(registry);

        Gauge.builder("llm.admission.active", admissionController, LlmAdmissionController::getActiveInferences)
                .description("Active inferences").register(registry);
        Gauge.builder("llm.admission.queue", admissionController, LlmAdmissionController::getQueueDepth)
                .description("Queue depth").register(registry);
        Gauge.builder("llm.admission.available", admissionController, LlmAdmissionController::getAvailablePermits)
                .description("Available inference slots").register(registry);
    }

    public void recordChatRequest() { chatRequests.increment(); }
    public void recordChatFailure() { chatFailures.increment(); }
    public void recordChatTimeout() { chatTimeouts.increment(); }
    public void recordChatLatency(long ms) { chatLatency.record(ms, TimeUnit.MILLISECONDS); }

    public void recordOrchestrationRequest() { orchestrationRequests.increment(); }
    public void recordOrchestrationFailure() { orchestrationFailures.increment(); }
    public void recordOrchestrationLatency(long ms) { orchestrationLatency.record(ms, TimeUnit.MILLISECONDS); }

    public void recordToolCallPlanned() { toolCallsPlanned.increment(); }
    public void recordToolCallRejected() { toolCallsRejected.increment(); }

    public void recordAdmissionRejected() { admissionRejected.increment(); }
    public void recordAdmissionTimeout() { admissionTimeout.increment(); }

    public void recordTokens(long in, long out) {
        tokensIn.increment(in);
        tokensOut.increment(out);
    }

    public void recordFallbackUsed() { fallbackUsed.increment(); }
    public void recordWarmStart() { warmStarts.increment(); }
    public void recordColdStart() { coldStarts.increment(); }
}
