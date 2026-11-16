package com.droidhands.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

sealed interface ToolContent {
    data class Text(val text: String) : ToolContent

    /** Base64 (standard alphabet, no line breaks) encoded image bytes. */
    data class Image(val base64Data: String, val mimeType: String) : ToolContent
}

data class ToolResult(val content: List<ToolContent>, val isError: Boolean = false) {
    companion object {
        private val compactJson = Json { encodeDefaults = true; explicitNulls = false }

        fun text(text: String) = ToolResult(listOf(ToolContent.Text(text)))

        fun error(message: String) = ToolResult(listOf(ToolContent.Text(message)), isError = true)

        /** Compact JSON text result (preferred for structured data: cheap for LLM tokens). */
        fun json(value: JsonElement) = text(compactJson.encodeToString(JsonElement.serializer(), value))

        fun json(block: JsonObjectBuilder.() -> Unit) = json(buildJsonObject(block))

        fun image(base64Data: String, mimeType: String, caption: String? = null) = ToolResult(
            buildList {
                add(ToolContent.Image(base64Data, mimeType))
                if (caption != null) add(ToolContent.Text(caption))
            },
        )
    }
}
