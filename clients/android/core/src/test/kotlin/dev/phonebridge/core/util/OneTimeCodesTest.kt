package dev.phonebridge.core.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneTimeCodesTest {
    private val m = OneTimeCodes.MASK

    /** input -> expected output. */
    private val masked: List<Pair<String, String>> = listOf(
        // English, the documented formats.
        "Your code is 123456" to "Your code is $m",
        "G-123456 is your Google verification code." to "$m is your Google verification code.",
        "Use 12345678 to log in" to "Use $m to log in",
        "OTP: 9876" to "OTP: $m",
        "Your verification code is 123 456" to "Your verification code is $m",
        "Your verification code is 123-456." to "Your verification code is $m.",
        "Code: 4821 5739" to "Code: $m",
        "123456 is your Instagram code. Don't share it." to "$m is your Instagram code. Don't share it.",
        "FB-12345 is your Facebook confirmation code" to "$m is your Facebook confirmation code",
        "Your Amazon OTP is 482913. Do not share it with anyone." to "Your Amazon OTP is $m. Do not share it with anyone.",
        "Telegram code: 12345\n\nDo not give this code to anyone, even if they say they are from Telegram!" to
            "Telegram code: $m\n\nDo not give this code to anyone, even if they say they are from Telegram!",
        "Your one-time passcode is: 48291037" to "Your one-time passcode is: $m",
        "Your 2FA code: 482913" to "Your 2FA code: $m",
        "Your PIN is 4821" to "Your PIN is $m",
        "Your verification code:\n\n482913" to "Your verification code:\n\n$m",
        "Here is your login code.\n\n482913" to "Here is your login code.\n\n$m",
        "Your code is 012345" to "Your code is $m",
        "Use 482913 as Microsoft account security code" to "Use $m as Microsoft account security code",
        "Enter 4821 to confirm your sign-in" to "Enter $m to confirm your sign-in",
        "482913 - your Signal verification code" to "$m - your Signal verification code",
        "Your login code is a7Kp9Q" to "Your login code is $m",
        "Your Steam Guard code: F7K2N" to "Your Steam Guard code: $m",
        "Steam Guard code: KRBTF" to "Steam Guard code: $m",
        "YOUR OTP IS 482913. DO NOT SHARE" to "YOUR OTP IS $m. DO NOT SHARE",
        "Your verification code is [482913]" to "Your verification code is [$m]",
        "Your code is ABC-123" to "Your code is $m",
        "Use code 482913 to sign in" to "Use code $m to sign in",
        "482913\nYour verification code" to "$m\nYour verification code",
        "Call me on 054 123 4567, the door code is 4821" to "Call me on 054 123 4567, the door code is $m",
        "PIN-Code: 4821" to "PIN-Code: $m",
        "Ihre mTAN: 482913" to "Ihre mTAN: $m",
        "Votre code : 482 913" to "Votre code : $m",
        "Код: 4821" to "Код: $m",
        "您的取件码为 4821" to "您的取件码为 $m",
        // The same code elsewhere (links, WebOTP line) is masked too.
        "<#> Your WhatsApp code: 123-456\n\nYou can also tap on this link to verify your phone: v.whatsapp.com/123456\n\n" +
            "Don't share this code with others\n4sgLq1p5sV6" to
            "<#> Your WhatsApp code: $m\n\nYou can also tap on this link to verify your phone: v.whatsapp.com/$m\n\n" +
            "Don't share this code with others\n4sgLq1p5sV6",
        "Your Apple Account code is: 123456. Don't share it with anyone.\n\n@apple.com #123456 %apple.com" to
            "Your Apple Account code is: $m. Don't share it with anyone.\n\n@apple.com #$m %apple.com",
        "@example.com #A1B2C3" to "@example.com #$m",
        // Mixed with ordinary numbers: only the code goes.
        "Your OTP for transaction of INR 5,000.00 at AMAZON is 482913. Valid for 10 mins." to
            "Your OTP for transaction of INR 5,000.00 at AMAZON is $m. Valid for 10 mins.",
        "OTP 482913 for txn of Rs 1500 at Flipkart on 12-05-2024 10:45" to "OTP $m for txn of Rs 1500 at Flipkart on 12-05-2024 10:45",
        "Your verification code is 482913 and expires at 10:45" to "Your verification code is $m and expires at 10:45",
        "Your Uber code: 1234. Never share this code. Reply STOP ALL to 89203 to unsubscribe." to
            "Your Uber code: $m. Never share this code. Reply STOP ALL to 89203 to unsubscribe.",
        "Your code for 2024 tax filing is 482913" to "Your code for 2024 tax filing is $m",
        "Your verification code is 482913. Your order 55512 ships tomorrow." to
            "Your verification code is $m. Your order 55512 ships tomorrow.",
        "Enter the code we sent to 0541234567: 482913" to "Enter the code we sent to 0541234567: $m",
        "Your code is 2019" to "Your code is $m",
        // Hebrew.
        "קוד האימות שלך: 4821" to "קוד האימות שלך: $m",
        "קוד האימות שלך הוא 123456" to "קוד האימות שלך הוא $m",
        "1234 הוא קוד האימות שלך" to "$m הוא קוד האימות שלך",
        "הקוד שלך לכניסה: 4821" to "הקוד שלך לכניסה: $m",
        "סיסמה חד פעמית: 482913" to "סיסמה חד פעמית: $m",
        // Spanish, Portuguese, French, German, Italian, Dutch.
        "Tu código de verificación es 482913" to "Tu código de verificación es $m",
        "482913 es tu código de Instagram" to "$m es tu código de Instagram",
        "Tu clave dinámica es 4821" to "Tu clave dinámica es $m",
        "Seu código de verificação é 482913" to "Seu código de verificação é $m",
        "Sua senha temporária é 4821" to "Sua senha temporária é $m",
        "Votre code de vérification est 482913" to "Votre code de vérification est $m",
        "482913 est votre code de confirmation" to "$m est votre code de confirmation",
        "Ihr Bestätigungscode lautet 482913" to "Ihr Bestätigungscode lautet $m",
        "482913 ist dein Bestätigungscode" to "$m ist dein Bestätigungscode",
        "Ihre TAN lautet: 482913" to "Ihre TAN lautet: $m",
        "Il tuo codice di verifica è 482913" to "Il tuo codice di verifica è $m",
        "Uw verificatiecode is 482913" to "Uw verificatiecode is $m",
        // Russian, Turkish, Polish, Indonesian.
        "Ваш код подтверждения: 4821" to "Ваш код подтверждения: $m",
        "4821 — ваш код для входа" to "$m — ваш код для входа",
        "Никому не сообщайте код 4821" to "Никому не сообщайте код $m",
        "Ваш одноразовый пароль 482913" to "Ваш одноразовый пароль $m",
        "Doğrulama kodunuz: 482913" to "Doğrulama kodunuz: $m",
        "Twój kod weryfikacyjny to 482913" to "Twój kod weryfikacyjny to $m",
        "Kode verifikasi Anda adalah 482913" to "Kode verifikasi Anda adalah $m",
        // Arabic (also Arabic-Indic digits).
        "رمز التحقق الخاص بك هو 482913" to "رمز التحقق الخاص بك هو $m",
        "482913 هو رمز التحقق الخاص بك" to "$m هو رمز التحقق الخاص بك",
        "رمز التحقق: ٤٨٢٩١٣" to "رمز التحقق: $m",
        // Chinese, Japanese, Korean (also full-width digits).
        "您的验证码是482913，5分钟内有效。" to "您的验证码是$m，5分钟内有效。",
        "【京东】验证码：482913，您正在登录，请勿泄露。" to "【京东】验证码：$m，您正在登录，请勿泄露。",
        "驗證碼：482913" to "驗證碼：$m",
        "482913是您的验证码" to "${m}是您的验证码",
        "認証コード：４８２９１３" to "認証コード：$m",
        "[Web발신] 인증번호 [482913]을 입력해주세요." to "[Web발신] 인증번호 [$m]을 입력해주세요.",
    )

    /** Texts that must come back unchanged. */
    private val preserved: List<String> = listOf(
        // No code keywords at all.
        "Meeting at 14:30 on 12/05/2024",
        "Your order #123456 has shipped",
        "Order 123456 has shipped and will arrive on 2024-06-01",
        "Total: $1234.56",
        "Pay ₪1500 by Friday",
        "Call me at 054-123-4567",
        "Call me at +1 415 555 0123 or +972541234567",
        "My number is 0541234567",
        "Room 1204, building 7",
        "Flight LY315 departs at 0830",
        "Your balance is 1234 NIS",
        "I'll call you back in 5 minutes",
        "Your parcel 123456789 is out for delivery",
        "Your table for 4 is booked for 20:00 on 15.06.2024",
        "Version 2.0.1 released",
        "Your account balance is 12,345.67 USD",
        "Здравствуйте, ваш заказ 123456 отправлен",
        "订单号 123456 已发货",
        "Send 500 to 0541234567",
        "Reply STOP to 89203",
        "We used 1500 tokens today",
        // Code-like words that are not one-time codes.
        "Use promo code SAVE20 for 20% off",
        "Use code SAVE20 at checkout and get 20% off",
        "ZIP code 90210",
        "Postcode: 1012AB",
        "Código postal 28013",
        "Code postal 75001",
        "Error code 5003 occurred",
        "QR code 123456",
        "Используйте промокод SALE2024",
        "קודם כל תתקשר אליי ב-1500",
        "המיקוד שלי הוא 6100000",
        "קוד קופון 4821",
        "Booking code: K7Q2X9",
        "Your code review is at 14:30",
        "Status code 503, retry after 2024-06-01",
        "Source code at github.com/foo/bar1234",
        // Keywords, but the numbers are not codes (phone numbers, years, other sentences).
        "Call 0541234567 to get your code",
        "Enter the code we sent to 0541234567",
        "Your verification code was sent. Questions? Call 1800 555 0199.",
        "Didn't request a code? Call 054-1234567",
        "Your code expires in 10 minutes",
        "Your password was changed on 2024-05-01 at 10:45",
        "Room 1204 access code",
        "",
        "abc",
        OneTimeCodes.MASK,
        "Your code is $m",
    )

    @Test
    fun masksCodes() {
        val failures = masked.mapNotNull { (input, expected) ->
            val actual = OneTimeCodes.redact(input)
            if (actual == expected) null else "\n  in:   $input\n  want: $expected\n  got:  $actual"
        }
        assertTrue(failures.isEmpty(), failures.joinToString(""))
        masked.forEach { (input, _) -> assertTrue(OneTimeCodes.containsCode(input), input) }
    }

    @Test
    fun preservesOrdinaryText() {
        val failures = preserved.mapNotNull { input ->
            val actual = OneTimeCodes.redact(input)
            if (actual == input) null else "\n  in:  $input\n  got: $actual"
        }
        assertTrue(failures.isEmpty(), failures.joinToString(""))
        preserved.forEach { assertFalse(OneTimeCodes.containsCode(it), it) }
    }

    @Test
    fun redactionIsIdempotent() {
        masked.forEach { (input, expected) -> assertEquals(expected, OneTimeCodes.redact(OneTimeCodes.redact(input)), input) }
    }

    @Test
    fun masksEveryCodeInAList() {
        assertEquals("Your backup codes: $m, $m, $m", OneTimeCodes.redact("Your backup codes: 4821 5739, 1182 0937, 66120934"))
    }

    @Test
    fun longTextWithACodeFarAwayFromNumbers() {
        val filler = "Lorem ipsum dolor sit amet. ".repeat(200)
        val text = "${filler}Invoice 55512 total 1500 USD. ${filler}Your verification code is 482913. $filler"
        val redacted = OneTimeCodes.redact(text)
        assertTrue(redacted.contains("Invoice 55512 total 1500 USD."))
        assertTrue(redacted.contains("Your verification code is $m."))
        assertFalse(redacted.contains("482913"))
    }

    @Test
    fun hugeTextsStayFast() {
        val chunk = "code 1234 5678 verification 482913, order 55512 total 1500 USD on 2024-06-01 at 10:45. "
        val text = chunk.repeat(3_000) // ~270 KB, dense with keywords and numbers
        val started = System.nanoTime()
        val redacted = OneTimeCodes.redact(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(redacted.contains("482913"))
        assertTrue(redacted.contains("1500 USD on 2024-06-01 at 10:45."))
        assertTrue(elapsedMs < 5_000, "took $elapsedMs ms")
        val longWord = "a".repeat(200_000) + " code 4821"
        assertEquals("a".repeat(200_000) + " code $m", OneTimeCodes.redact(longWord))
    }

    @Test
    fun candidatesSkipNumberParts() {
        val values = OtpCandidates.find("054-123-4567 12.05.2024 14:30 $12.50 +972541234567 a@b.co x/1234 #5678").map { it.value }
        assertEquals(emptyList(), values)
        val found = OtpCandidates.find("482913, 123 456, G-123456, A7K9Q2, KRBTF, 10min, 2019, Hello, abcdef")
        assertEquals(listOf("482913", "123456", "123456", "A7K9Q2", "KRBTF", "2019"), found.map { it.value })
        assertEquals(listOf(false, false, false, false, true, true), found.map { it.weak })
    }

    @Test
    fun normalizeMapsDigitsAndCase() {
        assertEquals("482913", OtpCandidates.normalize("٤٨٢-٩١٣"))
        assertEquals("482913", OtpCandidates.normalize("４８２９１３"))
        assertEquals("A7K9", OtpCandidates.normalize("a7k-9"))
    }
}
