package org.jarvis.desktop.shell

/**
 * Minimal lifecycle bridge for shell route content.
 *
 * The unified shell only needs route attach/detach hooks and a final shell
 * shutdown callback right now, so this stays intentionally small.
 */
interface ShellRouteContent {
    fun onRouteActivated() {}

    fun onRouteDeactivated() {}

    fun onShellShutdown() {}
}
