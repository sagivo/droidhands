package com.droidhands.android.ui.setup

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationManagerCompat
import com.droidhands.android.access.BridgeAccessibilityService
import com.droidhands.android.notify.BridgeNotificationListener
import com.droidhands.android.tools.support.PermissionCatalog
import com.droidhands.android.tools.support.PermissionGroup
import com.droidhands.android.tools.support.Perms
import com.droidhands.core.tools.Scopes

/**
 * Reads the current state of every Setup checklist row from the system. Each check is a binder
 * call or a settings read, so run it off the main thread.
 */
internal object SetupChecks {
    /**
     * @param asked permissions requested at least once (from [com.droidhands.android.ui.UiPrefs]).
     * @param showRationale `Activity.shouldShowRequestPermissionRationale`, for "don't ask again" detection.
     */
    @WorkerThread
    fun read(context: Context, asked: Set<String>, showRationale: (String) -> Boolean): List<SetupItem> {
        val items = readAll(context, asked, showRationale)
        // Android let the user grant something restricted, so restricted settings are allowed: drop the hints.
        if (!RestrictedSettings.likelyAllowed(items, Build.VERSION.SDK_INT)) return items
        return items.map { it.copy(restrictedSettingsHelp = false, restrictedSettingsGuide = false) }
    }

    /** True when Android likely restricts settings for this install ([RestrictedSettings.likelyRestricted]). */
    @WorkerThread
    fun installRestricted(context: Context): Boolean = RestrictedSettings.likelyRestricted(Build.VERSION.SDK_INT, installSource(context))

    private fun readAll(context: Context, asked: Set<String>, showRationale: (String) -> Boolean): List<SetupItem> = buildList {
        val source = installSource(context)
        PermissionCatalog.RUNTIME.forEach { group ->
            add(
                if (group.id == PermissionCatalog.NOTIFICATIONS.id) {
                    notifications(context, group, asked, showRationale)
                } else {
                    runtime(context, group, asked, showRationale)
                },
            )
        }
        add(backgroundLocation(context, asked, showRationale))
        add(accessibility(context).withRestrictedHelp(source))
        add(notificationAccess(context).withRestrictedHelp(source))
        add(battery(context))
        add(overlay(context))
        add(doNotDisturb(context))
    }

    private fun runtime(context: Context, group: PermissionGroup, asked: Set<String>, showRationale: (String) -> Boolean): SetupItem {
        val missing = group.permissions.filterNot { Perms.has(context, it) }
        val status = SetupLogic.runtimeStatus(group.permissions.size - missing.size, group.permissions.size)
        val description = if (group.id == SetupLogic.LOCATION) {
            "${group.rationale} Choose \"Precise\" so results are accurate."
        } else {
            group.rationale
        }
        return SetupItem(
            id = group.id,
            title = group.title,
            description = description,
            unlocks = SetupLogic.unlocksForScopes(group.scopes),
            essential = group.id in SetupLogic.ESSENTIAL_IDS,
            status = status,
            permissions = group.permissions,
            blocked = status != SetupStatus.DONE && SetupLogic.isPermanentlyDenied(missing, asked, showRationale),
        )
    }

    /**
     * POST_NOTIFICATIONS only exists on Android 13+; below that (and when the user switched
     * notifications off after granting) the fix is the app's notification settings page, which
     * the UI opens when [SetupItem.permissions] is empty.
     */
    private fun notifications(context: Context, group: PermissionGroup, asked: Set<String>, showRationale: (String) -> Boolean): SetupItem {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val runtime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted = !runtime || Perms.has(context, Manifest.permission.POST_NOTIFICATIONS)
        val status = if (granted && enabled) SetupStatus.DONE else SetupStatus.MISSING
        val blocked = runtime && !granted && SetupLogic.isPermanentlyDenied(group.permissions, asked, showRationale)
        return SetupItem(
            id = group.id,
            title = group.title,
            description = group.rationale + " Without it you won't see when an agent is working, and any approval you asked for times out and is denied.",
            unlocks = "Agent activity · Allow/Deny prompts you asked for · Bridge status notification",
            essential = true,
            status = status,
            permissions = if (runtime && !granted) group.permissions else emptyList(),
            blocked = blocked,
        )
    }

    private fun backgroundLocation(context: Context, asked: Set<String>, showRationale: (String) -> Boolean): SetupItem {
        val group = PermissionCatalog.BACKGROUND_LOCATION
        val foreground = Perms.has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            Perms.has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val status = when {
            group.isGranted(context) -> SetupStatus.DONE
            !foreground -> SetupStatus.NEEDS_PREREQUISITE
            else -> SetupStatus.MISSING
        }
        return SetupItem(
            id = group.id,
            title = group.title,
            description = "${group.rationale} Agent requests arrive while DroidHands is in the background, so Android " +
                "needs \"Allow all the time\".",
            unlocks = "Location while DroidHands isn't open",
            essential = false,
            status = status,
            permissions = group.permissions,
            blocked = status == SetupStatus.MISSING && SetupLogic.isPermanentlyDenied(group.permissions, asked, showRationale),
        )
    }

    private fun accessibility(context: Context): SetupItem {
        val bound = BridgeAccessibilityService.instance != null
        val status = when {
            bound -> SetupStatus.DONE
            safeCheck { BridgeAccessibilityService.isEnabled(context) } -> SetupStatus.PENDING
            else -> SetupStatus.MISSING
        }
        return SetupItem(
            id = SetupLogic.ACCESSIBILITY,
            title = "Accessibility service",
            description = "Lets agents you authorized read the screen and tap, type and scroll in any app, like pressing " +
                "Send in WhatsApp. It also lets DroidHands open apps from the background.",
            unlocks = listOf(Scopes.SCREEN_READ, Scopes.SCREEN_CONTROL).joinToString(" · ") { SetupLogic.shortLabel(it) } +
                " · Automatic WhatsApp Send · Opening apps",
            essential = true,
            status = status,
        )
    }

    private fun notificationAccess(context: Context): SetupItem = SetupItem(
        id = SetupLogic.NOTIFICATION_ACCESS,
        title = "Notification access",
        description = "Lets agents read notifications and reply through them (WhatsApp, Telegram, Signal, Messages…), " +
            "even while the phone is locked.",
        unlocks = SetupLogic.unlocksForScopes(listOf(Scopes.NOTIFICATIONS_READ, Scopes.NOTIFICATIONS_ACT)),
        essential = true,
        status = if (safeCheck { BridgeNotificationListener.isEnabled(context) }) SetupStatus.DONE else SetupStatus.MISSING,
    )

    private fun battery(context: Context): SetupItem {
        val ignoring = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        return SetupItem(
            id = SetupLogic.BATTERY,
            title = "Unrestricted battery use",
            description = "Without this exemption Android may cut the relay connection while the phone sleeps (Doze), " +
                "and agents can't reach the phone.",
            unlocks = "Stays reachable with the screen off",
            essential = true,
            status = if (ignoring) SetupStatus.DONE else SetupStatus.MISSING,
        )
    }

    private fun overlay(context: Context): SetupItem = SetupItem(
        id = SetupLogic.OVERLAY,
        title = "Display over other apps",
        description = "Helps open apps from the background (opening apps, links and calls) when accessibility is off. " +
            "On the next screen, find DroidHands in the list and allow it.",
        unlocks = "Opening apps, links and calls from the background",
        essential = false,
        status = if (Settings.canDrawOverlays(context)) SetupStatus.DONE else SetupStatus.MISSING,
    )

    private fun doNotDisturb(context: Context): SetupItem {
        val granted = context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        return SetupItem(
            id = SetupLogic.DND,
            title = "Do Not Disturb access",
            description = "Optional. Android requires it to switch the ringer to silent.",
            unlocks = "Silent mode",
            essential = false,
            status = if (granted) SetupStatus.DONE else SetupStatus.MISSING,
        )
    }

    private fun SetupItem.withRestrictedHelp(source: InstallSource): SetupItem = copy(
        restrictedSettingsHelp = SetupLogic.showsRestrictedSettingsHelp(id, status, Build.VERSION.SDK_INT, source.packageSource),
        restrictedSettingsGuide = RestrictedSettings.showGuide(id, status, Build.VERSION.SDK_INT, source),
    )

    /** How the app was installed (store, downloaded file, adb…); fields stay null when unknown. */
    private fun installSource(context: Context): InstallSource = try {
        val info = context.packageManager.getInstallSourceInfo(context.packageName)
        InstallSource(
            packageSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) info.packageSource else null,
            installer = info.installingPackageName,
            initiator = info.initiatingPackageName,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Reading the install source failed: ${e.javaClass.name}")
        InstallSource()
    }

    /** A failing check (OEM quirk, component not ready) reads as "not granted" instead of crashing the screen. */
    private inline fun safeCheck(check: () -> Boolean): Boolean = try {
        check()
    } catch (e: Exception) {
        Log.w(TAG, "Setup check failed: ${e.javaClass.name}")
        false
    } catch (e: NotImplementedError) {
        false
    }

    private const val TAG = "DroidHandsUi"
}
