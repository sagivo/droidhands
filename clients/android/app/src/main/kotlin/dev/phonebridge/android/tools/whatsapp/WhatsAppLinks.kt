package dev.phonebridge.android.tools.whatsapp

import dev.phonebridge.core.tools.ToolException
import java.net.URLEncoder

/** Click-to-chat URLs, recipient validation and confirmation text for WhatsApp (pure). */
object WhatsAppLinks {
    /** Longest message accepted (keeps the launch intent well below Binder limits). */
    const val MAX_MESSAGE_CHARS = 20_000

    /** International digits without '+': 7..15 digits (E.164), no leading zero. */
    fun isValidDigits(digits: String): Boolean =
        digits.length in 7..15 && digits.all { it in '0'..'9' } && digits[0] != '0'

    /** Throws a ToolException explaining the expected format unless [digits] is valid. */
    fun requireValidDigits(digits: String?, original: String): String {
        if (digits == null || !isValidDigits(digits)) {
            throw ToolException(
                "'$original' is not a usable WhatsApp number. Pass it in international format with the country code, " +
                    "e.g. +972541234567 (or pass a person's name as contact).",
            )
        }
        return digits
    }

    /** UTF-8 percent-encoding for a query value, with spaces as %20 (not '+'). */
    fun encodeText(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** `https://api.whatsapp.com/send?phone=<digits>[&text=<encoded>]`. */
    fun apiSendUrl(digits: String, text: String?): String = "https://api.whatsapp.com/send?phone=$digits" + textParam(text)

    /** `whatsapp://send?phone=<digits>[&text=<encoded>]`, the in-app scheme (fallback). */
    fun schemeSendUrl(digits: String, text: String?): String = "whatsapp://send?phone=$digits" + textParam(text)

    private fun textParam(text: String?): String = if (text.isNullOrEmpty()) "" else "&text=" + encodeText(text)

    /** What the user approves, e.g. `Send WhatsApp to Tania Cohen (+972541234567): "hi"`. */
    fun confirmationSummary(displayName: String, digits: String, message: String, business: Boolean): String =
        "Send ${if (business) "WhatsApp Business" else "WhatsApp"} to $displayName (+$digits): \"$message\""
}
