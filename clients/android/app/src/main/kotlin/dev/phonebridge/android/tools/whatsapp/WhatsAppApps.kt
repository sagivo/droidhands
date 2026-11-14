package dev.phonebridge.android.tools.whatsapp

import dev.phonebridge.core.tools.ToolException

/** WhatsApp package selection (pure). */
object WhatsAppApps {
    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    /**
     * [business] → WhatsApp Business (must be installed). Otherwise regular WhatsApp if installed,
     * else WhatsApp Business if installed. Throws a ToolException when nothing suitable is installed.
     */
    fun choose(business: Boolean, isInstalled: (String) -> Boolean): String {
        if (business) {
            if (isInstalled(BUSINESS)) return BUSINESS
            throw ToolException(
                "WhatsApp Business is not installed on the phone." +
                    if (isInstalled(CONSUMER)) " Retry without business=true to use regular WhatsApp." else "",
            )
        }
        return when {
            isInstalled(CONSUMER) -> CONSUMER
            isInstalled(BUSINESS) -> BUSINESS
            else -> throw ToolException(NOT_INSTALLED)
        }
    }

    /** "WhatsApp" or "WhatsApp Business". */
    fun label(pkg: String): String = if (pkg == BUSINESS) "WhatsApp Business" else "WhatsApp"

    const val NOT_INSTALLED = "WhatsApp is not installed on the phone. Use sms_send instead, or ask the user to install WhatsApp."
}
