package dev.phonebridge.android.live

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.mcp.ActiveCall
import dev.phonebridge.core.mcp.CallActivity

/**
 * The live "agent activity" notification (docs/SECURITY.md "Activity notification"), fed by the MCP server's
 * [CallActivity]: [AgentActivityState] decides what to show, [AgentActivityPresentation] how, and
 * [AgentActivityNotification] posts it. Events arrive on the calls' threads and are handled on the main
 * thread. Updates are spaced at least [MIN_UPDATE_INTERVAL_MS] apart, because Android drops updates posted
 * faster than about five per second, which could leave a stale card. Shows nothing while [enabled] is false
 * (Settings > "Show when agents are working").
 */
class AgentActivityTracker(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : CallActivity {
    private val handler = Handler(Looper.getMainLooper())
    private val state = AgentActivityState()
    private val refresh = Runnable { refreshNow() }

    // Main thread only.
    private var shownContent: ActivityContent? = null
    private var lastPostAt = 0L

    override fun onStarted(call: ActiveCall) = onMain { state.started(call) }

    override fun onUpdated(call: ActiveCall) = onMain { state.updated(call) }

    override fun onFinished(call: ActiveCall, outcome: AuditOutcome) = onMain { state.finished(call, outcome) }

    /** The user swiped the notification away: hide it until the current calls have ended. */
    fun dismiss() = onMain {
        state.dismiss()
        shownContent = null
    }

    /** The setting changed: show or hide at once. */
    fun settingChanged() = onMain { }

    private fun onMain(block: () -> Unit) {
        handler.post {
            block()
            scheduleRefresh()
        }
    }

    private fun scheduleRefresh(atUptime: Long = SystemClock.uptimeMillis()) {
        handler.removeCallbacks(refresh)
        handler.postAtTime(refresh, maxOf(atUptime, lastPostAt + MIN_UPDATE_INTERVAL_MS))
    }

    private fun refreshNow() {
        val now = clock()
        val content = if (enabled()) {
            state.snapshot(now)?.let { AgentActivityPresentation.content(it, Build.VERSION.SDK_INT) }
        } else {
            state.clear()
            null
        }
        if (content != shownContent) {
            post(content)
            shownContent = content
            lastPostAt = SystemClock.uptimeMillis()
        }
        state.nextChangeAt(now)?.let { at -> scheduleRefresh(SystemClock.uptimeMillis() + (at - now)) }
    }

    private fun post(content: ActivityContent?) {
        try {
            if (content == null) {
                AgentActivityNotification.cancel(context)
            } else if (AgentActivityNotification.canPost(context)) {
                AgentActivityNotification.show(context, content)
            }
        } catch (e: RuntimeException) {
            // SecurityException (notifications turned off meanwhile) and binder failures: the calls go on.
            Log.w(TAG, "Could not update the activity notification", e)
        }
    }

    companion object {
        private const val TAG = "PhoneBridge/Activity"

        /** Minimum spacing between notification updates. */
        const val MIN_UPDATE_INTERVAL_MS = 300L
    }
}
