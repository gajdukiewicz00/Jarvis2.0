package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.model.PcActionExecutionStatus;

/**
 * Records a structured audit trail entry for every PC-control action that is
 * actually attempted (as opposed to being rejected before any execution, e.g. by
 * {@link org.jarvis.pccontrol.security.CommandValidator}).
 *
 * <p>Implementations MUST NOT be given, and must never emit, raw sensitive
 * payloads (typed text, credentials embedded in URLs, etc.). Callers are
 * responsible for passing only messages that have already been redacted -
 * see {@code DefaultPcActionExecutionService} step definitions for the
 * redaction convention (e.g. TYPE_TEXT reports a character count, never the
 * literal text).
 */
public interface ActionAuditService {

    void record(String stepId, String actionType, PcActionExecutionStatus status, String message);
}
