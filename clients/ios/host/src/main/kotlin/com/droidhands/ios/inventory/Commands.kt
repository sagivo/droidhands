package com.droidhands.ios.inventory

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a command printed and how it ended. [exitCode] -1: it timed out or couldn't start ([stderr] says which). */
class CommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val ok: Boolean get() = exitCode == 0

    val text: String get() = stdout.toString(Charsets.UTF_8)

    /** The first line of [stderr] (or stdout), for error messages. */
    fun firstErrorLine(): String =
        (stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: text.lineSequence().firstOrNull { it.isNotBlank() } ?: "exit code $exitCode")
            .trim().take(300)
}

/**
 * Runs Apple's (and other) command-line tools. Arguments are passed as argv, never through a shell. Blocking; callers
 * run it off the coroutine threads that serve requests. Tests substitute fixtures.
 */
fun interface CommandRunner {
    fun run(command: List<String>, stdin: ByteArray?, timeoutMs: Long): CommandResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

fun CommandRunner.run(vararg command: String, stdin: ByteArray? = null, timeoutMs: Long = CommandRunner.DEFAULT_TIMEOUT_MS): CommandResult =
    run(command.toList(), stdin, timeoutMs)

/** [CommandRunner] with [ProcessBuilder]. Output is read concurrently so a full pipe never blocks the command. */
object ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, stdin: ByteArray?, timeoutMs: Long): CommandResult {
        val process = try {
            ProcessBuilder(command).redirectInput(if (stdin == null) ProcessBuilder.Redirect.from(File("/dev/null")) else ProcessBuilder.Redirect.PIPE).start()
        } catch (e: IOException) {
            return CommandResult(-1, ByteArray(0), "${command.first()} could not be started (${e.message})")
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val readers = listOf(
            thread(isDaemon = true, name = "cmd-out") { process.inputStream.use { it.copyTo(out) } },
            thread(isDaemon = true, name = "cmd-err") { process.errorStream.use { it.copyTo(err) } },
        )
        if (stdin != null) {
            try {
                process.outputStream.use { it.write(stdin) }
            } catch (e: IOException) {
                // The command exited without reading everything; its exit code says what happened.
            }
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return CommandResult(-1, ByteArray(0), "${command.first()} did not finish within ${timeoutMs / 1000} s")
        }
        readers.forEach { it.join(2_000) }
        return CommandResult(process.exitValue(), synchronized(out) { out.toByteArray() }, synchronized(err) { err.toString(Charsets.UTF_8) })
    }

    /** True if [name] is an executable on PATH (no shell, no `which`). */
    fun onPath(name: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { dir -> dir.isNotEmpty() && File(dir, name).canExecute() }
}
