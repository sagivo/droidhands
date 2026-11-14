package dev.phonebridge.android.tools.email

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.notify.AppLabels
import dev.phonebridge.android.tools.support.Launcher

/** Email-app discovery and the compose intent. PackageManager calls are blocking IPC: call off the main thread. */
object EmailLauncher {
    private val MAILTO: Uri = Uri.parse("mailto:")

    /** Packages that can compose an email (`ACTION_SENDTO mailto:`), PhoneBridge excluded. */
    fun handlers(context: Context): List<String> = try {
        query(context.packageManager, Intent(Intent.ACTION_SENDTO, MAILTO))
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()
    } catch (e: RuntimeException) {
        emptyList()
    }

    /** The package Android opens `mailto:` with; a chooser/resolver package when there is no default. */
    fun defaultHandler(context: Context): String? = try {
        resolve(context.packageManager, Intent(Intent.ACTION_SENDTO, MAILTO))?.activityInfo?.packageName
    } catch (e: RuntimeException) {
        null
    }

    /** See [EmailApps.choose]. Throws ToolException when no suitable app exists. */
    fun chooseApp(context: Context, requested: String?): String = EmailApps.choose(
        requested = requested?.trim()?.takeIf { it.isNotEmpty() },
        defaultHandler = defaultHandler(context),
        handlers = handlers(context),
        isInstalled = { isInstalled(context, it) },
        label = { AppLabels.of(context, it) },
    )

    /** For `unavailableReason()`: null when some app can compose email. */
    fun unavailableReason(context: Context): String? = if (handlers(context).isEmpty()) EmailApps.NO_EMAIL_APP else null

    /**
     * `ACTION_SENDTO` with everything in the `mailto:` URI (for apps that only read it) and in the standard
     * extras (for apps that prefer them), targeted at [pkg].
     */
    fun composeIntent(pkg: String, draft: EmailDraft): Intent {
        val to = draft.to.map { it.address }
        val cc = draft.cc.map { it.address }
        return Intent(Intent.ACTION_SENDTO, Uri.parse(Mailto.uri(to, cc, draft.subject, draft.body)))
            .setPackage(pkg)
            .putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
            .apply { if (cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, cc.toTypedArray()) }
            .apply { if (draft.subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, draft.subject) }
            .putExtra(Intent.EXTRA_TEXT, draft.body)
    }

    /** Opens [pkg]'s compose screen filled with [draft]. */
    suspend fun open(context: Context, pkg: String, draft: EmailDraft) = Launcher.startActivity(context, composeIntent(pkg, draft))

    /** Without the accessibility service Android may block background launches; a note for the agent, or null. */
    fun backgroundLaunchNote(context: Context): String? =
        if (BridgeAccessibilityService.instance == null && !Settings.canDrawOverlays(context)) {
            "If the compose screen did not appear, Android blocked opening it from the background: ask the user to enable " +
                "the PhoneBridge accessibility service (or 'Display over other apps') in PhoneBridge > Setup."
        } else {
            null
        }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(pkg, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun resolve(pm: PackageManager, intent: Intent): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
}
