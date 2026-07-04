package org.jarvis.common.safety;

/**
 * Granular capability a tool/intent may require before it is executed or
 * published (EPIC 3 — "Permission model for tools"). Layered on top of the
 * intent-level risk catalog: a tool can be SAFE risk yet still need an explicit
 * grant (e.g. finance access). Shared across services so the SAME policy gates
 * every entry point (gateway executor, orchestrator publishing, voice path).
 */
public enum ToolPermission {
    READ_FILES,
    WRITE_FILES,
    RUN_SHELL,
    NETWORK_ACCESS,
    CALENDAR_ACCESS,
    NOTIFICATION_ACCESS,
    FINANCE_ACCESS,
    MEDIA_ACCESS,
    SMART_HOME_ACCESS,
    PC_CONTROL,
    MEMORY_ACCESS,
    PLANNER_ACCESS
}
