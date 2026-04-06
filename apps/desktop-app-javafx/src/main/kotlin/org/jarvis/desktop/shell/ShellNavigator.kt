package org.jarvis.desktop.shell

import java.util.concurrent.CopyOnWriteArrayList

class ShellNavigator(
    initialRoute: ShellRoute = ShellRoute.HOME
) {
    private val listeners = CopyOnWriteArrayList<(ShellRoute) -> Unit>()

    @Volatile
    private var currentRoute = initialRoute

    fun currentRoute(): ShellRoute = currentRoute

    fun navigateTo(route: ShellRoute) {
        if (currentRoute == route) {
            return
        }

        currentRoute = route
        listeners.forEach { it(route) }
    }

    fun addListener(listener: (ShellRoute) -> Unit) {
        listeners += listener
        listener(currentRoute)
    }

    fun removeListener(listener: (ShellRoute) -> Unit) {
        listeners -= listener
    }
}
