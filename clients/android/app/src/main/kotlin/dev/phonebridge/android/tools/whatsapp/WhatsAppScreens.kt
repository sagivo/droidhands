package dev.phonebridge.android.tools.whatsapp

import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowSnapshot

/** Who the opened chat must be with. [namesAuthoritative]: we know the exact contact name WhatsApp will show. */
data class ExpectedChat(val digits: String, val names: List<String>, val namesAuthoritative: Boolean)

/** Whether the chat title shows the expected recipient. */
enum class TitleVerdict { MATCH, MISMATCH, UNKNOWN }

/** What to do with a chat on screen while waiting for the recipient's chat. */
enum class ChatAcceptance {
    /** Use it now. */
    ACCEPT,

    /** Use it once it has stayed the same for a short while. */
    ACCEPT_IF_STABLE,

    /** Keep waiting (wrong, stale or unverifiable chat). */
    WAIT,
}

/** What WhatsApp is showing. */
sealed interface WaScreen {
    /** A chat. [entryById]: the compose field was found by its view id (not by the layout fallback). */
    data class Conversation(
        val entry: UiNode,
        val entryById: Boolean,
        /** Compose-field text; empty when it only shows its hint. */
        val entryText: String,
        val send: UiNode?,
        /** Text of the title view (`conversation_contact_name`), if found. */
        val title: String?,
        /** Other texts in the toolbar area (outside the message list). */
        val headerTexts: List<String>,
    ) : WaScreen

    /** "The phone number … isn't on WhatsApp" (or the invite screen). */
    data class NotOnWhatsApp(val message: String) : WaScreen

    /** "Unblock … to send a message?" */
    data class Blocked(val message: String) : WaScreen

    /** Welcome / phone verification: WhatsApp isn't set up. */
    data object Registration : WaScreen

    /** WhatsApp's own app lock (fingerprint/PIN). */
    data object Locked : WaScreen

    /** The chat list. */
    data object Home : WaScreen

    /** No WhatsApp window on screen. */
    data object NotVisible : WaScreen

    data object Unknown : WaScreen
}

/**
 * Heuristics for WhatsApp's UI, on captured trees (pure). View ids are tried first; text phrases
 * cover English plus Hebrew and a few other common languages. Detection only ever *prevents*
 * sending: an unrecognized screen makes whatsapp_send fail rather than guess.
 */
object WhatsAppScreens {
    /** Toolbar area: the top fifth of the window. */
    private const val HEADER_FRACTION = 0.2

    private val NOT_ON_WHATSAPP = phrases(
        "isn't on whatsapp", "is not on whatsapp", "isn't using whatsapp", "is not using whatsapp", "not on whatsapp",
        "לא נמצא ב-whatsapp", "אינו נמצא ב-whatsapp", "אינו ב-whatsapp", "לא רשום ב-whatsapp", "אינו רשום ב-whatsapp",
        "אינו משתמש ב-whatsapp", "לא משתמש ב-whatsapp", "לא נמצא בוואטסאפ", "אינו משתמש בוואטסאפ",
        "no está en whatsapp", "no usa whatsapp", "não está no whatsapp", "não usa o whatsapp",
        "ist nicht bei whatsapp", "nicht auf whatsapp", "nutzt whatsapp nicht", "n'est pas sur whatsapp",
        "n'utilise pas whatsapp", "non è su whatsapp", "non usa whatsapp", "нет в whatsapp", "не использует whatsapp",
        "ليس على واتساب", "غير موجود على واتساب", "ليس على whatsapp", "whatsapp'ta değil",
    )

    private val INVITE = phrases(
        "invite to whatsapp", "הזמנה ל-whatsapp", "הזמן ל-whatsapp", "invitar a whatsapp", "convidar para o whatsapp",
        "zu whatsapp einladen", "inviter sur whatsapp", "invita su whatsapp", "пригласить в whatsapp", "دعوة إلى واتساب",
    )

    private val UNBLOCK = phrases(
        "unblock", "ביטול חסימה", "לבטל את החסימה", "בטל חסימה", "desbloquear", "débloquer", "blockierung aufheben",
        "sblocca", "разблокировать", "إلغاء الحظر", "engeli kaldır",
    )

    private val REGISTRATION = phrases(
        "agree and continue", "verify your phone number", "enter your phone number", "welcome to whatsapp",
        "הסכמה והמשך", "אימות מספר הטלפון", "הזנת מספר הטלפון", "ברוכים הבאים ל-whatsapp",
        "aceptar y continuar", "concordar e continuar", "zustimmen und fortfahren", "accepter et continuer",
        "accetta e continua", "принять и продолжить", "موافقة ومتابعة",
    )

    private val APP_LOCKED = phrases(
        "whatsapp is locked", "whatsapp locked", "unlock whatsapp", "whatsapp נעול", "ביטול נעילת whatsapp",
        "whatsapp está bloqueado", "whatsapp ist gesperrt", "whatsapp est verrouillé", "whatsapp è bloccato",
        "whatsapp заблокирован",
    )

    private val HOME_TABS = phrases(
        "chats", "updates", "communities", "calls", "status",
        "צ'אטים", "עדכונים", "קהילות", "שיחות", "סטטוס",
        "novedades", "comunidades", "llamadas", "conversas", "atualizações", "ligações",
        "anrufe", "neuigkeiten", "discussions", "actus", "appels", "chiamate", "aggiornamenti",
        "чаты", "звонки", "الدردشات", "المكالمات",
    )

    /** Content descriptions of the send button in common languages (fallback when the id changed). */
    private val SEND_DESCRIPTIONS = phrases(
        "send", "שליחה", "שלח", "enviar", "senden", "envoyer", "invia", "отправить", "إرسال", "kirim", "gönder", "भेजें",
    )

    // ---- view ids ----------------------------------------------------------------------------

    fun entryId(pkg: String) = "$pkg:id/entry"

    fun sendIds(pkg: String) = listOf("$pkg:id/send", "$pkg:id/send_container")

    fun titleIds(pkg: String) = listOf("$pkg:id/conversation_contact_name")

    private fun homeIds(pkg: String) =
        setOf("$pkg:id/fab", "$pkg:id/conversations_row_contact_name", "$pkg:id/home_tab_layout", "$pkg:id/bottom_nav")

    private fun registrationIdPrefixes(pkg: String) =
        listOf("$pkg:id/eula_", "$pkg:id/registration_", "$pkg:id/register_", "$pkg:id/verify_")

    // ---- classification ----------------------------------------------------------------------

    /**
     * Classifies the WhatsApp ([pkg]) windows among [windows]. Dialog windows are checked first.
     * In the main window, "not on WhatsApp" text only counts when it also shows [expectedDigits],
     * so a chat message quoting that phrase is not mistaken for the dialog.
     */
    fun classify(pkg: String, windows: List<WindowSnapshot>, expectedDigits: String?): WaScreen {
        val wa = windows.filter { !it.hidden && it.root != null && it.packageName == pkg }
        if (wa.isEmpty()) return WaScreen.NotVisible
        val main = wa.maxBy { it.bounds.area }
        for (dialog in wa) {
            if (dialog !== main) dialogScreen(dialog.root!!)?.let { return it }
        }
        val root = main.root!!
        findEntry(pkg, root, main)?.let { (entry, byId) ->
            return WaScreen.Conversation(
                entry = entry,
                entryById = byId,
                entryText = TextNorm.clean(entry.text).trim(),
                send = findSend(pkg, wa.mapNotNull { it.root }),
                title = byIds(root, titleIds(pkg))?.let { TextNorm.clean(it.text ?: it.description).trim() }?.ifEmpty { null },
                headerTexts = headerTexts(root, main),
            )
        }
        val nodes = NodeQueries.visibleNodes(listOf(root)).toList()
        for (n in nodes) {
            val text = n.text ?: continue
            if (containsAny(text, NOT_ON_WHATSAPP) && expectedDigits != null && digitsMatch(TextNorm.digits(text), expectedDigits)) {
                return WaScreen.NotOnWhatsApp(TextNorm.clean(text).trim())
            }
        }
        nodes.firstOrNull { n -> NodeQueries.clickTarget(n) != null && (startsWithAny(n.text, UNBLOCK) || startsWithAny(n.description, UNBLOCK)) }
            ?.let { return WaScreen.Blocked(TextNorm.clean(it.text ?: it.description).trim()) }
        val texts = nodes.flatMap { listOfNotNull(it.text, it.description) }
        if (texts.any { containsAny(it, REGISTRATION) } || nodes.any { n -> registrationIdPrefixes(pkg).any { n.viewId?.startsWith(it) == true } }) {
            return WaScreen.Registration
        }
        if (texts.any { containsAny(it, APP_LOCKED) }) return WaScreen.Locked
        val homeIds = homeIds(pkg)
        if (nodes.any { it.viewId in homeIds } || texts.count { phraseKey(it) in HOME_TABS } >= 2) return WaScreen.Home
        return WaScreen.Unknown
    }

    /** A WhatsApp dialog window: not-on-WhatsApp / invite, or unblock prompt. */
    private fun dialogScreen(root: UiNode): WaScreen? {
        val texts = NodeQueries.visibleNodes(listOf(root)).flatMap { sequenceOf(it.text, it.description) }.filterNotNull().toList()
        texts.firstOrNull { containsAny(it, NOT_ON_WHATSAPP) }?.let { return WaScreen.NotOnWhatsApp(TextNorm.clean(it).trim()) }
        texts.firstOrNull { containsAny(it, INVITE) }?.let { return WaScreen.NotOnWhatsApp(TextNorm.clean(it).trim()) }
        texts.firstOrNull { containsAny(it, UNBLOCK) }?.let { return WaScreen.Blocked(TextNorm.clean(it).trim()) }
        return null
    }

    /** The compose field: by id, else the only editable in the window's bottom half. */
    private fun findEntry(pkg: String, root: UiNode, window: WindowSnapshot): Pair<UiNode, Boolean>? {
        byIds(root, listOf(entryId(pkg)))?.let { return it to true }
        val midY = window.bounds.top + window.bounds.height / 2
        val editables = NodeQueries.visibleNodes(listOf(root)).filter { it.editable && it.bounds.top >= midY }.toList()
        return editables.singleOrNull()?.let { it to false }
    }

    /** The send button: by id, else a clickable element described as "Send". */
    fun findSend(pkg: String, roots: List<UiNode>): UiNode? {
        val ids = sendIds(pkg)
        for (id in ids) roots.firstNotNullOfOrNull { byIds(it, listOf(id)) }?.let { return it }
        return NodeQueries.visibleNodes(roots).firstOrNull { n ->
            phraseKey(n.description) in SEND_DESCRIPTIONS && NodeQueries.clickTarget(n) != null
        }
    }

    private fun byIds(root: UiNode, ids: List<String>): UiNode? =
        NodeQueries.visibleNodes(listOf(root)).firstOrNull { it.viewId != null && it.viewId in ids }

    /** Texts in the toolbar band, excluding anything inside a scrolling list (message bubbles). */
    private fun headerTexts(root: UiNode, window: WindowSnapshot): List<String> {
        val limit = window.bounds.top + (window.bounds.height * HEADER_FRACTION).toInt()
        return NodeQueries.visibleNodes(listOf(root))
            .filter { n -> !n.editable && n.bounds.top < limit && n.ancestors().none(NodeQueries::isScrollContainer) }
            .flatMap { sequenceOf(it.text, it.description) }
            .filterNotNull()
            .map { TextNorm.clean(it).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    // ---- recipient checks --------------------------------------------------------------------

    /**
     * Whether [conversation] is with [expected]: any toolbar text matching the name or number is
     * a MATCH; otherwise the title view decides (MISMATCH/UNKNOWN); without a title, UNKNOWN.
     */
    fun judge(conversation: WaScreen.Conversation, expected: ExpectedChat): TitleVerdict {
        val all = listOfNotNull(conversation.title) + conversation.headerTexts
        if (all.any { judgeTitle(it, expected) == TitleVerdict.MATCH }) return TitleVerdict.MATCH
        return conversation.title?.let { judgeTitle(it, expected) } ?: TitleVerdict.UNKNOWN
    }

    /**
     * The gate before typing/sending. Browsing ([strict] = false) takes any chat that doesn't
     * contradict the recipient. Sending requires a [fresh] window (shown after our launch) and
     * either a matching title, or an unreadable title with the compose field found by id (then the
     * caller also waits for stability).
     */
    fun acceptance(verdict: TitleVerdict, strict: Boolean, fresh: Boolean, entryById: Boolean): ChatAcceptance = when {
        !strict -> if (verdict == TitleVerdict.MISMATCH) ChatAcceptance.WAIT else ChatAcceptance.ACCEPT
        !fresh || verdict == TitleVerdict.MISMATCH -> ChatAcceptance.WAIT
        verdict == TitleVerdict.MATCH -> ChatAcceptance.ACCEPT
        entryById -> ChatAcceptance.ACCEPT_IF_STABLE
        else -> ChatAcceptance.WAIT
    }

    /** A title showing a phone number must match the digits; a name must equal an expected name. */
    fun judgeTitle(title: String, expected: ExpectedChat): TitleVerdict {
        val key = nameKey(title)
        if (key.isEmpty()) return TitleVerdict.UNKNOWN
        val digits = TextNorm.digits(title)
        if (digits.length >= 7) return if (digitsMatch(digits, expected.digits)) TitleVerdict.MATCH else TitleVerdict.MISMATCH
        if (expected.names.any { it.isNotBlank() && nameKey(it) == key }) return TitleVerdict.MATCH
        return if (expected.namesAuthoritative) TitleVerdict.MISMATCH else TitleVerdict.UNKNOWN
    }

    /** Same number, tolerating a missing country code / trunk zero: the last min(9, both lengths) digits, at least 7. */
    fun digitsMatch(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val n = minOf(9, a.length, b.length)
        return n >= 7 && a.takeLast(n) == b.takeLast(n)
    }

    /** Name comparison key: case-folded letters and digits only (drops emoji, punctuation, bidi marks). */
    fun nameKey(s: String): String =
        TextNorm.key(s).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    // ---- message checks ----------------------------------------------------------------------

    /** Message equality as shown by WhatsApp: invisible marks removed, ends trimmed. */
    fun sameMessage(shown: String?, message: String): Boolean = normalizeMessage(shown) == normalizeMessage(message)

    fun normalizeMessage(s: String?): String = TextNorm.clean(s).trim()

    /** Message bubbles (non-editable texts) in WhatsApp windows that equal [message]. */
    fun bubbleCount(pkg: String, windows: List<WindowSnapshot>, message: String): Int {
        val want = normalizeMessage(message)
        if (want.isEmpty()) return 0
        val roots = windows.filter { !it.hidden && it.packageName == pkg }.mapNotNull { it.root }
        return NodeQueries.visibleNodes(roots).count { !it.editable && normalizeMessage(it.text) == want }
    }

    // ---- phrase matching ---------------------------------------------------------------------

    /** Lower-case key with apostrophe/dash/maqaf variants unified, so "isn’t" and "ב־WhatsApp" match. */
    fun phraseKey(s: String?): String = TextNorm.key(s)
        .replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'').replace('׳', '\'').replace('`', '\'')
        .filterNot { it == '-' || it == '־' || it in '‐'..'—' }

    private fun phrases(vararg raw: String): Set<String> = raw.map(::phraseKey).toSet()

    private fun containsAny(s: String?, set: Set<String>): Boolean {
        val k = phraseKey(s)
        return k.isNotEmpty() && set.any { k.contains(it) }
    }

    private fun startsWithAny(s: String?, set: Set<String>): Boolean {
        val k = phraseKey(s)
        return k.isNotEmpty() && set.any { k.startsWith(it) }
    }
}
