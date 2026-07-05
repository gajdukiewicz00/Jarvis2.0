package org.jarvis.android.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Retention policy applied by [BankDraftPurgeWorker]: bank-notification-derived
 * `FINANCE_ENTRY` drafts carry `"source": "BANK_NOTIFICATION"` in their payload JSON
 * (see `buildBankDraftPayload` in [BankNotificationListenerService]); manual finance
 * entries (from [org.jarvis.android.ui.finance.ManualFinanceScreen]) never match and are
 * never touched by this policy.
 */
object BankDraftRetention {

    private val json = Json { ignoreUnknownKeys = true }

    /** True when [payloadJson] is a bank-notification-sourced draft, false for anything else
     * (manual entries, malformed JSON, or a payload predating this field). */
    fun isBankNotificationDraft(payloadJson: String): Boolean = runCatching {
        json.parseToJsonElement(payloadJson).jsonObject["source"]?.jsonPrimitive?.content == "BANK_NOTIFICATION"
    }.getOrDefault(false)

    /**
     * @param retentionDays `0` means "do not retain raw text at all" — anything already
     * queued is immediately eligible for purge, regardless of age.
     */
    fun isExpired(createdAtEpochMs: Long, nowEpochMs: Long, retentionDays: Int): Boolean {
        if (retentionDays <= 0) return true
        val ageMs = nowEpochMs - createdAtEpochMs
        return ageMs >= TimeUnit.DAYS.toMillis(retentionDays.toLong())
    }
}
