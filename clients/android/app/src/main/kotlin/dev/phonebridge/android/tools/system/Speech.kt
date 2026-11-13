package dev.phonebridge.android.tools.system

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Speaks text aloud with the phone's TTS engine. Each call creates an engine, speaks one
 * utterance, waits until it finishes, and always shuts the engine down. Calls are serialized so
 * two agents never talk over each other.
 */
internal object Speech {
    private const val INIT_TIMEOUT_MS = 10_000L
    private const val NO_ENGINE =
        "No working text-to-speech engine on the phone. Ask the user to install or enable one " +
            "(Android Settings > Accessibility > Text-to-speech output)."
    private val mutex = Mutex()

    /** For unavailableReason: null if a TTS engine is installed (cheap PackageManager query). */
    fun unavailableReason(context: Context): String? = runCatching {
        if (context.packageManager.hasServiceFor(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE))) null else NO_ENGINE
    }.getOrNull()

    /** Returns once the utterance has been spoken completely; throws [ToolException] otherwise. */
    suspend fun speak(context: Context, text: String, locale: Locale?) = mutex.withLock {
        var engine: TextToSpeech? = null
        try {
            // The engine binds a service; its OnInitListener is delivered on the main looper.
            val status = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val once = AtomicBoolean()
                        engine = TextToSpeech(context.applicationContext) { s -> cont.resumeOnce(once, s) }
                    }
                }
            }
            val tts = engine ?: throw ToolException("Text-to-speech could not be started on the phone.")
            when (status) {
                null -> throw ToolException("The phone's text-to-speech engine did not start within ${INIT_TIMEOUT_MS / 1000} s. Retry in a moment.")
                TextToSpeech.SUCCESS -> Unit
                else -> throw ToolException(NO_ENGINE)
            }
            withContext(Dispatchers.IO) {
                if (locale != null) applyLanguage(tts, locale)
                speakAndAwait(tts, text)
            }
        } finally {
            engine?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        }
    }

    private fun applyLanguage(tts: TextToSpeech, locale: Locale) {
        val tag = locale.toLanguageTag()
        when (tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> throw ToolException(
                "The phone's voice data for '$tag' is not installed. Ask the user to install it in Android Settings > " +
                    "Accessibility > Text-to-speech output > engine settings > Install voice data, or omit 'language'.",
            )
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                val available = runCatching { tts.availableLanguages?.map { it.toLanguageTag() }?.sorted()?.take(30) }.getOrNull()
                throw ToolException(
                    "The phone's text-to-speech engine does not support '$tag'." +
                        (available?.takeIf { it.isNotEmpty() }?.let { " Supported: ${it.joinToString(", ")}." } ?: ""),
                )
            }
        }
    }

    private suspend fun speakAndAwait(tts: TextToSpeech, text: String) {
        val utteranceId = "pb-" + UUID.randomUUID()
        val timeoutMs = SpeechLogic.timeoutMs(text.length)
        // null = finished; otherwise a failure description.
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val once = AtomicBoolean()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id == utteranceId) cont.resumeOnce(once, Outcome.Done)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(id: String?) {
                        if (id == utteranceId) cont.resumeOnce(once, Outcome.Failed("engine error"))
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) cont.resumeOnce(once, Outcome.Failed("engine error $errorCode"))
                    }

                    override fun onStop(id: String?, interrupted: Boolean) {
                        if (id == utteranceId) cont.resumeOnce(once, Outcome.Failed("speech was interrupted"))
                    }
                })
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
                    cont.resumeOnce(once, Outcome.Failed("the engine rejected the text"))
                }
            }
        }
        when (outcome) {
            null -> throw ToolException("Speech did not finish within ${timeoutMs / 1000} s and was stopped. Use shorter text.")
            is Outcome.Failed -> throw ToolException("Text-to-speech failed (${outcome.reason}). Retry, or check the phone's TTS settings.")
            Outcome.Done -> Unit
        }
    }

    private sealed interface Outcome {
        data object Done : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /** Callbacks may race on binder threads; only the first one resumes. */
    private fun <T> CancellableContinuation<T>.resumeOnce(once: AtomicBoolean, value: T) {
        if (once.compareAndSet(false, true) && isActive) resume(value)
    }
}
