package com.droidhands.core.screen

import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.plugins.DeviceOs
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.util.OneTimeCodes

/**
 * The home screen and system-alert processes of each OS: not apps a user picks in an allowlist, so an allowlist must
 * not lock an agent out of the home screen or out of a permission alert an allowed app triggered. Blocking one
 * explicitly (never-allow list, blocklist) still blocks it.
 */
fun defaultSystemSurfaces(os: DeviceOs): Set<String> = when (os) {
    DeviceOs.IOS -> setOf("com.apple.springboard")
    DeviceOs.ANDROID -> setOf("com.android.systemui")
}

/**
 * One element of a captured tree with its place in it: [owner] is the app whose window it is in, [path] its position
 * (`0.3.1`: window 0, child 3, child 1). Visible means its bounds overlap [screen].
 */
internal class Node(val el: UiElement, val owner: String?, val parent: Node?, val path: String, private val screen: Rect) {
    val children: List<Node> by lazy { el.children.mapIndexed { i, c -> Node(c, owner, this, "$path.$i", screen) } }

    val bounds: Rect get() = el.bounds

    /** The on-screen part of [bounds]. */
    val visibleBounds: Rect get() = el.bounds.intersect(screen)

    val visible: Boolean get() = !visibleBounds.isEmpty

    /** Identity within one capture (and across captures of an unchanged screen). */
    val key: String get() = "$owner|$path"

    fun walk(): Sequence<Node> = sequence {
        yield(this@Node)
        for (c in children) yieldAll(c.walk())
    }

    fun ancestors(): Sequence<Node> = generateSequence(parent) { it.parent }

    override fun toString(): String = "Node(${ScreenFormat.label(el)} $path)"
}

/** A window the agent may see: the foreground app's ([primary]) or a system overlay. */
internal class Window(val owner: String?, val root: Node, val primary: Boolean, val title: String)

/**
 * Which apps one agent may see and operate (docs/SECURITY.md "App access"), with [systemSurfaces] exempt unless the
 * user blocked them by name (see [defaultSystemSurfaces]). Unknown (null) apps are allowed.
 */
internal class Visibility(val access: AppAccess, private val systemSurfaces: Set<String>) {
    val restricted: Boolean get() = access.isRestricted

    fun allows(pkg: String?): Boolean {
        if (!restricted || pkg.isNullOrEmpty()) return true
        if (pkg in systemSurfaces && !explicitlyBlocked(pkg)) return true
        return access.isAllowed(pkg)
    }

    private fun explicitlyBlocked(pkg: String): Boolean =
        pkg in access.neverAllow || (access.mode == AppAccessMode.BLOCKLIST && pkg in access.packages)
}

/** One capture of everything an agent may see on screen (one-time codes masked when the user hides them). */
internal class Capture(
    val snapshot: ScreenSnapshot,
    /** Primary (the foreground app's tree) first, then system overlays, all allowed for this agent. */
    val windows: List<Window>,
    /** The foreground app when it is blocked for this agent: nothing of it was captured. */
    val blockedForeground: String?,
    /** Overlays of apps blocked for this agent, left out of [windows]. */
    val blockedOverlays: List<ScreenSnapshot.Overlay>,
    /** Some visible text was masked because the user hides one-time codes. */
    val codesHidden: Boolean,
    /** A visible element (before masking) shows a one-time code. */
    val codeVisible: Boolean,
) {
    val foreground: String? get() = snapshot.foregroundApp
    val screen: Rect = Rect(0, 0, snapshot.screen.width, snapshot.screen.height)
    val locked: Boolean get() = snapshot.locked
    val roots: List<Node> get() = windows.map { it.root }
    val primaryRoot: Node? get() = windows.firstOrNull { it.primary }?.root

    fun allNodes(): Sequence<Node> = roots.asSequence().flatMap { it.walk() }

    fun visibleNodes(): Sequence<Node> = allNodes().filter { it.visible }

    /** Notes for the agent about what this capture leaves out or masks, one per line, then the driver's. */
    val notes: List<String>
        get() = buildList {
            if (blockedOverlays.isNotEmpty()) add("(${blockedOverlays.size} window(s) of apps blocked for this agent are not shown)")
            if (codesHidden) add(ScreenTexts.CODES_NOTE)
            addAll(snapshot.notes)
        }

    companion object {
        fun of(snapshot: ScreenSnapshot, visibility: Visibility, redactor: ((String) -> String)?): Capture {
            val screen = Rect(0, 0, snapshot.screen.width, snapshot.screen.height)
            val fg = snapshot.foregroundApp
            if (fg != null && !visibility.allows(fg)) {
                return Capture(snapshot, emptyList(), fg, emptyList(), codesHidden = false, codeVisible = false)
            }
            val (overlays, blocked) = snapshot.overlays.partition { visibility.allows(it.owner) }
            val raw = buildList {
                snapshot.root?.let { add(Triple(fg, it, true)) }
                overlays.forEach { add(Triple(it.owner, it.root, false)) }
            }
            val codeVisible = redactor != null && raw.any { (_, root, _) -> anyVisibleText(root, screen, OneTimeCodes::containsCode) }
            var codesHidden = false
            val windows = raw.mapIndexed { i, (owner, root, primary) ->
                val shown = if (redactor == null) root else redact(root, redactor)
                if (shown !== root) codesHidden = true
                val node = Node(shown, owner, null, "$i", screen)
                Window(owner, node, primary, if (primary) owner ?: "app" else overlayTitle(owner, shown))
            }
            return Capture(snapshot, windows, null, blocked, codesHidden, codeVisible)
        }

        private fun overlayTitle(owner: String?, root: UiElement): String {
            val title = root.walk().mapNotNull { e -> TextNorm.clean(e.label).trim().takeIf { it.isNotEmpty() } }.firstOrNull()
            return when {
                title != null && owner != null -> "${TextNorm.truncate(title, 80)} ($owner)"
                title != null -> TextNorm.truncate(title, 80)
                else -> owner ?: "overlay"
            }
        }

        /** [e] with [redact] applied to every label, value and placeholder; [e] itself when nothing changed. */
        fun redact(e: UiElement, redact: (String) -> String): UiElement {
            val children = e.children.map { redact(it, redact) }
            val childrenChanged = children.indices.any { children[it] !== e.children[it] }
            val label = e.label?.let(redact)
            val value = e.value?.let(redact)
            val placeholder = e.placeholder?.let(redact)
            if (!childrenChanged && label == e.label && value == e.value && placeholder == e.placeholder) return e
            return e.copy(label = label, value = value, placeholder = placeholder, children = children)
        }

        private fun anyVisibleText(root: UiElement, screen: Rect, test: (String) -> Boolean): Boolean =
            root.walk().any { e -> !e.bounds.intersect(screen).isEmpty && listOfNotNull(e.label, e.value, e.placeholder).any(test) }
    }
}

internal fun UiElement.walk(): Sequence<UiElement> = sequence {
    yield(this@walk)
    for (c in children) yieldAll(c.walk())
}

/** Outcome of resolving `ui_tap(text=...)`. */
internal sealed interface TextTarget {
    class Found(val node: Node) : TextTarget

    /** Several equally good matches, best first. */
    class Ambiguous(val candidates: List<Node>) : TextTarget

    data object NotFound : TextTarget
}

/** Outcome of picking a text field without a ref. */
internal sealed interface EditableChoice {
    class Found(val node: Node) : EditableChoice

    class Ambiguous(val candidates: List<Node>) : EditableChoice

    data object None : EditableChoice
}

/** Pure queries over captured trees (the platform-neutral counterparts of Android's NodeQueries). */
internal object Queries {
    private const val MAX_TAP_ANCESTORS = 8

    /** Best match level of [n]'s label or value against [queryKey]. */
    fun textLevel(n: UiElement, queryKey: String): MatchLevel =
        maxOf(matchLevel(queryKey, TextNorm.key(n.label)), matchLevel(queryKey, TextNorm.key(if (n.secure) null else n.value)))

    /** The element itself if actionable and enabled, else its nearest such ancestor (or null). */
    fun tapTarget(n: Node): Node? = (sequenceOf(n) + n.ancestors().take(MAX_TAP_ANCESTORS)).firstOrNull { it.el.actionable && it.el.enabled }

    /**
     * Resolves `ui_tap(text=...)`: exact > starts-with > contains on label or value (case-insensitive); among the best
     * level, tappable elements win. Matches inside the same tappable element (a label and its row) count once.
     */
    fun resolveTapText(nodes: Sequence<Node>, query: String): TextTarget {
        val q = TextNorm.key(query)
        if (q.isEmpty()) return TextTarget.NotFound
        val scored = nodes.map { it to textLevel(it.el, q) }.filter { it.second != MatchLevel.NONE }.toList()
        if (scored.isEmpty()) return TextTarget.NotFound
        val best = scored.maxOf { it.second }
        var candidates = scored.filter { it.second == best }.map { it.first }
        val tappable = candidates.filter { tapTarget(it) != null }
        if (tappable.isNotEmpty()) candidates = tappable
        val distinct = LinkedHashMap<String, Node>()
        for (c in candidates) distinct.putIfAbsent((tapTarget(c) ?: c).key, c)
        val unique = distinct.values.toList()
        return if (unique.size == 1) TextTarget.Found(unique[0]) else TextTarget.Ambiguous(unique)
    }

    /** The focused visible text field, else the only visible one. */
    fun chooseEditable(nodes: List<Node>): EditableChoice {
        nodes.firstOrNull { it.el.editable && it.el.focused }?.let { return EditableChoice.Found(it) }
        val editables = nodes.filter { it.el.editable }
        return when (editables.size) {
            0 -> EditableChoice.None
            1 -> EditableChoice.Found(editables[0])
            else -> EditableChoice.Ambiguous(editables)
        }
    }

    /** First visible text field in [n]'s subtree ([n] included). */
    fun editableWithin(n: Node): Node? = n.walk().firstOrNull { it.el.editable && it.visible }

    /** The visible scrollable with the largest on-screen area, or null. */
    fun largestScrollable(nodes: Sequence<Node>): Node? =
        nodes.filter { it.el.scrollable && it.visible }.maxByOrNull { it.visibleBounds.width.toLong() * it.visibleBounds.height }

    /** The first [limit] distinct visible labels and values, truncated (for change reports and errors). */
    fun topTexts(nodes: Sequence<Node>, limit: Int = 8, maxLength: Int = 60): List<String> {
        val out = LinkedHashSet<String>()
        for (n in nodes) {
            if (!n.visible) continue
            for (s in listOf(n.el.label, if (n.el.secure) null else n.el.value)) {
                val clean = TextNorm.clean(s).replace('\n', ' ').trim()
                if (clean.isNotEmpty()) out += TextNorm.truncate(clean, maxLength)
                if (out.size >= limit) return out.toList()
            }
        }
        return out.toList()
    }
}
