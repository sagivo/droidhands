package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import java.util.Locale

/** Argument validation and time budgeting for `speak` (pure). */
object SpeechLogic {
    /**
     * Longest text per call. At typical TTS speed (~14 chars/s) this is ~45 s of speech, which keeps
     * the call inside its 60 s speaking budget; longer text must be split across calls.
     */
    const val MAX_CHARS = 600

    const val MAX_TIMEOUT_MS = 60_000L
    private const val MIN_TIMEOUT_MS = 10_000L

    fun requireText(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw ToolException("Argument 'text' must not be empty.")
        if (text.length > MAX_CHARS) {
            throw ToolException(
                "Text is too long to speak in one call (${text.length} characters, max $MAX_CHARS). " +
                    "Split it into several speak calls.",
            )
        }
        return text
    }

    /** How long to wait for the utterance to finish: generous for the text length, capped at 60 s. */
    fun timeoutMs(textLength: Int): Long = (5_000L + textLength * 90L).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /** Parses a BCP-47 tag ("en-US", "he", "pt_BR" tolerated); null for absent/blank. */
    fun parseLanguage(raw: String?): Locale? {
        val tag = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isNullOrEmpty()) {
            throw ToolException("Argument 'language' must be a BCP-47 language tag such as en-US, he or fr-FR; got '$raw'.")
        }
        return locale
    }
}
