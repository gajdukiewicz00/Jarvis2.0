package org.jarvis.android.notifications

/**
 * On-device sanitizer that masks card/account-number-like digit runs before a bank
 * notification's text is queued for sync. Runs only on notifications that already
 * passed [BankNotificationFilter] (whitelisted source) and [OtpGuard] (not an OTP/auth
 * code — those are dropped entirely, never sanitized-and-sent).
 *
 * <p>Heuristic: any run of digits containing 9+ actual digits — allowing single spaces
 * or dashes as internal separators, the way card numbers ("4111 1111 1111 1111") and
 * Polish NRB/IBAN account numbers (26 digits) are typically rendered — is treated as a
 * card/account number and masked down to its last 4 digits. Commas and decimal points
 * are *not* treated as separators, so ordinary monetary amounts ("1,234.56", "12 345,67
 * PLN") stay readable: thousands/decimal groups are at most 3 digits before a comma or
 * period breaks the run, well under the 9-digit threshold.</p>
 */
object NotificationSanitizer {

    private const val MIN_SENSITIVE_DIGITS = 9
    private const val VISIBLE_SUFFIX_DIGITS = 4

    /** Matches runs of digits with optional single spaces/dashes between digits. */
    private val NUMERIC_RUN_REGEX = Regex("""\d(?:[\d \-]*\d)?""")

    fun sanitize(text: String): String {
        if (text.isBlank()) return text
        return NUMERIC_RUN_REGEX.replace(text) { match ->
            val digitsOnly = match.value.filter(Char::isDigit)
            if (digitsOnly.length < MIN_SENSITIVE_DIGITS) match.value else maskKeepingLast4(digitsOnly)
        }
    }

    private fun maskKeepingLast4(digits: String): String {
        val visible = digits.takeLast(VISIBLE_SUFFIX_DIGITS)
        return "•••• $visible"
    }
}
