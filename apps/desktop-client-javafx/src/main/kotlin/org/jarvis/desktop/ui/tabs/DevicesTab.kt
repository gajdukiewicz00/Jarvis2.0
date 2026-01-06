package org.jarvis.desktop.ui.tabs

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient

class DevicesTab(private val apiClient: ApiClient) {
    val tab = Tab("Devices")
    private val statusLabel = Label("")

    init {
        val content = VBox(10.0)
        content.children.add(Label("Smart Home Devices"))
        
        // Status label for feedback
        statusLabel.style = "-fx-font-weight: bold;"
        content.children.add(statusLabel)

        val lightBtn = Button("Toggle Light")
        lightBtn.setOnAction {
            // Assuming device ID "kitchen_light" for demo
            try {
                apiClient.post("/smarthome/devices/kitchen_light/action", "{\"action\": \"toggle\", \"payload\": \"\"}")
                statusLabel.text = "✓ Light toggled successfully"
                statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
            } catch (e: Exception) {
                e.printStackTrace()
                statusLabel.text = "✗ Error: ${e.message ?: "Failed to toggle light"}"
                statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
            }
        }
        content.children.add(lightBtn)

        tab.content = content
        tab.isClosable = false
    }
}
