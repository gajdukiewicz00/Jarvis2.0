package org.jarvis.desktop.view

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.jarvis.desktop.i18n.I18n
import java.util.*

/**
 * Settings dialog with language selector
 */
class SettingsDialog : Dialog<ButtonType>() {
    
    private val languageComboBox = ComboBox<LanguageOption>()
    private val serverUrlField = TextField()
    
    init {
        title = I18n.getString("settings.title")
        headerText = I18n.getString("settings.title")
        
        // Language options
        languageComboBox.items.addAll(
            LanguageOption("English", Locale.ENGLISH),
            LanguageOption("Polski", Locale("pl", "PL")),
            LanguageOption("Русский", Locale("ru", "RU"))
        )
        
        // Select current language
        val currentLocale = I18n.getCurrentLocale()
        languageComboBox.selectionModel.select(
            languageComboBox.items.find { it.locale.language == currentLocale.language }
        )
        
        // Server URL
        serverUrlField.text = "http://localhost:8080" // TODO: Load from config
        serverUrlField.prefWidth = 300.0
        
        // Layout
        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            padding = Insets(20.0, 150.0, 10.0, 10.0)
            
            add(Label(I18n.getString("settings.language")), 0, 0)
            add(languageComboBox, 1, 0)
            
            add(Label(I18n.getString("settings.server.url")), 0, 1)
            add(serverUrlField, 1, 1)
        }
        
        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        
        // Apply language change
        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                val selectedLanguage = languageComboBox.selectionModel.selectedItem
                if (selectedLanguage != null) {
                    I18n.setLocale(selectedLanguage.locale)
                    // TODO: Save to preferences
                    // TODO: Refresh UI
                }
            }
            buttonType
        }
    }
    
    data class LanguageOption(val displayName: String, val locale: Locale) {
        override fun toString() = displayName
    }
}
