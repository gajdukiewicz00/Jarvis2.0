package org.jarvis.desktop.shell

enum class ShellRoute(
    val title: String,
    val navLabel: String
) {
    HOME("Home", "Home"),
    VOICE("Voice", "Voice"),
    DIAGNOSTICS("Diagnostics", "Diagnostics"),
    SETTINGS("Settings", "Settings")
}
