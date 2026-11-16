package dev.phonebridge.core.screen

import dev.phonebridge.core.plugins.DeviceOs
import kotlin.math.max
import kotlin.math.roundToInt

/** A scripted device: [screen] is what it shows; every call is recorded in [calls]; hooks change the screen. */
class FakeScreenDriver(
    var screen: ScreenSnapshot,
    override val os: DeviceOs = DeviceOs.IOS,
    override val globalActions: Set<String> = setOf("home", "lock_screen"),
) : ScreenDriver {
    val calls = mutableListOf<String>()
    var shot = Screenshot(ByteArray(8) { it.toByte() }, "image/png", 1179, 2556)
    var onTap: (x: Int, y: Int) -> Unit = { _, _ -> }
    var onType: (String) -> Unit = {}
    var onLaunch: (String) -> Unit = {}
    var failWith: ScreenException? = null

    /** Shown by the next snapshots, one each, before [screen] again (a screen that takes a moment to change). */
    val upcoming = ArrayDeque<ScreenSnapshot>()
    var onGlobal: (String) -> Unit = {}

    private fun record(call: String) {
        failWith?.let { throw it }
        calls += call
    }

    override suspend fun snapshot(): ScreenSnapshot = upcoming.removeFirstOrNull() ?: screen

    override suspend fun screenshot(): Screenshot = shot

    override suspend fun tap(x: Double, y: Double, durationMs: Long) {
        record("tap ${x.toInt()},${y.toInt()}" + if (durationMs > 0) " long" else "")
        onTap(x.toInt(), y.toInt())
    }

    override suspend fun swipe(fromX: Double, fromY: Double, toX: Double, toY: Double, durationMs: Long) {
        record("swipe ${fromX.toInt()},${fromY.toInt()}->${toX.toInt()},${toY.toInt()}")
    }

    override suspend fun typeText(text: String) {
        record("type $text")
        onType(text)
    }

    override suspend fun clearText() = record("clear")

    override suspend fun pressKey(key: String) = record("key $key")

    override suspend fun globalAction(action: String) {
        record("global $action")
        onGlobal(action)
    }

    override suspend fun launchApp(appId: String) {
        record("launch $appId")
        onLaunch(appId)
    }

    override suspend fun openUrl(url: String) = record("open $url")
}

/** Scales like a real marker would (without drawing) and remembers the boxes it was given. */
class ScalingMarker : ScreenshotMarker {
    var marks: List<ScreenshotMarker.Mark> = emptyList()

    override fun mark(shot: Screenshot, marks: List<ScreenshotMarker.Mark>, maxDimension: Int): Screenshot {
        this.marks = marks
        val longest = max(shot.width, shot.height)
        if (longest <= maxDimension) return shot
        val s = maxDimension.toDouble() / longest
        return Screenshot(shot.bytes, "image/jpeg", (shot.width * s).roundToInt(), (shot.height * s).roundToInt())
    }
}

val PHONE = Size(393, 852)

fun el(
    type: String,
    label: String? = null,
    bounds: Rect,
    value: String? = null,
    identifier: String? = null,
    placeholder: String? = null,
    actionable: Boolean = false,
    editable: Boolean = false,
    scrollable: Boolean = false,
    focused: Boolean = false,
    children: List<UiElement> = emptyList(),
) = UiElement(
    type = type, label = label, value = value, identifier = identifier, placeholder = placeholder, bounds = bounds,
    actionable = actionable, editable = editable, scrollable = scrollable, focused = focused, children = children,
)

const val MESSAGES = "com.apple.MobileSMS"

/** A Messages-like list screen. */
fun messagesScreen(
    search: String = "Search",
    overlays: List<ScreenSnapshot.Overlay> = emptyList(),
    locked: Boolean = false,
    extraText: String? = null,
) = ScreenSnapshot(
    foregroundApp = MESSAGES,
    screen = PHONE,
    root = el(
        "Application", "Messages", Rect(0, 0, 393, 852),
        children = listOfNotNull(
            el("NavigationBar", "Messages", Rect(0, 47, 393, 50), children = listOf(el("Button", "Compose", Rect(343, 55, 44, 44), actionable = true))),
            el("SearchField", "Search", Rect(16, 100, 361, 36), value = search, placeholder = "Search", actionable = true, editable = true),
            el(
                "Table", bounds = Rect(0, 143, 393, 700), scrollable = true,
                children = listOf(
                    el("Cell", bounds = Rect(0, 143, 393, 76), actionable = true, children = listOf(
                        el("StaticText", "Alice", Rect(16, 150, 200, 22)),
                        el("StaticText", "See you soon", Rect(16, 175, 300, 20)),
                    )),
                    el("Cell", bounds = Rect(0, 219, 393, 76), actionable = true, children = listOf(
                        el("StaticText", "Bob", Rect(16, 226, 200, 22)),
                        el("StaticText", "Lunch?", Rect(16, 251, 300, 20)),
                    )),
                    // Scrolled off screen: never listed or matched.
                    el("Cell", bounds = Rect(0, 900, 393, 76), actionable = true, children = listOf(el("StaticText", "Carol", Rect(16, 907, 200, 22)))),
                ),
            ),
            extraText?.let { el("StaticText", it, Rect(16, 800, 361, 20)) },
        ),
    ),
    overlays = overlays,
    locked = locked,
)

fun alert(owner: String, button: String) = ScreenSnapshot.Overlay(
    owner,
    el("Alert", "Heads up", Rect(40, 300, 313, 200), children = listOf(el("Button", button, Rect(60, 440, 273, 44), actionable = true))),
)
