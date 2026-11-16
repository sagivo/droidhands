package com.droidhands.ios.runner

import com.droidhands.core.screen.ScreenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The runner's last known state, refreshed in the background, so checks that must not block (tool availability,
 * `bridge_capabilities` status, [com.droidhands.core.plugins.AppActionsPlatform.unavailableReason]) can say at once
 * whether the iPhone is reachable and why not. Thread-safe.
 */
class RunnerMonitor(
    private val device: IosDevice,
    private val intervalMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The last answer: a status, or why there was none. */
    sealed interface State {
        data object Unknown : State

        data class Up(val status: RunnerStatus, val at: Long) : State

        data class Down(val reason: String, val at: Long) : State
    }

    @Volatile
    var state: State = State.Unknown
        private set

    /** Null if the runner answered last time (or hasn't been asked yet), else the agent-facing reason. */
    val unavailableReason: String? get() = (state as? State.Down)?.reason

    val status: RunnerStatus? get() = (state as? State.Up)?.status

    /** Asks the runner now and records the answer. */
    suspend fun refresh(): State {
        val next = try {
            State.Up(device.status(), clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScreenException) {
            State.Down(e.message ?: RunnerClient.UNREACHABLE, clock())
        } catch (e: Exception) {
            State.Down(RunnerClient.UNREACHABLE, clock())
        }
        state = next
        return next
    }

    /** Refreshes every [intervalMs] on [scope] until it is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            refresh()
            delay(intervalMs)
        }
    }
}
