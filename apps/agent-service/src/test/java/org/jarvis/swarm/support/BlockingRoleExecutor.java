package org.jarvis.swarm.support;

import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Test executor that signals when it has started, then loops on {@code ctx.checkpoint()}
 * until released — letting tests deterministically pause/cancel a RUNNING task.
 */
public class BlockingRoleExecutor implements RoleExecutor {

    private final AgentRole role;
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile boolean release = false;

    public BlockingRoleExecutor(AgentRole role) {
        this.role = role;
    }

    public CountDownLatch started() {
        return started;
    }

    public void release() {
        release = true;
    }

    @Override
    public AgentRole role() {
        return role;
    }

    @Override
    public RoleResult execute(ExecutionContext ctx) {
        started.countDown();
        while (!release) {
            ctx.checkpoint(); // throws on cancel; blocks while paused
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return RoleResult.success("blocking executor completed", null, List.of(), List.of(), List.of(), List.of());
    }
}
