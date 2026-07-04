package org.jarvis.commands;

/**
 * Phase 4 — RabbitMQ topology constants. Centralised here so every service
 * (orchestrator publisher, desktop-agent consumer, audit projector, etc.)
 * agrees on names without duplication.
 *
 * <p>Names follow SPEC-1 § "Messaging Split":</p>
 * <pre>
 *   jarvis.commands.agent.execute        -- orchestrator -> desktop-agent
 *   jarvis.commands.agent.result         -- desktop-agent -> orchestrator
 *   jarvis.commands.confirmation.request -- orchestrator -> user UI (Phase 5)
 *   jarvis.commands.confirmation.result  -- user UI      -> orchestrator (Phase 5)
 *   jarvis.tasks.background              -- async background tasks
 *   jarvis.tasks.llm                     -- LLM tasks queue
 *   jarvis.dlx.commands                  -- dead-letter for command queues
 *   jarvis.dlx.tasks                     -- dead-letter for task queues
 * </pre>
 */
public final class CommandTopology {

    private CommandTopology() {}

    // ----- Exchanges -----
    public static final String DLX_COMMANDS = "jarvis.dlx.commands";
    public static final String DLX_TASKS = "jarvis.dlx.tasks";

    // ----- Command queues -----
    public static final String QUEUE_AGENT_EXECUTE = "jarvis.commands.agent.execute";
    public static final String QUEUE_AGENT_RESULT = "jarvis.commands.agent.result";
    public static final String QUEUE_CONFIRMATION_REQUEST = "jarvis.commands.confirmation.request";
    public static final String QUEUE_CONFIRMATION_RESULT = "jarvis.commands.confirmation.result";

    // ----- Task queues -----
    public static final String QUEUE_TASKS_BACKGROUND = "jarvis.tasks.background";
    public static final String QUEUE_TASKS_LLM = "jarvis.tasks.llm";

    // ----- DLQ-side queues (consumers of the DLX exchanges) -----
    public static final String QUEUE_DLQ_COMMANDS = "jarvis.dlq.commands";
    public static final String QUEUE_DLQ_TASKS = "jarvis.dlq.tasks";

    // ----- RabbitMQ message headers -----
    public static final String HEADER_COMMAND_ID = "x-command-id";
    public static final String HEADER_CORRELATION_ID = "x-correlation-id";
    public static final String HEADER_USER_ID = "x-user-id";
    public static final String HEADER_RISK_LEVEL = "x-risk-level";
    public static final String HEADER_INTENT = "x-intent";
}
