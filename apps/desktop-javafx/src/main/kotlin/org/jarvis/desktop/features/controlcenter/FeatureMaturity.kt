package org.jarvis.desktop.features.controlcenter

import org.jarvis.desktop.shell.ShellRoute

/**
 * Honest maturity classification for a feature tile in the Control Center.
 *
 * Problem this solves: a real, end-to-end-verified capability (e.g. Memory
 * recall) and a skeleton/scaffolded one (e.g. the Media Jobs pipeline) render
 * as identical feature cards today, so an operator (or a demo audience) has
 * no way to tell which "Open" buttons lead to something real. [label] and
 * [color] are rendered as a small badge on every feature tile so the two
 * never look the same.
 */
enum class FeatureMaturity(val label: String, val color: String) {
    /** Works end-to-end against real services; safe to demo unattended. */
    READY("READY", "#36d399"),

    /** Functions today but is still rough, partially wired, or lightly tested. */
    BETA("BETA", "#5aa9ff"),

    /** UI and flow exist, but the data or execution behind it is synthetic/stubbed. */
    MOCK("MOCK DATA", "#f4bf4f"),

    /** Present for exploration only — behavior may change or regress without notice. */
    EXPERIMENTAL("EXPERIMENTAL", "#b48cff"),

    /** Requires a prerequisite the current runtime doesn't have (e.g. a paired phone). */
    UNAVAILABLE("UNAVAILABLE", "#5f7691")
}

/**
 * Single source of truth mapping every navigable [ShellRoute] to its honest
 * [FeatureMaturity]. Keeping this as one flat table (rather than scattering
 * judgment calls across view code) makes it easy to audit and to update the
 * moment a feature graduates from BETA to READY or regresses.
 *
 * Routes are grouped by rationale below; see individual comments for the
 * reasoning behind less obvious calls. Unlisted routes default to
 * [FeatureMaturity.BETA] — the safe middle ground that neither overclaims
 * ([FeatureMaturity.READY]) nor underclaims ([FeatureMaturity.UNAVAILABLE]) for
 * a feature nobody has explicitly classified yet.
 */
object FeatureMaturityRegistry {

    private val maturityByRoute: Map<ShellRoute, FeatureMaturity> = mapOf(
        // Verified end-to-end against real backends.
        ShellRoute.BRAIN to FeatureMaturity.READY,
        ShellRoute.MEMORY to FeatureMaturity.READY,
        ShellRoute.FINANCE to FeatureMaturity.READY,
        ShellRoute.PLANNER to FeatureMaturity.READY,
        ShellRoute.ANALYTICS to FeatureMaturity.READY,
        ShellRoute.PC_CONTROL to FeatureMaturity.READY,
        ShellRoute.VOICE_HELP to FeatureMaturity.READY,
        ShellRoute.DIAGNOSTICS to FeatureMaturity.READY,
        ShellRoute.AI to FeatureMaturity.READY,
        ShellRoute.SERVICE_STATUS to FeatureMaturity.READY,
        ShellRoute.SETTINGS to FeatureMaturity.READY,

        // Functional but still rough / partially wired / lightly proven.
        ShellRoute.VOICE to FeatureMaturity.BETA,
        ShellRoute.LIFE to FeatureMaturity.BETA,
        ShellRoute.INSIGHTS to FeatureMaturity.BETA,
        ShellRoute.SECURITY to FeatureMaturity.BETA,
        ShellRoute.SECURITY_SESSIONS to FeatureMaturity.BETA,
        ShellRoute.FINANCE_REVIEW to FeatureMaturity.BETA,
        ShellRoute.AGENT_SWARM to FeatureMaturity.BETA,
        ShellRoute.SMART_HOME to FeatureMaturity.BETA,
        ShellRoute.VISION_SECURITY to FeatureMaturity.BETA,

        // UI/flow present, but backed by synthetic/stubbed data or execution.
        ShellRoute.MEDIA_JOBS to FeatureMaturity.MOCK,

        // Observes + reasons but the full proactive speech loop is unproven
        // end-to-end (needs speakers); treat as exploratory, not production.
        ShellRoute.PROACTIVE to FeatureMaturity.EXPERIMENTAL,

        // Requires a paired Android phone the current runtime does not have.
        ShellRoute.SYNC to FeatureMaturity.UNAVAILABLE
    )

    /** Returns the honest maturity for [route], defaulting to [FeatureMaturity.BETA] if unclassified. */
    fun of(route: ShellRoute): FeatureMaturity = maturityByRoute[route] ?: FeatureMaturity.BETA
}
