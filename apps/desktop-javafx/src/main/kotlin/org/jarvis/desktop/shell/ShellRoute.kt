package org.jarvis.desktop.shell

enum class ShellRoute(
    val title: String,
    val navLabel: String
) {
    CONTROL_CENTER("Control Center", "Control Center"),
    HOME("Home", "Home"),
    BRAIN("Brain / AI Chat", "Brain / Chat"),
    VOICE_HELP("Voice — Ты можешь сказать", "Voice Commands"),
    VOICE("Voice", "Voice Control"),
    MEMORY("Memory", "Memory"),
    FINANCE("Finance", "Finance"),
    PLANNER("Planner", "Planner"),
    LIFE("Life", "Life"),
    ANALYTICS("Analytics", "Analytics"),
    INSIGHTS("Analytics Insights", "Insights"),
    SMART_HOME("Smart Home", "Smart Home"),
    PC_CONTROL("PC Control", "PC Control"),
    VISION_SECURITY("Vision Security / CV", "Vision Security / CV"),
    PROACTIVE("Proactive", "Proactive"),
    SECURITY("Security / Privacy", "Security / Privacy"),
    SECURITY_SESSIONS("Security Sessions & Audit", "Sessions & Audit"),
    AGENT_SWARM("Agent Swarm", "Agent Swarm"),
    MEDIA_JOBS("Media Jobs", "Media Jobs"),
    FINANCE_REVIEW("Finance Review Inbox", "Finance Review"),
    SYNC("Sync / Pairing", "Sync / Pairing"),
    DIAGNOSTICS("Diagnostics", "Diagnostics"),
    AI("AI Runtime", "AI"),
    SETTINGS("Settings", "Settings");

    companion object {
        /**
         * Returns only routes whose prerequisites are met in the current runtime.
         *
         * AI requires LLM or memory to be enabled in launcher settings.
         * Other major product tabs stay visible even when their backing service
         * may be unavailable, so the screen itself can surface the real error
         * or empty state instead of disappearing from navigation.
         */
        @Suppress("UNUSED_PARAMETER")
        fun visibleRoutes(
            runtimeMode: String,
            llmEnabled: Boolean,
            memoryEnabled: Boolean
        ): List<ShellRoute> {
            return entries.filter { route ->
                when (route) {
                    AI -> llmEnabled || memoryEnabled
                    else -> true
                }
            }
        }
    }
}
