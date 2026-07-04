package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MEDIA: prepares a media-service job request from the goal. It always requires
 * MEDIA_ACCESS (role ∩ user) — a task without that grant is rejected. When granted it
 * builds a real, valid media job spec; it does NOT dispatch to media-service because the
 * cross-service call would need a NetworkPolicy allowlist (out of scope for this MVP),
 * so the prepared spec is returned for review/forwarding.
 */
@Component
public class MediaAgentExecutor implements RoleExecutor {

    @Override
    public AgentRole role() {
        return AgentRole.MEDIA;
    }

    @Override
    public RoleResult execute(ExecutionContext ctx) {
        ctx.checkpoint();
        // Media work is gated: no MEDIA_ACCESS → rejected.
        ctx.guard().ensurePermission(ctx.task(), ToolPermission.MEDIA_ACCESS);

        String goal = ctx.task().goal();
        String jobType = goal.toLowerCase().contains("subtitle") || goal.toLowerCase().contains("субтитр")
                ? "russian-subtitles"
                : goal.toLowerCase().contains("dub") || goal.toLowerCase().contains("озвуч")
                ? "russian-dub-audio" : "probe";
        String spec = "{\"endpoint\":\"/api/v1/media/" + (jobType.equals("probe") ? "probe" : "jobs/" + jobType)
                + "\",\"derivedFrom\":\"" + goal.replace("\"", "'") + "\"}";

        List<String> proposed = List.of("prepare media job: " + jobType, "POST " + spec);
        List<String> next = List.of("Forward to media-service (requires gateway route / NetworkPolicy)");

        return RoleResult.success("MEDIA prepared a " + jobType + " job spec", spec,
                List.of(), proposed, List.of(), next);
    }
}
