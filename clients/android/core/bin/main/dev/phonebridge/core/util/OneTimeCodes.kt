package dev.phonebridge.core.util

/**
 * Masks one-time / verification / 2FA codes in message text before agents see it
 * (docs/SECURITY.md "Hide one-time codes"). Must handle common formats in many languages:
 * "Your code is 123456", "G-123456 is your Google verification code", "קוד האימות שלך: 4821",
 * "Use 12345678 to log in", "OTP: 9876", codes with a space/dash ("123 456", "123-456"), and
 * alphanumeric codes adjacent to code keywords. Ordinary numbers (prices, dates, times, phone numbers,
 * amounts) in texts without code keywords must be preserved.
 *
 * How it decides (pure, thread-safe, no I/O):
 * 1. Code-shaped tokens are found by [OtpCandidates] (4-10 digits or letters+digits, `123 456`,
 *    `G-123456`, uppercase words); parts of phone numbers, dates, times, prices, amounts, URLs,
 *    e-mail addresses and `#order` numbers are never candidates.
 * 2. A candidate is a code when one of these holds ([OtpKeywords] has the multilingual keywords):
 *    * a keyword comes first, at most [BEFORE_WINDOW] characters earlier, with no other sentence in
 *      between ("Your code is 123456", "OTP: 9876", "验证码：123456", "Code:\n\n482913");
 *    * a keyword follows within [AFTER_WINDOW] characters in the same sentence, introduced by a
 *      linking word or punctuation ("G-123456 is your Google verification code", "1234 הוא הקוד",
 *      "482913 — ваш код");
 *    * the sentence pattern "use/enter <code> to log in/verify/confirm…" in several languages;
 *    * an origin-bound code line (`@example.com #123456`, the WebOTP format).
 *    Weak candidates (uppercase words, year-like and 9-10 digit numbers) count only right after a
 *    keyword ("code: KRBTF", "PIN is 2019").
 * 3. Every other occurrence of a detected code is masked too (e.g. the same code inside a link).
 *
 * The rules favour masking: in a message that talks about a code, a nearby reference number may be
 * masked as well. Text without code keywords or code sentence patterns is returned unchanged.
 */
object OneTimeCodes {
    const val MASK = "[code hidden]"

    /** Max characters between the end of a keyword and a code that follows it. */
    internal const val BEFORE_WINDOW = 60

    /** Max characters between a code and a keyword that follows it. */
    internal const val AFTER_WINDOW = 40

    private const val MIN_TEXT_LENGTH = 4

    /** Returns [text] with every detected one-time code replaced by [MASK]. */
    fun redact(text: String): String {
        val spans = find(text)
        if (spans.isEmpty()) return text
        val out = StringBuilder(text.length)
        var last = 0
        for (span in spans) {
            out.append(text, last, span.first).append(MASK)
            last = span.last + 1
        }
        return out.append(text, last, text.length).toString()
    }

    /** True if [text] looks like it contains a one-time code. */
    fun containsCode(text: String): Boolean = find(text).isNotEmpty()

    /** Sorted, non-overlapping ranges of the codes in [text]. */
    internal fun find(text: String): List<IntRange> {
        if (text.length < MIN_TEXT_LENGTH) return emptyList()
        val accepted = ArrayList<IntRange>()
        val values = HashSet<String>()
        for (m in WEB_OTP.findAll(text)) {
            val range = m.groups[1]?.range ?: continue
            accepted += range
            values += OtpCandidates.normalize(m.groups[1]!!.value)
        }
        val candidates = OtpCandidates.find(text)
        if (candidates.isNotEmpty()) {
            val keywords = OtpKeywords.find(text)
            for (c in candidates) {
                val near = nearby(keywords, c)
                if (near.any { it.first < c.end && c.start <= it.last }) continue // the keyword itself, e.g. "CODE"
                if (isCode(text, c, near)) {
                    accepted += c.start until c.end
                    values += c.value
                }
            }
        }
        if (values.isEmpty()) return emptyList()
        accepted += repeats(text, values)
        return merge(accepted)
    }

    /**
     * The keywords (sorted by start, each at most [OtpKeywords.MAX_LENGTH] long) that can reach [c]:
     * a binary search keeps long texts with many numbers and keywords fast.
     */
    private fun nearby(keywords: List<IntRange>, c: OtpCandidates.Candidate): List<IntRange> {
        val from = c.start - BEFORE_WINDOW - OtpKeywords.MAX_LENGTH
        val to = c.end + AFTER_WINDOW
        var lo = 0
        var hi = keywords.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keywords[mid].first < from) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < keywords.size && keywords[end].first <= to) end++
        return keywords.subList(lo, end)
    }

    private fun isCode(text: String, c: OtpCandidates.Candidate, keywords: List<IntRange>): Boolean {
        for (kw in keywords) {
            val kwEnd = kw.last + 1
            if (kwEnd <= c.start) {
                if (c.start - kwEnd > BEFORE_WINDOW || !sameSentenceOrNoWords(text, kwEnd, c.start)) continue
                if (c.weak && !TIGHT_GAP.matches(text.subSequence(kwEnd, c.start))) continue
                return true
            }
            if (!c.weak && kw.first >= c.end && kw.first - c.end <= AFTER_WINDOW && !Sentences.anyBoundary(text, c.end, kw.first)) {
                if (introducesKeyword(text.substring(c.end, kw.first))) return true
            }
        }
        return !c.weak && usePattern(text, c.start, c.end)
    }

    /** True if no sentence ends in [from, to), or only punctuation/whitespace follows the last end. */
    private fun sameSentenceOrNoWords(text: String, from: Int, to: Int): Boolean {
        val boundary = Sentences.lastBoundary(text, from, to)
        if (boundary < 0) return true
        for (i in boundary + 1 until to) if (text[i].isLetter()) return false
        return true
    }

    /** The text between a code and a later keyword starts with punctuation or a linking word. */
    private fun introducesKeyword(gap: String): Boolean {
        val trimmed = gap.trimStart()
        if (trimmed.isEmpty()) return false // "1500 tokens", "Room 1204 code": too weak on its own.
        val first = trimmed[0]
        val type = Character.getType(first)
        if (type == Character.DASH_PUNCTUATION.toInt() || first in ":：(（[【|,，–—") return true
        return CONNECTOR.containsMatchIn(trimmed)
    }

    /** "Use 12345678 to log in", "Enter 482913 to verify", "请输入482913完成验证". */
    private fun usePattern(text: String, start: Int, end: Int): Boolean {
        val before = text.substring(maxOf(0, start - 40), start)
        if (!VERB_BEFORE.containsMatchIn(before)) return false
        var stop = minOf(text.length, end + 50)
        for (i in end until stop) {
            if (Sentences.isBoundary(text, i)) {
                stop = i
                break
            }
        }
        return PURPOSE.containsMatchIn(text.subSequence(end, stop))
    }

    /** Other occurrences of detected code [values], alone or as two groups ("123 456"), anywhere. */
    private fun repeats(text: String, values: Set<String>): List<IntRange> {
        val runs = RUN.findAll(text).toList()
        val out = ArrayList<IntRange>()
        for ((i, run) in runs.withIndex()) {
            val value = OtpCandidates.normalize(run.value)
            if (value in values) out += run.range
            val next = runs.getOrNull(i + 1) ?: continue
            val sep = run.range.last + 1
            if (next.range.first == sep + 1 && text[sep] in " -" && value + OtpCandidates.normalize(next.value) in values) {
                out += run.range.first..next.range.last
            }
        }
        return out
    }

    private fun merge(spans: List<IntRange>): List<IntRange> {
        val sorted = spans.sortedBy { it.first }
        val out = ArrayList<IntRange>(sorted.size)
        for (span in sorted) {
            val last = out.lastOrNull()
            if (last != null && span.first <= last.last + 1) {
                out[out.size - 1] = last.first..maxOf(last.last, span.last)
            } else {
                out += span
            }
        }
        return out
    }

    private val RUN = Regex("""[A-Za-z\p{Nd}]+""")

    /** WebOTP origin-bound line: `@example.com #123456` (optionally followed by `%iframe.example`). */
    private val WEB_OTP = Regex("""^@[A-Za-z0-9.\-]+[ \t]+#([A-Za-z0-9]{4,10})(?=[ \t\r]|$)""", RegexOption.MULTILINE)

    /** Gap after a keyword that is only punctuation and at most one linking word ("code: X", "PIN is X"). */
    private val TIGHT_GAP = Regex(
        """^[\s\p{P}\p{S}]*(?:(?:is|are|was|est|ist|es|son|é|è|är|er|jest|je|lautet|lauten|это|هو|هي|是|为|為|הוא|היא|הינו|הינה|adalah)[\s\p{P}\p{S}]*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Linking words between a code and the keyword after it ("123456 is your code", "1234 ist dein Code"). */
    private val CONNECTOR = Regex(
        """^(?:(?:is|are|was|as|will\s+be|est|sera|c'est|ist|lautet|es|son|ser[áa]|é|è|är|er|jest|je|это|является|""" +
            """هو|هي|היא|הוא|הינו|הינה|זה|your|votre|ton|ta|dein|deine|ihr|ihre|tu|su|seu|sua|o\s+seu|a\s+sua|il\s+tuo|""" +
            """la\s+tua|uw|jouw|ваш|ваша|твой|твоя|sizin|senin|tw[óo]j|anda|do\s+not\s+share|don't\s+share|never\s+share)(?![\p{L}])""" +
            """|是|为|為|即|は|が|您的|你的)""",
        RegexOption.IGNORE_CASE,
    )

    /** "Use", "Enter"… right before a code (optionally "the"/"code:"). */
    private val VERB_BEFORE = Regex(
        """(?:(?<![\p{L}])(?:use|enter|type|input|provide|utilisez|utilise|entrez|saisissez|saisis|usa|us[aá]|utiliza|utiliz[aá]|""" +
            """ingresa|ingres[aá]|introduce|introduzca|digite|insira|informe|verwende|verwenden|nutze|nutzen|gib|geben|""" +
            """eingeben|inserisci|utilizza|gebruik|voer|введите|используйте|укажите|أدخل|ادخل|استخدم|הזן|הזינו|הקלד|""" +
            """הקלידו|השתמש|השתמשו)(?:\s+(?:the|this|le|la|el|o|a|den|die|der|das|il|het|de))?|输入|輸入|使用|入力|입력)\s*[:：]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** What a code is used for, after it ("… to log in", "… para verificar", "… для входа"). */
    private val PURPOSE = Regex(
        """(?<![\p{L}])(?:log\s?in|login|sign[\s-]?in|sign[\s-]?up|verify|verification|confirm|authenticate|activate|reset|""" +
            """iniciar\s+sesi[óo]n|ingresar|acceder|verificar|confirmar|activar|entrar|connecter|connexion|v[ée]rifier|valider|""" +
            """confirmer|anmelden|anmeldung|einloggen|best[äa]tigen|verifizieren|accedere|verificare|confermare|inloggen|""" +
            """bevestigen|войти|входа|вход|подтверд\p{L}{0,20}|giriş|doğrula\p{L}{0,20}|zalogowa\p{L}{0,20}|logowani\p{L}{0,20}|potwierdz\p{L}{0,20}|""" +
            """להתחבר|התחברות|כניסה|לכניסה|לאמת|לאימות|לאישור)(?![\p{L}])|تسجيل\s+الدخول|للتحقق|لتأكيد|登录|登入|登陆|验证|驗證|确认|確認|ログイン|로그인""",
        RegexOption.IGNORE_CASE,
    )
}
