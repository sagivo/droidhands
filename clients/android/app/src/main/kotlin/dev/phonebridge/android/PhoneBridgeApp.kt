package dev.phonebridge.android

import android.app.Application
import dev.phonebridge.android.graph.BridgeGraph

class PhoneBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeGraph.init(this)
    }
}
