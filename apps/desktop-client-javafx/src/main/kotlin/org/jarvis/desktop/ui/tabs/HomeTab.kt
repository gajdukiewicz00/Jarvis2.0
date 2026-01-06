package org.jarvis.desktop.ui.tabs

import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient

class HomeTab(private val apiClient: ApiClient) {
    val tab = Tab("Home")

    init {
        val content = VBox(10.0)
        content.children.add(Label("Welcome to Jarvis 2.0"))
        
        // Add analytics overview here
        // For now, just a placeholder or fetch simple data
        // val analytics = apiClient.get("/analytics/overview")
        // content.children.add(Label(analytics))

        tab.content = content
        tab.isClosable = false
    }
}
