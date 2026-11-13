package dev.phonebridge.android.tools.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.phonebridge.android.notify.ActionSelector
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fires notification actions the way System UI does when the user taps a button or sends an
 * inline reply. Works while the phone is locked: the target app receives the intent directly.
 */
internal object NotificationSender {
    /**
     * Sends [message] through [action]'s free-form RemoteInput(s), like typing into the
     * notification's reply box.
     */
    suspend fun reply(context: Context, action: Notification.Action, message: String) {
        val inputs = action.remoteInputs.orEmpty()
        val freeForm = inputs.filter { it.allowFreeFormInput }
        val pendingIntent = action.actionIntent
        if (freeForm.isEmpty() || pendingIntent == null) throw ToolException(ActionSelector.NO_REPLY_ACTION)

        val results = Bundle().apply { freeForm.forEach { putCharSequence(it.resultKey, message) } }
        val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        send(context, pendingIntent, fillIn)
    }

    /** Triggers [action] without input, like tapping the button. */
    suspend fun tap(context: Context, action: Notification.Action) {
        val pendingIntent = action.actionIntent
            ?: throw ToolException("This action has nothing to trigger. Open the app instead (app_open, then screen tools).")
        send(context, pendingIntent, null)
    }

    private suspend fun send(context: Context, pendingIntent: PendingIntent, fillIn: Intent?) = withContext(Dispatchers.IO) {
        try {
            pendingIntent.send(context, 0, fillIn, null, null, null, backgroundStartOptions())
        } catch (e: PendingIntent.CanceledException) {
            throw ToolException(
                "The app withdrew this notification action (the notification is probably outdated). " +
                    "Call notifications_list and try again, or use the app.",
            )
        } catch (e: SecurityException) {
            throw ToolException("Android refused to trigger this notification action. Use the app instead (app_open, then screen tools).")
        }
    }

    /**
     * Since Android 14 a sender must opt in to lend its background-activity-start privilege (held
     * while the accessibility service is bound or "display over other apps" is granted), so actions
     * that open an activity work like a user tap.
     */
    private fun backgroundStartOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
}
