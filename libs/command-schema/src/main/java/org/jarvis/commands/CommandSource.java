package org.jarvis.commands;

/**
 * Phase 4 — origin of a command. Used for audit, throttling, and demo-mode
 * gating (Phase 5).
 */
public enum CommandSource {
    /** Voice loop: wake-word -> STT -> intent -> orchestrator. */
    VOICE,
    /** Desktop UI tab or live feed action. */
    DESKTOP_UI,
    /** Mobile app via cloud relay (Phase 12). */
    MOBILE,
    /** Scheduler / planner-service automation. */
    SCHEDULER,
    /** Internal system action (heartbeat, watchdog, cleanup). */
    SYSTEM,
    /** Manual API call (curl / dev), recorded for audit. */
    API
}
