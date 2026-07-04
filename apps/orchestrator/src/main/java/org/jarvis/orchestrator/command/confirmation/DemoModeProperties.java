package org.jarvis.orchestrator.command.confirmation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 5 — demo-mode policy.
 *
 * <p>When {@code enabled=true} the {@link ConfirmationCoordinator}
 * short-circuits dangerous commands with
 * {@link org.jarvis.commands.ConfirmationDecision#BLOCKED_DEMO_MODE} before
 * the confirmation request is even published. Useful for showing Jarvis
 * to non-owners without risking real actions.</p>
 *
 * <p>Toggled by env var {@code JARVIS_DEMO_MODE_ENABLED}. Default false.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.demo-mode")
public class DemoModeProperties {
    private boolean enabled = false;
    private String reason = "demo mode active — privileged action blocked";
}
