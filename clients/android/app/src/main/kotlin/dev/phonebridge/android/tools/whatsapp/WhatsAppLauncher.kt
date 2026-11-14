package dev.phonebridge.android.tools.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.tools.support.Launcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed-app checks and click-to-chat launches. */
object WhatsAppLauncher {
    /** True if [pkg] is installed and enabled. */
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(pkg, 0)
        }
        info.enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Package to use, or a ToolException when WhatsApp (Business) isn't installed. */
    fun choosePackage(context: Context, business: Boolean): String =
        WhatsAppApps.choose(business) { isInstalled(context, it) }

    /** For unavailableReason(): null when some WhatsApp is installed. */
    fun unavailableReason(context: Context): String? =
        if (isInstalled(context, WhatsAppApps.CONSUMER) || isInstalled(context, WhatsAppApps.BUSINESS)) null else WhatsAppApps.NOT_INSTALLED

    /**
     * Opens the chat with [digits] in [pkg], prefilled with [text] when given. [clearTask] resets
     * WhatsApp's task first, so no previously open chat can be mistaken for the new one.
     */
    suspend fun openChat(context: Context, pkg: String, digits: String, text: String?, clearTask: Boolean) {
        val candidates = listOf(WhatsAppLinks.apiSendUrl(digits, text), WhatsAppLinks.schemeSendUrl(digits, text))
            .map { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg) }
        val intent = withContext(Dispatchers.IO) { candidates.firstOrNull { resolves(context, it) } } ?: candidates.first()
        if (clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Launcher.startActivity(context, intent)
    }

    /**
     * Without the accessibility service, Android may silently block this background launch unless
     * "Display over other apps" is granted. Returns a note for the agent, or null.
     */
    fun backgroundLaunchNote(context: Context): String? =
        if (BridgeAccessibilityService.instance == null && !Settings.canDrawOverlays(context)) {
            "If WhatsApp did not appear, Android blocked opening it from the background: ask the user to enable the PhoneBridge " +
                "accessibility service (or 'Display over other apps') in PhoneBridge app > Settings > Permissions & access."
        } else {
            null
        }

    private fun resolves(context: Context, intent: Intent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0) != null
        }
    } catch (e: RuntimeException) {
        false
    }
}
