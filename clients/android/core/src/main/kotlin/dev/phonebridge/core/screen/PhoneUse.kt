package dev.phonebridge.core.screen

import dev.phonebridge.core.plugins.DeviceOs
import dev.phonebridge.core.plugins.ScrollDirection
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolContent
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optBool
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optLong
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqInt
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import dev.phonebridge.core.util.OneTimeCodes
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.Locale

/**
 * The screen tools of docs/TOOLS.md ("Screen": `screen_observe`, `screen_read`, `screen_find`, `screen_screenshot`,
 * `ui_wait`, `ui_tap`, `ui_type`, `ui_scroll`, `ui_swipe`, `ui_global_action`) on any [ScreenDriver].
 * [appLabel] names an app id for headers and refusals. [systemSurfaces] are exempt from an allowlist unless blocked by
 * name ([defaultSystemSurfaces]). Element refs, the gesture lock and screenshot geometry are kept per [driver] instance
 * and shared with [ScreenStepRunner].
 */
fun phoneUseTools(
    driver: ScreenDriver,
    appLabel: suspend (String) -> String?,
    marker: ScreenshotMarker = ScreenshotMarker.NONE,
    systemSurfaces: Set<String> = defaultSystemSurfaces(driver.os),
    timing: PhoneUseTiming = PhoneUseTiming(),
): List<Tool> = PhoneUse(ScreenOps(driver, appLabel, systemSurfaces, timing), marker).tools()

/** Every `ui_global_action` name of docs/TOOLS.md; a device supports [ScreenDriver.globalActions] of them. */
internal val GLOBAL_ACTIONS = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "power_dialog")

/** What screen_observe includes. */
internal data class ObserveOptions(
    val screenshot: Boolean = true,
    val elements: Elements = Elements.ACTIONABLE,
    val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    val maxElements: Int = DEFAULT_MAX_ELEMENTS,
) {
    enum class Elements { ACTIONABLE, ALL, NONE }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 1280
        const val MIN_MAX_DIMENSION = 64
        const val MAX_MAX_DIMENSION = 4096
        const val DEFAULT_MAX_ELEMENTS = 80
        const val MAX_MAX_ELEMENTS = 300

        fun maxDimension(args: JsonObject): Int =
            (args.optInt("max_dimension") ?: DEFAULT_MAX_DIMENSION).coerceIn(MIN_MAX_DIMENSION, MAX_MAX_DIMENSION)

        fun parse(args: JsonObject): ObserveOptions = ObserveOptions(
            screenshot = args.optBool("screenshot") ?: true,
            elements = when (args.optString("elements")?.trim()?.lowercase()) {
                null, "actionable" -> Elements.ACTIONABLE
                "all" -> Elements.ALL
                "none" -> Elements.NONE
                else -> throw ToolException("elements must be one of actionable, all, none.")
            },
            maxDimension = maxDimension(args),
            maxElements = (args.optInt("max_elements") ?: DEFAULT_MAX_ELEMENTS).coerceIn(1, MAX_MAX_ELEMENTS),
        )
    }
}

/** What the x,y of ui_tap and ui_swipe are measured in. */
internal enum class PointSpace {
    SCREEN, SCREENSHOT;

    companion object {
        val NAMES = entries.map { it.name.lowercase() }

        fun parse(raw: String?): PointSpace = when (raw?.trim()?.lowercase()) {
            null, "", "screen" -> SCREEN
            "screenshot", "image" -> SCREENSHOT
            else -> throw ToolException("space must be 'screen' (screen points) or 'screenshot' (pixels of your last screenshot).")
        }
    }
}

/** The phone-use tools on one device ([ScreenOps]). */
internal class PhoneUse(private val ops: ScreenOps, private val marker: ScreenshotMarker) {
    private val driver get() = ops.driver

    fun tools(): List<Tool> = listOf(
        observeTool(), readTool(), findTool(), screenshotTool(), waitTool(),
        tapTool(), typeTool(), scrollTool(), swipeTool(), globalActionTool(),
    )

    // ---- screen_observe -------------------------------------------------------------------------

    private fun observeTool(): Tool = tool(
        name = "screen_observe",
        title = "Look at the screen",
        description = "Phone use: shows what is on the phone's screen in any app and how to act on it, in one call. Returns a " +
            "screenshot with every actionable element boxed and numbered, plus those elements as lines " +
            "`n12 Button \"Send\" [clickable] (x,y,w,h)` (bounds in screen points; rows and cards add `contains \"…\"` with " +
            "their texts). Act with ui_tap ref=n12 / ui_type / ui_scroll, or tap any point of the image with ui_tap x,y " +
            "space=screenshot. Refs are yours alone and are replaced by your next screen_observe or screen_read. Every ui_* " +
            "action reports what changed on screen; pass observe=true to get a new screen_observe with it. When the screen " +
            "blocks capture or can't be shared, the result says why and still lists the elements; when an app exposes " +
            "nothing to accessibility (games, Flutter), it says so and the screenshot is what you work from. elements=all " +
            "gives the full tree like screen_read; screenshot=false skips the image. Apps the user blocked for this agent are " +
            "left out and refused in the foreground; when the user hides one-time codes they appear as \"${OneTimeCodes.MASK}\" " +
            "and no screenshot is taken while one is visible.",
        scope = Scopes.SCREEN_READ,
        risk = Risk.READ,
        schema = objectSchema {
            boolean("screenshot", "Include the marked screenshot (default true)")
            string("elements", "Which elements to list (default actionable)", enum = listOf("actionable", "all", "none"))
            integer(
                "max_dimension", "Longest side of the image in pixels (default ${ObserveOptions.DEFAULT_MAX_DIMENSION})",
                minimum = ObserveOptions.MIN_MAX_DIMENSION.toLong(), maximum = ObserveOptions.MAX_MAX_DIMENSION.toLong(),
            )
            integer(
                "max_elements", "Maximum number of elements listed (default ${ObserveOptions.DEFAULT_MAX_ELEMENTS})",
                minimum = 1, maximum = ObserveOptions.MAX_MAX_ELEMENTS.toLong(),
            )
        },
        timeoutMs = 30_000,
    ) { args, ctx -> ToolResult(observe(ctx, ObserveOptions.parse(args))) }

    private class Listing(val tree: String?, val marks: List<Pair<String, ScreenMarks.Mark>>)

    private class Shot(val content: ToolContent.Image, val caption: String)

    suspend fun observe(ctx: CallContext, options: ObserveOptions): List<ToolContent> {
        val capture = ops.settled(ctx, attempts = ops.timing.settleAttempts, waitFirst = false)
        capture.blockedForeground?.let { ops.refuse(ctx, it) }
        val marks = if (options.elements == ObserveOptions.Elements.NONE) emptyList() else ScreenMarks.select(capture, options.maxElements)
        // One snapshot: the tree (elements=all) and the marks share refs, and older refs stop working, as with screen_read.
        val listing = ops.refs(ctx).newSnapshot { register ->
            val tree = if (options.elements == ObserveOptions.Elements.ALL) {
                ScreenFormat.serialize(capture.windows, includeAll = true, maxNodes = options.maxElements * 4, assignRef = register).text
            } else {
                null
            }
            Listing(tree, marks.map { register(it.node) to it })
        }
        val notes = ArrayList<String>()
        if (capture.locked) notes += "The phone is locked: ask the user to unlock it (screen control does not work on the lock screen)."
        notes += capture.notes
        if (ScreenMarks.thinTree(capture)) notes += ScreenTexts.THIN_TREE
        val overlays = capture.windows.filter { !it.primary }
        if (overlays.isNotEmpty()) {
            val listed = if (options.elements == ObserveOptions.Elements.ACTIONABLE) " (its elements are listed first)" else ""
            notes += "Shown over the app: ${overlays.joinToString { "\"${it.title}\"" }}$listed."
        }
        val image = if (options.screenshot) screenshotFor(ctx, capture, listing.marks, options.maxDimension, notes) else null
        val text = buildString {
            append(ScreenFormat.header(capture.foreground, capture.foreground?.let { ops.label(it) }, capture.locked))
            image?.let { append('\n').append(it.caption) }
            notes.forEach { append('\n').append(it) }
            when {
                listing.tree != null ->
                    append("\nScreen tree (use the refs with ui_tap, ui_type, ui_scroll):\n").append(listing.tree.ifEmpty { "(nothing readable)" })
                options.elements == ObserveOptions.Elements.NONE -> Unit
                listing.marks.isEmpty() -> append("\nNo actionable elements are exposed on this screen.")
                else -> {
                    append("\nActionable elements (tap with ui_tap ref=n12; ")
                    append(if (image != null && marker !== ScreenshotMarker.NONE) "the number in each box on the image is its ref" else "bounds are screen points")
                    append("):")
                    for ((ref, mark) in listing.marks) {
                        append('\n').append(ScreenFormat.formatNode(mark.node.el, ref, MAX_ELEMENT_TEXT))
                        ScreenMarks.innerText(mark.node)?.let { append(" contains \"").append(it.replace("\"", "'")).append('"') }
                    }
                    if (listing.marks.size >= options.maxElements) append("\n... more elements not listed (raise max_elements, or elements=all)")
                }
            }
        }
        return listOfNotNull(image?.content, ToolContent.Text(text))
    }

    /** The marked screenshot, or null with the reason added to [notes] when it can't be taken or shared. */
    private suspend fun screenshotFor(
        ctx: CallContext,
        capture: Capture,
        marks: List<Pair<String, ScreenMarks.Mark>>,
        maxDimension: Int,
        notes: MutableList<String>,
    ): Shot? {
        val raw = try {
            requireShareable(ctx, capture)
            device { driver.screenshot() }.also { requireShareable(ctx, ops.capture(ctx)) }
        } catch (e: ToolException) {
            notes += "No screenshot: ${e.message}"
            return null
        }
        val screen = capture.screen
        val sx = raw.width.toDouble() / screen.width.coerceAtLeast(1)
        val sy = raw.height.toDouble() / screen.height.coerceAtLeast(1)
        val boxes = marks.filter { it.second.drawn }.map { (ref, mark) ->
            val b = mark.box
            ScreenshotMarker.Mark(ScreenMarks.tag(ref), Rect((b.x * sx).toInt(), (b.y * sy).toInt(), (b.width * sx).toInt(), (b.height * sy).toInt()))
        }
        val image = marker.mark(raw, boxes, maxDimension)
        ops.state.recordScreenshot(ctx.tokenId, ScreenshotGeometry(image.width, image.height, screen.width, screen.height))
        val caption = "Screenshot ${image.width}x${image.height} (screen ${screen.width}x${screen.height} points). ${coordinateHint(image, screen)}."
        return Shot(ToolContent.Image(Base64.getEncoder().encodeToString(image.bytes), image.mimeType), caption)
    }

    /**
     * Throws unless the screen may be shared as pixels: when the user hides one-time codes, no visible text that contains
     * one; for agents with app restrictions, no blocked app in front or in an overlay and not the lock screen (it shows
     * other apps' notifications). Pixels can't be filtered or masked the way the element list is.
     */
    private suspend fun requireShareable(ctx: CallContext, capture: Capture) {
        if (ctx.redactOneTimeCodes && capture.codeVisible) throw ToolException(ScreenTexts.CODE_VISIBLE)
        capture.blockedForeground?.let { ops.refuse(ctx, it, "Can't take a screenshot.") }
        capture.blockedOverlays.firstOrNull()
            ?.let { ops.refuse(ctx, it.owner, "Can't take a screenshot: a window or notification of a blocked app is visible.") }
        if (capture.locked && ops.visibility(ctx).restricted) throw ToolException(ScreenTexts.LOCK_SCREEN_SHOT)
    }

    private fun coordinateHint(image: Screenshot, screen: Rect): String = if (image.width == screen.width) {
        "Image pixels are screen points for ui_tap/ui_swipe"
    } else {
        "To tap a point of this image, pass its image x,y with space=screenshot to ui_tap/ui_swipe (or multiply by " +
            String.format(Locale.ROOT, "%.3f", screen.width.toDouble() / image.width) + " for screen points)"
    }

    // ---- screen_read / screen_find --------------------------------------------------------------

    private fun readTool(): Tool = tool(
        name = "screen_read",
        title = "Read the screen",
        description = "Reads what is on the phone's screen in any app, as an indented tree. First line: " +
            "app=<id> (<name>) locked=<bool>. Then one element per line: " +
            "`n12 Button \"Send\" value=\"...\" id=send [clickable] (x,y,w,h)` (bounds in screen points). " +
            "Use the refs (n12) with ui_tap/ui_type/ui_scroll; they are yours alone and stay valid until your next " +
            "screen_read or screen_observe (each read gives new numbers). System alerts over the app follow under 'window:' " +
            "headers. By default layout-only containers are hidden; include_all shows everything. Use screen_observe or " +
            "screen_screenshot when the tree is empty or unclear (games, maps, some web content). Apps the user blocked for " +
            "this agent are left out, and reading is refused while one of them is in the foreground; when the user hides " +
            "one-time codes they appear as \"${OneTimeCodes.MASK}\".",
        scope = Scopes.SCREEN_READ,
        risk = Risk.READ,
        schema = objectSchema {
            boolean("include_all", "Show every visible element, including layout containers (default false)")
            integer("max_nodes", "Maximum number of element lines (default $DEFAULT_MAX_NODES)", minimum = 1, maximum = MAX_MAX_NODES.toLong())
        },
        timeoutMs = 30_000,
    ) { args, ctx ->
        val includeAll = args.optBool("include_all") ?: false
        val maxNodes = (args.optInt("max_nodes") ?: DEFAULT_MAX_NODES).coerceIn(1, MAX_MAX_NODES)
        // Right after an app switch the new app often exposes nothing yet: read again briefly instead of reporting an
        // empty screen.
        var capture = ops.capture(ctx)
        var attempt = 1
        while (true) {
            capture.blockedForeground?.let { ops.refuse(ctx, it) }
            val read = capture
            val body = ops.refs(ctx).newSnapshot { register -> ScreenFormat.serialize(read.windows, includeAll, maxNodes, register) }
            if (body.text.isNotEmpty() || capture.locked || attempt >= READ_ATTEMPTS) {
                val label = capture.foreground?.let { ops.label(it) }
                return@tool ToolResult.text(
                    buildString {
                        append(ScreenFormat.header(capture.foreground, label, capture.locked))
                        capture.notes.forEach { append('\n').append(it) }
                        append('\n')
                        append(body.text.ifEmpty { "(nothing readable: the app exposes no accessibility info; try screen_observe or screen_screenshot)" })
                    },
                )
            }
            delay(ops.timing.retryMs)
            attempt++
            capture = ops.capture(ctx)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun findTool(): Tool = tool(
        name = "screen_find",
        title = "Find elements on screen",
        description = "Finds visible elements on the current screen and returns them with refs (same line format as " +
            "screen_read). text: case-insensitive 'contains' on the element's label or value; description: 'contains' on " +
            "the accessibility label (icons); view_id: exact developer id (iOS accessibilityIdentifier, Android view id); " +
            "clickable_only: only elements that react to taps (directly or through a tappable container). Criteria combine " +
            "with AND. Cheaper than screen_read when you know what you're looking for.",
        scope = Scopes.SCREEN_READ,
        risk = Risk.READ,
        schema = objectSchema {
            string("text", "Text the element shows (contains, case-insensitive)")
            string("view_id", "Developer id of the element (exact)")
            string("description", "Accessibility label (contains, case-insensitive)")
            boolean("clickable_only", "Only elements that respond to taps")
        },
        timeoutMs = 30_000,
    ) { args, ctx ->
        val textKey = args.optString("text")?.let(TextNorm::key)?.takeIf { it.isNotEmpty() }
        val descKey = args.optString("description")?.let(TextNorm::key)?.takeIf { it.isNotEmpty() }
        val id = args.optString("view_id")?.trim()?.takeIf { it.isNotEmpty() }
        val clickableOnly = args.optBool("clickable_only") ?: false
        if (textKey == null && descKey == null && id == null && !clickableOnly) {
            throw ToolException("Pass at least one of text, view_id, description, or clickable_only=true.")
        }
        val capture = ops.capture(ctx)
        capture.blockedForeground?.let { ops.refuse(ctx, it) }
        val matches = capture.visibleNodes().filter { n ->
            val e = n.el
            (textKey == null || TextNorm.key(e.label).contains(textKey) || (!e.secure && TextNorm.key(e.value).contains(textKey))) &&
                (descKey == null || TextNorm.key(e.label).contains(descKey)) &&
                (id == null || e.identifier.equals(id, ignoreCase = true)) &&
                (!clickableOnly || Queries.tapTarget(n) != null)
        }.toList()
        val app = capture.foreground ?: "unknown"
        if (matches.isEmpty()) {
            return@tool ToolResult.text(
                "No matching elements on screen (app=$app). Call screen_observe to see what is there, or ui_scroll to reveal more content.",
            )
        }
        val refs = ops.refs(ctx)
        val lines = matches.take(MAX_FIND_RESULTS).joinToString("\n") { ScreenFormat.formatNode(it.el, refs.register(it)) }
        val more = if (matches.size > MAX_FIND_RESULTS) "\n... and ${matches.size - MAX_FIND_RESULTS} more; refine the criteria" else ""
        val codes = if (capture.codesHidden) "\n${ScreenTexts.CODES_NOTE}" else ""
        ToolResult.text("Found ${matches.size} element(s) (app=$app):\n$lines$more$codes")
    }

    // ---- screen_screenshot / ui_wait ------------------------------------------------------------

    private fun screenshotTool(): Tool = tool(
        name = "screen_screenshot",
        title = "Take a screenshot",
        description = "Takes a screenshot of the phone's screen and returns it as an image, scaled so the longest side is at " +
            "most max_dimension pixels (default ${ObserveOptions.DEFAULT_MAX_DIMENSION}). The caption says how image " +
            "coordinates map to screen points for ui_tap/ui_swipe (or pass space=screenshot). Screens that block capture " +
            "(passwords, some banking apps) come back black: use screen_read for those. Prefer screen_observe for finding " +
            "and tapping elements. If the user blocked some apps for this agent, screenshots are refused while any of them " +
            "is visible and on the lock screen. When the user hides one-time codes, screenshots are refused while a " +
            "verification code is visible (use screen_read, which masks it).",
        scope = Scopes.SCREEN_READ,
        risk = Risk.READ,
        schema = objectSchema {
            integer(
                "max_dimension", "Longest side of the returned image in pixels (default ${ObserveOptions.DEFAULT_MAX_DIMENSION})",
                minimum = ObserveOptions.MIN_MAX_DIMENSION.toLong(), maximum = ObserveOptions.MAX_MAX_DIMENSION.toLong(),
            )
            string("format", "Image format (default jpeg; the phone may only produce one of them)", enum = listOf("jpeg", "png"))
        },
        timeoutMs = 30_000,
    ) { args, ctx ->
        val maxDimension = ObserveOptions.maxDimension(args)
        val format = when (args.optString("format")?.trim()?.lowercase()) {
            null, "jpeg", "jpg" -> "jpeg"
            "png" -> "png"
            else -> throw ToolException("format must be 'jpeg' or 'png'.")
        }
        val before = ops.capture(ctx)
        requireShareable(ctx, before)
        val raw = device { driver.screenshot() }
        // Re-check: a blocked app or a code may have appeared meanwhile.
        val after = ops.capture(ctx)
        requireShareable(ctx, after)
        val image = marker.mark(raw, emptyList(), maxDimension)
        ops.state.recordScreenshot(ctx.tokenId, ScreenshotGeometry(image.width, image.height, after.screen.width, after.screen.height))
        val sent = image.mimeType.substringAfter('/').replace("jpg", "jpeg")
        val caption = buildString {
            append("Screenshot ${image.width}x${image.height} $sent of app=${after.foreground ?: "unknown"}")
            append(", screen ${after.screen.width}x${after.screen.height} points. ")
            append(coordinateHint(image, after.screen)).append('.')
            if (sent != format) append(" (Sent as $sent: this phone does not produce $format screenshots.)")
            if (after.locked) append(" The phone is locked (lock screen shown).")
        }
        ToolResult.image(Base64.getEncoder().encodeToString(image.bytes), image.mimeType, caption)
    }

    private fun waitTool(): Tool = tool(
        name = "ui_wait",
        title = "Wait for the screen",
        description = "Waits until the given text is visible on screen (label or value, case-insensitive contains) and/or the " +
            "given app is in the foreground, up to timeout_ms. Returns {found, package, waited_ms} and, for text, the " +
            "matching element's ref. Use it after opening an app or tapping something that loads, instead of fixed sleeps. " +
            "Text in apps the user blocked for this agent never matches; when the user hides one-time codes, matching sees " +
            "them as \"${OneTimeCodes.MASK}\" (and the result has codes_hidden=true).",
        scope = Scopes.SCREEN_READ,
        risk = Risk.READ,
        schema = objectSchema {
            string("text", "Text to wait for (contains, case-insensitive)")
            string("package", "App id to wait for in the foreground (Android package or iOS bundle id)")
            integer("timeout_ms", "Max wait (default 5000, max 30000)", minimum = 0, maximum = 30_000)
        },
        timeoutMs = 40_000,
    ) { args, ctx ->
        val key = args.optString("text")?.takeIf { it.isNotBlank() }?.let(TextNorm::key)
        val pkg = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }
        if (key == null && pkg == null) throw ToolException("Pass text and/or package to wait for.")
        val timeout = (args.optLong("timeout_ms") ?: 5_000L).coerceIn(0L, 30_000L)
        pkg?.let { if (!ops.visibility(ctx).allows(it)) ops.refuse(ctx, it) }
        val start = ops.timing.clock()
        while (true) {
            val capture = ops.capture(ctx)
            val pkgOk = pkg == null || pkg.equals(capture.foreground, ignoreCase = true)
            val match = key?.let { k -> capture.visibleNodes().firstOrNull { Queries.textLevel(it.el, k) != MatchLevel.NONE } }
            val elapsed = ops.timing.clock() - start
            if (capture.blockedForeground == null && pkgOk && (key == null || match != null)) {
                val ref = match?.let { ops.refs(ctx).register(it) }
                val line = match?.let { ScreenFormat.formatNode(it.el, ref!!) }
                return@tool ToolResult.json {
                    put("found", true)
                    put("package", capture.foreground)
                    put("waited_ms", elapsed)
                    ref?.let { put("ref", it) }
                    line?.let { put("element", it) }
                    if (capture.codesHidden && line?.contains(OneTimeCodes.MASK) == true) put("codes_hidden", true)
                }
            }
            if (elapsed >= timeout) {
                capture.blockedForeground?.let { ops.refuse(ctx, it) }
                return@tool ToolResult.json {
                    put("found", false)
                    put("package", capture.foreground)
                    put("waited_ms", elapsed)
                }
            }
            delay(minOf(ops.timing.pollMs, timeout - elapsed).coerceAtLeast(1))
            ctx.ensureStillAllowed()
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    // ---- actions --------------------------------------------------------------------------------

    /**
     * An action tool's frame: the gesture lock, a capture to act on (refused while the phone is locked, unless
     * [allowLocked], or a blocked app is in front), a content-free activity line, then [block] (which reports with
     * [ScreenOps.report]); `observe=true` appends a fresh screen_observe.
     */
    private suspend fun action(
        args: JsonObject,
        ctx: CallContext,
        activity: String,
        allowLocked: Boolean = false,
        block: suspend (before: Capture) -> String,
    ): ToolResult {
        val observe = args.optBool("observe") ?: false
        val message = ops.exclusive(ctx) {
            val before = ops.capture(ctx)
            if (before.locked && !allowLocked) throw ToolException(ScreenTexts.LOCKED)
            before.blockedForeground?.let { ops.refuse(ctx, it) }
            ctx.reportActivity(activity)
            block(before)
        }
        if (!observe) return ToolResult.text(message)
        if (Scopes.SCREEN_READ !in ctx.scopes) return ToolResult.text("$message (observe=true was ignored: this agent may not read the screen.)")
        val observation = try {
            observe(ctx, ObserveOptions())
        } catch (e: ToolException) {
            return ToolResult.text("$message (Could not observe the screen afterwards: ${e.message})")
        }
        return ToolResult(listOf(ToolContent.Text(message)) + observation)
    }

    /** [raw] resolved among the calling agent's refs against [capture]; refused when it points into a blocked app. */
    private suspend fun resolve(ctx: CallContext, raw: String, capture: Capture): Pair<String, Node> {
        val entry = ops.refs(ctx).lookup(raw)
        entry.node.owner?.let { if (!ops.visibility(ctx).allows(it)) ops.refuse(ctx, it, "Element ${entry.ref} belongs to a blocked app.") }
        return entry.ref to RefRegistry.locate(entry, capture)
    }

    /** Screen points of ([x], [y]) in [space]: for screenshots, through the calling agent's last one. */
    private fun toScreen(ctx: CallContext, space: PointSpace, x: Int, y: Int, capture: Capture): Pair<Int, Int> {
        if (space == PointSpace.SCREEN) return x to y
        val shot = ops.state.lastScreenshot(ctx.tokenId)
            ?: throw ToolException("space=screenshot needs a screenshot first: call screen_observe or screen_screenshot.")
        if (!shot.matches(capture.snapshot.screen)) {
            throw ToolException("The screen changed size since your last screenshot (rotated). Call screen_observe again.")
        }
        return shot.toScreen(x, y) ?: throw ToolException("Point ($x,$y) is outside your last screenshot (${shot.imageWidth}x${shot.imageHeight}).")
    }

    /** Error text listing up to [MAX_CANDIDATES] candidates with refs (registered among the calling agent's refs). */
    private fun ambiguity(ctx: CallContext, prefix: String, candidates: List<Node>, noun: String = "different elements"): String {
        val refs = ops.refs(ctx)
        val lines = candidates.take(MAX_CANDIDATES).joinToString("\n") { ScreenFormat.formatNode(it.el, refs.register(it)) }
        val more = if (candidates.size > MAX_CANDIDATES) "\n... and ${candidates.size - MAX_CANDIDATES} more" else ""
        return "$prefix ${candidates.size} $noun. Retry with one of these refs:\n$lines$more"
    }

    // ---- ui_tap ---------------------------------------------------------------------------------

    private fun tapTool(): Tool = tool(
        name = "ui_tap",
        title = "Tap on screen",
        description = "Taps on the phone's screen, in any app. Target, in order of preference: ref (n12 from " +
            "screen_observe/screen_read/screen_find), text (visible label or value, case-insensitive: exact match beats " +
            "starts-with beats contains; if several elements match equally you get the candidates with refs), or a point " +
            "x+y: screen points, or pixels of your last screenshot with space=screenshot. long_press=true touches and holds. " +
            "Returns what changed on screen and the foreground app (observe=true adds a new screen_observe). Refused while " +
            "the phone is locked and in apps the user blocked for this agent.",
        scope = Scopes.SCREEN_CONTROL,
        risk = Risk.ACTION,
        schema = objectSchema {
            string("ref", "Element ref from screen_observe/screen_read/screen_find, e.g. n12")
            string("text", "Visible label or value of the element")
            integer("x", "x of the point (with y)", minimum = 0)
            integer("y", "y of the point (with x)", minimum = 0)
            string("space", "What x,y are measured in (default screen)", enum = PointSpace.NAMES)
            boolean("long_press", "Touch and hold instead of a tap")
            boolean("observe", ScreenTexts.OBSERVE_ARG)
        },
        timeoutMs = 30_000,
        describe = { args ->
            val verb = if (args.optBool("long_press") == true) "Long-press" else "Tap"
            when {
                args.optString("ref") != null -> "$verb element ${args.optString("ref")} on screen"
                args.optString("text") != null -> "$verb \"${args.optString("text")}\" on screen"
                else -> "$verb the screen at (${args.optInt("x")},${args.optInt("y")})" +
                    if (args.optString("space")?.lowercase() == "screenshot") " of the screenshot" else ""
            }
        },
    ) { args, ctx ->
        val ref = args.optString("ref")?.trim()
        val text = args.optString("text")?.takeIf { it.isNotBlank() }
        val x = args.optInt("x")
        val y = args.optInt("y")
        val longPress = args.optBool("long_press") ?: false
        if (ref == null && text == null && (x == null || y == null)) {
            throw ToolException("Pass ref (from screen_observe or screen_read), text, or both x and y.")
        }
        val space = PointSpace.parse(args.optString("space"))
        action(args, ctx, if (longPress) "Long-pressing on the screen" else "Tapping on the screen") { before ->
            val message = when {
                ref != null -> resolve(ctx, ref, before).let { (r, node) -> tapNode(ctx, before, r, node, longPress) }
                text != null -> tapText(ctx, before, text, longPress)
                else -> {
                    val (sx, sy) = toScreen(ctx, space, x!!, y!!, before)
                    tapPoint(ctx, before, sx, sy, longPress)
                }
            }
            ops.report(ctx, message, before, ops.settled(ctx, until = { ops.changed(before, it) }))
        }
    }

    private suspend fun tapText(ctx: CallContext, before: Capture, text: String, longPress: Boolean): String =
        when (val target = Queries.resolveTapText(before.visibleNodes(), text)) {
            TextTarget.NotFound -> throw ToolException(
                "No visible element shows text matching \"$text\" (app=${before.foreground ?: "unknown"}). Call screen_observe " +
                    "to see what is on screen, or ui_scroll to reveal more.",
            )
            is TextTarget.Ambiguous -> throw ToolException(ambiguity(ctx, "\"$text\" matches", target.candidates))
            is TextTarget.Found -> tapNode(ctx, before, ops.refs(ctx).register(target.node), target.node, longPress)
        }

    /** Taps the center of the visible part of [node]. */
    private suspend fun tapNode(ctx: CallContext, before: Capture, ref: String, node: Node, longPress: Boolean): String {
        val label = "$ref ${ScreenFormat.label(node.el)}"
        if (!node.el.enabled) {
            throw ToolException("Element $label is disabled right now (it may need other input first). Call screen_observe to check.")
        }
        val area = node.visibleBounds
        if (area.isEmpty) {
            throw ToolException("Element $label is not visible on screen, so it can't be tapped. Scroll to it (ui_scroll), then call screen_observe again.")
        }
        touch(ctx, before, area.centerX, area.centerY, longPress)
        return "${if (longPress) "Long-pressed" else "Tapped"} $label at (${area.centerX},${area.centerY})."
    }

    private suspend fun tapPoint(ctx: CallContext, before: Capture, x: Int, y: Int, longPress: Boolean): String {
        val screen = before.screen
        if (!screen.contains(x, y)) throw ToolException("Point ($x,$y) is outside the screen (${screen.width}x${screen.height} points).")
        touch(ctx, before, x, y, longPress)
        return "${if (longPress) "Long-pressed" else "Tapped"} at ($x,$y)."
    }

    private suspend fun touch(ctx: CallContext, before: Capture, x: Int, y: Int, longPress: Boolean) {
        ops.checkPoint(ctx, before, x, y)
        ops.act(ctx) { driver.tap(x.toDouble(), y.toDouble(), if (longPress) ScreenOps.LONG_PRESS_MS else 0) }
    }

    // ---- ui_type --------------------------------------------------------------------------------

    private fun typeTool(): Tool = tool(
        name = "ui_type",
        title = "Type text",
        description = "Types text into a text field in any app (replaces its content; append=true adds to it). Target: ref of " +
            "an [editable] element (tapped first to focus it), else the field that has focus, else the only text field on " +
            "screen. An empty text clears the field. submit=true then presses the keyboard's return key (send/search/go). " +
            "Returns what the field shows afterwards. To tap a Send button instead, use ui_tap. Refused while the phone is " +
            "locked and in apps the user blocked for this agent.",
        scope = Scopes.SCREEN_CONTROL,
        risk = Risk.ACTION,
        schema = objectSchema {
            string("text", "Text to type (empty string clears the field)", required = true)
            string("ref", "Ref of the text field (default: the focused field)")
            boolean("append", "Add to the existing text instead of replacing it")
            boolean("submit", "Press the keyboard's return key afterwards")
            boolean("observe", ScreenTexts.OBSERVE_ARG)
        },
        timeoutMs = 30_000,
        describe = { args -> "Type \"${TextNorm.truncate(rawText(args) ?: "", 80)}\" into ${args.optString("ref") ?: "the focused field"}" },
    ) { args, ctx ->
        val text = rawText(args) ?: throw ToolException("Missing required argument 'text'")
        val append = args.optBool("append") ?: false
        val submit = args.optBool("submit") ?: false
        val refArg = args.optString("ref")?.trim()
        action(args, ctx, "Typing on the screen") { before ->
            val (ref, field) = resolveField(ctx, before, refArg)
            val label = "$ref ${ScreenFormat.label(field.el)}"
            if (append && field.el.secure) {
                throw ToolException("Can't append to the password field $label (its content is hidden). Pass the full text with append=false.")
            }
            if (!field.el.enabled) throw ToolException("Field $label is disabled right now (it may need other input first). Call screen_observe to check.")
            val area = field.visibleBounds
            if (area.isEmpty) throw ToolException("Field $label is not visible on screen. Scroll to it (ui_scroll), then call screen_observe again.")
            if (!field.el.focused) {
                touch(ctx, before, area.centerX, area.centerY, longPress = false)
                delay(ops.timing.focusMs)
            }
            val value = field.el.value
            val hasText = field.el.secure || (!value.isNullOrEmpty() && value != field.el.placeholder)
            if (!append && hasText) ops.act(ctx) { driver.clearText() }
            if (text.isNotEmpty()) ops.act(ctx) { driver.typeText(text) }
            var submitNote = ""
            if (submit) {
                ctx.ensureStillAllowed()
                submitNote = try {
                    driver.pressKey("return")
                    " Pressed the keyboard's return key."
                } catch (e: ScreenException) {
                    " Submit FAILED: ${e.message?.trimEnd('.')}. Tap the app's Send/Search button instead (screen_find)."
                }
            }
            val after = ops.settled(ctx)
            val shown = try {
                RefRegistry.locate(RefRegistry.Entry(ref, field, Fingerprint.of(field.el)), after)
            } catch (e: ToolException) {
                null
            }
            val shownNote = when {
                shown == null -> " The field is no longer on screen."
                field.el.secure -> ""
                shown.el.value.isNullOrEmpty() || shown.el.value == shown.el.placeholder -> " The field is now empty."
                else -> " Field now shows: \"${TextNorm.truncate(TextNorm.clean(shown.el.value), 120)}\"."
            }
            // Submitting can change the screen (a search runs, a message goes out): report what it did.
            ops.report(ctx, "Typed into $label.$shownNote$submitNote", if (submit) before else null, after)
        }
    }

    /** `text` allowing the empty string (clears the field); null when absent. */
    private fun rawText(args: JsonObject): String? = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: args.optString("text")

    /** The field: [ref] (or a text field inside it), else the focused field, else the only one on screen. */
    private suspend fun resolveField(ctx: CallContext, capture: Capture, ref: String?): Pair<String, Node> {
        if (ref != null) {
            val (r, live) = resolve(ctx, ref, capture)
            if (live.el.editable) return r to live
            val inner = Queries.editableWithin(live)
                ?: throw ToolException("Element $r (${ScreenFormat.label(live.el)}) is not a text field. Pass the ref of an [editable] element.")
            return ops.refs(ctx).register(inner) to inner
        }
        return when (val choice = Queries.chooseEditable(capture.visibleNodes().toList())) {
            is EditableChoice.Found -> ops.refs(ctx).register(choice.node) to choice.node
            is EditableChoice.Ambiguous ->
                throw ToolException(ambiguity(ctx, "No field has focus and there are", choice.candidates, noun = "text fields"))
            EditableChoice.None -> throw ToolException(
                "There is no text field on screen (app=${capture.foreground ?: "unknown"}). Open the screen with the field first; " +
                    "screen_observe shows [editable] elements.",
            )
        }
    }

    // ---- ui_scroll / ui_swipe -------------------------------------------------------------------

    private fun scrollTool(): Tool = tool(
        name = "ui_scroll",
        title = "Scroll",
        description = "Scrolls a list or page in any app. direction is where you want to move in the content: 'down' shows " +
            "what is further down (like a mouse wheel), 'right' shows what is further right. Target: ref of a [scrollable] " +
            "element (or any element inside one), default the largest scrollable on screen. Says so when the content did " +
            "not move (end reached). Refs of the scrolled content may change: call screen_observe afterwards (or pass " +
            "observe=true). Refused while the phone is locked and in apps the user blocked for this agent.",
        scope = Scopes.SCREEN_CONTROL,
        risk = Risk.ACTION,
        schema = objectSchema {
            string("direction", "Where to move in the content", required = true, enum = ScrollDirection.entries.map { it.id })
            string("ref", "Ref of the scrollable element (default: the largest scrollable)")
            boolean("observe", ScreenTexts.OBSERVE_ARG)
        },
        timeoutMs = 30_000,
        describe = { args -> "Scroll ${args.optString("direction") ?: ""} on screen" },
    ) { args, ctx ->
        val raw = args.reqString("direction")
        val direction = ScrollDirection.entries.firstOrNull { it.id == raw.trim().lowercase() }
            ?: throw ToolException("direction must be one of up, down, left, right.")
        val ref = args.optString("ref")?.trim()
        action(args, ctx, "Scrolling on the screen") { before ->
            val container: Pair<String, Node>? = if (ref != null) {
                val (r, live) = resolve(ctx, ref, before)
                when {
                    live.el.scrollable -> r to live
                    // A ref inside a list scrolls that list; a ref with no scrollable ancestor is swiped directly.
                    else -> live.ancestors().firstOrNull { it.el.scrollable }?.let { ops.refs(ctx).register(it) to it } ?: (r to live)
                }
            } else {
                val node = Queries.largestScrollable(before.primaryRoot?.walk().orEmpty()) ?: Queries.largestScrollable(before.allNodes())
                node?.let { ops.refs(ctx).register(it) to it }
            }
            val message = ops.scrollWith(ctx, before, direction, container)
            ops.report(
                ctx, message, before, ops.settled(ctx, until = { ops.changed(before, it) }),
                noChange = "The content did not move: it is already at the end in that direction, or this area does not scroll that way.",
            )
        }
    }

    private fun swipeTool(): Tool = tool(
        name = "ui_swipe",
        title = "Swipe",
        description = "Drags a finger from (x1,y1) to (x2,y2) over duration_ms (default 300); points are screen points, or " +
            "pixels of your last screenshot with space=screenshot. Returns what changed on screen. Use for carousels, " +
            "sliders, pull-to-refresh, dismissing items and edge gestures (on iPhone, a swipe right from the left edge " +
            "goes back in most apps); for lists prefer ui_scroll. Refused while the phone is locked and in apps the user " +
            "blocked for this agent.",
        scope = Scopes.SCREEN_CONTROL,
        risk = Risk.ACTION,
        schema = objectSchema {
            integer("x1", "Start x", required = true, minimum = 0)
            integer("y1", "Start y", required = true, minimum = 0)
            integer("x2", "End x", required = true, minimum = 0)
            integer("y2", "End y", required = true, minimum = 0)
            integer("duration_ms", "Duration (default 300)", minimum = MIN_SWIPE_MS, maximum = MAX_SWIPE_MS)
            string("space", "What the points are measured in (default screen)", enum = PointSpace.NAMES)
            boolean("observe", ScreenTexts.OBSERVE_ARG)
        },
        timeoutMs = 30_000,
        describe = { args -> "Swipe from (${args.optInt("x1")},${args.optInt("y1")}) to (${args.optInt("x2")},${args.optInt("y2")})" },
    ) { args, ctx ->
        val space = PointSpace.parse(args.optString("space"))
        val duration = (args.optLong("duration_ms") ?: 300L).coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        val p1 = args.reqInt("x1") to args.reqInt("y1")
        val p2 = args.reqInt("x2") to args.reqInt("y2")
        action(args, ctx, "Swiping on the screen") { before ->
            val (x1, y1) = toScreen(ctx, space, p1.first, p1.second, before)
            val (x2, y2) = toScreen(ctx, space, p2.first, p2.second, before)
            val screen = before.screen
            if (!screen.contains(x1, y1) || !screen.contains(x2, y2)) {
                throw ToolException("Swipe points must be on the screen (0..${screen.width - 1}, 0..${screen.height - 1}).")
            }
            // The whole gesture goes to the window under its starting point.
            ops.checkPoint(ctx, before, x1, y1)
            ops.act(ctx) { driver.swipe(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), duration) }
            ops.report(ctx, "Swiped from ($x1,$y1) to ($x2,$y2) in ${duration}ms.", before, ops.settled(ctx, until = { ops.changed(before, it) }))
        }
    }

    // ---- ui_global_action -----------------------------------------------------------------------

    private fun globalActionTool(): Tool {
        val supported = GLOBAL_ACTIONS.filter { it in driver.globalActions } + (driver.globalActions - GLOBAL_ACTIONS.toSet()).sorted()
        return tool(
            name = "ui_global_action",
            title = "System navigation",
            description = "Performs a system navigation action. Supported on this phone: ${supported.joinToString()}" +
                (if (driver.os == DeviceOs.IOS) " (iPhones have no system Back button: use the app's own back control or ui_swipe from the left edge)" else "") +
                ". Refused while the phone is locked and while an app the user blocked for this agent is in the foreground.",
            scope = Scopes.SCREEN_CONTROL,
            risk = Risk.ACTION,
            schema = objectSchema {
                string("action", "The action", required = true, enum = GLOBAL_ACTIONS)
                boolean("observe", ScreenTexts.OBSERVE_ARG)
            },
            timeoutMs = 15_000,
            describe = { args -> "Press ${args.optString("action") ?: "a system button"}" },
        ) { args, ctx ->
            val raw = args.reqString("action")
            val name = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
            if (name !in GLOBAL_ACTIONS && name !in driver.globalActions) {
                throw ToolException("Unknown action '$raw'. Use one of: ${supported.joinToString()}.")
            }
            if (name !in driver.globalActions) throw ToolException(unsupportedAction(name, supported))
            action(args, ctx, "Pressing ${name.replace('_', ' ')}", allowLocked = name == "lock_screen") { before ->
                if (before.locked) return@action "The phone is already locked."
                ops.act(ctx) { driver.globalAction(name) }
                // System navigation leaves the app or locks the phone: wait for that, not the first status-bar tick.
                val after = ops.settled(ctx, attempts = ops.timing.settleAttempts) { it.foreground != before.foreground || it.locked != before.locked }
                ops.report(ctx, "Performed '$name'.", before, after)
            }
        }
    }

    /** Why [name] can't be done on this device, with what to do instead. */
    private fun unsupportedAction(name: String, supported: List<String>): String {
        val instead = if (driver.os == DeviceOs.IOS) {
            when (name) {
                "back" -> " iPhones have no system Back button: tap the app's own back control (usually at the top left; " +
                    "screen_observe lists it), or swipe right from the left edge of the screen with ui_swipe."
                "notifications" -> " To open Notification Center, swipe down from the top edge, left of center, with ui_swipe."
                "quick_settings" -> " To open Control Center, swipe down from the top right corner with ui_swipe."
                else -> ""
            }
        } else {
            ""
        }
        return "'$name' is not available on this phone (${driver.os.id}).$instead Supported here: ${supported.joinToString().ifEmpty { "none" }}."
    }

    companion object {
        private const val MAX_ELEMENT_TEXT = 120
        private const val DEFAULT_MAX_NODES = 300
        private const val MAX_MAX_NODES = 2000
        private const val MAX_FIND_RESULTS = 50
        private const val MAX_CANDIDATES = 10
        private const val READ_ATTEMPTS = 4
        private const val MIN_SWIPE_MS = 50L
        private const val MAX_SWIPE_MS = 10_000L
    }
}
