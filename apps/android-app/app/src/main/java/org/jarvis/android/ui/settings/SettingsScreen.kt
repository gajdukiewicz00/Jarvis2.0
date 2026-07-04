package org.jarvis.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarvis.android.sync.Pairing
import org.jarvis.android.sync.PairingState

/** Server pairing screen — enter the on-prem sync-service URL and pair the device. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { PairingState(context) }

    var baseUrl by remember { mutableStateOf(state.baseUrl() ?: "http://10.113.0.176:30095") }
    var label by remember { mutableStateOf("Phone") }
    var status by remember {
        mutableStateOf(
            if (state.routingId() != null) "Спарено ✓ (device=${state.senderDeviceId()})" else "Не спарено"
        )
    }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Подключение к серверу", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Адрес сервера (sync-service)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Имя устройства") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                status = "Пейринг…"
                scope.launch {
                    status = try {
                        val resp = withContext(Dispatchers.IO) { Pairing.pair(context, baseUrl, label) }
                        "Спарено ✓ device=${resp.senderDeviceId}"
                    } catch (e: Exception) {
                        pairErrorMessage(e)
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "..." else "Спарить") }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                state.forget()
                status = "Сброшено"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сбросить пейринг") }

        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text(
            "После пейринга сон/шаги (Health Connect) и финансы уходят на сервер автоматически каждые ~15 минут.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Maps a pairing failure to a clear, actionable message instead of a raw stack trace.
 * After the Bouncy Castle crypto fix, "Ed25519 KeyPairGenerator not available" no longer occurs.
 */
private fun pairErrorMessage(e: Throwable): String {
    val msg = e.message ?: e.javaClass.simpleName
    return when {
        e is java.net.UnknownHostException || e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException || e is java.io.IOException ->
            "Сервер недоступен. Телефон должен быть в той же сети; проверьте адрес (например http://10.113.0.176:30095)."
        msg.contains("identity_signature_invalid", true) || msg.contains("signature", true) ->
            "Подпись отклонена сервером. Переустановите APK и спарьте заново."
        msg.contains("nonce", true) ->
            "Сессия пейринга истекла. Нажмите «Спарить» ещё раз."
        msg.contains("401") ->
            "Сервер отклонил запрос (401). На ПК выполните: bash scripts/fix-sync-auth.sh, затем повторите."
        msg.contains("Ed25519", true) || msg.contains("X25519", true) || msg.contains("KeyPairGenerator", true) ->
            "Ошибка криптографии на устройстве: $msg. Обновите APK (исправлено через Bouncy Castle)."
        else -> "Ошибка пейринга: $msg"
    }
}
