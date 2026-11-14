package dev.phonebridge.android.tools.screen

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.SelfProtection
import dev.phonebridge.android.access.UiAction
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.info

/**
 * Accessibility actions on captured nodes. All functions do blocking IPC: call on Dispatchers.IO. Every action is
 * refused (ToolException) while a PhoneBridge approval is pending or shown ([SelfProtection.requireNoPendingApproval]).
 */
object NodeActions {
    /** Re-fetches [node]'s state; null when the node no longer exists. */
    fun refresh(node: UiNode): UiNode? {
        val info = node.info ?: return null
        val alive = try {
            info.refresh()
        } catch (e: RuntimeException) {
            false
        }
        return if (alive) NodeCapture.captureShallow(info) else null
    }

    fun click(node: UiNode): Boolean = perform(node, AccessibilityNodeInfo.ACTION_CLICK)

    fun longClick(node: UiNode): Boolean = perform(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)

    fun focus(node: UiNode): Boolean = perform(node, AccessibilityNodeInfo.ACTION_FOCUS)

    /** Replaces the field's text (ACTION_SET_TEXT). */
    fun setText(node: UiNode, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses the keyboard's action key (send/search/done) for the focused field (API 30). */
    fun imeEnter(node: UiNode): Boolean = perform(node, AccessibilityAction.ACTION_IME_ENTER.id)

    fun scroll(node: UiNode, action: UiAction): Boolean {
        val id = when (action) {
            UiAction.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            UiAction.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            UiAction.SCROLL_UP -> AccessibilityAction.ACTION_SCROLL_UP.id
            UiAction.SCROLL_DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN.id
            UiAction.SCROLL_LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT.id
            UiAction.SCROLL_RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> return false
        }
        return perform(node, id)
    }

    /** Throws [dev.phonebridge.core.tools.ToolException] instead of acting while a PhoneBridge approval is pending or shown. */
    private fun perform(node: UiNode, action: Int, args: Bundle? = null): Boolean {
        // Checked right before the action, so no approval can appear in between unnoticed.
        SelfProtection.requireNoPendingApproval()
        val info = node.info ?: return false
        return try {
            if (args == null) info.performAction(action) else info.performAction(action, args)
        } catch (e: RuntimeException) {
            false
        }
    }
}
