package com.droidhands.ios.apps

import com.droidhands.core.screen.ScreenException
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.AppBlockedException
import com.droidhands.core.tools.CallContext
import com.droidhands.core.tools.ToolException
import com.droidhands.ios.inventory.IosApp
import com.droidhands.ios.runner.IosDevice
import com.droidhands.ios.runner.RunnerScreenDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Opens links on the iPhone with app access applied before and after (used by `url_open` and by `url` and `open_link`
 * actions). iOS, not DroidHands, picks the app for a link, so:
 *
 * * Before: a link pinned to an app opens only if the installed app declares it ([IosLinks.declares]); an unpinned
 *   link is refused when every installed app that declares it is blocked for the agent.
 * * After: the app that came to the front is reported; if it is one the agent may not use, the iPhone goes back to
 *   the Home Screen and the call is refused ([AppBlockedException]), so the agent never works in it.
 */
class LinkOpener(
    private val device: IosDevice,
    private val pollMs: Long = 300,
    private val settleMs: Long = 4_000,
) {
    /** What happened: [foreground] is the app in front afterwards (null if the runner couldn't tell). */
    data class Opened(val foreground: String?, val before: String?, val handlers: List<IosApp>) {
        val changed: Boolean get() = foreground != before
    }

    /**
     * Opens [link], pinned to [pinned] if non-null. [apps] are the installed apps (for handlers and labels).
     * [verifyDeclared] false: the inventory can't read what apps declare, so a pinned link is tried and only the app
     * that came to the front is checked.
     * @throws AppBlockedException for blocked apps, [ToolException] when the pinned app doesn't declare the link or the
     *   runner refuses.
     */
    suspend fun open(
        link: CheckedUrl,
        pinned: IosApp?,
        apps: List<IosApp>,
        access: AppAccess,
        ctx: CallContext,
        verifyDeclared: Boolean = true,
    ): Opened {
        val handlers = if (pinned != null) listOf(pinned) else IosLinks.handlers(apps, link)
        if (pinned != null) {
            access.require(pinned.bundleId, pinned.name)
            if (verifyDeclared && !IosLinks.declares(pinned, link)) {
                throw ToolException(IosLinks.notDeclared(pinned, link) + " Retry without 'package' to let iOS pick the app, or use phone use.")
            }
        } else if (handlers.isNotEmpty() && handlers.none { access.isAllowed(it.bundleId) }) {
            // Every app that would open it is off limits: refuse before anything happens, naming one of them.
            val blocked = handlers.first()
            access.require(blocked.bundleId, blocked.name)
        }
        val prefer = handlers.map { it.bundleId }
        val before = foregroundOrNull(prefer)
        ctx.ensureStillAllowed()
        screen { device.openUrl(link.url) }
        val after = settle(before, pinned?.bundleId ?: handlers.singleOrNull()?.bundleId, prefer)
        if (after != null && !access.isAllowed(after)) {
            val label = apps.firstOrNull { it.bundleId == after }?.name
            try {
                device.globalAction(RunnerScreenDriver.HOME)
            } catch (e: ScreenException) {
                // Refused anyway; the screen tools won't operate the blocked app either.
            }
            val refusal = try {
                access.require(after, label)
                null
            } catch (e: AppBlockedException) {
                e
            }
            if (refusal != null) {
                throw AppBlockedException(after, "${refusal.message} iOS opened the link in it, so DroidHands went back to the Home Screen.")
            }
        }
        return Opened(after, before, handlers)
    }

    /** Waits until the app in front changes (or is [expected]), up to [settleMs]; the last answer otherwise. */
    private suspend fun settle(before: String?, expected: String?, prefer: List<String>): String? {
        val deadline = System.currentTimeMillis() + settleMs
        var current: String?
        do {
            delay(pollMs)
            current = foregroundOrNull(prefer)
            if (current != null && (current != before || current == expected)) return current
        } while (System.currentTimeMillis() < deadline)
        return current
    }

    private suspend fun foregroundOrNull(prefer: List<String>): String? = try {
        device.foreground(prefer)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ScreenException) {
        null
    }

    companion object {
        /** Runs a runner call, turning its [ScreenException] into the [ToolException] agents see. */
        suspend fun <T> screen(block: suspend () -> T): T = try {
            block()
        } catch (e: ScreenException) {
            throw ToolException(e.message ?: "The iPhone couldn't do that.")
        }
    }
}
