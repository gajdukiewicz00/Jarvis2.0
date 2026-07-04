package org.jarvis.commands.voice;

/**
 * Phase 7 — lifecycle of a voice session.
 *
 * <pre>
 *  STARTED -> LISTENING -> TRANSCRIBED -> CLASSIFIED -> DISPATCHED
 *                                                     -> AWAITING_CONFIRM
 *                                                     -> COMPLETED
 *                                                     -> FAILED
 *                                                     -> EXPIRED
 *                                                     -> ENDED
 * </pre>
 */
public enum VoiceSessionStatus {
    STARTED,
    LISTENING,
    TRANSCRIBED,
    CLASSIFIED,
    DISPATCHED,
    AWAITING_CONFIRM,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
    ENDED
}
