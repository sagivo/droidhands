package com.droidhands.core.transport

import com.droidhands.core.mcp.TransportRequest
import com.droidhands.core.mcp.TransportResponse
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalHttpServerTest {
    private val received = CopyOnWriteArrayList<TransportRequest>()

    @Volatile private var respond: suspend (TransportRequest) -> TransportResponse = { req ->
        TransportResponse(200, """{"echo":${req.body.length}}""", mapOf("content-type" to "application/json"))
    }

    private val servers = mutableListOf<LocalHttpServer>()

    private fun server(allowedOrigins: Set<String> = emptySet(), maxBodyBytes: Int = 1_048_576): LocalHttpServer =
        LocalHttpServer(
            port = 0,
            handler = { req ->
                received += req
                respond(req)
            },
            allowedOrigins = allowedOrigins,
            maxBodyBytes = maxBodyBytes,
        ).also {
            it.start()
            servers += it
        }

    @After
    fun tearDown() = servers.forEach { it.stop() }

    private class RawResponse(val status: Int, val headers: Map<String, String>, val body: String, val raw: String)

    private fun parse(raw: String): RawResponse {
        val headEnd = raw.indexOf("\r\n\r\n")
        require(headEnd >= 0) { "no header terminator in: $raw" }
        val lines = raw.substring(0, headEnd).split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return RawResponse(status, headers, raw.substring(headEnd + 4), raw)
    }

    private fun exchange(port: Int, request: String): RawResponse = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().apply {
            write(request.toByteArray(Charsets.UTF_8))
            flush()
        }
        s.shutdownOutput() // Like a client that has sent everything; truncated bodies then hit EOF.
        parse(s.getInputStream().readBytes().toString(Charsets.UTF_8))
    }

    private fun post(body: String, extraHeaders: String = "", path: String = "/mcp") =
        "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${body.toByteArray().size}\r\n$extraHeaders\r\n$body"

    @Test
    fun postMcpReachesHandlerWithMetadata() {
        val s = server()
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping","note":"héllo"}"""
        val response = exchange(
            s.boundPort,
            post(body, "Authorization: Bearer pb1.x\r\nMcp-Protocol-Version: 2025-06-18\r\nUser-Agent: curl/8\r\nContent-Type: application/json\r\n"),
        )
        assertEquals(200, response.status)
        assertEquals("""{"echo":${body.length}}""", response.body)
        assertEquals(response.body.toByteArray().size.toString(), response.headers["content-length"])
        assertEquals("close", response.headers["connection"])
        assertEquals("no-store", response.headers["cache-control"])
        assertEquals("application/json", response.headers["content-type"])
        val req = received.single()
        assertEquals("Bearer pb1.x", req.authorization)
        assertEquals(body, req.body)
        assertEquals("local", req.transport)
        assertEquals("2025-06-18", req.protocolVersionHeader)
        assertEquals("curl/8", req.userAgent)
        assertEquals("127.0.0.1", req.remoteAddress)
        assertTrue(s.isRunning)
    }

    @Test
    fun worksWithARealHttpClient() {
        val s = server()
        respond = { TransportResponse(401, """{"error":1}""", mapOf("content-type" to "application/json", "www-authenticate" to "Bearer realm=\"droidhands\"")) }
        val client = OkHttpClient()
        val response = client.newCall(
            Request.Builder().url("http://127.0.0.1:${s.boundPort}/mcp?x=1")
                .post("{\"a\":1}".toRequestBody("application/json".toMediaType())).build(),
        ).execute()
        response.use {
            assertEquals(401, it.code)
            assertEquals("Bearer realm=\"droidhands\"", it.header("WWW-Authenticate"))
            assertEquals("""{"error":1}""", it.body!!.string())
        }
        assertEquals("{\"a\":1}", received.single().body)
    }

    @Test
    fun chunkedBody() {
        val s = server()
        val request = "POST /mcp HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5\r\nhello\r\n" + "7;ext=1\r\n, world\r\n" + "0\r\nX-Trailer: y\r\n\r\n"
        val response = exchange(s.boundPort, request)
        assertEquals(200, response.status)
        assertEquals("hello, world", received.single().body)
    }

    @Test
    fun expect100ContinueIsHonoured() {
        val s = server()
        val body = "x".repeat(5_000)
        Socket("127.0.0.1", s.boundPort).use { sock ->
            sock.soTimeout = 10_000
            val out = sock.getOutputStream()
            out.write("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.length}\r\nExpect: 100-continue\r\n\r\n".toByteArray())
            out.flush()
            val input = sock.getInputStream()
            assertEquals("HTTP/1.1 100 Continue\r\n\r\n", readExactly(input, 25))
            out.write(body.toByteArray())
            out.flush()
            val response = parse(input.readBytes().toString(Charsets.UTF_8))
            assertEquals(200, response.status)
        }
        assertEquals(body, received.single().body)
    }

    @Test
    fun curlStyleLargePostWithOkHttp() {
        val s = server()
        val body = "{\"data\":\"" + "y".repeat(200_000) + "\"}"
        val response = OkHttpClient().newCall(
            Request.Builder().url("http://127.0.0.1:${s.boundPort}/mcp").post(body.toRequestBody("application/json".toMediaType())).build(),
        ).execute()
        response.use { assertEquals(200, it.code) }
        assertEquals(body, received.single().body)
    }

    @Test
    fun tooLargeBodiesGet413() {
        val s = server(maxBodyBytes = 100)
        Socket("127.0.0.1", s.boundPort).use { sock ->
            sock.soTimeout = 10_000
            sock.getOutputStream().write("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 101\r\nExpect: 100-continue\r\n\r\n".toByteArray())
            val response = parse(sock.getInputStream().readBytes().toString(Charsets.UTF_8))
            assertEquals(413, response.status)
            assertFalse(response.raw.contains("100 Continue"))
        }
        val chunked = exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n64\r\n${"a".repeat(100)}\r\n1\r\nb\r\n0\r\n\r\n")
        assertEquals(413, chunked.status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun originAllowlist() {
        val s = server(allowedOrigins = setOf("http://localhost:3000"))
        assertEquals(403, exchange(s.boundPort, post("{}", "Origin: https://evil.example\r\n")).status)
        assertEquals(403, exchange(s.boundPort, post("{}", "Origin: null\r\n")).status)
        assertEquals(403, exchange(s.boundPort, "GET /health HTTP/1.1\r\nOrigin: https://evil.example\r\n\r\n").status)
        assertEquals(200, exchange(s.boundPort, post("{}", "Origin: http://localhost:3000\r\n")).status)
        assertEquals(200, exchange(s.boundPort, post("{}", "Origin: HTTP://LOCALHOST:3000/\r\n")).status)
        assertEquals(200, exchange(s.boundPort, post("{}")).status)
        assertEquals(3, received.size)
    }

    @Test
    fun routing() {
        val s = server()
        val get = exchange(s.boundPort, "GET /mcp HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(405, get.status)
        assertEquals("POST", get.headers["allow"])
        assertEquals(405, exchange(s.boundPort, "DELETE /mcp HTTP/1.1\r\nHost: x\r\n\r\n").status)
        val health = exchange(s.boundPort, "GET /health HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, health.status)
        assertEquals("""{"status":"ok"}""", health.body)
        assertEquals(404, exchange(s.boundPort, "GET / HTTP/1.1\r\nHost: x\r\n\r\n").status)
        assertEquals(404, exchange(s.boundPort, post("{}", path = "/other")).status)
        assertEquals(200, exchange(s.boundPort, post("{}", path = "/mcp?session=1")).status)
        assertEquals(200, exchange(s.boundPort, post("{}", path = "/mcp/")).status)
        assertEquals(200, exchange(s.boundPort, post("{}", path = "http://127.0.0.1/mcp")).status)
        assertEquals(3, received.size)
    }

    @Test
    fun malformedRequests() {
        val s = server()
        assertEquals(400, exchange(s.boundPort, "NONSENSE\r\n\r\n").status)
        assertEquals(400, exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nBad Header\r\n\r\n").status)
        assertEquals(400, exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nContent-Length: abc\r\n\r\n").status)
        assertEquals(400, exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}").status)
        assertEquals(400, exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}").status)
        assertEquals(501, exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nTransfer-Encoding: gzip\r\n\r\n").status)
        assertEquals(400, exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n").status)
        assertEquals(505, exchange(s.boundPort, "POST /mcp HTTP/2.0\r\n\r\n").status)
        assertEquals(400, exchange(s.boundPort, "POST /mcp HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort").status, "truncated body")
        assertEquals(431, exchange(s.boundPort, "GET /health HTTP/1.1\r\nX-Big: ${"a".repeat(40_000)}\r\n\r\n").status)
        assertEquals(431, exchange(s.boundPort, "GET /health HTTP/1.1\r\n" + (1..101).joinToString("") { "X-$it: v\r\n" } + "\r\n").status)
        assertEquals(417, exchange(s.boundPort, post("{}", "Expect: something\r\n")).status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun bareLfLineEndingsAreAccepted() {
        val s = server()
        assertEquals(200, exchange(s.boundPort, "POST /mcp HTTP/1.1\nContent-Length: 2\n\n{}").status)
    }

    @Test
    fun handlerFailureIs500AndEmptyBodyHasZeroLength() {
        val s = server()
        respond = { throw IllegalStateException("boom") }
        val failed = exchange(s.boundPort, post("{}"))
        assertEquals(500, failed.status)
        assertTrue(failed.body.contains("-32603"))

        respond = { TransportResponse(202, null) }
        val accepted = exchange(s.boundPort, post("{}"))
        assertEquals(202, accepted.status)
        assertEquals("0", accepted.headers["content-length"])
        assertEquals("", accepted.body)
    }

    @Test
    fun handlerHeadersCannotInjectOrOverrideFraming() {
        val s = server()
        respond = { TransportResponse(200, "{}", mapOf("x-evil" to "a\r\nSet-Cookie: x=1", "content-length" to "999", "content-type" to "application/json")) }
        val response = exchange(s.boundPort, post("{}"))
        assertEquals("2", response.headers["content-length"])
        assertNull(response.headers["set-cookie"])
        assertEquals("{}", response.body)
    }

    @Test
    fun concurrentRequestsAreServedInParallel() {
        val s = server()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        respond = { req ->
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(300)
            inFlight.decrementAndGet()
            TransportResponse(200, req.body, mapOf("content-type" to "application/json"))
        }
        val pool = Executors.newFixedThreadPool(20)
        val started = System.nanoTime()
        val futures = (1..20).map { i -> pool.submit<RawResponse> { exchange(s.boundPort, post("{\"i\":$i}")) } }
        val responses = futures.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        responses.forEachIndexed { index, r ->
            assertEquals(200, r.status)
            assertEquals("{\"i\":${index + 1}}", r.body)
        }
        assertTrue(maxInFlight.get() > 1, "requests ran concurrently")
        assertTrue(elapsedMs < 20 * 300, "took $elapsedMs ms")
    }

    @Test
    fun stopClosesEverythingAndIsIdempotent() {
        val s = server()
        val port = s.boundPort
        assertTrue(port > 0)
        // An idle connection that never sends a request.
        val idle = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        Thread.sleep(100)
        s.start() // no-op while running
        assertEquals(port, s.boundPort)

        s.stop()
        s.stop()
        assertFalse(s.isRunning)
        assertEquals(-1, s.boundPort)
        assertEquals(-1, idle.getInputStream().read(), "open connection closed by stop()")
        idle.close()
        assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }

        s.start() // restart works
        assertTrue(s.isRunning)
        assertEquals(200, exchange(s.boundPort, "GET /health HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun startFailsWhenPortIsTaken() {
        val first = server()
        val second = LocalHttpServer(port = first.boundPort, handler = { TransportResponse(200, "{}") })
        assertFailsWith<java.io.IOException> { second.start() }
        assertFalse(second.isRunning)
    }

    @Test
    fun wireParserUnitChecks() {
        val head = HttpWire.readHead(ByteArrayInputStream("\r\nPOST /mcp?q=1 HTTP/1.1\r\nA: 1\r\na: 2\r\nContent-Length: 5, 5\r\n\r\n".toByteArray()))
        assertEquals("POST", head.method)
        assertEquals("/mcp", head.path)
        assertEquals("1, 2", head.header("a"))
        assertEquals(5L, HttpWire.contentLength(head))
        assertEquals("Content Too Large", HttpWire.reasonPhrase(413))
    }

    private fun readExactly(input: InputStream, n: Int): String {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            require(r >= 0) { "EOF after $read bytes" }
            read += r
        }
        return String(buf, Charsets.ISO_8859_1)
    }
}
