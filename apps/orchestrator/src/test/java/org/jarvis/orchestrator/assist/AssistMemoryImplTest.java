package org.jarvis.orchestrator.assist;

import org.jarvis.orchestrator.client.MemoryServiceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistMemoryImplTest {

    @Mock
    private MemoryServiceClient memory;

    private AssistMemoryImpl assistMemory;

    private AssistMemoryImpl newInstance() {
        AssistMemoryImpl impl = new AssistMemoryImpl(memory);
        // Keep retry/backoff fast and deterministic for tests.
        ReflectionTestUtils.setField(impl, "retryInitialBackoffMs", 1L);
        return impl;
    }

    @Test
    void readRecentExtractsTextFromResultsKey() {
        assistMemory = newInstance();
        Map<String, Object> resp = Map.of(
                "results", List.of(
                        Map.of("text", "first memory"),
                        Map.of("content", "second memory"),
                        Map.of("excerpt", "third memory")));
        when(memory.search(any(), eq("cid-1"))).thenReturn(resp);

        AssistMemory.ReadOutcome out = assistMemory.readRecent("user-1", "goals", "cid-1");

        assertThat(out.degraded()).isFalse();
        assertThat(out.items()).containsExactly("first memory", "second memory", "third memory");
    }

    @Test
    void readRecentFallsBackToChunksKeyWhenResultsAbsent() {
        assistMemory = newInstance();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("chunks", List.of(Map.of("text", "chunked memory")));
        when(memory.search(any(), eq("cid-2"))).thenReturn(resp);

        AssistMemory.ReadOutcome out = assistMemory.readRecent("user-1", "goals", "cid-2");

        assertThat(out.items()).containsExactly("chunked memory");
    }

    @Test
    void readRecentStringifiesNonMapListEntries() {
        assistMemory = newInstance();
        Map<String, Object> resp = Map.of("results", List.of("raw-string-memory", 42));
        when(memory.search(any(), eq("cid-3"))).thenReturn(resp);

        AssistMemory.ReadOutcome out = assistMemory.readRecent("user-1", "goals", "cid-3");

        assertThat(out.items()).containsExactly("raw-string-memory", "42");
    }

    @Test
    void readRecentReturnsEmptyWhenResultsIsNotAList() {
        assistMemory = newInstance();
        Map<String, Object> resp = Map.of("results", "not-a-list");
        when(memory.search(any(), eq("cid-4"))).thenReturn(resp);

        AssistMemory.ReadOutcome out = assistMemory.readRecent("user-1", "goals", "cid-4");
        assertThat(out.items()).isEmpty();
        assertThat(out.degraded()).isFalse();
    }

    @Test
    void readRecentReturnsEmptyWhenResponseIsNull() {
        assistMemory = newInstance();
        when(memory.search(any(), eq("cid-5"))).thenReturn(null);

        AssistMemory.ReadOutcome out = assistMemory.readRecent("user-1", "goals", "cid-5");
        assertThat(out.items()).isEmpty();
        assertThat(out.degraded()).isFalse();
    }

    @Test
    void readRecentDegradesAfterRetriesExhaustedOnMemoryServiceFailure() {
        assistMemory = newInstance();
        ReflectionTestUtils.setField(assistMemory, "retryMaxAttempts", 2);
        when(memory.search(any(), eq("cid-6"))).thenThrow(new RuntimeException("memory-service down"));

        AssistMemory.ReadOutcome out = assistMemory.readRecent("user-1", "goals", "cid-6");

        assertThat(out.degraded()).isTrue();
        assertThat(out.items()).isEmpty();
        // maxAttempts=2 -> the read is retried once before giving up.
        verify(memory, times(2)).search(any(), eq("cid-6"));
    }

    @Test
    void readRecentRetriesThenSucceeds() {
        assistMemory = newInstance();
        ReflectionTestUtils.setField(assistMemory, "retryMaxAttempts", 3);
        Map<String, Object> resp = Map.of("results", List.of(Map.of("text", "recovered memory")));
        when(memory.search(any(), eq("cid-6b")))
                .thenThrow(new RuntimeException("transient"))
                .thenReturn(resp);

        AssistMemory.ReadOutcome out = assistMemory.readRecent("user-1", "goals", "cid-6b");

        assertThat(out.degraded()).isFalse();
        assertThat(out.items()).containsExactly("recovered memory");
        verify(memory, times(2)).search(any(), eq("cid-6b"));
    }

    @Test
    void circuitBreakerOpensAfterConsecutiveFailuresAndSkipsFurtherCalls() {
        assistMemory = newInstance();
        ReflectionTestUtils.setField(assistMemory, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(assistMemory, "failureThreshold", 1);
        ReflectionTestUtils.setField(assistMemory, "resetTimeoutSeconds", 60);
        when(memory.search(any(), anyString())).thenThrow(new RuntimeException("memory-service down"));

        AssistMemory.ReadOutcome first = assistMemory.readRecent("user-1", "goals", "cid-7a");
        AssistMemory.ReadOutcome second = assistMemory.readRecent("user-1", "goals", "cid-7b");

        assertThat(first.degraded()).isTrue();
        assertThat(second.degraded()).isTrue();
        // Breaker opened after the first failure; the second call must not reach memory-service at all.
        verify(memory, times(1)).search(any(), anyString());
    }

    @Test
    void writeSendsIngestRequestAndReturnsMemoryReference() {
        assistMemory = newInstance();

        String ref = assistMemory.write("user-1", "open browser", "Opening now, sir.",
                "screen summary", "action ok", "cid-7");

        assertThat(ref).isEqualTo("memory:assist-user-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(memory).ingest(captor.capture(), eq("cid-7"));
        Map<String, Object> req = captor.getValue();
        assertThat(req.get("userId")).isEqualTo("user-1");
        assertThat(req.get("sessionId")).isEqualTo("assist-user-1");
        assertThat(req.get("createChunks")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) req.get("metadata");
        assertThat(meta.get("screenSummary")).isEqualTo("screen summary");
        assertThat(meta.get("actionResult")).isEqualTo("action ok");
    }

    @Test
    void writeHandlesNullAnswerAsEmptyAssistantMessage() {
        assistMemory = newInstance();

        assistMemory.write("user-1", "open browser", null, null, null, "cid-8");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(memory).ingest(captor.capture(), eq("cid-8"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) captor.getValue().get("messages");
        assertThat(messages.get(1).get("content")).isEqualTo("");
    }

    @Test
    void writeDegradesToSkippedOnMemoryServiceFailure() {
        assistMemory = newInstance();
        doThrow(new IllegalStateException("ingest failed")).when(memory).ingest(any(), anyString());

        String ref = assistMemory.write("user-1", "cmd", "answer", null, null, "cid-9");

        assertThat(ref).isEqualTo("skipped:IllegalStateException");
    }

    @Test
    void writeSkipsCallWhenCircuitBreakerOpen() {
        assistMemory = newInstance();
        ReflectionTestUtils.setField(assistMemory, "failureThreshold", 1);
        ReflectionTestUtils.setField(assistMemory, "resetTimeoutSeconds", 60);
        doThrow(new RuntimeException("down")).when(memory).ingest(any(), anyString());

        String first = assistMemory.write("user-1", "cmd", "answer", null, null, "cid-10a");
        String second = assistMemory.write("user-1", "cmd", "answer", null, null, "cid-10b");

        assertThat(first).isEqualTo("skipped:RuntimeException");
        assertThat(second).isEqualTo("skipped:circuit-open");
        verify(memory, times(1)).ingest(any(), anyString());
    }
}
