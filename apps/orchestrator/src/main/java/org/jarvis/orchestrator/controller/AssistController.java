package org.jarvis.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.orchestrator.assist.AssistService;
import org.jarvis.orchestrator.dto.AssistRequest;
import org.jarvis.orchestrator.dto.AssistResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code POST /api/v1/orchestrator/assist} — the cluster "brain" turn:
 * reason over host-supplied screen context + memory with the local LLM, propose
 * a safe next action, and (optionally) delegate execution to the host bridge.
 */
@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class AssistController {

    private final AssistService assistService;

    @PostMapping("/assist")
    public AssistResponse assist(
            @RequestBody AssistRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        String cid = (correlationId == null || correlationId.isBlank())
                ? "assist-" + UUID.randomUUID() : correlationId;
        return assistService.assist(request, cid);
    }
}
