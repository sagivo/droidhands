package dev.phonebridge.android.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Foreground service running the relay client and/or local server. STUB — services agent. */
class BridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
