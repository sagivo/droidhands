package com.droidhands.android.tools.screen

import com.droidhands.android.access.Bounds
import com.droidhands.android.access.UiAction

/** Where the agent wants to move in the content: `down` reveals content further down (like a mouse wheel). */
enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT;

    val vertical: Boolean get() = this == UP || this == DOWN

    companion object {
        fun parse(raw: String): ScrollDirection? = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/** How ui_scroll will move a container (pure decision). */
sealed interface ScrollPlan {
    /** Perform this accessibility action on the container. */
    data class Action(val action: UiAction) : ScrollPlan

    /** The container advertises scrolling on this axis, but not in this direction: already at the end. */
    data object AtEnd : ScrollPlan

    /** No usable action: drag a finger inside the container. */
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : ScrollPlan
}

object ScrollPlanner {
    const val SWIPE_MS = 350L

    private val HORIZONTAL_HINTS = listOf("ViewPager", "HorizontalScrollView", "TabLayout", "Carousel")

    /**
     * Prefers the directional action (ACTION_SCROLL_DOWN...), then SCROLL_FORWARD/BACKWARD when the
     * container's axis (guessed from its class) matches the direction, else a swipe within [bounds].
     */
    fun plan(actions: Set<UiAction>, className: String?, direction: ScrollDirection, bounds: Bounds): ScrollPlan {
        val directional = when (direction) {
            ScrollDirection.UP -> UiAction.SCROLL_UP
            ScrollDirection.DOWN -> UiAction.SCROLL_DOWN
            ScrollDirection.LEFT -> UiAction.SCROLL_LEFT
            ScrollDirection.RIGHT -> UiAction.SCROLL_RIGHT
        }
        if (directional in actions) return ScrollPlan.Action(directional)

        val sameAxis = if (direction.vertical) {
            setOf(UiAction.SCROLL_UP, UiAction.SCROLL_DOWN)
        } else {
            setOf(UiAction.SCROLL_LEFT, UiAction.SCROLL_RIGHT)
        }
        val horizontal = className != null && HORIZONTAL_HINTS.any { className.contains(it) }
        val axisMatches = direction.vertical != horizontal
        val generic = if (direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT) UiAction.SCROLL_FORWARD else UiAction.SCROLL_BACKWARD
        if (axisMatches && generic in actions) return ScrollPlan.Action(generic)

        val axisAdvertised = actions.any { it in sameAxis } ||
            (axisMatches && (UiAction.SCROLL_FORWARD in actions || UiAction.SCROLL_BACKWARD in actions))
        if (axisAdvertised) return ScrollPlan.AtEnd
        return swipe(direction, bounds)
    }

    /** A finger drag across the middle half of [bounds] that moves content in [direction]. */
    fun swipe(direction: ScrollDirection, bounds: Bounds): ScrollPlan.Swipe {
        val cx = bounds.centerX
        val cy = bounds.centerY
        val near = { start: Int, size: Int -> start + size / 4 }
        val far = { start: Int, size: Int -> start + size * 3 / 4 }
        return when (direction) {
            // To reveal content below, the finger moves up.
            ScrollDirection.DOWN -> ScrollPlan.Swipe(cx, far(bounds.top, bounds.height), cx, near(bounds.top, bounds.height))
            ScrollDirection.UP -> ScrollPlan.Swipe(cx, near(bounds.top, bounds.height), cx, far(bounds.top, bounds.height))
            ScrollDirection.RIGHT -> ScrollPlan.Swipe(far(bounds.left, bounds.width), cy, near(bounds.left, bounds.width), cy)
            ScrollDirection.LEFT -> ScrollPlan.Swipe(near(bounds.left, bounds.width), cy, far(bounds.left, bounds.width), cy)
        }
    }
}
