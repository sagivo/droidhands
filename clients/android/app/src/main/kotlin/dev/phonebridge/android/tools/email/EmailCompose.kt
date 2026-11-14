package dev.phonebridge.android.tools.email

import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowSnapshot
import java.util.Locale

/** Roles of a compose screen's text fields. */
enum class ComposeField(val label: String) { TO("To"), CC("Cc"), BCC("Bcc"), SUBJECT("Subject"), BODY("body") }

/**
 * A mail app's compose screen found on captured windows: its text [fields] by role, and the Send button
 * ([send], null while not visible or disabled). [shown] is what each field displays: for recipient fields
 * everything in the field's row (chips and typed text, without its label and hint), else the field's text.
 */
class ComposeScreen(
    val fields: Map<ComposeField, UiNode>,
    val shown: Map<ComposeField, String>,
    val send: UiNode?,
    /** All visible texts of the app's windows (to find a body rendered outside an editable). */
    val allText: String,
)

/**
 * Heuristics for compose screens of any mail app (Gmail, Outlook, the E2E fixture...), on captured trees (pure).
 * Fields are recognized by view id words ("to", "subject", "body"/"compose"), then by the ids of a wrapping
 * container, then by hint text ("To", "Subject", "Compose email"), then by a label to the left in the same row.
 */
object EmailCompose {
    /** How many wrapping containers to look at for a field's id, and for its recipient chips. */
    private const val MAX_FIELD_ANCESTORS = 3

    private val TO_LABELS = phrases("to", "recipients", "recipient", "add recipients", "אל", "נמען", "נמענים", "para", "an", "à", "a", "destinatari", "кому", "إلى", "aan", "kime")
    private val CC_LABELS = phrases("cc", "עותק")
    private val BCC_LABELS = phrases("bcc", "עותק מוסתר")
    private val SUBJECT_LABELS = phrases("subject", "add subject", "נושא", "asunto", "assunto", "betreff", "objet", "oggetto", "тема", "الموضوع", "onderwerp", "konu")
    private val BODY_HINTS = phrases(
        "compose email", "compose", "body", "message", "write your message", "write email", "write an email",
        "כתיבת אימייל", "חיבור אימייל", "כתיבת הודעה", "הודעה", "redactar correo", "redactar correo electrónico", "redactar",
        "escrever email", "escrever e-mail", "e-mail verfassen", "nachricht", "rédiger un e-mail", "rédiger", "scrivi email",
        "написать письмо", "письмо", "إنشاء رسالة",
    )

    /** Content descriptions / texts of Send buttons in common languages (fallback when ids don't say "send"). */
    val SEND_WORDS = phrases("send", "שליחה", "שלח", "enviar", "senden", "envoyer", "invia", "отправить", "إرسال", "gönder", "kirim", "भेजें", "verzenden", "skicka", "wyślij")
    private val SEND_ID_EXCLUDED = setOf("later", "schedule", "scheduled", "options", "menu", "more")

    // ---- screen ------------------------------------------------------------------------------

    /** The compose screen of [pkg] among [windows], or null: needs a To or body field. */
    fun find(pkg: String, windows: List<WindowSnapshot>): ComposeScreen? {
        val own = windows.filter { !it.hidden && it.root != null && it.packageName == pkg }
        if (own.isEmpty()) return null
        val main = own.maxBy { it.bounds.area }
        val root = main.root!!
        val fields = classifyFields(root)
        if (ComposeField.TO !in fields && ComposeField.BODY !in fields) return null
        val shown = fields.mapValues { (role, node) ->
            if (role == ComposeField.TO || role == ComposeField.CC || role == ComposeField.BCC) rowText(node, fields.values) else fieldText(node)
        }
        val roots = own.mapNotNull { it.root }
        val allText = NodeQueries.visibleNodes(roots).flatMap { sequenceOf(it.text, it.description) }.filterNotNull()
            .joinToString("\n") { TextNorm.clean(it) }
        return ComposeScreen(fields, shown, findSend(roots), allText)
    }

    /** Assigns each role to at most one editable (first in reading order wins). */
    fun classifyFields(root: UiNode): Map<ComposeField, UiNode> {
        val visible = NodeQueries.visibleNodes(listOf(root)).toList()
        val editables = visible.filter { it.editable }
        val labels = visible.filter { !it.editable && !it.text.isNullOrBlank() && labelRole(it.text) != null }
        val out = LinkedHashMap<ComposeField, UiNode>()
        for (field in editables) {
            val role = roleById(field) ?: roleByHint(field) ?: roleByLabel(field, labels) ?: continue
            out.putIfAbsent(role, field)
        }
        return out
    }

    private fun roleById(field: UiNode): ComposeField? {
        roleOfId(field.shortId)?.let { return it }
        // A wrapping container that holds only this field may carry the meaningful id (e.g. to_content > input).
        for (ancestor in field.ancestors().take(MAX_FIELD_ANCESTORS)) {
            if (ancestor.walk().count { it.editable && it.visible } != 1) break
            roleOfId(ancestor.shortId)?.let { return it }
        }
        return null
    }

    /** Role from view id words, e.g. `to`, `subject_field`, `composeBody`, `peoplekit_autocomplete_input` (Gmail's To). */
    fun roleOfId(id: String?): ComposeField? {
        val words = idWords(id)
        return when {
            words.isEmpty() -> null
            "bcc" in words -> ComposeField.BCC
            "cc" in words -> ComposeField.CC
            "to" in words || "recipient" in words || "recipients" in words || "peoplekit" in words -> ComposeField.TO
            "subject" in words -> ComposeField.SUBJECT
            "body" in words || "compose" in words || "message" in words || "editor" in words -> ComposeField.BODY
            else -> null
        }
    }

    private fun roleByHint(field: UiNode): ComposeField? {
        val hint = phraseKey(field.hint).ifEmpty { return null }
        return labelRole(hint) ?: if (hint in BODY_HINTS) ComposeField.BODY else null
    }

    /** A "To" / "Subject" label to the left of [field], vertically centred on it. */
    private fun roleByLabel(field: UiNode, labels: List<UiNode>): ComposeField? {
        val b = field.bounds
        return labels.firstOrNull { l -> l.bounds.centerY in b.top until b.bottom && l.bounds.right <= b.left + LABEL_OVERLAP_PX }
            ?.let { labelRole(it.text) }
    }

    private const val LABEL_OVERLAP_PX = 24

    private fun labelRole(text: String?): ComposeField? {
        val key = phraseKey(text)
        return when (key) {
            in BCC_LABELS -> ComposeField.BCC
            in CC_LABELS -> ComposeField.CC
            in TO_LABELS -> ComposeField.TO
            in SUBJECT_LABELS -> ComposeField.SUBJECT
            else -> null
        }
    }

    /** Field text, empty while only the hint shows. */
    fun fieldText(node: UiNode): String = TextNorm.clean(node.text?.takeUnless { it == node.hint }).trim()

    /**
     * Everything a recipient field displays: its own text plus chips around it, i.e. texts in the largest
     * wrapping container (up to [MAX_FIELD_ANCESTORS] levels) that holds no other field. Labels, hints and the
     * descriptions of icon buttons ("Show Cc/Bcc") are left out.
     */
    fun rowText(field: UiNode, allFields: Collection<UiNode>): String {
        val others = allFields.filter { it !== field }.map { it.key }.toSet()
        var scope = field
        for (ancestor in field.ancestors().take(MAX_FIELD_ANCESTORS)) {
            if (ancestor.walk().any { it.key in others }) break
            scope = ancestor
        }
        val parts = NodeQueries.visibleNodes(listOf(scope)).flatMap { n ->
            when {
                n === field -> sequenceOf(fieldText(n))
                isIcon(n) -> sequenceOf(n.text)
                else -> sequenceOf(n.text, n.description)
            }
        }.filterNotNull().map { TextNorm.clean(it).trim() }.filter { it.isNotEmpty() && labelRole(it) == null }
        return parts.distinct().joinToString(" ")
    }

    private fun isIcon(n: UiNode): Boolean = n.simpleClassName.contains("Image")

    /**
     * The Send button: an enabled clickable whose view id has the word "send" (exactly `send` first; never "send
     * later"/"schedule"), else one described or labelled "Send" in a common language.
     */
    fun findSend(roots: List<UiNode>): UiNode? {
        val nodes = NodeQueries.visibleNodes(roots).filter { !it.editable }.toList()
        nodes.filter { n -> idWords(n.shortId).let { "send" in it && it.none(SEND_ID_EXCLUDED::contains) } }
            .sortedBy { if (it.shortId.equals("send", ignoreCase = true)) 0 else 1 }
            .firstOrNull { NodeQueries.clickTarget(it) != null }
            ?.let { return it }
        return nodes.firstOrNull { n ->
            (phraseKey(n.description) in SEND_WORDS || phraseKey(n.text) in SEND_WORDS) && NodeQueries.clickTarget(n) != null
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** `composeSendButton` / `compose_send-button` → [compose, send, button]. */
    fun idWords(id: String?): List<String> {
        if (id.isNullOrBlank()) return emptyList()
        return id.replace(CAMEL, "$1_$2").lowercase(Locale.ROOT).split(NON_ALNUM).filter { it.isNotEmpty() }
    }

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Matching key: [TextNorm.key] without trailing ':' / '…' / '*'. */
    fun phraseKey(s: String?): String = TextNorm.key(s).trimEnd(':', '…', '*', '.', ' ')

    private fun phrases(vararg raw: String): Set<String> = raw.map(::phraseKey).toSet()
}
