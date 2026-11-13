package dev.phonebridge.android.tools.support

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runtime-permission helpers. Messages tell the agent what the *user* must do. */
object Perms {
    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, vararg permissions: String): List<String> = permissions.filterNot { has(context, it) }

    /** For [dev.phonebridge.core.tools.Tool.unavailableReason]: null if all granted. */
    fun unavailableIfMissing(context: Context, vararg permissions: String): String? {
        val missing = missing(context, *permissions)
        if (missing.isEmpty()) return null
        val names = missing.joinToString { it.substringAfterLast('.') }
        return "Permission not granted on the phone: $names. Ask the user to open the PhoneBridge app > Setup and grant it."
    }

    /** Throws [ToolException] if any permission is missing. */
    fun require(context: Context, vararg permissions: String) {
        unavailableIfMissing(context, *permissions)?.let { throw ToolException(it) }
    }
}

/** Accessibility-service helpers for availability checks. */
object Accessibility {
    const val NOT_ENABLED =
        "The PhoneBridge accessibility service is not enabled. Ask the user to open the PhoneBridge app > Setup > Accessibility and turn it on."

    fun unavailableReason(): String? = if (BridgeAccessibilityService.instance == null) NOT_ENABLED else null

    fun require(): BridgeAccessibilityService = BridgeAccessibilityService.instance ?: throw ToolException(NOT_ENABLED)
}

object Launcher {
    /**
     * Starts an activity from the background. Android blocks background activity starts unless an
     * exemption applies; a bound AccessibilityService is one, so prefer its context when available
     * (SYSTEM_ALERT_WINDOW is another). Always adds FLAG_ACTIVITY_NEW_TASK.
     */
    suspend fun startActivity(context: Context, intent: Intent) = withContext(Dispatchers.Main) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launcherContext: Context = BridgeAccessibilityService.instance ?: context.applicationContext
        try {
            launcherContext.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw ToolException("No app on the phone can handle this request (${intent.action ?: "intent"} ${intent.data ?: ""}).")
        } catch (e: SecurityException) {
            throw ToolException("Android refused to start the activity: ${e.message}")
        }
    }
}

object DeviceState {
    fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    fun isScreenOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    /** Throws a ToolException telling the agent the phone must be unlocked. */
    fun requireUnlocked(context: Context, what: String = "This action") {
        if (isLocked(context)) {
            throw ToolException(
                "$what needs the phone to be unlocked, and Android does not let apps unlock it. " +
                    "Ask the user to unlock the phone and retry. (Tip: notification_reply works while locked.)",
            )
        }
    }
}

suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }
