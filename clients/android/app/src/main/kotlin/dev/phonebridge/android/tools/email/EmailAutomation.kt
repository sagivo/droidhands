package dev.phonebridge.android.tools.email

import android.content.Context
import android.os.SystemClock
import dev.phonebridge.android.access.AppVisibility
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.tools.screen.NodeActions
import dev.phonebridge.android.tools.screen.ScreenCapture
import dev.phonebridge.android.tools.screen.ScreenState
import dev.phonebridge.android.tools.support.Accessibility
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Sends an approved email through a mail app's own compose screen with the accessibility service. Callers hold
 * the UI mutex ([dev.phonebridge.android.tools.screen.UiAutomation.exclusive]).
 *
 * Safety rules: only a compose screen that appeared after our own launch is used; fields are only filled when
 * empty, and Send is only tapped when To (and Cc), Subject and body show exactly the approved email
 * ([ComposeChecks]). Every failure before the Send tap says "Nothing was sent".
 */
object EmailAutomation {
    private const val OPEN_TIMEOUT_MS = 10_000L
    private const val READY_TIMEOUT_MS = 6_000L
    private const val VERIFY_TIMEOUT_MS = 8_000L
    private const val CLOSED_STABLE_MS = 400L
    private const val POLL_MS = 250L
    private const val MIN_GAP_MS = 100L
    private const val MAX_FILLS_PER_FIELD = 2
    private const val NOTHING_SENT = "Nothing was sent."

    /** Opens [pkg]'s compose screen with [draft], verifies it, taps Send and returns once sending is confirmed. */
    suspend fun send(
        context: Context,
        service: BridgeAccessibilityService,
        visibility: AppVisibility,
        pkg: String,
        appLabel: String,
        draft: EmailDraft,
    ) {
        val run = Run(context, service, visibility, pkg, appLabel, draft)
        val ready = nothingSentOnFailure {
            val launchedAt = SystemClock.uptimeMillis()
            BridgeAccessibilityService.expectUiChange()
            EmailLauncher.open(context, pkg, draft)
            run.awaitCompose(launchedAt)
            run.awaitReady()
        }
        val baseline = SendSignals.baseline(pkg, ready.state.windows)
        run.tapSend(ready.send)
        run.verifySent(baseline)
    }

    private suspend fun <T> nothingSentOnFailure(block: suspend () -> T): T = try {
        block()
    } catch (e: ToolException) {
        val text = e.message.orEmpty()
        throw if (text.contains(NOTHING_SENT, ignoreCase = true)) e else ToolException("$text $NOTHING_SENT")
    }

    /** The compose screen showing the approved email, with its Send button. */
    private class Ready(val state: ScreenState, val send: UiNode)

    /** One send: the parameters every step needs. */
    private class Run(
        val context: Context,
        val service: BridgeAccessibilityService,
        val visibility: AppVisibility,
        val pkg: String,
        val label: String,
        val draft: EmailDraft,
    ) {
        /** Waits for a compose screen of [pkg] shown after [launchedAt]. */
        suspend fun awaitCompose(launchedAt: Long) {
            val deadline = launchedAt + OPEN_TIMEOUT_MS
            while (true) {
                val seen = BridgeAccessibilityService.lastEventAt.value
                val state = observe()
                val compose = EmailCompose.find(pkg, state.windows)
                // Only a window the app showed after our launch counts, never a draft that was already open.
                val fresh = (BridgeAccessibilityService.lastWindowStateChangeAt(pkg) ?: 0L) > launchedAt
                if (compose != null && fresh) return
                if (SystemClock.uptimeMillis() >= deadline) {
                    BridgeAccessibilityService.healthCached()?.let { throw ToolException(it) }
                    throw ToolException(openTimeoutMessage(state, compose != null))
                }
                pause(seen)
            }
        }

        /** Fills empty fields, then waits until every field shows the approved email and Send is available. */
        suspend fun awaitReady(): Ready {
            val deadline = SystemClock.uptimeMillis() + READY_TIMEOUT_MS
            val fills = HashMap<ComposeField, Int>()
            while (true) {
                val seen = BridgeAccessibilityService.lastEventAt.value
                val state = observe()
                val compose = EmailCompose.find(pkg, state.windows)
                    ?: throw ToolException("$label's compose screen closed before Send was tapped. $NOTHING_SENT")
                val checks = ComposeChecks.check(compose, draft)
                if (ComposeChecks.allOk(checks) && compose.send != null) return Ready(state, compose.send)
                for (fill in checks.filterIsInstance<FieldCheck.Fill>()) {
                    val count = fills.getOrDefault(fill.field, 0)
                    if (count >= MAX_FILLS_PER_FIELD) continue
                    fills[fill.field] = count + 1
                    val node = compose.fields.getValue(fill.field)
                    val typed = withContext(Dispatchers.IO) { NodeActions.refresh(node)?.let { NodeActions.setText(it, fill.text) } ?: false }
                    if (!typed) throw ToolException("Could not fill the ${fill.field.label} field of $label's compose screen. $NOTHING_SENT")
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    val problem = ComposeChecks.problem(checks, label)
                        ?: if (checks.any { it is FieldCheck.Fill }) {
                            "$label did not accept the text PhoneBridge typed into its compose screen, so nothing was sent."
                        } else {
                            "Could not find an enabled Send button on $label's compose screen, so nothing was sent."
                        }
                    throw ToolException("$problem The draft is still open on the phone; the user can check it and tap Send, or discard it. ${screenTail(state)}")
                }
                pause(seen)
            }
        }

        /** Clicks Send (accessibility action, else a real tap if the compose screen is still open). */
        suspend fun tapSend(send: UiNode) {
            val target = NodeQueries.clickTarget(send) ?: send
            val clicked = withContext(Dispatchers.IO) { NodeActions.refresh(target)?.let { NodeActions.click(it) } ?: false }
            if (clicked) return
            // The click may still have gone through: never tap again once the compose screen is gone.
            if (EmailCompose.find(pkg, observe().windows) == null) return
            val area = send.bounds.intersect(service.screenBounds())
            if (area.isEmpty || !service.tap(area.centerX, area.centerY)) {
                throw ToolException("Could not tap Send in $label. The email is filled in but NOT sent; the user can tap Send.")
            }
        }

        /** Confirms the compose screen closed (stably) or a "Sending"/"Sent" message appeared. */
        suspend fun verifySent(baseline: Set<String>) {
            val deadline = SystemClock.uptimeMillis() + VERIFY_TIMEOUT_MS
            var closedSince: Long? = null
            var confirmedNoSubject = false
            while (true) {
                val seen = BridgeAccessibilityService.lastEventAt.value
                if (DeviceState.isLocked(context)) {
                    throw ToolException(
                        "The phone locked right after Send was tapped in $label, so PhoneBridge could not confirm the email left. " +
                            "It may have been sent: check before retrying, to avoid sending it twice.",
                    )
                }
                val state = observe()
                val now = SystemClock.uptimeMillis()
                when (val outcome = SendSignals.classify(pkg, state.windows, baseline)) {
                    is AfterSend.Signal -> return
                    AfterSend.Closed -> {
                        val since = closedSince ?: now.also { closedSince = it }
                        if (now - since >= CLOSED_STABLE_MS) return
                    }
                    AfterSend.Composing -> closedSince = null
                    is AfterSend.SubjectPrompt -> {
                        if (draft.subject.isNotBlank() || confirmedNoSubject) throw ToolException(problemMessage(outcome.text))
                        // The user approved this email without a subject: confirming the app's question is part of sending it.
                        confirmedNoSubject = true
                        val target = NodeQueries.clickTarget(outcome.confirm) ?: outcome.confirm
                        withContext(Dispatchers.IO) { NodeActions.refresh(target)?.let { NodeActions.click(it) } }
                    }
                    is AfterSend.Problem -> throw ToolException(problemMessage(outcome.text))
                }
                if (now >= deadline) {
                    throw ToolException(
                        "Tapped Send in $label, but its compose screen is still open, so the email may NOT have been sent. " +
                            "Check with screen_read before retrying, to avoid sending it twice. ${screenTail(state)}",
                    )
                }
                pause(seen)
            }
        }

        private fun problemMessage(text: String) =
            "$label showed \"$text\" after Send was tapped, so the email was most likely NOT sent; its compose screen may still " +
                "be open on the phone. Tell the user, or fix the cause and retry."

        private suspend fun observe(): ScreenState {
            if (BridgeAccessibilityService.instance == null) throw ToolException(Accessibility.NOT_ENABLED)
            return ScreenCapture.capture(context, service, visibility)
        }

        private suspend fun pause(sinceEvent: Long) {
            BridgeAccessibilityService.awaitEvent(sinceEvent, POLL_MS)
            delay(MIN_GAP_MS)
        }

        private fun openTimeoutMessage(state: ScreenState, composeVisible: Boolean): String {
            val visible = state.windows.any { it.packageName == pkg && !it.hidden }
            val what = when {
                composeVisible ->
                    "$label shows a compose screen, but it did not open in response to this request (Android may have blocked the launch)."
                !visible -> "$label did not come to the foreground (foreground app: ${state.packageName ?: "unknown"})."
                else -> "$label opened, but PhoneBridge did not recognize a compose screen (it may need an account set up first)."
            }
            return "$what $NOTHING_SENT ${screenTail(state)}"
        }

        private fun screenTail(state: ScreenState): String {
            val texts = NodeQueries.topTexts(state.roots)
            return if (texts.isEmpty()) "The screen shows no readable text." else "Screen shows: " + texts.joinToString(" | ")
        }
    }
}
