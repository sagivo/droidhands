package dev.phonebridge.android.tools.email

import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowSnapshot

/** What the mail app shows after Send was tapped. */
sealed interface AfterSend {
    /** The compose screen is still there (sending may still be in progress). */
    data object Composing : AfterSend

    /** The compose screen closed. */
    data object Closed : AfterSend

    /** A new "Sending…" / "Sent" message appeared. */
    data class Signal(val text: String) : AfterSend

    /** "Send without a subject?" with its confirming button. */
    data class SubjectPrompt(val confirm: UiNode, val text: String) : AfterSend

    /** Any other dialog of the app (invalid address, no account...). */
    data class Problem(val text: String) : AfterSend
}

/** Classifies the screen after the Send tap (pure). */
object SendSignals {
    private val SENT_PREFIXES = listOf(
        "sending", "sent", "message sent", "email sent", "mail sent", "שולח", "נשלח", "ההודעה נשלחה", "enviando", "enviado",
        "wird gesendet", "gesendet", "envoi", "envoyé", "invio", "inviato", "отправка", "отправлено",
    ).map(EmailCompose::phraseKey)
    private val SUBJECT_PROMPT = listOf(
        "without a subject", "without subject", "no subject", "empty subject", "subject is empty", "ללא נושא", "sin asunto",
        "sem assunto", "ohne betreff", "sans objet", "senza oggetto", "без темы",
    ).map(EmailCompose::phraseKey)
    private val POSITIVE = listOf("send", "send anyway", "ok", "yes", "continue", "שליחה", "שלח", "אישור", "כן", "enviar", "senden", "envoyer", "invia")
        .map(EmailCompose::phraseKey).toSet()
    private val DIALOG_IDS = setOf("android:id/message", "android:id/alerttitle", "android:id/button1")
    private const val TITLE_ID = "android:id/alerttitle"
    private const val MESSAGE_ID = "android:id/message"
    private const val POSITIVE_ID = "android:id/button1"

    /** Texts of [pkg]'s windows, as keys; taken before the tap so old "Sent" labels don't count as signals. */
    fun baseline(pkg: String, windows: List<WindowSnapshot>): Set<String> = texts(pkg, windows).toSet()

    fun classify(pkg: String, windows: List<WindowSnapshot>, baseline: Set<String>): AfterSend {
        val roots = windows.filter { !it.hidden && it.packageName == pkg }.mapNotNull { it.root }
        dialog(roots)?.let { return it }
        texts(pkg, windows).firstOrNull { t -> t !in baseline && SENT_PREFIXES.any { t == it || t.startsWith("$it ") || t.startsWith("$it…") } }
            ?.let { return AfterSend.Signal(it) }
        return if (EmailCompose.find(pkg, windows) != null) AfterSend.Composing else AfterSend.Closed
    }

    /** An AlertDialog-style window of the app (framework dialog ids). */
    private fun dialog(roots: List<UiNode>): AfterSend? {
        val nodes = NodeQueries.visibleNodes(roots).toList()
        if (nodes.none { it.viewId?.lowercase() in DIALOG_IDS }) return null
        val text = nodes.filter { it.viewId?.lowercase() == TITLE_ID || it.viewId?.lowercase() == MESSAGE_ID }
            .mapNotNull { TextNorm.clean(it.text).trim().ifEmpty { null } }
            .joinToString(" ")
            .ifEmpty { NodeQueries.topTexts(roots, limit = 4).joinToString(" ") }
        val key = EmailCompose.phraseKey(text)
        if (SUBJECT_PROMPT.any { key.contains(it) }) {
            val confirm = nodes.firstOrNull { it.viewId?.lowercase() == POSITIVE_ID && NodeQueries.clickTarget(it) != null }
                ?: nodes.firstOrNull { !it.editable && EmailCompose.phraseKey(it.text) in POSITIVE && NodeQueries.clickTarget(it) != null }
            if (confirm != null) return AfterSend.SubjectPrompt(confirm, text)
        }
        return AfterSend.Problem(text)
    }

    private fun texts(pkg: String, windows: List<WindowSnapshot>): List<String> {
        val roots = windows.filter { !it.hidden && it.packageName == pkg }.mapNotNull { it.root }
        return NodeQueries.visibleNodes(roots).flatMap { sequenceOf(it.text, it.description) }.filterNotNull()
            .map(EmailCompose::phraseKey).filter { it.isNotEmpty() }.toList()
    }
}
