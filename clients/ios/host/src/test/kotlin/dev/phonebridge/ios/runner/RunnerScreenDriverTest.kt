package dev.phonebridge.ios.runner

import com.sun.net.httpserver.HttpServer
import dev.phonebridge.core.screen.ScreenException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RunnerScreenDriver] and [RunnerClient] against a fake runner speaking docs/IOS.md's protocol. */
class RunnerScreenDriverTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Triple<String, String?, JsonObject?>>()

    /** path → (status, body) */
    private val answers = mutableMapOf<String, Pair<Int, String>>()

    private val secret = "s3cret-for-tests"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val auth = ex.requestHeaders.getFirst("Authorization")
            requests += Triple(ex.requestMethod + " " + ex.requestURI.path, auth, body.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as JsonObject })
            val (status, text) = if (auth != "Bearer $secret") {
                401 to """{"error":"unauthorized"}"""
            } else {
                answers[ex.requestURI.path] ?: (200 to "{}")
            }
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}"

    private fun driver(secret: String = this.secret, candidates: List<String> = emptyList()) =
        RunnerScreenDriver(RunnerClient(url, secret)) { candidates }

    @Test
    fun `every request carries the bearer secret`() = runBlocking {
        answers["/status"] = 200 to """{"runner_version":"1.2","os":"ios","os_version":"18.1","model":"iPhone16,1","name":"Dana's iPhone","screen":{"width":393,"height":852,"scale":3},"locked":false}"""
        val status = driver().status()
        assertEquals("Dana's iPhone", status.name)
        assertEquals("18.1", status.osVersion)
        assertEquals(393, status.screen!!.width)
        assertEquals(3.0, status.scale)
        assertEquals(false, status.locked)
        driver().tap(10.0, 20.5)
        assertTrue(requests.all { it.second == "Bearer $secret" })
        val tap = requests.last()
        assertEquals("POST /tap", tap.first)
        assertEquals(20.5, tap.third!!["y"]!!.jsonPrimitive.content.toDouble())
        assertNull(tap.third!!["duration_ms"])
    }

    @Test
    fun `a wrong secret is reported as such`() = runBlocking {
        val e = assertFailsWith<ScreenException> { driver(secret = "other").status() }
        assertTrue("refused this host's secret" in e.message!!, e.message)
    }

    @Test
    fun `the runner's own error sentence reaches the agent`() = runBlocking {
        answers["/type"] = 409 to """{"error":"No text field has keyboard focus: tap a field first."}"""
        val e = assertFailsWith<ScreenException> { driver().typeText("hello") }
        assertEquals("No text field has keyboard focus: tap a field first.", e.message)
        answers["/launch"] = 500 to "oops"
        val e2 = assertFailsWith<ScreenException> { driver().launchApp("com.x.y") }
        assertTrue("HTTP 500" in e2.message!!, e2.message)
    }

    @Test
    fun `an unreachable runner says to start the host script`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<ScreenException> { RunnerScreenDriver(RunnerClient("http://127.0.0.1:$port", secret, connectTimeoutMs = 500)).status() }
        assertEquals(RunnerClient.UNREACHABLE, e.message)
    }

    @Test
    fun `snapshot - types capitalized, actionable from the type, secure values never kept`() = runBlocking {
        answers["/snapshot"] = 200 to """
            {"bundle_id":"com.example.mail","screen":{"width":393,"height":852},"keyboard":true,
             "root":{"type":"application","label":"Mail","frame":[0,0,393,852],"enabled":true,"selected":false,"focused":false,"children":[
               {"type":"button","label":"Compose","identifier":"compose","frame":[340.4,60,40,40],"enabled":true,"selected":false,"focused":false,"children":[]},
               {"type":"secureTextField","label":"Password","value":"hunter2","frame":[20,200,350,44],"enabled":true,"selected":false,"focused":true,"children":[]},
               {"type":"table","frame":[0,100,393,700],"enabled":true,"selected":false,"focused":false,"children":[]}
             ]},
             "overlays":[{"owner":"com.apple.springboard","root":{"type":"alert","label":"Allow notifications?","frame":[40,300,313,200],"enabled":true,"selected":false,"focused":false,"children":[]}}]}
        """.trimIndent()
        val snap = driver().snapshot()
        assertEquals("com.example.mail", snap.foregroundApp)
        assertTrue(snap.keyboardShown)
        val (compose, password, table) = snap.root!!.children
        assertEquals("Button", compose.type)
        assertTrue(compose.actionable)
        assertEquals(340, compose.bounds.x)
        assertEquals("compose", compose.identifier)
        assertTrue(password.secure && password.editable)
        assertNull(password.value)
        assertTrue(table.scrollable)
        assertFalse(table.actionable)
        assertEquals("Allow notifications?", snap.overlays.single().root.label)
        assertEquals(60, requests.last().third!!["max_depth"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `screenshot data is decoded`() = runBlocking {
        val png = byteArrayOf(1, 2, 3, 4)
        answers["/screenshot"] = 200 to """{"mime":"image/png","width":1179,"height":2556,"data":"${Base64.getEncoder().encodeToString(png)}"}"""
        val shot = driver().screenshot()
        assertEquals(png.toList(), shot.bytes.toList())
        assertEquals("image/png", shot.mimeType)
        assertEquals(2556, shot.height)
    }

    @Test
    fun `global actions are home and lock, others are refused with what exists`() = runBlocking {
        val d = driver()
        assertEquals(setOf("home", "lock_screen"), d.globalActions)
        d.globalAction("home")
        assertEquals("home", requests.last().third!!["name"]!!.jsonPrimitive.content)
        d.globalAction("lock_screen")
        assertEquals("POST /lock", requests.last().first)
        val e = assertFailsWith<ScreenException> { d.globalAction("back") }
        assertTrue("home, lock_screen" in e.message!! && "swipe from the left edge" in e.message!!, e.message)
    }

    @Test
    fun `foreground sends the installed apps as candidates`() = runBlocking {
        answers["/foreground"] = 200 to """{"bundle_id":"com.apple.springboard"}"""
        val front = driver(candidates = listOf("a.b", "c.d")).foreground(listOf("c.d"))
        assertEquals("com.apple.springboard", front)
        assertEquals("[\"c.d\",\"a.b\",\"com.apple.springboard\"]", requests.last().third!!["candidates"].toString())
    }
}
