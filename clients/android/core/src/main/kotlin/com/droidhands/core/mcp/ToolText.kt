package com.droidhands.core.mcp

import com.droidhands.core.tools.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Shared, exception-safe accessors and text helpers for tools (pure). */
internal object ToolText {
    /** Max length of audit argument previews and details. */
    const val PREVIEW_CHARS = 300

    /**
     * [Tool.unavailableReason], never throwing: a failing check is reported as a reason so the
     * agent learns something is wrong instead of the whole listing failing.
     */
    fun unavailableReason(tool: Tool): String? = try {
        tool.unavailableReason()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        "Could not check availability on the phone (${describe(e)})."
    }

    /**
     * [Tool.describeCall] for confirmation prompts; on exception or a blank summary, falls back to
     * the title plus the compact arguments.
     */
    fun confirmationSummary(tool: Tool, args: JsonObject): String {
        val described = try {
            tool.describeCall(args)
        } catch (e: Exception) {
            null
        }
        if (!described.isNullOrBlank()) return described
        val compact = JsonRpc.encode(args)
        return if (compact == "{}") tool.title else "${tool.title}: ${truncate(compact, 200)}"
    }

    /** Compact JSON truncated to [PREVIEW_CHARS]. */
    fun argsPreview(args: JsonElement): String = truncate(JsonRpc.encode(args), PREVIEW_CHARS)

    /** "<SimpleClass>: <message>" (message omitted when null). */
    fun describe(t: Throwable): String {
        val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        return if (t.message.isNullOrBlank()) name else "$name: ${t.message}"
    }

    /** At most [max] chars, ending in "…" when cut, never splitting a surrogate pair. */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + "…"
    }

    /** "60 seconds", "1 second", "0.5 seconds". */
    fun seconds(ms: Long): String {
        val text = if (ms % 1000 == 0L) (ms / 1000).toString() else "%.1f".format(java.util.Locale.ROOT, ms / 1000.0)
        return if (text == "1") "1 second" else "$text seconds"
    }

    /** Compact seconds for timeouts: "60 s", "0.2 s". */
    fun shortSeconds(ms: Long): String =
        if (ms % 1000 == 0L) "${ms / 1000} s" else "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
}
