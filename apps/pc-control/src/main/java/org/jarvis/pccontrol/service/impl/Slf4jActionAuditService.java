package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.PcActionExecutionStatus;
import org.jarvis.pccontrol.service.ActionAuditService;
import org.springframework.stereotype.Service;

/**
 * Writes a structured, single-line audit record for every executed PC-control
 * action to the application log. Kept deliberately simple (log-based rather than
 * a dedicated audit store) since every log line already flows through this
 * service's centralized logging pipeline.
 */
@Slf4j
@Service
public class Slf4jActionAuditService implements ActionAuditService {

    @Override
    public void record(String stepId, String actionType, PcActionExecutionStatus status, String message) {
        log.info("AUDIT action_type={} step_id={} status={} message=\"{}\"",
                actionType, stepId, status, sanitize(message));
    }

    /**
     * Strips characters that would break the single-line, quoted structure of the
     * audit record (e.g. a message containing a newline could be used to forge
     * additional-looking log lines). Callers are still responsible for never
     * passing secret content in {@code message}.
     */
    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }
}
