package org.jarvis.commands.agent;

/**
 * Phase 6 — coarse health state of a Native Desktop Agent process.
 *
 * <ul>
 *   <li>{@code BOOTING}     — process started, identity loaded, not yet registered.</li>
 *   <li>{@code READY}       — registered, all listeners subscribed, last heartbeat OK.</li>
 *   <li>{@code DEGRADED}    — some capability missing or backend partially unreachable.</li>
 *   <li>{@code KILL_SWITCH} — operator engaged the kill switch; mic/cam/automation paused.</li>
 *   <li>{@code OFFLINE}     — backend last-seen exceeded threshold (set by the registry).</li>
 * </ul>
 */
public enum AgentStatus {
    BOOTING,
    READY,
    DEGRADED,
    KILL_SWITCH,
    OFFLINE
}
