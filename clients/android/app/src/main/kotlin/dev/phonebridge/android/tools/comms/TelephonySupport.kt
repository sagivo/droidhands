package dev.phonebridge.android.tools.comms

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import dev.phonebridge.core.tools.ToolException

/** Device telephony capabilities and call placement. */
internal object TelephonySupport {
    /** For unavailableReason(): null when the device has cellular telephony. */
    fun unavailableReason(context: Context, what: String): String? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) null
        else "This device has no cellular telephony, so it cannot $what."

    /**
     * Hands a call to the system dialer via TelecomManager.placeCall (no activity start needed, so
     * it works from the background). With several SIMs and no default, Android asks the user which
     * one to use. The caller has checked CALL_PHONE.
     */
    @SuppressLint("MissingPermission") // CALL_PHONE is checked by the tool before confirmation.
    fun placeCall(context: Context, number: String) {
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: throw ToolException("This device cannot place phone calls (no telecom service).")
        try {
            telecom.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), Bundle())
        } catch (e: SecurityException) {
            throw ToolException(
                "Android refused to place the call (CALL_PHONE permission missing or restricted). " +
                    "Ask the user to open the PhoneBridge app > Settings > Permissions & access and grant Phone access.",
            )
        }
    }
}
