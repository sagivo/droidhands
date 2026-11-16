package com.droidhands.ios.runner

import com.droidhands.core.plugins.DeviceOs
import com.droidhands.core.screen.Rect
import com.droidhands.core.screen.ScreenDriver
import com.droidhands.core.screen.ScreenException
import com.droidhands.core.screen.ScreenSnapshot
import com.droidhands.core.screen.Screenshot
import com.droidhands.core.screen.Size
import com.droidhands.core.screen.UiElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Base64
import kotlin.math.roundToInt

/** The runner's `GET /status` (docs/IOS.md), fields absent when the runner didn't report them. */
data class RunnerStatus(
    val runnerVersion: String?,
    val osVersion: String?,
    val model: String?,
    val name: String?,
    val screen: Size?,
    val scale: Double?,
    val locked: Boolean?,
)

/** What the iOS host needs from the iPhone besides phone use: its status and which app is in front. */
interface IosDevice : ScreenDriver {
    suspend fun status(): RunnerStatus

    /**
     * The bundle id of the app in front (`com.apple.springboard` on the Home Screen), or null if it can't be told.
     * [prefer] are checked first (the app expected to be in front).
     */
    suspend fun foreground(prefer: List<String> = emptyList()): String?
}

/**
 * The iPhone side of phone use, through the on-phone runner (docs/IOS.md "Runner protocol"). Element types come as
 * `XCUIElement.ElementType` names (`button`, `textField`) and are reported capitalized (`Button`, `TextField`); whether
 * an element is actionable, editable or scrollable follows from its type alone. [candidates] gives the installed
 * bundle ids, which the runner checks to tell which app is in front. Thread-safe.
 */
class RunnerScreenDriver(
    private val client: RunnerClient,
    private val candidates: suspend () -> List<String> = { emptyList() },
) : IosDevice {
    override val os: DeviceOs get() = DeviceOs.IOS
    override val globalActions: Set<String> = linkedSetOf(HOME, LOCK_SCREEN)

    override suspend fun status(): RunnerStatus = parseStatus(client.get("/status", STATUS_TIMEOUT_MS))

    override suspend fun foreground(prefer: List<String>): String? {
        // The Home Screen last: it is in front only when no app is.
        val ids = (prefer + candidates() + SPRINGBOARD).distinct()
        val body = buildJsonObject { if (ids.isNotEmpty()) putJsonArray("candidates") { ids.forEach { add(JsonPrimitive(it)) } } }
        return client.post("/foreground", body).string("bundle_id")
    }

    /** `not_running`, `background`, `foreground` or `unknown` per bundle id. */
    suspend fun appStates(bundleIds: List<String>): Map<String, String> {
        val body = buildJsonObject { putJsonArray("bundle_ids") { bundleIds.forEach { add(JsonPrimitive(it)) } } }
        val states = client.post("/app_state", body)["states"] as? JsonObject ?: return emptyMap()
        return states.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
    }

    override suspend fun snapshot(): ScreenSnapshot = parseSnapshot(client.post("/snapshot", buildJsonObject { put("max_depth", MAX_DEPTH) }, SNAPSHOT_TIMEOUT_MS))

    override suspend fun screenshot(): Screenshot {
        val r = client.post(
            "/screenshot",
            buildJsonObject {
                put("format", "jpeg")
                put("quality", 0.8)
            },
            SCREENSHOT_TIMEOUT_MS,
        )
        val data = r.string("data") ?: throw ScreenException("The runner returned a screenshot without image data. Try again.")
        val bytes = try {
            Base64.getMimeDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            throw ScreenException("The runner returned a screenshot that isn't valid base64. Try again.")
        }
        return Screenshot(bytes, r.string("mime") ?: "image/jpeg", r.int("width") ?: 0, r.int("height") ?: 0)
    }

    override suspend fun tap(x: Double, y: Double, durationMs: Long) {
        client.post(
            "/tap",
            buildJsonObject {
                put("x", x)
                put("y", y)
                if (durationMs > 0) put("duration_ms", durationMs)
            },
            gestureTimeout(durationMs),
        )
    }

    override suspend fun swipe(fromX: Double, fromY: Double, toX: Double, toY: Double, durationMs: Long) {
        client.post(
            "/swipe",
            buildJsonObject {
                put("from_x", fromX)
                put("from_y", fromY)
                put("to_x", toX)
                put("to_y", toY)
                put("duration_ms", durationMs)
            },
            gestureTimeout(durationMs),
        )
    }

    override suspend fun typeText(text: String) {
        client.post("/type", buildJsonObject { put("text", text) }, TYPE_TIMEOUT_MS)
    }

    override suspend fun clearText() {
        client.post("/clear")
    }

    override suspend fun pressKey(key: String) {
        if (key != "return" && key != "delete") throw ScreenException("Unknown key '$key'. The iPhone keyboard keys are: return, delete.")
        client.post("/key", buildJsonObject { put("key", key) })
    }

    override suspend fun globalAction(action: String) {
        when (action) {
            HOME -> client.post("/button", buildJsonObject { put("name", "home") })
            LOCK_SCREEN -> client.post("/lock")
            else -> throw ScreenException(
                "'$action' isn't available on iPhone. Available: ${globalActions.joinToString(", ")}. There is no Back button: " +
                    "tap the app's own back control, or swipe from the left edge (ui_swipe).",
            )
        }
    }

    override suspend fun launchApp(appId: String) {
        client.post("/launch", buildJsonObject { put("bundle_id", appId) }, LAUNCH_TIMEOUT_MS)
    }

    override suspend fun openUrl(url: String) {
        client.post("/open_url", buildJsonObject { put("url", url) }, LAUNCH_TIMEOUT_MS)
    }

    companion object {
        const val HOME = "home"
        const val LOCK_SCREEN = "lock_screen"
        const val SPRINGBOARD = "com.apple.springboard"
        const val MAX_DEPTH = 60

        private const val STATUS_TIMEOUT_MS = 5_000L
        private const val SNAPSHOT_TIMEOUT_MS = 20_000L
        private const val SCREENSHOT_TIMEOUT_MS = 20_000L
        private const val LAUNCH_TIMEOUT_MS = 30_000L
        private const val TYPE_TIMEOUT_MS = 60_000L

        private val ACTIONABLE = setOf(
            "button", "cell", "link", "switch", "toggle", "slider", "stepper", "segmentedControl", "tab", "menuItem", "menuButton",
            "popUpButton", "checkBox", "radioButton", "disclosureTriangle", "picker", "pickerWheel", "datePicker", "icon",
            "textField", "secureTextField", "searchField", "textView", "colorWell", "incrementArrow", "decrementArrow",
        )
        private val EDITABLE = setOf("textField", "secureTextField", "searchField", "textView")
        private val SCROLLABLE = setOf("scrollView", "table", "collectionView", "webView", "outline", "browser")

        private fun gestureTimeout(durationMs: Long) = 10_000L + durationMs.coerceIn(0, 60_000)

        fun parseStatus(o: JsonObject): RunnerStatus {
            val screen = o["screen"] as? JsonObject
            return RunnerStatus(
                runnerVersion = o.string("runner_version"),
                osVersion = o.string("os_version"),
                model = o.string("model"),
                name = o.string("name"),
                screen = screen?.let { s -> s.int("width")?.let { w -> s.int("height")?.let { h -> Size(w, h) } } },
                scale = screen?.let { (it["scale"] as? JsonPrimitive)?.doubleOrNull },
                locked = (o["locked"] as? JsonPrimitive)?.booleanOrNull,
            )
        }

        /** The runner's `/snapshot` answer as a [ScreenSnapshot] (pure). */
        fun parseSnapshot(o: JsonObject): ScreenSnapshot {
            val screen = o["screen"] as? JsonObject
            val size = Size(screen?.int("width") ?: 0, screen?.int("height") ?: 0)
            val root = (o["root"] as? JsonObject)?.let(::element)
            val overlays = (o["overlays"] as? JsonArray).orEmpty().mapNotNull { e ->
                val ov = e as? JsonObject ?: return@mapNotNull null
                val r = (ov["root"] as? JsonObject)?.let(::element) ?: return@mapNotNull null
                ScreenSnapshot.Overlay(ov.string("owner") ?: SPRINGBOARD, r)
            }
            val notes = buildList {
                (o["notes"] as? JsonArray)?.forEach { n -> (n as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::add) }
                o.string("note")?.let(::add)
            }
            return ScreenSnapshot(
                foregroundApp = o.string("bundle_id"),
                screen = size,
                root = root,
                overlays = overlays,
                locked = o.bool("locked") ?: false,
                keyboardShown = o.bool("keyboard") ?: false,
                notes = notes,
            )
        }

        private fun element(o: JsonObject): UiElement {
            val rawType = o.string("type") ?: "other"
            val secure = o.bool("secure") == true || rawType == "secureTextField"
            return UiElement(
                type = rawType.replaceFirstChar { it.uppercaseChar() },
                label = o.string("label"),
                value = if (secure) null else o.string("value"),
                identifier = o.string("identifier"),
                placeholder = o.string("placeholder"),
                bounds = frame(o["frame"]),
                enabled = o.bool("enabled") ?: true,
                selected = o.bool("selected") ?: false,
                focused = o.bool("focused") ?: false,
                actionable = rawType in ACTIONABLE,
                editable = rawType in EDITABLE,
                scrollable = rawType in SCROLLABLE,
                secure = secure,
                children = (o["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::element) },
            )
        }

        /** `[x, y, w, h]` in points (also `{x, y, width, height}`). */
        private fun frame(e: Any?): Rect {
            val n: List<Double> = when (e) {
                is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
                is JsonObject -> listOf("x", "y", "width", "height").mapNotNull { (e[it] as? JsonPrimitive)?.doubleOrNull }
                else -> emptyList()
            }
            if (n.size != 4 || n.any { !it.isFinite() }) return Rect(0, 0, 0, 0)
            return Rect(n[0].roundToInt(), n[1].roundToInt(), n[2].roundToInt(), n[3].roundToInt())
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.roundToInt()

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
