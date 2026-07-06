package org.jarvis.apigateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proxies {@code /api/v1/agents/**} to agent-service — the role catalog
 * ({@code GET /api/v1/agents/roles}), agent task lifecycle
 * ({@code /api/v1/agents/tasks/**}), and swarm runs ({@code /api/v1/agents/swarm/**}).
 * <p>
 * Deliberately plural ("agents") to match agent-service's actual controller mappings
 * ({@code RoleCatalogController}, {@code AgentTaskController}, {@code SwarmController})
 * and to avoid colliding with the gateway-local, singular {@code /api/v1/agent/**}
 * endpoints already served in-process by
 * {@link org.jarvis.apigateway.agent.AgentControlController} and
 * {@link org.jarvis.apigateway.agent.AgentExecutionController} (register, heartbeat,
 * panic, execute) — a wildcard proxy at the singular prefix would ambiguously overlap
 * those existing {@code @RequestMapping}s.
 */
@Tag(name = "Agent Service Proxy",
        description = "Proxies /api/v1/agents/** to agent-service: role catalog, agent task lifecycle, "
                + "and swarm runs. Requires an authenticated gateway user; the internal X-Service-Token "
                + "is attached automatically before forwarding.")
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentServiceProxyController {

    private final DownstreamProxyService downstreamProxyService;

    @Value("${services.agent-service.url}")
    private String agentServiceUrl;

    @Operation(summary = "Forward request to agent-service",
            description = "Wildcard proxy for GET/POST/PUT/PATCH/DELETE under /api/v1/agents/**, including "
                    + "roles, tasks/**, and swarm/**.")
    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return downstreamProxyService.forward(request, "agent-service", agentServiceUrl);
    }
}
