package dev.phonebridge.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.graph.NotificationIds
import dev.phonebridge.core.audit.AuditEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service (type `specialUse`) running the relay client and/or local server.
 *
 * Commands ([ACTION_APPLY] etc.) are delivered with startService/startForegroundService; every
 * command first (re-)enters the foreground (required within seconds of startForegroundService),
 * then re-checks settings: the service stops itself when no transport should run, otherwise it asks
 * [TransportController] to reconcile. The transports themselves live in [BridgeGraph] so that
 * successive service instances never race on sockets or ports.
 *
 * The persistent notification shows relay/local status and the last agent action, updated at most
 * once per second, with Pause/Resume and Open actions.
 */
class BridgeService : Service() {
    private val graph: BridgeGraph by lazy { BridgeGraph.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkTracker = NetworkChangeTracker<Network>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Main-thread confined: notification updates are posted only while true. */
    private var inForeground = false

    @Volatile private var lastAuditEntry: AuditEntry? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (enterForeground()) graph.setServiceRunning(true)
        registerNetworkCallback()
        observeStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        graph.setServiceRunning(true)
        // A null intent is a sticky restart: treat it as APPLY.
        when (intent?.action ?: ACTION_APPLY) {
            ACTION_STOP -> {
                shutdown(startId)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> graph.setAccessEnabled(false)
            ACTION_RESUME -> graph.setAccessEnabled(true)
            ACTION_REFRESH -> postNotification(currentContent())
            else -> Unit
        }
        if (!TransportConfig.shouldRun(graph.settings.state.value)) {
            shutdown(startId)
            return START_NOT_STICKY
        }
        graph.transports.apply()
        return START_STICKY
    }

    override fun onDestroy() {
        inForeground = false
        unregisterNetworkCallback()
        graph.transports.stopAll()
        graph.setServiceRunning(false)
        scope.cancel()
        // The system removes the foreground notification; this also covers a stray update.
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    /** @return false if Android refused (e.g. background start on Android 12+); the caller stops. */
    private fun enterForeground(): Boolean {
        val notification = StatusNotification.build(this, currentContent())
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            Log.w(TAG, "Not allowed to enter the foreground", e)
            graph.reportServiceError("Android did not allow PhoneBridge to run in the background. Open the PhoneBridge app to start it.")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing foreground-service permission", e)
            graph.reportServiceError("PhoneBridge could not start its background service: ${e.message}")
            false
        }
    }

    private fun shutdown(startId: Int) {
        inForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only stops if no newer command arrived; that one reconciles again.
        stopSelf(startId)
    }

    private fun currentContent(): StatusNotificationContent {
        val settings = graph.settings.state.value
        return StatusText.notification(
            paused = !settings.accessEnabled,
            relay = graph.relayStatus.value.takeIf { settings.relayEnabled },
            local = graph.localServerStatus.value.takeIf { settings.localServerEnabled },
            lastEntry = lastAuditEntry,
            now = System.currentTimeMillis(),
        )
    }

    private fun observeStatus() {
        scope.launch {
            // First access loads the audit file: done here, off the main thread.
            val lastEntries: Flow<AuditEntry?> = try {
                graph.audit.entries.map(StatusText::lastNotableEntry).distinctUntilChanged()
            } catch (e: Exception) {
                Log.w(TAG, "Audit log unavailable for the status notification", e)
                flowOf(null)
            }
            val clock = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(RELATIVE_TIME_TICK_MS)
                }
            }
            combine(graph.settings.state, graph.relayStatus, graph.localServerStatus, lastEntries, clock) { settings, relay, local, last, now ->
                lastAuditEntry = last
                StatusText.notification(
                    paused = !settings.accessEnabled,
                    relay = relay.takeIf { settings.relayEnabled },
                    local = local.takeIf { settings.localServerEnabled },
                    lastEntry = last,
                    now = now,
                )
            }
                .distinctUntilChanged()
                .conflate()
                .collect { content ->
                    withContext(Dispatchers.Main) { postNotification(content) }
                    delay(MIN_UPDATE_INTERVAL_MS)
                }
        }
    }

    /** Main thread only, so it can't race with [shutdown] and resurrect a removed notification. */
    @SuppressLint("MissingPermission") // Checked below.
    private fun postNotification(content: StatusNotificationContent) {
        if (!inForeground) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // The service keeps running; Android lists it in the task manager instead.
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, StatusNotification.build(this, content))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not update the status notification", e)
        }
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (networkTracker.onAvailable(network)) graph.transports.restartRelay()
                graph.transports.refreshLocalUrls()
            }

            override fun onLost(network: Network) {
                networkTracker.onLost(network)
                graph.transports.refreshLocalUrls()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                graph.transports.refreshLocalUrls()
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // SecurityException or TooManyRequestsException: reconnects then rely on the client's backoff.
            Log.w(TAG, "Could not watch network changes", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered", e)
        }
    }

    companion object {
        private const val TAG = "PhoneBridge/Service"
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        private const val RELATIVE_TIME_TICK_MS = 30_000L

        const val NOTIFICATION_ID = NotificationIds.STATUS

        /** Reconcile transports with settings (start foreground if needed). */
        const val ACTION_APPLY = "dev.phonebridge.android.service.APPLY"

        /** Stop transports and the service. */
        const val ACTION_STOP = "dev.phonebridge.android.service.STOP"

        /** Turn the global kill switch on (agents get "paused" errors). */
        const val ACTION_PAUSE = "dev.phonebridge.android.service.PAUSE"

        /** Turn the global kill switch off. */
        const val ACTION_RESUME = "dev.phonebridge.android.service.RESUME"

        /** Re-post the status notification now. */
        const val ACTION_REFRESH = "dev.phonebridge.android.service.REFRESH"

        /** Explicit intent for [action]. */
        fun intent(context: Context, action: String): Intent =
            Intent(context, BridgeService::class.java).setAction(action)
    }
}
