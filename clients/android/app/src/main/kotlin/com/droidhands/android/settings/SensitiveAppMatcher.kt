package com.droidhands.android.settings

import java.text.Normalizer
import java.util.Locale

/** Why an app is considered too sensitive for agents by default. */
enum class SensitiveCategory(val label: String) {
    BANKING("Banking"),
    PAYMENTS("Payments"),
    CRYPTO("Crypto"),
    PASSWORDS("Password manager"),
    AUTHENTICATOR("Authenticator"),
}

/**
 * Decides whether an installed app is a banking, payment, crypto, password-manager or
 * authenticator app (pure, unit-tested). Used to pre-select the global never-allow list on first
 * run and to suggest apps in the security settings.
 *
 * Two layers:
 * 1. [KNOWN_PACKAGES]: exact package names of well-known apps worldwide (including Israeli banks).
 * 2. Heuristics on the label and package name, in several languages ("bank", "wallet", "pay",
 *    "crypto", "authenticator", "password", "vault", "otp", "בנק", "банк", "銀行"…). Latin keywords
 *    must start a word, so "Display" or "Spotify" never match "pay".
 *
 * False positives are cheap (the user can unblock an app); false negatives leave an app reachable,
 * so the heuristics lean towards matching.
 */
object SensitiveAppMatcher {
    /** The category of [packageName] / [label], or null if the app doesn't look sensitive. */
    fun match(packageName: String, label: String?): SensitiveCategory? {
        KNOWN_PACKAGES[packageName]?.let { return it }
        return label?.let(::matchLabel) ?: matchPackage(packageName)
    }

    /** True if [match] returns a category. */
    fun isSensitive(packageName: String, label: String?): Boolean = match(packageName, label) != null

    private fun matchLabel(label: String): SensitiveCategory? {
        val words = words(label)
        val phrase = words.joinToString(" ", prefix = " ")
        // Order matters: "Bitwarden Authenticator" is an authenticator, "Crypto Wallet" is crypto.
        for ((category, keywords) in LABEL_KEYWORDS) {
            if (keywords.any { keyword -> matchesWords(keyword, words, phrase) }) return category
        }
        val lower = label.lowercase(Locale.ROOT)
        for ((category, fragments) in LABEL_FRAGMENTS) {
            if (fragments.any { it in lower }) return category
        }
        return null
    }

    private fun matchPackage(packageName: String): SensitiveCategory? {
        val segments = packageName.lowercase(Locale.ROOT).split('.', '_')
        for ((category, fragments) in PACKAGE_FRAGMENTS) {
            if (segments.any { segment -> fragments.any { it in segment } }) return category
        }
        for ((category, exact) in PACKAGE_SEGMENTS) {
            if (segments.any { it in exact }) return category
        }
        if (segments.any { it.startsWith("pay") || it.endsWith("pay") }) return SensitiveCategory.PAYMENTS
        return null
    }

    /**
     * A single-word [keyword] must start a word (ignoring leading digits, so "1Password" matches
     * "password"); a multi-word keyword must appear as a phrase starting at a word boundary.
     */
    private fun matchesWords(keyword: String, words: List<String>, phrase: String): Boolean =
        if (' ' in keyword) {
            " $keyword" in phrase
        } else {
            words.any { word -> word.startsWith(keyword) || word.trimStart(Char::isDigit).startsWith(keyword) }
        }

    /**
     * Lowercase words of [text], also split at camelCase boundaries ("CommBank" → "comm", "bank")
     * without diacritics. Letters and digits stay together ("2FAS" → "2fas").
     */
    internal fun words(text: String): List<String> {
        // "Crédit Agricole" → "Credit Agricole"; also drops Hebrew vowel points.
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        val spaced = plain
            .replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})"), " ")
            .replace(Regex("(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})"), " ")
        return spaced.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Latin-script keywords (without diacritics) matched at the start of a word of the label (or as a phrase). */
    private val LABEL_KEYWORDS: List<Pair<SensitiveCategory, List<String>>> = listOf(
        SensitiveCategory.AUTHENTICATOR to listOf("authenticat", "otp", "2fa", "mfa", "totp"),
        SensitiveCategory.PASSWORDS to listOf("password", "passwort", "keepass", "vault", "keychain", "contrasena", "mot de passe"),
        SensitiveCategory.CRYPTO to listOf("crypto", "bitcoin", "ethereum", "blockchain", "coinbase", "binance", "metamask"),
        SensitiveCategory.BANKING to listOf(
            "bank", "banco", "banque", "banca", "sparkasse", "volksbank", "raiffeisen", "credit union", "credit agricole",
        ),
        SensitiveCategory.PAYMENTS to listOf("wallet", "pay", "venmo", "zelle", "remit", "money transfer"),
    )

    /** Non-Latin keywords (no reliable word boundaries, e.g. Hebrew prefixes "ה", "ב"), matched anywhere. */
    private val LABEL_FRAGMENTS: List<Pair<SensitiveCategory, List<String>>> = listOf(
        SensitiveCategory.AUTHENTICATOR to listOf("מאמת", "аутентификатор", "認証"),
        SensitiveCategory.PASSWORDS to listOf("סיסמ", "пароль", "パスワード", "密码"),
        SensitiveCategory.CRYPTO to listOf("קריפטו", "ביטקוין", "крипто"),
        SensitiveCategory.BANKING to listOf(
            // Hebrew: "bank", and the big Israeli banks' names.
            "בנק", "לאומי", "הפועלים", "דיסקונט", "מזרחי", "טפחות", "הבינלאומי", "אוצר החייל", "מרכנתיל", "יהב",
            "банк", "بنك", "مصرف", "बैंक", "銀行", "银行", "은행", "ธนาคาร",
        ),
        SensitiveCategory.PAYMENTS to listOf("ארנק", "תשלומ", "פייבוקס", "кошел", "محفظة"),
    )

    /** Keywords matched anywhere inside a package-name segment ("com.commbank.netbank"). */
    private val PACKAGE_FRAGMENTS: List<Pair<SensitiveCategory, List<String>>> = listOf(
        SensitiveCategory.AUTHENTICATOR to listOf("authenticator", "freeotp", "andotp"),
        SensitiveCategory.PASSWORDS to listOf("password", "passwd", "keepass", "passwordmanager"),
        SensitiveCategory.CRYPTO to listOf("crypto", "bitcoin", "blockchain", "ethereum"),
        SensitiveCategory.BANKING to listOf("bank"),
        SensitiveCategory.PAYMENTS to listOf("wallet", "payment"),
    )

    /** Short keywords that only count as a whole package segment ("otp" must not match "hotpepper"). */
    private val PACKAGE_SEGMENTS: List<Pair<SensitiveCategory, Set<String>>> = listOf(
        SensitiveCategory.AUTHENTICATOR to setOf("otp", "totp", "2fa", "mfa", "authy"),
        SensitiveCategory.PASSWORDS to setOf("vault"),
    )

    /** Exact package names of well-known sensitive apps. */
    val KNOWN_PACKAGES: Map<String, SensitiveCategory> = buildMap {
        fun add(category: SensitiveCategory, vararg packages: String) = packages.forEach { put(it, category) }

        // Israel: Leumi, Hapoalim, Discount, Mizrahi-Tefahot, FIBI group, digital banks, credit cards.
        add(
            SensitiveCategory.BANKING,
            "com.leumi.leumiwallet", "com.leumi.leumicard", "il.co.leumi", "com.pepper.ldb",
            "com.ideomobile.hapoalim", "com.poalim.businessmobile", "com.poalim.wonder",
            "com.discount.discountmobile", "com.ideomobile.discount", "com.discountbank.mobile", "com.mercantile.mobile",
            "com.MizrahiTefahot.nh", "com.mizrahitefahot.nh", "com.mizrahi.tefahot",
            "com.fibi.nativeapp", "com.otsar.nativeapp", "com.massad.nativeapp", "com.pagi.nativeapp", "com.yahav.nativeapp",
            "com.onezero.bank", "com.ideomobile.leumicard", "com.isracard.android", "il.co.isracard", "com.onoapps.cal4u",
        )
        // United States.
        add(
            SensitiveCategory.BANKING,
            "com.chase.sig.android", "com.infonow.bofa", "com.wf.wellsfargomobile", "com.citi.citimobile",
            "com.konylabs.capitalone", "com.usbank.mobilebanking", "com.pnc.ecommerce.mobile", "com.discoverfinancial.mobile",
            "com.americanexpress.android.acctsvcs.us", "com.schwab.mobile", "com.fidelity.android", "com.robinhood.android",
            "com.ally.MobileBanking", "com.sofi.mobile", "com.tdbank", "com.usaa.mobile.android.usaa", "com.navyfederal.android",
            "com.onedebit.chime", "com.etrade.mobilepro.activity", "com.vanguard",
        )
        // United Kingdom, Europe.
        add(
            SensitiveCategory.BANKING,
            "com.barclays.android.barclaysmobilebanking", "uk.co.hsbc.hsbcukmobilebanking", "com.grppl.android.shell.CMBlloydsTSB73",
            "com.grppl.android.shell.halifax", "com.rbs.mobile.android.natwest", "uk.co.santander.santanderUK",
            "co.uk.Nationwide.Mobile", "co.uk.getmondo", "com.starlingbank.android", "de.number26.android", "com.bunq.android",
            "com.ing.mobile", "de.ing_diba.kontostand", "com.db.pwcc.dbmobile", "de.commerzbanking.mobil",
            "com.starfinanz.smob.android.sfinanzstatus", "de.dkb.portalapp", "net.bnpparibas.mescomptes",
            "mobi.societegenerale.mobile.lappli", "fr.creditagricole.androidapp", "com.bbva.bbvacontigo", "es.bancosantander.apps",
            "es.lacaixa.mobile.android.newwapicon", "com.latuabancaperandroid", "nl.rabomobiel", "com.abnamro.nl.mobile.payments",
            "ch.ubs.mobilebanking", "com.csg.cs.dnmb",
        )
        // Canada, Australia, India, Latin America.
        add(
            SensitiveCategory.BANKING,
            "com.rbc.mobile.android", "com.td", "com.scotiabank.banking", "com.bmo.mobile", "com.cibc.android.mobi",
            "com.commbank.netbank", "org.westpac.bank", "com.anz.android.gomoney", "au.com.nab.mobile",
            "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.axis.mobile",
            "com.nu.production", "com.itau", "com.bradesco", "br.com.bb.android", "br.com.intermedium",
        )
        add(
            SensitiveCategory.PAYMENTS,
            "com.bnhp.payments.paymentsapp", "com.payboxapp", "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash",
            "com.revolut.revolut", "com.transferwise.android", "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user", "com.samsung.android.spay", "com.samsung.android.samsungpay.gear",
            "com.zellepay.zelle", "com.myklarnamobile", "com.afterpaymobile", "net.one97.paytm", "com.phonepe.app",
            "com.eg.android.AlipayGphone", "com.payoneer.android", "com.moneybookers.skrillpayments",
            "com.westernunion.android.mtapp", "com.remitly.androidapp", "com.imaginecurve.curve.prd", "com.mercadopago.wallet",
        )
        add(
            SensitiveCategory.CRYPTO,
            "com.coinbase.android", "org.toshi", "com.binance.dev", "com.kraken.trade", "com.kraken.invest.app", "io.metamask",
            "com.wallet.crypto.trustapp", "exodusmovement.exodus", "co.mona.android", "com.ledger.live", "piuk.blockchain.android",
            "com.bybit.app", "com.okinc.okex.gp", "com.kubi.kucoin", "com.bitpanda.bitpanda", "com.gemini.android.app",
            "app.phantom", "org.electrum.electrum", "io.bluewallet.bluewallet", "io.trezor.suite", "io.atomicwallet",
        )
        add(
            SensitiveCategory.PASSWORDS,
            "com.onepassword.android", "com.agilebits.onepassword", "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.dashlane",
            "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre", "com.kunzisoft.keepass.pro",
            "keepass2android.keepass2android", "keepass2android.keepass2android_nonet", "com.android.keepass",
            "proton.android.pass", "com.callpod.android_apps.keeper", "com.nordpass.android.app.password.manager",
            "io.enpass.app", "com.siber.roboform", "com.samsung.android.samsungpass",
        )
        add(
            SensitiveCategory.AUTHENTICATOR,
            "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.beemdevelopment.aegis",
            "com.twofasapp", "com.duosecurity.duomobile", "org.fedorahosted.freeotp", "org.liberty.android.freeotpplus",
            "org.shadowice.flocke.andotp", "com.okta.android.auth", "com.lastpass.authenticator", "com.yubico.yubioath",
            "io.ente.auth", "proton.android.authenticator", "com.bitwarden.authenticator", "com.verisign.mvip.main",
        )
    }
}
