package com.droidhands.android.account

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.droidhands.android.graph.BridgeGraph
import com.droidhands.android.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Receives `droidhands://signed-in?…`, where the API server sends the browser after Google sign-in. It hands
 * the link to [BridgeGraph.completeSignIn] and brings the app back to the front, which also closes the Custom Tab
 * (it sits above [MainActivity] in the app's task). Has no UI. Links nobody asked for are ignored
 * ([AccountManager.completeSignIn] needs the verifier of a sign-in started on this phone).
 */
class SignInRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        val fresh = savedInstanceState == null && intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh && link != null && SignInLinks.isSignInLink(link)) {
            val graph = BridgeGraph.get(this)
            graph.appScope.launch { graph.completeSignIn(link) }
        }
        // NEW_TASK: without a Custom Tab, the browser launched this in its own task; go back to the app's.
        startActivity(MainActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
