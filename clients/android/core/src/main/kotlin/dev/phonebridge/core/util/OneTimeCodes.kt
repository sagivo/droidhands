package dev.phonebridge.core.util

/**
 * Masks one-time / verification / 2FA codes in message text before agents see it
 * (docs/SECURITY.md "Hide one-time codes"). Must handle common formats in many languages:
 * "Your code is 123456", "G-123456 is your Google verification code", "קוד האימות שלך: 4821",
 * "Use 12345678 to log in", "OTP: 9876", codes with a space/dash ("123 456", "123-456"), and
 * alphanumeric codes adjacent to code keywords. Ordinary numbers (prices, dates, times, phone numbers,
 * amounts) in texts without code keywords must be preserved.
 *
 * STUB — implemented by the core agent (wave 2).
 */
object OneTimeCodes {
    const val MASK = "[code hidden]"

    /** Returns [text] with every detected one-time code replaced by [MASK]. */
    fun redact(text: String): String = TODO()

    /** True if [text] looks like it contains a one-time code. */
    fun containsCode(text: String): Boolean = TODO()
}
