package org.jarvis.desktop.ui.tabs

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.model.SmartHomeActionCommand
import org.jarvis.desktop.model.SmartHomeDeviceDto
import org.jarvis.desktop.service.SmartHomeStateFormatter
import org.slf4j.LoggerFactory

class DevicesTab(private val apiClient: ApiClient) {
    private val logger = LoggerFactory.getLogger(DevicesTab::class.java)
    val tab = Tab("Devices")
    private val statusLabel = Label("")
    private val objectMapper = jacksonObjectMapper()
    private val deviceContainer = VBox(12.0)

    init {
        val content = VBox(12.0).apply {
            padding = Insets(16.0)
        }
        content.children.add(Label("Smart Home Devices").apply {
            style = "-fx-font-size: 18px; -fx-font-weight: bold;"
        })
        
        // Status label for feedback
        statusLabel.style = "-fx-font-weight: bold;"
        content.children.add(statusLabel)

        val refreshButton = Button("Refresh devices")
        refreshButton.setOnAction { loadDevices() }
        content.children.add(refreshButton)
        content.children.add(deviceContainer)

        tab.content = ScrollPane(content).apply {
            isFitToWidth = true
        }
        tab.isClosable = false

        loadDevices()
    }

    private fun loadDevices() {
        statusLabel.text = "Loading smart-home devices..."
        statusLabel.style = "-fx-text-fill: #1565c0; -fx-font-weight: bold;"

        Thread {
            try {
                val devices: List<SmartHomeDeviceDto> = objectMapper.readValue(apiClient.get("/smarthome/devices"))
                Platform.runLater {
                    renderDevices(devices)
                    statusLabel.text = "✓ Loaded ${devices.size} device(s)"
                    statusLabel.style = "-fx-text-fill: #2e7d32; -fx-font-weight: bold;"
                }
            } catch (e: Exception) {
                logger.error("Failed to load smart-home devices: {}", e.message, e)
                Platform.runLater {
                    deviceContainer.children.setAll(Label("Unable to load devices."))
                    statusLabel.text = "✗ ${e.message ?: "Failed to load devices"}"
                    statusLabel.style = "-fx-text-fill: #c62828; -fx-font-weight: bold;"
                }
            }
        }.start()
    }

    private fun renderDevices(devices: List<SmartHomeDeviceDto>) {
        deviceContainer.children.clear()
        if (devices.isEmpty()) {
            deviceContainer.children.add(Label("No smart-home devices registered yet."))
            return
        }

        devices.forEach { device ->
            val card = VBox(6.0).apply {
                style = "-fx-background-color: #f6f8fb; -fx-background-radius: 8; -fx-padding: 12;"
            }
            card.children.add(Label("${device.displayName} • ${device.room}").apply {
                style = "-fx-font-size: 14px; -fx-font-weight: bold;"
            })
            card.children.add(Label("State: ${SmartHomeStateFormatter.summarize(device)}"))
            card.children.add(Label("Provider: ${device.provider.ifBlank { "unknown" }}"))
            card.children.add(actionButtons(device))
            deviceContainer.children.add(card)
        }
    }

    private fun actionButtons(device: SmartHomeDeviceDto): FlowPane {
        val row = FlowPane(8.0, 8.0)
        val actions = device.supportedActions.toSet()

        fun addButton(label: String, action: String, payload: String? = null) {
            if (actions.contains(action)) {
                row.children.add(Button(label).apply {
                    setOnAction { executeAction(device, SmartHomeActionCommand(action, payload)) }
                })
            }
        }

        addButton("Toggle", "TOGGLE")
        addButton("On", "TURN_ON")
        addButton("Off", "TURN_OFF")
        addButton("Dim", "DIM")
        addButton("Brighten", "BRIGHTEN")
        addButton("Warm", "SET_COLOR", "warm_white")
        addButton("Cool", "SET_COLOR", "cool_white")
        addButton("20°C", "SET_TEMPERATURE", "20")
        addButton("22°C", "SET_TEMPERATURE", "22")
        addButton("24°C", "SET_TEMPERATURE", "24")
        addButton("Lock", "LOCK")
        addButton("Unlock", "UNLOCK")

        return row
    }

    private fun executeAction(device: SmartHomeDeviceDto, command: SmartHomeActionCommand) {
        statusLabel.text = "Sending ${command.action} to ${device.displayName}..."
        statusLabel.style = "-fx-text-fill: #1565c0; -fx-font-weight: bold;"

        Thread {
            try {
                apiClient.post(
                    "/smarthome/devices/${device.id}/action",
                    objectMapper.writeValueAsString(command)
                )
                Platform.runLater {
                    statusLabel.text = "✓ ${device.displayName}: ${command.action}"
                    statusLabel.style = "-fx-text-fill: #2e7d32; -fx-font-weight: bold;"
                    loadDevices()
                }
            } catch (e: Exception) {
                logger.error("Failed to execute smart-home action {} for {}: {}", command.action, device.id, e.message, e)
                Platform.runLater {
                    statusLabel.text = "✗ ${device.displayName}: ${e.message ?: "Action failed"}"
                    statusLabel.style = "-fx-text-fill: #c62828; -fx-font-weight: bold;"
                }
            }
        }.start()
    }
}
