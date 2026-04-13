package org.jarvis.desktop.shell

enum class ShellRoute(
    val title: String,
    val navLabel: String
) {
    HOME("Home", "Home"),
    PLANNER("Planner", "Planner"),
    LIFE("Life", "Life"),
    ANALYTICS("Analytics", "Analytics"),
    PC_CONTROL("PC Control", "PC Control"),
    SMART_HOME("Smart Home", "Smart Home"),
    VISION_SECURITY("Vision Security / CV", "Vision Security / CV"),
    VOICE("Voice", "Voice"),
    DIAGNOSTICS("Diagnostics", "Diagnostics"),
    SETTINGS("Settings", "Settings"),
    AI("AI Runtime", "AI");

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
