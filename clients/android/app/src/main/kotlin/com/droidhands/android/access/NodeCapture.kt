package com.droidhands.android.access

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/** The live platform node behind a captured [UiNode], if any. */
val UiNode.info: AccessibilityNodeInfo? get() = handle as? AccessibilityNodeInfo

/**
 * Copies `AccessibilityNodeInfo` trees into immutable [UiNode]s. Child fetches are blocking IPC to
 * the target app: always call from a background dispatcher (Dispatchers.IO).
 */
object NodeCapture {
    const val MAX_NODES = 4000
    const val MAX_DEPTH = 80
    const val MAX_MILLIS = 4000L

    /** Shared per-capture limits so one huge or hung app can't stall a tool. */
    class Budget(val maxNodes: Int = MAX_NODES, maxMillis: Long = MAX_MILLIS) {
        private val deadline = SystemClock.uptimeMillis() + maxMillis
        var count = 0
            internal set
        val exhausted: Boolean get() = count >= maxNodes || SystemClock.uptimeMillis() > deadline
    }

    /** Captures [root] and its descendants. */
    fun capture(root: AccessibilityNodeInfo, budget: Budget = Budget()): UiNode = captureNode(root, budget, 0)

    /** Captures only [info]'s own properties (no children); used after `refresh()`. */
    fun captureShallow(info: AccessibilityNodeInfo): UiNode = build(info, emptyList())

    private fun captureNode(info: AccessibilityNodeInfo, budget: Budget, depth: Int): UiNode {
        budget.count++
        val children = if (depth >= MAX_DEPTH) emptyList() else buildList {
            for (i in 0 until info.childCount) {
                if (budget.exhausted) break
                val child = try {
                    info.getChild(i)
                } catch (e: RuntimeException) {
                    null // node vanished or IPC failed: skip this child
                } ?: continue
                add(captureNode(child, budget, depth + 1))
            }
        }
        return build(info, children)
    }

    private fun build(info: AccessibilityNodeInfo, children: List<UiNode>): UiNode {
        val rect = Rect()
        info.getBoundsInScreen(rect)
        val showingHint = info.isShowingHintText
        return UiNode(
            text = if (showingHint) null else info.text?.toString(),
            description = info.contentDescription?.toString(),
            hint = info.hintText?.toString(),
            stateDescription = info.stateDescription?.toString(),
            viewId = info.viewIdResourceName,
            className = info.className?.toString(),
            packageName = info.packageName?.toString(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            visible = info.isVisibleToUser,
            clickable = info.isClickable,
            longClickable = info.isLongClickable,
            editable = info.isEditable,
            scrollable = info.isScrollable,
            checkable = info.isCheckable,
            checked = info.isChecked,
            selected = info.isSelected,
            focused = info.isFocused,
            enabled = info.isEnabled,
            password = info.isPassword,
            actions = mapActions(info.actionList),
            children = children,
            handle = info,
        )
    }

    private fun mapActions(list: List<AccessibilityAction>?): Set<UiAction> {
        if (list.isNullOrEmpty()) return emptySet()
        val out = HashSet<UiAction>()
        for (a in list) {
            val mapped = when (a.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> UiAction.CLICK
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> UiAction.LONG_CLICK
                AccessibilityNodeInfo.ACTION_FOCUS -> UiAction.FOCUS
                AccessibilityNodeInfo.ACTION_SET_TEXT -> UiAction.SET_TEXT
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> UiAction.SCROLL_FORWARD
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> UiAction.SCROLL_BACKWARD
                AccessibilityAction.ACTION_SCROLL_UP.id -> UiAction.SCROLL_UP
                AccessibilityAction.ACTION_SCROLL_DOWN.id -> UiAction.SCROLL_DOWN
                AccessibilityAction.ACTION_SCROLL_LEFT.id -> UiAction.SCROLL_LEFT
                AccessibilityAction.ACTION_SCROLL_RIGHT.id -> UiAction.SCROLL_RIGHT
                AccessibilityAction.ACTION_IME_ENTER.id -> UiAction.IME_ENTER
                else -> null
            }
            if (mapped != null) out += mapped
        }
        return out
    }
}
