package org.jarvis.orchestrator.dto;

import java.util.Map;

/**
 * Request for {@code POST /api/v1/orchestrator/assist}.
 *
 * <p>{@code screenContext} is supplied by the host bridge (the in-cluster
 * orchestrator cannot capture the host screen); when {@code useScreen=true} and
 * it is absent, the caller is expected to capture it first.</p>
 */
public record AssistRequest(
        String command,
        String mode,                       // dry-run | confirm | execute
        Boolean useScreen,
        Boolean useMemory,
        Boolean speak,
        Map<String, Object> screenContext,
        String confirmationToken,
        String userId) {

    public String modeOrDefault() {
        return (mode == null || mode.isBlank()) ? "dry-run" : mode.trim().toLowerCase();
    }
    public boolean wantScreen() { return useScreen == null || useScreen; }
    public boolean wantMemory() { return useMemory == null || useMemory; }
    public boolean wantSpeak()  { return speak != null && speak; }
    public String userOrOwner() { return (userId == null || userId.isBlank()) ? "owner" : userId; }
}
