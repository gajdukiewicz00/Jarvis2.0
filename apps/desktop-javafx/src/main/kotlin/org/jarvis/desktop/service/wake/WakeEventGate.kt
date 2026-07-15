package org.jarvis.desktop.service.wake

/**
 * Lifecycle safety valve between a provider's wake callback and starting a voice
 * session. A wake event passes through [offer] first; only an accepted event
 * should start a command. This debounces the two ways always-listening leaks:
 *
 *  1. Duplicate / rapid-fire wake events — a second WAKE_DETECTED within
 *     [cooldownMs] of the last accepted one is ignored.
 *  2. A wake that arrives while a command is already in flight — [isBusy]
 *     (e.g. `VoiceSession.state != LISTENING_WAKE_WORD`) short-circuits it.
 *
 * After a command finishes, the caller calls [markCompleted] so a fresh wake is
 * also held off for [cooldownMs] past completion (avoids the TTS tail / echo
 * re-triggering immediately).
 *
 * Deterministic: the current time is passed into [offer]/[markCompleted]; no
 * wall clock is read inside, so tests are exact.
 */
class WakeEventGate(
    private val isBusy: () -> Boolean,
    cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    /** Cooldown clamped to the sane 1500–3000ms window. */
    private val cooldownMs: Long = cooldownMs.coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)

    // Cross-thread: offer() runs on the provider's detection thread, markCompleted() on the
    // FX thread. @Volatile guarantees each thread sees the other's latest write.
    @Volatile private var lastAcceptedMs: Long? = null
    @Volatile private var lastCompletedMs: Long? = null

    /**
     * @return true to ACCEPT the wake (caller starts a session), false to IGNORE.
     */
    fun offer(event: WakeEvent, nowMs: Long): Boolean {
        if (safeBusy()) return false
        if (withinCooldown(lastAcceptedMs, nowMs)) return false
        if (withinCooldown(lastCompletedMs, nowMs)) return false
        lastAcceptedMs = nowMs
        return true
    }

    /** Mark the just-finished command's completion time to arm the post-command cooldown. */
    fun markCompleted(nowMs: Long) {
        lastCompletedMs = nowMs
    }

    private fun withinCooldown(referenceMs: Long?, nowMs: Long): Boolean =
        referenceMs != null && nowMs - referenceMs < cooldownMs

    private fun safeBusy(): Boolean = try {
        isBusy()
    } catch (_: Exception) {
        // If we cannot tell, be conservative and treat as busy (do not start).
        true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 2000L
        const val MIN_COOLDOWN_MS = 1500L
        const val MAX_COOLDOWN_MS = 3000L
    }
}
