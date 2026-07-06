package org.jarvis.android.notifications

/** A recognized bank/finance app: its Android package name plus a human-readable label. */
data class BankApp(
    val packageName: String,
    val displayName: String
)

/**
 * Increment E (bank push -> finance draft) — seed whitelist of bank-app packages the
 * notification listener recognizes out of the box.
 *
 * <p>Package names below were verified against public Google Play Store listings, but
 * banks occasionally rename/replace their package id across major app rewrites, and
 * regional variants exist. Treat this as a *starting point*, not ground truth: the
 * bank-selection UI ([org.jarvis.android.ui.notifications.BankNotificationSettingsScreen])
 * lets the user add/remove packages, and [BankAppSettings] persists the actual enabled
 * set independently of this registry.</p>
 */
object BankAppRegistry {

    val KNOWN_BANK_APPS: List<BankApp> = listOf(
        BankApp("com.revolut.revolut", "Revolut"),
        BankApp("pl.mbank", "mBank"),
        BankApp("pl.pkobp.iko", "PKO Bank Polski (IKO)"),
        BankApp("pl.bzwbk.bzwbk24", "Santander"),
        BankApp("wit.android.bcpBankingApp.millenniumPL", "Bank Millennium"),
        BankApp("pl.ing.mojeing", "ING (Moje ING mobile)"),
        BankApp("com.paypal.android.p2pmobile", "PayPal")
    )

    /** Falls back to the raw package name when it isn't one of [KNOWN_BANK_APPS]. */
    fun displayNameFor(packageName: String): String =
        KNOWN_BANK_APPS.firstOrNull { it.packageName == packageName }?.displayName ?: packageName

    /**
     * The full set of known bank-app packages — i.e. every entry in [KNOWN_BANK_APPS].
     *
     * <p>NOT used as the fresh-install default: [BankAppSettings.enabledPackages] defaults
     * to empty (see [DEFAULT_BANK_ENABLED_PACKAGES]) so no bank notification is captured
     * until the user opts one in. This is a convenience accessor for UI/tests that need
     * "every known package" (e.g. a future "select all" action), not a privacy default.</p>
     */
    fun defaultEnabledPackages(): Set<String> = KNOWN_BANK_APPS.map { it.packageName }.toSet()
}
