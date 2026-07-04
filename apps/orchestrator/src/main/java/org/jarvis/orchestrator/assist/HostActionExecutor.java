package org.jarvis.orchestrator.assist;

import org.jarvis.orchestrator.dto.ProposedAction;

/**
 * Executes a (already safety-checked) desktop action. The default in-cluster
 * implementation does NOT actually touch the host desktop — that is physically
 * impossible from a k3s pod — so it honestly delegates to the host bridge.
 */
public interface HostActionExecutor {

    record ExecResult(boolean executed, String detail, String reason) {}

    ExecResult execute(ProposedAction action, String correlationId);
}
