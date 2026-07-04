package org.jarvis.events;

/**
 * Phase 8 — narrow enumeration of auditable actions.
 *
 * <p>Every privileged action that SPEC-1 wants traced has a value here.
 * The projector indexes by this column so the desktop panel can filter
 * "show me every kill-switch toggle this week" or "every confirmation
 * the owner denied".</p>
 */
public enum AuditEventType {
    // Command pipeline (Phase 4)
    COMMAND_QUEUED,
    COMMAND_EXECUTED,
    COMMAND_FAILED,
    COMMAND_EXPIRED,

    // Confirmation pipeline (Phase 5)
    CONFIRMATION_REQUESTED,
    CONFIRMATION_APPROVED,
    CONFIRMATION_DENIED,
    CONFIRMATION_TIMEOUT,
    CONFIRMATION_BLOCKED_DEMO_MODE,
    CONFIRMATION_BLOCKED_NON_OWNER,

    // Native Desktop Agent (Phase 6)
    AGENT_REGISTERED,
    AGENT_HEARTBEAT_LOST,
    KILL_SWITCH_ENGAGED,
    KILL_SWITCH_DISENGAGED,

    // Voice loop (Phase 7)
    VOICE_SESSION_STARTED,
    VOICE_INTENT_CLASSIFIED,
    VOICE_FEEDBACK_SPOKEN,

    // Memory (Phase 9)
    MEMORY_WRITTEN,
    MEMORY_DELETED,

    // Computer Vision (Phase 10)
    CV_INCIDENT_RECORDED,
    VISION_FRAME_CAPTURED,
    VISION_FACE_ENROLLED,
    VISION_FACE_RECOGNIZED,
    VISION_OCR_PERFORMED,
    VISION_INCIDENT_DETECTED,
    VISION_FRAMES_PURGED,
    VISION_DEMO_MODE_BLOCK,

    // Mobile sync (Phase 12)
    SYNC_DEVICE_PAIRED,
    SYNC_DEVICE_PAIRING_REJECTED,
    SYNC_BLOB_RECEIVED,
    SYNC_BLOB_REPLAY_REJECTED,
    SYNC_BLOB_TAMPER_REJECTED,
    SYNC_DISPATCH_FAILED
}
