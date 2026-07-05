package org.jarvis.android.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jarvis.android.data.local.JarvisDatabase
import org.jarvis.android.data.local.PendingItem
import java.time.Instant
import java.util.UUID

/**
 * Increment E (bank push -> finance draft).
 *
 * <p>Pipeline for every posted notification:</p>
 * <ol>
 *   <li>[BankNotificationFilter] — ignore anything not from a whitelisted bank package</li>
 *   <li>[OtpGuard] — drop OTP/2FA/authorization-code notifications outright, unsanitized
 *       and unsent</li>
 *   <li>[NotificationSanitizer] — mask card/account-number-like digit runs in what's left</li>
 *   <li>queue a `FINANCE_ENTRY` draft ([buildBankDraftPayload]) into the same offline Room
 *       queue [org.jarvis.android.sync.SyncWorker] already drains over the existing paired
 *       sync channel — no new network path, no new crypto</li>
 * </ol>
 *
 * <p>Registered in the manifest with
 * {@code android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"} so
 * only the system can bind it. The user must separately grant "Notification access" in
 * system settings — Android has no runtime-permission dialog for this, it's an explicit
 * Settings toggle. See
 * [org.jarvis.android.ui.notifications.BankNotificationSettingsScreen] for the in-app
 * explanation and deep link into that settings screen.</p>
 */
class BankNotificationListenerService : NotificationListenerService() {

    private val tag = "JarvisBankListener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val settings = BankAppSettings(applicationContext)
        if (!settings.isCaptureEnabled()) return
        handlePosted(sbn, settings.enabledPackages(), settings.retentionDays())
    }

    /**
     * Filter -> guard -> sanitize -> queue, extracted from [onNotificationPosted] so the
     * decision logic is exercised on a real [StatusBarNotification] fixture in
     * instrumented/device testing; the pure helpers it calls ([BankNotificationFilter],
     * [OtpGuard], [NotificationSanitizer], [buildBankDraftPayload]) are unit-testable
     * directly without one.
     */
    internal fun handlePosted(sbn: StatusBarNotification, enabledPackages: Set<String>, retentionDays: Int) {
        val packageName = sbn.packageName
        if (!BankNotificationFilter.isWhitelisted(packageName, enabledPackages)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (OtpGuard.isOtpOrAuthCode(title, text)) {
            Log.i(tag, "blocked OTP/authorization-code notification from $packageName")
            return
        }
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val storeRawText = retentionDays > 0
        val payloadJson = buildBankDraftPayload(
            packageName = packageName,
            bankName = BankAppRegistry.displayNameFor(packageName),
            sanitizedTitle = title?.let(NotificationSanitizer::sanitize),
            sanitizedText = text?.let(NotificationSanitizer::sanitize),
            postedAtEpochMs = sbn.postTime,
            storeRawText = storeRawText
        )

        scope.launch {
            runCatching {
                JarvisDatabase.get(applicationContext).pendingItems().upsert(
                    PendingItem(
                        id = UUID.randomUUID().toString(),
                        kind = "FINANCE_ENTRY",
                        payloadJson = payloadJson,
                        createdAtEpochMs = System.currentTimeMillis()
                    )
                )
            }.onFailure { e -> Log.w(tag, "failed to queue bank notification draft: ${e.message}") }
        }
    }
}

/**
 * Builds the `FINANCE_ENTRY` draft payload queued from a sanitized bank push notification.
 * Pure function — extracted so the JSON shape is unit-testable without a live
 * [StatusBarNotification]. `needsReview = true` always: amount/category are not reliably
 * parseable from free-form notification text across banks, so drafts land as
 * to-be-confirmed entries rather than posted expenses (unlike
 * [org.jarvis.android.ui.finance.buildFinanceEntryPayload], which builds a
 * user-confirmed entry directly).
 */
fun buildBankDraftPayload(
    packageName: String,
    bankName: String,
    sanitizedTitle: String?,
    sanitizedText: String?,
    postedAtEpochMs: Long,
    storeRawText: Boolean
): String = buildJsonObject {
    put("type", JsonPrimitive("DRAFT"))
    put("source", JsonPrimitive("BANK_NOTIFICATION"))
    put("bankPackage", JsonPrimitive(packageName))
    put("bankName", JsonPrimitive(bankName))
    put("rawTextStored", JsonPrimitive(storeRawText))
    put("title", JsonPrimitive(if (storeRawText) sanitizedTitle.orEmpty() else ""))
    put("description", JsonPrimitive(if (storeRawText) sanitizedText.orEmpty() else ""))
    put("postedAt", JsonPrimitive(Instant.ofEpochMilli(postedAtEpochMs).toString()))
    put("needsReview", JsonPrimitive(true))
}.toString()
