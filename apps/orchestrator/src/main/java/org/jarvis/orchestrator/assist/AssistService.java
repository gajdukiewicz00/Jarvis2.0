package org.jarvis.orchestrator.assist;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.dto.AssistRequest;
import org.jarvis.orchestrator.dto.AssistResponse;
import org.jarvis.orchestrator.dto.ProposedAction;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates one assist turn: (optional) memory read -> local LLM reasoning
 * over the host-supplied screen context -> safety classification of the
 * proposed action -> mode-gated execution -> memory write -> audit.
 *
 * <p>Desktop actions are NEVER executed in-cluster (a pod cannot reach the host
 * display); execution is delegated to the host bridge. Dangerous/unknown
 * actions are refused; guarded actions need a confirmation token in execute
 * mode. Safe by default: dry-run executes nothing.</p>
 */
@Slf4j
@Service
public class AssistService {

    private final LlmReasoner reasoner;
    private final AssistMemory memory;
    private final ActionSafetyPolicy policy;
    private final HostActionExecutor executor;

    public AssistService(LlmReasoner reasoner, AssistMemory memory,
                         ActionSafetyPolicy policy, HostActionExecutor executor) {
        this.reasoner = reasoner;
        this.memory = memory;
        this.policy = policy;
        this.executor = executor;
    }

    public AssistResponse assist(AssistRequest req, String correlationId) {
        String command = req == null || req.command() == null ? "" : req.command().trim();
        if (command.isEmpty()) {
            return new AssistResponse(command, "assist.freeform", null,
                    new AssistResponse.Memory(List.of(), List.of()),
                    null, List.of(), List.of(), false, false, "command_required");
        }
        String mode = req.modeOrDefault();
        String userId = req.userOrOwner();
        Map<String, Object> screen = req.wantScreen() ? req.screenContext() : null;

        List<String> memRead = req.wantMemory()
                ? memory.readRecent(userId, command, correlationId) : List.of();

        LlmReasoner.Reasoning r = reasoner.reason(command, screen, memRead, correlationId, userId);
        if (!r.available()) {
            log.info("assist cid={} llm unavailable: {}", correlationId, r.error());
            return new AssistResponse(command, "assist.freeform", screen,
                    new AssistResponse.Memory(memRead, List.of()),
                    null, List.of(), List.of(), false, false, r.error());
        }

        String answer = SecretRedactor.redact(r.answer());
        String type = r.actionType() == null ? "NONE" : r.actionType().trim().toUpperCase();
        ActionSafetyPolicy.SafetyClass cls = policy.classify(type);

        List<ProposedAction> proposed = new ArrayList<>();
        List<ProposedAction> executed = new ArrayList<>();
        boolean requiresConfirmation = false;

        if (!"NONE".equals(type)) {
            ProposedAction action = new ProposedAction(type, SecretRedactor.redact(r.actionTarget()),
                    cls.name(), null);
            switch (mode) {
                case "execute" -> {
                    if (cls == ActionSafetyPolicy.SafetyClass.DANGEROUS
                            || cls == ActionSafetyPolicy.SafetyClass.UNKNOWN) {
                        requiresConfirmation = true;
                        action = action.withReason("refused: " + cls + " action is never auto-executed");
                    } else if (cls == ActionSafetyPolicy.SafetyClass.GUARDED && !hasToken(req)) {
                        requiresConfirmation = true;
                        action = action.withReason("guarded: confirmation token required to execute");
                    } else {
                        HostActionExecutor.ExecResult ex = executor.execute(action, correlationId);
                        if (ex.executed()) {
                            executed.add(action.withReason(ex.reason()));
                        } else {
                            action = action.withReason(ex.reason()); // honest: delegated to host bridge
                        }
                    }
                }
                case "confirm" -> {
                    requiresConfirmation = true;
                    action = action.withReason("confirm mode: awaiting approval");
                }
                default -> action = action.withReason("dry-run: not executed"); // dry-run / unknown mode
            }
            proposed.add(action);
        }

        List<String> written = List.of();
        if (req.wantMemory()) {
            String w = memory.write(userId, command, answer, screenSummary(screen),
                    executed.isEmpty() ? "none" : executed.get(0).reason(), correlationId);
            written = List.of(w);
        }

        log.info("assist cid={} mode={} user={} actionType={} class={} requiresConfirmation={} executed={}",
                correlationId, mode, userId, type, cls, requiresConfirmation, executed.size());

        return new AssistResponse(command, "assist.freeform", screen,
                new AssistResponse.Memory(memRead, written),
                answer, proposed, executed, requiresConfirmation, true, null);
    }

    private boolean hasToken(AssistRequest req) {
        return req.confirmationToken() != null && !req.confirmationToken().isBlank();
    }

    private static String screenSummary(Map<String, Object> screen) {
        if (screen == null) return "";
        Object w = screen.get("activeWindowTitle");
        return w == null ? "" : SecretRedactor.redact(w.toString());
    }
}
