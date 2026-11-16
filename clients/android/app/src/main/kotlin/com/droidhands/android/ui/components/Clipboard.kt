package com.droidhands.android.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** Clipboard and share helpers. Copies of secrets are flagged sensitive so the system hides previews. */
object Clipboard {
    /** Literal used before API 33, as recommended by the ClipDescription docs. */
    private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"

    /**
     * Puts [text] on the clipboard. Returns true when the app should show its own "Copied"
     * confirmation (Android 13+ shows a system one).
     */
    fun copy(context: Context, label: String, text: String, sensitive: Boolean): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE_COMPAT
            clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
        }
        manager.setPrimaryClip(clip)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    }

    /** Opens the system share sheet for plain [text]. Returns false if nothing can handle it. */
    fun share(context: Context, title: String, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return SystemIntents.launch(context, Intent.createChooser(send, title))
    }
}

/** Returns a `copy(label, text, sensitive)` function that confirms via the app snackbar where needed. */
@Composable
fun rememberCopier(): (label: String, text: String, sensitive: Boolean) -> Unit {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    return remember(context, snackbar, scope) {
        { label, text, sensitive ->
            if (Clipboard.copy(context, label, text, sensitive)) scope.launch { snackbar.showSnackbar("$label copied") }
        }
    }
}
