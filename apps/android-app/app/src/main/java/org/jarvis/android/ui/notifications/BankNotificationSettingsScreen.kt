package org.jarvis.android.ui.notifications

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import org.jarvis.android.notifications.BankAppRegistry
import org.jarvis.android.notifications.BankAppSettings
import org.jarvis.android.notifications.clampRetentionDays

/**
 * Increment E (bank push -> finance draft) — onboarding + settings for the bank
 * notification listener.
 *
 * <p>Explains why "Notification access" is needed, links out to the one system screen
 * that can grant it (Android has no runtime-permission dialog for this), and lets the
 * user pick which bank apps are trusted sources plus how long (if at all) sanitized
 * notification text may sit on the device before auto-delete.</p>
 */
@Composable
fun BankNotificationSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { BankAppSettings(context) }

    var captureEnabled by remember { mutableStateOf(settings.isCaptureEnabled()) }
    var enabledPackages by remember { mutableStateOf(settings.enabledPackages()) }
    var retentionText by remember { mutableStateOf(settings.retentionDays().toString()) }
    var feedback by remember { mutableStateOf("") }

    fun listenerAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    var accessGranted by remember { mutableStateOf(listenerAccessGranted()) }

    Column(
        modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bank notifications -> finance drafts", style = MaterialTheme.typography.titleLarge)
        Text(
            "Jarvis can read notifications from your bank apps to draft finance entries " +
                "automatically. It never reads notifications from any other app, it blocks " +
                "OTP/2FA/authorization-code notifications outright (they are never sent " +
                "anywhere), and it masks card/account numbers before anything leaves the " +
                "device. Every draft still needs your confirmation before it counts as a " +
                "real expense.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            if (accessGranted) "Notification access: granted ✓" else "Notification access: not granted",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open notification access settings") }
        OutlinedButton(
            onClick = { accessGranted = listenerAccessGranted() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Refresh status") }

        Text("—", style = MaterialTheme.typography.bodySmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = captureEnabled, onCheckedChange = {
                captureEnabled = it
                settings.setCaptureEnabled(it)
            })
            Text("Capture bank notifications", modifier = Modifier.padding(start = 8.dp))
        }

        Text("Trusted bank apps:", style = MaterialTheme.typography.titleSmall)
        BankAppRegistry.KNOWN_BANK_APPS.forEach { bank ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = bank.packageName in enabledPackages,
                    onCheckedChange = { checked ->
                        val updated = if (checked) enabledPackages + bank.packageName
                                      else enabledPackages - bank.packageName
                        enabledPackages = updated
                        settings.setEnabledPackages(updated)
                    }
                )
                Text(bank.displayName, modifier = Modifier.padding(start = 4.dp))
            }
        }

        Text("—", style = MaterialTheme.typography.bodySmall)

        Text(
            "Raw-text retention (days, 0 = never store raw text):",
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedTextField(
            value = retentionText,
            onValueChange = { retentionText = it },
            label = { Text("Days") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val parsed = retentionText.toIntOrNull()
                if (parsed == null) {
                    feedback = "enter a whole number of days"
                } else {
                    val clamped = clampRetentionDays(parsed)
                    settings.setRetentionDays(clamped)
                    retentionText = clamped.toString()
                    feedback = "saved"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save retention setting") }
        if (feedback.isNotBlank()) Text(feedback)
    }
}
