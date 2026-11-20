package com.droidhands.android.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.droidhands.android.BuildConfig
import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.mcp.TransportRequest
import com.droidhands.core.mcp.TransportResponse
import com.droidhands.core.oauth.LocalOAuth
import com.droidhands.core.transport.DeviceHello
import com.droidhands.core.transport.LocalHttpServer
import com.droidhands.core.transport.PairingHandler
import com.droidhands.core.transport.RelayAccountToken
import com.droidhands.core.transport.RelayClient
import com.droidhands.core.transport.RelayStatus
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Production [RelayTransportFactory]/[LocalTransportFactory] backed by the core transports. */
internal object CoreTransports {
    /** One shared client (connection pool, dispatcher); WS pings every 20 s as PROTOCOL.md §3.4 recommends. */
    private val relayHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * @param pairingHandler answers one-tap pairing requests arriving over the relay
     *   (docs/PROTOCOL.md §5); resolved when each client is created, null disables pairing.
     * @param accountToken the signed-in account's token for relays that need one ([RelayConfig.needsAccount]).
     */
    fun relayFactory(
        context: Context,
        identity: () -> DeviceIdentity,
        handler: suspend (TransportRequest) -> TransportResponse,
        pairingHandler: () -> PairingHandler? = { null },
        accountToken: suspend () -> RelayAccountToken = { RelayAccountToken.SignedOut },
    ): RelayTransportFactory = RelayTransportFactory { config, scope ->
        val hello = DeviceHello(
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = deviceName(context),
            accessKey = config.accessKey,
        )
        val client = RelayClient(
            relayBaseUrl = config.url,
            identity = identity(),
            hello = hello,
            handler = handler,
            scope = scope,
            httpClient = relayHttpClient,
            pairingHandler = pairingHandler(),
            accountToken = if (config.needsAccount) accountToken else null,
        )
        object : RelayTransport {
            override val status: StateFlow<RelayStatus> get() = client.status
            override fun start() = client.start()
            override fun stop() = client.stop()
        }
    }

    /**
     * @param oauth one-tap pairing on the local server (docs/PROTOCOL.md §4.1); called once per
     *   server (its pairings end with it), null serves only `/mcp` (manual tokens).
     */
    fun localFactory(
        handler: suspend (TransportRequest) -> TransportResponse,
        oauth: () -> LocalOAuth? = { null },
    ): LocalTransportFactory =
        LocalTransportFactory { config ->
            val bindAddress = requireNotNull(config.bindAddress) { "No address to listen on" }
            val server = LocalHttpServer(port = config.port, bindAddress = bindAddress, handler = handler, oauth = oauth())
            object : LocalTransport {
                override fun start() = server.start()
                override fun stop() = server.stop()
                override val boundPort: Int get() = server.boundPort
            }
        }

    /** The user-visible device name ("Sagiv's Pixel"), falling back to the model ("Pixel 8"). */
    fun deviceName(context: Context): String {
        val name = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: RuntimeException) {
            null
        }
        return name?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    }
}
