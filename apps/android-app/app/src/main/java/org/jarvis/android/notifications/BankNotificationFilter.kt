package org.jarvis.android.notifications

/**
 * Whitelist gate for [BankNotificationListenerService]: a posted notification is only
 * considered for capture at all when its source package is one the user has explicitly
 * enabled. Everything else — including every non-bank app on the phone — is ignored
 * before the OTP guard or sanitizer ever inspect the notification content.
 */
object BankNotificationFilter {
    fun isWhitelisted(packageName: String, enabledPackages: Set<String>): Boolean =
        packageName in enabledPackages
}
