package dev.phonebridge.core.screen

import dev.phonebridge.core.plugins.ScrollDirection
import dev.phonebridge.core.tools.AppBlockedException
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.util.OneTimeCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits and polling of phone use. A device has no "UI is idle" signal over the runner protocol, so the tools wait a
 * little after each gesture and re-read while the screen still looks like it is loading. [clock] is for tests.
 */
class PhoneUseTiming(
    /** Wait after a gesture before reading the result. */
    val settleMs: Long = 500,
    /** Wait between reads while a screen is still loading (empty tree, app starting). */
    val retryMs: Long = 350,
    /** Reads at most while a screen is loading, for screen_observe (actions use half). */
    val settleAttempts: Int = 6,
    /** ui_wait and recipe polling interval. */
    val pollMs: Long = 400,
    /** Wait after tapping a text field before typing, for the keyboard to come up. */
    val focusMs: Long = 400,
    /** How long an action waits for another one on the same device to finish. */
    val busyWaitMs: Long = 20_000,
    val clock: () -> Long = System::currentTimeMillis,
)

/** Runs a driver call, turning its [ScreenException] into a [ToolException] with the same message. */
internal inline fun <T> device(block: () -> T): T = try {
    block()
} catch (e: ScreenException) {
    throw ToolException(e.message ?: "The phone could not do that.")
}

/** A swipe that moves content in a [ScrollDirection]: the finger goes the opposite way. */
internal class SwipePlan(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/** What the screen tools and screen recipes share for one device: captures, refusals, the gesture lock and reports. */
internal class ScreenOps(
    val driver: ScreenDriver,
    private val appLabel: suspend (String) -> String?,
    private val systemSurfaces: Set<String>,
    val timing: PhoneUseTiming,
) {
    val state: DriverState = DriverState.of(driver)

    fun visibility(ctx: CallContext) = Visibility(ctx.appAccess, systemSurfaces)

    fun refs(ctx: CallContext): RefRegistry = state.refs(ctx.tokenId)

    /** What the calling agent may see now: blocked apps left out, one-time codes masked when the user hides them. */
    suspend fun capture(ctx: CallContext, mask: Boolean = true): Capture {
        val snapshot = device { driver.snapshot() }
        return Capture.of(snapshot, visibility(ctx), if (mask && ctx.redactOneTimeCodes) OneTimeCodes::redact else null)
    }

    /** A user-facing app name, or null. */
    suspend fun label(appId: String): String? = try {
        appLabel(appId)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** `com.apple.MobileSMS (Messages)`, or the id alone. */
    suspend fun named(appId: String?): String {
        appId ?: return "unknown"
        return label(appId)?.takeIf { it != appId }?.let { "$appId ($it)" } ?: appId
    }

    /** Throws the user's refusal for the blocked [pkg] ([dev.phonebridge.core.tools.AppAccess.require]), optionally after [prefix]. */
    suspend fun refuse(ctx: CallContext, pkg: String, prefix: String? = null): Nothing {
        val label = label(pkg)
        try {
            ctx.appAccess.require(pkg, label)
        } catch (e: AppBlockedException) {
            throw if (prefix == null) e else AppBlockedException(pkg, "$prefix ${e.message}")
        }
        // Only reached for a system surface the user blocked by name, which AppAccess itself allows.
        throw AppBlockedException(
            pkg,
            listOfNotNull(prefix, "This agent isn't allowed to use ${label ?: pkg}. Don't try to reach it another way.").joinToString(" "),
        )
    }

    /** Throws if a touch at ([x], [y]) would land on an overlay of an app blocked for this agent. */
    suspend fun checkPoint(ctx: CallContext, capture: Capture, x: Int, y: Int) {
        capture.blockedOverlays.firstOrNull { it.root.bounds.contains(x, y) }
            ?.let { refuse(ctx, it.owner, "Refusing to touch ($x,$y): it is on a window of a blocked app.") }
    }

    /**
     * Runs [block] holding the device's gesture lock; fails clearly if another sequence holds it longer than
     * [PhoneUseTiming.busyWaitMs]. Once held, re-checks that the agent is still allowed to act.
     */
    suspend fun <T> exclusive(ctx: CallContext, block: suspend () -> T): T {
        val acquired = withTimeoutOrNull(timing.busyWaitMs) { state.mutex.lock() } != null
        if (!acquired) throw ToolException(ScreenTexts.BUSY)
        try {
            ctx.ensureStillAllowed()
            return block()
        } finally {
            state.mutex.unlock()
        }
    }

    /** One side effect on the device, right after checking the agent may still act. */
    suspend fun <T> act(ctx: CallContext, block: suspend () -> T): T {
        ctx.ensureStillAllowed()
        return device { block() }
    }

    /** Nothing readable yet, or no tree for the app in front: the screen is probably still loading. */
    fun loading(c: Capture): Boolean =
        c.blockedForeground == null && !c.locked &&
            (c.primaryRoot == null || c.visibleNodes().none { ScreenFormat.isInformative(it.el) } || ScreenMarks.thinTree(c))

    /**
     * A capture once the screen has settled: re-read (up to [attempts] times) while it looks like it is loading, or until
     * [until] holds (an action's effect can take a moment to show, e.g. the animation back to the Home Screen).
     */
    suspend fun settled(
        ctx: CallContext,
        attempts: Int = (timing.settleAttempts + 1) / 2,
        waitFirst: Boolean = true,
        mask: Boolean = true,
        until: ((Capture) -> Boolean)? = null,
    ): Capture {
        if (waitFirst) delay(timing.settleMs)
        var capture = capture(ctx, mask)
        var attempt = 1
        while (attempt < attempts && (loading(capture) || until?.invoke(capture) == false)) {
            delay(timing.retryMs)
            attempt++
            capture = capture(ctx, mask)
        }
        return capture
    }

    /** True if anything an agent would notice differs between [before] and [after] (what [report] describes). */
    fun changed(before: Capture, after: Capture): Boolean =
        ScreenChange.describe(ScreenSignature.of(before), ScreenSignature.of(after)) != ScreenTexts.NO_CHANGE

    /**
     * The result line of an action: [message], the foreground app, and what changed since [before] ([noChange] replaces
     * the generic "did not visibly change" sentence). If the action brought an app blocked for this agent to the
     * foreground, presses Home (when the device has it) and throws instead.
     */
    suspend fun report(ctx: CallContext, message: String, before: Capture?, after: Capture, noChange: String? = null): String {
        after.blockedForeground?.let { blocked ->
            val wentHome = "home" in driver.globalActions && try {
                driver.globalAction("home")
                true
            } catch (e: ScreenException) {
                false
            }
            throw AppBlockedException(blocked, ScreenTexts.blockedAfterAction(message, label(blocked) ?: blocked, wentHome))
        }
        val change = before?.let {
            val text = ScreenChange.describe(ScreenSignature.of(it), ScreenSignature.of(after))
            if (noChange != null && text == ScreenTexts.NO_CHANGE) noChange else text
        }
        return listOfNotNull("$message Foreground app: ${named(after.foreground)}.", change).joinToString(" ")
    }

    // ---- scrolling ------------------------------------------------------------------------------

    /**
     * Scrolls [container] (ref and element; null: swipes across the middle of the screen) in [direction] with a swipe
     * inside its visible part. Every touch is checked against blocked overlays; [confine] (screen recipes) is called with
     * the start point before the swipe.
     */
    suspend fun scrollWith(
        ctx: CallContext,
        capture: Capture,
        direction: ScrollDirection,
        container: Pair<String, Node>?,
        confine: suspend (Int, Int) -> Unit = { _, _ -> },
    ): String {
        val dir = direction.id
        if (container == null) {
            swipe(ctx, capture, swipePlan(direction, inset(capture.screen)), confine)
            return "No scrollable element found; swiped $dir across the screen."
        }
        val (ref, node) = container
        val label = "$ref ${ScreenFormat.label(node.el)}"
        val area = node.visibleBounds
        if (area.isEmpty) throw ToolException("Element $label is not visible on screen, so it can't be scrolled. Call screen_observe again.")
        swipe(ctx, capture, swipePlan(direction, area), confine)
        return "Swiped $dir inside $label."
    }

    private suspend fun swipe(ctx: CallContext, capture: Capture, plan: SwipePlan, confine: suspend (Int, Int) -> Unit) {
        checkPoint(ctx, capture, plan.x1, plan.y1)
        confine(plan.x1, plan.y1)
        act(ctx) { driver.swipe(plan.x1.toDouble(), plan.y1.toDouble(), plan.x2.toDouble(), plan.y2.toDouble(), SWIPE_MS) }
    }

    companion object {
        const val SWIPE_MS = 350L
        const val LONG_PRESS_MS = 800L

        /** A finger drag across the middle half of [bounds] that moves content in [direction]. */
        fun swipePlan(direction: ScrollDirection, bounds: Rect): SwipePlan {
            val cx = bounds.centerX
            val cy = bounds.centerY
            val nearY = bounds.y + bounds.height / 4
            val farY = bounds.y + bounds.height * 3 / 4
            val nearX = bounds.x + bounds.width / 4
            val farX = bounds.x + bounds.width * 3 / 4
            return when (direction) {
                // To reveal content below, the finger moves up.
                ScrollDirection.DOWN -> SwipePlan(cx, farY, cx, nearY)
                ScrollDirection.UP -> SwipePlan(cx, nearY, cx, farY)
                ScrollDirection.RIGHT -> SwipePlan(farX, cy, nearX, cy)
                ScrollDirection.LEFT -> SwipePlan(nearX, cy, farX, cy)
            }
        }

        /** The screen minus 10% margins, so swipes avoid the system's edge gestures. */
        fun inset(screen: Rect): Rect {
            val dx = screen.width / 10
            val dy = screen.height / 10
            return Rect(screen.x + dx, screen.y + dy, screen.width - 2 * dx, screen.height - 2 * dy)
        }
    }
}
