package com.droidhands.android.ui

import android.content.Context
import android.util.Log
import com.droidhands.android.graph.BridgeGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Startup state of the UI's dependency graph. */
sealed interface WarmupState {
    data object Loading : WarmupState
    data object Ready : WarmupState
    data class Failed(val message: String) : WarmupState
}

/**
 * Touches every [BridgeGraph] member the UI reads on [Dispatchers.IO] before the first screen
 * composes, so lazy initialization (token file, audit log, settings, device secret) never runs on
 * the main thread. Process-wide and idempotent.
 */
internal object GraphWarmup {
    private const val TAG = "DroidHandsUi"
    private val mutex = Mutex()

    @Volatile
    var isReady: Boolean = false
        private set

    suspend fun ensure(context: Context): WarmupState {
        if (isReady) return WarmupState.Ready
        return mutex.withLock {
            if (isReady) return@withLock WarmupState.Ready
            try {
                withContext(Dispatchers.IO) {
                    val graph = BridgeGraph.get(context.applicationContext)
                    graph.settings.state
                    graph.identity.deviceId
                    graph.tokenManager.tokens
                    graph.audit.entries
                    graph.confirmations.pendingRequests
                    graph.relayStatus
                    graph.localServerStatus
                    GraphAdapters.usage(graph)
                    GraphAdapters.pendingPairings(graph)
                }
                isReady = true
                WarmupState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            } catch (e: NotImplementedError) {
                // A component still under construction: show it instead of crashing the app.
                failed(e)
            }
        }
    }

    private fun failed(e: Throwable): WarmupState {
        // Class name only: messages from storage layers could include file contents.
        Log.e(TAG, "Bridge startup failed: ${e.javaClass.name}")
        return WarmupState.Failed(e.message ?: e.javaClass.simpleName)
    }
}
