package dev.phonebridge.android.tools.whatsapp

import android.content.Context
import android.os.SystemClock
import dev.phonebridge.android.access.AppVisibility
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.tools.screen.NodeActions
import dev.phonebridge.android.tools.screen.ScreenAccess
import dev.phonebridge.android.tools.screen.ScreenCapture
import dev.phonebridge.android.tools.screen.ScreenState
import dev.phonebridge.android.tools.screen.UiAutomation
import dev.phonebridge.android.tools.support.Accessibility
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Drives WhatsApp through the accessibility service. Callers hold the UI mutex
 * ([dev.phonebridge.android.tools.screen.UiAutomation.exclusive]).
 *
 * Safety rules: text is only typed into, and Send only tapped in, a chat that is positively the
 * expected recipient's (title shows the name/number), or, when the title can't be read, a chat
 * that appeared after our own launch (WhatsApp's task is cleared first) and stayed stable.
 * Every failure before the Send tap says "Nothing was sent". Screens are captured through the
 * agent's [AppVisibility], so windows of apps blocked for it are never read. The launch re-checks
 * that the agent is still allowed to act; every node action and tap also refuses while a
 * PhoneBridge approval is pending ([UiAutomation.beforeAction]).
 */
object WhatsAppAutomation {
    private const val OPEN_TIMEOUT_MS = 10_000L
    private const val SEND_BUTTON_TIMEOUT_MS = 4_000L
    private const val VERIFY_TIMEOUT_MS = 5_000L
    private const val STABLE_MS = 600L
    private const val POLL_MS = 250L
    private const val MIN_GAP_MS = 100L
    private const val MAX_TEXT_SETS = 2
    private const val NOTHING_SENT = "Nothing was sent."

    private class Observation(val state: ScreenState, val screen: WaScreen)

    /** The chat with the approved text in the compose field and a visible Send button. */
    private class Ready(val state: ScreenState, val send: UiNode)

    /** Opens the chat prefilled with [message], taps Send, and returns only once sending is confirmed. */
    suspend fun send(
        context: Context,
        service: BridgeAccessibilityService,
        access: ScreenAccess,
        pkg: String,
        target: WhatsAppTarget,
        message: String,
    ) {
        val run = Run(context, service, access, pkg, target)
        val ready = nothingSentOnFailure {
            val launchedAt = SystemClock.uptimeMillis()
            access.ensureStillAllowed()
            BridgeAccessibilityService.expectUiChange()
            WhatsAppLauncher.openChat(context, pkg, target.digits, message, clearTask = true)
            run.awaitChat(launchedAt, OPEN_TIMEOUT_MS, strict = true)
            run.awaitReadyToSend(message)
        }
        val before = WhatsAppScreens.bubbleCount(pkg, ready.state.windows, message)
        run.tapSend(ready.send)
        run.verifySent(message, before)
    }

    /** whatsapp_open_chat: what WhatsApp shows after the launch. */
    class OpenedChat(val confirmed: Boolean, val title: String?, val note: String?)

    /** Watches the screen after [launchedAt] for up to [timeoutMs]; throws on definite failures (not on WhatsApp...). */
    suspend fun observeOpenedChat(
        context: Context,
        service: BridgeAccessibilityService,
        access: ScreenAccess,
        pkg: String,
        target: WhatsAppTarget,
        launchedAt: Long,
        timeoutMs: Long,
    ): OpenedChat {
        val conversation = Run(context, service, access, pkg, target).awaitChat(launchedAt, timeoutMs, strict = false)
        return if (conversation != null) {
            OpenedChat(true, conversation.title, null)
        } else {
            OpenedChat(false, null, "${WhatsAppApps.label(pkg)} opened, but the chat with ${target.description} could not be confirmed on screen. Call screen_read to check.")
        }
    }

    /** Adds "Nothing was sent." to failures of [block] that don't say so already (e.g. a stuck accessibility service). */
    private suspend fun <T> nothingSentOnFailure(block: suspend () -> T): T = try {
        block()
    } catch (e: ToolException) {
        val text = e.message.orEmpty()
        throw if (text.contains(NOTHING_SENT)) e else ToolException("$text $NOTHING_SENT")
    }

    /** One automation run: the parameters every step needs. */
    private class Run(
        val context: Context,
        val service: BridgeAccessibilityService,
        val access: ScreenAccess,
        val pkg: String,
        val target: WhatsAppTarget,
    ) {
        private val label = WhatsAppApps.label(pkg)

        /**
         * Waits for the recipient's chat. [strict] (sending): only a confirmed chat is returned and
         * timeouts throw; otherwise any non-contradicting chat is returned and timeouts yield null.
         */
        suspend fun awaitChat(launchedAt: Long, timeoutMs: Long, strict: Boolean): WaScreen.Conversation? {
            val deadline = launchedAt + timeoutMs
            var candidate: String? = null
            var candidateSince = 0L
            while (true) {
                val seen = BridgeAccessibilityService.lastEventAt.value
                val obs = observe()
                val now = SystemClock.uptimeMillis()
                when (val s = obs.screen) {
                    is WaScreen.NotOnWhatsApp -> throw ToolException(
                        "$label says ${target.description} is not on WhatsApp (\"${s.message}\"). Nothing was sent. " +
                            "Check the number (international format), or use sms_send.",
                    )
                    is WaScreen.Blocked -> throw ToolException(blockedMessage(s))
                    WaScreen.Registration -> throw ToolException(
                        "$label is not set up on this phone (it shows its welcome/verification screen). Nothing was sent. " +
                            "Ask the user to finish setting up $label.",
                    )
                    WaScreen.Locked -> throw ToolException(
                        "$label is locked with its own fingerprint/PIN lock. Nothing was sent. " +
                            "Ask the user to unlock $label (or turn off its app lock), then retry.",
                    )
                    is WaScreen.Conversation -> {
                        // Sending: only a window WhatsApp showed after our launch counts, never the screen from before it.
                        val fresh = (BridgeAccessibilityService.lastWindowStateChangeAt(pkg) ?: 0L) > launchedAt
                        when (WhatsAppScreens.acceptance(WhatsAppScreens.judge(s, target.expected), strict, fresh, s.entryById)) {
                            ChatAcceptance.ACCEPT -> return s
                            ChatAcceptance.ACCEPT_IF_STABLE -> {
                                val key = s.title ?: s.headerTexts.joinToString("|")
                                if (candidate != key) {
                                    candidate = key
                                    candidateSince = now
                                } else if (now - candidateSince >= STABLE_MS) {
                                    return s
                                }
                            }
                            ChatAcceptance.WAIT -> candidate = null
                        }
                    }
                    else -> candidate = null
                }
                if (now >= deadline) {
                    if (!strict) return null
                    BridgeAccessibilityService.healthCached()?.let { throw ToolException(it) }
                    throw ToolException(openTimeoutMessage(obs))
                }
                pause(seen)
            }
        }

        /** Ensures the compose field holds exactly [message] (re-typing it if needed) and Send is visible. */
        suspend fun awaitReadyToSend(message: String): Ready {
            val deadline = SystemClock.uptimeMillis() + SEND_BUTTON_TIMEOUT_MS
            var sets = 0
            while (true) {
                val seen = BridgeAccessibilityService.lastEventAt.value
                val obs = observe()
                val s = obs.screen
                if (s is WaScreen.Blocked) throw ToolException(blockedMessage(s))
                if (s is WaScreen.Conversation) {
                    if (WhatsAppScreens.judge(s, target.expected) == TitleVerdict.MISMATCH) {
                        throw ToolException("The open WhatsApp chat changed (now \"${s.title}\"), so nothing was sent. Retry.")
                    }
                    if (!WhatsAppScreens.sameMessage(s.entryText, message)) {
                        if (sets < MAX_TEXT_SETS) {
                            sets++
                            // The link's text was dropped or a draft replaced it: type the approved message.
                            UiAutomation.beforeAction(access)
                            val typed = withContext(Dispatchers.IO) { NodeActions.refresh(s.entry)?.let { NodeActions.setText(it, message) } ?: false }
                            if (!typed) throw ToolException("Could not put the message into WhatsApp's text box. Nothing was sent.")
                        }
                    } else if (s.send != null) {
                        return Ready(obs.state, s.send)
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    val shown = (s as? WaScreen.Conversation)?.entryText
                    throw ToolException(
                        if (s is WaScreen.Conversation && !WhatsAppScreens.sameMessage(shown, message)) {
                            "WhatsApp's text box does not contain the approved message (it shows \"${TextNorm.truncate(shown.orEmpty(), 80)}\"). Nothing was sent."
                        } else {
                            "Could not find WhatsApp's Send button. Nothing was sent; the message may be typed in the chat. " + screenTail(obs)
                        },
                    )
                }
                pause(seen)
            }
        }

        /** Clicks Send (accessibility action, else a real tap if the text is still unsent). */
        suspend fun tapSend(send: UiNode) {
            val clickTarget = NodeQueries.clickTarget(send) ?: send
            val clicked = unsentIfRefused {
                UiAutomation.beforeAction(access)
                withContext(Dispatchers.IO) { NodeActions.refresh(clickTarget)?.let { NodeActions.click(it) } ?: false }
            }
            if (clicked) return
            // The click may still have gone through; never tap again if the text box already emptied.
            val obs = observe()
            val s = obs.screen
            if (s is WaScreen.Conversation && s.entryText.isEmpty()) return
            val area = send.bounds.intersect(service.screenBounds())
            val tapped = !area.isEmpty && unsentIfRefused {
                UiAutomation.beforeAction(access)
                service.tap(area.centerX, area.centerY)
            }
            if (!tapped) {
                throw ToolException("Could not tap WhatsApp's Send button. The message is typed in the chat but NOT sent; the user can tap Send.")
            }
        }

        /** Runs [block], whose refusals happen before anything is tapped, saying so when it refuses. */
        private suspend fun <T> unsentIfRefused(block: suspend () -> T): T = try {
            block()
        } catch (e: ToolException) {
            throw ToolException("${e.message} The message is typed in the chat but NOT sent.")
        }

        /** Confirms the compose field emptied or a new bubble with the text appeared. */
        suspend fun verifySent(message: String, bubblesBefore: Int) {
            val deadline = SystemClock.uptimeMillis() + VERIFY_TIMEOUT_MS
            while (true) {
                val seen = BridgeAccessibilityService.lastEventAt.value
                val obs = observe()
                when (val s = obs.screen) {
                    is WaScreen.Blocked -> throw ToolException(blockedMessage(s))
                    is WaScreen.Conversation ->
                        if (s.entryText.isEmpty() || WhatsAppScreens.bubbleCount(pkg, obs.state.windows, message) > bubblesBefore) return
                    else -> Unit
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    throw ToolException(
                        "Tapped Send in WhatsApp but could not confirm the message left the text box; it may or may not have been sent. " +
                            "Check with screen_read before retrying, to avoid sending it twice. " + screenTail(obs),
                    )
                }
                pause(seen)
            }
        }

        private suspend fun observe(): Observation {
            if (BridgeAccessibilityService.instance == null) throw ToolException(Accessibility.NOT_ENABLED)
            // Unmasked: the compose field is compared with the approved message. Only screenTail reaches the agent.
            val state = ScreenCapture.capture(context, service, access.visibility)
            return Observation(state, WhatsAppScreens.classify(pkg, state.windows, target.digits))
        }

        private suspend fun pause(sinceEvent: Long) {
            BridgeAccessibilityService.awaitEvent(sinceEvent, POLL_MS)
            delay(MIN_GAP_MS)
        }

        private fun openTimeoutMessage(obs: Observation): String {
            val what = when (val s = obs.screen) {
                WaScreen.Home -> "$label opened its chat list instead of the chat with ${target.description}; the number may not be on WhatsApp."
                is WaScreen.Conversation -> when (WhatsAppScreens.judge(s, target.expected)) {
                    TitleVerdict.MISMATCH ->
                        "$label shows a chat titled \"${s.title ?: s.headerTexts.firstOrNull()}\", which is not ${target.description}. Not sending, to avoid messaging the wrong person."
                    TitleVerdict.MATCH ->
                        "$label shows the chat with ${target.description}, but it did not react to the request to open it (Android may have blocked the launch)."
                    TitleVerdict.UNKNOWN ->
                        "$label shows a chat, but PhoneBridge could not confirm it is the chat with ${target.description}."
                }
                WaScreen.NotVisible -> "$label did not come to the foreground (foreground app: ${obs.state.packageName ?: "unknown"})."
                else -> "Could not find the $label chat with ${target.description}."
            }
            return "$what Nothing was sent. ${screenTail(obs)}"
        }

        private fun blockedMessage(s: WaScreen.Blocked): String =
            "WhatsApp did not send: ${target.description} is blocked (\"${s.message}\"). Nothing was sent. " +
                "Ask the user whether to unblock them in WhatsApp."

        private fun screenTail(obs: Observation): String {
            val texts = NodeQueries.topTexts(obs.state.roots).let { t -> access.redactor?.let(t::map) ?: t }
            return if (texts.isEmpty()) "The screen shows no readable text." else "Screen shows: " + texts.joinToString(" | ")
        }
    }
}
