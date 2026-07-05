package org.jarvis.android.notifications

/**
 * Blocks one-time-passcode / 2FA / transaction-authorization notifications outright.
 *
 * <p>These must NEVER be forwarded off-device, whitelisted bank app or not — a false
 * negative here (letting an OTP through) is a direct account-takeover risk, while a
 * false positive (dropping a legitimate transaction notification because it happens to
 * mention "code") merely costs the user one manual finance entry. The keyword list is
 * therefore intentionally broad rather than narrowly precise, and covers English plus
 * Polish (the primary market of the seed bank whitelist in [BankAppRegistry]).</p>
 *
 * <p>Matched against the notification's title + text combined, case-insensitively.
 * This check runs *before* [NotificationSanitizer] and before anything is queued — a
 * blocked notification is never persisted, never logged with its content, and never
 * reaches the sync queue.</p>
 */
object OtpGuard {

    private val KEYWORD_PATTERNS: List<Regex> = listOf(
        // English
        """\botp\b""",
        """one[- ]?time[- ]?(?:password|code|passcode|pin)""",
        """verification code""",
        """security code""",
        """authoriz(?:e|ation) code""",
        """authoris(?:e|ation) code""",
        """auth code""",
        """login code""",
        """confirmation code""",
        """access code""",
        """\bpasscode\b""",
        """\b2fa\b""",
        """\bmfa\b""",
        // Polish
        """kod (?:autoryzacyjny|weryfikacyjny|jednorazowy|sms|dostępu|logowania|potwierdzaj\w*)""",
        """hasł\w* jednorazow\w*"""
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** @return true if [title]/[text] looks like an OTP, 2FA challenge, or authorization code. */
    fun isOtpOrAuthCode(title: String?, text: String?): Boolean {
        val combined = listOfNotNull(title, text).joinToString(" ")
        if (combined.isBlank()) return false
        return KEYWORD_PATTERNS.any { it.containsMatchIn(combined) }
    }
}
