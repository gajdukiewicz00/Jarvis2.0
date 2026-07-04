package org.jarvis.orchestrator.assist;

import org.jarvis.orchestrator.client.MemoryServiceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistMemoryImplTest {

    @Mock
    private MemoryServiceClient memory;

    private AssistMemoryImpl assistMemory;

    private AssistMemoryImpl newInstance() {
        return new AssistMemoryImpl(memory);
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

        List<String> out = assistMemory.readRecent("user-1", "goals", "cid-1");

        assertThat(out).containsExactly("first memory", "second memory", "third memory");
    }

    @Test
    void readRecentFallsBackToChunksKeyWhenResultsAbsent() {
        assistMemory = newInstance();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("chunks", List.of(Map.of("text", "chunked memory")));
        when(memory.search(any(), eq("cid-2"))).thenReturn(resp);

        List<String> out = assistMemory.readRecent("user-1", "goals", "cid-2");

        assertThat(out).containsExactly("chunked memory");
    }

    @Test
    void readRecentStringifiesNonMapListEntries() {
        assistMemory = newInstance();
        Map<String, Object> resp = Map.of("results", List.of("raw-string-memory", 42));
        when(memory.search(any(), eq("cid-3"))).thenReturn(resp);

        List<String> out = assistMemory.readRecent("user-1", "goals", "cid-3");

        assertThat(out).containsExactly("raw-string-memory", "42");
    }

    @Test
    void readRecentReturnsEmptyWhenResultsIsNotAList() {
        assistMemory = newInstance();
        Map<String, Object> resp = Map.of("results", "not-a-list");
        when(memory.search(any(), eq("cid-4"))).thenReturn(resp);

        assertThat(assistMemory.readRecent("user-1", "goals", "cid-4")).isEmpty();
    }

    @Test
    void readRecentReturnsEmptyWhenResponseIsNull() {
        assistMemory = newInstance();
        when(memory.search(any(), eq("cid-5"))).thenReturn(null);

        assertThat(assistMemory.readRecent("user-1", "goals", "cid-5")).isEmpty();
    }

    @Test
    void readRecentDegradesToEmptyOnMemoryServiceFailure() {
        assistMemory = newInstance();
        when(memory.search(any(), eq("cid-6"))).thenThrow(new RuntimeException("memory-service down"));

        assertThat(assistMemory.readRecent("user-1", "goals", "cid-6")).isEmpty();
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
}
