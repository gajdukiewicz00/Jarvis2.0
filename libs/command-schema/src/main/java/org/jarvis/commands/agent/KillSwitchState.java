package org.jarvis.commands.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Phase 6 — state of the agent's emergency kill switch.
 *
 * <p>When {@code engaged=true} the agent must:</p>
 * <ul>
 *   <li>release microphone capture (wake-word + STT);</li>
 *   <li>release webcam / screen capture;</li>
 *   <li>refuse all command-bus deliveries (NACK to DLQ);</li>
 *   <li>refuse all confirmation approvals (auto-deny);</li>
 *   <li>emit a {@code KILL_SWITCH_ENGAGED} {@code AgentEvent} for audit.</li>
 * </ul>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KillSwitchState {

    private boolean engaged;
    private Instant engagedAt;
    private Instant disengagedAt;
    private String engagedBy;
    private String reason;

    public static KillSwitchState disengaged() {
        return KillSwitchState.builder().engaged(false).build();
    }

    public static KillSwitchState engaged(String engagedBy, String reason) {
        return KillSwitchState.builder()
                .engaged(true)
                .engagedAt(Instant.now())
                .engagedBy(engagedBy)
                .reason(reason)
                .build();
    }
}
