package org.jarvis.orchestrator.assist;

import org.jarvis.orchestrator.dto.ProposedAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Honest default executor: an in-cluster orchestrator pod cannot reach the host
 * X11 display, so it never claims to have run a desktop action. The host bridge
 * ({@code scripts/jarvis-host-bridge.sh action}) performs the real execution.
 */
@Component
@ConditionalOnMissingBean(value = HostActionExecutor.class,
        ignored = DelegatedHostActionExecutor.class)
public class DelegatedHostActionExecutor implements HostActionExecutor {
    @Override
    public ExecResult execute(ProposedAction action, String correlationId) {
        return new ExecResult(false, action.target(),
                "delegated-to-host-bridge: in-cluster orchestrator does not control the host desktop");
    }
}
