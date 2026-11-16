package com.droidhands.ios.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Native macOS dialogs through `osascript`, for pairing and approvals on the Mac (docs/IOS.md). The AppleScript is a
 * constant: every shown value (agent names, hosts, summaries, which come from outside) is passed as an argument and
 * read with `item n of argv`, never put into the script, so no text can change what the script does. Dialogs have no
 * default button, so a stray Return never approves anything.
 */
object MacDialogs {
    enum class Answer { ALLOW, DENY, TIMED_OUT, FAILED }

    /** The script; argv is message, title, deny label, allow label, seconds. */
    val SCRIPT: List<String> = listOf(
        "on run argv",
        "set theReply to display dialog (item 1 of argv) with title (item 2 of argv) buttons {(item 3 of argv), (item 4 of argv)} " +
            "giving up after ((item 5 of argv) as integer) with icon caution",
        "if gave up of theReply then return \"timeout\"",
        "return button returned of theReply",
        "end run",
    )

    private const val MAX_MESSAGE = 1_500

    /** The full `osascript` command line for one dialog (pure, for tests). */
    fun command(message: String, title: String, deny: String, allow: String, timeoutSeconds: Long): List<String> = buildList {
        add("osascript")
        for (line in SCRIPT) {
            add("-e")
            add(line)
        }
        // Each starts with a letter, so osascript never reads one as an option.
        add(safe(message, MAX_MESSAGE))
        add(safe(title, 120))
        add(safe(deny, 30))
        add(safe(allow, 30))
        add(timeoutSeconds.coerceIn(1, 3_600).toString())
    }

    /**
     * Shows the dialog and suspends until the user answers or [timeoutSeconds] pass. Cancelling the coroutine closes
     * the dialog. [FAILED]: osascript couldn't show it (no logged-in GUI session, say).
     */
    suspend fun ask(message: String, title: String, deny: String, allow: String, timeoutSeconds: Long): Answer {
        val process = ProcessBuilder(command(message, title, deny, allow, timeoutSeconds))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            // waitFor is interruptible (a pipe read is not); the answer is a few bytes, so the pipe never fills.
            runInterruptible(Dispatchers.IO) { process.waitFor() }
            val out = process.inputStream.bufferedReader().readText().trim()
            return when {
                process.exitValue() != 0 -> Answer.FAILED
                out == "timeout" -> Answer.TIMED_OUT
                out == safe(allow, 30) -> Answer.ALLOW
                else -> Answer.DENY
            }
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    /** Printable text: control characters (but newlines) dropped, at most [max] chars, starting with no dash. */
    fun safe(text: String, max: Int): String {
        val cleaned = text.filter { it == '\n' || !Character.isISOControl(it) }.trim()
        val cut = if (cleaned.length <= max) cleaned else cleaned.take(max - 1) + "…"
        return cut.trimStart('-').ifEmpty { "?" }
    }

    /** One line of outside text (an agent's name or host) for a dialog: no newlines, at most [max] chars. */
    fun oneLine(text: String, max: Int = 80): String = safe(text.replace(Regex("\\s+"), " "), max)
}
