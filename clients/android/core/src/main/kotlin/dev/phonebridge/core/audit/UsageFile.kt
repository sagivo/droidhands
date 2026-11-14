package dev.phonebridge.core.audit

import dev.phonebridge.core.util.AtomicFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * On-disk format of [UsageTracker]: `{"version":1,"usage":{"<tokenId>":TokenUsage,…}}`.
 * [load] never throws; [save] replaces the file atomically and throws on I/O failure.
 */
internal object UsageFile {
    private const val FORMAT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * Missing or blank file → empty. Unparseable file → moved aside to `<name>.corrupt`, empty.
     * Entries that fail to decode, or whose key differs from their tokenId, are skipped.
     */
    fun load(file: File): Map<String, TokenUsage> {
        if (!file.exists()) return emptyMap()
        val root = try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyMap()
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            AtomicFiles.quarantine(file)
            return emptyMap()
        }
        val entries = root["usage"] as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, TokenUsage>()
        for ((tokenId, element) in entries) {
            val usage = try {
                json.decodeFromJsonElement<TokenUsage>(element)
            } catch (e: Exception) {
                continue
            }
            if (usage.tokenId == tokenId) result[tokenId] = usage
        }
        return result
    }

    fun save(file: File, usage: Map<String, TokenUsage>) {
        val root = buildJsonObject {
            put("version", FORMAT_VERSION)
            put("usage", JsonObject(usage.mapValues { (_, u) -> json.encodeToJsonElement(TokenUsage.serializer(), u) }))
        }
        AtomicFiles.write(file, json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8))
    }
}
