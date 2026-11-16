package com.droidhands.core.util

/**
 * Words that mark a one-time code nearby, in many languages (pure): code / OTP / PIN / passcode /
 * password / verification / one-time / 2FA / TAN and their equivalents (קוד, סיסמה, אימות, código,
 * clave, senha, codice, Code compounds such as Bestätigungscode, код, пароль, رمز, كود, 验证码,
 * 認証コード, 인증번호, …). Matches are bounded ([MAX_LENGTH]) so scanning stays linear.
 *
 * Non-code uses are filtered out: promo/discount/coupon/zip/postal/area/error/QR/bar/dress codes and
 * the like (`promo code`, `código postal`, `промокод`, `Postcode`, `קוד קופון`, `كود الخصم`), and
 * keywords in a sentence that reads like an advertisement (`20% off`, `at checkout`).
 */
internal object OtpKeywords {
    /** Longest keyword match returned by [find]. */
    const val MAX_LENGTH = 48

    private const val NO_LETTER_BEFORE = """(?<![\p{L}])"""
    private const val NO_LETTER_AFTER = """(?![\p{L}])"""

    /** Keyword patterns. Group 1, when present, is a compound prefix checked against [NEGATIVE_PREFIXES]. */
    private val PATTERNS: List<Regex> = listOf(
        // English/French/German/Dutch "code" and compounds: passcode, Bestätigungscode, verificatiecode, logincode.
        Regex("""$NO_LETTER_BEFORE(\p{L}{0,24}?)codes?$NO_LETTER_AFTER""", RegexOption.IGNORE_CASE),
        // Cyrillic "код" (+ cases) and compounds; промокод / штрихкод are caught by the prefix check.
        Regex("""$NO_LETTER_BEFORE(\p{L}{0,12}?)код(?:а|у|ом|е|ы|ов|ами|ах)?$NO_LETTER_AFTER""", RegexOption.IGNORE_CASE),
        Regex(
            NO_LETTER_BEFORE + "(?:" + listOf(
                """c[óo]digos?""", // es, pt
                """codic[ei]""", // it
                """codul(?:ui)?""", // ro
                """k[oó]d(?:u|unuz|unu|un|uz|em|y|ów|ok|lar|ları)?""", // tr, pl, hu, cs, sk, sv, ms
                """kode""", // id, da, no
                """κωδικ\p{L}{0,20}""", // el
                """otps?""",
                """one[\s-]?time""",
                """2fa""", """mfa""", """2-?step""", """two[\s-](?:step|factor)""", """2[\s-]factor""",
                """pass(?:code|word|wort|wd)s?""",
                """kennwort""", """wachtwoord""", """contrase[ñn]as?""", """senhas?""", """mot\s+de\s+passe""",
                """[şs]ifre\p{L}{0,20}""", """has[łl]o""", """hasła""", """heslo""", """lozinka""", """salasana""",
                """пароль\p{L}{0,3}""", """одноразов\p{L}{0,20}""", """подтвер\p{L}{0,20}""",
                """v[ée]rif\p{L}{0,20}""", """weryfik\p{L}{0,20}""", """verifizier\p{L}{0,20}""",
                """aut(?:h)?enti[cf]\p{L}{0,20}""", """auth""",
                """tokens?""", """clave""",
                """un\s+solo\s+uso""", """uso\s+[úu]nico""", """usage\s+unique""", """einmal\p{L}{0,20}""", """tek\s+kullan\p{L}{0,20}""",
                """pin[\s-]?(?:code|kod|код|number|nummer|n[úu]mero)""",
            ).joinToString("|") + ")" + NO_LETTER_AFTER,
            RegexOption.IGNORE_CASE,
        ),
        // Case-sensitive: "PIN" and German/Austrian banking "TAN" (lowercase pin/tan are ordinary words).
        Regex("""$NO_LETTER_BEFORE(?:PIN|ПИН|(?:m|sms|SMS|push|chip|photo|i|e)?TAN)$NO_LETTER_AFTER"""),
        // Hebrew, with attached prefixes (ו ב כ ל מ ש ה) and suffixes; "קודם" (= before) is not a match.
        Regex(
            """$NO_LETTER_BEFORE[ובכלמשה]{0,3}(?:קוד(?:ך|ים|י|ו|כם)?|סיסמ(?:ה|א|ת|תך|תכם|אות|ות)|אימות|חד[\s-]?פעמי(?:ת|ים)?)$NO_LETTER_AFTER""",
        ),
        // Arabic and Persian.
        Regex("""رمز|كود|كلمة\s+(?:المرور|السر)|التحقق|تحقق|التفعيل|تفعيل|لمرة\s+واحدة|الرقم\s+السري|رقم\s+سري|$NO_LETTER_BEFORE(?:کد|کد\s+تایید)$NO_LETTER_AFTER"""),
        // Chinese, Japanese, Korean, Thai, Hindi, Vietnamese.
        Regex(
            listOf(
                "验证码", "驗證碼", "校验码", "校驗碼", "动态码", "動態碼", "确认码", "確認碼", "认证码", "認證碼", "安全码", "安全碼",
                "登录码", "登錄碼", "授权码", "授權碼", "激活码", "激活碼", "取件码", "取件碼", "提货码", "验证代码", "驗證代碼",
                "动态密码", "動態密碼", "一次性密码", "一次性密碼", "密码", "密碼", "口令",
                "認証コード", "確認コード", "認証番号", "暗証番号", "ワンタイムパスワード", "パスコード", "セキュリティコード", "ログインコード", "パスワード",
                """인증\s?번호""", """인증\s?코드""", """확인\s?코드""", "보안코드", "승인번호", "비밀번호",
                "รหัส", "कोड", "ओटीपी", "सत्यापन", """m[ãa]\s+(?:x[áa]c\s+(?:th[ựu]c|nh[ậa]n)|otp|b[ảa]o\s+m[ậa]t)""",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        ),
    )

    /** Compound prefixes that make "code"/"код" a non-secret code (barcode, promocode, Postcode…). */
    private val NEGATIVE_PREFIXES = setOf(
        "bar", "qr", "zip", "post", "postal", "promo", "promotion", "discount", "coupon", "voucher", "referral", "invite",
        "short", "en", "de", "re", "trans", "uni", "geo", "op", "dress", "source", "area", "country", "error", "status",
        "tracking", "product", "tax", "sort", "swift", "morse", "colour", "color", "rabatt", "gutschein", "aktions", "korting",
        "kortings", "actie", "kleur", "fehler", "länder", "promo-", "промо", "штрих", "штрих-", "qr-",
    )

    /** A word right before the keyword that makes it a non-secret code ("promo code", "zip code"). */
    private val NEGATIVE_BEFORE = setOf(
        "promo", "promotional", "promotion", "discount", "coupon", "voucher", "referral", "invite", "invitation", "zip",
        "postal", "post", "area", "country", "dial", "dialing", "error", "status", "response", "tracking", "product", "item",
        "bar", "qr", "source", "dress", "tax", "sort", "swift", "bic", "iban", "bank", "branch", "routing", "morse", "colour",
        "color", "html", "ascii", "unicode", "airport", "iata", "booking", "reservation", "reference", "ref", "order", "ticket",
        "flight", "sku", "article", "gift", "rabatt", "gutschein", "aktions", "штрих", "почтовый", "почтового", "palabra",
        "region", "city", "zone", "postleit", "fehler",
    )

    /** A word right after the keyword (skipping de/of/…) that makes it a non-secret code ("código postal"). */
    private val NEGATIVE_AFTER = setOf(
        "postal", "postale", "promo", "promocional", "promotionnel", "promozionale", "promotional", "descuento", "desconto",
        "sconto", "réduction", "reduction", "rabais", "remise", "fiscal", "fiscale", "barras", "barres", "qr", "área", "area",
        "país", "pais", "paese", "pays", "rastreio", "rastreo", "seguimiento", "suivi", "tracking", "error", "erreur", "errore",
        "erro", "cupón", "cupon", "cupom", "coupon", "regalo", "cadeau", "parrainage", "referido", "convite", "invitación",
        "invito", "invitation", "города", "страны", "ошибки", "скидки", "купона", "товара", "региона", "קופון", "הנחה", "מבצע",
        "שגיאה", "הטבה", "קופה", "الخصم", "خصم", "بريدي", "البريدي", "الكوبون", "كوبون", "الدولة", "الخطأ", "البلد", "الاستجابة",
    )

    /** Small words skipped when looking at the word after a keyword ("código de descuento"). */
    private val LINKING_WORDS = setOf("de", "di", "da", "do", "du", "del", "della", "dello", "des", "of", "the", "d", "für", "for")

    /** Sentences with these read like ads ("Use code SAVE20 for 20% off"): their keywords don't count. */
    private val ADVERT = Regex(
        """%\s*(?:off|de\s+descuento|de\s+desconto|rabatt|скидк\p{L}{0,20}|הנחה)|$NO_LETTER_BEFORE(?:at\s+checkout|checkout|discount|coupon|voucher|promo\p{L}{0,20}|""" +
            """descuento|cup[óo]n|cupom|desconto|r[ée]duction|rabatt|gutschein|скидк\p{L}{0,20}|промо\p{L}{0,20}|הנחה|קופון|خصم|كوبون)$NO_LETTER_AFTER|优惠|折扣""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Bare words that also name promo codes. Only these are ignored in advert-like sentences; specific
     * keywords (OTP, verification code, 验证码…) always count, so a code is never revealed because its
     * message also mentions a discount.
     */
    private val GENERIC = setOf(
        "code", "codes", "código", "códigos", "codigo", "codigos", "codice", "codici", "kod", "kód", "kode", "kodu", "код", "кода",
        "коду", "кодом", "קוד", "הקוד", "בקוד", "כקוד", "كود", "الكود", "رمز", "الرمز", "clave", "token", "tokens",
    )

    /** [start, end) ranges of the code keywords in [text]. */
    fun find(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (pattern in PATTERNS) {
            for (m in pattern.findAll(text)) {
                val prefix = if (m.groups.size > 1) m.groups[1]?.value?.lowercase() else null
                if (!prefix.isNullOrEmpty() && (prefix in NEGATIVE_PREFIXES || prefix.trimEnd('-', 's') in NEGATIVE_PREFIXES)) continue
                val start = m.range.first
                val end = m.range.last + 1
                if (end - start > MAX_LENGTH) continue // e.g. "mot" + 50 spaces + "de passe": not a real keyword
                if (negatedByNeighbours(text, start, end)) continue
                if (m.value.lowercase() in GENERIC && inAdvertSentence(text, start)) continue
                out += start until end
            }
        }
        return out.sortedBy { it.first }
    }

    private fun negatedByNeighbours(text: String, start: Int, end: Int): Boolean {
        val before = wordBefore(text, start)?.lowercase()
        if (before != null && before in NEGATIVE_BEFORE) return true
        var after = wordAfter(text, end)
        if (after != null && after.second.lowercase() in LINKING_WORDS) after = wordAfter(text, after.first)
        return after != null && after.second.lowercase() in NEGATIVE_AFTER
    }

    private fun inAdvertSentence(text: String, index: Int): Boolean {
        val (from, to) = Sentences.bounds(text, index)
        return ADVERT.containsMatchIn(text.subSequence(from, to))
    }

    /** The letter run ending just before [start], skipping up to two spaces or hyphens. */
    private fun wordBefore(text: String, start: Int): String? {
        var i = start - 1
        var skipped = 0
        while (i >= 0 && (text[i] == ' ' || text[i] == '-') && skipped < 2) {
            i--
            skipped++
        }
        val end = i + 1
        while (i >= 0 && text[i].isLetter() && end - i <= 24) i--
        return text.substring(i + 1, end).takeIf { it.isNotEmpty() }
    }

    /** (end index, word) of the letter run after [end], skipping spaces, hyphens and apostrophes. */
    private fun wordAfter(text: String, end: Int): Pair<Int, String>? {
        var i = end
        var skipped = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '-' || text[i] == '\'' || text[i] == '’') && skipped < 3) {
            i++
            skipped++
        }
        val start = i
        while (i < text.length && text[i].isLetter() && i - start < 24) i++
        return if (i > start) i to text.substring(start, i) else null
    }
}

/** Sentence boundaries for keyword proximity (pure). */
internal object Sentences {
    private const val ENDERS = ".!?"
    private const val CJK_ENDERS = "。！？"

    /** True if a sentence ends at [i]: `.`, `!` or `?` followed by whitespace or the end, or a CJK full stop. */
    fun isBoundary(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        if (c in CJK_ENDERS) return true
        return c in ENDERS && (i + 1 == text.length || text[i + 1].isWhitespace())
    }

    /** Sentences longer than this on either side of an index are cut (keeps scans linear on huge texts). */
    private const val MAX_REACH = 400

    /** [from, to) of the sentence containing [index], at most [MAX_REACH] characters each way. */
    fun bounds(text: CharSequence, index: Int): Pair<Int, Int> {
        var from = index
        while (from > 0 && index - from < MAX_REACH && !isBoundary(text, from - 1)) from--
        var to = index
        while (to < text.length && to - index < MAX_REACH && !isBoundary(text, to)) to++
        return from to minOf(text.length, to + 1)
    }

    /** Index of the last boundary in [from, to), or -1. */
    fun lastBoundary(text: CharSequence, from: Int, to: Int): Int {
        for (i in to - 1 downTo from) if (isBoundary(text, i)) return i
        return -1
    }

    /** True if a sentence ends anywhere in [from, to). */
    fun anyBoundary(text: CharSequence, from: Int, to: Int): Boolean = lastBoundary(text, from, to) >= 0
}
