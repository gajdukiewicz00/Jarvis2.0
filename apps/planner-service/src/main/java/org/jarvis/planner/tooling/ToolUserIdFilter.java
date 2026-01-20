package org.jarvis.planner.tooling;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class ToolUserIdFilter extends OncePerRequestFilter {

    private static final String TOOL_PATH_PREFIX = "/api/v1/tools/";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ID_ATTRIBUTE = "toolUserId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith(TOOL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            log.warn("Tool request missing X-User-Id: {}", path);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing_user_id\"}");
            return;
        }

        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        filterChain.doFilter(request, response);
    }
}
