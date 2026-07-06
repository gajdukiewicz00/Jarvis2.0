package org.jarvis.swarm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed, immutable configuration for the agent swarm (prefix {@code swarm}).
 */
@ConfigurationProperties(prefix = "swarm")
public record SwarmProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Workspace workspace,
        @DefaultValue Queue queue,
        @DefaultValue Task task,
        @DefaultValue SwarmRun swarmRun,
        @DefaultValue Retention retention,
        @DefaultValue Process process) {

    public record Workspace(
            @DefaultValue("/tmp/jarvis-agents") String dir,
            // Git repository CODER/TESTER may branch a throwaway worktree from. Blank
            // (default) disables SandboxManager#createGitWorktree entirely.
            @DefaultValue("") String gitRepoDir) {}

    public record Queue(
            @DefaultValue("64") int capacity,
            @DefaultValue("3") int workerPoolSize) {}

    public record Task(
            @DefaultValue("120") int defaultTimeoutSeconds,
            @DefaultValue("1") int defaultMaxRetries) {}

    public record SwarmRun(
            @DefaultValue("60") int waitTimeoutSeconds,
            @DefaultValue("7") int maxRoles) {}

    /** Retention/TTL sweep of finished agent-task records (see AgentTaskRetentionSweeper). */
    public record Retention(
            @DefaultValue("true") boolean enabled,
            // Runs older than this (by createdAt) are eligible for deletion.
            @DefaultValue("30") int maxAgeDays,
            // Always kept per user regardless of age, so a quiet user never loses all history.
            @DefaultValue("50") int keepPerUser,
            @DefaultValue("3600000") long sweepIntervalMs) {}

    /** TESTER role external test-runner process limits. */
    public record Process(
            // Hard kill-on-overrun timeout for a single allowlisted test-runner invocation
            // (mvn/gradle/npm/pytest test); see ProcessRunner#run.
            @DefaultValue("30") int testCommandTimeoutSeconds) {}
}
