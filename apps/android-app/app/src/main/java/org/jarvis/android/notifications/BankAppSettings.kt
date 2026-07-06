package org.jarvis.android.notifications

import android.content.Context

/** Raw-text retention window is clamped to this many days (see [BankAppSettings.setRetentionDays]). */
const val MAX_BANK_DRAFT_RETENTION_DAYS = 30

/**
 * Default retention window applied on a fresh install: `0` (do not persist raw
 * notification text at all — see [BankAppSettings.retentionDays]). Secure-by-default:
 * the user must explicitly raise this before any sanitized notification text is stored
 * on-device, rather than the app opting them in to N days of retention.
 */
const val DEFAULT_BANK_DRAFT_RETENTION_DAYS = 0

/**
 * Default master-switch state on a fresh install: capture is OFF. The user must
 * explicitly opt in via [org.jarvis.android.ui.notifications.BankNotificationSettingsScreen]
 * before [BankNotificationListenerService] reads any notification content.
 */
const val DEFAULT_BANK_NOTIFICATION_CAPTURE_ENABLED = false

/**
 * Default bank-app whitelist on a fresh install: empty. No bank app is treated as a
 * trusted source — and therefore no notification content is read — until the user adds
 * one explicitly. [org.jarvis.android.notifications.BankAppRegistry.KNOWN_BANK_APPS]
 * only seeds the *choices* offered in the settings UI; it is deliberately not used as
 * the enabled-by-default set.
 */
val DEFAULT_BANK_ENABLED_PACKAGES: Set<String> = emptySet()

/**
 * Clamps a user-entered retention value into `[0, MAX_BANK_DRAFT_RETENTION_DAYS]`.
 *
 * `0` means "do not store raw notification text at all" (see
 * [BankAppSettings.retentionDays]); extracted as a top-level pure function so the
 * clamping rule is unit-testable without a [Context].
 */
fun clampRetentionDays(days: Int): Int = days.coerceIn(0, MAX_BANK_DRAFT_RETENTION_DAYS)

/**
 * Persists the user's bank-notification privacy choices: which bank-app packages are
 * trusted sources, whether capture is enabled at all, and how long (if at all) sanitized
 * raw notification text may sit on the device before [BankDraftPurgeWorker] deletes it.
 *
 * <p>Plain [android.content.SharedPreferences] is intentional here — unlike
 * [org.jarvis.android.sync.PairingState] (which stores a session key), the data held here
 * is configuration (package names, a day count, a boolean), not a secret.</p>
 */
class BankAppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Bank-app packages the [BankNotificationListenerService] treats as trusted sources.
     * Defaults to [DEFAULT_BANK_ENABLED_PACKAGES] (empty) — no bank is captured until the
     * user opts one in.
     */
    fun enabledPackages(): Set<String> =
        prefs.getStringSet(KEY_ENABLED_PACKAGES, null) ?: DEFAULT_BANK_ENABLED_PACKAGES

    fun setEnabledPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED_PACKAGES, packages).apply()
    }

    /**
     * Master switch — off means the listener ignores every notification regardless of
     * whitelist. Defaults to [DEFAULT_BANK_NOTIFICATION_CAPTURE_ENABLED] (`false`).
     */
    fun isCaptureEnabled(): Boolean = prefs.getBoolean(KEY_CAPTURE_ENABLED, DEFAULT_BANK_NOTIFICATION_CAPTURE_ENABLED)

    fun setCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
    }

    /**
     * Days of on-device raw-text retention before [BankDraftPurgeWorker] deletes a queued
     * draft's sanitized title/description. `0` means raw text is never stored in the first
     * place — only structured metadata (bank, timestamp, needs-review flag) is queued.
     */
    fun retentionDays(): Int = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_BANK_DRAFT_RETENTION_DAYS)

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, clampRetentionDays(days)).apply()
    }

    companion object {
        private const val PREFS_NAME = "jarvis-bank-notifications"
        private const val KEY_ENABLED_PACKAGES = "enabled_packages"
        private const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_RETENTION_DAYS = "retention_days"
    }
}
