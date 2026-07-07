package org.jarvis.desktop.features.status

/**
 * Single source of truth for status semantics across the whole desktop shell.
 *
 * Before this existed, every status-bearing screen invented its own strings and
 * tone classes: the top bar said "Backend healthy", Service Status said "UP",
 * AI Runtime said "Ready"/"Down"/"CPU only", and Diagnostics said "Reachable"/
 * "Auth required" — each with independently-decided colors. That is how the
 * canonical failure mode happens: the same underlying fact (e.g. a 401/403 from
 * a health endpoint) gets reported as "healthy" in one place and "needs
 * attention" in another, or the AI card claims "GPU not detected" while some
 * other card implies GPU presence.
 *
 * Every feature read-model/view maps its own domain-specific status type onto
 * one of these nine levels (via a small `toStatusLevel()` extension living next
 * to that domain type) instead of deciding its own label/color/severity. Two
 * screens describing the same fact through this enum cannot disagree on
 * whether it is healthy, and they render it with the same color.
 *
 * @property label Human-readable display name for the level.
 * @property colorToken The `-jarvis-*` JavaFX looked-up color (defined in
 *   `shell.css` on `.root`) that this level's tone is drawn from.
 * @property toneStyleClass The existing `shell-status-tone-*` style class that
 *   applies [colorToken] as `-fx-text-fill`. Views already have `applyTone(...)`
 *   helpers that add/remove these classes — pass this value straight through.
 */
enum class StatusLevel(
    val label: String,
    val colorToken: String,
    val toneStyleClass: String
) {
    /** Fully operational. */
    UP("Up", "-jarvis-success", "shell-status-tone-success"),

    /**
     * Reachable and alive — the process answered — but the endpoint is gated
     * behind authentication (HTTP 401/403). This is NOT a failure: it proves
     * the service is up. Every probe in this codebase must classify 401/403
     * this way, never as [DEGRADED] or [DOWN].
     */
    PROTECTED("Protected", "-jarvis-primary", "shell-status-tone-info"),

    /** Reachable but not fully healthy — some capability is impaired. */
    DEGRADED("Degraded", "-jarvis-warning", "shell-status-tone-warning"),

    /** Not reachable / not responding. */
    DOWN("Down", "-jarvis-error", "shell-status-tone-error"),

    /** Some, but not all, of a multi-part capability is working. */
    PARTIAL("Partial", "-jarvis-warning", "shell-status-tone-warning"),

    /** Intentionally simulated rather than backed by a real dependency. */
    MOCK("Mock", "-jarvis-primary", "shell-status-tone-info"),

    /** Intentionally turned off by configuration — not an error. */
    DISABLED("Disabled", "-jarvis-text-secondary", "shell-status-tone-muted"),

    /**
     * The capability is known, definitively, not to be present or working
     * (e.g. GPU acceleration was expected but the hardware/driver path came
     * back negative). Distinct from [UNKNOWN]: this is a confirmed negative
     * answer, not a missing answer.
     */
    UNAVAILABLE("Unavailable", "-jarvis-warning", "shell-status-tone-warning"),

    /**
     * No confirmed answer yet — a check is still in flight, transitioning, or
     * the authoritative source could not be reached. Screens must say
     * [UNKNOWN] rather than guessing "up" or "down"; a wrong guess is exactly
     * the kind of cross-screen contradiction this enum exists to prevent.
     */
    UNKNOWN("Unknown", "-jarvis-text-secondary", "shell-status-tone-muted");

    /**
     * True for statuses that are NOT a failure an operator needs to act on.
     * [UP], [PROTECTED], [MOCK], and [DISABLED] are healthy; everything else
     * ([DEGRADED], [DOWN], [PARTIAL], [UNAVAILABLE], [UNKNOWN]) is not.
     */
    val isHealthy: Boolean
        get() = this == UP || this == PROTECTED || this == MOCK || this == DISABLED
}
