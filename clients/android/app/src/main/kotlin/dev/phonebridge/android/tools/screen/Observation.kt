package dev.phonebridge.android.tools.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.ScreenshotException
import dev.phonebridge.android.access.SerializeOptions
import dev.phonebridge.android.access.TreeSerializer
import dev.phonebridge.android.tools.support.DeviceState
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
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
import dev.phonebridge.core.util.OneTimeCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** What [Observation.observe] includes. */
internal data class ObserveOptions(
    val screenshot: Boolean = true,
    val elements: Elements = Elements.ACTIONABLE,
    val maxDimension: Int = ScreenshotMath.DEFAULT_MAX_DIMENSION,
    val maxElements: Int = DEFAULT_MAX_ELEMENTS,
) {
    enum class Elements { ACTIONABLE, ALL, NONE }

    companion object {
        const val DEFAULT_MAX_ELEMENTS = 80
        const val MAX_MAX_ELEMENTS = 300

        /** Options from a screen_observe call's arguments (or an action tool's `observe`, which takes the defaults). */
        fun parse(args: JsonObject): ObserveOptions = ObserveOptions(
            screenshot = args.optBool("screenshot") ?: true,
            elements = when (args.optString("elements")?.trim()?.lowercase()) {
                null, "actionable" -> Elements.ACTIONABLE
                "all" -> Elements.ALL
                "none" -> Elements.NONE
                else -> throw ToolException("elements must be one of actionable, all, none.")
            },
            maxDimension = (args.optInt("max_dimension") ?: ScreenshotMath.DEFAULT_MAX_DIMENSION)
                .coerceIn(ScreenshotMath.MIN_MAX_DIMENSION, ScreenshotMath.MAX_MAX_DIMENSION),
            maxElements = (args.optInt("max_elements") ?: DEFAULT_MAX_ELEMENTS).coerceIn(1, MAX_MAX_ELEMENTS),
        )
    }
}

/**
 * "Phone use": one look at the screen the way an agent can act on it, for any app on any phone. The accessibility tree
 * gives the actionable elements (with refs for ui_tap / ui_type / ui_scroll); the screenshot shows everything, with each
 * listed element boxed and numbered. Whatever one of them can't provide (a screen that blocks screenshots, an app that
 * exposes nothing to accessibility) is said plainly, so the agent picks the other.
 *
 * Applies the screen tools' rules: app access, PhoneBridge's own screens withheld, one-time codes masked in text and
 * screenshots refused while one is visible ([requireShareable]).
 */
internal object Observation {
    private const val SETTLE_ATTEMPTS = 4
    private const val SETTLE_DELAY_MS = 350L

    suspend fun observe(context: Context, ctx: CallContext, options: ObserveOptions): List<ToolContent> {
        val service = UiAutomation.service()
        val access = ScreenAccess.of(context, ctx)
        val state = captureSettled(context, service, access, includeAll = options.elements == ObserveOptions.Elements.ALL)
        state.blockedForeground?.let { access.refuse(it) }
        val screen = service.screenBounds()
        val marks = if (options.elements == ObserveOptions.Elements.NONE) {
            emptyList()
        } else {
            ScreenMarks.select(state.windows, screen, options.maxElements)
        }
        // One snapshot: the tree (elements=all) and the marks share refs, and older refs stop working, as with screen_read.
        val listing = ScreenSession.refs(access).newSnapshot { register ->
            val tree = if (options.elements == ObserveOptions.Elements.ALL) {
                TreeSerializer.serialize(state.windows, SerializeOptions(includeAll = true, maxNodes = options.maxElements * 4), register).text
            } else {
                null
            }
            Listing(tree, marks.map { register(it.node) to it })
        }
        val notes = ArrayList<String>()
        if (!state.screenOn) notes += "The screen is off."
        notes += state.notes
        if (ScreenMarks.thinTree(state.windows)) {
            notes += "This app exposes almost nothing to accessibility (common for games, Flutter, maps and video): work " +
                "from the screenshot and tap points with ui_tap x,y space=screenshot."
        }
        val image = if (options.screenshot) screenshot(context, service, access, listing.marks, options.maxDimension, notes) else null
        val text = buildString {
            append(TreeSerializer.header(state.packageName, state.activity, state.locked))
            image?.let { append('\n').append(it.caption) }
            notes.forEach { append('\n').append(it) }
            when {
                listing.tree != null -> append("\nScreen tree (use the refs with ui_tap, ui_type, ui_scroll):\n").append(listing.tree.ifEmpty { "(nothing readable)" })
                options.elements == ObserveOptions.Elements.NONE -> Unit
                listing.marks.isEmpty() -> append("\nNo actionable elements are exposed on this screen.")
                else -> {
                    append("\nActionable elements (tap with ui_tap ref=n12; ")
                    append(if (image != null) "the number in each box on the image is its ref" else "bounds are screen pixels")
                    append("):")
                    for ((ref, mark) in listing.marks) {
                        append('\n').append(TreeSerializer.formatNode(mark.node, ref, MAX_ELEMENT_TEXT))
                    }
                    if (listing.marks.size >= options.maxElements) append("\n... more elements not listed (raise max_elements, or elements=all)")
                }
            }
        }
        return listOfNotNull(image?.content, ToolContent.Text(text))
    }

    private class Listing(val tree: String?, val marks: List<Pair<String, ScreenMarks.Mark>>)

    private class Shot(val content: ToolContent.Image, val caption: String)

    /** The marked screenshot, or null with the reason added to [notes] when it can't be taken or shared. */
    private suspend fun screenshot(
        context: Context,
        service: BridgeAccessibilityService,
        access: ScreenAccess,
        marks: List<Pair<String, ScreenMarks.Mark>>,
        maxDimension: Int,
        notes: MutableList<String>,
    ): Shot? {
        if (!DeviceState.isScreenOn(context)) {
            notes += "No screenshot: the screen is off."
            return null
        }
        val bitmap = try {
            requireShareable(context, service, access)
            service.takeScreenshotBitmap().also {
                try {
                    requireShareable(context, service, access)
                } catch (e: ToolException) {
                    it.recycle()
                    throw e
                }
            }
        } catch (e: ToolException) {
            notes += "No screenshot: ${e.message}"
            return null
        } catch (e: ScreenshotException) {
            notes += "No screenshot: ${e.message}"
            return null
        }
        val screenWidth = bitmap.width
        val encoded = withContext(Dispatchers.Default) {
            encode(bitmap, maxDimension, png = false) { image -> MarkPainter.paint(image, screenWidth, marks) }
        }
        ScreenSession.recordScreenshot(access.tokenId, encoded.geometry)
        val caption = buildString {
            append("Screenshot ${encoded.width}x${encoded.height} (screen ${encoded.sourceWidth}x${encoded.sourceHeight} px). ")
            append(coordinateHint(encoded)).append('.')
            if (encoded.mostlyBlack) {
                append(" The image is almost entirely black: this screen blocks screenshots (FLAG_SECURE) or is dark; rely on the elements.")
            }
        }
        return Shot(ToolContent.Image(Base64.encodeToString(encoded.bytes, Base64.NO_WRAP), "image/jpeg"), caption)
    }

    /**
     * A capture of what the agent may see; right after an app switch the new window often has no content yet, so it
     * waits briefly and captures again (as screen_read does).
     */
    private suspend fun captureSettled(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, includeAll: Boolean): ScreenState {
        var state = ScreenCapture.capture(context, service, access, includeAll)
        var attempt = 1
        while (state.blockedForeground == null && state.screenOn && attempt < SETTLE_ATTEMPTS &&
            (state.roots.none { root -> root.walk().any { it.visible && TreeSerializer.isInformative(it) } } || ScreenSettling.appWindowMissing(state))
        ) {
            delay(SETTLE_DELAY_MS)
            attempt++
            state = ScreenCapture.capture(context, service, access, includeAll)
        }
        return state
    }

    private const val MAX_ELEMENT_TEXT = 120
}

/** Draws numbered boxes over a scaled screenshot (Android graphics; the choice of boxes is [ScreenMarks]). */
internal object MarkPainter {
    private val COLORS = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00897B.toInt())

    /** [image] is the screenshot scaled from [screenWidth] screen pixels wide. */
    fun paint(image: Bitmap, screenWidth: Int, marks: List<Pair<String, ScreenMarks.Mark>>) {
        if (marks.none { it.second.drawn }) return
        val scale = image.width.toFloat() / screenWidth
        val canvas = Canvas(image)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(2f, image.width / 360f) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textSize = maxOf(11f, image.width / 34f)
        }
        val pad = text.textSize / 5
        marks.filter { it.second.drawn }.forEachIndexed { i, (ref, mark) ->
            val color = COLORS[i % COLORS.size]
            val box = RectF(mark.box.left * scale, mark.box.top * scale, mark.box.right * scale, mark.box.bottom * scale)
            stroke.color = color
            canvas.drawRect(box, stroke)
            val tag = ScreenMarks.tag(ref)
            val w = text.measureText(tag) + 2 * pad
            val h = text.textSize + 2 * pad
            // Tag at the box's top-left corner, inside the image.
            val left = box.left.coerceIn(0f, (image.width - w).coerceAtLeast(0f))
            val top = (box.top - h).takeIf { it >= 0f } ?: box.top.coerceAtMost((image.height - h).coerceAtLeast(0f))
            fill.color = color
            canvas.drawRect(left, top, left + w, top + h, fill)
            canvas.drawText(tag, left + pad, top + h - pad - text.descent() / 2, text)
        }
    }
}

/** screen_observe: the screen as a marked screenshot plus its actionable elements (docs/TOOLS.md "Screen"). */
internal fun screenObserveTool(context: Context): Tool = tool(
    name = "screen_observe",
    title = "Look at the screen",
    description = "Phone use: shows what is on the phone's screen in any app and how to act on it, in one call. Returns a " +
        "screenshot with every actionable element boxed and numbered, plus those elements as lines " +
        "`n12 Button \"Send\" [clickable] (x,y,w,h)` (bounds in screen pixels). Act with ui_tap ref=n12 / ui_type / ui_scroll, " +
        "or tap any point of the image with ui_tap x,y space=screenshot. Refs are yours alone and are replaced by your next " +
        "screen_observe or screen_read. Every ui_* action reports what changed on screen; pass observe=true to get a new " +
        "screen_observe with it. When the screen blocks screenshots (banking apps, PhoneBridge approvals), the result says " +
        "why and still lists the elements; when an app exposes nothing to accessibility (games, Flutter), it says so and the " +
        "screenshot is what you work from. elements=all gives the full tree like screen_read; screenshot=false skips the " +
        "image. Apps the user blocked for this agent are left out and refused in the foreground; when the user hides " +
        "one-time codes they appear as \"${OneTimeCodes.MASK}\" and no screenshot is taken while one is visible.",
    scope = Scopes.SCREEN_READ,
    risk = Risk.READ,
    schema = objectSchema {
        boolean("screenshot", "Include the marked screenshot (default true)")
        string("elements", "Which elements to list (default actionable)", enum = listOf("actionable", "all", "none"))
        integer(
            "max_dimension", "Longest side of the image in pixels (default ${ScreenshotMath.DEFAULT_MAX_DIMENSION})",
            minimum = ScreenshotMath.MIN_MAX_DIMENSION.toLong(), maximum = ScreenshotMath.MAX_MAX_DIMENSION.toLong(),
        )
        integer(
            "max_elements", "Maximum number of elements listed (default ${ObserveOptions.DEFAULT_MAX_ELEMENTS})",
            minimum = 1, maximum = ObserveOptions.MAX_MAX_ELEMENTS.toLong(),
        )
    },
    timeoutMs = 30_000,
    unavailable = { UiAutomation.unavailableReason() },
) { args, ctx ->
    ToolResult(Observation.observe(context, ctx, ObserveOptions.parse(args)))
}
