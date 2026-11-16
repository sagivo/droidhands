package com.droidhands.core.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An HTTP failure answered with [status] before the request reaches the handler. */
internal class HttpError(val status: Int, message: String, val headers: Map<String, String> = emptyMap()) : IOException(message)

/** Parsed request line and headers. Header names are lowercase. */
internal class HttpHead(
    val method: String,
    val target: String,
    val version: String,
    private val headers: Map<String, List<String>>,
) {
    /** Path without query string or fragment, trailing slash removed (except root). */
    val path: String = target.substringBefore('?').substringBefore('#').let { if (it.length > 1) it.trimEnd('/') else it }

    /** All values for [name], joined with ", ". */
    fun header(name: String): String? = headers[name]?.joinToString(", ")

    /** A header that must appear at most once (400 otherwise). */
    fun single(name: String): String? {
        val values = headers[name] ?: return null
        if (values.size > 1 && values.distinct().size > 1) throw HttpError(400, "Conflicting '$name' headers")
        return values.first()
    }
}

/**
 * HTTP/1.1 request parsing and response writing (RFC 9112 subset) on plain streams. Strict where
 * ambiguity could be abused (conflicting lengths, Content-Length with Transfer-Encoding,
 * obsolete line folding), lenient on bare-LF line endings.
 */
internal object HttpWire {
    const val MAX_HEADER_BYTES = 32 * 1024
    const val MAX_HEADERS = 100
    private const val MAX_CHUNK_LINE = 1024

    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    /** Reads the request line and headers. @throws HttpError on malformed or oversized input. */
    fun readHead(input: InputStream): HttpHead {
        val budget = intArrayOf(MAX_HEADER_BYTES)
        var requestLine = readLine(input, budget, 431) ?: throw HttpError(400, "Empty request")
        // RFC 9112 §2.2: ignore empty lines before the request line.
        while (requestLine.isEmpty()) requestLine = readLine(input, budget, 431) ?: throw HttpError(400, "Empty request")

        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts.any { it.isEmpty() }) throw HttpError(400, "Malformed request line")
        val (method, rawTarget, version) = parts
        if (!TOKEN.matches(method)) throw HttpError(400, "Malformed method")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw HttpError(505, "Unsupported HTTP version")
        val target = normalizeTarget(rawTarget)

        val headers = LinkedHashMap<String, MutableList<String>>()
        var count = 0
        while (true) {
            val line = readLine(input, budget, 431) ?: throw HttpError(400, "Unexpected end of headers")
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw HttpError(431, "Too many headers")
            if (line[0] == ' ' || line[0] == '\t') throw HttpError(400, "Obsolete header line folding")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "Malformed header")
            val name = line.substring(0, colon)
            if (!TOKEN.matches(name)) throw HttpError(400, "Malformed header name")
            val value = line.substring(colon + 1).trim(' ', '\t')
            headers.getOrPut(name.lowercase()) { ArrayList(1) } += value
        }
        return HttpHead(method, target, version, headers)
    }

    /**
     * Reads the body declared by [head], at most [maxBytes].
     * @throws HttpError 400 (malformed/truncated), 413 (too large), 501 (unsupported coding).
     */
    fun readBody(head: HttpHead, input: InputStream, maxBytes: Int): ByteArray {
        val transferEncoding = head.header("transfer-encoding")
        val length = contentLength(head)
        if (transferEncoding != null) {
            if (length != null) throw HttpError(400, "Both Content-Length and Transfer-Encoding")
            val codings = transferEncoding.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (codings != listOf("chunked")) throw HttpError(501, "Unsupported Transfer-Encoding")
            return readChunked(input, maxBytes)
        }
        if (length == null || length == 0L) return ByteArray(0)
        if (length > maxBytes) throw HttpError(413, "Body larger than $maxBytes bytes")
        return readExactly(input, length.toInt())
    }

    /** Declared Content-Length, or null. @throws HttpError 400 if invalid. */
    fun contentLength(head: HttpHead): Long? {
        val raw = head.single("content-length") ?: return null
        // A list like "5, 5" is allowed by RFC 9110 when all values agree.
        val values = raw.split(',').map { it.trim() }.distinct()
        if (values.size != 1 || values[0].isEmpty() || values[0].length > 18 || !values[0].all { it in '0'..'9' }) {
            throw HttpError(400, "Invalid Content-Length")
        }
        return values[0].toLong()
    }

    /** Writes a complete response; [includeBody] false answers HEAD (Content-Length still describes [body]). */
    fun writeResponse(out: OutputStream, status: Int, headers: Map<String, String>, body: ByteArray?, includeBody: Boolean = true) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        for ((name, value) in headers) {
            val lower = name.lowercase()
            if (lower in MANAGED_HEADERS || !TOKEN.matches(name)) continue
            head.append(name).append(": ").append(sanitizeValue(value)).append("\r\n")
        }
        head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null && includeBody) out.write(body)
        out.flush()
    }

    fun writeContinue(out: OutputStream) {
        out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun reasonPhrase(status: Int): String = when (status) {
        100 -> "Continue"
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        303 -> "See Other"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        413 -> "Content Too Large"
        417 -> "Expectation Failed"
        421 -> "Misdirected Request"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        505 -> "HTTP Version Not Supported"
        else -> "Status"
    }

    /** Headers the server always sets itself; handler values for these are ignored. */
    private val MANAGED_HEADERS = setOf("content-length", "connection", "cache-control", "transfer-encoding")

    private fun sanitizeValue(value: String): String = buildString(value.length) {
        for (c in value) append(if (c == '\t' || (c >= ' ' && c != '\u007f' && c.code <= 0xff)) c else ' ')
    }

    /** Origin-form is kept; absolute-form (`http://host/path`) is reduced to its path. */
    private fun normalizeTarget(target: String): String {
        if (target.startsWith('/')) return target
        val scheme = target.indexOf("://")
        if (scheme > 0) {
            val pathStart = target.indexOf('/', scheme + 3)
            return if (pathStart < 0) "/" else target.substring(pathStart)
        }
        throw HttpError(400, "Malformed request target")
    }

    /**
     * Reads one line (ISO-8859-1), stripping CRLF or LF. Null at end of stream before any byte.
     * [budget] holds the remaining allowed bytes; exceeding it throws [HttpError] with [overflowStatus].
     */
    private fun readLine(input: InputStream, budget: IntArray, overflowStatus: Int): String? {
        val sb = StringBuilder()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (!sawAny) return null
                throw HttpError(400, "Unexpected end of stream")
            }
            sawAny = true
            if (--budget[0] < 0) throw HttpError(overflowStatus, "Request headers too large")
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) throw HttpError(400, "Body shorter than Content-Length")
            read += n
        }
        return out
    }

    private fun readChunked(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val lineBudget = intArrayOf(0)
        while (true) {
            lineBudget[0] = MAX_CHUNK_LINE
            val sizeLine = readLine(input, lineBudget, 400) ?: throw HttpError(400, "Truncated chunked body")
            val sizeText = sizeLine.substringBefore(';').trim()
            if (sizeText.isEmpty() || sizeText.length > 8 || !sizeText.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw HttpError(400, "Invalid chunk size")
            }
            val size = sizeText.toLong(16)
            if (size == 0L) break
            if (out.size() + size > maxBytes) throw HttpError(413, "Body larger than $maxBytes bytes")
            out.write(readExactly(input, size.toInt()))
            lineBudget[0] = 2
            val end = readLine(input, lineBudget, 400)
            if (end == null || end.isNotEmpty()) throw HttpError(400, "Missing CRLF after chunk")
        }
        // Trailer section: skip fields until the empty line.
        val trailerBudget = intArrayOf(MAX_HEADER_BYTES)
        var trailers = 0
        while (true) {
            val line = readLine(input, trailerBudget, 431) ?: throw HttpError(400, "Truncated chunked trailer")
            if (line.isEmpty()) break
            if (++trailers > MAX_HEADERS) throw HttpError(431, "Too many trailer fields")
        }
        return out.toByteArray()
    }
}
