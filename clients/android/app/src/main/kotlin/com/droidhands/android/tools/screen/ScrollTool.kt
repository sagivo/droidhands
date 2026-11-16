package com.droidhands.android.tools.screen

import android.content.Context
import com.droidhands.android.access.BridgeAccessibilityService
import com.droidhands.android.access.Bounds
import com.droidhands.android.access.NodeQueries
import com.droidhands.android.access.TreeSerializer
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optBool
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.reqString
import com.droidhands.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ui_scroll: scrolls a container (the largest one by default). */
internal fun uiScrollTool(context: Context): Tool = tool(
    name = "ui_scroll",
    title = "Scroll",
    description = "Scrolls a list or page in any app. direction is where you want to move in the content: 'down' " +
        "shows what is further down (like a mouse wheel), 'right' shows what is further right. Target: ref of a " +
        "[scrollable] element (or any element inside one), default the largest scrollable on screen. Says so " +
        "when the end is reached. Refs of the scrolled content may change: call screen_observe afterwards (or pass " +
        "observe=true). If the phone is " +
        "locked, the user is asked to unlock it (up to 60 s). Refused in apps the user blocked for this agent. " +
        UiAutomation.APPROVAL_PAUSE,
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("direction", "Where to move in the content", required = true, enum = ScrollDirection.entries.map { it.name.lowercase() })
        string("ref", "Ref of the scrollable element (default: the largest scrollable)")
        boolean("observe", UiAutomation.OBSERVE_ARG)
    },
    timeoutMs = 30_000 + UiAutomation.UNLOCK_WAIT_MS,
    unavailable = { UiAutomation.actionUnavailableReason(context) },
    describe = { args -> "Scroll ${args.optString("direction") ?: ""} on screen" },
) { args, ctx ->
    val direction = ScrollDirection.parse(args.reqString("direction"))
        ?: throw ToolException("direction must be one of up, down, left, right.")
    val service = UiAutomation.service()
    val access = ScreenAccess.of(context, ctx)
    UiAutomation.awaitUsable(context, ctx, "scroll on the screen")
    val message = UiAutomation.exclusive(access) {
        UiAutomation.requireUnlocked(context)
        UiAutomation.requireOperable(service, access)
        val message = scroll(context, service, access, direction, args.optString("ref")?.trim())
        UiAutomation.report(service, access, message)
    }
    UiAutomation.result(context, ctx, args.optBool("observe") ?: false, message)
}

private suspend fun scroll(
    context: Context,
    service: BridgeAccessibilityService,
    access: ScreenAccess,
    direction: ScrollDirection,
    ref: String?,
): String {
    val container: ScreenSession.Live? = withContext(Dispatchers.IO) {
        if (ref != null) {
            val live = ScreenSession.resolve(ref, access)
            if (NodeQueries.isScrollContainer(live.fresh)) {
                live
            } else {
                // A ref inside a list scrolls that list; a ref with no scrollable ancestor is swiped directly.
                live.node.ancestors().firstOrNull(NodeQueries::isScrollContainer)
                    ?.let { anc -> ScreenSession.refresh(anc, access)?.let { ScreenSession.Live(ScreenSession.refs(access).register(anc), anc, it) } }
                    ?: live
            }
        } else {
            val state = ScreenCapture.capture(context, service, access)
            state.blockedForeground?.let { access.refuse(it) }
            val node = NodeQueries.largestScrollable(listOfNotNull(state.primaryRoot)) ?: NodeQueries.largestScrollable(state.roots)
            node?.let { n -> ScreenSession.refresh(n, access)?.let { ScreenSession.Live(ScreenSession.refs(access).register(n), n, it) } }
        }
    }
    return scrollWith(context, service, access, direction, container).message
}

/** What [scrollWith] did: [message] for the agent; [atEnd] when the container can't scroll further that way. */
internal class ScrollOutcome(val message: String, val atEnd: Boolean = false)

/**
 * Scrolls [container] (null: swipes across the screen) in [direction]: its scroll action if it has one, else a
 * swipe inside it. Shared by ui_scroll and app-action screen recipes; every action is checked ([ScreenGuard],
 * [UiAutomation.beforeAction]) right before it happens. With [confineTo] (a screen recipe's app), swipes must start
 * on that app's own window ([ScreenGuard.checkConfined]). The caller holds the UI mutex.
 */
internal suspend fun scrollWith(
    context: Context,
    service: BridgeAccessibilityService,
    access: ScreenAccess,
    direction: ScrollDirection,
    container: ScreenSession.Live?,
    confineTo: String? = null,
): ScrollOutcome {
    val dir = direction.name.lowercase()
    val screen = service.screenBounds()
    if (container == null) {
        val plan = ScrollPlanner.swipe(direction, inset(screen))
        swipe(context, service, access, plan, confineTo)
        return ScrollOutcome("No scrollable element found; swiped $dir across the screen.")
    }
    val label = "${container.ref} ${TreeSerializer.label(container.fresh)}"
    withContext(Dispatchers.IO) { ScreenGuard.checkNode(context, container.fresh, access) }
    val area = container.fresh.bounds.intersect(screen)
    return when (val plan = ScrollPlanner.plan(container.fresh.actions, container.fresh.className, direction, area)) {
        is ScrollPlan.Action -> {
            UiAutomation.beforeAction(access)
            if (withContext(Dispatchers.IO) { NodeActions.scroll(container.fresh, plan.action) }) {
                ScrollOutcome("Scrolled $dir in $label.")
            } else {
                ScrollOutcome(swipeWithin(context, service, access, direction, area, label, confineTo))
            }
        }
        ScrollPlan.AtEnd -> ScrollOutcome("Can't scroll $dir in $label: already at the end in that direction.", atEnd = true)
        is ScrollPlan.Swipe -> ScrollOutcome(swipeWithin(context, service, access, direction, area, label, confineTo))
    }
}

private suspend fun swipeWithin(
    context: Context,
    service: BridgeAccessibilityService,
    access: ScreenAccess,
    direction: ScrollDirection,
    area: Bounds,
    label: String,
    confineTo: String?,
): String {
    if (area.isEmpty) throw ToolException("Element $label is not visible on screen, so it can't be scrolled. Call screen_read again.")
    swipe(context, service, access, ScrollPlanner.swipe(direction, area), confineTo)
    return "Swiped ${direction.name.lowercase()} inside $label."
}

private suspend fun swipe(
    context: Context,
    service: BridgeAccessibilityService,
    access: ScreenAccess,
    plan: ScrollPlan.Swipe,
    confineTo: String? = null,
) {
    withContext(Dispatchers.IO) {
        ScreenGuard.checkPoint(context, service, plan.x1, plan.y1, access)
        confineTo?.let { ScreenGuard.checkConfined(service, plan.x1, plan.y1, it) }
    }
    UiAutomation.beforeAction(access)
    if (!service.swipe(plan.x1, plan.y1, plan.x2, plan.y2, ScrollPlanner.SWIPE_MS)) {
        throw ToolException("Android cancelled the swipe gesture. Retry.")
    }
}

/** The screen minus 10% margins, so swipes avoid the system's edge gestures (back/home). */
private fun inset(screen: Bounds): Bounds {
    val dx = screen.width / 10
    val dy = screen.height / 10
    return Bounds(screen.left + dx, screen.top + dy, screen.right - dx, screen.bottom - dy)
}
