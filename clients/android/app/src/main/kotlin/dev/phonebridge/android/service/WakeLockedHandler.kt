package dev.phonebridge.android.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.mcp.TransportResponse
import kotlinx.coroutines.CancellationException

/**
 * Wraps the MCP handler used by both transports:
 * * holds a per-request PARTIAL_WAKE_LOCK (not reference counted, 3-minute safety timeout) so the
 *   CPU doesn't sleep in the middle of a tool call or while waiting for the user's approval;
 * * converts unexpected exceptions into a JSON-RPC internal error instead of killing the transport.
 */
class WakeLockedHandler(
    context: Context,
    private val delegate: suspend (TransportRequest) -> TransportResponse,
) {
    private val power: PowerManager? = context.applicationContext.getSystemService(PowerManager::class.java)

    /** Pass as the transports' handler, e.g. `handler = wrapped::handle`. */
    suspend fun handle(request: TransportRequest): TransportResponse {
        val lock = try {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not acquire wake lock", e)
            null
        }
        try {
            return delegate(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MCP handler failed (${request.transport})", e)
            return internalError()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not release wake lock", e)
            }
        }
    }

    companion object {
        private const val TAG = "PhoneBridge/Handler"
        private const val WAKE_LOCK_TAG = "PhoneBridge:mcp-request"

        /** Covers 60 s of confirmation + 60 s of tool time with margin; also the relay's 150 s limit. */
        const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 1000L

        /** HTTP 500 with a JSON-RPC internal error (`id: null`, nothing about the failure leaks). */
        fun internalError(): TransportResponse = TransportResponse(
            status = 500,
            body = """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"Internal error"}}""",
            headers = mapOf("content-type" to "application/json"),
        )
    }
}
