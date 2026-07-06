package org.jarvis.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.safety.SystemPanicState;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * When {@link SystemPanicState} is engaged, refuse every action-bearing request
 * (agent execute, voice dispatch, tool routes, PC-desktop control, agent-swarm
 * proxy, pc-control action dispatch) with 423 Locked. Read-only requests and the
 * panic control endpoints stay reachable so the operator can inspect state and
 * clear the panic.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@RequiredArgsConstructor
public class PanicGuardFilter extends OncePerRequestFilter {

    private static final List<String> BLOCKED_PREFIXES = List.of(
            "/api/v1/agent/execute",
            "/api/v1/orchestrator/voice",
            "/api/v1/tools",
            // PcDesktopController (/api/v1/pc/desktop/action) and the generic
            // PcControlProxyController passthrough share this prefix.
            "/api/v1/pc",
            // AgentServiceProxyController: agent-swarm run + task proxy (plural "agents").
            // Deliberately distinct from "/api/v1/agent" (singular) so the panic
            // control endpoints below (/api/v1/agent/panic*, /register, /heartbeat)
            // stay reachable while panic is engaged.
            "/api/v1/agents",
            // PcControlInternalController: internal action dispatch to desktop clients.
            "/internal/pc-control");

    private final SystemPanicState panicState;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (panicState.isEngaged() && isActionPath(request)) {
            log.warn("🚨 PANIC: blocking {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.LOCKED.value()); // 423
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"system_panic_engaged\",\"message\":\"All actions are halted by the global kill switch.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isActionPath(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)
                && !"PATCH".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return false;
        }
        String uri = request.getRequestURI();
        return BLOCKED_PREFIXES.stream().anyMatch(uri::startsWith);
    }
}
