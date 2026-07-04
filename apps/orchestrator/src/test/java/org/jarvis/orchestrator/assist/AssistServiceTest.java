package org.jarvis.orchestrator.assist;

import org.jarvis.orchestrator.dto.AssistRequest;
import org.jarvis.orchestrator.dto.AssistResponse;
import org.jarvis.orchestrator.dto.ProposedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistServiceTest {

    private LlmReasoner reasoner;
    private AssistMemory memory;
    private HostActionExecutor executor;
    private AssistService service;

    @BeforeEach
    void setUp() {
        reasoner = mock(LlmReasoner.class);
        memory = mock(AssistMemory.class);
        executor = mock(HostActionExecutor.class);
        service = new AssistService(reasoner, memory, new ActionSafetyPolicy(), executor);
        when(memory.readRecent(anyString(), anyString(), anyString())).thenReturn(List.of("prev note"));
        when(memory.write(anyString(), anyString(), any(), any(), any(), anyString())).thenReturn("memory:assist-owner");
    }

    private AssistRequest req(String mode, String token) {
        return new AssistRequest("look at my screen", mode, true, true, false,
                Map.of("activeWindowTitle", "Terminal"), token, "owner");
    }

    @Test
    void blankCommandIsRejected() {
        AssistResponse r = service.assist(new AssistRequest("  ", "dry-run", true, true, false, null, null, "owner"), "c1");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).isEqualTo("command_required");
    }

    @Test
    void llmUnavailableIsHonest_noFakeAnswer() {
        when(reasoner.reason(any(), any(), any(), any(), any())).thenReturn(LlmReasoner.Reasoning.unavailable("llm_unavailable: X"));
        AssistResponse r = service.assist(req("dry-run", null), "c2");
        assertThat(r.success()).isFalse();
        assertThat(r.answer()).isNull();
        assertThat(r.error()).contains("llm_unavailable");
        verify(executor, never()).execute(any(), anyString());
    }

    @Test
    void dryRunProposesButExecutesNothing() {
        when(reasoner.reason(any(), any(), any(), any(), any()))
                .thenReturn(LlmReasoner.Reasoning.of("You are in a terminal.", "OPEN_APP", "terminal"));
        AssistResponse r = service.assist(req("dry-run", null), "c3");
        assertThat(r.success()).isTrue();
        assertThat(r.proposedActions()).hasSize(1);
        assertThat(r.proposedActions().get(0).type()).isEqualTo("OPEN_APP");
        assertThat(r.executedActions()).isEmpty();
        assertThat(r.requiresConfirmation()).isFalse();
        assertThat(r.memory().written()).containsExactly("memory:assist-owner");
        verify(executor, never()).execute(any(), anyString());
    }

    @Test
    void confirmModeRequiresConfirmation() {
        when(reasoner.reason(any(), any(), any(), any(), any()))
                .thenReturn(LlmReasoner.Reasoning.of("ok", "OPEN_URL", "https://x"));
        AssistResponse r = service.assist(req("confirm", null), "c4");
        assertThat(r.requiresConfirmation()).isTrue();
        assertThat(r.executedActions()).isEmpty();
        verify(executor, never()).execute(any(), anyString());
    }

    @Test
    void executeSafeActionCallsExecutor() {
        when(reasoner.reason(any(), any(), any(), any(), any()))
                .thenReturn(LlmReasoner.Reasoning.of("ok", "OPEN_APP", "terminal"));
        when(executor.execute(any(), anyString()))
                .thenReturn(new HostActionExecutor.ExecResult(true, "gnome-terminal", "executed"));
        AssistResponse r = service.assist(req("execute", null), "c5");
        assertThat(r.executedActions()).hasSize(1);
        assertThat(r.requiresConfirmation()).isFalse();
        verify(executor).execute(any(ProposedAction.class), anyString());
    }

    @Test
    void executeDangerousActionIsRefused() {
        when(reasoner.reason(any(), any(), any(), any(), any()))
                .thenReturn(LlmReasoner.Reasoning.of("ok", "DELETE_FILE", "/etc/passwd"));
        AssistResponse r = service.assist(req("execute", null), "c6");
        assertThat(r.requiresConfirmation()).isTrue();
        assertThat(r.executedActions()).isEmpty();
        assertThat(r.proposedActions().get(0).classification()).isEqualTo("DANGEROUS");
        assertThat(r.proposedActions().get(0).reason()).contains("refused");
        verify(executor, never()).execute(any(), anyString());
    }

    @Test
    void executeUnknownActionIsRefused() {
        when(reasoner.reason(any(), any(), any(), any(), any()))
                .thenReturn(LlmReasoner.Reasoning.of("ok", "FROBNICATE", "x"));
        AssistResponse r = service.assist(req("execute", null), "c7");
        assertThat(r.requiresConfirmation()).isTrue();
        assertThat(r.executedActions()).isEmpty();
        assertThat(r.proposedActions().get(0).classification()).isEqualTo("UNKNOWN");
        verify(executor, never()).execute(any(), anyString());
    }

    @Test
    void guardedActionNeedsTokenInExecuteMode() {
        when(reasoner.reason(any(), any(), any(), any(), any()))
                .thenReturn(LlmReasoner.Reasoning.of("ok", "TYPE_TEXT", "hello"));
        AssistResponse noTok = service.assist(req("execute", null), "c8");
        assertThat(noTok.requiresConfirmation()).isTrue();
        assertThat(noTok.executedActions()).isEmpty();
        verify(executor, never()).execute(any(), anyString());

        when(executor.execute(any(), anyString()))
                .thenReturn(new HostActionExecutor.ExecResult(true, "hello", "executed"));
        AssistResponse withTok = service.assist(req("execute", "confirm-123"), "c9");
        assertThat(withTok.executedActions()).hasSize(1);
    }

    @Test
    void secretLookingTextIsRedactedFromAnswer() {
        when(reasoner.reason(any(), any(), any(), any(), any()))
                .thenReturn(LlmReasoner.Reasoning.of("your api_key=SUPERSECRETVALUE12345", "NONE", ""));
        AssistResponse r = service.assist(req("dry-run", null), "c10");
        assertThat(r.answer()).doesNotContain("SUPERSECRETVALUE12345");
        assertThat(r.answer()).contains("<redacted>");
    }
}
