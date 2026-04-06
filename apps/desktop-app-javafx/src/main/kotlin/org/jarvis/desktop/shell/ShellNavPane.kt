package org.jarvis.desktop.shell

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import java.util.EnumMap

class ShellNavPane(
    private val navigator: ShellNavigator
) : VBox(10.0) {
    private val routeGroup = ToggleGroup()
    private val navButtons = EnumMap<ShellRoute, ToggleButton>(ShellRoute::class.java)
    private val routeListener: (ShellRoute) -> Unit = { route ->
        navButtons[route]?.isSelected = true
    }

    init {
        styleClass += "shell-nav"
        padding = Insets(20.0, 14.0, 20.0, 14.0)
        prefWidth = 220.0
        minWidth = 220.0

        ShellRoute.entries.forEach { route ->
            val button = ToggleButton(route.navLabel).apply {
                toggleGroup = routeGroup
                maxWidth = Double.MAX_VALUE
                alignment = Pos.CENTER_LEFT
                styleClass += "shell-nav-button"
                setOnAction { navigator.navigateTo(route) }
            }
            navButtons[route] = button
            children += button
        }

        val spacer = Region()
        VBox.setVgrow(spacer, Priority.ALWAYS)
        children += spacer
        children += Label("Unified shell MVP").apply {
            styleClass += "shell-nav-footer"
            isWrapText = true
        }

        navigator.addListener(routeListener)
    }

    fun dispose() {
        navigator.removeListener(routeListener)
    }
}
