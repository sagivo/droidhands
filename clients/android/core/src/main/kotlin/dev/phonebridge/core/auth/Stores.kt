package dev.phonebridge.core.auth

import dev.phonebridge.core.util.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/** Volatile store for tests and ephemeral setups. Thread-safe; stores defensive copies. */
class InMemoryTokenStore(initial: List<TokenRecord> = emptyList()) : TokenStore {
    @Volatile
    private var tokens: List<TokenRecord> = initial.toList()

    override fun load(): List<TokenRecord> = tokens

    override fun save(tokens: List<TokenRecord>) {
        this.tokens = tokens.toList()
    }
}

/**
 * JSON file store with atomic replace (write temp + fsync + rename), so a crash mid-save leaves
 * either the old or the new file, never a torn one.
 *
 * [load] never throws: a missing file is an empty list; an unreadable or corrupt file is moved
 * aside to `<name>.corrupt` (replacing an older backup) and treated as empty. Losing tokens fails
 * closed (agents get 401 and the user re-issues). [save] throws on I/O failure.
 *
 * Thread-safe within one instance; use a single instance per file.
 */
class FileTokenStore(private val file: File) : TokenStore {
    private val lock = Any()

    override fun load(): List<TokenRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            decode(text)
        } catch (e: Exception) {
            AtomicFiles.quarantine(file)
            emptyList()
        }
    }

    override fun save(tokens: List<TokenRecord>) = synchronized(lock) {
        val text = json.encodeToString(TokenFile.serializer(), TokenFile(FORMAT_VERSION, tokens))
        AtomicFiles.write(file, text.toByteArray(Charsets.UTF_8))
    }

    @Serializable
    private data class TokenFile(val version: Int = FORMAT_VERSION, val tokens: List<TokenRecord> = emptyList())

    private companion object {
        const val FORMAT_VERSION = 1

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        fun decode(text: String): List<TokenRecord> {
            val root = json.parseToJsonElement(text)
            // Also accept a bare array (hand-written files, older formats).
            if (root is JsonArray) return json.decodeFromJsonElement<List<TokenRecord>>(root)
            return json.decodeFromJsonElement<TokenFile>(root).tokens
        }
    }
}
