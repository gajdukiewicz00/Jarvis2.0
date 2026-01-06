package org.jarvis.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class SettingsActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var languageSelector: MaterialAutoCompleteTextView
    private lateinit var saveButton: MaterialButton
    private lateinit var cancelButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupViews()
        loadSettings()
        setupListeners()
    }

    private fun setupViews() {
        serverUrlInput = findViewById(R.id.serverUrlInput)
        languageSelector = findViewById(R.id.languageSelector)
        saveButton = findViewById(R.id.saveButton)
        cancelButton = findViewById(R.id.cancelButton)

        // Language options
        val languages = listOf(
            getString(R.string.language_english),
            getString(R.string.language_polish),
            getString(R.string.language_russian)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languages)
        languageSelector.setAdapter(adapter)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        
        // Load server URL
        serverUrlInput.setText(prefs.getString("server_url", "http://192.168.1.100:8080"))
        
        // Load language
        val currentLocale = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "en"
        when (currentLocale) {
            "en" -> languageSelector.setText(getString(R.string.language_english), false)
            "pl" -> languageSelector.setText(getString(R.string.language_polish), false)
            "ru" -> languageSelector.setText(getString(R.string.language_russian), false)
        }
    }

    private fun setupListeners() {
        saveButton.setOnClickListener {
            saveSettings()
        }

        cancelButton.setOnClickListener {
            finish()
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        
        // Save server URL
        val serverUrl = serverUrlInput.text.toString()
        prefs.edit().putString("server_url", serverUrl).apply()

        // Save and apply language
        val selectedLanguage = when (languageSelector.text.toString()) {
            getString(R.string.language_polish) -> "pl"
            getString(R.string.language_russian) -> "ru"
            else -> "en"
        }
        
        val localeList = LocaleListCompat.forLanguageTags(selectedLanguage)
        AppCompatDelegate.setApplicationLocales(localeList)

        // Return to main activity
        finish()
    }
}
