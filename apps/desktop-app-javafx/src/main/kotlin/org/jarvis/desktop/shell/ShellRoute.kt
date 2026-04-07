package org.jarvis.desktop.shell

enum class ShellRoute(
    val title: String,
    val navLabel: String
) {
    HOME("Home", "Home"),
    VISION_SECURITY("Vision Security", "Vision Security"),
    VOICE("Voice", "Voice"),
    DIAGNOSTICS("Diagnostics", "Diagnostics"),
    SETTINGS("Settings", "Settings")
}
