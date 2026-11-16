package com.droidhands.android.tools.screen

import com.droidhands.android.access.NodeQueries
import com.droidhands.android.access.TextNorm
import com.droidhands.android.access.WindowKind

/**
 * What the screen looked like, reduced to what tells an agent whether its action did anything (pure): the app and
 * screen in front, the windows on top (dialogs, keyboard) and the visible texts.
 */
data class ScreenSignature(
    val packageName: String?,
    val activity: String?,
    /** Titles (or packages) of the non-primary windows, e.g. a dialog or a permission prompt. */
    val windows: List<String>,
    val keyboard: Boolean,
    val texts: List<String>,
    /** Text of the focused field, if any (shows what typing did). */
    val focusedText: String?,
) {
    companion object {
        private const val MAX_TEXTS = 80
        private const val MAX_TEXT_LENGTH = 80

        fun of(state: ScreenState): ScreenSignature {
            val windows = state.windows.filter { !it.primary && it.kind != WindowKind.INPUT_METHOD && it.kind != WindowKind.SYSTEM }
                .map { w -> w.title?.takeIf { it.isNotBlank() } ?: w.packageName ?: w.kind.name.lowercase() }
            val focused = NodeQueries.visibleNodes(state.roots).firstOrNull { it.focused && it.editable }
            return ScreenSignature(
                packageName = state.packageName,
                activity = state.activity,
                windows = windows,
                keyboard = state.windows.any { it.kind == WindowKind.INPUT_METHOD },
                texts = NodeQueries.topTexts(state.roots, MAX_TEXTS, MAX_TEXT_LENGTH),
                focusedText = focused?.let { TextNorm.truncate(TextNorm.clean(it.text).trim(), MAX_TEXT_LENGTH) },
            )
        }
    }
}

/** Describes the difference between two [ScreenSignature]s in one short line for the agent (pure). */
object ScreenChange {
    private const val SHOWN_TEXTS = 6

    fun describe(before: ScreenSignature, after: ScreenSignature): String {
        val parts = ArrayList<String>()
        if (before.packageName != after.packageName) {
            parts += "now in app ${after.packageName ?: "unknown"} (was ${before.packageName ?: "unknown"})"
        } else if (after.activity != null && before.activity != null && before.activity != after.activity) {
            parts += "new screen ${after.activity.substringAfterLast('.')}"
        }
        val opened = after.windows - before.windows.toSet()
        val closed = before.windows - after.windows.toSet()
        if (opened.isNotEmpty()) parts += "opened ${opened.joinToString { "\"$it\"" }}"
        if (closed.isNotEmpty()) parts += "closed ${closed.joinToString { "\"$it\"" }}"
        if (after.keyboard != before.keyboard) parts += if (after.keyboard) "keyboard opened" else "keyboard closed"
        if (after.focusedText != before.focusedText && after.focusedText != null) parts += "focused field shows \"${after.focusedText}\""
        val beforeTexts = before.texts.toSet()
        val afterTexts = after.texts.toSet()
        val added = after.texts.filter { it !in beforeTexts }
        val removed = before.texts.filter { it !in afterTexts }
        if (added.isNotEmpty()) parts += "new text: " + quoted(added)
        if (removed.isNotEmpty()) parts += "gone: " + quoted(removed)
        return if (parts.isEmpty()) {
            "The screen did not visibly change (the tap may have missed, the element may need something else first, or the " +
                "app is still loading: check with screen_observe)."
        } else {
            "Screen changed: ${parts.joinToString("; ")}."
        }
    }

    private fun quoted(texts: List<String>): String {
        val shown = texts.take(SHOWN_TEXTS).joinToString(", ") { "\"$it\"" }
        return if (texts.size > SHOWN_TEXTS) "$shown (+${texts.size - SHOWN_TEXTS} more)" else shown
    }
}
