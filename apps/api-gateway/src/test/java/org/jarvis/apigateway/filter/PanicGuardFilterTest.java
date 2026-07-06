package org.jarvis.apigateway.filter;

import org.jarvis.common.safety.SystemPanicState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the panic kill-switch actually halts every action-executing route,
 * including the PC-desktop control, agent-swarm proxy, and internal pc-control
 * dispatch routes (CRITICAL finding #2: these were missing from
 * {@code BLOCKED_PREFIXES}, so panic did not stop real desktop/agent actions).
 */
class PanicGuardFilterTest {

    private SystemPanicState panicState;
    private PanicGuardFilter filter;

    @BeforeEach
    void setUp() {
        panicState = new SystemPanicState();
        filter = new PanicGuardFilter(panicState);
    }

    @Test
    void panicEngagedBlocksPcDesktopActionRouteWith423() throws Exception {
        panicState.engage("operator", "drill", 1L);
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pc/desktop/action");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(response.getStatus()).isEqualTo(423);
        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getContentAsString()).contains("system_panic_engaged");
    }

    @Test
    void panicEngagedBlocksGenericPcControlProxyRouteWith423() throws Exception {
        panicState.engage("operator", "drill", 1L);
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pc/some-action");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(response.getStatus()).isEqualTo(423);
        assertThat(chainInvoked.get()).isFalse();
    }

    @Test
    void panicEngagedBlocksAgentSwarmProxyRouteWith423() throws Exception {
        panicState.engage("operator", "drill", 1L);
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/swarm");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(response.getStatus()).isEqualTo(423);
        assertThat(chainInvoked.get()).isFalse();
    }

    @Test
    void panicEngagedBlocksInternalPcControlDispatchRouteWith423() throws Exception {
        panicState.engage("operator", "drill", 1L);
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/pc-control/action");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(response.getStatus()).isEqualTo(423);
        assertThat(chainInvoked.get()).isFalse();
    }

    @Test
    void panicEngagedDoesNotBlockReadOnlyStatusRoute() throws Exception {
        panicState.engage("operator", "drill", 1L);
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pc/desktop/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void panicEngagedDoesNotBlockPanicClearRoute() throws Exception {
        panicState.engage("operator", "drill", 1L);
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/panic/clear");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void panicNotEngagedAllowsPcDesktopActionRouteThrough() throws Exception {
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pc/desktop/action");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
    }
}
