package dev.phonebridge.core.transport

import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.mcp.TransportResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

sealed interface RelayStatus {
    data object Stopped : RelayStatus
    data object Connecting : RelayStatus
    data class Connected(val mcpUrl: String) : RelayStatus

    /** [fatal] = will not retry (bad access key / unsupported protocol) until settings change. */
    data class Error(val message: String, val retryInMs: Long?, val fatal: Boolean = false) : RelayStatus
}

data class DeviceHello(
    val platform: String,
    val appVersion: String,
    val deviceName: String,
    val accessKey: String? = null,
)

/**
 * Outbound WebSocket to the relay (docs/PROTOCOL.md §3). [relayBaseUrl] is http(s)://host[:port][/prefix];
 * the client connects to ws(s)://…/v1/device. Sends hello, handles tunneled requests concurrently via
 * [handler] (on [scope]), answers pings, reconnects with exponential backoff + jitter (1 s → 60 s),
 * stops retrying on close codes 4001/4002.
 *
 * STUB — implemented by the core agent.
 */
class RelayClient(
    private val relayBaseUrl: String,
    private val identity: DeviceIdentity,
    private val hello: DeviceHello,
    private val handler: suspend (TransportRequest) -> TransportResponse,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    val status: StateFlow<RelayStatus> get() = TODO()

    fun start(): Unit = TODO()

    fun stop(): Unit = TODO()
}
