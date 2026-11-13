package dev.phonebridge.android.confirm

import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.tools.Risk
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmationTextTest {
    private val request = ConfirmationRequest(
        id = "r1",
        tokenName = "Claude",
        toolName = "whatsapp_send",
        toolTitle = "Send WhatsApp message",
        summary = "Send WhatsApp to Tania Cohen (+972541234567): \"running late\"",
        risk = Risk.SENSITIVE,
        transport = "relay",
    )

    @Test
    fun title() {
        assertEquals("Claude wants to: Send WhatsApp message", ConfirmationText.title(request))
        assertEquals("An agent wants to: Send WhatsApp message", ConfirmationText.title(request.copy(tokenName = "  ")))
        assertEquals("Claude wants to: whatsapp_send", ConfirmationText.title(request.copy(toolTitle = "")))
    }

    @Test
    fun notificationBodyStartsWithTheExactAction() {
        assertEquals(
            "Send WhatsApp to Tania Cohen (+972541234567): \"running late\"\n\nSensitive: acts on your behalf · via relay",
            ConfirmationText.notificationBody(request),
        )
        assertEquals(
            "Open app\n\nChanges something on this phone · via local connection",
            ConfirmationText.notificationBody(request.copy(summary = " Open app ", risk = Risk.ACTION, transport = "local")),
        )
    }

    @Test
    fun labels() {
        assertEquals("Read only", ConfirmationText.riskLabel(Risk.READ))
        assertEquals("via usb", ConfirmationText.transportLabel("usb"))
    }

    @Test
    fun secondsRemainingRoundsUpAndNeverGoesNegative() {
        assertEquals(60, ConfirmationText.secondsRemaining(expiresAt = 60_000, now = 0))
        assertEquals(60, ConfirmationText.secondsRemaining(expiresAt = 60_000, now = 999))
        assertEquals(59, ConfirmationText.secondsRemaining(expiresAt = 60_000, now = 1_000))
        assertEquals(1, ConfirmationText.secondsRemaining(expiresAt = 60_000, now = 59_999))
        assertEquals(0, ConfirmationText.secondsRemaining(expiresAt = 60_000, now = 60_000))
        assertEquals(0, ConfirmationText.secondsRemaining(expiresAt = 60_000, now = 90_000))
    }

    @Test
    fun countdownLabel() {
        assertEquals("Expires in 42 s", ConfirmationText.countdownLabel(42))
        assertEquals("Expired", ConfirmationText.countdownLabel(0))
    }
}
