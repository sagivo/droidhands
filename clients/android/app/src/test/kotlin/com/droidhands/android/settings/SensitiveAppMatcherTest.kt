package com.droidhands.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveAppMatcherTest {
    private fun assertCategory(expected: SensitiveCategory, pkg: String, label: String?) =
        assertEquals("$pkg / $label", expected, SensitiveAppMatcher.match(pkg, label))

    private fun assertNotSensitive(pkg: String, label: String?) = assertNull("$pkg / $label", SensitiveAppMatcher.match(pkg, label))

    @Test
    fun knownPackagesMatchWhateverTheirLabel() {
        assertCategory(SensitiveCategory.BANKING, "com.ideomobile.hapoalim", "Poalim")
        assertCategory(SensitiveCategory.BANKING, "com.leumi.leumiwallet", null)
        assertCategory(SensitiveCategory.BANKING, "com.MizrahiTefahot.nh", "מזרחי טפחות")
        assertCategory(SensitiveCategory.PAYMENTS, "com.bnhp.payments.paymentsapp", "bit")
        assertCategory(SensitiveCategory.PAYMENTS, "com.payboxapp", "PayBox")
        assertCategory(SensitiveCategory.PAYMENTS, "com.squareup.cash", "Cash App")
        assertCategory(SensitiveCategory.PAYMENTS, "com.revolut.revolut", "Revolut")
        assertCategory(SensitiveCategory.CRYPTO, "io.metamask", "MetaMask")
        assertCategory(SensitiveCategory.PASSWORDS, "com.x8bit.bitwarden", "Bitwarden")
        assertCategory(SensitiveCategory.PASSWORDS, "proton.android.pass", "Proton Pass")
        assertCategory(SensitiveCategory.AUTHENTICATOR, "com.google.android.apps.authenticator2", "Authenticator")
        assertCategory(SensitiveCategory.AUTHENTICATOR, "com.duosecurity.duomobile", "Duo Mobile")
    }

    @Test
    fun knownListCoversEveryRequiredFamily() {
        val known = SensitiveAppMatcher.KNOWN_PACKAGES
        listOf(
            "com.paypal.android.p2pmobile", "com.venmo", "com.transferwise.android", "com.google.android.apps.walletnfcrel",
            "com.coinbase.android", "com.onepassword.android", "com.lastpass.lpandroid", "com.dashlane",
            "com.kunzisoft.keepass.free", "keepass2android.keepass2android", "com.azure.authenticator", "com.authy.authy",
            "com.beemdevelopment.aegis", "com.twofasapp", "com.fibi.nativeapp", "com.discount.discountmobile",
        ).forEach { assertTrue(it, it in known) }
        // Entries must be real package names, or they could never match.
        known.keys.forEach { assertTrue(it, SettingsCodec.isPackageName(it)) }
    }

    @Test
    fun labelHeuristicsInEnglish() {
        assertCategory(SensitiveCategory.BANKING, "com.example.retail", "First Example Bank")
        assertCategory(SensitiveCategory.BANKING, "au.example.x", "CommBank")
        assertCategory(SensitiveCategory.BANKING, "com.example.cu", "Example Credit Union")
        assertCategory(SensitiveCategory.PAYMENTS, "com.example.w", "My Wallet")
        assertCategory(SensitiveCategory.PAYMENTS, "com.example.p", "PayByPhone")
        assertCategory(SensitiveCategory.CRYPTO, "com.example.c", "Crypto Exchange")
        assertCategory(SensitiveCategory.PASSWORDS, "com.example.pm", "Password Safe")
        assertCategory(SensitiveCategory.PASSWORDS, "com.example.one", "1Password 8")
        assertCategory(SensitiveCategory.PASSWORDS, "com.example.v", "Photo Vault")
        assertCategory(SensitiveCategory.AUTHENTICATOR, "com.example.a", "Bitwarden Authenticator")
        assertCategory(SensitiveCategory.AUTHENTICATOR, "com.example.t", "2FAS Auth")
        assertCategory(SensitiveCategory.AUTHENTICATOR, "com.example.o", "OTP Manager")
    }

    @Test
    fun labelHeuristicsInOtherLanguages() {
        assertCategory(SensitiveCategory.BANKING, "com.example.il", "בנק ירושלים")
        assertCategory(SensitiveCategory.BANKING, "com.example.il2", "הבנק הבינלאומי")
        assertCategory(SensitiveCategory.BANKING, "com.example.il3", "לאומי")
        assertCategory(SensitiveCategory.BANKING, "com.example.il4", "דיסקונט")
        assertCategory(SensitiveCategory.BANKING, "com.example.fr", "Crédit Agricole")
        assertCategory(SensitiveCategory.BANKING, "com.example.es", "Banco Ejemplo")
        assertCategory(SensitiveCategory.BANKING, "com.example.de", "Sparkasse")
        assertCategory(SensitiveCategory.BANKING, "com.example.ru", "Сбербанк")
        assertCategory(SensitiveCategory.BANKING, "com.example.jp", "みずほ銀行")
        assertCategory(SensitiveCategory.PAYMENTS, "com.example.il5", "ארנק דיגיטלי")
        assertCategory(SensitiveCategory.PASSWORDS, "com.example.il6", "מנהל סיסמאות")
    }

    @Test
    fun packageHeuristicsWhenTheLabelSaysNothing() {
        assertCategory(SensitiveCategory.BANKING, "com.example.netbank", "Example")
        assertCategory(SensitiveCategory.PAYMENTS, "com.example.walletapp", "Example")
        assertCategory(SensitiveCategory.PAYMENTS, "com.example.paypoint", "Example")
        assertCategory(SensitiveCategory.PAYMENTS, "com.example.gpay", "Example")
        assertCategory(SensitiveCategory.AUTHENTICATOR, "com.example.otp", "Example")
        assertCategory(SensitiveCategory.PASSWORDS, "org.example.keepassxc", "Example")
        assertCategory(SensitiveCategory.CRYPTO, "com.example.bitcoinwallet", "Example")
    }

    @Test
    fun ordinaryAppsAreNotSensitive() {
        assertNotSensitive("com.whatsapp", "WhatsApp")
        assertNotSensitive("com.google.android.gm", "Gmail")
        assertNotSensitive("com.android.chrome", "Chrome")
        assertNotSensitive("com.spotify.music", "Spotify")
        assertNotSensitive("com.google.android.apps.photos", "Photos")
        assertNotSensitive("com.android.settings", "Settings")
        assertNotSensitive("com.android.vending", "Google Play Store")
        assertNotSensitive("com.example.display", "Display Tuner")
        assertNotSensitive("com.example.wallpapers", "Wallpapers")
        assertNotSensitive("jp.co.hotpepper", "Hot Pepper")
        assertNotSensitive("com.example.airplay", "AirPlay Receiver")
        assertNotSensitive("com.droidhands.android", "DroidHands")
        assertNotSensitive("com.waze", "Waze")
    }

    @Test
    fun wordsSplitCamelCaseAndKeepDigitsWithLetters() {
        assertEquals(listOf("comm", "bank"), SensitiveAppMatcher.words("CommBank"))
        assertEquals(listOf("pay", "by", "phone"), SensitiveAppMatcher.words("PayByPhone"))
        assertEquals(listOf("2fas", "auth"), SensitiveAppMatcher.words("2FAS Auth"))
        assertEquals(listOf("otp", "auth"), SensitiveAppMatcher.words("OTPAuth"))
        assertEquals(listOf("credit", "agricole"), SensitiveAppMatcher.words("Crédit-Agricole"))
    }

    @Test
    fun securityDefaultsApplyOnlyOnce() {
        val first = SecurityDefaults.apply(BridgeSettings(neverAllowApps = setOf("com.user.choice")), listOf("com.venmo"))
        assertEquals(setOf("com.user.choice", "com.venmo"), first.neverAllowApps)
        assertTrue(first.securityDefaultsApplied)

        // The user later unblocked Venmo; a later scan must not re-add it.
        val edited = first.copy(neverAllowApps = setOf("com.user.choice"))
        assertEquals(edited, SecurityDefaults.apply(edited, listOf("com.venmo", "com.squareup.cash")))
    }

    @Test
    fun securityDefaultsWithNothingDetectedStillMarkApplied() {
        val applied = SecurityDefaults.apply(BridgeSettings(), emptyList())
        assertTrue(applied.securityDefaultsApplied)
        assertTrue(applied.neverAllowApps.isEmpty())
    }
}
