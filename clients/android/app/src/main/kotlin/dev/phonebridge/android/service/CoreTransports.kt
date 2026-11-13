package dev.phonebridge.android.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.phonebridge.android.BuildConfig
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.mcp.TransportResponse
import dev.phonebridge.core.transport.DeviceHello
import dev.phonebridge.core.transport.LocalHttpServer
import dev.phonebridge.core.transport.RelayClient
import dev.phonebridge.core.transport.RelayStatus
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

    fun relayFactory(
        context: Context,
        identity: () -> DeviceIdentity,
        handler: suspend (TransportRequest) -> TransportResponse,
    ): RelayTransportFactory = RelayTransportFactory { config, scope ->
        val hello = DeviceHello(
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = deviceName(context),
            accessKey = config.accessKey,
        )
        val client = RelayClient(config.url, identity(), hello, handler, scope, relayHttpClient)
        object : RelayTransport {
            override val status: StateFlow<RelayStatus> get() = client.status
            override fun start() = client.start()
            override fun stop() = client.stop()
        }
    }

    fun localFactory(handler: suspend (TransportRequest) -> TransportResponse): LocalTransportFactory =
        LocalTransportFactory { config ->
            val server = LocalHttpServer(port = config.port, bindAddress = config.bindAddress, handler = handler)
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
