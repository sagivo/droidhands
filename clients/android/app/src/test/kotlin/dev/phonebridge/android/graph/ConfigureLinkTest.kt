package dev.phonebridge.android.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigureLinkTest {
    @Test
    fun parsesEncodedRelayAndKey() {
        val link = ConfigureLink.parse("phonebridge://configure?relay=https%3A%2F%2Frelay.example.com%2F&key=s3cr%2Bet")
        assertNotNull(link)
        assertEquals("https://relay.example.com", link!!.relayUrl)
        assertEquals("s3cr+et", link.accessKey)
        assertEquals("relay.example.com", link.relayHost)
        assertTrue(link.isHttps)
    }

    @Test
    fun acceptsUnencodedValuesAndKeepsPlusLiterally() {
        val link = ConfigureLink.parse("phonebridge://configure?relay=http://192.168.1.5:8080/pb&key=a+b/c=")!!
        assertEquals("http://192.168.1.5:8080/pb", link.relayUrl)
        assertEquals("a+b/c=", link.accessKey)
        assertEquals("192.168.1.5:8080", link.relayHost)
        assertFalse(link.isHttps)
    }

    @Test
    fun keyIsOptional() {
        assertEquals("", ConfigureLink.parse("phonebridge://configure?relay=https://r.example.com")!!.accessKey)
        assertEquals("", ConfigureLink.parse("phonebridge://configure?relay=https://r.example.com&key=")!!.accessKey)
        assertEquals("", ConfigureLink.parse("phonebridge://configure/?relay=https://r.example.com&key=%20%20")!!.accessKey)
    }

    @Test
    fun schemeAndHostAreCaseInsensitiveAndUnknownParamsIgnored() {
        assertNotNull(ConfigureLink.parse("PhoneBridge://Configure?relay=https://r.example.com&utm_source=qr"))
    }

    @Test
    fun rejectsOtherLinks() {
        listOf(
            null,
            "",
            "   ",
            "https://configure?relay=https://r.example.com",
            "phonebridge://pair?relay=https://r.example.com",
            "phonebridge:configure?relay=https://r.example.com",
            "phonebridge://configure",
            "phonebridge://configure?key=abc",
            "phonebridge://configure?relay=",
            "phonebridge://configure?relay=ftp://r.example.com",
            "phonebridge://configure?relay=javascript:alert(1)",
            "phonebridge://configure?relay=relay.example.com",
            "phonebridge://configure?relay=https%3A%2F%2Fr.example.com%3Fx%3D1",
            "phonebridge://configure?relay=https://user:pw@r.example.com",
            "phonebridge://user@configure?relay=https://r.example.com",
            "phonebridge://configure:99?relay=https://r.example.com",
            "phonebridge://configure/other?relay=https://r.example.com",
            "phonebridge://configure?relay=https://r.example.com#frag",
            "phonebridge://configure?relay=https://r.example.com&relay=https://evil.example.com",
            "phonebridge://configure?relay=https://r.example.com&key=a&key=b",
            "phonebridge://configure?relay=https://r.example.com&key=%ZZ",
            "phonebridge://configure?relay=https://r.example.com&key=a%0Ab",
            "phonebridge://configure?relay=https://r.example.com&key=" + "k".repeat(600),
            "phonebridge://configure?relay=https://r.example.com bad",
        ).forEach { assertNull("expected rejection: $it", ConfigureLink.parse(it)) }
    }

    @Test
    fun errorsExplainTheProblem() {
        val missing = ConfigureLink.parseOrError("phonebridge://configure?key=abc")
        assertEquals(ConfigureLink.Parsed.Invalid("The setup link doesn't say which relay to use."), missing)
        val badRelay = ConfigureLink.parseOrError("phonebridge://configure?relay=ftp://r.example.com") as ConfigureLink.Parsed.Invalid
        assertTrue(badRelay.message, badRelay.message.startsWith("The relay address in the link is not usable"))
        val foreign = ConfigureLink.parseOrError("https://example.com") as ConfigureLink.Parsed.Invalid
        assertEquals("This is not a PhoneBridge setup link.", foreign.message)
    }

    @Test
    fun toStringHidesTheKey() {
        val link = ConfigureLink.parse("phonebridge://configure?relay=https://r.example.com&key=topsecret")!!
        assertFalse(link.toString().contains("topsecret"))
        assertEquals(link, ConfigureLink.parse("phonebridge://configure?key=topsecret&relay=https://r.example.com/"))
    }
}
