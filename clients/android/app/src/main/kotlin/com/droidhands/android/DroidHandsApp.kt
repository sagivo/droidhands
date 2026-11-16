package com.droidhands.android

import android.app.Application
import com.droidhands.android.graph.BridgeGraph

class DroidHandsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeGraph.init(this)
    }
}
