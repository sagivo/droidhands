package com.droidhands.core.demo

import com.droidhands.core.auth.AuthResult
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.auth.InMemoryTokenStore
import com.droidhands.core.auth.TokenManager
import com.droidhands.core.transport.PairingDecision
import com.droidhands.core.transport.PairingRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoPairingHandlerTest {
    private val tokens = TokenManager(InMemoryTokenStore(), DeviceIdentity(ByteArray(32) { it.toByte() }).deviceId)
    private val request = PairingRequest("p_1", "Claude", "claude.ai", "K7Q2", null, null, Long.MAX_VALUE)

    @Test
    fun approveIssuesARealPairingToken() = runTest {
        val handler = assertNotNull(demoPairingHandler("approve", tokens, ConfirmPolicy.NEVER))
        val approved = assertIs<PairingDecision.Approved>(handler.onPairRequest(request))
        val record = tokens.tokens.value.single()
        assertEquals(approved.tokenId, record.id)
        assertEquals("Claude", record.name)
        assertEquals("pairing", record.origin)
        assertEquals("claude.ai", record.clientHost)
        assertIs<AuthResult.Ok>(tokens.authenticate(approved.accessToken))

        handler.onPairFinished("p_1", approved.tokenId, completed = true)
        assertEquals(1, tokens.tokens.value.size)
        handler.onPairFinished("p_1", approved.tokenId, completed = false)
        assertTrue(tokens.tokens.value.isEmpty(), "abandoned tokens are deleted")
    }

    @Test
    fun denyAndIgnore() = runTest {
        assertEquals(PairingDecision.Denied("denied"), demoPairingHandler("deny", tokens, ConfirmPolicy.NEVER)!!.onPairRequest(request))
        val ignoring = demoPairingHandler("ignore", tokens, ConfirmPolicy.NEVER)!!
        assertNull(withTimeoutOrNull(1_000) { ignoring.onPairRequest(request) })
        assertTrue(tokens.tokens.value.isEmpty())
        assertNull(demoPairingHandler(null, tokens, ConfirmPolicy.NEVER))
        assertNull(demoPairingHandler("maybe", tokens, ConfirmPolicy.NEVER))
    }
}
