package org.jarvis.orchestrator.assist;

import org.jarvis.orchestrator.client.LlmServiceClient;
import org.jarvis.orchestrator.dto.LlmChatRequest;
import org.jarvis.orchestrator.dto.LlmChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmReasonerImplTest {

    @Mock
    private LlmServiceClient llm;

    private LlmReasonerImpl reasoner() {
        return new LlmReasonerImpl(llm);
    }

    @Test
    void parsesStrictJsonReplyWithAction() {
        when(llm.chat(any(), eq("cid-1"), eq("user-1"), eq("default")))
                .thenReturn(new LlmChatResponse(
                        "{\"answer\": \"Opening the browser.\", \"action\": {\"type\": \"OPEN_URL\", \"target\": \"https://x.com\"}}",
                        Map.of(), "qwen", 5, "neutral"));

        LlmReasoner.Reasoning result = reasoner().reason("open browser", Map.of(), List.of(), "cid-1", "user-1");

        assertThat(result.available()).isTrue();
        assertThat(result.answer()).isEqualTo("Opening the browser.");
        assertThat(result.actionType()).isEqualTo("OPEN_URL");
        assertThat(result.actionTarget()).isEqualTo("https://x.com");
        assertThat(result.error()).isNull();
    }

    @Test
    void defaultsActionTypeToNoneWhenActionObjectMissingFields() {
        when(llm.chat(any(), anyString(), any(), anyString()))
                .thenReturn(new LlmChatResponse("{\"answer\": \"Sure thing.\"}", Map.of(), "qwen", 1, "neutral"));

        LlmReasoner.Reasoning result = reasoner().reason("cmd", null, null, "cid-2", "user-2");

        assertThat(result.answer()).isEqualTo("Sure thing.");
        assertThat(result.actionType()).isEqualTo("NONE");
        assertThat(result.actionTarget()).isEqualTo("");
    }

    @Test
    void plainTextReplyWithoutJsonFallsBackToRawAnswer() {
        when(llm.chat(any(), anyString(), any(), anyString()))
                .thenReturn(new LlmChatResponse("Just a plain sentence.", Map.of(), "qwen", 1, "neutral"));

        LlmReasoner.Reasoning result = reasoner().reason("cmd", Map.of(), List.of(), "cid-3", "user-3");

        assertThat(result.available()).isTrue();
        assertThat(result.answer()).isEqualTo("Just a plain sentence.");
        assertThat(result.actionType()).isEqualTo("NONE");
        assertThat(result.actionTarget()).isEqualTo("");
    }

    @Test
    void malformedJsonBraceContentFallsBackToRawReply() {
        when(llm.chat(any(), anyString(), any(), anyString()))
                .thenReturn(new LlmChatResponse("prefix {not really json} suffix", Map.of(), "qwen", 1, "neutral"));

        LlmReasoner.Reasoning result = reasoner().reason("cmd", Map.of(), List.of(), "cid-4", "user-4");

        assertThat(result.available()).isTrue();
        assertThat(result.answer()).isEqualTo("prefix {not really json} suffix");
        assertThat(result.actionType()).isEqualTo("NONE");
    }

    @Test
    void nullReplyIsUnavailable() {
        when(llm.chat(any(), anyString(), any(), anyString()))
                .thenReturn(new LlmChatResponse(null, Map.of(), "qwen", 1, "neutral"));

        LlmReasoner.Reasoning result = reasoner().reason("cmd", Map.of(), List.of(), "cid-5", "user-5");

        assertThat(result.available()).isFalse();
        assertThat(result.error()).isEqualTo("llm_empty_reply");
    }

    @Test
    void blankReplyIsUnavailable() {
        when(llm.chat(any(), anyString(), any(), anyString()))
                .thenReturn(new LlmChatResponse("   ", Map.of(), "qwen", 1, "neutral"));

        LlmReasoner.Reasoning result = reasoner().reason("cmd", Map.of(), List.of(), "cid-6", "user-6");

        assertThat(result.available()).isFalse();
        assertThat(result.error()).isEqualTo("llm_empty_reply");
    }

    @Test
    void nullResponseIsUnavailable() {
        when(llm.chat(any(), anyString(), any(), anyString())).thenReturn(null);

        LlmReasoner.Reasoning result = reasoner().reason("cmd", Map.of(), List.of(), "cid-7", "user-7");

        assertThat(result.available()).isFalse();
        assertThat(result.error()).isEqualTo("llm_empty_reply");
    }

    @Test
    void llmFailureProducesUnavailableWithExceptionClassName() {
        when(llm.chat(any(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("llm-service unreachable"));

        LlmReasoner.Reasoning result = reasoner().reason("cmd", Map.of(), List.of(), "cid-8", "user-8");

        assertThat(result.available()).isFalse();
        assertThat(result.error()).isEqualTo("llm_unavailable: IllegalStateException");
    }

    @Test
    void includesScreenContextAndMemoryInUserMessage() {
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        when(llm.chat(captor.capture(), eq("cid-9"), eq("user-9"), eq("default")))
                .thenReturn(new LlmChatResponse("ok", Map.of(), "qwen", 1, "neutral"));

        Map<String, Object> screenContext = Map.of(
                "activeWindowTitle", "Terminal",
                "semanticTags", "code,editor",
                "ocrText", "some ocr text on screen");
        List<String> memory = List.of("memory one", "memory two");

        reasoner().reason("open the ide", screenContext, memory, "cid-9", "user-9");

        LlmChatRequest sent = captor.getValue();
        assertThat(sent.sessionId()).isEqualTo("assist-user-9");
        assertThat(sent.messages()).hasSize(2);
        assertThat(sent.messages().get(0).role()).isEqualTo("system");
        String userMessage = sent.messages().get(1).content();
        assertThat(userMessage).contains("open the ide");
        assertThat(userMessage).contains("Terminal");
        assertThat(userMessage).contains("code,editor");
        assertThat(userMessage).contains("some ocr text on screen");
        assertThat(userMessage).contains("memory one");
        assertThat(userMessage).contains("memory two");
    }

    @Test
    void truncatesLongOcrTextAndMemoryEntries() {
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        when(llm.chat(captor.capture(), eq("cid-10"), eq("user-10"), eq("default")))
                .thenReturn(new LlmChatResponse("ok", Map.of(), "qwen", 1, "neutral"));

        String longOcr = "x".repeat(4000);
        String longMemory = "y".repeat(500);
        Map<String, Object> screenContext = Map.of("ocrText", longOcr);

        reasoner().reason("cmd", screenContext, List.of(longMemory), "cid-10", "user-10");

        String userMessage = captor.getValue().messages().get(1).content();
        assertThat(userMessage).contains("x".repeat(3000) + "…");
        assertThat(userMessage).contains("y".repeat(300) + "…");
    }
}
